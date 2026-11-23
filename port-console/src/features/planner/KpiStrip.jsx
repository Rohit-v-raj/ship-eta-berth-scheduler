import { useTime } from '@/app/providers'
import { dayClock, relative, toMillis } from '@/lib/time'
import { AGREEMENT, agreementOf, answerOverdueMs, STANDING, standingOf } from '@/lib/visit'

/** The four numbers a planner checks first. Each is a figure, not a chart. */
export function KpiStrip({ port, visits, maintenance = [] }) {
  const { now, zone } = useTime()
  const berthCount = port.berths.filter((b) => b.status === 'ACTIVE').length
  const alongside = visits.filter((v) => standingOf(v) === STANDING.ALONGSIDE)
  const booked = visits.filter((v) => standingOf(v) === STANDING.RESERVED)
  const waiting = visits.filter((v) => standingOf(v) === STANDING.WAITING)
  const awaiting = visits.filter((v) => agreementOf(v) === AGREEMENT.AWAITING)
  const overdue = awaiting.filter((v) => answerOverdueMs(v, now) > 0)
  const underMaintenance = new Set(
    maintenance.filter((m) => toMillis(m.from) <= now.getTime() && now.getTime() < toMillis(m.to)).map((m) => m.berthCode),
  ).size

  // a berth is in use while a ship lies at it; bookings further ahead do not take it yet
  const occupied = new Set(alongside.map((v) => v.berth.berthCode)).size
  const ends = alongside.map((v) => toMillis(v.berth.plannedEnd)).filter((ms) => ms != null).sort((a, b) => a - b)
  const nextFree = ends[0]

  let freeValue = 'Now'
  let freeNote = `${berthCount - occupied} of ${berthCount} berths free`
  if (occupied >= berthCount && nextFree != null) {
    const late = nextFree < now.getTime()
    freeValue = late ? 'Overdue' : dayClock(nextFree, zone, now)
    freeNote = late ? `planned free ${relative(nextFree, now)}` : relative(nextFree, now)
  }

  return (
    <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <Tile
        label="Berths in use"
        value={`${occupied} of ${berthCount}`}
        note={`with a ship alongside${underMaintenance > 0 ? `, ${underMaintenance} under maintenance` : ''}`}
      />
      <Tile label="Ships booked" value={booked.length} note={bookedNote(booked.length, waiting.length)} />
      <Tile label="Next berth free" value={freeValue} note={freeNote} />
      <Tile label="Awaiting the ship's answer" value={awaiting.length} note={awaitingNote(awaiting.length, overdue.length)} />
    </dl>
  )
}

function bookedNote(booked, waiting) {
  if (waiting > 0) return `${waiting} more in line, due too far ahead for a berth yet`
  return booked === 0 ? 'no ship on its way' : 'each with a berth and a time'
}

function awaitingNote(awaiting, overdue) {
  if (awaiting === 0) return 'every requested time is agreed'
  if (overdue === 0) return 'requested time not yet accepted'
  return `${overdue} overdue: no answer in time`
}

function Tile({ label, value, note }) {
  return (
    <div className="rounded-lg border border-line bg-surface px-4 py-3">
      <dt className="text-[13px] text-ink-2">{label}</dt>
      <dd className="mt-0.5 text-2xl leading-tight font-semibold">{value}</dd>
      <dd className="mt-0.5 truncate text-xs text-ink-2">{note}</dd>
    </div>
  )
}
