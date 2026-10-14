import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Map, { Layer, Marker, NavigationControl, Source } from 'react-map-gl/maplibre'
import { setWorkerUrl } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import 'maplibre-gl/dist/maplibre-gl.css'
import { Anchor, Crosshair, Maximize2 } from 'lucide-react'
import { useTheme } from '@/app/providers'
import { Button } from '@/components/ui/controls'
import { cn } from '@/lib/cn'
import { bearingDeg } from '@/lib/geo'
import { STANDING, standingOf, vesselLabel } from '@/lib/visit'

/**
 * The harbour on a real map: the port, its berths when zoomed in, and each
 * inbound ship at the position it last gave with an estimate.
 *
 * The system only knows where a ship said it was, so a marker sits still
 * between reports. Everything shown here is also in the arrivals table.
 */

// MapLibre draws tiles in a web worker and looks for the worker's file beside
// its own. A bundler moves both, so tell it where the worker ended up.
setWorkerUrl(workerUrl)

const STYLES = {
  light: import.meta.env.VITE_MAP_STYLE_LIGHT ?? 'https://tiles.openfreemap.org/styles/positron',
  dark: import.meta.env.VITE_MAP_STYLE_DARK ?? 'https://tiles.openfreemap.org/styles/dark',
}

const BERTH_ZOOM = 11.5

export default function PortMap({ port, visits = [], selectedRef, onSelect, picked, onPick, track, className }) {
  const { theme } = useTheme()
  const mapRef = useRef(null)
  const [zoom, setZoom] = useState(8.2)
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)

  const ships = useMemo(
    () => visits.filter((v) => v.lastReport?.lat != null && v.lastReport?.lon != null && standingOf(v) !== STANDING.ALONGSIDE),
    [visits],
  )
  const trackLine = useMemo(() => {
    const points = (track ?? []).filter((r) => r.lat != null && r.lon != null).map((r) => [r.lon, r.lat])
    return points.length < 2 ? null : { type: 'Feature', geometry: { type: 'LineString', coordinates: points } }
  }, [track])

  /** Move the view so the port and every ship (and a picked position) are in it. */
  const fitAll = useCallback(
    (duration = 600) => {
      const points = [[port.longitude, port.latitude], ...ships.map((v) => [v.lastReport.lon, v.lastReport.lat])]
      if (picked) points.push([picked.lon, picked.lat])
      if (points.length < 2) return
      const lons = points.map((p) => p[0])
      const lats = points.map((p) => p[1])
      mapRef.current?.fitBounds(
        [[Math.min(...lons), Math.min(...lats)], [Math.max(...lons), Math.max(...lats)]],
        { padding: 64, maxZoom: 11, duration },
      )
    },
    [port, ships, picked],
  )

  // Re-frame when a ship appears or leaves, not each time one moves: the reader may have zoomed in on purpose.
  const shipKey = ships.map((v) => v.visitRef).join('|')
  const fitRef = useRef(fitAll)
  useEffect(() => {
    fitRef.current = fitAll
  })
  useEffect(() => {
    if (loaded) fitRef.current(0)
  }, [loaded, shipKey])

  return (
    <div className={cn('relative h-full min-h-64 overflow-hidden', className)}>
      <Map
        ref={mapRef}
        mapStyle={STYLES[theme] ?? STYLES.light}
        initialViewState={{ longitude: port.longitude - 0.35, latitude: port.latitude, zoom: 8.2 }}
        onMove={(event) => setZoom(event.viewState.zoom)}
        onClick={onPick ? (event) => onPick({ lat: event.lngLat.lat, lon: event.lngLat.lng }) : undefined}
        onLoad={() => setLoaded(true)}
        onError={() => setFailed(true)}
        cursor={onPick ? 'crosshair' : undefined}
        attributionControl={{ compact: true }}
        style={{ width: '100%', height: '100%' }}
      >
        <NavigationControl position="top-right" showCompass={false} />

        {trackLine && (
          <Source id="track" type="geojson" data={trackLine}>
            <Layer id="track-line" type="line" paint={{ 'line-color': theme === 'dark' ? '#3987e5' : '#2a78d6', 'line-width': 2 }} />
          </Source>
        )}

        {zoom < BERTH_ZOOM ? (
          <Marker longitude={port.longitude} latitude={port.latitude} anchor="bottom">
            <div className="flex flex-col items-center">
              <span className="rounded-md border border-line bg-surface px-1.5 py-0.5 text-[11px] font-semibold shadow-panel">{port.name}</span>
              <Anchor className="mt-0.5 size-5 text-ink" aria-hidden="true" />
            </div>
          </Marker>
        ) : (
          port.berths.map((berth) => (
            <Marker key={berth.code} longitude={berth.longitude} latitude={berth.latitude} anchor="center">
              <span className="rounded-sm border border-ink bg-surface px-1 text-[11px] font-semibold" title={berth.name}>{berth.code}</span>
            </Marker>
          ))
        )}

        {ships.map((visit) => (
          <Marker key={visit.visitRef} longitude={visit.lastReport.lon} latitude={visit.lastReport.lat} anchor="center">
            <ShipMarker visit={visit} port={port} selected={visit.visitRef === selectedRef} onSelect={onSelect} />
          </Marker>
        ))}

        {picked && (
          <Marker longitude={picked.lon} latitude={picked.lat} anchor="center">
            <Crosshair className="size-7 text-ink" strokeWidth={2.2} aria-label="Position to report" />
          </Marker>
        )}
      </Map>

      <Button variant="secondary" size="sm" onClick={() => fitAll()} className="absolute top-2 left-2 shadow-panel">
        <Maximize2 className="size-3.5" aria-hidden="true" />
        Show all
      </Button>

      {failed && (
        <p role="status" className="absolute inset-x-2 bottom-2 rounded-md border border-line bg-surface px-3 py-2 text-xs shadow-panel">
          {loaded
            ? 'Part of the map background could not be loaded; it needs an internet connection. Ship positions are still shown.'
            : 'The map could not be drawn. It needs an internet connection for the background and a browser with WebGL. Every position is listed in the table.'}
        </p>
      )}
    </div>
  )
}

/**
 * A ship at the position it last gave: an arrow, hollow while it waits for a
 * berth. A DCSA estimate carries a position but no course, so the arrow points
 * at the port, which is where an inbound ship is heading.
 */
function ShipMarker({ visit, port, selected, onSelect }) {
  const waiting = standingOf(visit) === STANDING.WAITING
  const label = vesselLabel(visit.vessel)
  const course = bearingDeg(visit.lastReport.lat, visit.lastReport.lon, port.latitude, port.longitude)
  const body = (
    <>
      <svg width="26" height="26" viewBox="-13 -13 26 26" style={{ transform: `rotate(${course}deg)` }} aria-hidden="true">
        {selected && <circle r="12" fill="none" stroke="var(--ink)" strokeWidth="1.5" />}
        <path
          d="M 0 -9 L 6 7 L 0 3.5 L -6 7 Z"
          fill={waiting ? 'var(--surface)' : 'var(--series-1)'}
          stroke={waiting ? 'var(--series-1)' : 'var(--surface)'}
          strokeWidth="2"
          strokeLinejoin="round"
        />
      </svg>
      <span className="rounded-sm bg-surface/90 px-1 text-[11px] font-medium text-ink">{label}</span>
    </>
  )
  if (!onSelect) return <div className="flex flex-col items-center">{body}</div>
  return (
    <button
      type="button"
      onClick={(event) => {
        event.stopPropagation()
        onSelect(visit.visitRef)
      }}
      aria-label={`${label}, ${waiting ? 'waiting for a berth' : 'berth reserved'}. Open the visit.`}
      className="flex cursor-pointer flex-col items-center"
    >
      {body}
    </button>
  )
}
