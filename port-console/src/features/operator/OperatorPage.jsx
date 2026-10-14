import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useOutletContext, useParams } from 'react-router-dom'
import { ArrowLeft, Ban, Gauge, Ship } from 'lucide-react'
import { useVisits } from '@/api/queries'
import { askEta } from '@/api/scheduler'
import { useTime } from '@/app/providers'
import { AgreementChip, knots, StandingChip, When } from '@/components/domain'
import { Button, Field, Input, Notice, Panel } from '@/components/ui/controls'
import { relative, span, toMillis } from '@/lib/time'
import { cancelledByTerminal, STANDING, standingOf, vesselLabel, vesselTypeLabel } from '@/lib/visit'
import { DecisionBar } from './DecisionBar'
import { PositionForm } from './PositionForm'

const PortMap = lazy(() => import('@/features/map/PortMap'))

/**
 * The vessel operator's page. One ship at a time, and one question: when am I
 * wanted, and how fast should I sail? The terminal answers the first half; the
 * second is the operator's own sum, done by the calculator on this page. Without a vessel in the address it asks
 * which ship the operator is acting for.
 */
export function OperatorPage() {
  const { port } = useOutletContext()
  const { vesselId } = useParams()
  return vesselId ? <VesselDesk key={vesselId} port={port} vesselCode={vesselId} /> : <ChooseVessel />
}

function ChooseVessel() {
  const navigate = useNavigate()
  const { data: visits = [], isPending } = useVisits()
  const [code, setCode] = useState('')
  const [problem, setProblem] = useState(null)

  const open = (event) => {
    event.preventDefault()
    const cleaned = code.trim().toUpperCase()
    if (!/^[A-Z0-9][A-Z0-9 _-]{1,30}$/.test(cleaned)) {
      return setProblem('Use 2 to 31 letters, digits, spaces, hyphens or underscores')
    }
    return navigate(`/operator/${encodeURIComponent(cleaned)}`)
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 p-4">
      <h1 className="text-lg font-semibold">Which ship are you acting for?</h1>
      <Panel title="Ships with a call in progress">
        {isPending ? (
          <Notice title="Loading" />
        ) : visits.length === 0 ? (
          <Notice icon={Ship} title="No ship has a call in progress">Name a ship below to report its position and request a berth.</Notice>
        ) : (
          <ul>
            {visits.map((visit) => (
              <li key={visit.visitRef} className="border-b border-line last:border-b-0">
                <Link to={`/operator/${encodeURIComponent(visit.vessel.code)}`} className="flex items-center justify-between gap-3 px-4 py-3 hover:bg-surface-2">
                  <span>
                    <span className="font-semibold">{vesselLabel(visit.vessel)}</span>
                    <span className="ml-2 text-xs text-ink-2">{vesselTypeLabel(visit.vessel.type)}</span>
                  </span>
                  <StandingChip visit={visit} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <Panel title="Another ship">
        <form onSubmit={open} className="flex items-end gap-2 px-4 py-4">
          <Field id="vessel-code" label="Vessel name or code" error={problem} className="flex-1">
            {(a11y) => <Input {...a11y} value={code} onChange={(event) => setCode(event.target.value)} placeholder="for example SOLVIK" autoComplete="off" />}
          </Field>
          <Button type="submit" variant="primary">Open</Button>
        </form>
      </Panel>
    </div>
  )
}

const sameInstant = (a, b) => a != null && b != null && toMillis(a) === toMillis(b)

function VesselDesk({ port, vesselCode }) {
  const filters = useMemo(() => ({ vessel: vesselCode }), [vesselCode])
  const { data, isPending, isError, error } = useVisits(filters)
  const visit = data?.[0] ?? null
  // With no call in progress, look at the last one: if the terminal cancelled it, this is where the operator hears of it.
  const pastFilters = useMemo(() => ({ vessel: vesselCode, state: 'all' }), [vesselCode])
  const past = useVisits(pastFilters, { enabled: !isPending && !isError && !visit })
  const lastCall = visit ? null : past.data?.[0] ?? null
  const [picked, setPicked] = useState(null)
  const [position, setPosition] = useState(null)
  const [answer, setAnswer] = useState(null)
  const onPositionChange = useCallback((next) => setPosition(next), [])
  const mapVisits = useMemo(() => (visit ? [visit] : []), [visit])

  // The speed in the calculator's answer is for one requested arrival. When the
  // terminal changes its request, ask again with the same position.
  const requested = visit && !visit.ataBerth ? visit.rtaBerth : null
  const lastQuery = answer?.query
  const answeredFor = answer?.estimate.requestedArrival ?? null
  useEffect(() => {
    if (!lastQuery || !requested || sameInstant(requested, answeredFor)) return undefined
    let cancelled = false
    askEta(lastQuery, 'Recalculate: the requested arrival changed')
      .then((estimate) => {
        if (!cancelled) setAnswer((current) => (current ? { ...current, estimate, sent: false } : current))
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [requested, answeredFor, lastQuery])

  return (
    <div className="mx-auto flex max-w-[1300px] flex-col gap-4 p-4">
      <div className="flex items-center gap-3">
        <Link to="/operator" aria-label="Back to the list of ships" className="rounded-md p-1.5 text-ink-2 hover:bg-surface-2 hover:text-ink">
          <ArrowLeft className="size-4" aria-hidden="true" />
        </Link>
        <h1 className="text-lg font-semibold">{visit ? vesselLabel(visit.vessel) : vesselCode}</h1>
        {visit && <span className="text-[13px] text-ink-2">{vesselTypeLabel(visit.vessel.type)} · visit {visit.visitRef}</span>}
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,6fr)_minmax(0,5fr)]">
        <div className="flex min-w-0 flex-col gap-4">
          <Panel title="Where you stand">
            {isError ? (
              <Notice tone="error" title="Your call could not be loaded">{error.message}</Notice>
            ) : isPending ? (
              <Notice title="Loading" />
            ) : visit ? (
              <Outcome visit={visit} port={port} answer={answer} />
            ) : lastCall && cancelledByTerminal(lastCall) ? (
              <Notice icon={Ban} title={`The terminal cancelled ${vesselCode}'s call`}>
                {lastCall.remarks ? `${lastCall.remarks}. ` : ''}
                The berth or queue place it held has passed on. Send a new estimated arrival below to ask again; the ship joins the back of the queue.
              </Notice>
            ) : (
              <Notice icon={Ship} title={`${vesselCode} has no call in progress`}>
                Work out your arrival below and send it. The terminal answers with a berth or a place in the queue, and the time it wants you there.
              </Notice>
            )}
          </Panel>

          <Panel title="Your calculator" hint="When you would arrive from here, and the speed that meets the terminal's time">
            {!isPending && (
              <PositionForm
                port={port}
                vesselCode={vesselCode}
                visit={visit}
                picked={picked}
                onPositionChange={onPositionChange}
                answer={answer}
                onAnswer={setAnswer}
              />
            )}
          </Panel>
        </div>

        <Panel title="Chart" hint="Click to set your position" bodyClassName="h-80 lg:h-auto lg:min-h-[28rem]">
          <Suspense fallback={<Notice title="Loading the map" />}>
            <PortMap port={port} visits={mapVisits} picked={position} onPick={setPicked} className="rounded-b-lg" />
          </Suspense>
        </Panel>
      </div>
    </div>
  )
}

/**
 * The terminal's answer, largest first: where the ship stands and when it is
 * wanted. Beside it, the speed that meets that time, which is the operator's
 * own calculation and shown only once it has been made for this request.
 */
function Outcome({ visit, port, answer }) {
  const { now } = useTime()
  const standing = standingOf(visit)
  const fresh = answer && sameInstant(answer.estimate.requestedArrival, visit.rtaBerth) ? answer : null
  const early = fresh ? toMillis(visit.rtaBerth) - toMillis(fresh.estimate.estimatedArrival) : 0

  const headline =
    standing === STANDING.ALONGSIDE
      ? `You are alongside ${visit.berth.berthName}`
      : standing === STANDING.RESERVED
        ? `${visit.berth.berthName} is reserved for you`
        : 'Every berth is taken: you are in the queue'

  return (
    <div className="flex flex-col gap-5 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <p className="mr-1 text-base font-semibold">{headline}</p>
        <StandingChip visit={visit} />
      </div>

      {standing !== STANDING.ALONGSIDE && (
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <p className="text-[13px] text-ink-2">The terminal asks you to arrive at</p>
            <p className="mt-0.5 text-3xl leading-tight font-semibold"><When value={visit.rtaBerth} showRelative={false} /></p>
            <p className="mt-1 flex flex-wrap items-center gap-2 text-[13px] text-ink-2">
              {relative(visit.rtaBerth, now)}
              <AgreementChip visit={visit} />
            </p>
          </div>

          <div className="rounded-md border border-line bg-surface-2 px-4 py-3">
            <p className="flex items-center gap-1.5 text-[13px] text-ink-2">
              <Gauge className="size-4" aria-hidden="true" /> Speed to meet it
            </p>
            {!fresh ? (
              <>
                <p className="mt-0.5 text-xl leading-tight font-semibold">Yours to work out</p>
                <p className="mt-1 text-[13px]">The terminal sends a time, not a speed. Enter your position in the calculator below to see what speed meets this time.</p>
              </>
            ) : fresh.estimate.advisedSpeedKnots != null ? (
              <>
                <p className="mt-0.5 text-3xl leading-tight font-semibold">Slow to {knots(fresh.estimate.advisedSpeedKnots)}</p>
                <p className="mt-1 text-[13px]">
                  from {knots(fresh.query.sog)}. At your present speed you would arrive about {span(early)} before the berth is free.
                </p>
              </>
            ) : early > 60_000 ? (
              <>
                <p className="mt-0.5 text-xl leading-tight font-semibold">No slower speed worth sailing</p>
                <p className="mt-1 text-[13px]">You would arrive about {span(early)} before the berth is free, but you are already at or below the slowest speed worth advising.</p>
              </>
            ) : (
              <>
                <p className="mt-0.5 text-xl leading-tight font-semibold">Hold your speed</p>
                <p className="mt-1 text-[13px]">By the calculator&apos;s estimate you arrive no earlier than the terminal asks.</p>
              </>
            )}
            <p className="mt-1.5 text-xs text-ink-2">Your own calculation, not an instruction. No fuel or CO2 saving is calculated.</p>
          </div>
        </div>
      )}

      {visit.lastReport && standing !== STANDING.ALONGSIDE && (
        <p className="text-xs text-ink-2">
          Your last estimate, sent <When value={visit.lastReport.reportedAt} showRelative />, told the terminal you were
          {visit.lastReport.distanceNm != null ? ` ${visit.lastReport.distanceNm.toFixed(1)} nm out` : ' under way'}.
        </p>
      )}

      {standing !== STANDING.ALONGSIDE && (visit.estimateDueSince || visit.nextEstimateBy) && (
        <p className="text-xs text-ink-2">
          {visit.estimateDueSince ? (
            <>
              <span className="font-semibold text-ink">A fresh estimate is due.</span> The terminal expected one by{' '}
              <When value={visit.estimateDueSince} />. Send it from the calculator below.
            </>
          ) : (
            <>The terminal expects your next estimate by <When value={visit.nextEstimateBy} />.</>
          )}
        </p>
      )}

      <DecisionBar visit={visit} port={port} />
    </div>
  )
}
