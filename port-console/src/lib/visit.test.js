import { describe, expect, it } from 'vitest'
import { makeVisits, withAnswerOverdue } from '@/test/fixtures'
import { AGREEMENT, agreementOf, answerOverdueMs, cancelledByTerminal, ordinal, STANDING, standingOf } from './visit'

const now = Date.parse('2026-10-13T12:00:00Z')
const [alongside, reserved, waiting] = makeVisits(now)
const HOUR = 3_600_000
const MINUTE = 60_000

describe('standingOf', () => {
  it('tells waiting, berth reserved, alongside and over apart', () => {
    expect(standingOf(waiting)).toBe(STANDING.WAITING)
    expect(standingOf(reserved)).toBe(STANDING.RESERVED)
    expect(standingOf(alongside)).toBe(STANDING.ALONGSIDE)
    expect(standingOf({ ...alongside, phase: 'DEPARTED' })).toBe(STANDING.CLOSED)
    expect(standingOf({ ...waiting, phase: 'CANCELLED' })).toBe(STANDING.CLOSED)
  })
})

describe('agreementOf', () => {
  it('is agreed only while the planned time equals the requested one', () => {
    expect(agreementOf(reserved)).toBe(AGREEMENT.AGREED)
    expect(agreementOf(waiting)).toBe(AGREEMENT.AWAITING)
    // the terminal issued a new request after the ship accepted the old one
    const overtaken = { ...reserved, rtaBerth: new Date(now + 9 * HOUR).toISOString() }
    expect(agreementOf(overtaken)).toBe(AGREEMENT.AWAITING)
  })

  it('is arrived once there is an actual arrival, and nothing before any request', () => {
    expect(agreementOf(alongside)).toBe(AGREEMENT.ARRIVED)
    expect(agreementOf({ ...waiting, rtaBerth: null })).toBe(AGREEMENT.NONE)
  })

  it('compares instants, not the text they were written in', () => {
    const sameInstant = { ...reserved, ptaBerth: reserved.rtaBerth.replace('.000Z', 'Z') }
    expect(agreementOf(sameInstant)).toBe(AGREEMENT.AGREED)
  })
})

describe('answerOverdueMs', () => {
  it('is nothing until the due time the scheduler gives has passed', () => {
    // the waiting ship was asked 12 minutes before "now" and has an hour to answer
    expect(answerOverdueMs(waiting, now)).toBe(0)
    expect(answerOverdueMs(waiting, now + 48 * MINUTE)).toBe(0)
    expect(answerOverdueMs(waiting, now + 2 * HOUR)).toBe(72 * MINUTE)
  })

  it('is nothing for a visit that owes no answer', () => {
    expect(answerOverdueMs(reserved, now + 48 * HOUR)).toBe(0)
    expect(answerOverdueMs(alongside, now + 48 * HOUR)).toBe(0)
    expect(answerOverdueMs(withAnswerOverdue(reserved, 3, now), now)).toBe(2 * HOUR)
  })
})

describe('cancelledByTerminal', () => {
  it('tells a call the terminal cancelled from one its operator omitted', () => {
    expect(cancelledByTerminal({ ...waiting, phase: 'CANCELLED', cancelledBy: 'TERMINAL' })).toBe(true)
    expect(cancelledByTerminal({ ...waiting, phase: 'CANCELLED', cancelledBy: 'VESSEL' })).toBe(false)
    expect(cancelledByTerminal(waiting)).toBe(false)
  })
})

describe('ordinal', () => {
  it('counts places in a queue', () => {
    expect([1, 2, 3, 4, 11].map(ordinal)).toEqual(['1st', '2nd', '3rd', '4th', '11th'])
  })
})
