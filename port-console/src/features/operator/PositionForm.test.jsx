import { useState } from 'react'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { makeVisits, port } from '@/test/fixtures'
import { server } from '@/test/msw/server'
import { renderWithApp } from '@/test/render'
import { ROLES } from '@/session/permissions'
import { PositionForm } from './PositionForm'

const ARRIVAL = '2026-10-13T20:22:00.000Z'

/** Record the calls the calculator makes: questions to the model, and anything sent to the terminal. */
function recordCalls({ requestedArrival = null, advisedSpeedKnots = null } = {}) {
  const calls = { eta: [], events: [] }
  server.use(
    http.post('/api/operator/eta', async ({ request }) => {
      calls.eta.push(await request.json())
      return HttpResponse.json({
        vesselId: 'SOLVIK',
        etaMinutes: 502,
        estimatedArrival: ARRIVAL,
        distanceNm: 60.04,
        naiveEtaMinutes: 257,
        // the terminal has an answer only once an estimate has been sent
        requestedArrival: calls.events.length > 0 || requestedArrival ? requestedArrival ?? '2026-10-13T22:00:00.000Z' : null,
        advisedSpeedKnots: calls.events.length > 0 || requestedArrival ? advisedSpeedKnots : null,
      })
    }),
    http.post('/api/port-call/v2/events', async ({ request }) => {
      calls.events.push(await request.json())
      return HttpResponse.json({})
    }),
  )
  return calls
}

/** The form with the page's job of holding the last answer. */
function Desk({ visit = null }) {
  const [answer, setAnswer] = useState(null)
  return <PositionForm port={port} vesselCode="SOLVIK" visit={visit} answer={answer} onAnswer={setAnswer} />
}

const asOperator = { role: ROLES.OPERATOR }

describe('PositionForm', () => {
  it('only calculates when asked to: the model is asked and nothing is sent', async () => {
    const calls = recordCalls()
    renderWithApp(<Desk />, asOperator)

    await userEvent.click(screen.getByRole('button', { name: 'Calculate only' }))

    expect(await screen.findByText(/A calculation only: nothing was sent or stored/)).toBeInTheDocument()
    expect(calls.eta).toHaveLength(1)
    expect(calls.eta[0]).toMatchObject({ vesselId: 'SOLVIK', lat: 61.62, lon: 4.45, sog: 12, destLat: 61.612, destLon: 5.07 })
    expect(calls.events).toHaveLength(0)
  })

  it('opens a call by sending the model’s estimate as a DCSA estimated arrival, with the position', async () => {
    const calls = recordCalls({ advisedSpeedKnots: 7.5 })
    renderWithApp(<Desk />, asOperator)

    await userEvent.click(screen.getByRole('button', { name: 'Send estimated arrival to the terminal' }))

    expect(await screen.findByText(/This estimate was sent to the terminal with your position/)).toBeInTheDocument()
    expect(calls.events).toHaveLength(1)
    const [event] = calls.events[0].events
    expect(event.timestamp).toEqual({ classifierCode: 'EST', serviceDateTime: ARRIVAL })
    expect(event.vessel).toEqual({
      vesselName: 'SOLVIK',
      vesselTypeCode: 'GCGO',
      vesselPosition: { geoCoordinate: { latitude: '61.62', longitude: '4.45' } },
      milesToDestinationPort: 60.04,
    })
    // asked once for the estimate, and once more for the speed that meets the terminal's answer
    expect(calls.eta).toHaveLength(2)
    expect(screen.getByText(/7\.5 kn, down from 12\.0 kn/)).toBeInTheDocument()
  })

  it("sends the ship's size with the estimate when one is given, and checks it first", async () => {
    const calls = recordCalls()
    renderWithApp(<Desk />, asOperator)

    await userEvent.type(screen.getByLabelText('Length overall'), '120')
    await userEvent.type(screen.getByLabelText('Draft'), '-2')
    await userEvent.click(screen.getByRole('button', { name: 'Send estimated arrival to the terminal' }))
    expect(await screen.findByText('The draft must be more than 0')).toBeInTheDocument()
    expect(calls.events).toHaveLength(0)

    await userEvent.clear(screen.getByLabelText('Draft'))
    await userEvent.type(screen.getByLabelText('Draft'), '7.5')
    await userEvent.click(screen.getByRole('button', { name: 'Send estimated arrival to the terminal' }))

    await screen.findByText(/This estimate was sent to the terminal/)
    expect(calls.events[0].events[0].vessel).toMatchObject({ vesselSizeUnit: 'MTR', lengthOverall: 120, draftUnit: 'MTR', draft: 7.5 })
    expect(calls.events[0].events[0].vessel).not.toHaveProperty('width')
  })

  it('offers a ship that already has a call a calculation first, and sending as the second choice', async () => {
    const [, , waiting] = makeVisits()
    const calls = recordCalls({ requestedArrival: waiting.rtaBerth, advisedSpeedKnots: 5.7 })
    renderWithApp(<Desk visit={{ ...waiting, vessel: { ...waiting.vessel, code: 'SOLVIK' } }} />, asOperator)

    await userEvent.click(screen.getByRole('button', { name: 'Calculate' }))

    expect(await screen.findByText(/5\.7 kn, down from 12\.0 kn/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send as a new estimate' })).toBeEnabled()
    expect(calls.events).toHaveLength(0)
  })

  it('catches a position that cannot be right before anything is asked', async () => {
    const calls = recordCalls()
    renderWithApp(<Desk />, asOperator)

    await userEvent.clear(screen.getByLabelText('Latitude'))
    await userEvent.type(screen.getByLabelText('Latitude'), '95')
    await userEvent.click(screen.getByRole('button', { name: 'Calculate only' }))

    expect(await screen.findByText('Latitude runs from -90 to 90')).toBeInTheDocument()
    expect(calls.eta).toHaveLength(0)
  })
})
