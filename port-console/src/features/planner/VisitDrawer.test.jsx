import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { makeVisitDetail, makeVisits, port, withAnswerOverdue } from '@/test/fixtures'
import { server } from '@/test/msw/server'
import { renderWithApp } from '@/test/render'
import { ROLES } from '@/session/permissions'
import { KpiStrip } from './KpiStrip'
import { VisitDrawer } from './VisitDrawer'

const [alongside, reserved, waiting] = makeVisits()

/** Serve one visit's detail, and record any cancellation the console sends. Pass `refuse` to answer 409 with that message. */
function serve(visit, { refuse } = {}) {
  const cancelled = []
  server.use(
    http.get('/api/visits/:visitRef', () => HttpResponse.json(makeVisitDetail(visit))),
    http.put('/api/visits/:visitRef/cancel', async ({ request, params }) => {
      const text = await request.text()
      cancelled.push({ visitRef: params.visitRef, body: text ? JSON.parse(text) : null })
      return refuse
        ? HttpResponse.json({ statusCode: 409, message: refuse }, { status: 409 })
        : HttpResponse.json({ visitRef: params.visitRef, cancelled: true })
    }),
  )
  return cancelled
}

describe('VisitDrawer', () => {
  it('flags an unanswered request and cancels the call with that as the reason', async () => {
    const visit = withAnswerOverdue(waiting, 1.5)
    const cancelled = serve(visit)
    const onClose = vi.fn()
    renderWithApp(<VisitDrawer visitRef={visit.visitRef} onClose={onClose} />)

    expect(await screen.findByText(/has not answered the requested arrival, issued 1 h 30 min ago/)).toBeInTheDocument()
    expect(screen.getByText('No answer for 1 h 30 min')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel this call' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel the call' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(cancelled).toEqual([{ visitRef: visit.visitRef, body: { reason: 'no answer to the requested arrival' } }])
  })

  it('cancels a call that is not overdue without giving a reason, and names the way out of the dialog', async () => {
    const cancelled = serve(waiting)
    renderWithApp(<VisitDrawer visitRef={waiting.visitRef} onClose={() => {}} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel this call' }))
    expect(screen.queryByText(/has not answered/)).not.toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Keep the call' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancel the call' }))

    await waitFor(() => expect(cancelled).toEqual([{ visitRef: waiting.visitRef, body: null }]))
  })

  it('shows the scheduler\'s refusal in the dialog and keeps the visit open', async () => {
    serve(reserved, { refuse: 'Conflict: The vessel is alongside; release its berth when it leaves' })
    const onClose = vi.fn()
    renderWithApp(<VisitDrawer visitRef={reserved.visitRef} onClose={onClose} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel this call' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel the call' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The vessel is alongside; release its berth when it leaves')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('offers release, not cancel, for a ship alongside', async () => {
    serve(alongside)
    renderWithApp(<VisitDrawer visitRef={alongside.visitRef} onClose={() => {}} />)

    expect(await screen.findByRole('button', { name: 'Release B2' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel this call' })).not.toBeInTheDocument()
  })

  it('flags a ship past its planned end and saves an expected departure for it', async () => {
    const twoHoursAgo = new Date(Date.now() - 2 * 3600 * 1000).toISOString()
    const overstaying = { ...alongside, overstayingSince: twoHoursAgo, berth: { ...alongside.berth, plannedEnd: twoHoursAgo } }
    serve(overstaying)
    const sent = []
    server.use(
      http.put('/api/visits/:visitRef/expected-completion', async ({ request, params }) => {
        sent.push({ visitRef: params.visitRef, body: await request.json() })
        return HttpResponse.json({ visitRef: params.visitRef, expectedCompletion: sent.at(-1).body.at })
      }),
    )
    renderWithApp(<VisitDrawer visitRef={overstaying.visitRef} onClose={() => {}} />)

    expect(await screen.findByText('Overstaying, 2 h past planned end')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Reason (optional)'), 'Crane breakdown')
    await userEvent.click(screen.getByRole('button', { name: 'Save expected departure' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0].visitRef).toBe(overstaying.visitRef)
    expect(sent[0].body.reason).toBe('Crane breakdown')
    // the planned end has passed, so the form starts two hours from the next full hour: in the future
    expect(new Date(sent[0].body.at).getTime()).toBeGreaterThan(Date.now())
  })

  it('offers the operator seat neither action', async () => {
    serve(waiting)
    renderWithApp(<VisitDrawer visitRef={waiting.visitRef} onClose={() => {}} />, { role: ROLES.OPERATOR })

    expect(await screen.findByText('Ledger: who said which time, and when')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel this call' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Release/ })).not.toBeInTheDocument()
  })
})

describe('KpiStrip', () => {
  it('counts the answers that are overdue among those awaited', () => {
    renderWithApp(<KpiStrip port={port} visits={[alongside, reserved, waiting]} />)
    expect(screen.getByText('requested time not yet accepted')).toBeInTheDocument()
  })

  it('says how many are overdue', () => {
    renderWithApp(<KpiStrip port={port} visits={[alongside, withAnswerOverdue(reserved, 3), withAnswerOverdue(waiting, 2)]} />)
    expect(screen.getByText('2 overdue: no answer in time')).toBeInTheDocument()
  })
})
