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

// Every ship gives its length, beam and draft: the terminal opens a call only for a ship of known size.
// All fit every berth (140 m, 24 m beam and 7.5 m draft at most), so any berth can be shown.
const SHIPS = {
  SOLVIK: { code: 'SOLVIK', lat: 62.6, lon: 5.4, sog: 14, cog: 190, where: '60 nm to the north', size: { lengthOverall: 129, width: 20, draft: 7 } },
  HAVBRIS: { code: 'HAVBRIS', lat: 62.75, lon: 6.1, sog: 15, cog: 205, where: '74 nm to the north-east', size: { lengthOverall: 118, width: 19, draft: 6.8 } },
  FJORDLYS: { code: 'FJORDLYS', lat: 62.0, lon: 4.45, sog: 13, cog: 140, where: '29 nm to the north-west', size: { lengthOverall: 99, width: 16, draft: 6.2 } },
  VESTKYST: { code: 'VESTKYST', lat: 61.62, lon: 4.4, sog: 13, cog: 92, where: '19 nm to the west', size: { lengthOverall: 88, width: 14, draft: 5.5 } },
}

// Ships already at the berths when a story opens, and how long ago each arrived.
const ALONGSIDE = [
  { code: 'BREMANGER', arrivedHoursAgo: 13, size: { lengthOverall: 110, width: 18, draft: 6.5 } },
  { code: 'KINN', arrivedHoursAgo: 10, size: { lengthOverall: 105, width: 17, draft: 6.4 } },
  { code: 'SKORPA', arrivedHoursAgo: 7, size: { lengthOverall: 96, width: 16, draft: 6 } },
  { code: 'BATALDEN', arrivedHoursAgo: 4, size: { lengthOverall: 92, width: 15, draft: 5.8 } },
]

const knots = (value) => `${Number(value).toFixed(1)} knots`

async function visitOf(code) {
  const [visit] = await getVisits({ vessel: code })
  if (!visit) throw new Error(`${code} has no call in progress. Reset the port and start the story again.`)
  return visit
}

function stillStanding(visit) {
  return visit.berth ? `still holds ${visit.berth.berthCode}` : `is still ${ordinal(visit.queueNumber)} in line`
}

/** The ships on a berth: the one alongside, then those booked, in the order they are planned. */
function lineOn(berthCode, visits) {
  return visits
    .filter((v) => v.berth?.berthCode === berthCode)
    .sort((a, b) => (a.berth.status === 'ACTIVE' ? -1 : b.berth.status === 'ACTIVE' ? 1 : toMillis(a.berth.plannedStart) - toMillis(b.berth.plannedStart)))
}

/** The ship planned on the same berth just before this one, if any. */
function shipBefore(visit, visits) {
  const line = lineOn(visit.berth.berthCode, visits)
  const index = line.findIndex((v) => v.visitRef === visit.visitRef)
  return index > 0 ? line[index - 1] : null
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
        [estimatedArrival(ship.code, arrived, 'GCGO', null, ship.size), actualArrival(ship.code, arrived)],
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
 * A sentence for each ship whose requested arrival or berth the terminal has
 * just changed. The terminal works the plan out again whenever it changes,
 * and tells a ship only when its time really moves.
 */
function changes(before, after, ctx, except = []) {
  const was = new Map(before.map((v) => [v.visitRef, v]))
  return after
    .filter((v) => !except.includes(v.vessel.code) && was.has(v.visitRef))
    .map((v) => {
      const old = was.get(v.visitRef)
      const from = old.berth?.berthCode
      const to = v.berth?.berthCode
      const timeMoved = toMillis(old.rtaBerth) !== toMillis(v.rtaBerth)
      if (!timeMoved && from === to) return ''
      const ask = `the terminal asks it for ${ctx.time(v.rtaBerth)} instead of ${ctx.time(old.rtaBerth)}`
      const again = old.ptaBerth != null && v.ptaBerth == null ? ', which cancels the time it had agreed: it has to accept again' : ''
      if (from && to && from !== to) return ` ${v.vessel.code} moves from ${from} to ${to}, which can take it sooner: ${ask}${again}.`
      if (to && !from) return ` ${v.vessel.code} is booked on ${to}: ${ask}${again}.`
      if (to) {
        const earlier = toMillis(v.rtaBerth) < toMillis(old.rtaBerth)
        return ` ${v.vessel.code} ${earlier ? 'moves up' : 'moves back'} on ${to}: ${ask}${again}.`
      }
      return ` ${v.vessel.code} is ${ordinal(v.queueNumber)} in line: ${ask}${again}.`
    })
    .join('')
}

const NOTHING_ELSE = " No other ship's time changes."

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
      [estimatedArrival(ship.code, estimate.estimatedArrival, 'GCGO', { lat: ship.lat, lon: ship.lon, distanceNm: estimate.distanceNm }, ship.size)],
      `Story: ${ship.code} sends its estimated arrival`,
    )
    const visit = await visitOf(ship.code)
    const opening = `Its operator asks the ETA model, which puts the ship ${span(estimate.etaMinutes * 60_000)} away, and sends that to the terminal as its estimated arrival.`
    if (visit.berth && toMillis(visit.rtaBerth) === toMillis(visit.etaBerth)) {
      return `${opening} ${visit.berth.berthName} is free by then, so it is booked at once and the terminal asks for arrival at ${ctx.time(visit.rtaBerth)}: the ship's own estimate. There is nothing to wait for.`
    }
    // The terminal answers with a time. What speed meets it is the ship's sum, so the operator asks the calculator again.
    const speed = await askEta(query, `Story: ${ship.code} asks what speed meets the requested arrival`)
    const advice =
      speed.advisedSpeedKnots != null
        ? `The terminal sends a time, not a speed: the operator's calculator works out that slowing from ${knots(ship.sog)} to ${knots(speed.advisedSpeedKnots)} meets it, rather than hurrying to wait.`
        : "The terminal sends a time, not a speed: the operator's calculator finds no slower speed worth sailing."
    if (visit.berth) {
      const before = shipBefore(visit, await getVisits())
      return `${opening} No berth is free by then, so the terminal books it on ${visit.berth.berthCode}${before ? ` after ${before.vessel.code}` : ''} and asks for arrival at ${ctx.time(visit.rtaBerth)}, when the berth is ready for it. ${advice}`
    }
    return `${opening} It is due too far ahead to be given a berth yet, so it waits in line, ${ordinal(visit.queueNumber)}, and the terminal asks for arrival at ${ctx.time(visit.rtaBerth)}. ${advice}`
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

const shipAheadLeaves = (ship) => ({
  title: `The ship ahead of ${ship.code} leaves early`,
  sends: 'PUT /api/port/berths/{berthCode}/release',
  async run(ctx) {
    const before = await getVisits()
    const mine = before.find((v) => v.vessel.code === ship.code)
    if (!mine?.berth) throw new Error(`${ship.code} holds no berth. Reset the port and start the story again.`)
    const berthCode = mine.berth.berthCode
    const leaving = lineOn(berthCode, before).find((v) => v.berth.status === 'ACTIVE')
    if (!leaving) throw new Error(`No ship lies at ${berthCode}. Reset the port and start the story again.`)
    await releaseBerth(berthCode, `Story: release ${berthCode}`)
    const after = await getVisits()
    const opening = `${leaving.vessel.code} leaves ${berthCode} hours before its planned end, and the planner releases the berth.`
    return `${opening}${changes(before, after, ctx) || NOTHING_ELSE}`
  },
})

const terminalCancels = (ship) => ({
  title: `${ship.code} has not answered, and the planner cancels its call`,
  sends: 'PUT /api/visits/{visitRef}/cancel',
  async run(ctx) {
    const before = await getVisits()
    const silent = before.find((v) => v.vessel.code === ship.code)
    if (!silent) throw new Error(`${ship.code} has no call in progress. Reset the port and start the story again.`)
    await cancelVisit(silent.visitRef, 'no answer to the requested arrival', `Story: the planner cancels ${ship.code}'s call`)
    const after = await getVisits()
    const opening = `${ship.code} holds ${silent.berth ? silent.berth.berthCode : 'a place in line'} but never accepted the requested arrival. The terminal does not take a berth away by itself: an answer left for an hour is flagged on the planner's screen, and the planner decides. (The story does not wait the hour.) The planner cancels the call, which the feed publishes as a berth service cancelled by the terminal, not as a call omitted by the carrier.`
    return `${opening}${changes(before, after, ctx) || NOTHING_ELSE}`
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
    // overtaken: moved to another berth, out of the plan, or someone new is now ahead of it on its berth
    const aheadOf = (visit, visits) => {
      const line = lineOn(visit.berth.berthCode, visits)
      return line.slice(0, line.findIndex((v) => v.visitRef === visit.visitRef)).map((v) => v.visitRef)
    }
    const overtaken = held && (now.berth?.berthCode !== held || aheadOf(now, after).some((ref) => !aheadOf(mine, before).includes(ref)))
    if (overtaken) {
      const movedIn = lineOn(held, after).find((v) => v.visitRef !== mine.visitRef && before.some((b) => b.visitRef === v.visitRef && b.berth?.berthCode !== held))
      const taker = movedIn ?? (now.berth?.berthCode === held ? shipBefore(now, after) : lineOn(held, after)[0])
      const given = taker
        ? `${held} goes to ${taker.vessel.code}, which is asked for ${ctx.time(taker.rtaBerth)} and must accept that time.`
        : `${held} is given up.`
      const behind = now.berth ? shipBefore(now, after) : null
      const rebooked = now.berth
        ? `${ship.code} is booked again, on ${now.berth.berthCode}${behind ? ` after ${behind.vessel.code}` : ''}, and asked for ${ctx.time(now.rtaBerth)}.`
        : `${ship.code} keeps its place in line, ${ordinal(now.queueNumber)}, and is asked for ${ctx.time(now.rtaBerth)}.`
      const others = changes(before, after, ctx, [ship.code, taker?.vessel.code])
      return `${opening} That is later than the terminal tolerates, and another ship can be alongside ${held} sooner, so the berth is planned again: ${given} ${rebooked}${others}`
    }
    return `${opening} No other ship can use ${held ?? 'a berth'} sooner, so ${ship.code} ${stillStanding(now)}, and the terminal follows its new time: ${ctx.time(now.rtaBerth)}.`
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
    return `${ship.code} will not call. It gave up ${leaving.berth ? leaving.berth.berthCode : 'its place in line'}.${changes(before, after, ctx) || NOTHING_ELSE}`
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
    summary: 'Every berth is taken when two more ships report. Each is booked after a ship there, with a time and a slower speed instead of a wait, and moves up when a berth frees early.',
    steps: [
      berthsAlreadyTaken,
      estimatesArrival(SHIPS.SOLVIK),
      accepts(SHIPS.SOLVIK),
      estimatesArrival(SHIPS.HAVBRIS),
      shipAheadLeaves(SHIPS.SOLVIK),
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
    summary: 'A booked ship agrees a time, is delayed and proposes another, then drops the call. The plan is worked out again without it.',
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
    summary: 'A ship is given the last free berth and never answers. Two more are booked after the ships alongside. The planner cancels the silent call, and a ship that the freed berth can take much sooner moves to it.',
    steps: [
      shipsAlongside(ALONGSIDE.slice(0, 3)),
      estimatesArrival(SHIPS.VESTKYST),
      estimatesArrival(SHIPS.SOLVIK),
      accepts(SHIPS.SOLVIK),
      estimatesArrival(SHIPS.HAVBRIS),
      terminalCancels(SHIPS.VESTKYST),
      accepts(SHIPS.HAVBRIS),
    ],
  },
  {
    id: 'falls-behind',
    title: 'A ship falls behind',
    summary: 'A ship holding the last free berth sends an estimate hours later than the time it agreed. A ship booked elsewhere that can come much sooner gets the berth; the late one is booked again.',
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
 * Ships not alongside, booked or waiting, are omitted first; then releasing
 * the berths of the ships alongside moves no one up.
 * @returns {Promise<number>} how many visits were closed
 */
export async function resetPort(port) {
  const visits = await getVisits()
  const coming = visits.filter((v) => v.berth?.status !== 'ACTIVE')
  if (coming.length > 0) {
    await sendEvents(coming.map((v) => omittedCall(v.vessel.code, port.unLocode)), 'Reset: omit the calls not yet alongside')
  }
  for (const visit of visits.filter((v) => v.berth?.status === 'ACTIVE')) {
    await releaseBerth(visit.berth.berthCode, `Reset: release ${visit.berth.berthCode}`)
  }
  return visits.length
}
