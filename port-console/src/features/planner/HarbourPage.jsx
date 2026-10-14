import { lazy, Suspense, useMemo, useState } from 'react'
import { useOutletContext, useSearchParams } from 'react-router-dom'
import { useBerthPlan, useMaintenance, useVisits } from '@/api/queries'
import { useTime } from '@/app/providers'
import { Button, Notice, Panel } from '@/components/ui/controls'
import { HOUR } from '@/lib/time'
import { STANDING, standingOf } from '@/lib/visit'
import { ArrivalsTable } from './ArrivalsTable'
import { BerthTimeline } from './BerthTimeline'
import { KpiStrip } from './KpiStrip'
import { MaintenancePanel } from './MaintenancePanel'
import { VisitDrawer } from './VisitDrawer'

const PortMap = lazy(() => import('@/features/map/PortMap'))

const RANGES = [12, 24, 48]

/**
 * The planner's page. It answers one question: is the berth plan safe for the
 * hours ahead? Figures first, then the plan against time beside the map, then
 * every visit as a table. A visit opens in a drawer; its reference is kept in
 * the address so the view can be shared or reloaded.
 */
export function HarbourPage() {
  const { port } = useOutletContext()
  const [searchParams, setSearchParams] = useSearchParams()
  const [hours, setHours] = useState(24)
  const selectedRef = searchParams.get('visit')
  const select = (visitRef) => setSearchParams(visitRef ? { visit: visitRef } : {}, { replace: true })

  const visitsQuery = useVisits()
  const visits = useMemo(() => visitsQuery.data ?? [], [visitsQuery.data])
  const waiting = useMemo(() => visits.filter((v) => standingOf(v) === STANDING.WAITING), [visits])

  // The plan is fetched for a window that moves on the hour, so the request stays the same between ticks.
  const { now } = useTime()
  const hourStart = Math.floor(now.getTime() / HOUR) * HOUR
  const range = useMemo(
    () => ({ from: new Date(hourStart - 6 * HOUR).toISOString(), to: new Date(hourStart + 50 * HOUR).toISOString() }),
    [hourStart],
  )
  const planQuery = useBerthPlan(range)
  const maintenance = useMaintenance().data

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-4 p-4">
      <h1 className="sr-only">Harbour view</h1>
      <KpiStrip port={port} visits={visits} maintenance={maintenance} />

      <div className="grid gap-4 xl:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
        <Panel title="Approaches" hint="Each ship at the position it last reported" bodyClassName="h-80 xl:h-auto xl:min-h-80">
          <Suspense fallback={<Notice title="Loading the map" />}>
            <PortMap port={port} visits={visits} selectedRef={selectedRef} onSelect={select} className="rounded-b-lg" />
          </Suspense>
        </Panel>

        <Panel
          title="Berth plan"
          hint="Who holds each berth, and until when"
          actions={
            <div role="group" aria-label="Hours ahead" className="flex rounded-md border border-line-strong p-0.5">
              {RANGES.map((value) => (
                <Button key={value} size="sm" variant={value === hours ? 'primary' : 'ghost'} aria-pressed={value === hours} onClick={() => setHours(value)} className="h-6 border-0">
                  {value} h
                </Button>
              ))}
            </div>
          }
        >
          {planQuery.data ? (
            <div className={planQuery.isPlaceholderData ? 'opacity-60' : undefined}>
              <BerthTimeline plan={planQuery.data} waiting={waiting} hours={hours} selectedRef={selectedRef} onSelect={select} />
            </div>
          ) : planQuery.isError ? (
            <Notice tone="error" title="The berth plan could not be loaded">{planQuery.error.message}</Notice>
          ) : (
            <Notice title="Loading the berth plan" />
          )}
        </Panel>
      </div>

      <Panel title="Arrivals" hint="Visits in progress, in queue order">
        {visitsQuery.isError ? (
          <Notice tone="error" title="The visits could not be loaded">{visitsQuery.error.message}</Notice>
        ) : visitsQuery.isPending ? (
          <Notice title="Loading visits" />
        ) : (
          <ArrivalsTable visits={visits} selectedRef={selectedRef} onSelect={select} />
        )}
      </Panel>

      <MaintenancePanel port={port} />

      <VisitDrawer visitRef={selectedRef} onClose={() => select(null)} />
    </div>
  )
}
