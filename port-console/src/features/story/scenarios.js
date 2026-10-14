import { actualArrival, actualDeparture, estimatedArrival, omittedCall, plannedArrival, sendEvents } from '@/api/dcsa'
import { askEta, cancelVisit, getVisits, releaseBerth } from '@/api/scheduler'
import { span, toMillis } from '@/lib/time'
import { ordinal } from '@/lib/visit'

/**
 * Stories: the berth conversation played one step at a time.
 *
 * A story is not a simulation. Each step sends real requests to the scheduler
 * (the same ones the screens send) and then says, from the answers, what
 * happened. Nothing is asserted in advance: the captions are built from
 * what the scheduler replied, so they stay true if its rules change.
 *
 * The ships are invented and placed at open-sea positions inside the area the
 * ETA model was trained on. No real AIS data is used.
 */

const HOUR = 3_600_000

const SHIPS = {
  SOLVIK: { code: 'SOLVIK', lat: 62.6, lon: 5.4, sog: 14, cog: 190, where: '60 nm to the north' },
  HAVBRIS: { code: 'HAVBRIS', lat: 62.75, lon: 6.1, sog: 15, cog: 205, where: '74 nm to the north-east' },
  FJORDLYS: { code: 'FJORDLYS', lat: 62.0, lon: 4.45, sog: 13, cog: 140, where: '29 nm to the north-west' },
  VESTKYST: { code: 'VESTKYST', lat: 61.62, lon: 4.4, sog: 13, cog: 92, where: '19 nm to the west' },
}

// Ships already at the berths when a story opens, and how long ago each arrived.
const ALONGSIDE = [
  { code: 'BREMANGER', arrivedHoursAgo: 13 },
  { code: 'KINN', arrivedHoursAgo: 10 },
  { code: 'SKORPA', arrivedHoursAgo: 7 },
  { code: 'BATALDEN', arrivedHoursAgo: 4 },
]

const knots = (value) => `${Number(value).toFixed(1)} knots`

async function visitOf(code) {
  const [visit] = await getVisits({ vessel: code })
  if (!visit) throw new Error(`${code} has no call in progress. Reset the port and start the story again.`)
  return visit
}

function stillStanding(visit) {
  return visit.berth ? `still holds ${visit.berth.berthCode}` : `is still ${ordinal(visit.queueNumber)} in the queue`
}

// ---- steps ---------------------------------------------------------------

const COUNT_WORDS = ['No', 'One', 'Two', 'Three', 'Four']

const shipsAlongside = (ships) => ({
  title: `${COUNT_WORDS[ships.length]} ships are already alongside`,
  sends: 'DCSA events: an estimated and an actual arrival for each ship',
  async run(ctx) {
    for (const ship of ships) {
      const arrived = new Date(Date.now() - ship.arrivedHoursAgo * HOUR)
      await sendEvents(
        [estimatedArrival(ship.code, arrived, 'GCGO'), actualArrival(ship.code, arrived)],
        `Story: ${ship.code} is alongside`,
      )
    }
    const visits = await getVisits()
    const ends = visits.map((v) => toMillis(v.berth?.plannedEnd)).filter((ms) => ms != null).sort((a, b) => a - b)
    const free = ctx.port.berths.length - visits.filter((v) => v.berth).length
    const opening = free > 0 ? `All but ${free === 1 ? 'one berth hold' : `${free} berths hold`}` : 'Every berth holds'
    return `${opening} a ship part-way through its stay. Each berth is planned free one typical stay after its ship arrived; the first is due at ${ctx.time(ends[0])}.`
  },
})

const berthsAlreadyTaken = shipsAlongside(ALONGSIDE)

/**
 * A sentence for each waiting ship whose requested arrival the terminal has
 * just changed. The terminal works the waiting ships' times out again whenever
 * the berth plan changes, and tells a ship only when its time really moves.
 */
function reissued(before, after, ctx, except = null) {
  const was = new Map(before.map((v) => [v.visitRef, v.rtaBerth]))
  return after
    .filter((v) => v.queueNumber != null && v.vessel.code !== except && was.has(v.visitRef) && toMillis(was.get(v.visitRef)) !== toMillis(v.rtaBerth))
    .map((v) => ` ${v.vessel.code} is now ${ordinal(v.queueNumber)} in the queue, so the terminal asks it for ${ctx.time(v.rtaBerth)} instead of ${ctx.time(was.get(v.visitRef))}.`)
    .join('')
}

const estimatesArrival = (ship) => ({
  title: `${ship.code} sends its estimated arrival, ${ship.where}`,
  sends: 'POST /api/operator/eta, then a DCSA event: estimated arrival (EST) with the position',
  async run(ctx) {
    const query = {
      vesselId: ship.code,
      lat: ship.lat,
      lon: ship.lon,
      sog: ship.sog,
      cog: ship.cog,
      destLat: ctx.destination.latitude,
      destLon: ctx.destination.longitude,
      vesselType: 'GENERAL_CARGO',
    }
    // The operator's own sum first; nothing reaches the terminal until the estimate is sent.
    const estimate = await askEta(query, `Story: ${ship.code} asks the ETA calculator`)
    await sendEvents(
      [estimatedArrival(ship.code, estimate.estimatedArrival, 'GCGO', { lat: ship.lat, lon: ship.lon, distanceNm: estimate.distanceNm })],
      `Story: ${ship.code} sends its estimated arrival`,
    )
    const visit = await visitOf(ship.code)
    const opening = `Its operator asks the ETA model, which puts the ship ${span(estimate.etaMinutes * 60_000)} away, and sends that to the terminal as its estimated arrival.`
    if (visit.berth) {
      return `${opening} ${visit.berth.berthName} is free, so it is allocated at once and the terminal asks for arrival at ${ctx.time(visit.rtaBerth)}: the ship's own estimate. There is nothing to wait for.`
    }
    // The terminal answers with a time. What speed meets it is the ship's sum, so the operator asks the calculator again.
    const speed = await askEta(query, `Story: ${ship.code} asks what speed meets the requested arrival`)
    const advice =
      speed.advisedSpeedKnots != null
        ? `The terminal sends a time, not a speed: the operator's calculator works out that slowing from ${knots(ship.sog)} to ${knots(speed.advisedSpeedKnots)} meets it, rather than hurrying to wait.`
        : "The terminal sends a time, not a speed: the operator's calculator finds no slower speed worth sailing."
    return `${opening} Every berth is taken, so it is ${ordinal(visit.queueNumber)} in the queue, and the terminal asks for arrival at ${ctx.time(visit.rtaBerth)}, when a berth is expected free. ${advice}`
  },
})

const accepts = (ship) => ({
  title: `${ship.code}'s operator accepts the requested arrival`,
  sends: 'DCSA event: planned arrival (PLN)',
  async run(ctx) {
    const visit = await visitOf(ship.code)
    await sendEvents([plannedArrival(ship.code, visit.rtaBerth)], `Story: ${ship.code} accepts the requested arrival`)
    return `The requested time, ${ctx.time(visit.rtaBerth)}, is now the planned arrival: terminal and ship have agreed it.`
  },
})

const berthReleased = (berthCode) => ({
  title: `The ship on ${berthCode} leaves early`,
  sends: `PUT /api/port/berths/${berthCode}/release`,
  async run(ctx) {
    const before = await getVisits()
    const head = before.find((v) => v.queueNumber === 1)
    await releaseBerth(berthCode, `Story: release ${berthCode}`)
    if (!head) return `${berthCode} is free. Nobody was waiting, so it stays free for the next ship to report.`
    const after = await getVisits()
    const promoted = after.find((v) => v.visitRef === head.visitRef)
    const hadAgreed = head.ptaBerth != null
    return `${berthCode} goes straight to ${promoted.vessel.code}, which had waited longest. The terminal issues a new request, ${ctx.time(promoted.rtaBerth)}${
      hadAgreed ? ', and that cancels the time agreed before: the ship has to accept again' : ''
    }.${reissued(before, after, ctx)}`
  },
})

const terminalCancels = (ship) => ({
  title: `${ship.code} has not answered, and the planner cancels its call`,
  sends: 'PUT /api/visits/{visitRef}/cancel',
  async run(ctx) {
    const before = await getVisits()
    const silent = before.find((v) => v.vessel.code === ship.code)
    if (!silent) throw new Error(`${ship.code} has no call in progress. Reset the port and start the story again.`)
    const head = before.find((v) => v.queueNumber === 1)
    await cancelVisit(silent.visitRef, 'no answer to the requested arrival', `Story: the planner cancels ${ship.code}'s call`)
    const after = await getVisits()
    const opening = `${ship.code} holds ${silent.berth ? silent.berth.berthCode : 'a place in the queue'} but never accepted the requested arrival. The terminal does not take a berth away by itself: an answer left for an hour is flagged on the planner's screen, and the planner decides. (The story does not wait the hour.) The planner cancels the call, which the feed publishes as a berth service cancelled by the terminal, not as a call omitted by the carrier.`
    const promoted = head && after.find((v) => v.visitRef === head.visitRef)
    if (!promoted?.berth) return `${opening}${reissued(before, after, ctx)}`
    const hadAgreed = head.ptaBerth != null
    return `${opening} ${promoted.berth.berthCode} goes to ${promoted.vessel.code}, with a new request for ${ctx.time(promoted.rtaBerth)}${
      hadAgreed ? ', so it has to accept again' : ''
    }.${reissued(before, after, ctx)}`
  },
})

const proposesLater = (ship, hours) => ({
  title: `${ship.code} is delayed and proposes a later time`,
  sends: 'DCSA event: estimated arrival (EST)',
  async run(ctx) {
    const before = await visitOf(ship.code)
    const proposed = new Date(toMillis(before.rtaBerth ?? before.etaBerth) + hours * HOUR)
    await sendEvents([estimatedArrival(ship.code, proposed, 'GCGO')], `Story: ${ship.code} proposes another time`)
    const after = await visitOf(ship.code)
    return `The ship says it can be there at ${ctx.time(proposed)}. The terminal replans and answers with a requested arrival of ${ctx.time(after.rtaBerth)}; ${ship.code} ${stillStanding(after)}.`
  },
})

const fallsBehind = (ship, hours) => ({
  title: `${ship.code} falls ${hours} hours behind the time it agreed`,
  sends: 'DCSA event: estimated arrival (EST)',
  async run(ctx) {
    const before = await getVisits()
    const mine = before.find((v) => v.vessel.code === ship.code)
    if (!mine) throw new Error(`${ship.code} has no call in progress. Reset the port and start the story again.`)
    const agreed = mine.ptaBerth ?? mine.rtaBerth
    const proposed = new Date(toMillis(agreed) + hours * HOUR)
    await sendEvents([estimatedArrival(ship.code, proposed, 'GCGO')], `Story: ${ship.code} sends a much later estimate`)
    const after = await getVisits()
    const now = after.find((v) => v.visitRef === mine.visitRef)
    const held = mine.berth?.berthCode
    const opening = `${ship.code} now expects to reach the berth at ${ctx.time(proposed)}, ${hours} hours after the time it agreed.`
    if (held && !now.berth) {
      const taker = after.find((v) => v.berth?.berthCode === held)
      const given = taker
        ? `${held} goes to ${taker.vessel.code}, which is asked for ${ctx.time(taker.rtaBerth)} and must accept that time.`
        : `${held} is given up.`
      return `${opening} That is later than the terminal tolerates, and a waiting ship can be alongside sooner, so the berth is assigned again: ${given} ${ship.code} keeps its place in line, ${ordinal(now.queueNumber)}, and is asked for ${ctx.time(now.rtaBerth)}, the soonest a berth is free for it.${reissued(before, after, ctx, ship.code)}`
    }
    return `${opening} No waiting ship can use ${held ?? 'a berth'} sooner, so ${ship.code} ${stillStanding(now)}, and the terminal follows its new time: ${ctx.time(now.rtaBerth)}.`
  },
})

const omits = (ship) => ({
  title: `${ship.code} omits its call`,
  sends: 'DCSA event: omitted port call',
  async run(ctx) {
    const before = await getVisits()
    const leaving = before.find((v) => v.vessel.code === ship.code)
    if (!leaving) throw new Error(`${ship.code} has no call in progress. Reset the port and start the story again.`)
    await sendEvents([omittedCall(ship.code, ctx.port.unLocode)], `Story: ${ship.code} omits its call`)
    const after = await getVisits()
    const waiting = after.filter((v) => v.queueNumber != null)
    const moved = reissued(before, after, ctx)
    const next = moved || (waiting.length > 0 ? ` ${waiting[0].vessel.code} is now first in the queue.` : '')
    return `${ship.code} will not call. It gave up ${leaving.berth ? leaving.berth.berthCode : 'its place in the queue'}.${next}`
  },
})

const arrives = (ship) => ({
  title: `${ship.code} reports that it is alongside`,
  sends: 'DCSA event: actual arrival (ACT)',
  async run() {
    await sendEvents([actualArrival(ship.code, new Date())], `Story: ${ship.code} arrives`)
    const visit = await visitOf(ship.code)
    return `${ship.code} is alongside ${visit.berth.berthCode}. (The story skips the hours of sailing in between.) With the actual arrival on record, each estimate the ship sent for this call can be checked against it.`
  },
})

const departs = (ship) => ({
  title: `${ship.code} reports its departure`,
  sends: 'DCSA event: actual departure (ACT)',
  async run() {
    const visit = await visitOf(ship.code)
    await sendEvents([actualDeparture(ship.code, new Date())], `Story: ${ship.code} departs`)
    return `${ship.code} has left ${visit.berth.berthCode}, and the call is closed. The berth is free for the next ship.`
  },
})

// ---- stories -------------------------------------------------------------

export const SCENARIOS = [
  {
    id: 'port-fills-up',
    title: 'The port fills up',
    summary: 'Every berth is taken when two more ships report. They get a time and a slower speed instead of a wait, and the queue moves when a berth frees early.',
    steps: [
      berthsAlreadyTaken,
      estimatesArrival(SHIPS.SOLVIK),
      accepts(SHIPS.SOLVIK),
      estimatesArrival(SHIPS.HAVBRIS),
      berthReleased('B1'),
      accepts(SHIPS.SOLVIK),
      arrives(SHIPS.SOLVIK),
    ],
  },
  {
    id: 'quiet-port',
    title: 'A quiet port',
    summary: 'Berths are free. A ship is given one at once, agrees the time, arrives and leaves.',
    steps: [
      estimatesArrival(SHIPS.VESTKYST),
      accepts(SHIPS.VESTKYST),
      estimatesArrival(SHIPS.FJORDLYS),
      arrives(SHIPS.VESTKYST),
      departs(SHIPS.VESTKYST),
    ],
  },
  {
    id: 'change-of-plan',
    title: 'A change of plan',
    summary: 'A waiting ship agrees a time, is delayed and proposes another, then drops the call. The next ship moves up.',
    steps: [
      berthsAlreadyTaken,
      estimatesArrival(SHIPS.HAVBRIS),
      accepts(SHIPS.HAVBRIS),
      proposesLater(SHIPS.HAVBRIS, 5),
      estimatesArrival(SHIPS.FJORDLYS),
      omits(SHIPS.HAVBRIS),
    ],
  },
  {
    id: 'ship-goes-quiet',
    title: 'A ship goes quiet',
    summary: 'A ship is given the last berth and never answers. Two more wait behind it. The planner cancels the silent call, the berth passes on, and the ship still waiting is given a new time.',
    steps: [
      shipsAlongside(ALONGSIDE.slice(0, 3)),
      estimatesArrival(SHIPS.VESTKYST),
      estimatesArrival(SHIPS.SOLVIK),
      accepts(SHIPS.SOLVIK),
      estimatesArrival(SHIPS.HAVBRIS),
      terminalCancels(SHIPS.VESTKYST),
      accepts(SHIPS.SOLVIK),
    ],
  },
  {
    id: 'falls-behind',
    title: 'A ship falls behind',
    summary: 'A ship holding the last berth sends an estimate hours later than the time it agreed. A waiting ship that can come sooner gets the berth; the late one keeps its place in line.',
    steps: [
      shipsAlongside(ALONGSIDE.slice(0, 3)),
      estimatesArrival(SHIPS.VESTKYST),
      accepts(SHIPS.VESTKYST),
      estimatesArrival(SHIPS.FJORDLYS),
      fallsBehind(SHIPS.VESTKYST, 8),
      accepts(SHIPS.FJORDLYS),
    ],
  },
]

/**
 * Close every visit in progress, so a story can start from an empty port.
 * Waiting ships are omitted first; then releasing a berth promotes no one.
 * @returns {Promise<number>} how many visits were closed
 */
export async function resetPort(port) {
  const visits = await getVisits()
  const waiting = visits.filter((v) => !v.berth)
  if (waiting.length > 0) {
    await sendEvents(waiting.map((v) => omittedCall(v.vessel.code, port.unLocode)), 'Reset: omit the waiting calls')
  }
  for (const visit of visits.filter((v) => v.berth)) {
    await releaseBerth(visit.berth.berthCode, `Reset: release ${visit.berth.berthCode}`)
  }
  return visits.length
}
