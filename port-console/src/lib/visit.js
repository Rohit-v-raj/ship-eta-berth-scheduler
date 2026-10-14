import { toMillis } from './time'

/**
 * What a visit's fields mean on screen. Kept in one place so the table, the
 * map, the timeline and the operator's page all read a visit the same way.
 */

/** The four kinds of time (IMO FAL.5/Circ.52, as DCSA Port Call codes them). */
export const TIME_CLASSES = {
  ESTIMATED: { code: 'EST', label: 'Estimated', by: 'the ship, or the ETA model' },
  REQUESTED: { code: 'REQ', label: 'Requested', by: 'the terminal' },
  PLANNED: { code: 'PLN', label: 'Planned', by: 'the ship, accepting the request' },
  ACTUAL: { code: 'ACT', label: 'Actual', by: 'the ship' },
}

export const TIME_CLASS_BY_CODE = Object.fromEntries(
  Object.entries(TIME_CLASSES).map(([name, value]) => [value.code, name]),
)

export const STANDING = {
  WAITING: 'WAITING',
  RESERVED: 'RESERVED',
  ALONGSIDE: 'ALONGSIDE',
  CLOSED: 'CLOSED',
}

const CLOSED_PHASES = ['DEPARTED', 'CANCELLED']

/** Where the visit stands: in the queue, holding a berth it has not reached, alongside, or over. */
export function standingOf(visit) {
  if (CLOSED_PHASES.includes(visit.phase)) return STANDING.CLOSED
  if (!visit.berth) return STANDING.WAITING
  return visit.berth.status === 'ACTIVE' ? STANDING.ALONGSIDE : STANDING.RESERVED
}

export const AGREEMENT = {
  ARRIVED: 'ARRIVED',
  AGREED: 'AGREED',
  AWAITING: 'AWAITING',
  NONE: 'NONE',
}

/**
 * How far the arrival time has been agreed. The terminal requests a time and
 * the ship accepts it; a planned time that no longer equals the request has
 * been overtaken by a new request.
 */
export function agreementOf(visit) {
  if (visit.ataBerth) return AGREEMENT.ARRIVED
  if (!visit.rtaBerth) return AGREEMENT.NONE
  const agreed = visit.ptaBerth && toMillis(visit.ptaBerth) === toMillis(visit.rtaBerth)
  return agreed ? AGREEMENT.AGREED : AGREEMENT.AWAITING
}

/**
 * How long the ship's answer to the requested arrival is overdue, in
 * milliseconds; 0 while it is not. The scheduler says when the answer is due
 * and stops saying so once the ship has accepted or arrived, so the rule for
 * "due" lives there and only the clock is read here.
 */
export function answerOverdueMs(visit, now = new Date()) {
  const due = toMillis(visit.answerDueAt)
  return due == null ? 0 : Math.max(0, toMillis(now) - due)
}

/** A call that ended without taking place: omitted by the ship's operator, or cancelled by the terminal. */
export function cancelledByTerminal(visit) {
  return visit.phase === 'CANCELLED' && visit.cancelledBy === 'TERMINAL'
}

/** A vessel's size in words, for example "120 m long, 20 m beam, draft 7.5 m"; null when none is known. */
export function sizeText(visit) {
  const parts = []
  if (visit.vessel.loaMeters != null) parts.push(`${Number(visit.vessel.loaMeters)} m long`)
  if (visit.vessel.beamMeters != null) parts.push(`${Number(visit.vessel.beamMeters)} m beam`)
  if (visit.arrivalDraftMeters != null) parts.push(`draft ${Number(visit.arrivalDraftMeters)} m`)
  return parts.length > 0 ? parts.join(', ') : null
}

export function vesselLabel(vessel) {
  return vessel.name || vessel.code
}

const ORDINALS = ['', '1st', '2nd', '3rd']

export function ordinal(n) {
  return ORDINALS[n] ?? `${n}th`
}

export const VESSEL_TYPES = [
  { value: 'CONTAINER', label: 'Container ship' },
  { value: 'BULK_CARRIER', label: 'Bulk carrier' },
  { value: 'TANKER', label: 'Tanker' },
  { value: 'RORO', label: 'Ro-ro' },
  { value: 'GENERAL_CARGO', label: 'General cargo' },
  { value: 'PASSENGER', label: 'Passenger' },
  { value: 'FISHING', label: 'Fishing vessel' },
  { value: 'TUG', label: 'Tug' },
  { value: 'OTHER', label: 'Other' },
]

export function vesselTypeLabel(type) {
  return VESSEL_TYPES.find((t) => t.value === type)?.label ?? 'Other'
}
