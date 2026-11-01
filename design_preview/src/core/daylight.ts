// The part of the day a Seasonal theme's picture shows (F22), as the launcher's DayPhase: dawn from 40 minutes before
// sunrise to 50 after, sunset from 50 minutes before sunset to 40 after, day and night in between. Sunrise and sunset
// come from the sunrise equation at the mock's place (Stockholm, the build's city), worked out offline.

export type Phase = 'dawn' | 'day' | 'sunset' | 'night';

export const PHASES: Phase[] = ['dawn', 'day', 'sunset', 'night'];
export const PHASE_LABELS: Record<Phase, string> = { dawn: 'Dawn', day: 'Day', sunset: 'Sunset', night: 'Night' };

const LAT = 59.33;
const LON = 18.07;
const MIN = 60_000;
const DAY_MS = 86_400_000;
const rad = (d: number) => (d * Math.PI) / 180;
const norm = (d: number) => ((d % 360) + 360) % 360;

/** Sunrise and sunset (ms) around a moment; 'up' or 'down' when the sun doesn't cross the horizon that day. */
function sun(noonMs: number): [number, number] | 'up' | 'down' {
  const julian = noonMs / DAY_MS + 2440587.5;
  const n = Math.ceil(julian - 2451545.0 + 0.0008 - 0.5);
  const jStar = n - LON / 360;
  const m = norm(357.5291 + 0.98560028 * jStar);
  const mr = rad(m);
  const c = 1.9148 * Math.sin(mr) + 0.02 * Math.sin(2 * mr) + 0.0003 * Math.sin(3 * mr);
  const lambda = rad(norm(m + c + 180 + 102.9372));
  const transit = 2451545.0 + jStar + 0.0053 * Math.sin(mr) - 0.0069 * Math.sin(2 * lambda);
  const sinDec = Math.sin(lambda) * Math.sin(rad(23.4397));
  const cosDec = Math.cos(Math.asin(sinDec));
  const cosW = (Math.sin(rad(-0.833)) - Math.sin(rad(LAT)) * sinDec) / (Math.cos(rad(LAT)) * cosDec);
  if (cosW < -1) return 'up';
  if (cosW > 1) return 'down';
  const w = Math.acos(cosW) / (2 * Math.PI);
  const ms = (j: number) => (j - 2440587.5) * DAY_MS;
  return [ms(transit - w), ms(transit + w)];
}

/** A date's four edges: dawn, day, sunset and night start (ms); or the phase all day long. */
function edges(d: Date): number[] | Phase {
  const noon = new Date(d.getFullYear(), d.getMonth(), d.getDate(), 12).getTime();
  const s = sun(noon);
  if (s === 'up') return 'day';
  if (s === 'down') return 'night';
  const [rise, set] = s;
  const e = [rise - 40 * MIN, rise + 50 * MIN, set - 50 * MIN, set + 40 * MIN];
  if (e[1] > e[2]) e[1] = e[2] = (rise + set) / 2;
  return e;
}

export function phaseAt(d: Date): Phase {
  const e = edges(d);
  if (typeof e === 'string') return e;
  const t = d.getTime();
  if (t < e[0]) return 'night';
  if (t < e[1]) return 'dawn';
  if (t < e[2]) return 'day';
  if (t < e[3]) return 'sunset';
  return 'night';
}

/** Milliseconds until the part of the day next changes (at most until the next midnight). */
export function msToNextPhase(d: Date): number {
  const t = d.getTime();
  const midnight = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime();
  const e = edges(d);
  if (typeof e !== 'string') for (const x of e) if (x > t) return x - t;
  const tomorrow = edges(new Date(midnight + 12 * 3_600_000));
  if (typeof tomorrow !== 'string') return tomorrow[0] - t;
  return midnight - t;
}
