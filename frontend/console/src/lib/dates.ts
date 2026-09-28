const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

export const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function parts(iso: string): [number, number, number] {
  const [y, m, d] = iso.split('-').map(Number);
  return [y, m, d];
}

/** Business dates are calendar dates without a time zone; do all arithmetic in UTC. */
export function toUtcDate(iso: string): Date {
  const [y, m, d] = parts(iso);
  return new Date(Date.UTC(y, m - 1, d));
}

export function fromUtcDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function addDays(iso: string, days: number): string {
  const d = toUtcDate(iso);
  d.setUTCDate(d.getUTCDate() + days);
  return fromUtcDate(d);
}

export function dayOfWeek(iso: string): number {
  return toUtcDate(iso).getUTCDay();
}

/** "2026-06-30" -> "30 Jun 2026" */
export function formatDate(iso: string | null | undefined, withWeekday = false): string {
  if (!iso) return '—';
  if (!ISO_DATE.test(iso.slice(0, 10))) return iso;
  const [y, m, d] = parts(iso.slice(0, 10));
  const base = `${String(d).padStart(2, '0')} ${MONTHS[m - 1]} ${y}`;
  return withWeekday ? `${DAYS[dayOfWeek(iso.slice(0, 10))]}, ${base}` : base;
}

/** RFC 3339 timestamp -> "30 Jun 2026, 14:05" in the viewer's local time. */
export function formatDateTime(ts: string | null | undefined): string {
  if (!ts) return '—';
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return ts;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getDate())} ${MONTHS[d.getMonth()]} ${d.getFullYear()}, ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Whole years between dob and asOf (both ISO dates). */
export function ageOn(dob: string, asOf: string): number {
  const [by, bm, bd] = parts(dob);
  const [ay, am, ad] = parts(asOf);
  let age = ay - by;
  if (am < bm || (am === bm && ad < bd)) age -= 1;
  return age;
}

/** Indian financial year start (1 April) for a date. */
export function financialYearStart(iso: string): string {
  const [y, m] = parts(iso);
  return `${m >= 4 ? y : y - 1}-04-01`;
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.max(0, Math.round(ms))} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)} s`;
  const m = Math.floor(s / 60);
  return `${m} m ${Math.round(s % 60)} s`;
}
