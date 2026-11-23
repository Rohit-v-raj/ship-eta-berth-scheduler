import { TriangleAlert } from 'lucide-react'
import { useRouteError } from 'react-router-dom'
import { Button, Notice } from '@/components/ui/controls'

/**
 * Shown in place of a page that failed to draw, so one bad value does not
 * blank the whole console: the header stays, and a reload starts the page
 * again.
 */
export function RouteError() {
  const error = useRouteError()
  const detail = error instanceof Error ? error.message : error?.statusText ?? ''
  return (
    <div className="mt-20 flex flex-col items-center gap-3">
      <Notice icon={TriangleAlert} tone="error" title="This page ran into a problem">
        {detail ? `${detail}. ` : ''}Reload the page to start it again; the other pages still work.
      </Notice>
      <Button variant="secondary" onClick={() => window.location.reload()}>
        Reload the page
      </Button>
    </div>
  )
}
