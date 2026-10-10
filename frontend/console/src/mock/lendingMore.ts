/**
 * Lending completion (P2-6) in the mock API: product templates and draft-product preview, tranches, simulations
 * (run on a copy; nothing is stored), sanction change and the manual NPA override. Tranche draws, sanction changes and
 * overrides are replayed like other loan transactions but can never be reversed.
 */
import type { AssetClass, InterestTable, InterestTableInput, DisbursementSimulation, LoanAmendment, LoanProductTemplate, LoanTranches, SanctionChangePreview, TransactionSimulation } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { addDays, ISO_DATE } from '../lib/dates';
import { isMoney } from '../lib/money';
import { toRow } from './amendCalc';
import { appendAudit, uuid, type ApprovalPayload, type MockDb, type StoredLoan } from './db';
import { addEvent, arrearsOf, checkDisbursement, checkTrancheMaturity, clone, disburse, futurePrincipal, previewProduct, principalOutstanding, quoteFrom, refreshLoan, replay, unpaidCharges } from './lending';
import * as C from './lendingCalc';
import { bad, conflict, notFound } from './problems';

type Result = { status: number; body: unknown };
interface Ctx {
  user: DemoUser;
  body: unknown;
  params: Record<string, string>;
}
export interface LendingMoreRouter {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (user: DemoUser, entityType: string, action: string, payload: ApprovalPayload, proposed: Record<string, unknown>, current?: Record<string, unknown> | null, entityId?: string | null, amount?: string | null) => Result;
  nowIso: () => string;
}

const ok = (body: unknown): Result => ({ status: 200, body });
const inr = (p: number) => `₹${C.fromPaise(p)}`;
const NPA: AssetClass[] = ['SUBSTANDARD', 'DOUBTFUL1', 'DOUBTFUL2', 'DOUBTFUL3', 'LOSS'];
const ORDER: AssetClass[] = ['STANDARD', 'SMA0', 'SMA1', 'SMA2', ...NPA];

// ------------------------------------------------------------------ templates
const base = { minRate: '10', maxRate: '24', rateType: 'FIXED', dayCount: 'ACTUAL_365', rounding: 'RUPEE_HALF_UP', penalChargeRate: '24', coolingOffDays: 3, secured: false, frequency: 'MONTHLY', interestBasis: 'DAILY_REDUCING', bpiMode: 'NONE', fees: [] };
const pf = (percent: string) => ({ code: 'PF', name: 'Processing fee', event: 'DISBURSEMENT', calcType: 'PERCENT', percent, gstRate: '18', taxTreatment: 'EXCLUSIVE', deductFromDisbursal: true });
export const PRODUCT_TEMPLATES: LoanProductTemplate[] = [
  { code: 'PERSONAL_EMI', name: 'Personal loan (EMI)', category: 'RETAIL', description: 'Unsecured monthly EMI loan with a processing fee deducted at disbursal.', product: { ...base, name: 'Personal loan', repaymentMethod: 'EQUATED', minAmount: '25000', maxAmount: '500000', minTenorMonths: 6, maxTenorMonths: 60, minRate: '12', maxMoratoriumMonths: 0, fees: [pf('1')] } },
  { code: 'BUSINESS_EMI', name: 'Business loan (EMI)', category: 'BUSINESS', description: 'Working-capital term loan repaid in monthly EMIs, up to three months of moratorium.', product: { ...base, name: 'Business loan', repaymentMethod: 'EQUATED', minAmount: '100000', maxAmount: '5000000', minTenorMonths: 12, maxTenorMonths: 84, maxMoratoriumMonths: 3, fees: [pf('1.5')] } },
  { code: 'BUSINESS_STEP_UP', name: 'Business loan (step-up EMI)', category: 'BUSINESS', description: 'EMIs that rise 10% every 12 instalments, for a business whose cash flow grows.', product: { ...base, name: 'Step-up business loan', repaymentMethod: 'STEP_EQUATED', stepPercent: '10', stepEvery: 12, minAmount: '100000', maxAmount: '5000000', minTenorMonths: 24, maxTenorMonths: 84 } },
  { code: 'GOLD_BULLET', name: 'Gold loan (bullet)', category: 'GOLD', description: 'Secured loan: principal and all interest at maturity.', product: { ...base, name: 'Gold loan', repaymentMethod: 'BULLET_TOTAL_INTEREST', secured: true, minAmount: '5000', maxAmount: '2000000', minTenorMonths: 3, maxTenorMonths: 12, minRate: '9', maxRate: '18' } },
  { code: 'MICRO_WEEKLY', name: 'Micro-loan (weekly)', category: 'MICRO', description: 'Small-ticket loan repaid in equal weekly instalments; the tenor counts weeks.', product: { ...base, name: 'Weekly micro-loan', repaymentMethod: 'EQUATED', frequency: 'WEEKLY', minAmount: '5000', maxAmount: '100000', minTenorMonths: 12, maxTenorMonths: 104, minRate: '18', maxRate: '26' } },
  { code: 'CONSUMER_FLAT', name: 'Consumer durable loan (flat rate)', category: 'CONSUMER', description: 'Flat-rate EMI loan; the KFS discloses the equivalent reducing rate and the APR.', product: { ...base, name: 'Consumer durable loan', repaymentMethod: 'EQUATED', interestBasis: 'FLAT', minAmount: '5000', maxAmount: '300000', minTenorMonths: 3, maxTenorMonths: 36, minRate: '6', maxRate: '18' } },
  { code: 'HOME_FLOATING_TRANCHES', name: 'Home loan (floating, in tranches)', category: 'HOUSING', description: 'Floating rate on a benchmark plus spread, drawn in tranches with pre-EMI interest until fully drawn; top-up allowed.', product: { ...base, name: 'Home loan', repaymentMethod: 'EQUATED', rateType: 'FLOATING', benchmarkCode: 'REPO', spread: '2.75', resetFrequencyMonths: 3, multipleDisbursements: true, preEmi: true, topUpAllowed: true, secured: true, minAmount: '500000', maxAmount: '20000000', minTenorMonths: 60, maxTenorMonths: 360, minRate: '7', maxRate: '14' } },
  { code: 'AGRI_STRUCTURED', name: 'Seasonal loan (structured)', category: 'AGRI', description: 'Principal assigned to harvest dates on each loan, with the interest accrued.', product: { ...base, name: 'Seasonal crop loan', repaymentMethod: 'STRUCTURED', frequency: 'HALF_YEARLY', minAmount: '25000', maxAmount: '1000000', minTenorMonths: 1, maxTenorMonths: 10, minRate: '7', maxRate: '14' } },
];

// ------------------------------------------------------------------ helpers
const shim = (businessDate: string) => ({ businessDate }) as MockDb;
const duesOf = (loan: StoredLoan) => arrearsOf(loan.state) + unpaidCharges(loan.state);
function paise(v: unknown, field: string): number {
  const s = v === null || v === undefined ? '' : String(v).trim();
  if (!isMoney(s) || Number(s) <= 0) throw bad('Amount must be a positive decimal', [{ field, message: 'Enter an amount greater than zero' }]);
  return C.toPaise(s);
}
function reasonOf(v: unknown): string {
  const r = typeof v === 'string' ? v.trim() : '';
  if (!r) throw bad('A reason is required', [{ field: 'reason', message: 'Required' }]);
  if (r.length > 500) throw bad('Reason must be at most 500 characters', [{ field: 'reason', message: 'Too long' }]);
  return r;
}
function assertActive(loan: StoredLoan) {
  const s = loan.state.status;
  if (s === 'FROZEN') throw conflict('Loan is frozen', `Loan ${loan.loanNo} is frozen; unfreeze it first`);
  if (s !== 'ACTIVE') throw conflict('Loan is not active', `Loan ${loan.loanNo} is ${s}`);
}

function history(db: MockDb, loan: StoredLoan, kind: LoanAmendment['kind'], txnId: string, before: StoredLoan['state'], parameters: Record<string, unknown>, approval: { id?: string; maker?: string }, checkers: string, reason: string, at: string) {
  const fig = (st: StoredLoan['state']) => ({ emi: C.fromPaise(st.emi ?? st.rows[st.raised]?.instalment ?? 0), n: st.rows.length - st.raised, rate: C.pct(st.rate), maturity: st.rows[st.rows.length - 1]?.dueDate ?? null, interest: C.fromPaise(st.rows.slice(st.raised).reduce((s, r) => s + r.interest, 0)) });
  const [b, a] = [fig(before), fig(loan.state)];
  loan.amendments.push({
    id: uuid(), seq: loan.amendments.length + 1, txnId, kind, parameters, emiBefore: b.emi, emiAfter: a.emi, tenureBefore: b.n, tenureAfter: a.n, rateBefore: b.rate, rateAfter: a.rate,
    maturityBefore: b.maturity, maturityAfter: a.maturity ?? undefined, interestBefore: b.interest, interestAfter: a.interest, proposedFigures: null, appliedFigures: parameters, differsFromProposal: false,
    approvalId: approval.id, madeBy: approval.maker, checkedBy: checkers, businessDate: db.businessDate, reason, reversedBy: null, createdAt: at,
  });
}

// ------------------------------------------------------------------ sanction change
function sanctionTarget(loan: StoredLoan, db: MockDb, body: unknown): { newAmount: number; topUp: boolean } {
  const b = (body ?? {}) as { newAmount?: string | number | null; cancelUndrawn?: boolean | null };
  const st = loan.state;
  const hasAmount = b.newAmount !== undefined && b.newAmount !== null && b.newAmount !== '';
  if (hasAmount === !!b.cancelUndrawn) throw bad('Give either the new sanctioned amount or cancel the undrawn amount', [{ field: 'newAmount', message: 'One of the two' }]);
  if (b.cancelUndrawn) {
    if (st.sanctioned <= st.drawn) throw conflict('Nothing is undrawn', `Loan ${loan.loanNo} is fully disbursed`);
    return { newAmount: st.drawn, topUp: false };
  }
  const newAmount = paise(b.newAmount, 'newAmount');
  if (newAmount === st.sanctioned) throw bad('The sanctioned amount is already that', [{ field: 'newAmount', message: 'No change' }]);
  if (newAmount < st.sanctioned) {
    if (newAmount < st.drawn) throw bad(`A reduction can only take away undrawn amount: ${inr(st.drawn)} is already disbursed`, [{ field: 'newAmount', message: 'Below the amount disbursed' }]);
    return { newAmount, topUp: false };
  }
  if (!loan.product.topUpAllowed) throw conflict('Top-up not allowed', `Product ${loan.product.code} does not allow a top-up in the same account`);
  if (newAmount > C.toPaise(String(loan.product.maxAmount))) throw bad(`Above the product maximum of ${inr(C.toPaise(String(loan.product.maxAmount)))}`, [{ field: 'newAmount', message: 'Above the product maximum' }]);
  if (st.assetClass !== 'STANDARD' || duesOf(loan) > 0 || st.restructuredOn) {
    throw conflict('Top-up refused', 'A top-up needs a standard account with no unpaid dues that is not under post-restructuring monitoring: new money to a borrower in arrears would be evergreening');
  }
  const customer = db.customers.find((c) => c.id === loan.customerId);
  const limit = customer?.exposureLimit;
  if (limit !== null && limit !== undefined) {
    const others = db.loans.filter((l) => l.customerId === loan.customerId && l.id !== loan.id).reduce((s, l) => s + (l.disbursedOn ? principalOutstanding(l) : l.amount), 0);
    const mine = principalOutstanding(loan) + (newAmount - st.drawn);
    if (others + mine > limit) throw conflict('Exposure limit exceeded', `The top-up would take the borrower's exposure to ${inr(others + mine)}, above the limit of ${inr(limit)}`);
  }
  return { newAmount, topUp: true };
}

function sanctionPreview(db: MockDb, loan: StoredLoan, body: unknown): SanctionChangePreview & { newAmount: number } {
  assertActive(loan);
  const { newAmount, topUp } = sanctionTarget(loan, db, body);
  const copy = clone(loan);
  addEvent(shim(db.businessDate), copy, 'preview', '', { type: 'SANCTION_CHANGE', valueDate: db.businessDate, amount: null, summary: '', data: { newAmount } });
  const st = replay(copy, db.businessDate);
  const startsEmi = !!loan.product.preEmi && loan.state.drawn < loan.state.sanctioned && st.drawn >= st.sanctioned;
  return {
    newAmount,
    asOf: db.businessDate,
    sanctionedBefore: C.fromPaise(loan.state.sanctioned),
    sanctionedAfter: C.fromPaise(newAmount),
    disbursed: C.fromPaise(st.drawn),
    undrawnAfter: C.fromPaise(newAmount - st.drawn),
    topUp,
    schedule: st.rows.slice(st.raised).map(toRow),
    note: topUp
      ? 'No money moves on approval: the extra amount is then paid out through a disbursement (tranche). The schedule changes when it is drawn.'
      : startsEmi
        ? 'The loan becomes fully drawn, so the EMIs start: the schedule below replaces the interest-only instalments.'
        : 'The undrawn amount is reduced; the schedule of the amount already drawn does not change.',
  };
}

// ------------------------------------------------------------------ approvals
export function applyLendingMoreApproval(db: MockDb, p: ApprovalPayload, approval: { id?: string; entityId?: string | null; maker?: string; appliedRef?: string | null }, checker: string, at: string, prior: string[] = []): boolean {
  if (p.kind === 'BENCHMARK') {
    if (db.benchmarks.some((b) => b.code === p.benchmark.code)) throw conflict('Benchmark exists', `Benchmark ${p.benchmark.code} already exists`);
    db.benchmarks.push({ ...p.benchmark, rates: [] });
    db.benchmarks.sort((a, b) => a.code.localeCompare(b.code));
    approval.entityId = p.benchmark.code;
    approval.appliedRef = p.benchmark.code;
    return true;
  }
  if (p.kind === 'INTEREST_TABLE') {
    const i = db.interestTables.findIndex((t) => t.code === p.table.code);
    if (i >= 0) {
      const used = productsUsing(db, p.table.code);
      if (used.length && (db.interestTables[i].mode === 'SPREAD') !== (p.table.mode === 'SPREAD')) throw bad(`table ${p.table.code} is used by product(s) ${used.join(', ')}: it cannot change to or from SPREAD`);
      db.interestTables[i] = p.table;
    } else {
      db.interestTables.push(p.table);
      db.interestTables.sort((a, b) => a.code.localeCompare(b.code));
    }
    approval.entityId = p.table.code;
    approval.appliedRef = p.table.code;
    return true;
  }
  if (p.kind === 'BENCHMARK_RATE') {
    const b = db.benchmarks.find((x) => x.code === p.code);
    if (!b) throw notFound('Benchmark');
    // re-checked at approval: another rate may have been approved since the proposal
    if (b.rates[0] && p.effectiveFrom <= b.rates[0].effectiveFrom) throw conflict('Rate history is not rewritten', `A rate of ${p.code} is already recorded from ${b.rates[0].effectiveFrom}; a new rate takes effect on a later date`);
    b.rates.unshift({ effectiveFrom: p.effectiveFrom, rate: Number(p.rate).toFixed(4), recordedBy: approval.maker ?? checker, recordedAt: at, approvalId: approval.id ?? null });
    approval.appliedRef = `${p.code}@${p.effectiveFrom}`;
    return true;
  }
  if (p.kind !== 'LOAN_SANCTION_CHANGE' && p.kind !== 'LOAN_NPA_OVERRIDE') return false;
  const loan = db.loans.find((l) => l.id === p.loanId);
  if (!loan) throw notFound('Loan');
  const before = clone(loan.state);
  const checkers = [...prior, checker].join(', ');
  if (p.kind === 'LOAN_SANCTION_CHANGE') {
    // Re-checked on the loan as it is now.
    sanctionPreview(db, loan, { newAmount: C.fromPaise(p.newAmount) });
    const ev = addEvent(db, loan, checker, at, { type: 'SANCTION_CHANGE', valueDate: db.businessDate, amount: null, summary: `Sanctioned amount ${inr(before.sanctioned)} → ${inr(p.newAmount)} — ${p.reason}`, data: { newAmount: p.newAmount, reason: p.reason } });
    refreshLoan(db, loan);
    history(db, loan, 'SANCTION_CHANGE', ev.id, before, { sanctionedBefore: C.fromPaise(before.sanctioned), sanctionedAfter: C.fromPaise(p.newAmount) }, approval, checkers, p.reason, at);
    appendAudit(db, at, checker, 'LOAN_SANCTION_CHANGED', 'LOAN', loan.id, { from: C.fromPaise(before.sanctioned), to: C.fromPaise(p.newAmount) });
  } else if (p.release) {
    if (arrearsOf(loan.state) + unpaidCharges(loan.state) > 0) throw conflict('Account has unpaid dues', 'An NPA is upgraded only when all arrears are cleared; the override cannot be released yet');
    const ev = addEvent(db, loan, checker, at, { type: 'NPA_RELEASE', valueDate: db.businessDate, amount: null, summary: `Manual NPA override released — ${p.reason}`, data: { reason: p.reason } });
    refreshLoan(db, loan);
    history(db, loan, 'NPA_OVERRIDE', ev.id, before, { action: 'RELEASE', classBefore: before.overrideClass }, approval, checkers, p.reason, at);
    appendAudit(db, at, checker, 'LOAN_NPA_OVERRIDE_RELEASED', 'LOAN', loan.id, null);
  } else {
    const ev = addEvent(db, loan, checker, at, { type: 'NPA_OVERRIDE', valueDate: db.businessDate, amount: null, summary: `Manual NPA mark: ${p.assetClass} until ${p.until} — ${p.reason}`, data: { overrideClass: p.assetClass, until: p.until, reason: p.reason } });
    refreshLoan(db, loan);
    history(db, loan, 'NPA_OVERRIDE', ev.id, before, { action: 'OVERRIDE', assetClass: p.assetClass, until: p.until, classBefore: before.assetClass }, approval, checkers, p.reason, at);
    appendAudit(db, at, checker, 'LOAN_NPA_OVERRIDE', 'LOAN', loan.id, { assetClass: p.assetClass, until: p.until });
  }
  approval.entityId = loan.id;
  approval.appliedRef = `${loan.loanNo} #${loan.amendments.length}`;
  return true;
}

const productsUsing = (db: MockDb, code: string) => db.loanProducts.filter((x) => x.interestTableCode === code).map((x) => x.code).sort();

/** The same rules as InterestTableService.normalise on the backend. */
export function normaliseInterestTable(b: Partial<InterestTableInput>, businessDate: string): InterestTable {
  const fail = (m: string, field = 'rows'): never => { throw bad(m, [{ field, message: m }]); };
  if (!/^[A-Z0-9_]{2,20}$/.test(b.code ?? '')) fail('code is 2 to 20 capital letters, digits or underscores', 'code');
  if (!b.name?.trim() || b.name.length > 100) fail('name is required (at most 100 characters)', 'name');
  const mode = String(b.mode ?? '').toUpperCase() === 'FIXED' ? 'ABSOLUTE' : String(b.mode ?? '').toUpperCase();
  if (!['ABSOLUTE', 'ADDITIVE', 'SPREAD'].includes(mode)) fail('mode is FIXED (ABSOLUTE), ADDITIVE or SPREAD', 'mode');
  const base = Number(b.baseRate ?? 0);
  if (mode !== 'ADDITIVE' && base !== 0) fail(`baseRate is used by ADDITIVE tables only; leave it out (0) for ${mode}`, 'baseRate');
  const rows = b.rows ?? [];
  if (!rows.length) fail('at least one row (amount band, tenor band and rate) is required');
  if (rows.length > 200) fail('at most 200 rows');
  const cap = mode === 'SPREAD' ? 30 : 60;
  const out = rows.map((r, i) => {
    const at = `row ${i + 1}`;
    const min = Number(r.minAmount), max = Number(r.maxAmount), rate = Number(r.rate);
    if (![r.minAmount, r.maxAmount, r.rate].every((v) => v !== undefined && v !== null && v !== '') || r.minTenorMonths == null || r.maxTenorMonths == null) return fail(`${at}: minAmount, maxAmount, minTenorMonths, maxTenorMonths and rate are required`);
    if (!(min >= 0) || !(max >= min) || max > 1e12) fail(`${at}: amounts are from 0 and maxAmount is not below minAmount`);
    if (!Number.isInteger(r.minTenorMonths) || r.minTenorMonths < 1 || r.maxTenorMonths < r.minTenorMonths || r.maxTenorMonths > 600) fail(`${at}: tenor is 1 to 600 months and maxTenorMonths is not below minTenorMonths`);
    if (!(rate >= 0) || rate > cap || !/^\d+(\.\d{1,4})?$/.test(String(r.rate))) fail(`${at}: rate is a percentage from 0 to ${cap} with at most four decimals`);
    return { minAmount: min.toFixed(2), maxAmount: max.toFixed(2), minTenorMonths: r.minTenorMonths, maxTenorMonths: r.maxTenorMonths, rate: rate.toFixed(4) };
  });
  out.sort((a, b) => Number(a.minAmount) - Number(b.minAmount) || a.minTenorMonths - b.minTenorMonths);
  for (let i = 0; i < out.length; i++) {
    for (let j = i + 1; j < out.length; j++) {
      const a = out[i], c = out[j];
      if (Number(a.minAmount) <= Number(c.maxAmount) && Number(c.minAmount) <= Number(a.maxAmount) && a.minTenorMonths <= c.maxTenorMonths && c.minTenorMonths <= a.maxTenorMonths) {
        fail(`bands overlap: ${a.minAmount}-${a.maxAmount} / ${a.minTenorMonths}-${a.maxTenorMonths} months and ${c.minAmount}-${c.maxAmount} / ${c.minTenorMonths}-${c.maxTenorMonths} months (bands include both ends)`);
      }
    }
  }
  return { code: b.code!, name: b.name!.trim(), mode: mode as InterestTable['mode'], baseRate: base.toString(), effectiveFrom: b.effectiveFrom || businessDate, rows: out, usedByProducts: [] };
}

// ------------------------------------------------------------------ routes
export function registerLendingMoreRoutes(db: MockDb, r: LendingMoreRouter) {
  const { on, require, propose } = r;
  const findLoan = (id: string) => {
    const loan = db.loans.find((l) => l.id === id || l.loanNo === id);
    if (!loan) throw notFound('Loan');
    return loan;
  };
  const pendingOf = (loan: StoredLoan, kinds: ApprovalPayload['kind'][]) => db.approvals.find((s) => s.approval.status === 'PENDING' && kinds.includes(s.payload.kind) && 'loanId' in s.payload && s.payload.loanId === loan.id);

  on('GET', '/api/v1/benchmarks', ({ user }) => {
    require(user, P.benchmarkView);
    return ok(db.benchmarks.map((b) => {
      const current = b.rates.find((x) => x.effectiveFrom <= db.businessDate);
      return { ...b, currentRate: current?.rate ?? null, currentFrom: current?.effectiveFrom ?? null };
    }));
  });
  on('GET', '/api/v1/interest-tables', ({ user }) => {
    require(user, P.productView);
    return ok(db.interestTables.map((t) => ({ ...clone(t), usedByProducts: productsUsing(db, t.code) })));
  });
  on('POST', '/api/v1/interest-tables', ({ user, body }) => {
    require(user, P.productPropose);
    const table = normaliseInterestTable((body ?? {}) as Partial<InterestTableInput>, db.businessDate);
    const existing = db.interestTables.find((t) => t.code === table.code);
    const used = productsUsing(db, table.code);
    if (existing && used.length && (existing.mode === 'SPREAD') !== (table.mode === 'SPREAD')) throw bad(`table ${table.code} is used by product(s) ${used.join(', ')}: a table cannot change to or from SPREAD while a product uses it`);
    return propose(user, 'INTEREST_TABLE', existing ? 'UPDATE' : 'CREATE', { kind: 'INTEREST_TABLE', table }, { ...table }, existing ? { ...existing } : null, table.code);
  });
  on('POST', '/api/v1/benchmarks', ({ user, body }) => {
    require(user, P.benchmarkPropose);
    const b = (body ?? {}) as { code?: string; name?: string; source?: string; external?: boolean };
    const errors: Array<{ field: string; message: string }> = [];
    if (!/^[A-Z0-9_]{2,20}$/.test(b.code ?? '')) errors.push({ field: 'code', message: 'Code is 2 to 20 capital letters, digits or underscores' });
    if (!b.name?.trim() || b.name.length > 100) errors.push({ field: 'name', message: 'Name is required (at most 100 characters)' });
    if (!b.source?.trim() || b.source.length > 100) errors.push({ field: 'source', message: 'Source is required: who publishes the benchmark' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (db.benchmarks.some((x) => x.code === b.code)) throw conflict('Benchmark exists', `Benchmark ${b.code} already exists`);
    const benchmark = { code: b.code!, name: b.name!.trim(), source: b.source!.trim(), external: b.external ?? true };
    return propose(user, 'BENCHMARK', 'CREATE', { kind: 'BENCHMARK', benchmark }, { ...benchmark }, null, benchmark.code);
  });
  on('POST', '/api/v1/benchmarks/{code}/rates', ({ user, params, body }) => {
    require(user, P.benchmarkPropose);
    const b = db.benchmarks.find((x) => x.code === params.code);
    if (!b) throw notFound('Benchmark');
    const r = (body ?? {}) as { rate?: string | number; effectiveFrom?: string };
    const rate = String(r.rate ?? '');
    const errors: Array<{ field: string; message: string }> = [];
    if (!/^\d{1,3}(\.\d{1,4})?$/.test(rate) || Number(rate) > 100) errors.push({ field: 'rate', message: 'Rate is a percentage from 0 to 100 with at most four decimals' });
    if (!ISO_DATE.test(r.effectiveFrom ?? '')) errors.push({ field: 'effectiveFrom', message: 'Effective from is required' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    const last = b.rates[0] ?? null;
    if (last && r.effectiveFrom! <= last.effectiveFrom) throw conflict('Rate history is not rewritten', `A rate of ${b.code} is already recorded from ${last.effectiveFrom}; a new rate takes effect on a later date`);
    return propose(user, 'BENCHMARK_RATE', 'CREATE', { kind: 'BENCHMARK_RATE', code: b.code, rate, effectiveFrom: r.effectiveFrom! }, { code: b.code, rate, effectiveFrom: r.effectiveFrom },
      last ? { effectiveFrom: last.effectiveFrom, rate: last.rate } : null, `${b.code}@${r.effectiveFrom}`);
  });

  on('GET', '/api/v1/loan-product-templates', ({ user }) => {
    require(user, P.productView);
    return ok(PRODUCT_TEMPLATES);
  });
  on('POST', '/api/v1/loan-products/preview', ({ user, body }) => {
    if (!user.permissions.includes(P.productPropose)) require(user, P.productView);
    if (!body || typeof body !== 'object' || !(body as { product?: unknown }).product) throw bad('A draft product is required', [{ field: 'product', message: 'Required' }]);
    return ok(previewProduct(db, body as Record<string, unknown>));
  });

  on('GET', '/api/v1/loans/{id}/tranches', ({ user, params }) => {
    require(user, P.loanView);
    const loan = findLoan(params.id);
    const st = loan.state;
    const body: LoanTranches = {
      sanctionedAmount: C.fromPaise(st.sanctioned),
      disbursedAmount: C.fromPaise(st.drawn),
      undrawnAmount: C.fromPaise(Math.max(0, st.sanctioned - st.drawn)),
      multipleDisbursements: !!loan.product.multipleDisbursements,
      preEmi: !!loan.product.preEmi,
      tranches: loan.events.filter((e) => e.type === 'DISBURSEMENT' && !e.reversedBy).sort((a, b) => a.seq - b.seq).map((e, i) => ({
        trancheNo: i + 1, txnId: e.id, businessDate: e.businessDate, amount: C.fromPaise(e.amount ?? 0), feesDeducted: C.fromPaise(e.data.feesDeducted ?? 0), interestDeducted: '0.00',
        netDisbursed: C.fromPaise(e.data.netDisbursed ?? e.amount ?? 0), maturityDate: e.data.maturityDate ?? null, createdBy: e.createdBy,
      })),
    };
    return ok(body);
  });

  on('POST', '/api/v1/loans/{id}/simulations/disbursement', ({ user, params, body }) => {
    require(user, P.loanView);
    const loan = findLoan(params.id);
    const raw = (body as { amount?: string | number | null } | null)?.amount;
    const amount = checkDisbursement(loan, raw === undefined || raw === null || raw === '' ? null : paise(raw, 'amount'));
    const maturity = checkTrancheMaturity(db, loan, (body as { maturityDate?: string | null } | null)?.maturityDate);
    const copy = clone(loan);
    const ev = disburse(shim(db.businessDate), copy, 'simulation', '', 'IMPS', amount, maturity);
    const st = copy.state;
    const first = !loan.disbursedOn;
    const fees = first ? (loan.kfs.fees ?? []) : [];
    const sim: DisbursementSimulation = {
      asOf: db.businessDate,
      trancheNo: ev.data.tranche,
      amount: C.fromPaise(amount),
      deductedFees: (ev.data.feesDeducted ?? 0) > 0 ? fees : [],
      chargedFees: (ev.data.feesDeducted ?? 0) > 0 ? [] : fees,
      interestDeducted: '0.00',
      netDisbursal: C.fromPaise(ev.data.netDisbursed ?? amount),
      disbursedAfter: C.fromPaise(st.drawn),
      undrawnAfter: C.fromPaise(st.sanctioned - st.drawn),
      fullyDrawn: st.drawn >= st.sanctioned,
      preEmi: !!loan.product.preEmi && st.drawn < st.sanctioned,
      instalmentAfter: C.fromPaise(st.emi ?? st.rows[st.raised]?.instalment ?? 0),
      schedule: st.rows.slice(st.raised).map(toRow),
    };
    return ok(sim);
  });

  on('POST', '/api/v1/loans/{id}/simulations/transaction', ({ user, params, body }) => {
    require(user, P.loanView);
    const loan = findLoan(params.id);
    assertActive(loan);
    const b = (body ?? {}) as { type?: string; amount?: string | number | null; mode?: string | null; onDate?: string | null };
    const onDate = b.onDate || db.businessDate;
    if (!ISO_DATE.test(onDate) || onDate < db.businessDate || onDate > addDays(db.businessDate, 366)) throw bad('The date must be today or up to 366 days ahead', [{ field: 'onDate', message: 'Out of range' }]);
    const copy = clone(loan);
    const s = shim(onDate);
    copy.state = replay(copy, onDate); // the day-ends until then, on the copy
    if (copy.state.closedOn) throw conflict('Loan would already be closed', `By ${onDate} the loan is closed`);
    const before = clone(copy.state);
    const sim: TransactionSimulation = { type: b.type, asOf: db.businessDate, onDate };
    const after = () => {
      const st = copy.state;
      Object.assign(sim, { duesAfter: C.fromPaise(arrearsOf(st) + unpaidCharges(st)), principalOutstandingAfter: C.fromPaise(principalOutstanding(copy)), dpdAfter: st.dpd, assetClassAfter: st.assetClass, statusAfter: st.status });
    };
    if (b.type === 'REPAYMENT') {
      const amount = paise(b.amount, 'amount');
      addEvent(s, copy, 'simulation', '', { type: 'REPAYMENT', valueDate: onDate, amount, summary: '' });
      refreshLoan(s, copy);
      const st = copy.state;
      const allocations: NonNullable<TransactionSimulation['allocations']> = [];
      st.demands.forEach((d, i) => {
        const o = before.demands[i] ?? { interestPaid: 0, principalPaid: 0 };
        if (d.interestPaid > o.interestPaid) allocations.push({ ref: `D${d.no}`, component: 'INTEREST', amount: C.fromPaise(d.interestPaid - o.interestPaid) });
        if (d.principalPaid > o.principalPaid) allocations.push({ ref: `D${d.no}`, component: 'PRINCIPAL', amount: C.fromPaise(d.principalPaid - o.principalPaid) });
      });
      st.charges.forEach((c) => {
        const o = before.charges.find((x) => x.id === c.id);
        if (c.paid > (o?.paid ?? 0)) allocations.push({ ref: c.id, component: c.kind === 'PENAL' ? 'PENAL' : 'FEE', amount: C.fromPaise(c.paid - (o?.paid ?? 0)) });
      });
      const sum = (comp: string) => C.fromPaise(allocations.filter((a) => a.component === comp).reduce((x, a) => x + C.toPaise(a.amount), 0));
      Object.assign(sim, { amount: C.fromPaise(amount), duesBefore: C.fromPaise(arrearsOf(before) + unpaidCharges(before)), allocations, principal: sum('PRINCIPAL'), interest: sum('INTEREST'), fees: sum('FEE'), penal: sum('PENAL'), advance: C.fromPaise(Math.max(0, st.advance - before.advance)) });
      after();
    } else if (b.type === 'PREPAYMENT') {
      const amount = paise(b.amount, 'amount');
      const mode = b.mode || loan.product.prepaymentMode || 'REDUCE_TENURE';
      if (mode !== 'REDUCE_EMI' && mode !== 'REDUCE_TENURE') throw bad('Mode must be REDUCE_EMI or REDUCE_TENURE', [{ field: 'mode', message: 'Invalid mode' }]);
      const dues = arrearsOf(before) + unpaidCharges(before);
      if (dues > 0) throw conflict('Overdue dues must be cleared first', `On ${onDate} ${inr(dues)} would be overdue; a part-prepayment needs a clean account`);
      if (amount >= futurePrincipal(before)) throw bad(`Amount must be less than the future principal ${inr(futurePrincipal(before))}; use pre-closure to close the loan`, [{ field: 'amount', message: 'Too large for a part-prepayment' }]);
      addEvent(s, copy, 'simulation', '', { type: 'PREPAYMENT', valueDate: onDate, amount, summary: '', data: { mode } });
      refreshLoan(s, copy);
      const rule = (loan.product.fees ?? []).find((f) => f.event === 'PART_PREPAYMENT');
      const st = copy.state;
      Object.assign(sim, {
        amount: C.fromPaise(amount), mode, feeCharged: C.fromPaise(rule ? C.computeFee(rule, amount, loan.supplierState, loan.recipientState).total : 0),
        instalmentBefore: C.fromPaise(before.emi ?? before.rows[before.raised]?.instalment ?? 0), instalmentAfter: C.fromPaise(st.emi ?? st.rows[st.raised]?.instalment ?? 0),
        remainingBefore: before.rows.length - before.raised, remainingAfter: st.rows.length - st.raised, schedule: st.rows.slice(st.raised).map(toRow),
      });
      after();
    } else if (b.type === 'PRECLOSURE') {
      const q = quoteFrom(copy, copy.state, onDate);
      Object.assign(sim, {
        principal: C.fromPaise(q.principal), overdueDues: C.fromPaise(q.overdue), accruedInterest: C.fromPaise(q.accrued), charges: C.fromPaise(q.charges), foreclosureFee: C.fromPaise(q.fc?.total ?? 0),
        advanceAdjusted: C.fromPaise(q.advance), total: C.fromPaise(q.total), amount: C.fromPaise(q.total), duesAfter: '0.00', principalOutstandingAfter: '0.00', dpdAfter: 0, assetClassAfter: 'STANDARD', statusAfter: 'CLOSED',
      });
    } else throw bad('Type must be REPAYMENT, PREPAYMENT or PRECLOSURE', [{ field: 'type', message: 'Invalid type' }]);
    return ok(sim);
  });

  on('POST', '/api/v1/loans/{id}/sanction-change/preview', ({ user, params, body }) => {
    require(user, P.loanView);
    const { newAmount: _n, ...preview } = sanctionPreview(db, findLoan(params.id), body);
    return ok(preview);
  });
  on('POST', '/api/v1/loans/{id}/sanction-change', ({ user, params, body }) => {
    require(user, P.loanAmend);
    const loan = findLoan(params.id);
    const reason = reasonOf((body as { reason?: string } | null)?.reason);
    const { newAmount, schedule: _s, ...figs } = sanctionPreview(db, loan, body);
    const pending = pendingOf(loan, ['LOAN_SANCTION_CHANGE', 'LOAN_AMENDMENT', 'LOAN_RESTRUCTURE']);
    if (pending) throw conflict('Change already pending', `Loan ${loan.loanNo} already has a change awaiting approval`, { approvalId: pending.approval.id });
    return propose(
      user, 'LOAN_SANCTION_CHANGE', figs.topUp ? 'TOP_UP' : 'REDUCE',
      { kind: 'LOAN_SANCTION_CHANGE', loanId: loan.id, newAmount, reason, figures: figs },
      { loanNo: loan.loanNo, sanctionedAmount: figs.sanctionedAfter, undrawn: figs.undrawnAfter, reason },
      { loanNo: loan.loanNo, sanctionedAmount: figs.sanctionedBefore, undrawn: C.fromPaise(loan.state.sanctioned - loan.state.drawn) },
      loan.id, figs.sanctionedAfter,
    );
  });

  on('POST', '/api/v1/loans/{id}/npa-override', ({ user, params, body }) => {
    require(user, P.loanClassify);
    const loan = findLoan(params.id);
    if (!loan.disbursedOn || loan.state.closedOn) throw conflict('Loan is not live', `Loan ${loan.loanNo} is ${loan.state.status}`);
    const b = (body ?? {}) as { assetClass?: AssetClass; until?: string; reason?: string };
    if (!b.assetClass || !NPA.includes(b.assetClass)) throw bad('Choose an NPA class', [{ field: 'assetClass', message: 'Must be an NPA class' }]);
    if (!ISO_DATE.test(b.until ?? '') || b.until! <= db.businessDate) throw bad('The expiry must be after the business date', [{ field: 'until', message: 'After the business date' }]);
    const reason = reasonOf(b.reason);
    if (ORDER.indexOf(b.assetClass) < ORDER.indexOf(loan.state.assetClass)) {
      throw conflict('An override never upgrades', `The account is ${loan.state.assetClass}; it can be held there or downgraded, not moved to ${b.assetClass}`);
    }
    if (pendingOf(loan, ['LOAN_NPA_OVERRIDE'])) throw conflict('Change already pending', `Loan ${loan.loanNo} already has an NPA override awaiting approval`);
    return propose(user, 'LOAN_NPA_OVERRIDE', 'OVERRIDE', { kind: 'LOAN_NPA_OVERRIDE', loanId: loan.id, release: false, assetClass: b.assetClass, until: b.until, reason }, { loanNo: loan.loanNo, assetClass: b.assetClass, until: b.until, reason }, { loanNo: loan.loanNo, assetClass: loan.state.assetClass }, loan.id);
  });
  on('POST', '/api/v1/loans/{id}/npa-override/release', ({ user, params, body }) => {
    require(user, P.loanClassify);
    const loan = findLoan(params.id);
    const reason = reasonOf((body as { reason?: string } | null)?.reason);
    const st = loan.state;
    if (!st.overrideClass) throw conflict('No override', `Loan ${loan.loanNo} has no manual NPA override`);
    if (st.overrideClass === 'LOSS') throw conflict('LOSS is permanent', 'A LOSS classification cannot be released');
    if (duesOf(loan) > 0) throw conflict('Account has unpaid dues', `An NPA is upgraded only when all arrears are cleared; ${inr(duesOf(loan))} is unpaid`);
    if (pendingOf(loan, ['LOAN_NPA_OVERRIDE'])) throw conflict('Change already pending', `Loan ${loan.loanNo} already has an NPA override change awaiting approval`);
    return propose(user, 'LOAN_NPA_OVERRIDE', 'RELEASE', { kind: 'LOAN_NPA_OVERRIDE', loanId: loan.id, release: true, reason }, { loanNo: loan.loanNo, override: null, reason }, { loanNo: loan.loanNo, override: st.overrideClass, until: st.overrideUntil }, loan.id);
  });
}
