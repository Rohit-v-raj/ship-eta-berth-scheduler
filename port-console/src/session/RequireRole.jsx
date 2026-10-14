import { ShieldAlert } from 'lucide-react'
import { ROLE_LABELS } from './permissions'
import { useCan, useSession } from './session'

/** Shows its children only to a session allowed to take `action`; anyone else sees why not. */
export function RequireRole({ action, children }) {
  return useCan(action) ? children : <NotAllowed />
}

export function NotAllowed() {
  const { session } = useSession()
  return (
    <div className="mx-auto mt-24 max-w-md rounded-lg border border-line bg-surface p-8 text-center">
      <ShieldAlert className="mx-auto mb-3 size-8 text-muted" aria-hidden="true" />
      <h1 className="text-lg font-semibold">Not allowed</h1>
      <p className="mt-2 text-ink-2">
        This page is not open to the {ROLE_LABELS[session.role].toLowerCase()} seat.
        {session.demo && ' Switch seats in the header to see it.'}
      </p>
    </div>
  )
}
