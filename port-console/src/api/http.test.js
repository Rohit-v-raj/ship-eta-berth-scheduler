import { renderHook } from '@testing-library/react'
import { http as route, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { server } from '@/test/msw/server'
import { clearExchanges, useWireLog } from '@/features/wire/wireStore'
import { ApiError, http, withReference } from './http'
import { askEta } from './scheduler'

const ID = '3f9c2a7b41d0'

function answer(status, body) {
  return HttpResponse.json(body, { status, headers: { 'X-Request-Id': ID } })
}

async function failure(promise) {
  try {
    await promise
  } catch (error) {
    return error
  }
  throw new Error('the request did not fail')
}

afterEach(() => clearExchanges())

describe('the request id', () => {
  it('ends a failure on the scheduler side with the reference to quote', async () => {
    server.use(route.get('/api/port', () => answer(503, { message: 'The ETA model service is not answering; try again shortly' })))
    const error = await failure(http('GET', '/api/port'))
    expect(error).toBeInstanceOf(ApiError)
    expect(error.message).toBe(`The ETA model service is not answering; try again shortly. Reference: ${ID}`)
    expect(error.requestId).toBe(ID)
  })

  it('leaves a refusal as the scheduler worded it', async () => {
    server.use(route.put('/api/visits/V-1/cancel', () => answer(409, { message: 'Conflict: Visit V-1 is already over' })))
    const error = await failure(http('PUT', '/api/visits/V-1/cancel', { body: {} }))
    expect(error.message).toBe('Conflict: Visit V-1 is already over')
    expect(error.requestId).toBe(ID)
  })

  it('does not name the reference twice', () => {
    const message = `Something went wrong in the scheduler. Reference: ${ID}`
    expect(withReference(message, 500, ID)).toBe(message)
    expect(withReference('Gateway failed.', 502, ID)).toBe(`Gateway failed. Reference: ${ID}`)
    expect(withReference('Gateway failed', 502, null)).toBe('Gateway failed')
    expect(withReference('Is it running?', 503, ID)).toBe(`Is it running? Reference: ${ID}`)
  })

  it('is kept when the calculator explains a model failure in its own words', async () => {
    server.use(route.post('/api/operator/eta', () => answer(503, { message: 'The ETA model service is not answering; try again shortly' })))
    const error = await failure(askEta({ vesselId: 'LOGTEST' }))
    expect(error.message).toBe(`The scheduler could not get an ETA. Is the ETA model service running on port 8000? Reference: ${ID}`)
    expect(error.requestId).toBe(ID)
  })

  it('is kept in the wire log', async () => {
    server.use(route.put('/api/port/berths/B1/release', () => answer(200, { berthCode: 'B1', released: true })))
    await http('PUT', '/api/port/berths/B1/release', { label: 'Release B1' })
    const { result } = renderHook(() => useWireLog())
    expect(result.current[0]).toMatchObject({ method: 'PUT', status: 200, requestId: ID })
  })
})
