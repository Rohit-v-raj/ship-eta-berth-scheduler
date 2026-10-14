import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

/** Light or dark, remembered between visits; the first visit follows the system. */

const ThemeContext = createContext(null)
const THEME_KEY = 'port-console.theme'

function initialTheme() {
  try {
    const stored = window.localStorage.getItem(THEME_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    // fall through to the system setting
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function ThemeProvider({ children }) {
  const [theme, setTheme] = useState(initialTheme)

  useEffect(() => {
    document.documentElement.dataset.theme = theme
  }, [theme])

  const toggleTheme = useCallback(() => {
    setTheme((current) => {
      const next = current === 'dark' ? 'light' : 'dark'
      try {
        window.localStorage.setItem(THEME_KEY, next)
      } catch {
        // not remembered
      }
      return next
    })
  }, [])

  const value = useMemo(() => ({ theme, toggleTheme }), [theme, toggleTheme])
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  return useContext(ThemeContext)
}

/**
 * One clock for the whole screen. `now` ticks every ten seconds, which is
 * often enough for "in 2 h 10 min" and for the timeline's now-line without
 * redrawing everything each second. `zone` is the zone times are shown in: the
 * port's own unless the reader has chosen UTC.
 */

const TimeContext = createContext(null)
const TICK_MS = 10_000

export function TimeProvider({ portZone = 'UTC', children }) {
  const [now, setNow] = useState(() => new Date())
  const [utc, setUtc] = useState(false)

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), TICK_MS)
    return () => window.clearInterval(timer)
  }, [])

  const value = useMemo(
    () => ({ now, zone: utc ? 'UTC' : portZone, portZone, utc, toggleUtc: () => setUtc((u) => !u) }),
    [now, utc, portZone],
  )
  return <TimeContext.Provider value={value}>{children}</TimeContext.Provider>
}

export function useTime() {
  const value = useContext(TimeContext)
  if (!value) throw new Error('useTime needs a TimeProvider above it')
  return value
}
