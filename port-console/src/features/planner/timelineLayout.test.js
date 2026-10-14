import { describe, expect, it } from 'vitest'
import { makeBerthPlan, makeVisits } from '@/test/fixtures'
import { layoutTimeline, timeTicks } from './timelineLayout'

const HOUR = 3_600_000
const now = Date.parse('2026-10-13T12:00:00Z')
// 4 hours back and 20 ahead on 480 px: 20 px per hour, "now" at 80 px
const domain = [now - 4 * HOUR, now + 20 * HOUR]
const width = 480

function layout(overrides = {}) {
  const [, , waiting] = makeVisits(now)
  return layoutTimeline({ plan: makeBerthPlan(now), waiting: [waiting], domain, width, now, ...overrides })
}

describe('layoutTimeline', () => {
  it('gives every berth a lane and puts the waiting lane underneath', () => {
    const result = layout()
    expect(result.lanes.map((lane) => lane.label)).toEqual(['B1', 'B2', 'B3', 'B4'])
    expect(result.waiting.y).toBe(result.lanes.at(-1).y + result.lanes.at(-1).height)
    expect(result.height).toBe(result.waiting.y + result.waiting.height)
    expect(result.nowX).toBe(80)
  })

  it('draws a reserved window from its planned start, and cuts it at the edge of the plot', () => {
    const [bar] = layout().lanes[0].bars
    expect(bar.label).toBe('SOLVIK')
    expect(bar.status).toBe('PLANNED')
    expect(bar.x).toBe(240) // 8 hours after now
    expect(bar.x + bar.width).toBe(width) // planned end is past the right edge
    expect(bar.clippedRight).toBe(true)
    expect(bar.clippedLeft).toBe(false)
  })

  it('draws a ship alongside from its actual arrival, starting off the left edge', () => {
    const [bar] = layout().lanes[1].bars
    expect(bar.status).toBe('ACTIVE')
    expect(bar.x).toBe(0)
    expect(bar.clippedLeft).toBe(true)
    expect(bar.x + bar.width).toBe(340) // planned free 13 hours after now
    expect(bar.overdue).toBe(false)
  })

  it('marks an agreed arrival as planned and an open request as requested', () => {
    const [agreed] = layout().lanes[0].bars
    expect(agreed.marks.map((m) => m.kind)).toEqual(['ESTIMATED', 'PLANNED'])

    const plan = makeBerthPlan(now)
    plan.berths[0].windows[0].ptaBerth = null
    const [open] = layout({ plan }).lanes[0].bars
    expect(open.marks.map((m) => m.kind)).toEqual(['ESTIMATED', 'REQUESTED'])
  })

  it('runs a berth held past its planned end on to now and flags it overdue', () => {
    const plan = makeBerthPlan(now)
    plan.berths[1].windows[0].plannedEnd = new Date(now - 1 * HOUR).toISOString()
    const [bar] = layout({ plan }).lanes[1].bars
    expect(bar.overdue).toBe(true)
    expect(bar.x + bar.width).toBe(80) // drawn up to now
    expect(bar.overdueFromX).toBe(60) // where the plan said it would end
  })

  it('leaves out a window that lies wholly outside the period', () => {
    const plan = makeBerthPlan(now)
    plan.berths[0].windows[0].plannedStart = new Date(now + 30 * HOUR).toISOString()
    plan.berths[0].windows[0].plannedEnd = new Date(now + 40 * HOUR).toISOString()
    expect(layout({ plan }).lanes[0].bars).toEqual([])
  })

  it('only labels a bar when the name fits inside it', () => {
    const plan = makeBerthPlan(now)
    plan.berths[0].windows[0].plannedStart = new Date(now + 19 * HOUR).toISOString()
    const [narrow] = layout({ plan }).lanes[0].bars
    expect(narrow.width).toBe(20)
    expect(narrow.labelFits).toBe(false)

    const [wide] = layout().lanes[0].bars
    expect(wide.width).toBe(240)
    expect(wide.labelFits).toBe(true)
  })

  it('draws planned maintenance as a block on its berth and says when maintenance under way ends', () => {
    const [block] = layout().lanes[2].maintenance
    expect(block.reason).toBe('Fender repair')
    expect(block.x).toBe(80 + 20 * 20) // from 20 hours after now
    expect(block.width).toBe(0 + 2) // the plot ends at 20 hours, so only a sliver shows
    expect(layout().lanes[2].maintenanceUntil).toBeNull()

    const plan = makeBerthPlan(now)
    plan.berths[2].maintenance[0].from = new Date(now - 2 * HOUR).toISOString()
    plan.berths[2].maintenance[0].to = new Date(now + 3 * HOUR).toISOString()
    const lane = layout({ plan }).lanes[2]
    expect(lane.maintenance[0].x).toBe(40)
    expect(lane.maintenance[0].width).toBe(100)
    expect(lane.maintenanceUntil).toBe(now + 3 * HOUR)
    expect(layout({ plan }).lanes[0].maintenance).toEqual([])
  })

  it('places a waiting ship by its estimate and the time it is asked to arrive', () => {
    const [item] = layout().waiting.items
    expect(item.label).toBe('HAVBRIS')
    expect(item.queueNumber).toBe(1)
    expect(item.etaX).toBeCloseTo(80 + 8.6 * 20)
    expect(item.requestedX).toBe(80 + 13 * 20)
    expect(item.agreed).toBe(false)
  })
})

describe('timeTicks', () => {
  const hourOfUtc = (ms) => new Date(ms).getUTCHours()

  it('uses a step that keeps the labels apart', () => {
    const ticks = timeTicks(domain, width, hourOfUtc)
    // 24 hours on 480 px leaves room for five labels, so the step is six hours
    expect(ticks.map((t) => new Date(t).toISOString().slice(11, 16))).toEqual(['12:00', '18:00', '00:00', '06:00'])
  })

  it('puts ticks on round hours of the zone being shown', () => {
    const osloHour = (ms) => (new Date(ms).getUTCHours() + 2) % 24
    const ticks = timeTicks(domain, width, osloHour)
    expect(ticks.every((t) => osloHour(t) % 6 === 0)).toBe(true)
  })
})
