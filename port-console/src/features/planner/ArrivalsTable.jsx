import { Ship } from 'lucide-react'
import { AgreementChip, AttentionChips, StandingChip, When } from '@/components/domain'
import { Notice } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { vesselLabel, vesselTypeLabel } from '@/lib/visit'

/**
 * Every visit in progress, as text. This is the planner's working list and
 * also the accessible twin of the map and the timeline: anything they show is
 * in a cell here.
 */
export function ArrivalsTable({ visits, selectedRef, onSelect }) {
  if (visits.length === 0) {
    return (
      <Notice icon={Ship} title="No ships are expected">
        A visit opens when a ship's operator sends an estimated arrival. Run a story below to see one.
      </Notice>
    )
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[920px] border-collapse text-left text-[13px]">
        <caption className="sr-only">Visits in progress, in queue order</caption>
        <thead>
          <tr className="border-b border-line text-xs text-ink-2">
            <Th>Vessel</Th>
            <Th>Standing</Th>
            <Th>Estimated arrival</Th>
            <Th>Requested arrival</Th>
            <Th>Agreement</Th>
            <Th>Berth planned free</Th>
            <Th>Last position</Th>
          </tr>
        </thead>
        <tbody>
          {visits.map((visit) => (
            <tr
              key={visit.visitRef}
              onClick={() => onSelect(visit.visitRef)}
              aria-selected={visit.visitRef === selectedRef}
              className={cn(
                'cursor-pointer border-b border-line last:border-b-0 hover:bg-surface-2',
                visit.visitRef === selectedRef && 'bg-surface-2',
              )}
            >
              <td className="px-4 py-2.5">
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation()
                    onSelect(visit.visitRef)
                  }}
                  className="text-left font-semibold underline-offset-2 hover:underline"
                >
                  {vesselLabel(visit.vessel)}
                </button>
                <div className="text-xs whitespace-nowrap text-ink-2">{vesselTypeLabel(visit.vessel.type)}</div>
              </td>
              <td className="px-4 py-2.5"><StandingChip visit={visit} /></td>
              <td className="px-4 py-2.5">
                {visit.ataBerth ? <span className="text-ink-2">arrived <When value={visit.ataBerth} showRelative={false} /></span> : <When value={visit.etaBerth} />}
              </td>
              <td className="px-4 py-2.5">{visit.ataBerth ? <span className="text-muted">—</span> : <When value={visit.rtaBerth} />}</td>
              <td className="px-4 py-2.5">
                <div className="flex flex-col items-start gap-1">
                  <AgreementChip visit={visit} />
                  <AttentionChips visit={visit} />
                </div>
              </td>
              <td className="px-4 py-2.5">{visit.berth ? <When value={visit.berth.plannedEnd} /> : <span className="text-muted">—</span>}</td>
              <td className="px-4 py-2.5">
                {visit.lastReport ? <ReportCell report={visit.lastReport} /> : <span className="text-ink-2">none given</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Th({ children, className }) {
  return <th scope="col" className={cn('px-4 py-2 font-medium whitespace-nowrap', className)}>{children}</th>
}

/** Where the ship said it was when it last sent an estimate. */
function ReportCell({ report }) {
  return (
    <span className="tabular whitespace-nowrap">
      {report.distanceNm != null ? `${report.distanceNm.toFixed(1)} nm out` : 'position given'}
      <span className="ml-1.5 text-ink-2"><When value={report.reportedAt} showRelative={false} /></span>
    </span>
  )
}
