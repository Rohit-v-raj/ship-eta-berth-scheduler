import { render, screen } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RouteError } from './RouteError'
import { routes } from './router'

function Boom() {
  throw new Error('Invalid LngLat latitude value: must be between -90 and 90')
}

describe('RouteError', () => {
  afterEach(() => vi.restoreAllMocks())

  it('replaces a page that fails to draw with a notice, and the shell around it stays', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {}) // React reports the thrown error; expected here
    const router = createMemoryRouter([
      {
        path: '/',
        element: (
          <div>
            <header>Port Call Console</header>
            <Outlet />
          </div>
        ),
        children: [{ errorElement: <RouteError />, children: [{ index: true, element: <Boom /> }] }],
      },
    ])

    render(<RouterProvider router={router} />)

    expect(screen.getByText('Port Call Console')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('This page ran into a problem')
    expect(screen.getByRole('alert')).toHaveTextContent('Invalid LngLat latitude value')
    expect(screen.getByRole('button', { name: 'Reload the page' })).toBeInTheDocument()
  })

  it('guards every page of the console', () => {
    const [shell] = routes
    expect(shell.children).toHaveLength(1)
    expect(shell.children[0].errorElement).toBeTruthy()
    expect(shell.children[0].children.map((route) => route.path ?? 'index'))
      .toEqual(['index', 'planner', 'operator', 'operator/:vesselId', 'events', '*'])
  })
})
