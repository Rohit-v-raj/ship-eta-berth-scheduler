import { http, HttpResponse } from 'msw'
import { makeBerthPlan, makeDcsaEvents, makeMaintenance, makeVisitDetail, makeVisits, port } from '../fixtures'

/**
 * The scheduler's API, answered from fixtures. Used by the tests and by
 * `npm run dev:mock`. Actions are accepted and change nothing.
 */
export const handlers = [
  http.get('/api/port', () => HttpResponse.json(port)),

  http.get('/api/port/berth-plan', () => HttpResponse.json(makeBerthPlan())),

  http.get('/api/port/maintenance', () => HttpResponse.json(makeMaintenance())),

  http.post('/api/port/berths/:berthCode/maintenance', async ({ request, params }) => {
    const body = await request.json()
    return HttpResponse.json({ id: 2, berthCode: params.berthCode, berthName: `Berth ${params.berthCode.slice(1)}`, ...body, reason: body.reason ?? null }, { status: 201 })
  }),

  http.delete('/api/port/berths/:berthCode/maintenance/:id', ({ params }) =>
    HttpResponse.json({ id: Number(params.id), removed: true }),
  ),

  http.get('/api/visits', ({ request }) => {
    const vesselCode = new URL(request.url).searchParams.get('vessel')
    const visits = makeVisits()
    return HttpResponse.json(vesselCode ? visits.filter((v) => v.vessel.code === vesselCode) : visits)
  }),

  http.get('/api/visits/:visitRef', ({ params }) => {
    const visit = makeVisits().find((v) => v.visitRef === params.visitRef)
    if (!visit) {
      return HttpResponse.json({ statusCode: 404, message: `Visit not found: No visit has the reference ${params.visitRef}` }, { status: 404 })
    }
    return HttpResponse.json(makeVisitDetail(visit))
  }),

  http.get('/api/port-call/v2/events', () =>
    HttpResponse.json({ events: makeDcsaEvents() }, { headers: { 'API-Version': '2.0.0' } }),
  ),

  http.post('/api/port-call/v2/events', () => HttpResponse.json({}, { headers: { 'API-Version': '2.0.0' } })),

  // The calculator: the model's estimate, and for a ship with a call open the speed that meets the request.
  http.post('/api/operator/eta', async ({ request }) => {
    const body = await request.json()
    const call = makeVisits().find((v) => v.vessel.code === body.vesselId && !v.ataBerth)
    return HttpResponse.json({
      vesselId: body.vesselId,
      etaMinutes: 502,
      estimatedArrival: new Date(Date.now() + 502 * 60_000).toISOString(),
      distanceNm: 60,
      naiveEtaMinutes: 257,
      requestedArrival: call?.rtaBerth ?? null,
      advisedSpeedKnots: call?.queueNumber ? 5.7 : null,
    })
  }),

  http.put('/api/port/berths/:berthCode/release', ({ params }) =>
    HttpResponse.json({ berthCode: params.berthCode, released: true }),
  ),

  http.put('/api/visits/:visitRef/cancel', ({ params }) =>
    HttpResponse.json({ visitRef: params.visitRef, cancelled: true }),
  ),
]
