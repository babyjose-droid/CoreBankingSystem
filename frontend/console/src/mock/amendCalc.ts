/**
 * Loan amendments (rate / tenure / EMI / due-day change) and restructures for the mock API. Pure functions on a
 * loan's derived state, used both for previews (on a copy) and when replaying the AMENDMENT / RESTRUCTURE
 * transactions. A reasonable recomputation, not the backend engine to the paisa.
 */
import type { AmendmentRequest, AssetClass, RestructureOption, RestructureTerms, ScheduleRow } from '../api/types';
import type { LoanState, StoredLoan } from './db';
import * as C from './lendingCalc';
import { bad, conflict } from './problems';

const unpaidPrincipal = (st: LoanState) => st.demands.reduce((s, d) => s + d.principalDue - d.principalPaid, 0);
const unpaidInterest = (st: LoanState) => st.demands.reduce((s, d) => s + d.interestDue - d.interestPaid, 0);
const future = (st: LoanState) => st.rows.slice(st.raised);
const futurePrincipal = (st: LoanState) => future(st).reduce((s, r) => s + r.principal, 0);
const NPA: AssetClass[] = ['SUBSTANDARD', 'DOUBTFUL1', 'DOUBTFUL2', 'DOUBTFUL3', 'LOSS'];

export const cloneState = (st: LoanState): LoanState => JSON.parse(JSON.stringify(st)) as LoanState;

function lastDayOf(iso: string): number {
  const [y, m] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

/** Same month as `iso`, on `day` (31 = month end; clamped to the month's length). */
function withDay(iso: string, day: number): string {
  const last = lastDayOf(iso);
  return `${iso.slice(0, 8)}${String(day >= 31 ? last : Math.min(day, last)).padStart(2, '0')}`;
}

/** n monthly due dates starting at `first`, month-end anchored when `first` is a month end. */
function monthly(first: string, n: number, monthEnd = C.isMonthEnd(first)): string[] {
  return Array.from({ length: n }, (_, k) => C.addMonths(first, k, monthEnd));
}

/** Due dates for n instalments: keep today's dates where they exist, extend monthly after the last. */
function continueDates(st: LoanState, n: number, asOf: string): string[] {
  const fut = future(st);
  if (fut.length) {
    const tail = fut[fut.length - 1].dueDate;
    const monthEnd = C.isMonthEnd(fut[0].dueDate);
    return Array.from({ length: n }, (_, k) => (k < fut.length ? fut[k].dueDate : C.addMonths(tail, k - fut.length + 1, monthEnd)));
  }
  const lastDue = st.demands[st.demands.length - 1]?.dueDate ?? st.lastInterestDate ?? asOf;
  let first = C.addMonths(lastDue, 1, C.isMonthEnd(lastDue));
  while (first <= asOf) first = C.addMonths(first, 1, C.isMonthEnd(lastDue));
  return monthly(first, n, C.isMonthEnd(lastDue));
}

function num(v: unknown): number {
  if (v === null || v === undefined || v === '') return NaN;
  return Number(v);
}

function rateIn(loan: StoredLoan, v: unknown, field = 'newRatePercent'): number {
  const r = num(v);
  const [min, max] = [num(loan.product.minRate), num(loan.product.maxRate)];
  if (!Number.isFinite(r)) throw bad('Enter the new rate', [{ field, message: 'Required' }]);
  if (r < min || r > max) throw bad(`Rate must be within the product band ${min}% to ${max}%`, [{ field, message: 'Outside the product band' }]);
  return r;
}

function instalments(v: unknown, field = 'remainingInstalments'): number {
  const n = Number(v);
  if (!Number.isInteger(n) || n < 1 || n > 480) throw bad('Instalments must be a whole number from 1 to 480', [{ field, message: 'Invalid' }]);
  return n;
}

/** Instalments needed to repay `bal` at `emi`; 409 when the EMI does not cover the monthly interest. */
function solveTenure(bal: number, ratePct: number, emi: number): number {
  const i = ratePct / 1200;
  if (i === 0) return Math.ceil(bal / emi);
  if (emi <= Math.ceil(bal * i)) {
    throw conflict('EMI does not cover the interest', `An EMI of ${C.fromPaise(emi)} does not cover the monthly interest of about ${C.fromPaise(C.roundRupee(bal * i))}`);
  }
  return Math.ceil(-Math.log(1 - (bal * i) / emi) / Math.log(1 + i) - 1e-9);
}

export interface Figures {
  rate: number;
  emi: number;
  remaining: number;
  nextDue: string | null;
  maturity: string | null;
  interest: number;
}

export function figuresOf(st: LoanState): Figures {
  const fut = future(st);
  return {
    rate: st.rate,
    emi: st.emi ?? fut[0]?.instalment ?? 0,
    remaining: fut.length,
    nextDue: fut[0]?.dueDate ?? null,
    maturity: fut[fut.length - 1]?.dueDate ?? null,
    interest: fut.reduce((s, r) => s + r.interest, 0),
  };
}

export function toRow(r: C.Row): ScheduleRow {
  return {
    instalmentNo: r.no,
    dueDate: r.dueDate,
    days: r.days,
    openingBalance: C.fromPaise(r.opening),
    interest: C.fromPaise(r.interest),
    principal: C.fromPaise(r.principal),
    instalment: C.fromPaise(r.instalment),
    closingBalance: C.fromPaise(r.closing),
  };
}

function requireEmiLoan(loan: StoredLoan, st: LoanState) {
  if (loan.product.repaymentMethod !== 'EQUATED') throw bad('Only EMI (equated) loans can be amended or restructured in this console', [{ field: 'loan', message: 'Not an EMI loan' }]);
  if (future(st).length === 0 && unpaidPrincipal(st) === 0) throw conflict('Nothing left to reschedule', 'Every instalment has been demanded and paid');
}

/**
 * Applies an amendment to `st` (mutates). Returns the broken-period interest of a due-day change.
 * Throws 422/409 problems for invalid requests.
 */
/** `anyRate`: a day-end reset follows the benchmark even outside the product band (D-14). */
export function amendState(loan: StoredLoan, st: LoanState, req: AmendmentRequest, asOf: string, anyRate = false): number {
  requireEmiLoan(loan, st);
  const fut = future(st);
  if (fut.length === 0) throw conflict('Nothing left to amend', 'Every instalment has already been demanded');
  const bal = futurePrincipal(st);
  const before = figuresOf(st);
  let rate = st.rate;
  let n = fut.length;
  let emi = before.emi;
  let dates: string[] | null = null;
  let broken = 0;
  switch (req?.kind) {
    case 'RATE_CHANGE':
    case 'SWITCH_TO_FIXED': {
      rate = anyRate ? num(req.newRatePercent) : rateIn(loan, req.newRatePercent);
      const opt = req.rateOption;
      if (opt === 'KEEP_TENURE_CHANGE_EMI') emi = C.pmt(bal, rate, n);
      else if (opt === 'KEEP_EMI_CHANGE_TENURE') n = solveTenure(bal, rate, emi);
      else if (opt === 'CHANGE_BOTH') {
        if (req.remainingInstalments !== undefined && req.remainingInstalments !== null) {
          n = instalments(req.remainingInstalments);
          emi = C.pmt(bal, rate, n);
        } else if (num(req.newEmi) > 0) {
          emi = C.roundRupee(C.toPaise(String(req.newEmi)));
          n = solveTenure(bal, rate, emi);
        } else throw bad('Give the new number of instalments or the new EMI', [{ field: 'remainingInstalments', message: 'Required' }]);
      } else throw bad('Choose the borrower’s option: keep EMI, keep tenure, or change both', [{ field: 'rateOption', message: 'Required' }]);
      break;
    }
    case 'TENURE_CHANGE':
      n = instalments(req.remainingInstalments);
      emi = C.pmt(bal, rate, n);
      break;
    case 'MATURITY_CHANGE': {
      // A tenure change expressed as a date: the last instalment falls due in that month, on the loan's due day.
      const target = req.newMaturityDate ?? '';
      if (!/^\d{4}-\d{2}-\d{2}$/.test(target)) throw bad('Give the new maturity date', [{ field: 'newMaturityDate', message: 'Required' }]);
      const [y1, m1] = fut[0].dueDate.split('-').map(Number);
      const [y2, m2] = target.split('-').map(Number);
      const months = (y2 - y1) * 12 + (m2 - m1) + 1;
      if (months < 1) throw bad('The new maturity must not be before the next instalment', [{ field: 'newMaturityDate', message: 'Too early' }]);
      n = instalments(months, 'newMaturityDate');
      if (n === fut.length) throw bad('The loan already matures in that month', [{ field: 'newMaturityDate', message: 'No change' }]);
      emi = C.pmt(bal, rate, n);
      break;
    }
    case 'EMI_CHANGE': {
      if (!(num(req.newEmi) > 0)) throw bad('Enter the new EMI', [{ field: 'newEmi', message: 'Required' }]);
      emi = C.roundRupee(C.toPaise(String(req.newEmi)));
      n = solveTenure(bal, rate, emi);
      break;
    }
    case 'DUE_DAY_CHANGE': {
      const day = Number(req.newDueDay);
      if (!Number.isInteger(day) || day < 1 || day > 31) throw bad('Due day must be 1 to 31 (31 = month end)', [{ field: 'newDueDay', message: 'Invalid' }]);
      let first = withDay(fut[0].dueDate, day);
      const from = st.lastInterestDate ?? asOf;
      if (first <= asOf || first <= from) first = withDay(C.addMonths(`${first.slice(0, 8)}01`, 1), day);
      if (first === fut[0].dueDate) throw bad(`Instalments already fall due on day ${day}`, [{ field: 'newDueDay', message: 'No change' }]);
      dates = monthly(first, n, day >= 31);
      const oldDays = C.daysBetween(from, fut[0].dueDate);
      const newDays = C.daysBetween(from, first);
      broken = Math.max(0, C.interestFor(bal, rate, newDays) - C.interestFor(bal, rate, oldDays));
      break;
    }
    default:
      throw bad('Kind must be RATE_CHANGE, TENURE_CHANGE, EMI_CHANGE, DUE_DAY_CHANGE or MATURITY_CHANGE', [{ field: 'kind', message: 'Invalid' }]);
  }
  if (n > 480) throw bad(`That needs ${n} more instalments; at most 480 are allowed`, [{ field: 'remainingInstalments', message: 'Too long' }]);
  if (n > fut.length && st.raised + n > loan.product.maxTenorMonths) {
    throw bad(`The tenure would be ${st.raised + n} months, beyond the product maximum of ${loan.product.maxTenorMonths}`, [{ field: 'remainingInstalments', message: 'Beyond the product maximum tenure' }]);
  }
  const arrears = unpaidPrincipal(st) + unpaidInterest(st);
  if (arrears > 0 && (n > fut.length || emi < before.emi)) {
    throw conflict('Borrower is in arrears', `Extending the tenure or lowering the EMI while ${C.fromPaise(arrears)} is overdue is a restructure, not an amendment`);
  }
  const rows = C.buildRows({ balance: bal, ratePct: rate, from: st.lastInterestDate ?? asOf, dueDates: dates ?? continueDates(st, n, asOf), method: 'EQUATED', emi, startNo: st.raised + 1 });
  st.rows = [...st.rows.slice(0, st.raised), ...rows];
  st.rate = rate;
  st.emi = emi;
  return broken;
}

/** Applies a restructure to `st` (mutates): overdue principal (and optionally interest) goes into a new schedule. */
export function restructureState(loan: StoredLoan, st: LoanState, t: RestructureTerms, asOf: string) {
  requireEmiLoan(loan, st);
  const n = instalments(t?.remainingInstalments);
  const m = t.principalMoratoriumMonths ?? 0;
  if (!Number.isInteger(m) || m < 0 || m >= n) throw bad('Principal moratorium must be 0 or more months and shorter than the new tenure', [{ field: 'principalMoratoriumMonths', message: 'Invalid' }]);
  if (t.overdueInterest !== 'CAPITALISE' && t.overdueInterest !== 'KEEP_AS_ARREARS') throw bad('Choose how overdue interest is treated', [{ field: 'overdueInterest', message: 'Required' }]);
  const rate = t.newRatePercent === null || t.newRatePercent === undefined || t.newRatePercent === '' ? st.rate : rateIn(loan, t.newRatePercent);
  const dates = continueDates(st, n, asOf);
  const overdueP = unpaidPrincipal(st);
  let capitalised = 0;
  for (const d of st.demands) {
    const up = d.principalDue - d.principalPaid;
    if (up > 0) {
      d.principalRescheduled += up;
      d.principalDue = d.principalPaid;
    }
    if (t.overdueInterest === 'CAPITALISE') {
      const ui = d.interestDue - d.interestPaid;
      if (ui > 0) {
        d.interestCapitalised += ui;
        d.interestDue = d.interestPaid;
        capitalised += ui;
      }
    }
  }
  const bal = futurePrincipal(st) + overdueP + capitalised;
  const emi = C.pmt(bal, rate, n - m);
  const rows = C.buildRows({ balance: bal, ratePct: rate, from: st.lastInterestDate ?? asOf, dueDates: dates, method: 'EQUATED', moratoriumRows: m, emi, startNo: st.raised + 1 });
  st.rows = [...st.rows.slice(0, st.raised), ...rows];
  st.rate = rate;
  st.emi = emi;
  st.capitalised += capitalised;
  st.restructuredOn = asOf;
  st.restructureCount += 1;
  // RBI: the specified period runs at least a year from the first payment under the new schedule.
  st.upgradeNotBefore = C.addMonths(rows[Math.min(m, rows.length - 1)].dueDate, 12);
  // A standard account is downgraded to sub-standard; an NPA keeps its class (and its NPA date).
  if (!st.npaSince) st.npaSince = asOf;
}

/** NPV at the contract rate (monthly periods) of what is due now plus the future instalments. */
function npv(dueNow: number, instalmentsFromNextMonth: number[], ratePct: number): number {
  const i = ratePct / 1200;
  return Math.round(instalmentsFromNextMonth.reduce((s, x, k) => s + x / Math.pow(1 + i, k + 1), dueNow));
}

export function simulateOption(loan: StoredLoan, current: LoanState, t: RestructureTerms, asOf: string): RestructureOption {
  const st = cloneState(current);
  const before = figuresOf(st);
  const classBefore = current.assetClass;
  const overdueP = unpaidPrincipal(st);
  const overdueI = unpaidInterest(st);
  const principalBefore = futurePrincipal(st) + overdueP;
  restructureState(loan, st, t, asOf);
  const after = figuresOf(st);
  const capitalised = t.overdueInterest === 'CAPITALISE' ? overdueI : 0;
  const kept = overdueI - capitalised;
  const npvBefore = npv(overdueP + overdueI, future(current).map((r) => r.instalment), current.rate);
  const npvAfter = npv(kept, future(st).map((r) => r.instalment), current.rate);
  return {
    newRatePercent: t.newRatePercent === null || t.newRatePercent === undefined || t.newRatePercent === '' ? null : C.pct(t.newRatePercent),
    remainingInstalments: t.remainingInstalments,
    principalMoratoriumMonths: t.principalMoratoriumMonths ?? 0,
    overdueInterestTreatment: t.overdueInterest,
    classBefore,
    classAfter: NPA.includes(classBefore) ? classBefore : 'SUBSTANDARD',
    principalBefore: C.fromPaise(principalBefore),
    overduePrincipalRescheduled: C.fromPaise(overdueP),
    overdueInterest: C.fromPaise(overdueI),
    interestCapitalised: C.fromPaise(capitalised),
    arrearsKept: C.fromPaise(kept),
    principalAfter: C.fromPaise(principalBefore + capitalised),
    rateBefore: C.pct(before.rate),
    rateAfter: C.pct(after.rate),
    emiBefore: C.fromPaise(before.emi),
    emiAfter: C.fromPaise(after.emi),
    remainingBefore: before.remaining,
    remainingAfter: after.remaining,
    maturityBefore: before.maturity ?? undefined,
    maturityAfter: after.maturity ?? undefined,
    interestBefore: C.fromPaise(before.interest),
    interestAfter: C.fromPaise(after.interest),
    npvBefore: C.fromPaise(npvBefore),
    npvAfter: C.fromPaise(npvAfter),
    npvLoss: C.fromPaise(npvBefore - npvAfter),
    upgradeNotBefore: st.upgradeNotBefore ?? undefined,
    schedule: future(st).map(toRow),
  };
}
