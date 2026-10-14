/**
 * The shapes the scheduler sends and expects, as JSDoc types. They mirror the
 * Java records under port-scheduler-api and exist for editor hints only;
 * nothing here runs. Every time is a UTC instant as an ISO string.
 */

/**
 * @typedef {Object} Berth  one berth of the port (PortView.BerthView)
 * @property {string} code
 * @property {string} name
 * @property {number} seq
 * @property {string} terminalCode
 * @property {string} quayCode
 * @property {number} latitude
 * @property {number} longitude
 * @property {number} lengthMeters
 * @property {number} depthMeters
 * @property {number|null} maxLoaMeters
 * @property {number|null} maxBeamMeters
 * @property {number|null} maxDraftMeters
 * @property {'ACTIVE'|'MAINTENANCE'|'CLOSED'} status
 */

/**
 * @typedef {Object} Port  GET /api/port
 * @property {string} unLocode
 * @property {string} name
 * @property {string} countryCode
 * @property {string} timeZone  IANA zone id, for example Europe/Oslo
 * @property {number} latitude
 * @property {number} longitude
 * @property {{code: string, name: string, type: string, status: string}[]} terminals
 * @property {Berth[]} berths
 */

/**
 * @typedef {Object} PositionReport  where a vessel said it was when it sent an estimated arrival
 * @property {string} reportedAt
 * @property {number|null} lat
 * @property {number|null} lon
 * @property {number|null} distanceNm
 * @property {string} estimatedArrival  the estimate it came with
 */

/**
 * @typedef {Object} BerthWindow
 * @property {string} berthCode
 * @property {string} berthName
 * @property {'PLANNED'|'ACTIVE'|'COMPLETED'|'CANCELLED'} status
 * @property {string|null} plannedStart
 * @property {string|null} plannedEnd
 * @property {string|null} actualStart
 * @property {string|null} actualEnd
 */

/**
 * @typedef {Object} Visit  GET /api/visits (VisitView). It has a berth or a queueNumber, never both.
 * @property {string} visitRef
 * @property {{code: string, name: string|null, imoNumber: string|null, mmsi: string|null, type: string, loaMeters: number|null, beamMeters: number|null}} vessel  a size is null when the terminal has not been told it
 * @property {string} phase
 * @property {string|null} terminalCode
 * @property {BerthWindow|null} berth
 * @property {number|null} queueNumber
 * @property {string|null} etaBerth  estimated arrival
 * @property {string|null} rtaBerth  requested by the terminal
 * @property {string|null} ptaBerth  planned: the ship's acceptance of the request
 * @property {string|null} ataBerth  actual arrival
 * @property {string|null} atdBerth
 * @property {number|null} plannedServiceMinutes
 * @property {PositionReport|null} lastReport
 * @property {string|null} requestedAt  when the terminal issued the request that now stands
 * @property {string|null} answerDueAt  when the ship's answer to it is due; null once accepted, arrived or over
 * @property {'VESSEL'|'TERMINAL'|null} cancelledBy  who ended a call that did not take place: the ship (omitted) or the terminal
 * @property {string|null} remarks  a note kept with the visit, such as why the call was cancelled
 * @property {string|null} estimateDueSince  a milestone before arrival (72, 48 or 24 h) passed with no estimate since
 * @property {string|null} nextEstimateBy  the next milestone by which a fresh estimate is expected
 * @property {string|null} notArrivedSince  for a ship holding a berth it has not reached: its agreed arrival, once passed
 * @property {number|null} arrivalDraftMeters  the draft declared for this call
 */

/**
 * @typedef {Object} VisitEvent  one entry of a visit's ledger
 * @property {string} milestone
 * @property {'ESTIMATED'|'REQUESTED'|'PLANNED'|'ACTUAL'} classifier
 * @property {string} eventTime   the moment the event refers to
 * @property {string} recordedAt  when it was reported
 * @property {'VESSEL'|'TERMINAL'|'PORT_AUTHORITY'|'ML_MODEL'|'SYSTEM'} source
 * @property {string|null} berthCode
 * @property {string|null} remarks
 */

/**
 * @typedef {Object} VisitDetail  GET /api/visits/{visitRef}
 * @property {Visit} visit
 * @property {VisitEvent[]} events
 * @property {PositionReport[]} reports
 */

/**
 * @typedef {Object} PlanWindow  one berth window in the plan
 * @property {string} visitRef
 * @property {string} vesselCode
 * @property {string|null} vesselName
 * @property {string} vesselType
 * @property {'PLANNED'|'ACTIVE'|'COMPLETED'} status
 * @property {string|null} plannedStart
 * @property {string|null} plannedEnd
 * @property {string|null} actualStart
 * @property {string|null} actualEnd
 * @property {string|null} etaBerth
 * @property {string|null} rtaBerth
 * @property {string|null} ptaBerth
 */

/**
 * @typedef {Object} Maintenance  a planned maintenance window: no ship may lie at the berth from `from` until `to`
 * @property {number} id
 * @property {string} berthCode
 * @property {string} berthName
 * @property {string} from
 * @property {string} to
 * @property {string|null} reason
 */

/**
 * @typedef {Object} BerthPlan  GET /api/port/berth-plan
 * @property {string} from
 * @property {string} to
 * @property {{berthCode: string, berthName: string, terminalCode: string, status: string, windows: PlanWindow[], maintenance: Maintenance[]}[]} berths
 */

/**
 * @typedef {Object} EtaQuery  POST /api/operator/eta
 * @property {string} vesselId
 * @property {number} lat
 * @property {number} lon
 * @property {number} sog  knots
 * @property {number} cog  degrees
 * @property {number} destLat
 * @property {number} destLon
 * @property {string} [vesselType]
 */

/**
 * @typedef {Object} EtaEstimate  the calculator's answer; asking stores nothing
 * @property {string} vesselId
 * @property {number} etaMinutes
 * @property {string} estimatedArrival
 * @property {number} distanceNm  straight line
 * @property {number|null} naiveEtaMinutes  distance over present speed
 * @property {string|null} requestedArrival  the terminal's request for the vessel's open call, if it has one
 * @property {number|null} advisedSpeedKnots  the speed that meets it, when slower than the present speed
 */

export {}
