/**
 * Geometry for hints on screen only. The scheduler and the model compute the
 * figures that count; nothing here is sent to them.
 */

const EARTH_NM = 3440.065
const rad = (deg) => (deg * Math.PI) / 180

/** Great-circle distance in nautical miles. */
export function distanceNm(lat1, lon1, lat2, lon2) {
  const dLat = rad(lat2 - lat1)
  const dLon = rad(lon2 - lon1)
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2
  return 2 * EARTH_NM * Math.asin(Math.sqrt(a))
}

/** Initial bearing from point 1 to point 2, degrees from north, 0 to 360. */
export function bearingDeg(lat1, lon1, lat2, lon2) {
  const dLon = rad(lon2 - lon1)
  const x = Math.sin(dLon) * Math.cos(rad(lat2))
  const y = Math.cos(rad(lat1)) * Math.sin(rad(lat2)) - Math.sin(rad(lat1)) * Math.cos(rad(lat2)) * Math.cos(dLon)
  return ((Math.atan2(x, y) * 180) / Math.PI + 360) % 360
}

/**
 * The area the ETA model was trained on (Norwegian west coast). A position
 * outside it still gets an answer, but not one to rely on.
 */
export const TRAINING_REGION = { minLat: 61.49, maxLat: 62.81, minLon: 4.13, maxLon: 7.76 }

export function insideTrainingRegion(lat, lon) {
  const r = TRAINING_REGION
  return lat >= r.minLat && lat <= r.maxLat && lon >= r.minLon && lon <= r.maxLon
}
