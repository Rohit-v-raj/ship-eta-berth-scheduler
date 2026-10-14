import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import { can, ROLE_LABELS, ROLES } from './permissions'

/**
 * The session: who is using the console.
 *
 * @typedef {Object} Session
 * @property {string} role           one of ROLES
 * @property {string} displayName
 * @property {string[]|null} vessels vessel codes an operator may act for; null means any
 * @property {boolean} demo          true while there is no login
 *
 * Today the session comes from DemoSessionProvider: a seat the reader picks,
 * with no login. When login is built, a provider that reads a token supplies
 * the same shape through the same context, and no screen has to change.
 */

const SessionContext = createContext(null)
const STORAGE_KEY = 'port-console.seat'

const isRole = (value) => Object.values(ROLES).includes(value)

/** The seat to start in: one named in the address (?seat=operator, for a link into a demo), else the one remembered. */
function startingRole() {
  try {
    const asked = new URLSearchParams(window.location.search).get('seat')?.toUpperCase()
    if (isRole(asked)) return asked
    const stored = window.localStorage.getItem(STORAGE_KEY)
    return isRole(stored) ? stored : ROLES.PLANNER
  } catch {
    return ROLES.PLANNER
  }
}

export function DemoSessionProvider({ children, initialRole }) {
  const [role, setRole] = useState(() => initialRole ?? startingRole())

  const switchSeat = useCallback((next) => {
    setRole(next)
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // private browsing: the seat simply is not remembered
    }
  }, [])

  const value = useMemo(
    () => ({
      session: { role, displayName: ROLE_LABELS[role], vessels: null, demo: true },
      switchSeat,
    }),
    [role, switchSeat],
  )
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession() {
  const value = useContext(SessionContext)
  if (!value) throw new Error('useSession needs a session provider above it')
  return value
}

/** Whether the person at the console may take this action. */
export function useCan(action) {
  return can(useSession().session, action)
}
