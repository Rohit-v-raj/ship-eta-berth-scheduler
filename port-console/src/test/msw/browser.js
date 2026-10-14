import { http, passthrough } from 'msw'
import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

/** The mock scheduler for `npm run dev:mock`. Only the API is mocked; the map still loads its real tiles. */
export const worker = setupWorker(
  ...handlers,
  http.all('https://tiles.openfreemap.org/*', () => passthrough()),
)
