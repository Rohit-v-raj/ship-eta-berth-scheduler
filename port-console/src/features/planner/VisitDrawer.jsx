import { useState } from 'react'
import { Ban, LogOut, TriangleAlert } from 'lucide-react'
import { useRefreshPort, useVisit } from '@/api/queries'
import { useTime } from '@/app/providers'
import { cancelVisit, releaseBerth } from '@/api/scheduler'
import { AgreementChip, AttentionChips, StandingChip, TimeClassLabel, TimeMark, When } from '@/components/domain'
import { Button, Notice } from '@/components/ui/controls'
import { ConfirmDialog, Sheet } from '@/components/ui/overlay'
import { readableTimes, span, toMillis } from '@/lib/time'
import { answerOverdueMs, sizeText, STANDING, standingOf, TIME_CLASSES, vesselLabel, vesselTypeLabel } from '@/lib/visit'
import { useCan } from '@/session/session'

const SOURCE_LABEL = {
  VESSEL: 'the ship',
  TERMINAL: 'the terminal',
  PORT_AUTHORITY: 'the port authority',
  ML_MODEL: 'the ETA model',
  SYSTEM: 'the system',
}

const MILESTONE_LABEL = {
  ARRIVAL_PILOT_STATION: 'arrival at the pilot station',
  ARRIVAL_BERTH: 'arrival at berth',
  CARGO_OPS_START: 'start of cargo operations',
  CARGO_OPS_COMPLETE: 'end of cargo operations',
  DEPARTURE_BERTH: 'departure from berth',
}

/** One visit in full: where it stands, the four times and who gave each, and the whole ledger. */
export function VisitDrawer({ visitRef, onClose }) {
  const { data, isPending, isError, error } = useVisit(visitRef)
  const visit = data?.visit

  return (
    <Sheet
      open={Boolean(visitRef)}
      onOpenChange={(open) => !open && onClose()}
      title={visit ? vesselLabel(visit.vessel) : 'Visit'}
      description={visit ? `${vesselTypeLabel(visit.vessel.type)} · visit ${visit.visitRef}` : visitRef}
    >
      <div className="min-h-0 flex-1 overflow-y-auto">
        {isPending && <Notice title="Loading the visit" />}
        {isError && <Notice tone="error" title="The visit could not be loaded">{error.message}</Notice>}
        {data && <VisitBody detail={data} onClose={onClose} />}
      </div>
    </Sheet>
  )
}

function VisitBody({ detail, onClose }) {
  const { visit, events } = detail
  return (
    <div className="flex flex-col gap-6 px-5 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <StandingChip visit={visit} />
        <AgreementChip visit={visit} />
        <AttentionChips visit={visit} />
      </div>
      <p className="-mt-3 text-[13px] text-ink-2">
        {sizeText(visit) ?? 'Size unknown: the terminal assumes it fits any berth.'}
      </p>

      <section aria-labelledby="four-times">
        <h3 id="four-times" className="mb-2 text-sm font-semibold">Arrival at berth: the four times</h3>
        <FourTimes visit={visit} events={events} />
      </section>

      {visit.lastReport && (
        <section aria-labelledby="last-report">
          <h3 id="last-report" className="mb-2 text-sm font-semibold">Last position the ship gave</h3>
          <LastReport visit={visit} />
        </section>
      )}

      <section aria-labelledby="ledger">
        <h3 id="ledger" className="mb-2 text-sm font-semibold">Ledger: who said which time, and when</h3>
        <Ledger events={events} />
      </section>

      <PlannerActions visit={visit} onDone={onClose} />
    </div>
  )
}

/** Estimated, requested, planned, actual: the current value of each and who gave it. */
function FourTimes({ visit, events }) {
  const arrivals = events.filter((e) => e.milestone === 'ARRIVAL_BERTH')
  const latest = (classifier) => arrivals.filter((e) => e.classifier === classifier).at(-1)
  const cells = [
    { kind: 'ESTIMATED', value: visit.etaBerth },
    { kind: 'REQUESTED', value: visit.rtaBerth },
    { kind: 'PLANNED', value: visit.ptaBerth },
    { kind: 'ACTUAL', value: visit.ataBerth },
  ]
  return (
    <ol className="grid grid-cols-2 gap-px overflow-hidden rounded-md border border-line bg-line sm:grid-cols-4">
      {cells.map(({ kind, value }) => {
        const event = latest(kind)
        return (
          <li key={kind} className="bg-surface px-3 py-2.5">
            <TimeClassLabel kind={kind} className="text-xs font-medium text-ink-2" />
            <div className="mt-1 text-[15px] font-semibold"><When value={value} showRelative={false} /></div>
            <div className="mt-0.5 text-xs text-ink-2">
              {value && event ? `by ${SOURCE_LABEL[event.source]}` : value ? `by ${TIME_CLASSES[kind].by}` : notYet(kind)}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

function notYet(kind) {
  if (kind === 'PLANNED') return 'the ship has not accepted'
  if (kind === 'ACTUAL') return 'not yet arrived'
  return 'none yet'
}

function LastReport({ visit }) {
  const report = visit.lastReport
  const rows = [
    ['Sent', <When key="t" value={report.reportedAt} />],
    ['Position', report.lat == null || report.lon == null ? 'not given' : `${report.lat.toFixed(4)}, ${report.lon.toFixed(4)}`],
    ['Distance to go', report.distanceNm == null ? 'not given' : `${report.distanceNm.toFixed(1)} nm`],
    ['With the estimate', <When key="e" value={report.estimatedArrival} showRelative={false} />],
  ]
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[13px]">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-ink-2">{label}</dt>
          <dd className="tabular">{value}</dd>
        </div>
      ))}
    </dl>
  )
}

function Ledger({ events }) {
  const { zone, now } = useTime()
  const readable = (remark) => readableTimes(remark, zone, now)
  if (events.length === 0) return <p className="text-[13px] text-ink-2">Nothing has been reported for this visit.</p>
  return (
    <ol className="flex flex-col">
      {[...events].reverse().map((event, index) => (
        <li key={`${event.recordedAt}-${index}`} className="flex gap-3 border-b border-line py-2 text-[13px] last:border-b-0">
          <TimeMark kind={event.classifier} className="mt-1" />
          <div className="min-w-0 flex-1">
            <p>
              <span className="font-medium">{TIME_CLASSES[event.classifier].label}</span>{' '}
              {MILESTONE_LABEL[event.milestone] ?? event.milestone}
              {event.berthCode && `, ${event.berthCode}`}: <When value={event.eventTime} showRelative={false} className="font-medium" />
            </p>
            <p className="text-xs text-ink-2">
              from {SOURCE_LABEL[event.source] ?? event.source}, reported <When value={event.recordedAt} showRelative={false} />
            </p>
            {event.remarks && <p className="mt-0.5 text-xs text-ink-2">{readable(event.remarks)}</p>}
          </div>
        </li>
      ))}
    </ol>
  )
}

/**
 * What the planner may do to a visit. A ship alongside can have its berth
 * released, which records it as departed. A ship that has not arrived can
 * have its call cancelled, which is the planner's answer to a request the
 * ship leaves unanswered. A visit that is over offers neither.
 */
function PlannerActions({ visit, onDone }) {
  const standing = standingOf(visit)
  if (standing === STANDING.CLOSED) return null
  return standing === STANDING.ALONGSIDE ? <ReleaseBerth visit={visit} onDone={onDone} /> : <CancelCall visit={visit} onDone={onDone} />
}

/** One action behind a confirm dialog: busy while it runs, then refresh and close, or say why it failed. */
function useConfirmedAction(action, onDone) {
  const refresh = useRefreshPort()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const confirm = async () => {
    setBusy(true)
    setError(null)
    try {
      await action()
      await refresh()
      setOpen(false)
      onDone()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  return { open, setOpen, busy, error, confirm }
}

/** Free the berth of a ship alongside, which departs the ship and promotes the queue. */
function ReleaseBerth({ visit, onDone }) {
  const allowed = useCan('berth.release')
  const { open, setOpen, busy, error, confirm } = useConfirmedAction(() => releaseBerth(visit.berth.berthCode), onDone)
  if (!allowed) return null

  return (
    <section className="border-t border-line pt-4">
      <Button onClick={() => setOpen(true)}>
        <LogOut className="size-4" aria-hidden="true" />
        Release {visit.berth.berthCode}
      </Button>
      <p className="mt-1.5 text-xs text-ink-2">
        Records the ship as departed and passes the berth to the ship that has waited longest.
      </p>
      <ConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title={`Release ${visit.berth.berthCode}?`}
        description={`${vesselLabel(visit.vessel)} is recorded as departed now. The berth goes to the head of the queue, if anyone is waiting.`}
        confirmLabel="Release berth"
        onConfirm={confirm}
        busy={busy}
        error={error}
      />
    </section>
  )
}

const NO_ANSWER = 'no answer to the requested arrival'

/** Cancel the call of a ship that has not arrived. Its berth, or its place in the queue, passes on. */
function CancelCall({ visit, onDone }) {
  const allowed = useCan('visit.cancel')
  const { now } = useTime()
  const overdue = answerOverdueMs(visit, now) > 0
  const name = vesselLabel(visit.vessel)
  const { open, setOpen, busy, error, confirm } = useConfirmedAction(
    () => cancelVisit(visit.visitRef, overdue ? NO_ANSWER : undefined, `Cancel the call of ${name}`),
    onDone,
  )
  if (!allowed) return null

  const passesOn = visit.berth
    ? `${visit.berth.berthCode} goes to the head of the queue, if anyone is waiting.`
    : 'The ships behind it in the queue move up.'

  return (
    <section className="border-t border-line pt-4">
      {overdue && (
        <p className="mb-3 flex items-start gap-1.5 text-[13px]">
          <TriangleAlert className="mt-0.5 size-4 shrink-0 text-serious" aria-hidden="true" />
          <span>
            The ship has not answered the requested arrival, issued {span(toMillis(now) - toMillis(visit.requestedAt))} ago.
            Whether to keep waiting is your decision.
          </span>
        </p>
      )}
      <Button variant="danger" onClick={() => setOpen(true)}>
        <Ban className="size-4" aria-hidden="true" />
        Cancel this call
      </Button>
      <p className="mt-1.5 text-xs text-ink-2">
        For a ship that is not coming or does not answer. {passesOn} The ship may announce itself again and joins the back of the queue.
      </p>
      <ConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title={`Cancel the call of ${name}?`}
        description={`${name} loses ${visit.berth ? visit.berth.berthCode : 'its place in the queue'}, and its operator is told the terminal cancelled the call. ${passesOn}`}
        confirmLabel="Cancel the call"
        dismissLabel="Keep the call"
        onConfirm={confirm}
        busy={busy}
        error={error}
        danger
      />
    </section>
  )
}
