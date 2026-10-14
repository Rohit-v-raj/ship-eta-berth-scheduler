import { forwardRef } from 'react'
import * as RadixTooltip from '@radix-ui/react-tooltip'
import { LoaderCircle } from 'lucide-react'
import { cn } from '@/lib/cn'

/** Buttons, form fields, panels and tooltips: the plain controls every screen uses. */

const BUTTON_VARIANTS = {
  primary: 'bg-accent text-accent-ink hover:bg-accent-hover border-transparent',
  secondary: 'bg-surface text-ink border-line-strong hover:bg-surface-2',
  ghost: 'bg-transparent text-ink-2 border-transparent hover:bg-surface-2 hover:text-ink',
  danger: 'bg-surface text-ink border-critical hover:bg-surface-2',
}

const BUTTON_SIZES = {
  sm: 'h-7 px-2.5 text-[13px] gap-1.5',
  md: 'h-9 px-3.5 text-sm gap-2',
  lg: 'h-11 px-5 text-[15px] gap-2',
}

export const Button = forwardRef(function Button(
  { variant = 'secondary', size = 'md', busy = false, disabled, className, children, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-md border font-medium whitespace-nowrap transition-colors',
        'disabled:cursor-not-allowed disabled:opacity-50',
        BUTTON_VARIANTS[variant],
        BUTTON_SIZES[size],
        className,
      )}
      {...rest}
    >
      {busy && <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />}
      {children}
    </button>
  )
})

/** A titled panel: the card every section of a page sits in. */
export function Panel({ title, hint, actions, className, bodyClassName, children, as: Tag = 'section' }) {
  return (
    <Tag className={cn('flex min-w-0 flex-col rounded-lg border border-line bg-surface', className)}>
      {(title || actions) && (
        <header className="flex min-h-11 items-center justify-between gap-3 border-b border-line px-4 py-2">
          <div className="min-w-0">
            <h2 className="truncate text-sm font-semibold">{title}</h2>
            {hint && <p className="truncate text-xs text-ink-2">{hint}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={cn('min-h-0 flex-1', bodyClassName)}>{children}</div>
    </Tag>
  )
}

/** A label, its control and an error or hint line, wired together for screen readers. */
export function Field({ id, label, hint, error, children, className }) {
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      <label htmlFor={id} className="text-[13px] font-medium text-ink-2">
        {label}
      </label>
      {typeof children === 'function' ? children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined }) : children}
      {error ? (
        <p id={`${id}-error`} className="text-xs text-ink" role="alert">
          <span className="mr-1 inline-block size-2 rounded-full bg-critical" aria-hidden="true" />
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${id}-hint`} className="text-xs text-ink-2">
            {hint}
          </p>
        )
      )}
    </div>
  )
}

export const inputClass =
  'h-9 w-full rounded-md border border-line-strong bg-surface px-2.5 text-sm text-ink placeholder:text-muted ' +
  'aria-[invalid=true]:border-critical disabled:opacity-60'

export const Input = forwardRef(function Input({ className, ...rest }, ref) {
  return <input ref={ref} className={cn(inputClass, className)} {...rest} />
})

export const Select = forwardRef(function Select({ className, children, ...rest }, ref) {
  return (
    <select ref={ref} className={cn(inputClass, 'pr-7', className)} {...rest}>
      {children}
    </select>
  )
})

/** A hover and focus tooltip. The content must also be reachable without it. */
export function Tooltip({ content, children, side = 'top' }) {
  if (!content) return children
  return (
    <RadixTooltip.Root>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          side={side}
          sideOffset={6}
          className="z-50 max-w-xs rounded-md border border-line bg-surface px-2.5 py-1.5 text-xs text-ink shadow-panel"
        >
          {content}
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  )
}

export const TooltipProvider = RadixTooltip.Provider

/** A message for a page or panel that has nothing to show, or could not load. */
export function Notice({ icon: Icon, title, children, tone = 'neutral', className }) {
  return (
    <div className={cn('flex flex-col items-center justify-center gap-1 px-6 py-10 text-center', className)} role={tone === 'error' ? 'alert' : undefined}>
      {Icon && <Icon className={cn('mb-1 size-6', tone === 'error' ? 'text-critical' : 'text-muted')} aria-hidden="true" />}
      <p className="text-sm font-medium">{title}</p>
      {children && <p className="max-w-sm text-[13px] text-ink-2">{children}</p>}
    </div>
  )
}

/** An inline error under the control that caused it. */
export function InlineError({ children }) {
  if (!children) return null
  return (
    <p role="alert" className="flex items-start gap-2 rounded-md border border-critical bg-surface px-3 py-2 text-[13px]">
      <span className="mt-1 inline-block size-2 shrink-0 rounded-full bg-critical" aria-hidden="true" />
      <span>{children}</span>
    </p>
  )
}
