import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { makeVisits, port } from '@/test/fixtures'
import { server } from '@/test/msw/server'
import { renderWithApp } from '@/test/render'
import { ROLES } from '@/session/permissions'
import { DecisionBar } from './DecisionBar'

const [alongside, reserved, waiting] = makeVisits()

/** Capture what the console posts to the DCSA endpoint, and answer with `feedback`. */
function captureEvents(feedback = []) {
  const sent = []
  server.use(
    http.post('/api/port-call/v2/events', async ({ request }) => {
      sent.push(await request.json())
      return HttpResponse.json(feedback.length > 0 ? { feedbackElements: feedback } : {})
    }),
  )
  return sent
}

const asOperator = { role: ROLES.OPERATOR }

describe('DecisionBar', () => {
  it('accepts by sending a planned arrival equal to the requested one', async () => {
    const sent = captureEvents()
    renderWithApp(<DecisionBar visit={waiting} port={port} />, asOperator)

    await userEvent.click(screen.getByRole('button', { name: 'Accept requested arrival' }))

    expect(sent).toHaveLength(1)
    const [event] = sent[0].events
    expect(event.vessel).toEqual({ vesselName: 'HAVBRIS' })
    expect(event.timestamp).toEqual({ classifierCode: 'PLN', serviceDateTime: waiting.rtaBerth })
    expect(event.portCallService.portCallServiceTypeCode).toBe('BERTH')
  })

  it('shows the terminal\'s refusal when the request changed in the meantime', async () => {
    captureEvents([{ severity: 'ERROR', message: 'The requested arrival is now 2026-10-13T15:00:00Z; confirm that time', propertyPath: '$.events[0]' }])
    renderWithApp(<DecisionBar visit={waiting} port={port} />, asOperator)

    await userEvent.click(screen.getByRole('button', { name: 'Accept requested arrival' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/requested arrival is now .* The terminal changed its request/)
  })

  it('does not offer to accept a time that is already agreed', () => {
    renderWithApp(<DecisionBar visit={reserved} port={port} />, asOperator)
    expect(screen.getByRole('button', { name: 'Requested arrival accepted' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Report arrival at B1' })).toBeEnabled()
  })

  it('asks before omitting, then sends an omitted port call', async () => {
    const sent = captureEvents()
    renderWithApp(<DecisionBar visit={waiting} port={port} />, asOperator)

    await userEvent.click(screen.getByRole('button', { name: 'Omit this call' }))
    expect(sent).toHaveLength(0)
    await userEvent.click(await screen.findByRole('button', { name: 'Omit the call' }))

    expect(sent[0].events).toEqual([{ vessel: { vesselName: 'HAVBRIS' }, portCall: { UNLocationCode: 'NOFRO', isOmitted: true } }])
  })

  it('offers only departure to a ship that is alongside', () => {
    renderWithApp(<DecisionBar visit={alongside} port={port} />, asOperator)
    expect(screen.getByRole('button', { name: 'Report departure from B2' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Accept/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Omit this call' })).not.toBeInTheDocument()
  })

  it('offers nothing to the planner seat', () => {
    const { container } = renderWithApp(<DecisionBar visit={waiting} port={port} />, { role: ROLES.PLANNER })
    expect(container).toBeEmptyDOMElement()
  })
})
