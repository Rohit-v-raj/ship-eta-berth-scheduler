/**
 * Sample answers in the scheduler's own shapes, for tests and for running the
 * console with no scheduler behind it (`npm run dev:mock`). Times are set
 * relative to "now" so the mock port always looks current.
 */

const HOUR = 3_600_000
const at = (now, hours) => new Date(now + hours * HOUR).toISOString()

export const port = {
  unLocode: 'NOFRO',
  name: 'Floro',
  countryCode: 'NO',
  timeZone: 'Europe/Oslo',
  latitude: 61.6,
  longitude: 5.033,
  terminals: [
    { code: 'FRO-CT', name: 'Floro Container Terminal', type: 'CONTAINER', status: 'ACTIVE' },
    { code: 'FRO-MP', name: 'Floro Multipurpose Terminal', type: 'MULTIPURPOSE', status: 'ACTIVE' },
  ],
  berths: [
    { code: 'B1', name: 'Berth 1', seq: 1, terminalCode: 'FRO-CT', quayCode: 'Q1', latitude: 61.612, longitude: 5.07, lengthMeters: 200, depthMeters: 12, maxLoaMeters: 190, maxBeamMeters: 32, maxDraftMeters: 10.5, status: 'ACTIVE' },
    { code: 'B2', name: 'Berth 2', seq: 2, terminalCode: 'FRO-CT', quayCode: 'Q1', latitude: 61.611, longitude: 5.07, lengthMeters: 200, depthMeters: 12, maxLoaMeters: 190, maxBeamMeters: 32, maxDraftMeters: 10.5, status: 'ACTIVE' },
    { code: 'B3', name: 'Berth 3', seq: 3, terminalCode: 'FRO-MP', quayCode: 'Q1', latitude: 61.613, longitude: 5.072, lengthMeters: 150, depthMeters: 9, maxLoaMeters: 140, maxBeamMeters: 24, maxDraftMeters: 7.5, status: 'ACTIVE' },
    { code: 'B4', name: 'Berth 4', seq: 4, terminalCode: 'FRO-MP', quayCode: 'Q1', latitude: 61.61, longitude: 5.071, lengthMeters: 150, depthMeters: 9, maxLoaMeters: 140, maxBeamMeters: 24, maxDraftMeters: 7.5, status: 'ACTIVE' },
  ],
}

const vessel = (code, type = 'GENERAL_CARGO') => ({ code, name: code, imoNumber: null, mmsi: null, type, loaMeters: null, beamMeters: null })

/**
 * Three visits: one alongside, one holding a berth it has not reached, one
 * waiting. The waiting ship was asked twelve minutes ago and has an hour to
 * answer.
 */
export function makeVisits(now = Date.now()) {
  return [
    {
      visitRef: 'VV-20261013-KINN0001',
      vessel: vessel('KINN'),
      phase: 'BERTHED',
      terminalCode: 'FRO-CT',
      berth: { berthCode: 'B2', berthName: 'Berth 2', status: 'ACTIVE', plannedStart: at(now, -10), plannedEnd: at(now, 13), actualStart: at(now, -10), actualEnd: null },
      queueNumber: null,
      etaBerth: at(now, -10),
      rtaBerth: at(now, -10),
      ptaBerth: null,
      ataBerth: at(now, -10),
      atdBerth: null,
      plannedServiceMinutes: 1397,
      lastReport: null,
      requestedAt: at(now, -11),
      answerDueAt: null,
      cancelledBy: null,
      remarks: null,
      estimateDueSince: null,
      nextEstimateBy: null,
      notArrivedSince: null,
      arrivalDraftMeters: null,
    },
    {
      visitRef: 'VV-20261013-SOLV0002',
      vessel: vessel('SOLVIK'),
      phase: 'INBOUND',
      terminalCode: 'FRO-CT',
      berth: { berthCode: 'B1', berthName: 'Berth 1', status: 'PLANNED', plannedStart: at(now, 8), plannedEnd: at(now, 31), actualStart: null, actualEnd: null },
      queueNumber: null,
      etaBerth: at(now, 8),
      rtaBerth: at(now, 8),
      ptaBerth: at(now, 8),
      ataBerth: null,
      atdBerth: null,
      plannedServiceMinutes: 1397,
      lastReport: { reportedAt: at(now, -0.4), lat: 62.6, lon: 5.4, distanceNm: 60, estimatedArrival: at(now, 8) },
      requestedAt: at(now, -0.4),
      answerDueAt: null,
      cancelledBy: null,
      remarks: null,
      estimateDueSince: null,
      nextEstimateBy: null,
      notArrivedSince: null,
      arrivalDraftMeters: null,
    },
    {
      visitRef: 'VV-20261013-HAVB0003',
      vessel: vessel('HAVBRIS'),
      phase: 'INBOUND',
      terminalCode: null,
      berth: null,
      queueNumber: 1,
      etaBerth: at(now, 8.6),
      rtaBerth: at(now, 13),
      ptaBerth: null,
      ataBerth: null,
      atdBerth: null,
      plannedServiceMinutes: 1397,
      lastReport: { reportedAt: at(now, -0.2), lat: 62.75, lon: 6.1, distanceNm: 74.2, estimatedArrival: at(now, 8.6) },
      requestedAt: at(now, -0.2),
      answerDueAt: at(now, 0.8),
      cancelledBy: null,
      remarks: null,
      estimateDueSince: null,
      nextEstimateBy: null,
      notArrivedSince: null,
      arrivalDraftMeters: null,
    },
  ]
}

/** The same visit with its answer overdue: asked `hoursAgo` hours ago, with an hour to answer. */
export function withAnswerOverdue(visit, hoursAgo, now = Date.now()) {
  return { ...visit, ptaBerth: null, requestedAt: at(now, -hoursAgo), answerDueAt: at(now, 1 - hoursAgo) }
}

/** One maintenance window, on B3 from tomorrow. */
export function makeMaintenance(now = Date.now()) {
  return [{ id: 1, berthCode: 'B3', berthName: 'Berth 3', from: at(now, 20), to: at(now, 26), reason: 'Fender repair' }]
}

export function makeBerthPlan(now = Date.now()) {
  const [kinn, solvik] = makeVisits(now)
  const window = (visit) => ({
    visitRef: visit.visitRef,
    vesselCode: visit.vessel.code,
    vesselName: visit.vessel.name,
    vesselType: visit.vessel.type,
    status: visit.berth.status,
    plannedStart: visit.berth.plannedStart,
    plannedEnd: visit.berth.plannedEnd,
    actualStart: visit.berth.actualStart,
    actualEnd: visit.berth.actualEnd,
    etaBerth: visit.etaBerth,
    rtaBerth: visit.rtaBerth,
    ptaBerth: visit.ptaBerth,
  })
  const windows = { B1: [window(solvik)], B2: [window(kinn)] }
  return {
    from: at(now, -6),
    to: at(now, 50),
    berths: port.berths.map((b) => ({
      berthCode: b.code,
      berthName: b.name,
      terminalCode: b.terminalCode,
      status: b.status,
      windows: windows[b.code] ?? [],
      maintenance: makeMaintenance(now).filter((m) => m.berthCode === b.code),
    })),
  }
}

export function makeVisitDetail(visit, now = Date.now()) {
  const events = []
  const push = (classifier, source, eventTime, hoursAgo, remarks = null) =>
    events.push({ milestone: 'ARRIVAL_BERTH', classifier, eventTime, recordedAt: at(now, -hoursAgo), source, berthCode: classifier === 'ESTIMATED' ? null : visit.berth?.berthCode ?? null, remarks })
  if (visit.etaBerth) push('ESTIMATED', 'VESSEL', visit.etaBerth, 1)
  if (visit.rtaBerth) push('REQUESTED', 'TERMINAL', visit.rtaBerth, 0.9, visit.berth ? null : `Berth expected free ${visit.rtaBerth}`)
  if (visit.ptaBerth) push('PLANNED', 'VESSEL', visit.ptaBerth, 0.5)
  if (visit.ataBerth) push('ACTUAL', 'VESSEL', visit.ataBerth, 0.1)
  const reports = visit.lastReport
    ? [{ ...visit.lastReport, reportedAt: at(now, -1.4), distanceNm: visit.lastReport.distanceNm + 14 }, visit.lastReport]
    : []
  return { visit, events, reports }
}

export function makeDcsaEvents(now = Date.now()) {
  const event = (id, classifierCode, vesselName, hours, locationName) => ({
    eventID: `00000000-0000-4000-8000-00000000000${id}`,
    eventUpdatedDateTime: at(now, -1),
    portCall: { portCallID: '11111111-1111-4111-8111-111111111111', portVisitReference: 'VV-20261013-SOLV0002', UNLocationCode: 'NOFRO' },
    terminalCall: { terminalCallID: '22222222-2222-4222-8222-222222222222', sequenceNumber: 1 },
    portCallService: {
      portCallServiceTypeCode: 'BERTH',
      portCallServiceEventTypeCode: 'ARRI',
      portCallPhaseTypeCode: 'INBD',
      facilityTypeCode: 'BRTH',
      ...(locationName && { serviceLocation: { locationName, UNLocationCode: 'NOFRO' } }),
    },
    vessel: { vesselName, vesselTypeCode: 'GCGO' },
    timestamp: { classifierCode, serviceDateTime: at(now, hours) },
  })
  return [event(1, 'REQ', 'SOLVIK', 8, 'Berth 1'), event(2, 'PLN', 'SOLVIK', 8, 'Berth 1'), event(3, 'REQ', 'HAVBRIS', 13)]
}
