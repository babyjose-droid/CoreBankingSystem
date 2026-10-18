/**
 * Mock of the life of a deposit account after opening (Phase 4, P4-4): service charges with GST invoices, closing a
 * savings or current account, closing a term deposit before or at maturity, its maturity instruction (with the
 * depositor's consent on a deposit-taking NBFC) and the maturities list. Every change needs a checker, as the tenant's
 * starting rules (tenant V34).
 *
 * There is no deposit day-end here: no minimum balance month is ever evaluated, no deposit reaches MATURED by itself
 * and no maturity notice is sent. A closure works its interest out from the deposit's periods (as the backend's
 * TermDeposit) with the premature rule of the institution, simplified; the backend is the truth.
 */
import type { DepositAccount, DepositClosure } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { fromUnits, toUnits } from '../lib/money';
import { appendAudit, uuid, type ApprovalPayload, type MockDb } from './db';
import { activeHolds, balanceOf, findAccount, move, plain, rdSchedule, refuseIfAny, text } from './depositMoney';
import { BANK_RULES, NBFC_DEPOSIT_RULES } from './depositRules';
import { addMonths, periods, rdMonths } from './deposits';
import { HttpProblem, PROBLEM_BASE } from './problems';

export interface MockCharge {
  id: string;
  accountId: string;
  code: string;
  description: string;
  businessDate: string;
  taxableValue: string;
  gstRatePercent: string;
  cgst: string;
  sgst: string;
  igst: string;
  total: string;
  invoiceNo: string;
  supplierState: string;
  placeOfSupply: string;
  status: 'ISSUED' | 'CANCELLED';
  cancelledOn: string | null;
  creditNoteNo: string | null;
  movementId: string;
  reason: string | null;
  createdBy: string;
}

type Result = { status: number; body: unknown };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
}
interface Registrar {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (user: DemoUser, entityType: string, action: string, payload: ApprovalPayload, proposed: Record<string, unknown>, current?: Record<string, unknown> | null, entityId?: string | null, amount?: string | null) => Result;
}

const ok = (body: unknown): Result => ({ status: 200, body });
const invalid = (detail: string) => new HttpProblem(422, 'Validation failed', detail, {}, PROBLEM_BASE + 'validation');
const STATE = 'KA';
const PAY_MODES = ['ACCOUNT', 'CASH', 'BANK_TRANSFER', 'GATEWAY'] as const;
const REASONS = ['DEPOSITOR_REQUEST', 'DEATH', 'EMERGENCY_EXPENSE', 'CRITICAL_ILLNESS'];
const INSTRUCTIONS = ['PAY_OUT', 'RENEW_PRINCIPAL', 'RENEW_WITH_INTEREST'];

const daysBetween = (from: string, to: string) => Math.round((Date.parse(to + 'T00:00:00Z') - Date.parse(from + 'T00:00:00Z')) / 86_400_000);
const money = (units: bigint) => plain(units);
const half = (units: bigint) => (units / 200n) * 100n;

function rule(db: MockDb, code: string): number | null {
  const rows = db.deposits.kind === 'BANK' ? BANK_RULES : db.deposits.kind === 'NBFC_DEPOSIT' ? NBFC_DEPOSIT_RULES : [];
  const r = rows.find((x) => x.code === code);
  return r ? Number(r.value) : null;
}

function productOf(db: MockDb, a: DepositAccount) {
  return db.deposits.products.find((p) => p.code === a.productCode)!;
}

/** GST on a charge in money units: CGST and SGST half each (the branch and the depositor are in one state here). */
function withGst(taxable: bigint, ratePercent: string) {
  const rate = toUnits(ratePercent);
  const gst = ((taxable * rate) / 10000n / 100n + 50n) / 100n * 100n;
  const cgst = half(gst);
  return { taxable, gst, cgst, sgst: gst - cgst, total: taxable + gst };
}

/** What the account can give: its balance less liens, nothing when frozen for debits. */
function available(db: MockDb, a: DepositAccount): bigint {
  const holds = activeHolds(db, a.id!);
  if (holds.some((h) => h.kind === 'DEBIT_FREEZE' || h.kind === 'TOTAL_FREEZE')) return 0n;
  const liens = holds.filter((h) => h.kind === 'LIEN').reduce((s, h) => s + toUnits(h.amount ?? '0'), 0n);
  return balanceOf(db, a.id!) - liens;
}

function levy(db: MockDb, a: DepositAccount, code: string, description: string, taxable: bigint, reason: string | null, maker: string, approvalId: string | null): MockCharge {
  const rate = productOf(db, a).charges?.gstRatePercent ?? '18';
  const g = withGst(taxable, rate);
  const m = move(db, a, 'CHARGE', 'DEBIT', fromUnits(g.total, 4), uuid(), { narration: `${description} (with GST)` }, maker, approvalId);
  const c: MockCharge = {
    id: uuid(), accountId: a.id!, code, description, businessDate: db.businessDate, taxableValue: money(g.taxable), gstRatePercent: rate,
    cgst: money(g.cgst), sgst: money(g.sgst), igst: '0', total: money(g.total), invoiceNo: `GST/DEP/${String(db.deposits.charges.length + 1).padStart(5, '0')}`,
    supplierState: STATE, placeOfSupply: STATE, status: 'ISSUED', cancelledOn: null, creditNoteNo: null, movementId: m.id!, reason, createdBy: maker,
  };
  db.deposits.charges.unshift(c);
  return c;
}

// ---------- savings and current accounts ----------

function casaQuote(db: MockDb, a: DepositAccount) {
  const problems: string[] = [];
  if (a.status !== 'ACTIVE') problems.push(`account ${a.accountNo} is ${a.status}`);
  const holds = activeHolds(db, a.id!);
  if (holds.length) problems.push(`account ${a.accountNo} has a freeze or lien in force: release it first`);
  const funding = db.deposits.accounts.filter((d) => d.family === 'TERM_DEPOSIT' && ['BOOKED', 'ACTIVE'].includes(d.status!) && d.termDeposit?.payoutAccountId === a.id);
  for (const d of funding) problems.push(`deposit ${d.accountNo} pays its interest or proceeds to this account: change it first`);
  const s = productOf(db, a).charges;
  const days = daysBetween(a.openedOn!, db.businessDate);
  let charge: ReturnType<typeof withGst> | null = null;
  if (s?.closureAmount && days >= (s.closureFreeWithinDays ?? 0) && (s.closureWithinDays == null || days <= s.closureWithinDays)) {
    charge = withGst(toUnits(s.closureAmount), s.gstRatePercent ?? '18');
  }
  const balance = balanceOf(db, a.id!);
  if (charge && charge.total > balance) {
    const gross = balance;
    const rate = toUnits(s?.gstRatePercent ?? '18');
    const taxable = (gross * 1_000_000n) / (1_000_000n + rate) / 100n * 100n;
    charge = gross > 0n ? { taxable, gst: gross - taxable, cgst: half(gross - taxable), sgst: gross - taxable - half(gross - taxable), total: gross } : null;
  }
  return { balance, interest: 0n, charge, payable: balance - (charge?.total ?? 0n), problems };
}

// ---------- term deposits ----------

interface DepositQuote {
  kind: 'PREMATURE' | 'MATURITY' | null;
  reason: string | null;
  rate: string | null;
  due: bigint;
  credited: bigint;
  principalPayable: bigint;
  basis: string | null;
  problems: string[];
}

/** The card rate the product offered for the period the deposit has run (the highest amount step, the longest tenure step run). */
function rateForPeriodRun(db: MockDb, d: DepositAccount, on: string): string | null {
  const p = productOf(db, d);
  const t = d.termDeposit!;
  const card = (p.rateCard ?? []).map((c) => ({ fromAmount: String(c.fromAmount ?? '0'), fromMonths: Number(c.fromMonths ?? 0), fromDays: Number(c.fromDays ?? 0),
    ratePercent: String(c.ratePercent ?? '0') }));
  const steps = card.filter((c) => toUnits(c.fromAmount) <= toUnits(t.principal!));
  if (!steps.length) return null;
  const amount = steps.reduce((m, c) => (toUnits(c.fromAmount) > toUnits(m) ? c.fromAmount : m), steps[0].fromAmount);
  const run = steps.filter((c) => c.fromAmount === amount && on >= addDaysIso(addMonths(t.startDate!, c.fromMonths), c.fromDays));
  if (!run.length) return null;
  return run.reduce((m, c) => (c.fromMonths * 31 + c.fromDays > m.fromMonths * 31 + m.fromDays ? c : m), run[0]).ratePercent;
}
const addDaysIso = (iso: string, days: number) => new Date(Date.parse(iso + 'T00:00:00Z') + days * 86_400_000).toISOString().slice(0, 10);

function interestAt(db: MockDb, d: DepositAccount, end: string, rate: string): bigint {
  const t = d.termDeposit!;
  if (Number(rate) === 0 || end <= t.startDate!) return 0n;
  if (t.recurring) {
    // RecurringDeposit.interestFor: whole months run, each on the instalments paid by the day it began
    const tenure = t.tenureMonths ?? 0;
    let run = 0;
    while (run < tenure && addMonths(t.startDate!, run + 1) <= end) run += 1;
    if (run === 0) return 0n;
    const paid = (rdSchedule(db, d).schedule ?? []).filter((r) => r.paidOn);
    const held = (m: number) => paid.filter((r) => r.paidOn! <= addMonths(t.startDate!, m - 1)).reduce((s, r) => s + toUnits(r.amount!), 0n);
    const r = rdMonths(t.startDate!, tenure, rate, t.interestPeriodMonths ?? 3, t.rounding ?? 'PAISE_HALF_UP', run, held);
    return r.credited + r.pending;
  }
  return periods({ principal: t.principal!, ratePercent: rate, start: t.startDate!, maturity: end, periodMonths: t.interestPeriodMonths!, cumulative: t.cumulative === true,
    dayCount: t.dayCount!, rounding: t.rounding! }).reduce((s, x) => s + x.interest, 0n);
}

function depositQuote(db: MockDb, d: DepositAccount, asked: string | null): DepositQuote {
  const t = d.termDeposit!;
  const out: DepositQuote = { kind: null, reason: null, rate: null, due: 0n, credited: 0n, principalPayable: toUnits(t.principal ?? '0'), basis: null, problems: [] };
  if (d.status !== 'ACTIVE' && d.status !== 'MATURED') {
    out.problems.push(`deposit ${d.accountNo} is ${d.status}; only a funded deposit is closed`);
    return out;
  }
  if (activeHolds(db, d.id!).length) out.problems.push(`deposit ${d.accountNo} is under lien: release it first`);
  const today = db.businessDate;
  if (today >= t.maturityDate!) {
    out.kind = 'MATURITY';
    out.reason = 'MATURITY';
    out.rate = t.ratePercent!;
    out.due = interestAt(db, d, t.maturityDate!, t.ratePercent!);
    out.basis = 'paid at maturity on the contracted rate; no deposit day-end runs in mock mode, so no overdue interest';
    return out;
  }
  out.kind = 'PREMATURE';
  const reason = (asked ?? 'DEPOSITOR_REQUEST').toUpperCase();
  if (!REASONS.includes(reason)) {
    out.problems.push(`reason is one of ${REASONS.join(', ')}`);
    return out;
  }
  out.reason = reason;
  const months = (from: string, n: number) => addMonths(from, n);
  if (db.deposits.kind === 'NBFC_DEPOSIT') {
    const lockIn = rule(db, 'td.lock-in-months') ?? 3;
    if (today < months(t.startDate!, lockIn)) {
      if (reason === 'DEPOSITOR_REQUEST') {
        out.problems.push(`no repayment within ${lockIn} months of the deposit except on death or, for an individual, an emergency expense or critical illness`);
        return out;
      }
      out.rate = '0';
      if (reason === 'EMERGENCY_EXPENSE' && toUnits(t.principal!) > toUnits(String(rule(db, 'td.emergency.tiny-deposit-max') ?? 10000))) {
        const part = (toUnits(t.principal!) * BigInt(rule(db, 'td.emergency.max-percent') ?? 50)) / 100n;
        const cap = toUnits(String(rule(db, 'td.emergency.max-amount') ?? 500000));
        out.principalPayable = part < cap ? part : cap;
        out.problems.push('mock mode does not pay part of a deposit: the backend repays the emergency share and keeps the rest on deposit');
      }
      out.basis = `${reason.toLowerCase().replace(/_/g, ' ')} within ${lockIn} months of the deposit: principal repaid, no interest`;
      return out;
    }
    const noInterest = rule(db, 'td.premature.no-interest-before-months') ?? 6;
    if (today < months(t.startDate!, noInterest)) {
      out.rate = '0';
      out.basis = `closed before ${noInterest} months: no interest`;
    } else {
      const run = rateForPeriodRun(db, d, today);
      if (run !== null) {
        const cut = rule(db, 'td.premature.rate-cut-percent') ?? 2;
        out.rate = String(Math.max(0, Number(run) - cut));
        out.basis = `rate for the period run ${run}% less ${cut} points`;
      } else {
        const cut = rule(db, 'td.premature.rate-cut-from-minimum-percent') ?? 3;
        const lowest = Math.min(...(productOf(db, d).rateCard ?? []).map((c) => Number(c.ratePercent)));
        out.rate = String(Math.max(0, lowest - cut));
        out.basis = `no rate for the period run: lowest rate ${lowest}% less ${cut} points`;
      }
    }
  } else {
    if (!t.prematureAllowed) {
      out.problems.push(`product ${d.productCode} does not allow premature withdrawal`);
      return out;
    }
    const days = daysBetween(t.startDate!, today);
    const minDays = t.prematureMinDays ?? 0;
    if (days < minDays) {
      out.rate = '0';
      out.basis = `closed within ${minDays} days: no interest`;
    } else {
      const run = rateForPeriodRun(db, d, today);
      const base = run === null ? Number(t.ratePercent) : Math.min(Number(run), Number(t.ratePercent));
      const penalty = Number(t.prematurePenaltyPercent ?? 0);
      out.rate = String(Math.max(0, base - penalty));
      out.basis = `rate for the period run ${base}% less penalty ${penalty} points`;
    }
  }
  out.due = interestAt(db, d, today, out.rate!);
  return out;
}

// ---------- paying out ----------

interface Payout {
  mode: (typeof PAY_MODES)[number];
  accountId: string | null;
  reference: string | null;
}

function payout(db: MockDb, a: DepositAccount, b: Partial<Payout> | null | undefined, problems: string[]): Payout | null {
  if (!b || !b.mode) {
    problems.push('say where the proceeds go: payTo.mode is ACCOUNT, CASH, BANK_TRANSFER or GATEWAY');
    return null;
  }
  const mode = String(b.mode).toUpperCase() as Payout['mode'];
  if (!PAY_MODES.includes(mode)) {
    problems.push('payTo.mode is ACCOUNT, CASH, BANK_TRANSFER or GATEWAY');
    return null;
  }
  const reference = text(b.reference, 60, 'reference', problems);
  if (mode !== 'ACCOUNT' && b.accountId) problems.push('payTo.accountId goes with mode ACCOUNT');
  if (mode === 'ACCOUNT') {
    const to = b.accountId ? db.deposits.accounts.find((x) => x.id === b.accountId && x.family === 'CASA') : undefined;
    if (!to) problems.push('name the savings or current account the proceeds go to');
    else {
      if (to.id === a.id) problems.push('an account is not paid to itself');
      if (to.status !== 'ACTIVE') problems.push(`account ${to.accountNo} is ${to.status}`);
      if (activeHolds(db, to.id!).some((h) => h.kind === 'CREDIT_FREEZE' || h.kind === 'TOTAL_FREEZE')) problems.push(`account ${to.accountNo} is frozen for credits`);
    }
  }
  if (mode === 'BANK_TRANSFER' && !reference) problems.push("the bank's reference (UTR) is required for a bank transfer");
  if (mode === 'GATEWAY') {
    if (a.family !== 'TERM_DEPOSIT') problems.push('only a term deposit pays its proceeds through the payment gateway, to the bank account recorded on it');
    else if (!a.termDeposit?.payoutBankIfsc) problems.push('no bank account is recorded on the deposit');
  }
  return { mode, accountId: mode === 'ACCOUNT' ? b.accountId ?? null : null, reference };
}

function pay(db: MockDb, a: DepositAccount, amount: bigint, p: Payout, maker: string, approvalId: string | null): string | null {
  if (amount <= 0n) return null;
  const lot = uuid();
  const to = p.mode === 'ACCOUNT' ? findAccount(db, p.accountId!, 'CASA') : null;
  move(db, a, 'CLOSURE', 'DEBIT', fromUnits(amount, 4), lot, { reference: p.reference, counterpartyAccountNo: to?.accountNo ?? null,
    narration: to ? `Closure proceeds to ${to.accountNo}` : `Closure proceeds (${p.mode.toLowerCase().replace('_', ' ')})`,
    ...(p.mode === 'GATEWAY' ? { gatewayStatus: 'SENT' as const, gatewayUtr: null, bankAccountMasked: a.termDeposit?.payoutBankAccountMasked ?? null } : {}) }, maker, approvalId);
  if (to) move(db, to, 'TRANSFER_IN', 'CREDIT', fromUnits(amount, 4), lot, { counterpartyAccountNo: a.accountNo, narration: `Closure of ${a.accountNo}` }, maker, approvalId);
  return to?.accountNo ?? null;
}

// ---------- approvals ----------

export function applyDepositLifecycleApproval(db: MockDb, p: ApprovalPayload, approval: { id?: string; entityId?: string | null; maker?: string; appliedRef?: string | null }, checker: string, at: string): boolean {
  if (p.kind !== 'DEPOSIT_LIFECYCLE') return false;
  const maker = approval.maker ?? checker;
  const id = approval.id ?? null;
  const data = p.data as Record<string, string | null>;
  if (p.op === 'CHARGE') {
    const a = findAccount(db, p.accountId, 'CASA');
    const g = withGst(toUnits(data.amount!), productOf(db, a).charges?.gstRatePercent ?? '18');
    if (a.status !== 'ACTIVE') refuseIfAny('the charge cannot be levied', [`account ${a.accountNo} is ${a.status}`]);
    if (available(db, a) < g.total) refuseIfAny('the charge cannot be levied', [`account ${a.accountNo} has ${money(available(db, a))} free; the charge with GST is ${money(g.total)}`]);
    approval.appliedRef = levy(db, a, data.code!, data.description!, toUnits(data.amount!), data.reason, maker, id).movementId;
    return true;
  }
  if (p.op === 'CASA_CLOSE') {
    const a = findAccount(db, p.accountId, 'CASA');
    const q = casaQuote(db, a);
    refuseIfAny('the account cannot be closed', q.problems);
    let charges = 0n;
    if (q.charge) charges = toUnits(levy(db, a, 'CLOSURE', 'Account closure charge', q.charge.taxable, data.note, maker, id).total);
    const left = balanceOf(db, a.id!);
    const payTo = { mode: data.mode as Payout['mode'], accountId: data.toAccountId, reference: data.reference };
    const payAccountNo = pay(db, a, left, payTo, maker, id);
    a.status = 'CLOSED';
    db.deposits.closures[a.id!] = { kind: 'CASA', reason: 'DEPOSITOR_REQUEST', closedOn: db.businessDate, ratePercent: null, interestDue: '0', interestCredited: '0',
      interestRecovered: '0', overdueInterest: '0', charges: money(charges), tax: '0', paidAmount: money(left), payTo: left > 0n ? payTo.mode : null,
      payAccountNo, payReference: payTo.reference, renewedToAccountNo: null, basis: null, note: data.note, closedBy: maker };
    approval.appliedRef = a.accountNo!;
    appendAudit(db, at, checker, 'CASA_ACCOUNT_CLOSED', 'CASA_ACCOUNT', a.id!, { accountNo: a.accountNo });
    return true;
  }
  if (p.op === 'TD_CLOSE') {
    const d = findAccount(db, p.accountId, 'TERM_DEPOSIT');
    const q = depositQuote(db, d, data.reason === 'MATURITY' ? null : data.reason);
    refuseIfAny('the deposit cannot be closed', q.problems);
    const toCredit = q.due - q.credited;
    if (toCredit > 0n) move(db, d, 'INTEREST_CREDIT', 'CREDIT', fromUnits(toCredit, 4), uuid(), { narration: `Interest on closure (${q.basis})` }, maker, id);
    else if (toCredit < 0n) move(db, d, 'INTEREST_RECOVERY', 'DEBIT', fromUnits(-toCredit, 4), uuid(), { narration: 'Interest paid above what is due' }, maker, id);
    const left = balanceOf(db, d.id!);
    const payTo = { mode: data.mode as Payout['mode'], accountId: data.toAccountId, reference: data.reference };
    const payAccountNo = pay(db, d, left, payTo, maker, id);
    d.status = 'CLOSED';
    db.deposits.closures[d.id!] = { kind: q.kind!, reason: q.reason as DepositClosure['reason'], closedOn: db.businessDate, ratePercent: q.rate, interestDue: money(q.due),
      interestCredited: money(q.credited), interestRecovered: toCredit < 0n ? money(-toCredit) : '0', overdueInterest: '0', charges: '0', tax: '0', paidAmount: money(left),
      payTo: payTo.mode, payAccountNo, payReference: payTo.reference, renewedToAccountNo: null, basis: q.basis, note: data.note, closedBy: maker };
    approval.appliedRef = d.accountNo!;
    appendAudit(db, at, checker, 'TERM_DEPOSIT_CLOSED', 'TERM_DEPOSIT', d.id!, { accountNo: d.accountNo, kind: q.kind });
    return true;
  }
  if (p.op === 'INSTRUCTION') {
    const d = findAccount(db, p.accountId, 'TERM_DEPOSIT');
    d.termDeposit!.maturityInstruction = data.instruction as 'PAY_OUT';
    if (data.consentReference) db.deposits.consents[d.id!] = { instruction: data.instruction!, reference: data.consentReference, receivedOn: data.consentReceivedOn! };
    else delete db.deposits.consents[d.id!];
    approval.appliedRef = d.accountNo!;
    return true;
  }
  if (p.op === 'RENEW') throw invalid('a matured deposit is renewed by the backend only (there is no deposit day-end in mock mode)');
  return false;
}

// ---------- routes ----------

export function registerDepositLifecycleRoutes(db: MockDb, r: Registrar, module: (name: 'CASA' | 'TD') => void): void {
  const { on, require, propose } = r;
  const lifecycle = (op: 'CHARGE' | 'CASA_CLOSE' | 'TD_CLOSE' | 'INSTRUCTION' | 'RENEW', accountId: string, data: Record<string, unknown>): ApprovalPayload =>
    ({ kind: 'DEPOSIT_LIFECYCLE', op, accountId, data });

  on('GET', '/api/v1/casa/accounts/{id}/charges', ({ user, params }) => {
    module('CASA');
    require(user, P.casaView);
    findAccount(db, params.id, 'CASA');
    return ok(db.deposits.charges.filter((c) => c.accountId === params.id).map(({ accountId: _a, ...c }) => c));
  });

  on('GET', '/api/v1/casa/accounts/{id}/minimum-balance', ({ user, params }) => {
    module('CASA');
    require(user, P.casaView);
    findAccount(db, params.id, 'CASA');
    return ok([]);
  });

  on('POST', '/api/v1/casa/accounts/{id}/charges', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const a = findAccount(db, params.id, 'CASA');
    const b = (body ?? {}) as { code?: string; reason?: string };
    if (!b.code?.trim()) throw invalid("code is required: one of the product's service charges");
    const problems: string[] = [];
    const reason = text(b.reason, 500, 'reason', problems);
    if (!reason) problems.push('a reason is required');
    const services = productOf(db, a).charges?.service ?? [];
    const s = services.find((x) => x.code === b.code!.trim());
    if (!s) problems.push(`product ${a.productCode} has no service charge ${b.code.trim()}${services.length ? `; it has ${services.map((x) => x.code).join(', ')}` : ''}`);
    else {
      if (a.status !== 'ACTIVE') problems.push(`account ${a.accountNo} is ${a.status}`);
      const g = withGst(toUnits(s.amount!), productOf(db, a).charges?.gstRatePercent ?? '18');
      if (available(db, a) < g.total) problems.push(`account ${a.accountNo} has ${money(available(db, a))} free (after liens, and nothing when frozen for debits); the charge with GST is ${money(g.total)}`);
    }
    refuseIfAny('the charge cannot be levied', problems);
    const data = { accountNo: a.accountNo, code: s!.code, description: s!.name, amount: s!.amount, reason };
    const total = money(withGst(toUnits(s!.amount!), productOf(db, a).charges?.gstRatePercent ?? '18').total);
    return propose(user, 'DEPOSIT_TXN', 'CHARGE', lifecycle('CHARGE', a.id!, data), { ...data, total }, null, a.id!, total);
  });

  on('GET', '/api/v1/casa/accounts/{id}/closure-quote', ({ user, params }) => {
    module('CASA');
    require(user, P.casaView);
    const a = findAccount(db, params.id, 'CASA');
    const q = casaQuote(db, a);
    return ok({ accountNo: a.accountNo, closeOn: db.businessDate, balance: money(q.balance), interest: '0',
      closureCharge: q.charge ? { code: 'CLOSURE', description: 'Account closure charge', taxableValue: money(q.charge.taxable), gst: money(q.charge.gst), total: money(q.charge.total) } : null,
      payable: money(q.payable), problems: q.problems });
  });

  on('POST', '/api/v1/casa/accounts/{id}/close', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const a = findAccount(db, params.id, 'CASA');
    const b = (body ?? {}) as { payTo?: Partial<Payout>; reason?: string };
    const problems: string[] = [];
    const reason = text(b.reason, 500, 'reason', problems);
    if (!reason) problems.push('a reason is required');
    const q = casaQuote(db, a);
    problems.push(...q.problems);
    const p = q.payable > 0n ? payout(db, a, b.payTo, problems) : null;
    refuseIfAny('the account cannot be closed', problems);
    const data = { accountNo: a.accountNo, mode: p?.mode ?? null, toAccountId: p?.accountId ?? null, reference: p?.reference ?? null, note: reason, payable: money(q.payable) };
    return propose(user, 'CASA_ACCOUNT', 'CLOSE', lifecycle('CASA_CLOSE', a.id!, data), data, null, a.id!, money(q.payable));
  });

  on('GET', '/api/v1/deposits/accounts/{id}/closure-quote', ({ user, params, url }) => {
    module('TD');
    require(user, P.depositView);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    const q = depositQuote(db, d, url.searchParams.get('reason'));
    const t = d.termDeposit!;
    const balance = balanceOf(db, d.id!);
    return ok({ kind: q.kind, reason: q.reason, closeOn: db.businessDate, maturityDate: t.maturityDate ?? null, principal: t.principal, balance: money(balance),
      ratePercent: q.rate, interestDue: money(q.due), interestCredited: money(q.credited), toCredit: money(q.due > q.credited ? q.due - q.credited : 0n),
      toRecover: money(q.credited > q.due ? q.credited - q.due : 0n), overdueInterest: '0', basis: q.basis,
      payableBeforeTax: q.kind ? money(balance + q.due - q.credited) : null,
      taxNote: 'Tax at source is deducted from interest credited at closure, as from any interest credit.', problems: q.problems });
  });

  on('POST', '/api/v1/deposits/accounts/{id}/close', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositFund);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    const b = (body ?? {}) as { reason?: string | null; payTo?: Partial<Payout>; note?: string | null };
    const problems: string[] = [];
    const note = text(b.note, 500, 'note', problems);
    const q = depositQuote(db, d, b.reason ?? null);
    problems.push(...q.problems);
    const p = payout(db, d, b.payTo, problems);
    if (q.kind === 'PREMATURE' && q.reason !== 'DEPOSITOR_REQUEST' && !note) {
      problems.push(`say what supports a closure for ${q.reason!.toLowerCase().replace(/_/g, ' ')} (the note: the certificate or claim seen)`);
    }
    refuseIfAny('the deposit cannot be closed', problems);
    const payable = money(balanceOf(db, d.id!) + q.due - q.credited);
    const data = { accountNo: d.accountNo, reason: q.reason, mode: p!.mode, toAccountId: p!.accountId, reference: p!.reference, note, payable };
    return propose(user, 'TERM_DEPOSIT', 'CLOSE', lifecycle('TD_CLOSE', d.id!, data), data, null, d.id!, payable);
  });

  const consentProblems = (c: { reference?: string; receivedOn?: string } | null | undefined, instruction: string, d: DepositAccount): string[] => {
    const needed = rule(db, 'td.renewal-needs-consent') === 1 && instruction !== 'PAY_OUT';
    if (!needed) return c ? ['a consent is recorded only for a renewal on a deposit-taking NBFC'] : [];
    if (!c?.reference?.trim() || !c.receivedOn) {
      return ["a renewal needs the depositor's express consent (NBFC Directions para 17): give the reference of their signed request and the date it was received"];
    }
    const out: string[] = [];
    if (c.reference.trim().length > 60) out.push('the consent reference is longer than 60 characters');
    if (c.receivedOn > db.businessDate || c.receivedOn < d.termDeposit!.bookedOn!) out.push(`the consent was received between booking (${d.termDeposit!.bookedOn}) and today`);
    return out;
  };

  on('POST', '/api/v1/deposits/accounts/{id}/maturity-instruction', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositFund);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    const t = d.termDeposit!;
    const b = (body ?? {}) as { maturityInstruction?: string; consent?: { reference?: string; receivedOn?: string } | null };
    const instruction = (b.maturityInstruction ?? '').toUpperCase();
    const problems: string[] = [];
    if (!INSTRUCTIONS.includes(instruction)) problems.push('maturityInstruction is PAY_OUT, RENEW_PRINCIPAL or RENEW_WITH_INTEREST');
    if (d.status !== 'ACTIVE' || !t.funded || db.businessDate >= t.maturityDate!) {
      problems.push(`the maturity instruction of deposit ${d.accountNo} is changed while it is funded and before its maturity date`);
    }
    if (instruction === t.maturityInstruction) problems.push(`the deposit is already to ${instruction.toLowerCase().replace(/_/g, ' ')}`);
    if (instruction.startsWith('RENEW') && !productOf(db, d).autoRenewalAllowed) problems.push(`product ${d.productCode} does not allow renewal at maturity`);
    problems.push(...consentProblems(b.consent, instruction, d));
    refuseIfAny('the maturity instruction cannot be changed', problems);
    const data = { accountNo: d.accountNo, instruction, consentReference: b.consent?.reference?.trim() ?? null, consentReceivedOn: b.consent?.receivedOn ?? null };
    return propose(user, 'TERM_DEPOSIT', 'INSTRUCTION', lifecycle('INSTRUCTION', d.id!, data), data, { maturityInstruction: t.maturityInstruction }, d.id!, null);
  });

  on('POST', '/api/v1/deposits/accounts/{id}/renew', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositFund);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    const b = (body ?? {}) as { instruction?: string; consent?: { reference?: string; receivedOn?: string } | null };
    const instruction = (b.instruction ?? '').toUpperCase();
    const problems: string[] = [];
    if (instruction !== 'RENEW_PRINCIPAL' && instruction !== 'RENEW_WITH_INTEREST') problems.push('instruction is RENEW_PRINCIPAL or RENEW_WITH_INTEREST');
    if (d.status !== 'MATURED') problems.push(`deposit ${d.accountNo} is ${d.status}; a deposit is renewed on request once it has MATURED`);
    problems.push(...consentProblems(b.consent, instruction || 'RENEW_PRINCIPAL', d));
    refuseIfAny('the deposit cannot be renewed', problems);
    const data = { accountNo: d.accountNo, instruction };
    return propose(user, 'TERM_DEPOSIT', 'RENEW', lifecycle('RENEW', d.id!, data), data, null, d.id!, money(balanceOf(db, d.id!)));
  });

  on('GET', '/api/v1/deposits/maturities', ({ user, url }) => {
    module('TD');
    require(user, P.depositView);
    const from = url.searchParams.get('from') ?? db.businessDate;
    const to = url.searchParams.get('to') ?? addDaysIso(db.businessDate, 30);
    if (to < from) throw invalid('to is on or after from');
    const rows = db.deposits.accounts
      .filter((d) => d.family === 'TERM_DEPOSIT' && d.termDeposit?.maturityDate && (d.status === 'MATURED' || (d.status === 'ACTIVE' && d.termDeposit.maturityDate >= from && d.termDeposit.maturityDate <= to)))
      .sort((x, y) => Number(y.status === 'MATURED') - Number(x.status === 'MATURED') || x.termDeposit!.maturityDate!.localeCompare(y.termDeposit!.maturityDate!));
    return ok(rows.map((d) => {
      const t = d.termDeposit!;
      return { id: d.id, accountNo: d.accountNo, status: d.status, branchCode: d.branchCode, customerNo: d.holders?.[0]?.customerNo, customerName: d.holders?.[0]?.name,
        productCode: d.productCode, principal: t.principal, ratePercent: t.ratePercent, startDate: t.startDate, maturityDate: t.maturityDate, maturityInstruction: t.maturityInstruction,
        payoutTo: t.payoutTo, payoutAccountNo: t.payoutAccountNo ?? null, payoutBankAccountMasked: t.payoutBankAccountMasked ?? null, balance: money(balanceOf(db, d.id!)),
        expectedAmount: t.maturityValue, noticeOn: null, consentReference: db.deposits.consents[d.id!]?.reference ?? null, waitingBecause: null };
    }));
  });

}
