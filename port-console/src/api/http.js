import { recordExchange } from '@/features/wire/wireStore'

/**
 * The one way the console talks to the scheduler.
 *
 * Every request goes through here, which is what makes three things possible
 * in one place: a readable error for any failure, the wire log, and (later) a
 * login token on every request.
 */

export class ApiError extends Error {
  constructor(message, { status = 0, body = null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

// Login is not built yet. When it is, the session sets a provider that returns
// "Bearer <token>", and a handler for 401/403; nothing else in the app changes.
let authHeaderProvider = () => null
let unauthorizedHandler = () => {}

export function setAuthHeaderProvider(provider) {
  authHeaderProvider = provider
}

export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler
}

function buildUrl(path, params) {
  if (!params) return path
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, value)
  }
  const query = search.toString()
  return query ? `${path}?${query}` : path
}

function parse(text) {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

/** The most useful sentence the scheduler gave for a failure. */
function messageFrom(body, status) {
  if (body && typeof body === 'object') {
    if (typeof body.message === 'string') return body.message
    const feedback = body.feedbackElements?.find((f) => f.severity === 'ERROR') ?? body.feedbackElements?.[0]
    if (feedback?.message) return feedback.message
    if (typeof body.detail === 'string') return body.detail
    if (typeof body.error === 'string') return `${body.error} (HTTP ${status})`
  }
  return `The scheduler answered HTTP ${status}`
}

/**
 * @param {'GET'|'POST'|'PUT'} method
 * @param {string} path  starts with /api
 * @param {{ body?: unknown, params?: Record<string, unknown>, label?: string, quiet?: boolean }} [options]
 *   `label` names the request in the wire log; `quiet` keeps a polled read out of it.
 * @returns {Promise<{ data: any, headers: Headers, status: number }>}
 */
export async function http(method, path, { body, params, label, quiet = false } = {}) {
  const url = buildUrl(path, params)
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const authorization = authHeaderProvider()
  if (authorization) headers.Authorization = authorization

  const at = new Date()
  const started = performance.now()
  const log = (extra) => {
    if (quiet) return
    recordExchange({ at, method, url, label, requestBody: body, durationMs: Math.round(performance.now() - started), ...extra })
  }

  let response
  try {
    response = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    log({ status: 0, responseBody: null, failed: true })
    throw new ApiError('The scheduler could not be reached')
  }

  const data = parse(await response.text())
  log({ status: response.status, responseBody: data, failed: !response.ok })

  if (response.status === 401 || response.status === 403) unauthorizedHandler(response.status)
  if (!response.ok) {
    throw new ApiError(messageFrom(data, response.status), { status: response.status, body: data })
  }
  return { data, headers: response.headers, status: response.status }
}
