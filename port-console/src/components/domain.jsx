import { Anchor, Ban, CircleCheck, Clock, Hourglass, LogOut, MapPin, TriangleAlert } from 'lucide-react'
import { useTime } from '@/app/providers'
import { cn } from '@/lib/cn'
import { dayClock, fullTime, relative, span, toMillis } from '@/lib/time'
import { AGREEMENT, agreementOf, answerOverdueMs, cancelledByTerminal, ordinal, STANDING, standingOf, TIME_CLASSES } from '@/lib/visit'

/** The pieces that show port-call facts the same way on every screen. */

/**
 * The mark for one of the four kinds of time. Shape and fill carry the
 * meaning, never colour: estimated is a hollow circle, requested a hollow
 * diamond, planned (the request accepted) a filled diamond, actual a filled
 * circle. Use it beside a text label; on its own it is decoration.
 */
export function TimeMark({ kind, size = 12, className, ring = false }) {
  const half = size / 2
  const r = half - 1.5
  const diamond = `M ${half} 1.2 L ${size - 1.2} ${half} L ${half} ${size - 1.2} L 1.2 ${half} Z`
  const filled = kind === 'PLANNED' || kind === 'ACTUAL'
  const shape =
    kind === 'ESTIMATED' || kind === 'ACTUAL' ? (
      <circle cx={half} cy={half} r={r} />
    ) : (
      <path d={diamond} />
    )
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className={cn('shrink-0', className)} aria-hidden="true">
      {ring && <g fill="var(--surface)" stroke="var(--surface)" strokeWidth="4">{shape}</g>}
      <g fill={filled ? 'currentColor' : 'var(--surface)'} stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round">
        {shape}
      </g>
    </svg>
  )
}

/** The mark with its name, for example "◇ Requested". */
export function TimeClassLabel({ kind, short = false, className }) {
  const info = TIME_CLASSES[kind]
  return (
    <span className={cn('inline-flex items-center gap-1.5', className)}>
      <TimeMark kind={kind} />
      <span>{short ? info.code : info.label}</span>
    </span>
  )
}

/**
 * A time for people: the clock time in the chosen zone, then how far away it
 * is. The full date and zone are in the title for anyone who needs them.
 */
export function When({ value, showRelative = true, className, empty = '—' }) {
  const { now, zone } = useTime()
  if (!value) return <span className={cn('text-muted', className)}>{empty}</span>
  return (
    <time dateTime={new Date(value).toISOString()} title={fullTime(value, zone)} className={cn('tabular whitespace-nowrap', className)}>
      {dayClock(value, zone, now)}
      {showRelative && <span className="ml-1.5 text-ink-2">{relative(value, now)}</span>}
    </time>
  )
}

const TONE_ICON = {
  good: 'text-good',
  warning: 'text-warning',
  serious: 'text-serious',
  critical: 'text-critical',
  accent: 'text-series-1',
  neutral: 'text-muted',
}

/** A state shown as icon plus words. The colour is on the icon only and is never the whole message. */
export function Chip({ icon: Icon, tone = 'neutral', children, className }) {
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-full border border-line bg-surface-2 px-2 py-0.5 text-xs font-medium whitespace-nowrap', className)}>
      {Icon && <Icon className={cn('size-3.5', TONE_ICON[tone])} aria-hidden="true" />}
      {children}
    </span>
  )
}

/** Where a visit stands: alongside, holding a berth it has not reached, waiting, or over. */
export function StandingChip({ visit }) {
  const standing = standingOf(visit)
  if (standing === STANDING.ALONGSIDE) return <Chip icon={Anchor} tone="good">Alongside {visit.berth.berthCode}</Chip>
  if (standing === STANDING.RESERVED) return <Chip icon={MapPin} tone="accent">{visit.berth.berthCode} reserved</Chip>
  if (standing === STANDING.WAITING) return <Chip icon={Hourglass} tone="warning">Waiting, {ordinal(visit.queueNumber)} in line</Chip>
  if (visit.phase !== 'CANCELLED') return <Chip icon={LogOut}>Departed</Chip>
  return <Chip icon={Ban}>{cancelledByTerminal(visit) ? 'Cancelled by the terminal' : 'Call omitted'}</Chip>
}

/**
 * How far the arrival time has been agreed between terminal and ship. A
 * request the ship has left unanswered past its due time is flagged; what to
 * do about it is the planner's decision.
 */
export function AgreementChip({ visit }) {
  const { now } = useTime()
  const agreement = agreementOf(visit)
  if (agreement === AGREEMENT.ARRIVED) return <Chip icon={CircleCheck} tone="good">Arrived</Chip>
  if (agreement === AGREEMENT.AGREED) return <Chip icon={CircleCheck} tone="good">Agreed</Chip>
  if (agreement === AGREEMENT.AWAITING) {
    return answerOverdueMs(visit, now) > 0 ? (
      <Chip icon={TriangleAlert} tone="serious">No answer for {span(toMillis(now) - toMillis(visit.requestedAt))}</Chip>
    ) : (
      <Chip icon={Clock} tone="warning">Awaiting the ship</Chip>
    )
  }
  return <span className="text-muted">—</span>
}

/**
 * What on a visit needs someone's attention: a ship holding a berth past its
 * agreed arrival without arriving, or a fresh estimate that is due. The
 * scheduler says when; the console only compares that with the clock.
 */
export function AttentionChips({ visit }) {
  const { now } = useTime()
  return (
    <>
      {visit.notArrivedSince && (
        <Chip icon={TriangleAlert} tone="serious">Not arrived, {span(toMillis(now) - toMillis(visit.notArrivedSince))} past agreed time</Chip>
      )}
      {visit.estimateDueSince && <Chip icon={Clock} tone="warning">Estimate due</Chip>}
    </>
  )
}

/** Knots with one decimal, for example "9.5 kn". */
export function knots(value) {
  return value == null ? '—' : `${Number(value).toFixed(1)} kn`
}
