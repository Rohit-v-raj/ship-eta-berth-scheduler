import { useState } from 'react'
import { BookOpen, ChevronDown, ChevronUp, Play, RotateCcw, X } from 'lucide-react'
import { Button, InlineError } from '@/components/ui/controls'
import { ConfirmDialog } from '@/components/ui/overlay'
import { useCan } from '@/session/session'
import { useStory } from './StoryProvider'

/**
 * The story player, docked under every page. It drives the real scheduler one
 * step at a time and says what each step did, so a visitor can watch the whole
 * berth conversation without typing a request.
 */
export function StoryDock() {
  const allowed = useCan('story.run')
  const story = useStory()
  const [expanded, setExpanded] = useState(true)
  const [confirmReset, setConfirmReset] = useState(false)
  if (!allowed) return null

  const resetDialog = (
    <ConfirmDialog
      open={confirmReset}
      onOpenChange={setConfirmReset}
      title="Reset the port?"
      description="Every visit in progress is closed: waiting ships are omitted and berths are released. The history stays in the ledger. This cannot be undone."
      confirmLabel="Reset the port"
      danger
      busy={story.busy}
      onConfirm={async () => {
        await story.reset()
        setConfirmReset(false)
      }}
    />
  )

  if (!story.scenario) {
    return (
      <aside aria-label="Stories" className="border-t border-line-strong bg-surface">
        <div className="mx-auto flex max-w-[1500px] flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5">
          <p className="flex items-center gap-2 text-[13px] font-semibold">
            <BookOpen className="size-4" aria-hidden="true" /> Stories
          </p>
          <p className="text-[13px] text-ink-2">Watch the berth conversation step by step, on the real scheduler.</p>
          <div className="ml-auto flex flex-wrap gap-2">
            {story.scenarios.map((scenario) => (
              <Button key={scenario.id} size="sm" onClick={() => story.start(scenario.id)} disabled={story.busy} title={scenario.summary}>
                <Play className="size-3.5" aria-hidden="true" />
                {scenario.title}
              </Button>
            ))}
            <Button size="sm" variant="ghost" onClick={() => setConfirmReset(true)} disabled={story.busy}>
              <RotateCcw className="size-3.5" aria-hidden="true" />
              Reset the port
            </Button>
          </div>
        </div>
        {story.error && <div className="mx-auto max-w-[1500px] px-4 pb-2.5"><InlineError>{story.error}</InlineError></div>}
        {resetDialog}
      </aside>
    )
  }

  const { scenario, done, nextStep, finished } = story
  const last = done.at(-1)

  return (
    <aside aria-label={`Story: ${scenario.title}`} className="border-t border-line-strong bg-surface">
      <div className="mx-auto max-w-[1500px] px-4 py-2.5">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <p className="flex items-center gap-2 text-[13px] font-semibold">
            <BookOpen className="size-4" aria-hidden="true" /> {scenario.title}
          </p>
          <p className="tabular text-xs text-ink-2">
            {finished ? 'finished' : `step ${done.length + 1} of ${scenario.steps.length}`}
          </p>
          <div className="ml-auto flex flex-wrap items-center gap-2">
            {story.blockedBy > 0 ? (
              <Button size="sm" variant="primary" onClick={() => setConfirmReset(true)} disabled={story.busy}>
                <RotateCcw className="size-3.5" aria-hidden="true" />
                Reset the port to begin
              </Button>
            ) : (
              !finished && (
                <Button size="sm" variant="primary" onClick={story.next} busy={story.busy}>
                  {!story.busy && <Play className="size-3.5" aria-hidden="true" />}
                  {nextStep.title}
                </Button>
              )
            )}
            {story.blockedBy === 0 && (
              <Button size="sm" variant="ghost" onClick={() => setConfirmReset(true)} disabled={story.busy}>
                <RotateCcw className="size-3.5" aria-hidden="true" />
                Start over
              </Button>
            )}
            <Button size="sm" variant="ghost" onClick={() => setExpanded((v) => !v)} aria-expanded={expanded} aria-label={expanded ? 'Hide the story text' : 'Show the story text'}>
              {expanded ? <ChevronDown className="size-4" aria-hidden="true" /> : <ChevronUp className="size-4" aria-hidden="true" />}
            </Button>
            <Button size="sm" variant="ghost" onClick={story.close} aria-label="Close the story">
              <X className="size-4" aria-hidden="true" />
            </Button>
          </div>
        </div>

        {expanded && (
          <div className="mt-2 max-w-4xl text-[13px]" aria-live="polite">
            {story.blockedBy > 0 ? (
              <p>
                A story starts from an empty port, and {story.blockedBy} visit{story.blockedBy === 1 ? ' is' : 's are'} in progress. Reset the port to begin.
              </p>
            ) : last ? (
              <>
                <p className="font-medium">{last.title}</p>
                <p className="mt-0.5 text-ink-2">{last.caption}</p>
                <p className="mt-0.5 font-mono text-[11px] text-muted">{last.sends}</p>
              </>
            ) : (
              <p className="text-ink-2">{scenario.summary}</p>
            )}
            {finished && <p className="mt-1.5">That is the whole story. Open the wire inspector to see every message it sent.</p>}
          </div>
        )}
        {story.error && <div className="mt-2 max-w-4xl"><InlineError>{story.error}</InlineError></div>}
      </div>
      {resetDialog}
    </aside>
  )
}
