/** Plain-language reading of the common six-field cron shapes (second minute hour day-of-month month day-of-week). Times are IST. */
const DAYS: Record<string, string> = { '0': 'Sunday', '1': 'Monday', '2': 'Tuesday', '3': 'Wednesday', '4': 'Thursday', '5': 'Friday', '6': 'Saturday', '7': 'Sunday', SUN: 'Sunday', MON: 'Monday', TUE: 'Tuesday', WED: 'Wednesday', THU: 'Thursday', FRI: 'Friday', SAT: 'Saturday' };
const any = (f: string) => f === '*' || f === '?';
const num = (f: string) => /^\d{1,2}$/.test(f);
const pad = (f: string) => f.padStart(2, '0');

export function describeCron(expr: string | null | undefined): string | null {
  if (!expr) return null;
  const f = expr.trim().split(/\s+/);
  if (f.length !== 6) return null;
  const [, m, h, dom, mon, dow] = f;
  if (!num(m) || !any(mon)) return null;
  if (any(h) && any(dom) && any(dow)) return `Every hour at ${pad(m)} minutes past`;
  if (!num(h)) return null;
  const at = `${pad(h)}:${pad(m)} IST`;
  if (any(dom) && any(dow)) return `Every day at ${at}`;
  if (num(dom) && any(dow)) return `Day ${Number(dom)} of every month at ${at}`;
  if (any(dom) && DAYS[dow.toUpperCase()]) return `Every ${DAYS[dow.toUpperCase()]} at ${at}`;
  return null;
}

export function looksLikeCron(expr: string): boolean {
  return expr.trim().split(/\s+/).length === 6;
}
