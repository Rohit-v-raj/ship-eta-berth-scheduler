import { toMillis } from '@/lib/time'
import { vesselLabel } from '@/lib/visit'

/**
 * Turns the berth plan and the waiting visits into positions for the berth
 * timeline. Pure arithmetic, no drawing, so it can be tested without a browser.
 *
 * Time runs left to right: `domain` is [start, end] in milliseconds and maps
 * onto 0..width pixels.
 */

export const LANE_HEIGHT = 44
export const BAR_HEIGHT = 20
export const WAIT_ROW_HEIGHT = 26
const WAIT_PADDING = 9
// wide enough for capitals at the label's size; a name that might not fit is left to the tooltip and the table
const LABEL_CHAR_WIDTH = 8.2
const LABEL_PADDING = 16

const OPEN = ['PLANNED', 'ACTIVE']

export function layoutTimeline({ plan, waiting = [], domain, width, now }) {
  const [start, end] = domain
  const x = (ms) => ((ms - start) / (end - start)) * width
  const inView = (ms) => ms != null && ms >= start && ms <= end

  const lanes = plan.berths.map((lane, index) => ({
    key: lane.berthCode,
    label: lane.berthCode,
    sublabel: lane.terminalCode,
    outOfService: lane.status !== 'ACTIVE',
    y: index * LANE_HEIGHT,
    height: LANE_HEIGHT,
    bars: lane.windows.map((window) => toBar(window, { x, start, end, width, now, inView })).filter(Boolean),
    maintenance: (lane.maintenance ?? []).map((window) => toBlock(window, { x, start, end, width })).filter(Boolean),
    maintenanceUntil: maintenanceEndingAfter(lane.maintenance, now),
  }))

  const waitingTop = lanes.length * LANE_HEIGHT
  const waitingHeight = Math.max(LANE_HEIGHT, waiting.length * WAIT_ROW_HEIGHT + 2 * WAIT_PADDING)
  const clamp = (px) => Math.min(Math.max(px, 0), width)
  const waitingItems = waiting.map((visit, index) => {
    const eta = toMillis(visit.etaBerth)
    const requested = toMillis(visit.rtaBerth)
    return {
      visitRef: visit.visitRef,
      label: vesselLabel(visit.vessel),
      queueNumber: visit.queueNumber,
      y: waitingTop + WAIT_PADDING + index * WAIT_ROW_HEIGHT + WAIT_ROW_HEIGHT / 2,
      etaX: eta == null ? null : clamp(x(eta)),
      requestedX: requested == null ? null : clamp(x(requested)),
      agreed: visit.ptaBerth != null && toMillis(visit.ptaBerth) === requested,
      eta,
      requested,
    }
  })

  return {
    lanes,
    waiting: { y: waitingTop, height: waitingHeight, items: waitingItems },
    height: waitingTop + waitingHeight,
    nowX: inView(now) ? x(now) : null,
    x,
  }
}

function toBar(window, { x, start, end, width, now, inView }) {
  const open = OPEN.includes(window.status)
  const from = toMillis(window.actualStart ?? window.plannedStart)
  const plannedTo = toMillis(window.actualEnd ?? window.plannedEnd) ?? from
  if (from == null) return null

  // A berth still held after its planned end is overdue: the bar runs on to now.
  const overdue = open && plannedTo < now
  const to = overdue ? now : plannedTo
  if (to < start || from > end) return null

  const x0 = Math.max(x(from), 0)
  const x1 = Math.min(x(to), width)
  const label = window.vesselName || window.vesselCode
  const barWidth = Math.max(x1 - x0, 3)

  const marks = []
  if (open) {
    const add = (kind, value) => {
      const ms = toMillis(value)
      if (inView(ms)) marks.push({ kind, x: x(ms), ms })
    }
    if (window.status === 'ACTIVE') {
      add('ACTUAL', window.actualStart)
    } else {
      add('ESTIMATED', window.etaBerth)
      const agreed = window.ptaBerth != null && toMillis(window.ptaBerth) === toMillis(window.rtaBerth)
      add(agreed ? 'PLANNED' : 'REQUESTED', window.rtaBerth)
    }
  }

  return {
    visitRef: window.visitRef,
    label,
    status: window.status,
    x: x0,
    width: barWidth,
    clippedLeft: x(from) < 0,
    clippedRight: x(to) > width,
    overdue,
    overdueFromX: overdue ? Math.max(x(plannedTo), 0) : null,
    labelFits: barWidth >= label.length * LABEL_CHAR_WIDTH + LABEL_PADDING,
    from,
    to: plannedTo,
    marks,
  }
}

/** A maintenance window as a block on its lane, cut to the plotted period. */
function toBlock(window, { x, start, end, width }) {
  const from = toMillis(window.from)
  const to = toMillis(window.to)
  if (to < start || from > end) return null
  const x0 = Math.max(x(from), 0)
  const x1 = Math.min(x(to), width)
  return { id: window.id, x: x0, width: Math.max(x1 - x0, 2), from, to, reason: window.reason, labelFits: x1 - x0 >= 96 }
}

/** When the maintenance under way on a berth ends; null if none is under way. */
function maintenanceEndingAfter(windows = [], now) {
  const current = windows.find((window) => toMillis(window.from) <= now && now < toMillis(window.to))
  return current ? toMillis(current.to) : null
}

/**
 * Tick times for the axis: whole hours, a step chosen so labels do not crowd.
 * `hourOf` gives the hour of day a time has in the zone being shown, so ticks
 * fall on that zone's round hours rather than the browser's.
 */
export function timeTicks(domain, width, hourOf, minSpacing = 84) {
  const [start, end] = domain
  const HOUR = 3_600_000
  const hours = (end - start) / HOUR
  const maxTicks = Math.max(2, Math.floor(width / minSpacing))
  const step = [1, 2, 3, 6, 12, 24].find((s) => hours / s <= maxTicks) ?? 24
  const ticks = []
  for (let t = Math.ceil(start / HOUR) * HOUR; t <= end; t += HOUR) {
    if (hourOf(t) % step === 0) ticks.push(t)
  }
  return ticks
}
