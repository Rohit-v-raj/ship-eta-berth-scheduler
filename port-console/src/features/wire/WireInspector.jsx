import { useState } from 'react'
import { Cable } from 'lucide-react'
import { useTime } from '@/app/providers'
import { Button, Notice } from '@/components/ui/controls'
import { Sheet } from '@/components/ui/overlay'
import { cn } from '@/lib/cn'
import { clock } from '@/lib/time'
import { clearExchanges, useWireLog } from './wireStore'

/**
 * What each click sent to the scheduler and what came back, newest first.
 * This is the layer under the screen made visible: the DCSA messages are here
 * in full.
 */
export function WireInspector({ open, onOpenChange }) {
  const entries = useWireLog()
  return (
    <Sheet
      wide
      open={open}
      onOpenChange={onOpenChange}
      title="Wire inspector"
      description="Each request the console made because of an action, and the scheduler's answer. Background refreshes are not listed."
    >
      <div className="flex items-center justify-between border-b border-line px-5 py-2 text-xs text-ink-2">
        <span>{entries.length === 0 ? 'Nothing sent yet' : `${entries.length} request${entries.length === 1 ? '' : 's'}`}</span>
        <Button size="sm" variant="ghost" onClick={clearExchanges} disabled={entries.length === 0}>Clear</Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {entries.length === 0 ? (
          <Notice icon={Cable} title="No requests yet">Report a position, answer a request or run a story, then look here.</Notice>
        ) : (
          <ol>
            {entries.map((entry) => <Exchange key={entry.id} entry={entry} />)}
          </ol>
        )}
      </div>
    </Sheet>
  )
}

function Exchange({ entry }) {
  const { zone } = useTime()
  const [open, setOpen] = useState(false)
  return (
    <li className="border-b border-line">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center gap-3 px-5 py-2.5 text-left hover:bg-surface-2"
      >
        <span className="w-11 shrink-0 font-mono text-xs font-semibold">{entry.method}</span>
        <span className="min-w-0 flex-1">
          {entry.label && <span className="block truncate text-[13px] font-medium">{entry.label}</span>}
          <span className="block truncate font-mono text-xs text-ink-2">{entry.url}</span>
        </span>
        <span className="flex shrink-0 items-center gap-1.5 text-xs">
          <span className={cn('inline-block size-2 rounded-full', entry.failed ? 'bg-critical' : 'bg-good')} aria-hidden="true" />
          <span className="tabular">{entry.status === 0 ? 'no answer' : entry.status}</span>
        </span>
        <span className="tabular w-24 shrink-0 text-right text-xs text-ink-2">
          {clock(entry.at, zone)} · {entry.durationMs} ms
        </span>
      </button>
      {open && (
        <div className="grid gap-3 bg-surface-2 px-5 py-3 md:grid-cols-2">
          <Body title="Sent" value={entry.requestBody} empty="No body" />
          <Body title="Answer" value={entry.responseBody} empty="Empty answer" />
        </div>
      )}
    </li>
  )
}

function Body({ title, value, empty }) {
  const text = value == null ? null : typeof value === 'string' ? value : JSON.stringify(value, null, 2)
  return (
    <div className="min-w-0">
      <div className="mb-1 flex items-center justify-between">
        <h4 className="text-xs font-semibold text-ink-2">{title}</h4>
        {text && (
          <Button size="sm" variant="ghost" className="h-6" onClick={() => navigator.clipboard?.writeText(text)}>
            Copy
          </Button>
        )}
      </div>
      {text ? (
        <pre className="max-h-80 overflow-auto rounded-md border border-line bg-surface p-2.5 font-mono text-[11.5px] leading-relaxed">{text}</pre>
      ) : (
        <p className="text-xs text-ink-2">{empty}</p>
      )}
    </div>
  )
}
