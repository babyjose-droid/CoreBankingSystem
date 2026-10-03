/**
 * Pure lending arithmetic for the mock API, mirroring backend/calc and backend/lending-core.
 * All money is held as integer paise (JS numbers are exact far beyond any loan size); interest and EMI are
 * rounded to the rupee (RUPEE_HALF_UP), fees and GST to the paisa.
 */
import type { FeeRule, LoanProduct } from '../api/types';
import { toUtcDate } from '../lib/dates';
import { fromUnits, isMoney, toUnits } from '../lib/money';

export type Paise = number;
export type RepaymentMethod = LoanProduct['repaymentMethod'];

// ---------- money ----------
export function toPaise(v: string | number | null | undefined): Paise {
  if (v === null || v === undefined || v === '') return 0;
  const s = typeof v === 'number' ? String(v) : v.trim();
  if (!isMoney(s)) throw new Error(`Invalid money value: ${s}`);
  return Number(fromUnits(toUnits(s), 2).replace('.', ''));
}

export function fromPaise(p: Paise): string {
  const neg = p < 0;
  const abs = Math.abs(Math.round(p));
  return `${neg && abs ? '-' : ''}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

/** Round a (possibly fractional) paise amount to whole rupees, half-up. */
export const roundRupee = (p: number): Paise => Math.round(p / 100 + 1e-9) * 100;
/** Round to whole paise, half-up. */
export const roundPaise = (p: number): Paise => Math.round(p + 1e-9);

export function num(v: string | number | null | undefined): number {
  if (v === null || v === undefined || v === '') return NaN;
  return typeof v === 'number' ? v : Number(v);
}

/** "18.5" / 18.5 -> "18.50" */
export function pct(v: string | number | null | undefined): string {
  const n = num(v);
  return Number.isFinite(n) ? n.toFixed(2) : '';
}

// ---------- dates ----------
const lastDayOf = (y: number, m0: number) => new Date(Date.UTC(y, m0 + 1, 0)).getUTCDate();

/** Calendar months after `iso`; the day is clamped to the month length (Java LocalDate.plusMonths). */
export function addMonths(iso: string, n: number, monthEnd = false): string {
  const [y, m, d] = iso.split('-').map(Number);
  const t = new Date(Date.UTC(y, m - 1 + n, 1));
  const last = lastDayOf(t.getUTCFullYear(), t.getUTCMonth());
  t.setUTCDate(monthEnd ? last : Math.min(d, last));
  return t.toISOString().slice(0, 10);
}

export function isMonthEnd(iso: string): boolean {
  const [y, m, d] = iso.split('-').map(Number);
  return d === lastDayOf(y, m - 1);
}

export function daysBetween(from: string, to: string): number {
  return Math.round((toUtcDate(to).getTime() - toUtcDate(from).getTime()) / 86_400_000);
}

/**
 * Due date of instalment n (1-based). Dates derive from the anchor (never chained) and are month-end anchored when
 * the anchor is a month end: open 30-Jun gives 31-Jul, 31-Aug, 30-Sep …
 */
export function dueDate(disbursal: string, firstDue: string | null | undefined, n: number): string {
  if (!firstDue) return addMonths(disbursal, n, isMonthEnd(disbursal));
  if (n === 1) return firstDue;
  return addMonths(firstDue, n - 1, isMonthEnd(firstDue));
}

// ---------- schedule ----------
export interface Row {
  no: number;
  dueDate: string;
  days: number;
  opening: Paise;
  interest: Paise;
  principal: Paise;
  instalment: Paise;
  closing: Paise;
}

export function interestFor(balance: Paise, ratePct: number, days: number): Paise {
  return roundRupee((balance * ratePct * days) / 36500);
}

export type Frequency = NonNullable<LoanProduct['frequency']>;
/** Instalment periods per year. */
export const PERIODS_PER_YEAR: Record<Frequency, number> = { DAILY: 365, WEEKLY: 52, FORTNIGHTLY: 26, MONTHLY: 12, QUARTERLY: 4, HALF_YEARLY: 2, YEARLY: 1 };
const MONTHS_PER_PERIOD: Partial<Record<Frequency, number>> = { MONTHLY: 1, QUARTERLY: 3, HALF_YEARLY: 6, YEARLY: 12 };
const DAYS_PER_PERIOD: Partial<Record<Frequency, number>> = { DAILY: 1, WEEKLY: 7, FORTNIGHTLY: 14 };

/** `n` periods of the frequency after `iso`. */
export function addPeriods(iso: string, n: number, freq: Frequency = 'MONTHLY', monthEnd = false): string {
  const days = DAYS_PER_PERIOD[freq];
  if (days) {
    const d = toUtcDate(iso);
    d.setUTCDate(d.getUTCDate() + days * n);
    return d.toISOString().slice(0, 10);
  }
  return addMonths(iso, n * (MONTHS_PER_PERIOD[freq] ?? 1), monthEnd);
}

/** Due date of instalment n (1-based) at a frequency; see dueDate for the anchoring rule. */
export function dueDateAt(disbursal: string, firstDue: string | null | undefined, n: number, freq: Frequency = 'MONTHLY'): string {
  if (!firstDue) return addPeriods(disbursal, n, freq, isMonthEnd(disbursal));
  return n === 1 ? firstDue : addPeriods(firstDue, n - 1, freq, isMonthEnd(firstDue));
}

/** PMT rounded to the rupee. */
export function pmt(principal: Paise, ratePct: number, n: number, ppy = 12): Paise {
  if (n <= 0) return principal;
  if (ratePct === 0) return roundRupee(principal / n);
  const i = ratePct / (100 * ppy);
  return roundRupee((principal * i) / (1 - Math.pow(1 + i, -n)));
}

/** EMI when a balloon of `balloon` is left for the last instalment (on top of its EMI). */
export function pmtBalloon(principal: Paise, ratePct: number, n: number, balloon: Paise, ppy = 12): Paise {
  if (!balloon) return pmt(principal, ratePct, n, ppy);
  if (n <= 0) return principal;
  if (ratePct === 0) return roundRupee((principal - balloon) / n);
  const i = ratePct / (100 * ppy);
  const pv = balloon / Math.pow(1 + i, n);
  return roundRupee(((principal - pv) * i) / (1 - Math.pow(1 + i, -n)));
}

/** Annual rate (%) at which `n` instalments of `emi` repay `principal` (rate basis "tenure, amount and instalment"). */
export function rateForInstalment(principal: Paise, n: number, emi: Paise, ppy = 12): number {
  if (emi * n <= principal) return 0;
  let lo = 0;
  let hi = 200;
  for (let k = 0; k < 80; k++) {
    const mid = (lo + hi) / 2;
    const i = mid / (100 * ppy);
    const need = (principal * i) / (1 - Math.pow(1 + i, -n));
    if (need > emi) hi = mid;
    else lo = mid;
  }
  return Math.round(lo * 10000) / 10000;
}

/** First instalment of a step-up / step-down loan: instalment k is base x (1 + step%)^floor(k / every). */
export function stepBase(principal: Paise, ratePct: number, n: number, stepPct: number, every: number, ppy = 12): Paise {
  const i = ratePct / (100 * ppy);
  let pv = 0;
  for (let k = 0; k < n; k++) pv += Math.pow(1 + stepPct / 100, Math.floor(k / every)) / Math.pow(1 + i, k + 1);
  return roundRupee(principal / pv);
}

export interface RowsInput {
  balance: Paise;
  ratePct: number;
  /** Interest runs from here to the first due date. */
  from: string;
  dueDates: string[];
  method: RepaymentMethod;
  /** Leading interest-only rows. */
  moratoriumRows?: number;
  /** EQUATED: fixed instalment; computed with PMT over the amortising rows when omitted. */
  emi?: Paise | null;
  /** FIXED_PRINCIPAL: fixed principal part. */
  principalPart?: Paise | null;
  startNo?: number;
  /** STEP_EQUATED: instalment of row i (0-based within this call). */
  emiAt?: (i: number) => Paise;
  /** STRUCTURED: principal of each row. */
  principalPlan?: Paise[];
  /** FIXED_PRINCIPAL: principal falls due every n-th row only. */
  principalEvery?: number;
  /** Rows before this call (for principalEvery counting). */
  offset?: number;
}

/** Builds schedule rows; the last row clears the balance and absorbs rounding. */
export function buildRows(input: RowsInput): Row[] {
  const { ratePct, method, dueDates } = input;
  const mor = input.moratoriumRows ?? 0;
  const start = input.startNo ?? 1;
  const rows: Row[] = [];
  let bal = input.balance;
  let prev = input.from;
  if (dueDates.length === 0 || bal <= 0) return rows;
  if (method === 'BULLET_TOTAL_INTEREST') {
    const due = dueDates[dueDates.length - 1];
    const days = daysBetween(prev, due);
    const interest = interestFor(bal, ratePct, days);
    return [{ no: start, dueDate: due, days, opening: bal, interest, principal: bal, instalment: bal + interest, closing: 0 }];
  }
  const emi = input.emi ?? pmt(bal, ratePct, dueDates.length - mor);
  const every = Math.max(1, input.principalEvery ?? 1);
  const offset = input.offset ?? 0;
  const principalRows = dueDates.filter((_, i) => (offset + i + 1) % every === 0 || i === dueDates.length - 1).length;
  const part = input.principalPart ?? roundRupee(bal / Math.max(1, every > 1 ? principalRows : dueDates.length));
  const full = bal;
  for (let i = 0; i < dueDates.length; i++) {
    const due = dueDates[i];
    const days = daysBetween(prev, due);
    const last = i === dueDates.length - 1;
    const interest = interestFor(method === 'BULLET_PERIODIC_INTEREST' ? full : bal, ratePct, days);
    let principal: Paise;
    if (last) principal = bal;
    else if (i < mor) principal = 0;
    else if (input.principalPlan) principal = Math.min(input.principalPlan[i] ?? 0, bal);
    else if (method === 'EQUATED' || method === 'STEP_EQUATED') principal = Math.min(Math.max((input.emiAt ? input.emiAt(i) : emi) - interest, 0), bal);
    else if (method === 'FIXED_PRINCIPAL' && every > 1 && (offset + i + 1) % every !== 0) principal = 0;
    else if (method === 'FIXED_PRINCIPAL') principal = Math.min(part, bal);
    else principal = 0; // BULLET_PERIODIC_INTEREST
    const closing = bal - principal;
    rows.push({ no: start + i, dueDate: due, days, opening: bal, interest, principal, instalment: principal + interest, closing });
    bal = closing;
    prev = due;
    if (bal === 0) break;
  }
  return rows;
}

// ---------- fees and GST ----------
export interface FeeCharge {
  code: string;
  name: string;
  fee: Paise;
  cgst: Paise;
  sgst: Paise;
  igst: Paise;
  total: Paise;
}

/** Fee for a rule on a base amount, with GST split by place of supply (intra-state: CGST + SGST, else IGST). */
export function computeFee(rule: FeeRule, base: Paise, supplierState: string, recipientState: string): FeeCharge {
  let fee: Paise;
  if (rule.calcType === 'FIXED') fee = toPaise(rule.amount ?? 0);
  else if (rule.calcType === 'PERCENT') fee = roundPaise((base * num(rule.percent ?? 0)) / 100);
  else {
    const slab = (rule.slabs ?? []).find((s) => base >= toPaise(s.from ?? 0) && base <= toPaise(s.to ?? 0));
    fee = slab ? toPaise(slab.fee ?? 0) : 0;
  }
  if (rule.minAmount !== null && rule.minAmount !== undefined && rule.minAmount !== '') fee = Math.max(fee, toPaise(rule.minAmount));
  if (rule.maxAmount !== null && rule.maxAmount !== undefined && rule.maxAmount !== '') fee = Math.min(fee, toPaise(rule.maxAmount));
  const gstRate = Number.isFinite(num(rule.gstRate)) ? num(rule.gstRate) : 18;
  if (rule.taxTreatment === 'INCLUSIVE') fee = roundPaise((fee * 100) / (100 + gstRate));
  const intra = supplierState === recipientState;
  const half = roundPaise((fee * gstRate) / 200);
  const cgst = intra ? half : 0;
  const sgst = intra ? half : 0;
  const igst = intra ? 0 : roundPaise((fee * gstRate) / 100);
  return { code: rule.code, name: rule.name, fee, cgst, sgst, igst, total: fee + cgst + sgst + igst };
}

// ---------- APR ----------
function npv(flows: number[], r: number): number {
  let v = 0;
  for (let i = 0; i < flows.length; i++) v += flows[i] / Math.pow(1 + r, i);
  return v;
}

/** Periodic IRR × periods per year, % p.a. to 2 dp, by bisection (monotone, never diverges). */
export function nominalAnnualIrr(flows: number[], periodsPerYear = 12): string {
  let lo = 0;
  let hi = 1;
  for (let i = 0; i < 200; i++) {
    const mid = (lo + hi) / 2;
    if (npv(flows, mid) > 0) lo = mid;
    else hi = mid;
  }
  return (Math.round(lo * periodsPerYear * 100 * 100 + 1e-9) / 100).toFixed(2);
}

/** Effective annual rate on dated flows (Actual/365), % to 2 dp. */
export function xirr(flows: Array<{ date: string; amount: number }>): string {
  const t0 = flows[0].date;
  let lo = -0.99;
  let hi = 10;
  for (let i = 0; i < 300; i++) {
    const mid = (lo + hi) / 2;
    let v = 0;
    for (const f of flows) v += f.amount / Math.pow(1 + mid, daysBetween(t0, f.date) / 365);
    if (v > 0) lo = mid;
    else hi = mid;
  }
  return (Math.round(lo * 100 * 100 + 1e-9) / 100).toFixed(2);
}

/**
 * APR for the KFS: IRR of [−(amount − fees ex-GST), instalments…]. For EMI loans the contractual flat EMI is used
 * for amortising rows ("IRR basic", golden value G-08); bullet loans use XIRR on dated flows.
 */
export function apr(amount: Paise, feesExGst: Paise, rows: Row[], method: RepaymentMethod, disbursal: string, emi: Paise | null): string {
  const net = (amount - feesExGst) / 100;
  if (rows.length === 0) return '0.00';
  if (method === 'BULLET_TOTAL_INTEREST') {
    return xirr([{ date: disbursal, amount: -net }, ...rows.map((r) => ({ date: r.dueDate, amount: r.instalment / 100 }))]);
  }
  const flows = rows.map((r) => (method === 'EQUATED' && emi !== null && r.principal > 0 ? emi : r.instalment) / 100);
  return nominalAnnualIrr([-net, ...flows], 12);
}

// ---------- delinquency ----------
export type AssetClass = 'STANDARD' | 'SMA0' | 'SMA1' | 'SMA2' | 'SUBSTANDARD' | 'DOUBTFUL1' | 'DOUBTFUL2' | 'DOUBTFUL3' | 'LOSS';

/** Days past due; the due date itself is day 1 (RBI: overdue if unpaid at day-end of the due date). */
export function dpdOf(asOf: string, oldestUnpaidDue: string | null): number {
  if (!oldestUnpaidDue || oldestUnpaidDue > asOf) return 0;
  return daysBetween(oldestUnpaidDue, asOf) + 1;
}

export function smaClass(dpd: number): AssetClass {
  if (dpd > 90) return 'SUBSTANDARD';
  if (dpd > 60) return 'SMA2';
  if (dpd > 30) return 'SMA1';
  if (dpd > 0) return 'SMA0';
  return 'STANDARD';
}

export function npaAge(npaSince: string, asOf: string): AssetClass {
  const [y1, m1, d1] = npaSince.split('-').map(Number);
  const [y2, m2, d2] = asOf.split('-').map(Number);
  const months = (y2 - y1) * 12 + (m2 - m1) - (d2 < d1 ? 1 : 0);
  if (months < 12) return 'SUBSTANDARD';
  if (months < 24) return 'DOUBTFUL1';
  if (months < 48) return 'DOUBTFUL2';
  return 'DOUBTFUL3';
}

export const NPA_CLASSES: AssetClass[] = ['SUBSTANDARD', 'DOUBTFUL1', 'DOUBTFUL2', 'DOUBTFUL3', 'LOSS'];

/** Provision % of principal outstanding (starter RBI IRACP minimums, unsecured). */
export const PROVISION_PCT: Record<AssetClass, number> = {
  STANDARD: 0.4, SMA0: 0.4, SMA1: 0.4, SMA2: 0.4, SUBSTANDARD: 10, DOUBTFUL1: 100, DOUBTFUL2: 100, DOUBTFUL3: 100, LOSS: 100,
};
