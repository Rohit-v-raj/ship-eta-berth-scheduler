import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react'
import { useRefreshPort } from '@/api/queries'
import { getVisits } from '@/api/scheduler'
import { useTime } from '@/app/providers'
import { dayClock } from '@/lib/time'
import { resetPort, SCENARIOS } from './scenarios'

/**
 * Runs a story: which one, which steps are done, what each one did. One step
 * runs at a time, and a step that fails stays current so it can be tried again
 * once the cause is fixed.
 */

const StoryContext = createContext(null)

const IDLE = { scenarioId: null, done: [], busy: false, error: null, blockedBy: 0 }

export function StoryProvider({ port, children }) {
  const refresh = useRefreshPort()
  const { portZone } = useTime()
  const [state, setState] = useState(IDLE)
  const running = useRef(false)

  const scenario = SCENARIOS.find((s) => s.id === state.scenarioId) ?? null

  /** Run something that talks to the scheduler, one at a time, and refresh the screens after it. */
  const guarded = useCallback(
    async (work) => {
      if (running.current) return
      running.current = true
      setState((s) => ({ ...s, busy: true, error: null }))
      try {
        await work()
      } catch (failure) {
        setState((s) => ({ ...s, error: failure.message }))
      } finally {
        running.current = false
        setState((s) => ({ ...s, busy: false }))
        await refresh()
      }
    },
    [refresh],
  )

  /** Stories begin from an empty port. If it is not empty, say so instead of starting. */
  const start = useCallback(
    (scenarioId) =>
      guarded(async () => {
        const open = (await getVisits()).length
        setState({ ...IDLE, scenarioId, blockedBy: open })
      }),
    [guarded],
  )

  const reset = useCallback(
    () =>
      guarded(async () => {
        await resetPort(port)
        setState((s) => ({ ...s, done: [], blockedBy: 0 }))
      }),
    [guarded, port],
  )

  const next = useCallback(
    () =>
      guarded(async () => {
        if (!scenario) return
        const step = scenario.steps[state.done.length]
        if (!step) return
        const context = {
          port,
          destination: port.berths[0],
          time: (instant) => dayClock(instant, portZone, new Date()),
        }
        const caption = await step.run(context)
        setState((s) => ({ ...s, done: [...s.done, { title: step.title, sends: step.sends, caption }] }))
      }),
    [guarded, scenario, state.done.length, port, portZone],
  )

  const close = useCallback(() => setState(IDLE), [])

  const value = useMemo(
    () => ({
      scenarios: SCENARIOS,
      scenario,
      done: state.done,
      busy: state.busy,
      error: state.error,
      blockedBy: state.blockedBy,
      nextStep: scenario?.steps[state.done.length] ?? null,
      finished: Boolean(scenario) && state.done.length >= scenario.steps.length,
      start,
      next,
      reset,
      close,
    }),
    [scenario, state, start, next, reset, close],
  )
  return <StoryContext.Provider value={value}>{children}</StoryContext.Provider>
}

export function useStory() {
  const value = useContext(StoryContext)
  if (!value) throw new Error('useStory needs a StoryProvider above it')
  return value
}
