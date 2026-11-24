import { useState } from 'react'
import { toast } from 'sonner'
import { actualArrival, actualDeparture, DCSA_VESSEL_TYPE, estimatedArrival, omittedCall, plannedArrival, sendEvents } from '@/api/dcsa'
import { useRefreshPort } from '@/api/queries'
import { useTime } from '@/app/providers'
import { Button, Field, InlineError, Input } from '@/components/ui/controls'
import { ConfirmDialog, Dialog } from '@/components/ui/overlay'
import { dayClock, fromLocalInput, HOUR, toLocalInput, toMillis } from '@/lib/time'
import { AGREEMENT, agreementOf, STANDING, standingOf, vesselLabel } from '@/lib/visit'
import { useCan } from '@/session/session'

/**
 * The operator's answers to the terminal. Each one is sent as a DCSA Port Call
 * event: accepting is a planned arrival equal to the requested one, proposing
 * another time is a new estimated arrival, and skipping the call is an omitted
 * port call. The scheduler decides; the screen only shows what it said.
 *
 * Arrival is offered only once the ship has accepted the requested time: the
 * just-in-time order is estimate, request, accept, arrive. The scheduler itself
 * still records an arrival sent without an acceptance, since a ship really at
 * the quay has to be recorded.
 *
 * Nor is it offered before the ship's turn (`visit.aheadOnBerth`): while
 * another ship lies at the berth, or one booked ahead of it is still to come
 * and this stay would not be over before it. The scheduler refuses such an
 * arrival; an early ship waits.
 */
export function DecisionBar({ visit, port }) {
  const allowed = useCan('visit.answer')
  const refresh = useRefreshPort()
  const { zone, now } = useTime()
  const [busy, setBusy] = useState(null)
  const [error, setError] = useState(null)
  const [dialog, setDialog] = useState(null)
  if (!allowed) return null

  const code = visit.vessel.code
  const standing = standingOf(visit)
  const agreement = agreementOf(visit)
  const agreedOnBerth = standing === STANDING.RESERVED && agreement === AGREEMENT.AGREED
  const ahead = visit.aheadOnBerth

  const act = async (name, events, label, done) => {
    setBusy(name)
    setError(null)
    try {
      await sendEvents(events, label)
      await refresh()
      setDialog(null)
      toast.success(done)
    } catch (failure) {
      await refresh()
      setError(explain(failure.message))
    } finally {
      setBusy(null)
    }
  }

  const accept = () =>
    act('accept', [plannedArrival(code, visit.rtaBerth)], 'Accept the requested arrival', `Arrival agreed for ${dayClock(visit.rtaBerth, zone, now)}`)

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-2">
        {standing !== STANDING.ALONGSIDE && (
          <>
            <Button variant="primary" size="lg" onClick={accept} busy={busy === 'accept'} disabled={busy !== null || agreement !== AGREEMENT.AWAITING}>
              {agreement === AGREEMENT.AGREED ? 'Requested arrival accepted' : 'Accept requested arrival'}
            </Button>
            <Button size="lg" onClick={() => setDialog('propose')} disabled={busy !== null}>Propose another time</Button>
            <Button size="lg" variant="danger" onClick={() => setDialog('omit')} disabled={busy !== null}>Omit this call</Button>
          </>
        )}
        {agreedOnBerth && !ahead && (
          <Button size="lg" onClick={() => act('arrive', [actualArrival(code, new Date())], 'Report arrival at berth', `Arrival at ${visit.berth.berthCode} recorded`)} busy={busy === 'arrive'} disabled={busy !== null}>
            Report arrival at {visit.berth.berthCode}
          </Button>
        )}
        {standing === STANDING.ALONGSIDE && (
          <Button size="lg" onClick={() => act('depart', [actualDeparture(code, new Date())], 'Report departure', 'Departure recorded')} busy={busy === 'depart'} disabled={busy !== null}>
            Report departure from {visit.berth.berthCode}
          </Button>
        )}
      </div>
      {standing === STANDING.RESERVED && agreement === AGREEMENT.AWAITING && (
        <p className="text-sm text-ink-2">Accept the requested arrival, or propose another time, before reporting arrival at {visit.berth.berthCode}.</p>
      )}
      {agreedOnBerth && ahead && <p className="text-sm text-ink-2">{waitForTurn(ahead, visit.berth.berthCode, zone, now)}</p>}
      <InlineError>{error}</InlineError>

      <ProposeDialog
        open={dialog === 'propose'}
        onOpenChange={(open) => setDialog(open ? 'propose' : null)}
        visit={visit}
        busy={busy === 'propose'}
        onSubmit={(time) =>
          act('propose', [estimatedArrival(code, time, DCSA_VESSEL_TYPE[visit.vessel.type])], 'Propose another arrival time', 'New estimate sent. The terminal has answered with a requested arrival.')
        }
      />
      <ConfirmDialog
        open={dialog === 'omit'}
        onOpenChange={(open) => setDialog(open ? 'omit' : null)}
        title="Omit this call?"
        description={
          standing === STANDING.WAITING
            ? 'The ship gives up its place in line. This cannot be undone.'
            : `The ship gives up its booking on ${visit.berth.berthCode}; the ships booked after it move up. This cannot be undone.`
        }
        confirmLabel="Omit the call"
        danger
        busy={busy === 'omit'}
        onConfirm={() => act('omit', [omittedCall(code, port.unLocode)], 'Omit the call', 'The call is omitted')}
      />
    </div>
  )
}

/** Why the ship cannot report arrival yet: whose turn at the berth comes first. */
function waitForTurn(ahead, berthCode, zone, now) {
  const name = vesselLabel({ code: ahead.vesselCode, name: ahead.vesselName })
  if (ahead.status === 'ACTIVE') return `${name} is still alongside ${berthCode}. Report arrival once it has left.`
  return `${berthCode} is booked for ${name} first, from ${dayClock(ahead.plannedStart, zone, now)}. Report arrival at your turn, once it has left.`
}

/** The scheduler's refusals are exact but terse; say what to do about the common one. */
function explain(message) {
  if (/requested arrival is now/i.test(message)) {
    return `${message}. The terminal changed its request while you were deciding; the new time is shown above.`
  }
  return message
}

function ProposeDialog({ open, onOpenChange, visit, busy, onSubmit }) {
  const { zone, now } = useTime()
  const start = toMillis(visit.rtaBerth ?? visit.etaBerth) ?? now.getTime()
  const [value, setValue] = useState(() => toLocalInput(new Date(start + HOUR), zone))
  const [problem, setProblem] = useState(null)

  const submit = (event) => {
    event.preventDefault()
    const time = fromLocalInput(value, zone)
    if (!time) return setProblem('Enter a date and time')
    setProblem(null)
    return onSubmit(time)
  }

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title="Propose another arrival time"
      description="Tell the terminal when the ship can reach the berth. It answers with a new requested arrival, and any earlier acceptance lapses."
    >
      <form onSubmit={submit} className="flex flex-col gap-4 px-5 py-4">
        <Field id="proposed" label={`The ship can be at the berth at (${zone})`} error={problem}>
          {(a11y) => <Input {...a11y} type="datetime-local" value={value} onChange={(event) => setValue(event.target.value)} />}
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" busy={busy}>Send estimate</Button>
        </div>
      </form>
    </Dialog>
  )
}
