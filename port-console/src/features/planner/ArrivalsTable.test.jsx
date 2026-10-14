import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { makeVisits, withAnswerOverdue } from '@/test/fixtures'
import { renderWithApp } from '@/test/render'
import { ArrivalsTable } from './ArrivalsTable'

describe('ArrivalsTable', () => {
  it('says where each visit stands and how far its arrival is agreed', () => {
    renderWithApp(<ArrivalsTable visits={makeVisits()} onSelect={() => {}} />)

    const row = (name) => screen.getByRole('button', { name }).closest('tr')

    expect(within(row('KINN')).getByText('Alongside B2')).toBeInTheDocument()
    expect(within(row('KINN')).getByText('Arrived')).toBeInTheDocument()

    expect(within(row('SOLVIK')).getByText('B1 reserved')).toBeInTheDocument()
    expect(within(row('SOLVIK')).getByText('Agreed')).toBeInTheDocument()

    expect(within(row('HAVBRIS')).getByText('Waiting, 1st in line')).toBeInTheDocument()
    expect(within(row('HAVBRIS')).getByText('Awaiting the ship')).toBeInTheDocument()
    expect(within(row('HAVBRIS')).getByText('74.2 nm out')).toBeInTheDocument()
    // a ship that gave no position, and no speed column: speed is the ship's own business
    expect(within(row('KINN')).getByText('none given')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: /speed/i })).not.toBeInTheDocument()
  })

  it('flags a requested arrival the ship has left unanswered past its due time', () => {
    const [alongside, reserved, waiting] = makeVisits()
    renderWithApp(<ArrivalsTable visits={[alongside, reserved, withAnswerOverdue(waiting, 1.5)]} onSelect={() => {}} />)

    const row = screen.getByRole('button', { name: 'HAVBRIS' }).closest('tr')
    expect(within(row).getByText('No answer for 1 h 30 min')).toBeInTheDocument()
    expect(within(row).queryByText('Awaiting the ship')).not.toBeInTheDocument()
  })

  it('flags a ship holding a berth past its agreed time, and an estimate that is due', () => {
    const now = Date.now()
    const [alongside, reserved, waiting] = makeVisits(now)
    const late = { ...reserved, notArrivedSince: new Date(now - 40 * 60_000).toISOString() }
    const quiet = { ...waiting, estimateDueSince: new Date(now - 2 * 3_600_000).toISOString() }
    renderWithApp(<ArrivalsTable visits={[alongside, late, quiet]} onSelect={() => {}} />)

    const row = (name) => screen.getByRole('button', { name }).closest('tr')
    expect(within(row('SOLVIK')).getByText('Not arrived, 40 min past agreed time')).toBeInTheDocument()
    expect(within(row('HAVBRIS')).getByText('Estimate due')).toBeInTheDocument()
    expect(within(row('KINN')).queryByText(/Not arrived|Estimate due/)).not.toBeInTheDocument()
  })

  it('opens a visit from its vessel name', async () => {
    const onSelect = vi.fn()
    renderWithApp(<ArrivalsTable visits={makeVisits()} onSelect={onSelect} />)

    await userEvent.click(screen.getByRole('button', { name: 'HAVBRIS' }))

    expect(onSelect).toHaveBeenCalledExactlyOnceWith('VV-20261013-HAVB0003')
  })

  it('explains an empty port instead of showing an empty table', () => {
    renderWithApp(<ArrivalsTable visits={[]} onSelect={() => {}} />)
    expect(screen.getByText('No ships are expected')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
})
