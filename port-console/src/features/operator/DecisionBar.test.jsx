import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { describe, expect, it, vi } from 'vitest'
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

  it('shows a warning the terminal gave with an event it applied', async () => {
    const warn = vi.spyOn(toast, 'warning').mockImplementation(() => {})
    const message = 'Applied, but the estimated arrival 2026-10-13T08:00:00Z has already passed: the ship is planned from now'
    captureEvents([{ severity: 'WARN', message, propertyPath: '$.events[0]' }])
    renderWithApp(<DecisionBar visit={waiting} port={port} />, asOperator)

    await userEvent.click(screen.getByRole('button', { name: 'Accept requested arrival' }))

    await vi.waitFor(() => expect(warn).toHaveBeenCalledWith(message))
    warn.mockRestore()
  })

  it('does not offer to accept a time that is already agreed', () => {
    renderWithApp(<DecisionBar visit={reserved} port={port} />, asOperator)
    expect(screen.getByRole('button', { name: 'Requested arrival accepted' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Report arrival at B1' })).toBeEnabled()
  })

  it('offers arrival at the berth only once the requested arrival is accepted', async () => {
    const notAccepted = { ...reserved, ptaBerth: null }
    renderWithApp(<DecisionBar visit={notAccepted} port={port} />, asOperator)

    expect(screen.getByRole('button', { name: 'Accept requested arrival' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: /Report arrival at B/ })).not.toBeInTheDocument()
    expect(screen.getByText(/before reporting arrival at B1/)).toBeInTheDocument()
  })

  it('offers arrival at the berth only at the ship\'s turn', () => {
    const plannedStart = new Date(Date.now() + 3_600_000).toISOString()
    const behindBooking = { ...reserved, aheadOnBerth: { vesselCode: 'KVITSOY', vesselName: null, status: 'PLANNED', plannedStart } }
    const { unmount } = renderWithApp(<DecisionBar visit={behindBooking} port={port} />, asOperator)

    expect(screen.queryByRole('button', { name: /Report arrival at B/ })).not.toBeInTheDocument()
    expect(screen.getByText(/^B1 is booked for KVITSOY first, from .+\. Report arrival at your turn, once it has left\./)).toBeInTheDocument()
    unmount()

    const behindAlongside = { ...reserved, aheadOnBerth: { vesselCode: 'KVITSOY', vesselName: 'Kvitsoy', status: 'ACTIVE', plannedStart } }
    renderWithApp(<DecisionBar visit={behindAlongside} port={port} />, asOperator)
    expect(screen.queryByRole('button', { name: /Report arrival at B/ })).not.toBeInTheDocument()
    expect(screen.getByText(/^Kvitsoy is still alongside B1\. Report arrival once it has left\./)).toBeInTheDocument()
  })

  it('lets a ship that is early report arrival at the anchorage instead', async () => {
    const sent = captureEvents()
    const plannedStart = new Date(Date.now() + 3_600_000).toISOString()
    const early = { ...reserved, aheadOnBerth: { vesselCode: 'KVITSOY', vesselName: null, status: 'PLANNED', plannedStart } }
    renderWithApp(<DecisionBar visit={early} port={port} />, asOperator)

    expect(screen.getByText(/once it has left\. If you are here early, report arrival at the anchorage\.$/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Report arrival at the anchorage' }))

    const [event] = sent[0].events
    expect(event.portCallService).toEqual({ portCallServiceTypeCode: 'ANCHORAGE', portCallServiceEventTypeCode: 'STRT', facilityTypeCode: 'ANCH' })
    expect(event.timestamp.classifierCode).toBe('ACT')
  })

  it('does not offer the anchorage to a ship already lying there, nor to one alongside', () => {
    const anchored = { ...reserved, ataAnchorage: new Date().toISOString(), aheadOnBerth: { vesselCode: 'KVITSOY', vesselName: null, status: 'PLANNED', plannedStart: new Date().toISOString() } }
    const { unmount } = renderWithApp(<DecisionBar visit={anchored} port={port} />, asOperator)
    expect(screen.queryByRole('button', { name: 'Report arrival at the anchorage' })).not.toBeInTheDocument()
    expect(screen.queryByText(/If you are here early/)).not.toBeInTheDocument()
    unmount()

    renderWithApp(<DecisionBar visit={alongside} port={port} />, asOperator)
    expect(screen.queryByRole('button', { name: 'Report arrival at the anchorage' })).not.toBeInTheDocument()
  })

  it('asks before omitting, then sends an omitted port call', async () => {
    const sent = captureEvents()
    renderWithApp(<DecisionBar visit={waiting} port={port} />, asOperator)

    await userEvent.click(screen.getByRole('button', { name: 'Omit this call' }))
    expect(sent).toHaveLength(0)
    await userEvent.click(await screen.findByRole('button', { name: 'Omit the call' }))

    expect(sent[0].events).toEqual([{
      eventID: expect.any(String),
      eventUpdatedDateTime: expect.any(String),
      vessel: { vesselName: 'HAVBRIS' },
      portCall: { UNLocationCode: 'NOFRO', isOmitted: true },
    }])
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
