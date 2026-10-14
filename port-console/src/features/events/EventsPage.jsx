import { Fragment, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, Radio } from 'lucide-react'
import { getEvents } from '@/api/dcsa'
import { queryKeys } from '@/api/queries'
import { TimeMark, When } from '@/components/domain'
import { Button, Field, Input, Notice, Panel, Select } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { TIME_CLASS_BY_CODE, TIME_CLASSES } from '@/lib/visit'

const CLASSIFIERS = [
  { code: '', label: 'All' },
  ...Object.values(TIME_CLASSES).map((c) => ({ code: c.code, label: c.label })),
]

const SERVICES = [
  { code: '', label: 'Any service' },
  { code: 'BERTH', label: 'Berth' },
  { code: 'CARGO_OPERATIONS', label: 'Cargo operations' },
  { code: 'PILOT_BOARDING_PLACE', label: 'Pilot boarding place' },
]

const EVENT_TYPE = { ARRI: 'arrival', DEPA: 'departure', STRT: 'start', CMPL: 'completion' }

/**
 * The timestamp ledger as the DCSA Port Call 2.0 feed publishes it. Filters
 * sit in one row above the table and scope everything below. Paging follows
 * the standard: each page names the cursor for the next.
 */
export function EventsPage() {
  const [classifierCode, setClassifierCode] = useState('')
  const [serviceType, setServiceType] = useState('')
  const [vesselDraft, setVesselDraft] = useState('')
  const [vesselName, setVesselName] = useState('')
  const [expanded, setExpanded] = useState(null)

  const filters = {
    ...(classifierCode && { classifierCode }),
    ...(serviceType && { portCallServiceTypeCode: serviceType }),
    ...(vesselName && { vesselName }),
  }
  const query = useInfiniteQuery({
    queryKey: queryKeys.events(filters),
    queryFn: ({ pageParam }) => getEvents(filters, pageParam),
    initialPageParam: undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const events = query.data?.pages.flatMap((page) => page.events) ?? []

  return (
    <div className="mx-auto flex max-w-[1300px] flex-col gap-4 p-4">
      <div>
        <h1 className="text-lg font-semibold">Port call events</h1>
        <p className="mt-0.5 max-w-3xl text-[13px] text-ink-2">
          Every time the terminal and the ships have exchanged, in the DCSA Port Call 2.0 format. This service follows the
          standard&apos;s messages and codes but is not certified. The ETA model is the operator&apos;s calculator, so
          a figure from it appears here only when an operator sends it as the ship&apos;s estimate.
        </p>
      </div>

      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          setVesselName(vesselDraft.trim())
        }}
      >
        <div role="group" aria-label="Kind of time" className="flex rounded-md border border-line-strong p-0.5">
          {CLASSIFIERS.map((c) => (
            <Button
              key={c.code || 'all'}
              size="sm"
              variant={classifierCode === c.code ? 'primary' : 'ghost'}
              aria-pressed={classifierCode === c.code}
              onClick={() => setClassifierCode(c.code)}
              className="border-0"
            >
              {c.code && <TimeMark kind={TIME_CLASS_BY_CODE[c.code]} />}
              {c.label}
            </Button>
          ))}
        </div>
        <Field id="service" label="Service" className="w-52">
          {(a11y) => (
            <Select {...a11y} value={serviceType} onChange={(event) => setServiceType(event.target.value)}>
              {SERVICES.map((s) => <option key={s.code} value={s.code}>{s.label}</option>)}
            </Select>
          )}
        </Field>
        <Field id="vessel" label="Vessel name" className="w-56">
          {(a11y) => <Input {...a11y} value={vesselDraft} onChange={(event) => setVesselDraft(event.target.value)} placeholder="exact name or code" />}
        </Field>
        <Button type="submit">Apply</Button>
      </form>

      <Panel title="Events" hint="Oldest first, as published">
        {query.isError ? (
          <Notice tone="error" title="The feed could not be loaded">{query.error.message}</Notice>
        ) : query.isPending ? (
          <Notice title="Loading events" />
        ) : events.length === 0 ? (
          <Notice icon={Radio} title="No events match">Nothing has been exchanged yet, or the filters leave nothing.</Notice>
        ) : (
          <div className={cn('overflow-x-auto', query.isFetching && !query.isFetchingNextPage && 'opacity-60')}>
            <table className="w-full min-w-[820px] border-collapse text-left text-[13px]">
              <caption className="sr-only">DCSA Port Call events</caption>
              <thead>
                <tr className="border-b border-line text-xs text-ink-2">
                  <th scope="col" className="w-8 px-2 py-2"><span className="sr-only">Show the message</span></th>
                  <th scope="col" className="px-3 py-2 font-medium">Kind</th>
                  <th scope="col" className="px-3 py-2 font-medium">About</th>
                  <th scope="col" className="px-3 py-2 font-medium">Time</th>
                  <th scope="col" className="px-3 py-2 font-medium">Vessel</th>
                  <th scope="col" className="px-3 py-2 font-medium">Where</th>
                  <th scope="col" className="px-3 py-2 font-medium">Reported</th>
                </tr>
              </thead>
              <tbody>
                {events.map((event) => {
                  const open = expanded === event.eventID
                  const kind = TIME_CLASS_BY_CODE[event.timestamp?.classifierCode]
                  const service = event.portCallService ?? {}
                  return (
                    <Fragment key={event.eventID}>
                      <tr className="border-b border-line">
                        <td className="px-2 py-2">
                          <button
                            type="button"
                            onClick={() => setExpanded(open ? null : event.eventID)}
                            aria-expanded={open}
                            aria-label={open ? 'Hide the DCSA message' : 'Show the DCSA message'}
                            className="rounded-sm p-1 text-ink-2 hover:bg-surface-2"
                          >
                            {open ? <ChevronDown className="size-4" aria-hidden="true" /> : <ChevronRight className="size-4" aria-hidden="true" />}
                          </button>
                        </td>
                        <td className="px-3 py-2">
                          <span className="inline-flex items-center gap-1.5 font-medium">
                            {kind && <TimeMark kind={kind} />}
                            {kind ? TIME_CLASSES[kind].label : '—'}
                            <span className="font-mono text-[11px] text-ink-2">{event.timestamp?.classifierCode}</span>
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          {(service.portCallServiceTypeCode ?? '').toLowerCase().replaceAll('_', ' ')}{' '}
                          {EVENT_TYPE[service.portCallServiceEventTypeCode] ?? ''}
                          {event.portCall?.isOmitted && <span className="ml-1.5 text-ink-2">(call omitted)</span>}
                        </td>
                        <td className="px-3 py-2"><When value={event.timestamp?.serviceDateTime} showRelative={false} /></td>
                        <td className="px-3 py-2">{event.vessel?.vesselName ?? '—'}</td>
                        <td className="px-3 py-2">{service.serviceLocation?.locationName ?? <span className="text-muted">—</span>}</td>
                        <td className="px-3 py-2 text-ink-2"><When value={event.eventUpdatedDateTime} showRelative={false} /></td>
                      </tr>
                      {open && (
                        <tr className="border-b border-line bg-surface-2">
                          <td />
                          <td colSpan={6} className="px-3 py-2">
                            {event.timestamp?.remark && <p className="mb-1.5 text-xs">Remark: {event.timestamp.remark}</p>}
                            <pre className="max-h-72 overflow-auto font-mono text-[11.5px] leading-relaxed">{JSON.stringify(event, null, 2)}</pre>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
        {query.hasNextPage && (
          <div className="border-t border-line px-4 py-3 text-center">
            <Button onClick={() => query.fetchNextPage()} busy={query.isFetchingNextPage}>Load more events</Button>
          </div>
        )}
      </Panel>
    </div>
  )
}
