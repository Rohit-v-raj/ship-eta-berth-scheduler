import { recordExchange } from '@/features/wire/wireStore'

/**
 * The one way the console talks to the scheduler.
 *
 * Every request goes through here, which is what makes three things possible
 * in one place: a readable error for any failure, the wire log, and (later) a
 * login token on every request.
 *
 * The scheduler names each request in its `X-Request-Id` header, and its log
 * lines carry the same id. The wire log keeps it, and a failure on the
 * scheduler's side (5xx) ends its message with "Reference: <id>", so a person
 * can quote it and the log lines can be found.
 */

export const REQUEST_ID_HEADER = 'X-Request-Id'

export class ApiError extends Error {
  constructor(message, { status = 0, body = null, requestId = null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
    this.requestId = requestId
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
 * A failure on the scheduler's side gets the request's id to quote, unless the
 * message already names it. A refusal (4xx) is left as it is: its reason is
 * what the person acts on.
 */
export function withReference(message, status, requestId) {
  if (status < 500 || !requestId || message.includes(requestId)) return message
  const sentence = /[.?!]$/.test(message) ? message : `${message}.`
  return `${sentence} Reference: ${requestId}`
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
  const requestId = response.headers.get(REQUEST_ID_HEADER)
  log({ status: response.status, responseBody: data, failed: !response.ok, requestId })

  if (response.status === 401 || response.status === 403) unauthorizedHandler(response.status)
  if (!response.ok) {
    const message = withReference(messageFrom(data, response.status), response.status, requestId)
    throw new ApiError(message, { status: response.status, body: data, requestId })
  }
  return { data, headers: response.headers, status: response.status }
}
