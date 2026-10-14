import { ApiError, http } from './http'

/**
 * The scheduler's own endpoints (everything except the DCSA ones, which are in
 * dcsa.js). Reads that the screen polls are marked quiet so they stay out of
 * the wire log.
 */

/** @returns {Promise<import('./shapes').Port>} */
export async function getPort() {
  return (await http('GET', '/api/port', { quiet: true })).data
}

/** @returns {Promise<import('./shapes').BerthPlan>} */
export async function getBerthPlan({ from, to }) {
  return (await http('GET', '/api/port/berth-plan', { params: { from, to }, quiet: true })).data
}

/** @returns {Promise<import('./shapes').Visit[]>} */
export async function getVisits({ vessel, state } = {}) {
  return (await http('GET', '/api/visits', { params: { vessel, state }, quiet: true })).data
}

/** @returns {Promise<import('./shapes').Maintenance[]>} maintenance windows not yet over, earliest first */
export async function getMaintenance() {
  return (await http('GET', '/api/port/maintenance', { quiet: true })).data
}

/**
 * Plan maintenance on a berth. The scheduler refuses a window that overlaps a
 * ship's booking on the berth or another window, and plans the waiting ships
 * around the rest.
 * @param {string} berthCode
 * @param {{ from: string, to: string, reason?: string }} window
 * @returns {Promise<import('./shapes').Maintenance>}
 */
export async function scheduleMaintenance(berthCode, window, label = `Schedule maintenance on ${berthCode}`) {
  return (await http('POST', `/api/port/berths/${encodeURIComponent(berthCode)}/maintenance`, { body: window, label })).data
}

export async function removeMaintenance(berthCode, id, label = `Remove maintenance on ${berthCode}`) {
  return (await http('DELETE', `/api/port/berths/${encodeURIComponent(berthCode)}/maintenance/${id}`, { label })).data
}

/** @returns {Promise<import('./shapes').VisitDetail>} */
export async function getVisit(visitRef) {
  return (await http('GET', `/api/visits/${encodeURIComponent(visitRef)}`, { quiet: true })).data
}

// The scheduler calls the ETA model for the calculator. If the model service
// is down the scheduler answers 500, which on its own tells the reader nothing.
function explainModelFailure(error) {
  if (error instanceof ApiError && error.status >= 500) {
    return new ApiError('The scheduler could not get an ETA. Is the ETA model service running on port 8000?', {
      status: error.status,
      body: error.body,
    })
  }
  return error
}

/**
 * The operator's calculator: when the ship would arrive from this position
 * and, if the terminal has requested an arrival, the speed that meets it.
 * The scheduler stores nothing.
 * @param {import('./shapes').EtaQuery} query
 * @returns {Promise<import('./shapes').EtaEstimate>}
 */
export async function askEta(query, label = 'Ask the ETA calculator') {
  try {
    return (await http('POST', '/api/operator/eta', { body: query, label })).data
  } catch (error) {
    throw explainModelFailure(error)
  }
}

/**
 * Free a berth, for the planner when a ship's side has not reported its
 * departure. The ship is recorded as departed and the berth passes to the head
 * of the queue. For a ship that never arrived, cancel its call instead.
 */
export async function releaseBerth(berthCode, label = `Release ${berthCode}`) {
  return (await http('PUT', `/api/port/berths/${encodeURIComponent(berthCode)}/release`, { label })).data
}

/**
 * The terminal cancels a call whose ship has not arrived, for the planner
 * when a requested arrival goes unanswered. A berth the ship held passes to
 * the head of the queue. `reason` is optional and is kept with the visit.
 */
export async function cancelVisit(visitRef, reason, label = 'Cancel the call') {
  const body = reason ? { reason } : undefined
  return (await http('PUT', `/api/visits/${encodeURIComponent(visitRef)}/cancel`, { body, label })).data
}
