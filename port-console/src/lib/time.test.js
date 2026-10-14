import { describe, expect, it } from 'vitest'
import { dayClock, fromLocalInput, fullTime, relative, span, toLocalInput } from './time'

const MIN = 60_000
const now = new Date('2026-10-13T12:00:00Z')

describe('span', () => {
  it('says a length of time in the largest two units that matter', () => {
    expect(span(20_000)).toBe('under a minute')
    expect(span(45 * MIN)).toBe('45 min')
    expect(span(120 * MIN)).toBe('2 h')
    expect(span(190 * MIN)).toBe('3 h 10 min')
    expect(span(52 * 60 * MIN)).toBe('2 d 4 h')
    expect(span(-45 * MIN)).toBe('45 min')
  })
})

describe('relative', () => {
  it('reads forwards, backwards and now', () => {
    expect(relative('2026-10-13T14:10:00Z', now)).toBe('in 2 h 10 min')
    expect(relative('2026-10-13T11:35:00Z', now)).toBe('25 min ago')
    expect(relative('2026-10-13T12:00:20Z', now)).toBe('now')
  })
})

describe('dayClock', () => {
  it('shows the clock time in the chosen zone', () => {
    expect(dayClock('2026-10-13T14:05:00Z', 'UTC', now)).toBe('14:05')
    expect(dayClock('2026-10-13T14:05:00Z', 'Europe/Oslo', now)).toBe('16:05')
  })

  it('adds the day when it is not today in that zone', () => {
    expect(dayClock('2026-10-14T09:00:00Z', 'UTC', now)).toBe('Wed 09:00')
    // 23:30 UTC is already the next day in Oslo
    expect(dayClock('2026-10-13T23:30:00Z', 'Europe/Oslo', now)).toBe('Wed 01:30')
    expect(dayClock('2026-11-20T09:00:00Z', 'UTC', now)).toBe('20 Nov 09:00')
  })
})

describe('fullTime', () => {
  it('names the date and the zone', () => {
    expect(fullTime('2026-10-13T14:05:00Z', 'UTC')).toBe('13 Oct 2026, 14:05 UTC')
  })
})

describe('datetime-local inputs', () => {
  it('round-trip an instant through the zone the reader sees', () => {
    const text = toLocalInput('2026-10-13T14:05:00Z', 'Europe/Oslo')
    expect(text).toBe('2026-10-13T16:05')
    expect(fromLocalInput(text, 'Europe/Oslo').toISOString()).toBe('2026-10-13T14:05:00.000Z')
  })

  it('give null for something that is not a time', () => {
    expect(fromLocalInput('', 'UTC')).toBeNull()
    expect(fromLocalInput('not a time', 'UTC')).toBeNull()
  })
})
