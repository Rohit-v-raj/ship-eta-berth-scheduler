import { useState } from 'react'
import { Trash2, Wrench } from 'lucide-react'
import { toast } from 'sonner'
import { useMaintenance, useRefreshPort } from '@/api/queries'
import { removeMaintenance, scheduleMaintenance } from '@/api/scheduler'
import { useTime } from '@/app/providers'
import { Chip, When } from '@/components/domain'
import { Button, Field, InlineError, Input, Notice, Panel, Select } from '@/components/ui/controls'
import { fromLocalInput, HOUR, readableTimes, toLocalInput, toMillis } from '@/lib/time'
import { useCan } from '@/session/session'

/**
 * Planned maintenance on the berths: when each berth is out of use, and why.
 * The planner records a window here. The scheduler refuses one that overlaps
 * a ship's booking on the berth or another window, gives no ship the berth for
 * a stay that would run into one, and plans the waiting ships around them.
 */
export function MaintenancePanel({ port }) {
  const allowed = useCan('berth.maintain')
  const { data: windows = [], isPending, isError, error } = useMaintenance()

  return (
    <Panel title="Berth maintenance" hint="No ship is given a berth for a stay that runs into a window">
      {allowed && <ScheduleForm port={port} />}
      {isError ? (
        <Notice tone="error" title="The maintenance plan could not be loaded">{error.message}</Notice>
      ) : isPending ? (
        <Notice title="Loading the maintenance plan" />
      ) : windows.length === 0 ? (
        <Notice icon={Wrench} title="No maintenance planned">Every berth in service is available.</Notice>
      ) : (
        <WindowList windows={windows} canRemove={allowed} />
      )}
    </Panel>
  )
}

function ScheduleForm({ port }) {
  const { zone, now } = useTime()
  const refresh = useRefreshPort()
  const berths = port.berths.filter((berth) => berth.status === 'ACTIVE')
  const nextHour = Math.ceil(now.getTime() / HOUR) * HOUR
  const [berthCode, setBerthCode] = useState(berths[0]?.code ?? '')
  const [from, setFrom] = useState(() => toLocalInput(new Date(nextHour + 24 * HOUR), zone))
  const [to, setTo] = useState(() => toLocalInput(new Date(nextHour + 30 * HOUR), zone))
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState(null)
  const [failure, setFailure] = useState(null)
  const [busy, setBusy] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    const start = fromLocalInput(from, zone)
    const end = fromLocalInput(to, zone)
    if (!start || !end) return setProblem('Enter both times')
    if (end <= start) return setProblem('The window must end after it starts')
    if (end <= now) return setProblem('The window must end in the future')
    setProblem(null)
    setFailure(null)
    setBusy(true)
    try {
      await scheduleMaintenance(berthCode, { from: start.toISOString(), to: end.toISOString(), reason: reason.trim() || undefined })
      await refresh()
      setReason('')
      toast.success(`Maintenance on ${berthCode} scheduled. Waiting ships have been planned around it.`)
    } catch (failed) {
      setFailure(readableTimes(failed.message, zone, now))
    } finally {
      setBusy(false)
    }
    return undefined
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-3 border-b border-line px-4 py-4">
      <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)_minmax(0,1.3fr)]">
        <Field id="maintenance-berth" label="Berth">
          {(a11y) => (
            <Select {...a11y} value={berthCode} onChange={(event) => setBerthCode(event.target.value)}>
              {berths.map((berth) => (
                <option key={berth.code} value={berth.code}>
                  {berth.code}, {berth.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field id="maintenance-from" label={`From (${zone})`} error={problem}>
          {(a11y) => <Input {...a11y} type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} />}
        </Field>
        <Field id="maintenance-to" label={`To (${zone})`}>
          {(a11y) => <Input {...a11y} type="datetime-local" value={to} onChange={(event) => setTo(event.target.value)} />}
        </Field>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <Field id="maintenance-reason" label="Reason (optional)" className="min-w-56 flex-1">
          {(a11y) => (
            <Input {...a11y} value={reason} maxLength={200} onChange={(event) => setReason(event.target.value)} placeholder="for example Fender repair" />
          )}
        </Field>
        <Button type="submit" variant="primary" busy={busy}>
          <Wrench className="size-4" aria-hidden="true" />
          Schedule maintenance
        </Button>
      </div>
      <InlineError>{failure}</InlineError>
    </form>
  )
}

function WindowList({ windows, canRemove }) {
  const { now } = useTime()
  const refresh = useRefreshPort()
  const [removing, setRemoving] = useState(null)
  const [failure, setFailure] = useState(null)

  const remove = async (window) => {
    setRemoving(window.id)
    setFailure(null)
    try {
      await removeMaintenance(window.berthCode, window.id)
      await refresh()
    } catch (failed) {
      setFailure(failed.message)
    } finally {
      setRemoving(null)
    }
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] border-collapse text-left text-[13px]">
        <caption className="sr-only">Maintenance planned, earliest first</caption>
        <thead>
          <tr className="border-b border-line text-xs text-ink-2">
            <th scope="col" className="px-4 py-2 font-medium">Berth</th>
            <th scope="col" className="px-4 py-2 font-medium">From</th>
            <th scope="col" className="px-4 py-2 font-medium">To</th>
            <th scope="col" className="px-4 py-2 font-medium">Reason</th>
            <th scope="col" className="px-4 py-2 font-medium">State</th>
            {canRemove && <th scope="col" className="px-4 py-2"><span className="sr-only">Remove</span></th>}
          </tr>
        </thead>
        <tbody>
          {windows.map((window) => {
            const underWay = toMillis(window.from) <= now.getTime()
            return (
              <tr key={window.id} className="border-b border-line last:border-b-0">
                <td className="px-4 py-2.5 font-semibold">{window.berthCode}</td>
                <td className="px-4 py-2.5"><When value={window.from} /></td>
                <td className="px-4 py-2.5"><When value={window.to} /></td>
                <td className="px-4 py-2.5">{window.reason ?? <span className="text-muted">—</span>}</td>
                <td className="px-4 py-2.5">
                  {underWay ? <Chip icon={Wrench} tone="serious">Under way</Chip> : <Chip icon={Wrench}>Planned</Chip>}
                </td>
                {canRemove && (
                  <td className="px-4 py-2.5 text-right">
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => remove(window)}
                      busy={removing === window.id}
                      disabled={removing !== null}
                      aria-label={`Remove the maintenance on ${window.berthCode}`}
                    >
                      <Trash2 className="size-3.5" aria-hidden="true" />
                      Remove
                    </Button>
                  </td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
      {failure && <div className="px-4 pb-3"><InlineError>{failure}</InlineError></div>}
    </div>
  )
}
