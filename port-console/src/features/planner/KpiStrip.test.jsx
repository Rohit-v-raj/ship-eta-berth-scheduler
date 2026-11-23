import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { makeVisits, port } from '@/test/fixtures'
import { renderWithApp } from '@/test/render'
import { KpiStrip } from './KpiStrip'

const tile = (label) => screen.getByText(label).closest('div')

describe('KpiStrip', () => {
  it('counts a berth as in use only while a ship lies at it, and the booked ships apart from those in line', () => {
    // KINN alongside B2, SOLVIK booked on B1, HAVBRIS in line with no berth
    renderWithApp(<KpiStrip port={port} visits={makeVisits()} />)

    expect(tile('Berths in use')).toHaveTextContent('1 of 4')
    expect(tile('Ships booked')).toHaveTextContent('1')
    expect(tile('Ships booked')).toHaveTextContent('1 more in line, due too far ahead for a berth yet')
    expect(tile('Next berth free')).toHaveTextContent('Now')
    expect(tile('Next berth free')).toHaveTextContent('3 of 4 berths free')
  })

  it('says every booked ship has a berth when no one is in line', () => {
    const [alongside, booked] = makeVisits()
    renderWithApp(<KpiStrip port={port} visits={[alongside, booked]} />)

    expect(tile('Ships booked')).toHaveTextContent('each with a berth and a time')
  })
})
