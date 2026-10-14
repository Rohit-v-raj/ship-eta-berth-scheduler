import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server'

// The app calls the API by path ("/api/port"). A browser resolves that against
// the page; Node's fetch does not, so resolve it here the way a browser would.
const nodeFetch = globalThis.fetch
globalThis.fetch = (input, init) =>
  nodeFetch(typeof input === 'string' && input.startsWith('/') ? new URL(input, window.location.origin).toString() : input, init)

// jsdom has no ResizeObserver or matchMedia; the components that use them only need them to exist.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.matchMedia ??= () => ({ matches: false, addEventListener() {}, removeEventListener() {} })

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  window.localStorage.clear()
})
afterAll(() => server.close())
