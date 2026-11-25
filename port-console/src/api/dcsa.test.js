import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/msw/server'
import { actualDeparture, anchorageArrival, DcsaFeedbackError, estimatedArrival, getEvents, omittedCall, plannedArrival, sendEvents } from './dcsa'

const time = '2026-10-13T14:00:00.123456Z'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('DCSA event builders', () => {
  it('build a planned arrival at berth, named by the vessel code', () => {
    expect(plannedArrival('SOLVIK', time)).toEqual({
      eventID: expect.stringMatching(UUID),
      eventUpdatedDateTime: expect.any(String),
      vessel: { vesselName: 'SOLVIK' },
      portCallService: {
        portCallServiceTypeCode: 'BERTH',
        portCallServiceEventTypeCode: 'ARRI',
        portCallPhaseTypeCode: 'INBD',
        facilityTypeCode: 'BRTH',
      },
      timestamp: { classifierCode: 'PLN', serviceDateTime: '2026-10-13T14:00:00.123Z' },
    })
  })

  it('carry the vessel type on an estimate and mark a departure as outbound', () => {
    expect(estimatedArrival('SOLVIK', time, 'GCGO').vessel).toEqual({ vesselName: 'SOLVIK', vesselTypeCode: 'GCGO' })
    expect(estimatedArrival('SOLVIK', time, 'GCGO').timestamp.classifierCode).toBe('EST')
    const departure = actualDeparture('SOLVIK', time)
    expect(departure.portCallService.portCallServiceEventTypeCode).toBe('DEPA')
    expect(departure.portCallService.portCallPhaseTypeCode).toBe('OUTB')
  })

  it("carry the position in the standard's own fields when the ship gives one", () => {
    const event = estimatedArrival('SOLVIK', time, 'GCGO', { lat: 62.6, lon: 5.4, distanceNm: 60.004 })
    expect(event.vessel).toEqual({
      vesselName: 'SOLVIK',
      vesselTypeCode: 'GCGO',
      vesselPosition: { geoCoordinate: { latitude: '62.6', longitude: '5.4' } },
      milesToDestinationPort: 60,
    })
    // no position, no fields: the terminal then simply has none for this estimate
    expect(estimatedArrival('SOLVIK', time).vessel).toEqual({ vesselName: 'SOLVIK' })
  })

  it("carry the ship's size in metres in the standard's own fields, and only the parts given", () => {
    const event = estimatedArrival('SOLVIK', time, 'GCGO', null, { lengthOverall: 120, width: 20, draft: 7.5 })
    expect(event.vessel).toEqual({
      vesselName: 'SOLVIK',
      vesselTypeCode: 'GCGO',
      vesselSizeUnit: 'MTR',
      lengthOverall: 120,
      width: 20,
      draftUnit: 'MTR',
      draft: 7.5,
    })
    expect(estimatedArrival('SOLVIK', time, null, null, { draft: 7.5 }).vessel).toEqual({ vesselName: 'SOLVIK', draftUnit: 'MTR', draft: 7.5 })
    expect(estimatedArrival('SOLVIK', time, null, null, { lengthOverall: undefined }).vessel).toEqual({ vesselName: 'SOLVIK' })
  })

  it('give every event its own id and the time it was made, so a repeat or an overtaken one is not applied', () => {
    const before = Date.now()
    const first = estimatedArrival('SOLVIK', time, 'GCGO')
    const second = estimatedArrival('SOLVIK', time, 'GCGO')
    expect(first.eventID).toMatch(UUID)
    expect(second.eventID).not.toBe(first.eventID)
    expect(Date.parse(first.eventUpdatedDateTime)).toBeGreaterThanOrEqual(before)
    expect(omittedCall('SOLVIK', 'NOFRO').eventID).toMatch(UUID)
  })

  it('build an arrival at the anchorage: the anchorage service started, at an actual time', () => {
    expect(anchorageArrival('SOLVIK', time)).toEqual({
      eventID: expect.stringMatching(UUID),
      eventUpdatedDateTime: expect.any(String),
      vessel: { vesselName: 'SOLVIK' },
      portCallService: { portCallServiceTypeCode: 'ANCHORAGE', portCallServiceEventTypeCode: 'STRT', facilityTypeCode: 'ANCH' },
      timestamp: { classifierCode: 'ACT', serviceDateTime: new Date(time).toISOString() },
    })
  })

  it('build an omitted port call with no timestamp', () => {
    expect(omittedCall('SOLVIK', 'NOFRO')).toEqual({
      eventID: expect.stringMatching(UUID),
      eventUpdatedDateTime: expect.any(String),
      vessel: { vesselName: 'SOLVIK' },
      portCall: { UNLocationCode: 'NOFRO', isOmitted: true },
    })
  })
})

describe('sendEvents', () => {
  it('posts the events and returns warnings the terminal gave', async () => {
    let sent
    server.use(
      http.post('/api/port-call/v2/events', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ feedbackElements: [{ severity: 'WARN', message: 'Not processed', propertyPath: '$.events[0]' }] })
      }),
    )

    const feedback = await sendEvents([plannedArrival('SOLVIK', time)], 'test')

    expect(sent.events).toHaveLength(1)
    expect(feedback).toEqual([{ severity: 'WARN', message: 'Not processed', propertyPath: '$.events[0]' }])
  })

  it('throws when the terminal refused an event, although the answer was 200', async () => {
    server.use(
      http.post('/api/port-call/v2/events', () =>
        HttpResponse.json({
          feedbackElements: [{ severity: 'ERROR', message: 'The requested arrival is now 2026-10-13T15:00:00Z; confirm that time', propertyPath: '$.events[0]' }],
        }),
      ),
    )

    const attempt = sendEvents([plannedArrival('SOLVIK', time)], 'test')

    await expect(attempt).rejects.toBeInstanceOf(DcsaFeedbackError)
    await expect(attempt).rejects.toThrow(/requested arrival is now/)
  })
})

describe('getEvents', () => {
  it('sends the filters and reads the cursor for the next page from the header', async () => {
    let query
    server.use(
      http.get('/api/port-call/v2/events', ({ request }) => {
        query = Object.fromEntries(new URL(request.url).searchParams)
        return HttpResponse.json({ events: [{ eventID: 'a' }] }, { headers: { 'Next-Page-Cursor': '42' } })
      }),
    )

    const page = await getEvents({ classifierCode: 'REQ' }, '17', 10)

    expect(query).toEqual({ classifierCode: 'REQ', limit: '10', cursor: '17' })
    expect(page).toEqual({ events: [{ eventID: 'a' }], nextCursor: '42' })
  })

  it('has no next cursor on the last page', async () => {
    expect((await getEvents()).nextCursor).toBeNull()
  })
})
