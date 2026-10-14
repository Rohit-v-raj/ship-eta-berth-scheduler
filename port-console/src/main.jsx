import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/inter'
import '@fontsource-variable/jetbrains-mono'
import './styles/globals.css'
import App from './App'

// `npm run dev:mock` runs the console with no scheduler behind it: a service
// worker answers the API calls from fixtures.
async function startMocks() {
  if (import.meta.env.MODE !== 'mock') return
  const { worker } = await import('./test/msw/browser')
  await worker.start()
}

startMocks().then(() => {
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})
