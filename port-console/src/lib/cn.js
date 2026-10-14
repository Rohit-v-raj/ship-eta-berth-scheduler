import clsx from 'clsx'

/** Join class names, dropping the falsy ones. */
export function cn(...parts) {
  return clsx(parts)
}
