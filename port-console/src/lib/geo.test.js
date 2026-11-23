import { describe, expect, it } from 'vitest'
import { isPosition } from './geo'

describe('isPosition', () => {
  it('takes a real place, the poles and the date line included', () => {
    expect(isPosition(61.62, 4.45)).toBe(true)
    expect(isPosition(90, 180)).toBe(true)
    expect(isPosition(-90, -180)).toBe(true)
  })

  it('refuses a latitude or longitude out of range, or anything that is not a number', () => {
    expect(isPosition(500, 4.45)).toBe(false)
    expect(isPosition(-90.01, 4.45)).toBe(false)
    expect(isPosition(61.62, 180.5)).toBe(false)
    expect(isPosition(Number.NaN, 4.45)).toBe(false)
    expect(isPosition(61.62, Number.POSITIVE_INFINITY)).toBe(false)
    expect(isPosition(undefined, 4.45)).toBe(false)
  })
})
