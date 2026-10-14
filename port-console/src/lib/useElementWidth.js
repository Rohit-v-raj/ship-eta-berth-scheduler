import { useLayoutEffect, useRef, useState } from 'react'

/** The rendered width of an element, kept up to date as the layout changes. */
export function useElementWidth(fallback = 600) {
  const ref = useRef(null)
  const [width, setWidth] = useState(fallback)

  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return undefined
    setWidth(element.clientWidth || fallback)
    if (typeof ResizeObserver === 'undefined') return undefined
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [fallback])

  return [ref, width]
}
