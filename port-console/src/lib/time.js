import { formatInTimeZone, fromZonedTime } from 'date-fns-tz'

/**
 * Time helpers. The API speaks UTC instants (ISO strings). The screen shows
 * them in a zone the reader picks: the port's own, or UTC.
 */

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** A Date from an ISO string, a Date or null. */
export function toDate(value) {
  if (value == null) return null
  return value instanceof Date ? value : new Date(value)
}

export function toMillis(value) {
  const date = toDate(value)
  return date ? date.getTime() : null
}

/** "14:05" */
export function clock(value, zone) {
  const date = toDate(value)
  return date ? formatInTimeZone(date, zone, 'HH:mm') : ''
}

/** "14:05" when it is the same calendar day as now in that zone, otherwise "Wed 14:05". */
export function dayClock(value, zone, now = new Date()) {
  const date = toDate(value)
  if (!date) return ''
  const sameDay = formatInTimeZone(date, zone, 'yyyy-MM-dd') === formatInTimeZone(now, zone, 'yyyy-MM-dd')
  if (sameDay) return formatInTimeZone(date, zone, 'HH:mm')
  const withinAWeek = Math.abs(date.getTime() - now.getTime()) < 6 * DAY
  return formatInTimeZone(date, zone, withinAWeek ? 'EEE HH:mm' : 'd MMM HH:mm')
}

/** "13 Oct 2026, 14:05 CEST" */
export function fullTime(value, zone) {
  const date = toDate(value)
  return date ? formatInTimeZone(date, zone, 'd MMM yyyy, HH:mm zzz') : ''
}

/** A length of time in words: "45 min", "3 h 10 min", "2 d 4 h". */
export function span(ms) {
  const total = Math.round(Math.abs(ms) / MINUTE)
  if (total < 1) return 'under a minute'
  const days = Math.floor(total / (24 * 60))
  const hours = Math.floor((total % (24 * 60)) / 60)
  const minutes = total % 60
  if (days > 0) return hours > 0 ? `${days} d ${hours} h` : `${days} d`
  if (hours > 0) return minutes > 0 ? `${hours} h ${minutes} min` : `${hours} h`
  return `${minutes} min`
}

/** "in 2 h 10 min", "25 min ago" or "now". */
export function relative(value, now = new Date()) {
  const date = toDate(value)
  if (!date) return ''
  const delta = date.getTime() - toDate(now).getTime()
  if (Math.abs(delta) < MINUTE) return 'now'
  return delta > 0 ? `in ${span(delta)}` : `${span(delta)} ago`
}

/** The value for a datetime-local input showing this instant in the zone. */
export function toLocalInput(value, zone) {
  const date = toDate(value)
  return date ? formatInTimeZone(date, zone, "yyyy-MM-dd'T'HH:mm") : ''
}

/** The instant a datetime-local value means in the zone, or null if it is not a time. */
export function fromLocalInput(text, zone) {
  if (!text) return null
  const date = fromZonedTime(text, zone)
  return Number.isNaN(date.getTime()) ? null : date
}

const ISO_INSTANT = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g

/** A message from the scheduler with each UTC instant in it written as a clock time in the zone. */
export function readableTimes(text, zone, now = new Date()) {
  return text.replace(ISO_INSTANT, (instant) => dayClock(instant, zone, now))
}

export { MINUTE, HOUR, DAY }
