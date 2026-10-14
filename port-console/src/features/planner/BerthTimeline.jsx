import { useId, useMemo, useState } from 'react'
import { formatInTimeZone } from 'date-fns-tz'
import { TriangleAlert } from 'lucide-react'
import { useTime } from '@/app/providers'
import { TimeMark } from '@/components/domain'
import { cn } from '@/lib/cn'
import { dayClock, HOUR, relative, span } from '@/lib/time'
import { useElementWidth } from '@/lib/useElementWidth'
import { BAR_HEIGHT, layoutTimeline, timeTicks } from './timelineLayout'

/**
 * The berth plan against time. One lane per berth, then a lane for the ships
 * that are waiting. A window is a light wash while the berth is only reserved
 * and solid once the ship is alongside; the marks on it are the arrival times
 * (estimated, requested or agreed, actual).
 *
 * The arrivals table below carries the same facts as text, so nothing here is
 * readable only by sight or by hovering. Planned maintenance is a hatched
 * block on its berth's lane; the maintenance panel lists it as text.
 */

const GUTTER = 84
const AXIS_HEIGHT = 30
const LOOK_BACK_HOURS = 3

const BAR_STYLE = {
  PLANNED: { fill: 'var(--accent-wash)', stroke: 'var(--series-1)', text: 'var(--ink)' },
  ACTIVE: { fill: 'var(--series-1)', stroke: 'none', text: '#ffffff' },
  COMPLETED: { fill: 'var(--series-quiet)', stroke: 'none', text: 'var(--ink)' },
}

export function BerthTimeline({ plan, waiting, hours, selectedRef, onSelect }) {
  const { now, zone } = useTime()
  const [ref, containerWidth] = useElementWidth(720)
  const [tip, setTip] = useState(null)
  const hatch = `maintenance-${useId().replace(/:/g, '')}`

  const nowMs = now.getTime()
  const width = Math.max(containerWidth - GUTTER, 200)
  const domain = useMemo(() => [nowMs - LOOK_BACK_HOURS * HOUR, nowMs + hours * HOUR], [nowMs, hours])
  const layout = useMemo(
    () => layoutTimeline({ plan, waiting, domain, width, now: nowMs }),
    [plan, waiting, domain, width, nowMs],
  )
  const ticks = useMemo(
    () => timeTicks(domain, width, (ms) => Number(formatInTimeZone(new Date(ms), zone, 'H'))),
    [domain, width, zone],
  )

  const show = (event, content) => {
    const box = ref.current.getBoundingClientRect()
    const point = event.clientX != null && event.clientX !== 0
      ? { x: event.clientX - box.left, y: event.clientY - box.top }
      : focusPoint(event.currentTarget, box)
    setTip({ ...point, content })
  }
  const hide = () => setTip(null)
  const pick = (visitRef) => (event) => {
    if (event.type === 'keydown' && event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    onSelect(visitRef)
  }

  return (
    <div ref={ref} className="relative select-none">
      <svg
        width={GUTTER + width}
        height={AXIS_HEIGHT + layout.height + 1}
        role="group"
        aria-label={`Berth plan for the next ${hours} hours`}
        className="block"
      >
        <defs>
          <pattern id={hatch} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="6" height="6" fill="var(--surface-2)" />
            <line x1="0" y1="0" x2="0" y2="6" stroke="var(--muted)" strokeWidth="1.5" />
          </pattern>
        </defs>

        {/* axis band */}
        <g transform={`translate(${GUTTER},0)`}>
          {ticks.map((t) => (
            <g key={t} transform={`translate(${layout.x(t)},0)`}>
              <line y1={AXIS_HEIGHT - 6} y2={AXIS_HEIGHT + layout.height} stroke="var(--line)" />
              {(layout.nowX == null || Math.abs(layout.x(t) - layout.nowX) > 34) && (
                <text y={AXIS_HEIGHT - 11} textAnchor="middle" fontSize="11" fill="var(--muted)" className="tabular">
                  {tickLabel(t, zone)}
                </text>
              )}
            </g>
          ))}
        </g>

        {/* lanes */}
        <g transform={`translate(0,${AXIS_HEIGHT})`}>
          {layout.lanes.map((lane) => (
            <g key={lane.key}>
              <line x1={0} x2={GUTTER + width} y1={lane.y} y2={lane.y} stroke="var(--line)" />
              <text x={12} y={lane.y + lane.height / 2 - 2} fontSize="13" fontWeight="600" fill="var(--ink)">
                {lane.label}
              </text>
              <text
                x={12}
                y={lane.y + lane.height / 2 + 12}
                fontSize="10.5"
                fill={lane.maintenanceUntil != null ? 'var(--ink)' : 'var(--muted)'}
                fontWeight={lane.maintenanceUntil != null ? 600 : 400}
              >
                {lane.outOfService
                  ? 'out of service'
                  : lane.maintenanceUntil != null
                    ? `maint. to ${dayClock(lane.maintenanceUntil, zone, now)}`
                    : lane.sublabel}
              </text>
            </g>
          ))}
          <line x1={0} x2={GUTTER + width} y1={layout.waiting.y} y2={layout.waiting.y} stroke="var(--line-strong)" />
          <text x={12} y={layout.waiting.y + 20} fontSize="13" fontWeight="600" fill="var(--ink)">Waiting</text>
          <text x={12} y={layout.waiting.y + 34} fontSize="10.5" fill="var(--muted)">
            {layout.waiting.items.length === 0 ? 'no one' : `${layout.waiting.items.length} in line`}
          </text>
          <line x1={0} x2={GUTTER + width} y1={layout.height} y2={layout.height} stroke="var(--line)" />

          <g transform={`translate(${GUTTER},0)`}>
            {layout.lanes.flatMap((lane) =>
              lane.maintenance.map((block) => (
                <MaintenanceBlock
                  key={`m-${block.id}`}
                  block={block}
                  berth={lane.label}
                  y={lane.y + 4}
                  height={lane.height - 8}
                  hatch={hatch}
                  zone={zone}
                  now={now}
                  onShow={(event) => show(event, <MaintenanceTip block={block} berth={lane.label} zone={zone} now={now} />)}
                  onHide={hide}
                />
              )),
            )}
            {layout.lanes.flatMap((lane) =>
              lane.bars.map((bar) => (
                <Bar
                  key={bar.visitRef}
                  bar={bar}
                  y={lane.y + (lane.height - BAR_HEIGHT) / 2}
                  selected={bar.visitRef === selectedRef}
                  onPick={pick(bar.visitRef)}
                  onShow={(event) => show(event, <BarTip bar={bar} berth={lane.label} zone={zone} now={now} />)}
                  onHide={hide}
                />
              )),
            )}

            {layout.waiting.items.map((item) => (
              <WaitingRow
                key={item.visitRef}
                item={item}
                width={width}
                selected={item.visitRef === selectedRef}
                onPick={pick(item.visitRef)}
                onShow={(event) => show(event, <WaitingTip item={item} zone={zone} now={now} />)}
                onHide={hide}
              />
            ))}

            {layout.nowX != null && (
              <g transform={`translate(${layout.nowX},0)`} pointerEvents="none">
                <line y1={-6} y2={layout.height} stroke="var(--ink)" strokeWidth="1.5" />
                <rect x={-15} y={-20} width={30} height={15} rx={3} fill="var(--ink)" />
                <text y={-9} textAnchor="middle" fontSize="10" fontWeight="600" fill="var(--surface)">now</text>
              </g>
            )}
          </g>
        </g>
      </svg>

      {tip && (
        <div
          role="status"
          className="pointer-events-none absolute z-10 w-60 rounded-md border border-line bg-surface px-3 py-2 text-xs shadow-panel"
          style={{ left: Math.min(Math.max(tip.x + 12, 0), containerWidth - 248), top: tip.y + 14 }}
        >
          {tip.content}
        </div>
      )}

      <Legend />
    </div>
  )
}

function focusPoint(target, box) {
  const rect = target.getBoundingClientRect()
  return { x: rect.left - box.left + rect.width / 2, y: rect.bottom - box.top }
}

function tickLabel(ms, zone) {
  const date = new Date(ms)
  const hour = formatInTimeZone(date, zone, 'HH:mm')
  return hour === '00:00' ? formatInTimeZone(date, zone, 'EEE d') : hour
}

function Bar({ bar, y, selected, onPick, onShow, onHide }) {
  const style = BAR_STYLE[bar.status] ?? BAR_STYLE.COMPLETED
  const left = bar.clippedLeft ? 0 : 4
  const right = bar.clippedRight ? 0 : 4
  return (
    <g
      role="button"
      tabIndex={0}
      aria-label={`${bar.label}, ${bar.status === 'ACTIVE' ? 'alongside' : bar.status === 'PLANNED' ? 'berth reserved' : 'departed'}. Open the visit.`}
      onClick={onPick}
      onKeyDown={onPick}
      onPointerMove={onShow}
      onPointerLeave={onHide}
      onFocus={onShow}
      onBlur={onHide}
      className="cursor-pointer outline-none [&:focus-visible>rect:first-of-type]:stroke-[var(--ink)]"
    >
      {/* a hit area taller than the bar, so it is easy to point at */}
      <rect x={bar.x - 2} y={y - 8} width={bar.width + 4} height={BAR_HEIGHT + 16} fill="transparent" strokeWidth="2" rx={4} />
      <path
        d={roundedRect(bar.x, y, bar.width, BAR_HEIGHT, left, right)}
        fill={style.fill}
        stroke={selected ? 'var(--ink)' : style.stroke}
        strokeWidth={selected ? 2 : 1}
      />
      {bar.overdue && bar.overdueFromX != null && (
        <line x1={bar.overdueFromX} x2={bar.overdueFromX} y1={y - 3} y2={y + BAR_HEIGHT + 3} stroke="var(--serious)" strokeWidth="2" />
      )}
      {bar.labelFits && (
        <text x={bar.x + 8} y={y + BAR_HEIGHT / 2 + 4} fontSize="12" fontWeight="500" fill={style.text} pointerEvents="none">
          {bar.label}
        </text>
      )}
      {bar.marks.map((mark) => (
        <g key={mark.kind} transform={`translate(${mark.x - 6},${y + BAR_HEIGHT / 2 - 6})`} color="var(--ink)" pointerEvents="none">
          <TimeMark kind={mark.kind} ring />
        </g>
      ))}
    </g>
  )
}

/** A rectangle whose left and right ends are rounded independently, so a bar cut off by the edge of the plot stays square there. */
function roundedRect(x, y, w, h, rl, rr) {
  const l = Math.min(rl, w / 2)
  const r = Math.min(rr, w / 2)
  return [
    `M ${x + l} ${y}`,
    `H ${x + w - r}`,
    r ? `Q ${x + w} ${y} ${x + w} ${y + r}` : '',
    `V ${y + h - r}`,
    r ? `Q ${x + w} ${y + h} ${x + w - r} ${y + h}` : '',
    `H ${x + l}`,
    l ? `Q ${x} ${y + h} ${x} ${y + h - l}` : '',
    `V ${y + l}`,
    l ? `Q ${x} ${y} ${x + l} ${y}` : '',
    'Z',
  ].join(' ')
}

function WaitingRow({ item, width, selected, onPick, onShow, onHide }) {
  const { etaX, requestedX, y } = item
  const anchor = requestedX ?? etaX ?? 0
  const labelRight = anchor < width - 130
  return (
    <g
      role="button"
      tabIndex={0}
      aria-label={`${item.label}, waiting, number ${item.queueNumber} in line. Open the visit.`}
      onClick={onPick}
      onKeyDown={onPick}
      onPointerMove={onShow}
      onPointerLeave={onHide}
      onFocus={onShow}
      onBlur={onHide}
      className="cursor-pointer outline-none [&:focus-visible>rect]:stroke-[var(--ink)]"
    >
      <rect x={0} y={y - 12} width={width} height={24} fill={selected ? 'var(--surface-2)' : 'transparent'} strokeWidth="1.5" rx={4} />
      {/* the gap between when the ship would arrive and when it is asked to: time it would spend waiting */}
      {etaX != null && requestedX != null && requestedX > etaX && (
        <line x1={etaX} x2={requestedX} y1={y} y2={y} stroke="var(--series-1)" strokeWidth="2" strokeDasharray="2 4" strokeLinecap="round" />
      )}
      {etaX != null && (
        <g transform={`translate(${etaX - 6},${y - 6})`} color="var(--ink)"><TimeMark kind="ESTIMATED" ring /></g>
      )}
      {requestedX != null && (
        <g transform={`translate(${requestedX - 6},${y - 6})`} color="var(--ink)">
          <TimeMark kind={item.agreed ? 'PLANNED' : 'REQUESTED'} ring />
        </g>
      )}
      <text x={labelRight ? anchor + 12 : anchor - 12} y={y + 4} textAnchor={labelRight ? 'start' : 'end'} fontSize="12" fontWeight="500" fill="var(--ink)" pointerEvents="none">
        {item.queueNumber}. {item.label}
      </text>
    </g>
  )
}

function MaintenanceBlock({ block, berth, y, height, hatch, zone, now, onShow, onHide }) {
  return (
    <g
      role="img"
      tabIndex={0}
      aria-label={`${berth} under maintenance from ${dayClock(block.from, zone, now)} to ${dayClock(block.to, zone, now)}${block.reason ? `: ${block.reason}` : ''}`}
      onPointerMove={onShow}
      onPointerLeave={onHide}
      onFocus={onShow}
      onBlur={onHide}
      className="outline-none [&:focus-visible>rect]:stroke-[var(--ink)]"
    >
      <rect x={block.x} y={y} width={block.width} height={height} fill={`url(#${hatch})`} stroke="var(--line-strong)" rx={3} />
      {block.labelFits && (
        <text x={block.x + 6} y={y + height - 6} fontSize="10.5" fontWeight="600" fill="var(--ink)" pointerEvents="none">
          Maintenance
        </text>
      )}
    </g>
  )
}

function MaintenanceTip({ block, berth, zone, now }) {
  return (
    <>
      <p className="text-[13px] font-semibold">Maintenance on {berth}</p>
      {block.reason && <p className="text-ink-2">{block.reason}</p>}
      <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
        <dt className="text-ink-2">From</dt>
        <dd className="tabular">{dayClock(block.from, zone, now)}</dd>
        <dt className="text-ink-2">To</dt>
        <dd className="tabular">{dayClock(block.to, zone, now)} <span className="text-ink-2">{relative(block.to, now)}</span></dd>
      </dl>
      <p className="mt-1.5">No ship is given this berth for a stay that runs into it.</p>
    </>
  )
}

function BarTip({ bar, berth, zone, now }) {
  const state = bar.status === 'ACTIVE' ? 'Alongside' : bar.status === 'PLANNED' ? 'Berth reserved' : 'Departed'
  return (
    <>
      <p className="text-[13px] font-semibold">{bar.label}</p>
      <p className="text-ink-2">{state} at {berth}</p>
      <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
        <dt className="text-ink-2">From</dt>
        <dd className="tabular">{dayClock(bar.from, zone, now)}</dd>
        <dt className="text-ink-2">{bar.status === 'COMPLETED' ? 'Left' : 'Planned free'}</dt>
        <dd className="tabular">{dayClock(bar.to, zone, now)} <span className="text-ink-2">{relative(bar.to, now)}</span></dd>
      </dl>
      {bar.overdue && (
        <p className="mt-1.5 flex items-center gap-1.5">
          <TriangleAlert className="size-3.5 text-serious" aria-hidden="true" />
          Still on the berth past its planned end
        </p>
      )}
    </>
  )
}

function WaitingTip({ item, zone, now }) {
  const wait = item.eta != null && item.requested != null ? item.requested - item.eta : 0
  return (
    <>
      <p className="text-[13px] font-semibold">{item.label}</p>
      <p className="text-ink-2">Waiting, number {item.queueNumber} in line</p>
      <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
        <dt className="text-ink-2">Estimated</dt>
        <dd className="tabular">{item.eta == null ? '—' : dayClock(item.eta, zone, now)}</dd>
        <dt className="text-ink-2">{item.agreed ? 'Agreed' : 'Requested'}</dt>
        <dd className="tabular">{item.requested == null ? '—' : dayClock(item.requested, zone, now)}</dd>
      </dl>
      {wait > 60_000 && <p className="mt-1.5">At its present speed it would wait {span(wait)} for the berth.</p>}
    </>
  )
}

function Legend() {
  const swatch = 'inline-block h-2.5 w-5 rounded-sm'
  return (
    <ul className="flex flex-wrap items-center gap-x-5 gap-y-1 border-t border-line px-4 py-2 text-xs text-ink-2">
      <li className="flex items-center gap-1.5">
        <span className={cn(swatch, 'border border-series-1 bg-accent-wash')} aria-hidden="true" /> Berth reserved
      </li>
      <li className="flex items-center gap-1.5">
        <span className={cn(swatch, 'bg-series-1')} aria-hidden="true" /> Alongside
      </li>
      <li className="flex items-center gap-1.5">
        <span className={cn(swatch, 'bg-series-quiet')} aria-hidden="true" /> Departed
      </li>
      <li className="flex items-center gap-1.5">
        <span
          className={cn(swatch, 'border border-line-strong')}
          style={{ background: 'repeating-linear-gradient(45deg, var(--muted) 0 1.5px, var(--surface-2) 1.5px 5px)' }}
          aria-hidden="true"
        />{' '}
        Maintenance
      </li>
      <li className="flex items-center gap-1.5 text-ink"><TimeMark kind="ESTIMATED" /> <span className="text-ink-2">Estimated</span></li>
      <li className="flex items-center gap-1.5 text-ink"><TimeMark kind="REQUESTED" /> <span className="text-ink-2">Requested</span></li>
      <li className="flex items-center gap-1.5 text-ink"><TimeMark kind="PLANNED" /> <span className="text-ink-2">Agreed</span></li>
      <li className="flex items-center gap-1.5 text-ink"><TimeMark kind="ACTUAL" /> <span className="text-ink-2">Actual</span></li>
    </ul>
  )
}
