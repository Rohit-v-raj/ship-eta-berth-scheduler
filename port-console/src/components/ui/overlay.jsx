import * as RadixDialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import { cn } from '@/lib/cn'
import { Button } from './controls'

/**
 * Overlays, built on Radix Dialog so focus is trapped, Escape closes, and the
 * rest of the page is hidden from screen readers while one is open.
 */

function Frame({ title, description, children, className, onOpenChange, open }) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/30" />
        <RadixDialog.Content
          className={cn('fixed z-50 flex flex-col border-line bg-surface shadow-panel outline-none', className)}
        >
          <header className="flex items-start justify-between gap-4 border-b border-line px-5 py-3">
            <div className="min-w-0">
              <RadixDialog.Title className="truncate text-base font-semibold">{title}</RadixDialog.Title>
              {description ? (
                <RadixDialog.Description className="mt-0.5 text-[13px] text-ink-2">{description}</RadixDialog.Description>
              ) : (
                <RadixDialog.Description className="sr-only">{title}</RadixDialog.Description>
              )}
            </div>
            <RadixDialog.Close asChild>
              <Button variant="ghost" size="sm" aria-label="Close" className="-mr-2 px-1.5">
                <X className="size-4" aria-hidden="true" />
              </Button>
            </RadixDialog.Close>
          </header>
          {children}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

/** A panel that slides over the right edge: detail without leaving the page. */
export function Sheet({ wide = false, ...props }) {
  return (
    <Frame
      {...props}
      className={cn('inset-y-0 right-0 w-full border-l', wide ? 'sm:max-w-3xl' : 'sm:max-w-xl')}
    />
  )
}

/** A centred dialog for one question or one small form. */
export function Dialog(props) {
  return (
    <Frame
      {...props}
      className="top-1/2 left-1/2 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border"
    />
  )
}

/**
 * "Are you sure?" for an action that cannot be undone. Name the way out with
 * `dismissLabel` when "Cancel" would be mistaken for the action itself.
 */
export function ConfirmDialog({ open, onOpenChange, title, description, confirmLabel, dismissLabel = 'Cancel', onConfirm, busy, danger = false, error }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={title} description={description}>
      <div className="flex flex-col gap-3 px-5 py-4">
        {error && (
          <p role="alert" className="text-[13px]">
            <span className="mr-1.5 inline-block size-2 rounded-full bg-critical" aria-hidden="true" />
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button onClick={() => onOpenChange(false)}>{dismissLabel}</Button>
          <Button variant={danger ? 'danger' : 'primary'} busy={busy} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
