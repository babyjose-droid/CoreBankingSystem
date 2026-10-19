/**
 * Mock standing instructions and pledges (P4-5b), as the backend's StandingInstructions and DepositPledges: given and
 * cancelled through a checker; carried out when the job STANDING_INSTRUCTIONS runs (the Jobs page's "Run now"), each
 * payment from its date and tried again for three days; a deposit pledged for a loan by a lien naming it, released only
 * once the loan is closed. Mock mode has no deposit day-end, so a pledge of a closed loan is released by hand.
 */
import type { DepositAccount, LoanPledge, PledgeQuote, StandingInstruction, StandingInstructionInput } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { addDays } from '../lib/dates';
import { toUnits } from '../lib/money';
import { appendAudit, uuid, type ApprovalPayload, type MockDb, type StoredLoan } from './db';
import { applyDepositMoneyApproval, balanceOf, findAccount, move, movementProblems, plain, rdSchedule, refuseIfAny, text, type MockHold } from './depositMoney';
import { BANK_RULES, NBFC_DEPOSIT_RULES } from './depositRules';
import { addMonths } from './deposits';
import { addEvent, refreshLoan } from './lending';
import { HttpProblem, notFound, PROBLEM_BASE } from './problems';

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

export type MockInstruction = Required<Pick<StandingInstruction, 'id' | 'instructionNo' | 'kind' | 'fromAccountId' | 'fromAccountNo' | 'startOn' | 'status' | 'createdBy'>> &
  Omit<StandingInstruction, 'paidCount' | 'lastPaidFor'>;
export interface MockSiRun {
  instructionId: string;
  forDate: string;
  businessDate: string;
  status: 'DONE' | 'FAILED';
  amount: string | null;
  reason: string | null;
  attempts: number;
  movementId: string | null;
  loanTxnId: string | null;
}
export interface MockSiRequest {
  op: 'CREATE' | 'CANCEL';
  instruction: Partial<MockInstruction>;
  reason: string | null;
}
export interface MockPledgeRequest {
  accountId: string;
  accountNo: string;
  loanId: string;
  loanNo: string;
  amount: string;
  reason: string;
}

const RETRY_DAYS = 3;
const KINDS = ['TRANSFER', 'LOAN_INSTALMENT', 'RD_INSTALMENT'];
const holdsOf = (a: DepositAccount, customerId: string) => (a.holders ?? []).some((h) => h.customerId === customerId);
const shareHolder = (x: DepositAccount, y: DepositAccount) => (x.holders ?? []).some((h) => holdsOf(y, h.customerId!));
const loanNoOf = (db: MockDb, id: string | null | undefined) => db.loans.find((l) => l.id === id)?.loanNo ?? null;

function view(db: MockDb, s: MockInstruction): StandingInstruction {
  const done = db.deposits.siRuns.filter((r) => r.instructionId === s.id && r.status === 'DONE');
  return { ...s, paidCount: done.length, lastPaidFor: done.map((r) => r.forDate).sort().at(-1) ?? null };
}

/** The loan's unpaid amount for a due date: its demand, or the coming instalment less any advance held. Paise. */
function loanDue(loan: StoredLoan, date: string): number {
  const st = loan.state;
  const d = st.demands.find((x) => x.dueDate === date);
  if (d) return Math.max(0, d.principalDue + d.interestDue - d.principalPaid - d.interestPaid - d.principalRescheduled - d.interestCapitalised);
  const row = st.rows.slice(st.raised).find((r) => r.dueDate === date);
  return row ? Math.max(0, row.instalment - st.advance) : 0;
}

function done(db: MockDb, id: string, forDate: string) {
  return db.deposits.siRuns.some((r) => r.instructionId === id && r.forDate === forDate && r.status === 'DONE');
}

function failedAttempt(db: MockDb, s: MockInstruction, forDate: string, amount: string | null, reason: string) {
  const r = db.deposits.siRuns.find((x) => x.instructionId === s.id && x.forDate === forDate && x.status === 'FAILED' && x.businessDate === db.businessDate);
  if (r) Object.assign(r, { attempts: r.attempts + 1, reason });
  else db.deposits.siRuns.unshift({ instructionId: s.id, forDate, businessDate: db.businessDate, status: 'FAILED', amount, reason, attempts: 1, movementId: null, loanTxnId: null });
}

function paid(db: MockDb, s: MockInstruction, forDate: string, amount: string, movementId: string, loanTxnId: string | null = null) {
  db.deposits.siRuns.unshift({ instructionId: s.id, forDate, businessDate: db.businessDate, status: 'DONE', amount, reason: null, attempts: 1, movementId, loanTxnId });
}

function end(db: MockDb, s: MockInstruction, why: string) {
  Object.assign(s, { status: 'ENDED', closedOn: db.businessDate, closedBy: 'system', closeReason: why });
}

/** Carries out the payments due (the job STANDING_INSTRUCTIONS). */
export function runStandingInstructions(db: MockDb, at: string): { processed: number; failed: number } {
  let processed = 0;
  let failed = 0;
  const today = db.businessDate;
  for (const s of db.deposits.instructions.filter((x) => x.status === 'ACTIVE' && x.startOn <= today)) {
    const from = db.deposits.accounts.find((a) => a.id === s.fromAccountId)!;
    const windowStart = [s.startOn, addDays(today, -RETRY_DAYS)].sort().at(-1)!;
    if (from.status !== 'ACTIVE') { end(db, s, `account ${from.accountNo} is ${from.status}`); continue; }
    if (s.endOn && today > addDays(s.endOn, RETRY_DAYS)) { end(db, s, `the end date ${s.endOn} has passed`); continue; }
    const tryPay = (forDate: string, amount: string, pay: () => { movementId: string; loanTxnId?: string | null }) => {
      if (done(db, s.id, forDate)) return false;
      const problems = movementProblems(db, from, 'DEBIT', amount, false);
      if (problems.length) {
        failed += 1;
        failedAttempt(db, s, forDate, amount, `the payment cannot be made: ${problems.join('; ')}`);
        return false;
      }
      const r = pay();
      paid(db, s, forDate, amount, r.movementId, r.loanTxnId ?? null);
      processed += 1;
      return true;
    };
    if (s.kind === 'TRANSFER') {
      const to = db.deposits.accounts.find((a) => a.id === s.toAccountId)!;
      if (to.status !== 'ACTIVE') { end(db, s, `account ${to.accountNo} is ${to.status}`); continue; }
      for (let k = 0; ; k++) {
        const d = addMonths(s.startOn, k * (s.everyMonths ?? 1));
        if (d > today || (s.endOn && d > s.endOn)) break;
        if (d < windowStart) continue;
        tryPay(d, s.amount!, () => {
          const lot = uuid();
          const out = move(db, from, 'TRANSFER_OUT', 'DEBIT', s.amount!, lot, { reference: s.instructionNo, narration: s.narration ?? `Standing instruction ${s.instructionNo}`, counterpartyAccountNo: to.accountNo }, 'system', null);
          move(db, to, 'TRANSFER_IN', 'CREDIT', s.amount!, lot, { reference: s.instructionNo, narration: s.narration ?? `Standing instruction ${s.instructionNo}`, counterpartyAccountNo: from.accountNo }, 'system', null);
          return { movementId: out.id! };
        });
      }
    } else if (s.kind === 'LOAN_INSTALMENT') {
      const loan = db.loans.find((l) => l.id === s.loanId);
      if (!loan || ['CLOSED', 'CANCELLED', 'WRITTEN_OFF'].includes(loan.state.status)) { end(db, s, `loan ${loan?.loanNo ?? s.loanId} is ${loan?.state.status ?? 'gone'}`); continue; }
      if (loan.state.status !== 'ACTIVE') continue;
      for (let d = windowStart; d <= today; d = addDays(d, 1)) {
        const due = loanDue(loan, d);
        if (due <= 0) continue;
        const amount = plain(BigInt(due) * 100n);
        tryPay(d, amount, () => {
          const m = move(db, from, 'LOAN_REPAYMENT', 'DEBIT', amount, uuid(), { reference: s.instructionNo, narration: `Instalment of ${d}, loan ${loan.loanNo}` }, 'system', null);
          const ev = addEvent(db, loan, 'system', at, { type: 'REPAYMENT', valueDate: today, amount: due, summary: `Receipt via SI (${s.instructionNo}) from ${from.accountNo}`, data: { mode: 'SI', reference: s.instructionNo } });
          refreshLoan(db, loan);
          return { movementId: m.id!, loanTxnId: ev.id };
        });
      }
    } else {
      const rd = db.deposits.accounts.find((a) => a.id === s.toAccountId)!;
      for (let guard = 0; guard < 24; guard++) {
        const next = rdSchedule(db, rd).next;
        if (rd.status !== 'ACTIVE' || !next) { end(db, s, `recurring deposit ${rd.accountNo} ${rd.status !== 'ACTIVE' ? `is ${rd.status}` : 'has every instalment paid'}`); break; }
        if (!next.payableNow || next.dueDate! > today || next.dueDate! < windowStart || done(db, s.id, next.dueDate!)) break;
        const made = tryPay(next.dueDate!, next.total!, () => {
          const approval: { id?: string; appliedRef?: string | null } = {};
          applyDepositMoneyApproval(db, { kind: 'DEPOSIT_INSTALMENT', instalment: { depositId: rd.id!, depositNo: rd.accountNo!, number: next.number!, dueDate: next.dueDate!,
            amount: next.amount!, lateCharge: next.lateCharge!, source: 'STANDING_INSTRUCTION', fromAccountId: from.id!, fromAccountNo: from.accountNo!, reference: s.instructionNo } },
            approval, 'system', at);
          const credit = db.deposits.movements.find((m) => m.id === approval.appliedRef)!;
          return { movementId: db.deposits.movements.find((m) => m.lotId === credit.lotId && m.kind === 'FUNDING_PAID')!.id! };
        });
        if (!made) break;
      }
    }
  }
  return { processed, failed };
}

// ---------- pledges ----------

const rule = (db: MockDb, code: string) => {
  const r = (db.deposits.kind === 'BANK' ? BANK_RULES : db.deposits.kind === 'NBFC_DEPOSIT' ? NBFC_DEPOSIT_RULES : []).find((x) => x.code === code);
  return r ? Number(r.value) : null;
};

function pledgeQuote(db: MockDb, d: DepositAccount, loan: StoredLoan): PledgeQuote {
  const problems: string[] = [];
  const status: string = loan.state.status;
  if (d.status !== 'ACTIVE') problems.push(`deposit ${d.accountNo} is ${d.status}; an active deposit is pledged`);
  if (!['SANCTIONED', 'ACTIVE'].includes(status)) problems.push(`loan ${loan.loanNo} is ${status}; a deposit is pledged for a sanctioned or active loan`);
  if (!holdsOf(d, loan.customerId)) problems.push(`deposit ${d.accountNo} is not held by the borrower of loan ${loan.loanNo}`);
  const other = db.deposits.holds.find((h) => h.accountId === d.id && h.status === 'ACTIVE' && h.loanId);
  if (other) problems.push(`deposit ${d.accountNo} already secures loan ${other.loanNo}`);
  const pledged = db.deposits.holds.filter((h) => h.loanId === loan.id && h.status === 'ACTIVE' && h.accountId !== d.id).map((h) => db.deposits.accounts.find((a) => a.id === h.accountId)!);
  const all = [...pledged, d];
  const maxPercent = rule(db, 'td.loan-max-percent') ?? 90;
  const spread = rule(db, 'td.loan-spread-percent') ?? 1;
  const lockIn = rule(db, 'td.lock-in-months') ?? 0;
  const value = all.reduce((s, x) => s + balanceOf(db, x.id!), 0n);
  // to the rupee below (money units are 1/10000 of a rupee)
  const maxLoan = ((value * BigInt(Math.round(maxPercent * 100))) / 10000n / 10000n) * 10000n;
  const minRate = Math.max(...all.map((x) => Number(x.termDeposit?.ratePercent ?? 0))) + spread;
  const sanctioned = BigInt(loan.amount) * 100n;
  const maturity = addMonths(loan.disbursedOn ?? db.businessDate, loan.tenorMonths);
  if (d.status === 'ACTIVE') {
    for (const x of all) {
      const start = x.termDeposit!.startDate!;
      if (lockIn > 0 && db.businessDate < addMonths(start, lockIn)) problems.push(`deposit ${x.accountNo} started on ${start}: no loan against it within ${lockIn} months of the deposit (until ${addMonths(start, lockIn)})`);
      if (x.termDeposit!.maturityDate && maturity > x.termDeposit!.maturityDate) problems.push(`loan ${loan.loanNo} runs to ${maturity}, after deposit ${x.accountNo} matures on ${x.termDeposit!.maturityDate}`);
    }
    if (sanctioned > maxLoan) problems.push(`loan ${loan.loanNo} of ${plain(sanctioned)} is more than ${maxPercent}% of the deposits pledged (${plain(maxLoan)})`);
    if (loan.statedRate < minRate) problems.push(`the loan's rate ${loan.statedRate}% is below ${Number(minRate.toFixed(4))}% (the deposit's rate plus ${spread} points)`);
  }
  return {
    depositId: d.id, depositNo: d.accountNo, loanId: loan.id, loanNo: loan.loanNo, loanStatus: status, sanctioned: plain(sanctioned), loanRatePercent: String(loan.statedRate),
    loanMaturity: maturity, lien: plain(balanceOf(db, d.id!)), maxPercent: String(maxPercent), spreadPercent: String(spread), lockInMonths: lockIn, maxLoan: plain(maxLoan),
    minRatePercent: String(Number(minRate.toFixed(4))),
    deposits: all.map((x) => ({ depositNo: x.accountNo, value: plain(balanceOf(db, x.id!)), ratePercent: String(x.termDeposit?.ratePercent ?? '0'), maturityDate: x.termDeposit?.maturityDate ?? null, thisOne: x.id === d.id })),
    problems: [...new Set(problems)],
  };
}

/** Why a hold cannot be released by hand: a pledge of a loan that is still open. */
export function pledgeReleaseProblems(db: MockDb, h: MockHold): string[] {
  if (!h.loanId) return [];
  const loan = db.loans.find((l) => l.id === h.loanId);
  const status = loan?.state.status ?? 'CLOSED';
  return status === 'CLOSED' || status === 'CANCELLED' ? [] : [`the deposit secures loan ${loan?.loanNo}, which is ${status}: the pledge is released when the loan is closed`];
}

// ---------- approvals ----------

export function applyDepositStandingApproval(db: MockDb, p: ApprovalPayload, approval: { id?: string; appliedRef?: string | null }, checker: string, at: string): boolean {
  if (p.kind === 'DEPOSIT_SI') {
    const q = p.request;
    if (q.op === 'CANCEL') {
      const s = db.deposits.instructions.find((x) => x.id === q.instruction.id);
      if (!s || s.status !== 'ACTIVE') throw new HttpProblem(409, 'Conflict', 'the standing instruction is no longer active', {}, PROBLEM_BASE + 'conflict');
      Object.assign(s, { status: 'CANCELLED', closedOn: db.businessDate, closedBy: checker, closeReason: q.reason });
      approval.appliedRef = s.instructionNo;
    } else {
      const s = { ...q.instruction, id: uuid(), instructionNo: `SI${String(++db.deposits.siSeq).padStart(8, '0')}`, status: 'ACTIVE', closedOn: null, closedBy: null, closeReason: null } as MockInstruction;
      db.deposits.instructions.unshift(s);
      approval.appliedRef = s.instructionNo;
    }
    appendAudit(db, at, checker, q.op === 'CANCEL' ? 'STANDING_INSTRUCTION_CANCELLED' : 'STANDING_INSTRUCTION_GIVEN', 'STANDING_INSTRUCTION', approval.appliedRef!, {});
    return true;
  }
  if (p.kind === 'DEPOSIT_PLEDGE') {
    const q = p.pledge;
    const d = findAccount(db, q.accountId, 'TERM_DEPOSIT');
    const loan = db.loans.find((l) => l.id === q.loanId)!;
    refuseIfAny('the deposit cannot be pledged', pledgeQuote(db, d, loan).problems ?? []);
    const hold: MockHold = { id: uuid(), accountId: d.id!, kind: 'LIEN', amount: plain(balanceOf(db, d.id!)), reason: q.reason, reference: `Loan ${loan.loanNo}`, status: 'ACTIVE',
      placedOn: db.businessDate, placedBy: checker, releasedOn: null, releasedBy: null, releaseReason: null, loanId: loan.id, loanNo: loan.loanNo, loanStatus: loan.state.status };
    db.deposits.holds.unshift(hold);
    approval.appliedRef = hold.id!;
    return true;
  }
  return false;
}

// ---------- routes ----------

export function registerDepositStandingRoutes(db: MockDb, r: Registrar, module: (name: 'CASA' | 'TD') => void): void {
  const { on, require, propose } = r;
  const findInstruction = (id: string) => {
    const s = db.deposits.instructions.find((x) => x.id === id);
    if (!s) throw notFound(`standing instruction ${id}`);
    return s;
  };
  const findLoan = (id: string | null | undefined) => {
    const l = db.loans.find((x) => x.id === id);
    if (!l) throw notFound(`loan ${id}`);
    return l;
  };

  on('GET', '/api/v1/casa/standing-instructions', ({ user, url }) => {
    module('CASA');
    require(user, P.casaView);
    const accountId = url.searchParams.get('accountId');
    const loanId = url.searchParams.get('loanId');
    if (!accountId && !loanId) throw invalid('give accountId or loanId');
    const rows = db.deposits.instructions.filter((s) => (accountId ? s.fromAccountId === accountId || s.toAccountId === accountId : s.loanId === loanId));
    return ok(rows.sort((x, y) => (x.status === y.status ? 0 : x.status === 'ACTIVE' ? -1 : 1)).map((s) => view(db, s)));
  });

  on('GET', '/api/v1/casa/standing-instructions/{id}', ({ user, params }) => {
    module('CASA');
    require(user, P.casaView);
    const s = findInstruction(params.id);
    return ok({ ...view(db, s), runs: db.deposits.siRuns.filter((x) => x.instructionId === s.id).sort((x, y) => y.forDate.localeCompare(x.forDate) || y.businessDate.localeCompare(x.businessDate)).map(({ instructionId: _i, ...x }) => x) });
  });

  on('POST', '/api/v1/casa/standing-instructions', ({ user, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const b = (body ?? {}) as Partial<StandingInstructionInput>;
    if (!b.kind || !KINDS.includes(b.kind)) throw invalid('kind is one of TRANSFER, LOAN_INSTALMENT, RD_INSTALMENT');
    if (!b.fromAccountId) throw invalid('fromAccountId is required');
    const from = findAccount(db, b.fromAccountId, 'CASA');
    const problems: string[] = [];
    if (from.status !== 'ACTIVE') problems.push(`account ${from.accountNo} is ${from.status}`);
    const start = b.startOn || db.businessDate;
    if (start < db.businessDate) problems.push(`the instruction starts on or after the business date (${db.businessDate})`);
    if (b.endOn && b.endOn < start) problems.push('the end date is before the start date');
    const narration = text(b.narration, 200, 'narration', problems);
    let to: DepositAccount | null = null;
    let amount: string | null = null;
    if (b.kind === 'TRANSFER') {
      if (!b.toAccountId) problems.push('name the account the money goes to');
      else {
        to = findAccount(db, b.toAccountId, 'CASA');
        if (to.id === from.id) problems.push('an account does not pay itself');
        else if (to.status !== 'ACTIVE') problems.push(`account ${to.accountNo} is ${to.status}`);
      }
      amount = b.amount == null || b.amount === '' ? null : String(b.amount);
      if (!amount || !/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) problems.push('the amount is above zero, to the paisa');
      if (![1, 3, 6, 12].includes(b.everyMonths ?? 0)) problems.push('everyMonths is 1, 3, 6 or 12');
    } else if (b.kind === 'LOAN_INSTALMENT') {
      if (!b.loanId) problems.push('name the loan whose instalments are paid');
      else {
        const loan = findLoan(b.loanId);
        if (!['SANCTIONED', 'ACTIVE'].includes(loan.state.status)) problems.push(`loan ${loan.loanNo} is ${loan.state.status}`);
        if (!holdsOf(from, loan.customerId)) problems.push(`account ${from.accountNo} is not held by the borrower of loan ${loan.loanNo}`);
        if (db.deposits.instructions.some((s) => s.status === 'ACTIVE' && s.loanId === loan.id)) problems.push(`loan ${loan.loanNo} is already paid by a standing instruction`);
      }
      if (b.amount != null || b.everyMonths != null || b.toAccountId) problems.push('a loan instalment instruction pays what falls due: it has no amount, frequency or other account');
    } else {
      if (!b.toAccountId) problems.push('name the recurring deposit');
      else {
        to = findAccount(db, b.toAccountId, 'TERM_DEPOSIT');
        if (!to.termDeposit?.recurring) problems.push(`deposit ${to.accountNo} is not a recurring deposit`);
        else if (to.status !== 'ACTIVE') problems.push(`recurring deposit ${to.accountNo} is ${to.status} (fund it first)`);
        if (!shareHolder(from, to)) problems.push(`account ${from.accountNo} and deposit ${to.accountNo} have no holder in common`);
        if (db.deposits.instructions.some((s) => s.status === 'ACTIVE' && s.kind === 'RD_INSTALMENT' && s.toAccountId === to!.id)) problems.push(`deposit ${to.accountNo} is already paid by a standing instruction`);
      }
      if (b.amount != null || b.everyMonths != null || b.loanId) problems.push('a recurring deposit instruction pays each instalment: it has no amount, frequency or loan');
    }
    refuseIfAny('the standing instruction cannot be given', problems);
    const instruction: Partial<MockInstruction> = { kind: b.kind as 'TRANSFER', fromAccountId: from.id!, fromAccountNo: from.accountNo!, toAccountId: to?.id ?? null, toAccountNo: to?.accountNo ?? null,
      loanId: b.loanId ?? null, loanNo: loanNoOf(db, b.loanId), amount: amount ? plain(toUnits(amount)) : null, everyMonths: b.kind === 'TRANSFER' ? b.everyMonths! : null,
      startOn: start, endOn: b.endOn ?? null, narration, createdBy: user.username };
    const request: MockSiRequest = { op: 'CREATE', instruction, reason: null };
    return propose(user, 'STANDING_INSTRUCTION', 'CREATE', { kind: 'DEPOSIT_SI', request }, { ...instruction }, null, from.id!, amount);
  });

  on('POST', '/api/v1/casa/standing-instructions/{id}/cancel', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const s = findInstruction(params.id);
    const problems: string[] = [];
    if (s.status !== 'ACTIVE') problems.push(`standing instruction ${s.instructionNo} is ${s.status}`);
    const reason = text(((body ?? {}) as { reason?: string }).reason, 500, 'reason', problems);
    if (!reason) problems.push('a reason is required');
    refuseIfAny('the standing instruction cannot be cancelled', problems);
    const request: MockSiRequest = { op: 'CANCEL', instruction: { id: s.id, instructionNo: s.instructionNo }, reason };
    return propose(user, 'STANDING_INSTRUCTION', 'CANCEL', { kind: 'DEPOSIT_SI', request }, { instructionNo: s.instructionNo, reason }, null, s.id);
  });

  on('GET', '/api/v1/deposits/accounts/{id}/pledge-quote', ({ user, params, url }) => {
    module('TD');
    require(user, P.depositView);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    const loanId = url.searchParams.get('loanId');
    if (!loanId) throw invalid('loanId is required');
    return ok(pledgeQuote(db, d, findLoan(loanId)));
  });

  on('POST', '/api/v1/deposits/accounts/{id}/pledge', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositHold);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    const b = (body ?? {}) as { loanId?: string; reason?: string };
    if (!b.loanId) throw invalid('loanId is required');
    const loan = findLoan(b.loanId);
    const problems = [...(pledgeQuote(db, d, loan).problems ?? [])];
    const reason = text(b.reason, 500, 'reason', problems);
    if (!reason) problems.push('a reason is required (the sanction letter or the loan agreement)');
    refuseIfAny('the deposit cannot be pledged', problems);
    const pledge: MockPledgeRequest = { accountId: d.id!, accountNo: d.accountNo!, loanId: loan.id, loanNo: loan.loanNo, amount: plain(balanceOf(db, d.id!)), reason: reason! };
    return propose(user, 'DEPOSIT_HOLD', 'PLEDGE', { kind: 'DEPOSIT_PLEDGE', pledge }, { ...pledge }, null, d.id!, pledge.amount);
  });

  on('GET', '/api/v1/deposits/pledges', ({ user, url }) => {
    module('TD');
    require(user, P.depositView);
    const loan = findLoan(url.searchParams.get('loanId'));
    const rows: LoanPledge[] = db.deposits.holds.filter((h) => h.loanId === loan.id).map((h) => {
      const d = db.deposits.accounts.find((a) => a.id === h.accountId)!;
      return { holdId: h.id, depositId: d.id, depositNo: d.accountNo, amount: h.amount ?? '0', status: h.status, placedOn: h.placedOn, releasedOn: h.releasedOn ?? null, reason: h.reason,
        depositRatePercent: String(d.termDeposit?.ratePercent ?? ''), depositMaturity: d.termDeposit?.maturityDate ?? null, depositStatus: d.status };
    });
    return ok(rows);
  });
}
