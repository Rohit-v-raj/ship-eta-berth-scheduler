import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { port } from '@/test/fixtures'
import { server } from '@/test/msw/server'
import { renderWithApp } from '@/test/render'
import { ROLES } from '@/session/permissions'
import { MaintenancePanel } from './MaintenancePanel'

/** Record what the console sends to schedule or remove maintenance. Pass `refuse` to answer 409 with that message. */
function recordMaintenance({ refuse } = {}) {
  const sent = { scheduled: [], removed: [] }
  server.use(
    http.post('/api/port/berths/:berthCode/maintenance', async ({ request, params }) => {
      const body = await request.json()
      sent.scheduled.push({ berthCode: params.berthCode, ...body })
      return refuse
        ? HttpResponse.json({ statusCode: 409, message: refuse }, { status: 409 })
        : HttpResponse.json({ id: 2, berthCode: params.berthCode, berthName: 'Berth', ...body }, { status: 201 })
    }),
    http.delete('/api/port/berths/:berthCode/maintenance/:id', ({ params }) => {
      sent.removed.push({ berthCode: params.berthCode, id: params.id })
      return HttpResponse.json({ id: Number(params.id), removed: true })
    }),
  )
  return sent
}

describe('MaintenancePanel', () => {
  it('lists the maintenance planned, with its reason and whether it is under way', async () => {
    renderWithApp(<MaintenancePanel port={port} />)

    const row = (await screen.findByText('Fender repair')).closest('tr')
    expect(within(row).getByText('B3')).toBeInTheDocument()
    expect(within(row).getByText('Planned')).toBeInTheDocument()
  })

  it('schedules a window on the chosen berth, with its times in UTC and its reason', async () => {
    const sent = recordMaintenance()
    renderWithApp(<MaintenancePanel port={port} />)

    await userEvent.selectOptions(screen.getByLabelText('Berth'), 'B2')
    await userEvent.clear(screen.getByLabelText('From (UTC)'))
    await userEvent.type(screen.getByLabelText('From (UTC)'), '2030-01-10T08:00')
    await userEvent.clear(screen.getByLabelText('To (UTC)'))
    await userEvent.type(screen.getByLabelText('To (UTC)'), '2030-01-10T14:00')
    await userEvent.type(screen.getByLabelText('Reason (optional)'), 'Crane rail')
    await userEvent.click(screen.getByRole('button', { name: 'Schedule maintenance' }))

    await waitFor(() =>
      expect(sent.scheduled).toEqual([
        { berthCode: 'B2', from: '2030-01-10T08:00:00.000Z', to: '2030-01-10T14:00:00.000Z', reason: 'Crane rail' },
      ]),
    )
  })

  it('catches a window that ends before it starts, and shows a refusal with readable times', async () => {
    const sent = recordMaintenance({ refuse: 'Conflict: B1 is booked for NORDVIK from 2030-01-10T09:00:00Z to 2030-01-11T08:17:00Z; maintenance cannot overlap a booking' })
    renderWithApp(<MaintenancePanel port={port} />)

    await userEvent.clear(screen.getByLabelText('To (UTC)'))
    await userEvent.type(screen.getByLabelText('To (UTC)'), '2020-01-01T00:00')
    await userEvent.click(screen.getByRole('button', { name: 'Schedule maintenance' }))
    expect(await screen.findByText('The window must end after it starts')).toBeInTheDocument()
    expect(sent.scheduled).toHaveLength(0)

    await userEvent.clear(screen.getByLabelText('From (UTC)'))
    await userEvent.type(screen.getByLabelText('From (UTC)'), '2030-01-10T08:00')
    await userEvent.clear(screen.getByLabelText('To (UTC)'))
    await userEvent.type(screen.getByLabelText('To (UTC)'), '2030-01-10T14:00')
    await userEvent.click(screen.getByRole('button', { name: 'Schedule maintenance' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('B1 is booked for NORDVIK from 10 Jan 09:00 to 11 Jan 08:17')
  })

  it('removes a window', async () => {
    const sent = recordMaintenance()
    renderWithApp(<MaintenancePanel port={port} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Remove the maintenance on B3' }))

    await waitFor(() => expect(sent.removed).toEqual([{ berthCode: 'B3', id: '1' }]))
  })

  it('shows the operator seat the plan but not the means to change it', async () => {
    renderWithApp(<MaintenancePanel port={port} />, { role: ROLES.OPERATOR })

    expect(await screen.findByText('Fender repair')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Schedule maintenance' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument()
  })
})
