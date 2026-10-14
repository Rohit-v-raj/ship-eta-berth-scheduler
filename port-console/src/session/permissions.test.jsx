import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithApp } from '@/test/render'
import { can, HOME, PERMISSIONS, ROLES } from './permissions'
import { RequireRole } from './RequireRole'

describe('can', () => {
  const planner = { role: ROLES.PLANNER }
  const operator = { role: ROLES.OPERATOR }

  it('lets only the planner release a berth or cancel a call, and only the operator answer the terminal', () => {
    expect(can(planner, 'berth.release')).toBe(true)
    expect(can(operator, 'berth.release')).toBe(false)
    expect(can(planner, 'visit.cancel')).toBe(true)
    expect(can(operator, 'visit.cancel')).toBe(false)
    expect(can(planner, 'berth.maintain')).toBe(true)
    expect(can(operator, 'berth.maintain')).toBe(false)
    expect(can(operator, 'visit.answer')).toBe(true)
    expect(can(planner, 'visit.answer')).toBe(false)
  })

  it('lets both read the event feed', () => {
    expect(can(planner, 'events.view')).toBe(true)
    expect(can(operator, 'events.view')).toBe(true)
  })

  it('refuses everything without a session, and rejects an action nobody defined', () => {
    expect(can(null, 'plan.view')).toBe(false)
    expect(() => can(planner, 'berth.demolish')).toThrow(/Unknown action/)
  })

  it('gives every role a page to land on and every permission a known role', () => {
    const roles = Object.values(ROLES)
    roles.forEach((role) => expect(HOME[role]).toMatch(/^\//))
    Object.values(PERMISSIONS).flat().forEach((role) => expect(roles).toContain(role))
  })
})

describe('RequireRole', () => {
  it('shows the page to a seat with the permission', () => {
    renderWithApp(<RequireRole action="plan.view"><p>the berth plan</p></RequireRole>, { role: ROLES.PLANNER })
    expect(screen.getByText('the berth plan')).toBeInTheDocument()
  })

  it('says not allowed to a seat without it, and does not render the page', () => {
    renderWithApp(<RequireRole action="plan.view"><p>the berth plan</p></RequireRole>, { role: ROLES.OPERATOR })
    expect(screen.queryByText('the berth plan')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Not allowed' })).toBeInTheDocument()
    expect(screen.getByText(/not open to the vessel operator seat/)).toBeInTheDocument()
  })
})
