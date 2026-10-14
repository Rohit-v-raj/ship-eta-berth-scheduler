import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { toast } from 'sonner'
import { DCSA_VESSEL_TYPE, estimatedArrival, sendEvents } from '@/api/dcsa'
import { useRefreshPort } from '@/api/queries'
import { askEta } from '@/api/scheduler'
import { When, knots } from '@/components/domain'
import { Button, Field, InlineError, Input, Select } from '@/components/ui/controls'
import { bearingDeg, insideTrainingRegion } from '@/lib/geo'
import { span } from '@/lib/time'
import { VESSEL_TYPES } from '@/lib/visit'

/**
 * The operator's calculator. Given where the ship is and how fast it is going,
 * the ETA model says when it would arrive; if the terminal has already asked
 * for a time, the answer includes the speed that would meet it.
 *
 * Calculating changes nothing. The estimate reaches the terminal only when the
 * operator sends it, as a DCSA estimated arrival that carries the position.
 * The same rules the scheduler applies are checked here first, so a mistake is
 * caught at the field instead of coming back as a refusal.
 */
/** An optional size in metres: an empty field is no size at all. */
const optionalMetres = (what) =>
  z.preprocess(
    (value) => (value === '' || value == null ? undefined : value),
    z.coerce.number({ message: `Enter the ${what} in metres` }).gt(0, `The ${what} must be more than 0`).lt(1000, `The ${what} must be under 1000 m`).optional(),
  )

const schema = z.object({
  lat: z.coerce.number({ message: 'Enter a latitude' }).min(-90, 'Latitude runs from -90 to 90').max(90, 'Latitude runs from -90 to 90'),
  lon: z.coerce.number({ message: 'Enter a longitude' }).min(-180, 'Longitude runs from -180 to 180').max(180, 'Longitude runs from -180 to 180'),
  sog: z.coerce.number({ message: 'Enter a speed' }).min(0, 'Speed cannot be negative').max(40, 'That is faster than any ship here sails'),
  cog: z.coerce.number({ message: 'Enter a course' }).min(0, 'Course runs from 0 to 360').max(360, 'Course runs from 0 to 360'),
  vesselType: z.string(),
  lengthOverall: optionalMetres('length'),
  width: optionalMetres('beam'),
  draft: optionalMetres('draft'),
})

export function PositionForm({ port, vesselCode, visit, picked, onPositionChange, answer, onAnswer }) {
  const refresh = useRefreshPort()
  const destination = port.berths[0]
  const last = visit?.lastReport
  const [failure, setFailure] = useState(null)
  const [pending, setPending] = useState(null)

  const { register, handleSubmit, setValue, control, formState: { errors } } = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      lat: last?.lat ?? 61.62,
      lon: last?.lon ?? 4.45,
      sog: 12,
      cog: 90,
      vesselType: visit?.vessel.type ?? 'GENERAL_CARGO',
      lengthOverall: visit?.vessel.loaMeters ?? '',
      width: visit?.vessel.beamMeters ?? '',
      draft: visit?.arrivalDraftMeters ?? '',
    },
  })

  // A click on the map fills in the position and points the course at the port.
  useEffect(() => {
    if (!picked) return
    setValue('lat', Number(picked.lat.toFixed(4)), { shouldValidate: true })
    setValue('lon', Number(picked.lon.toFixed(4)), { shouldValidate: true })
    setValue('cog', Math.round(bearingDeg(picked.lat, picked.lon, destination.latitude, destination.longitude)))
  }, [picked, setValue, destination.latitude, destination.longitude])

  const [latText, lonText] = useWatch({ control, name: ['lat', 'lon'] })
  const lat = Number(latText)
  const lon = Number(lonText)
  useEffect(() => {
    if (Number.isFinite(lat) && Number.isFinite(lon)) onPositionChange?.({ lat, lon })
  }, [lat, lon, onPositionChange])
  const outside = Number.isFinite(lat) && Number.isFinite(lon) && !insideTrainingRegion(lat, lon)

  const run = (kind) =>
    handleSubmit(async (values) => {
      const query = {
        vesselId: vesselCode,
        lat: values.lat,
        lon: values.lon,
        sog: values.sog,
        cog: values.cog,
        destLat: destination.latitude,
        destLon: destination.longitude,
        vesselType: values.vesselType,
      }
      setPending(kind)
      setFailure(null)
      try {
        let estimate = await askEta(query)
        if (kind === 'send') {
          await sendEvents(
            [
              estimatedArrival(
                vesselCode,
                estimate.estimatedArrival,
                DCSA_VESSEL_TYPE[values.vesselType],
                { lat: values.lat, lon: values.lon, distanceNm: estimate.distanceNm },
                { lengthOverall: values.lengthOverall, width: values.width, draft: values.draft },
              ),
            ],
            'Send the estimated arrival to the terminal',
          )
          await refresh()
          // The terminal has now answered with a requested arrival: ask again for the speed that meets it.
          estimate = await askEta(query, 'Ask what speed meets the requested arrival')
          toast.success('Estimate sent. The terminal has answered with a requested arrival.')
        }
        onAnswer({ estimate, query, sent: kind === 'send' })
      } catch (error) {
        if (kind === 'send') await refresh()
        setFailure(error.message)
      } finally {
        setPending(null)
      }
    })

  const hasCall = Boolean(visit)

  return (
    <form onSubmit={run(hasCall ? 'calculate' : 'send')} noValidate className="flex flex-col gap-3 px-4 py-4">
      <div className="grid grid-cols-2 gap-3">
        <Field id="lat" label="Latitude" error={errors.lat?.message}>
          {(a11y) => <Input {...a11y} inputMode="decimal" {...register('lat')} />}
        </Field>
        <Field id="lon" label="Longitude" error={errors.lon?.message}>
          {(a11y) => <Input {...a11y} inputMode="decimal" {...register('lon')} />}
        </Field>
        <Field id="sog" label="Speed over ground (knots)" error={errors.sog?.message}>
          {(a11y) => <Input {...a11y} inputMode="decimal" {...register('sog')} />}
        </Field>
        <Field id="cog" label="Course over ground (degrees)" error={errors.cog?.message}>
          {(a11y) => <Input {...a11y} inputMode="decimal" {...register('cog')} />}
        </Field>
        <Field id="vesselType" label="Vessel type" hint="Sets the slowest speed worth advising, and the stay the terminal plans for" className="col-span-2">
          {(a11y) => (
            <Select {...a11y} {...register('vesselType')}>
              {VESSEL_TYPES.map((type) => <option key={type.value} value={type.value}>{type.label}</option>)}
            </Select>
          )}
        </Field>
      </div>

      <fieldset className="grid grid-cols-3 gap-3">
        <legend className="mb-1 text-[13px] font-medium">
          Ship&apos;s size <span className="font-normal text-ink-2">(optional, metres)</span>
        </legend>
        <Field id="lengthOverall" label="Length overall" error={errors.lengthOverall?.message}>
          {(a11y) => <Input {...a11y} inputMode="decimal" {...register('lengthOverall')} />}
        </Field>
        <Field id="width" label="Beam" error={errors.width?.message}>
          {(a11y) => <Input {...a11y} inputMode="decimal" {...register('width')} />}
        </Field>
        <Field id="draft" label="Draft" error={errors.draft?.message}>
          {(a11y) => <Input {...a11y} inputMode="decimal" {...register('draft')} />}
        </Field>
        <p className="col-span-3 text-xs text-ink-2">
          Sent with the estimate. The terminal gives a ship only a berth it fits, and turns away one that fits none; without a size it assumes the ship fits.
        </p>
      </fieldset>

      <p className="text-xs text-ink-2">
        Destination: {port.name} ({port.unLocode}). Click the map to set the position; the course is pointed at the port.
      </p>
      {outside && (
        <p className="rounded-md border border-line bg-surface-2 px-3 py-2 text-xs" role="status">
          <span className="mr-1.5 inline-block size-2 rounded-full bg-warning" aria-hidden="true" />
          This position is outside the area the ETA model was trained on. It will still answer, but the estimate is not to be relied on.
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        {hasCall ? (
          <>
            <Button type="submit" variant="primary" busy={pending === 'calculate'} disabled={pending !== null}>Calculate</Button>
            <Button onClick={run('send')} busy={pending === 'send'} disabled={pending !== null}>Send as a new estimate</Button>
          </>
        ) : (
          <>
            <Button type="submit" variant="primary" busy={pending === 'send'} disabled={pending !== null}>
              Send estimated arrival to the terminal
            </Button>
            <Button onClick={run('calculate')} busy={pending === 'calculate'} disabled={pending !== null}>Calculate only</Button>
          </>
        )}
      </div>

      <InlineError>{failure}</InlineError>
      {answer && <Answer answer={answer} />}
    </form>
  )
}

/** The calculator's last answer, in words. */
function Answer({ answer }) {
  const { estimate, query, sent } = answer
  return (
    <div className="rounded-md border border-line bg-surface-2 px-3 py-2 text-[13px]" role="status">
      <p>
        The ETA model estimates arrival at <When value={estimate.estimatedArrival} showRelative={false} className="font-semibold" />,{' '}
        {span(estimate.etaMinutes * 60_000)} from now.
        {estimate.naiveEtaMinutes != null && ` Distance over speed gives ${span(estimate.naiveEtaMinutes * 60_000)}.`}{' '}
        {estimate.distanceNm.toFixed(1)} nm to go in a straight line.
      </p>
      {estimate.requestedArrival && (
        <p className="mt-1">
          {estimate.advisedSpeedKnots != null
            ? `To arrive at the requested time instead of early: ${knots(estimate.advisedSpeedKnots)}, down from ${knots(query.sog)}.`
            : 'No slower speed is worth sailing for the requested time.'}
        </p>
      )}
      <p className="mt-1 text-xs text-ink-2">
        {sent ? 'This estimate was sent to the terminal with your position.' : 'A calculation only: nothing was sent or stored.'}
      </p>
    </div>
  )
}
