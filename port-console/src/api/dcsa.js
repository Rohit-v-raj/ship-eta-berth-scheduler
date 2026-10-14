import { http } from './http'

/**
 * The DCSA Port Call 2.0 endpoints: the feed of events (GET) and the way a
 * vessel operator answers the terminal (POST).
 *
 * A vessel is named by `vesselName`, set to the code the terminal knows it by;
 * the scheduler also matches on IMO number and MMSI when they are on record.
 */

const BASE = '/api/port-call/v2/events'

/** The scheduler refused one or more events. `elements` are DCSA feedback elements. */
export class DcsaFeedbackError extends Error {
  constructor(elements) {
    super(elements.map((e) => e.message).join(' '))
    this.name = 'DcsaFeedbackError'
    this.elements = elements
  }
}

const vesselOf = (vesselCode, vesselTypeCode, position, size) => ({
  vesselName: vesselCode,
  ...(vesselTypeCode ? { vesselTypeCode } : {}),
  ...positionOf(position),
  ...sizeOf(size),
})

/**
 * Where the ship is, in the standard's own optional fields. The coordinates
 * are text, as the standard specifies.
 */
function positionOf(position) {
  if (!position) return {}
  const { lat, lon, distanceNm } = position
  return {
    ...(lat != null && lon != null ? { vesselPosition: { geoCoordinate: { latitude: String(lat), longitude: String(lon) } } } : {}),
    ...(distanceNm != null ? { milesToDestinationPort: Number(distanceNm.toFixed(2)) } : {}),
  }
}

/**
 * The ship's size in metres, in the standard's own optional fields: length
 * overall and beam (`width`) in `vesselSizeUnit`, draft in `draftUnit`.
 */
function sizeOf(size) {
  if (!size) return {}
  const { lengthOverall, width, draft } = size
  return {
    ...(lengthOverall != null || width != null ? { vesselSizeUnit: 'MTR' } : {}),
    ...(lengthOverall != null ? { lengthOverall } : {}),
    ...(width != null ? { width } : {}),
    ...(draft != null ? { draftUnit: 'MTR', draft } : {}),
  }
}

const iso = (time) => new Date(time).toISOString()

function berthEvent(vesselCode, eventType, classifierCode, time, vesselTypeCode, position, size) {
  return {
    vessel: vesselOf(vesselCode, vesselTypeCode, position, size),
    portCallService: {
      portCallServiceTypeCode: 'BERTH',
      portCallServiceEventTypeCode: eventType,
      portCallPhaseTypeCode: eventType === 'ARRI' ? 'INBD' : 'OUTB',
      facilityTypeCode: 'BRTH',
    },
    timestamp: { classifierCode, serviceDateTime: iso(time) },
  }
}

/**
 * The ship's own estimate of when it reaches the berth. Opens a visit, or
 * replans one; this is the only way a call begins. `position` is optional:
 * { lat, lon, distanceNm }, where the ship is as it sends the estimate. So is
 * `size`: { lengthOverall, width, draft } in metres; the terminal gives a ship
 * only a berth it fits, and refuses one that fits none.
 */
export const estimatedArrival = (vesselCode, time, vesselTypeCode, position, size) =>
  berthEvent(vesselCode, 'ARRI', 'EST', time, vesselTypeCode, position, size)

/** The ship accepts the terminal's requested arrival; `time` must be that requested time. */
export const plannedArrival = (vesselCode, time) => berthEvent(vesselCode, 'ARRI', 'PLN', time)

export const actualArrival = (vesselCode, time) => berthEvent(vesselCode, 'ARRI', 'ACT', time)

export const actualDeparture = (vesselCode, time) => berthEvent(vesselCode, 'DEPA', 'ACT', time)

/** The ship will not call after all. */
export const omittedCall = (vesselCode, unLocode) => ({
  vessel: vesselOf(vesselCode),
  portCall: { ...(unLocode ? { UNLocationCode: unLocode } : {}), isOmitted: true },
})

/**
 * Send events to the terminal. The answer is 200 even when an event could not
 * be applied; each refusal comes back as a feedback element, and an ERROR
 * among them is thrown here so a caller cannot mistake it for success.
 * @returns {Promise<Array<{severity: string, message: string, propertyPath?: string}>>} warnings and notes
 */
export async function sendEvents(events, label) {
  const { data } = await http('POST', BASE, { body: { events }, label })
  const feedback = data?.feedbackElements ?? []
  const errors = feedback.filter((f) => f.severity === 'ERROR')
  if (errors.length > 0) throw new DcsaFeedbackError(errors)
  return feedback
}

/**
 * One page of the event feed.
 * @param {{classifierCode?: string, vesselName?: string, portCallServiceTypeCode?: string, eventTimestampMin?: string}} filters
 * @param {string} [cursor] the nextCursor of the previous page; keep the same filters with it
 * @returns {Promise<{events: object[], nextCursor: string|null}>}
 */
export async function getEvents(filters = {}, cursor, limit = 25) {
  const { data, headers } = await http('GET', BASE, {
    params: { ...filters, limit, cursor },
    label: cursor ? 'Port call events, next page' : 'Port call events',
  })
  return { events: data?.events ?? [], nextCursor: headers.get('Next-Page-Cursor') }
}

/** DCSA vessel type codes for the scheduler's vessel types; types DCSA has no code for are left out. */
export const DCSA_VESSEL_TYPE = {
  CONTAINER: 'CONT',
  BULK_CARRIER: 'BULK',
  TANKER: 'TANK',
  RORO: 'RORO',
  GENERAL_CARGO: 'GCGO',
  PASSENGER: 'PASS',
}
