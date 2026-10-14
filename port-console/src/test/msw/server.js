import { setupServer } from 'msw/node'
import { handlers } from './handlers'

/** The mock scheduler for the tests. */
export const server = setupServer(...handlers)
