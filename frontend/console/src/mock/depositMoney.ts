/**
 * Mock of money on deposit accounts (Phase 4, P4-2): cash deposits and withdrawals, transfers, bank transfers recorded
 * with their UTR, reversals, freezes and liens, and funding a term deposit. The checks follow the backend's deposit
 * engine (AccountHolds) closely enough to try the screens; the backend is the truth. Money in and out and a funding
 * post at once below 2,00,000 (as the tenant's starting approval rules); from it upward, and for every reversal, hold and
 * funding reversal, a checker is needed. No ledger is kept here: the balances are the movements.
 *
 * P4-2b: a bank transfer out can be sent through the payment gateway, and a payment link can be made for a savings or
 * current account or a booked term deposit. There is no worker here: the simulator acts when the statement or the links
 * are next read. A transfer is paid (with a UTR) unless its amount ends in .01, when it fails and the money comes back;
 * a link is paid unless its amount ends in .01.
 */
import type {
  BankAccountInput,
  DepositAccount,
  TaxDeclaration,
  DepositHold,
  DepositHoldInput,
  DepositMovement,
  DepositTransactionInput,
  DepositTransferInput,
  TermDepositFundingInput,
  RdInstalmentInput,
  RdInstalments,
} from '../api/types';
import type { CollectionOrder } from '../api/integrationTypes';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { fromUnits, toUnits } from '../lib/money';
import { appendAudit, uuid, type ApprovalPayload, type MockDb } from './db';
import { periods, rdPeriods } from './deposits';
import { assertWithinLimit } from './limits';
import { conflict, HttpProblem, notFound, PROBLEM_BASE } from './problems';

/** Below this, money in and out posts at once (the tenant's starting approval rule; not a regulatory figure). */
export const DEPOSIT_CHECKER_FROM = '200000';

export type MockMovement = DepositMovement & { accountId: string; lotId: string };
export type MockHold = DepositHold & { accountId: string };
export type MockDepositOrder = CollectionOrder & { depositAccountId: string; accountNo: string; purpose: 'CREDIT' | 'FUNDING' };
export type MockDeclaration = TaxDeclaration & { customerId: string };
export interface MockDeclarationRequest {
  customerId: string;
  customerNo: string;
  taxYear: number;
  receivedOn: string;
  reference: string | null;
  declarationId: string | null;
  reason: string | null;
}

export interface MockBankAccount {
  last4: string;
  ifsc: string;
}

export interface MockTxn {
  type: string;
  accountId: string;
  accountNo: string;
  toAccountId: string | null;
  toAccountNo: string | null;
  amount: string;
  reference: string | null;
  narration: string | null;
  bankAccount: MockBankAccount | null;
  movementId: string | null;
  reason: string | null;
  /** P4-2b: a bank transfer out sent through the payment gateway. */
  viaGateway?: boolean;
}

export interface MockHoldRequest {
  accountId: string;
  accountNo: string;
  kind: string;
  amount: string | null;
  reason: string;
  reference: string | null;
  holdId: string | null;
}

/** The next instalment of a recurring deposit (P4-5), with its late charge. */
export interface MockInstalment {
  depositId: string;
  depositNo: string;
  number: number;
  dueDate: string;
  amount: string;
  lateCharge: string;
  source: string;
  fromAccountId: string | null;
  fromAccountNo: string | null;
  reference: string | null;
}

export interface MockFunding {
  depositId: string;
  depositNo: string;
  source: string;
  fromAccountId: string | null;
  fromAccountNo: string | null;
  amount: string;
  reference: string | null;
  payoutBankAccount: MockBankAccount | null;
  reason: string | null;
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
export function refuseIfAny(what: string, problems: string[]): void {
  const distinct = [...new Set(problems)];
  if (distinct.length) throw new HttpProblem(422, 'Validation failed', `${what}: ${distinct.join('; ')}`, { problems: distinct }, PROBLEM_BASE + 'validation');
}
export const plain = (units: bigint) => {
  const s = fromUnits(units, 4).replace(/\.?0+$/, '');
  return s === '' || s === '-' ? '0' : s;
};

/** Checkers a deposit money request needs, as the tenant's starting rules (tenant V30). */
export function depositCheckers(payload: ApprovalPayload, amount: string | null): number | null {
  if (payload.kind === 'DEPOSIT_HOLD' || payload.kind === 'TDS_DECLARATION') return 1;
  if (payload.kind === 'DEPOSIT_TXN') return payload.txn.type === 'REVERSE' || !amount || toUnits(amount) >= toUnits(DEPOSIT_CHECKER_FROM) ? 1 : 0;
  if (payload.kind === 'DEPOSIT_LIFECYCLE') return 1;
  if (payload.kind === 'DEPOSIT_FUNDING') return payload.funding.reason !== null || !amount || toUnits(amount) >= toUnits(DEPOSIT_CHECKER_FROM) ? 1 : 0;
  if (payload.kind === 'DEPOSIT_INSTALMENT') return !amount || toUnits(amount) >= toUnits(DEPOSIT_CHECKER_FROM) ? 1 : 0;
  return null;
}

// ---------- balances, holds, checks ----------

export function balanceOf(db: MockDb, accountId: string): bigint {
  return toUnits(db.deposits.balances[accountId] ?? '0');
}

export function activeHolds(db: MockDb, accountId: string): MockHold[] {
  return db.deposits.holds.filter((h) => h.accountId === accountId && h.status === 'ACTIVE');
}

const liens = (holds: MockHold[]) => holds.filter((h) => h.kind === 'LIEN').reduce((s, h) => s + toUnits(h.amount ?? '0'), 0n);
const DESCRIBE: Record<string, string> = { DEBIT_FREEZE: 'debit freeze', CREDIT_FREEZE: 'credit freeze', TOTAL_FREEZE: 'total freeze', LIEN: 'lien' };

/** The balance figures an account's detail shows. */
export function balanceView(db: MockDb, accountId: string): Pick<DepositAccount, 'balance' | 'lienAmount' | 'availableBalance' | 'freezes'> {
  const bal = balanceOf(db, accountId);
  const holds = activeHolds(db, accountId);
  const l = liens(holds);
  return {
    balance: plain(bal),
    lienAmount: plain(l),
    availableBalance: plain(bal - l > 0n ? bal - l : 0n),
    freezes: holds.filter((h) => h.kind !== 'LIEN').map((h) => h.kind as 'DEBIT_FREEZE').sort(),
  };
}

export const onHold = (db: MockDb, accountId: string) => activeHolds(db, accountId).length > 0;

/** As the engine's AccountHolds.checkMovement. */
export function movementProblems(db: MockDb, a: DepositAccount, direction: 'DEBIT' | 'CREDIT', amount: string | null | undefined, correction: boolean): string[] {
  const out: string[] = [];
  if (a.family === 'CASA' && a.status !== 'ACTIVE') out.push(`account ${a.accountNo} is ${a.status}`);
  if (amount === null || amount === undefined || amount === '' || !/^\d+(\.\d+)?$/.test(String(amount)) || Number(amount) <= 0) {
    out.push('the amount must be above zero');
    return out;
  }
  const amt = String(amount);
  if (/\.\d{3,}$/.test(amt.replace(/0+$/, ''))) out.push('the amount has more than two decimals (paise)');
  const holds = activeHolds(db, a.id!);
  if (!correction) {
    for (const h of holds) {
      if (h.kind === 'TOTAL_FREEZE' || (h.kind === 'DEBIT_FREEZE' && direction === 'DEBIT') || (h.kind === 'CREDIT_FREEZE' && direction === 'CREDIT')) {
        out.push(`the account is under a ${DESCRIBE[h.kind!]}${h.reason ? ` (${h.reason})` : ''}`);
      }
    }
  }
  if (direction === 'DEBIT') {
    const bal = balanceOf(db, a.id!);
    const after = bal - toUnits(amt);
    const l = liens(holds);
    if (after < 0n) out.push(`the balance is ${plain(bal)}; ${plain(toUnits(amt))} would take it below zero`);
    else if (after < l) out.push(`only ${plain(bal - l > 0n ? bal - l : 0n)} is available: ${plain(l)} is held under lien`);
  }
  return out;
}

function sealBank(b: BankAccountInput | null | undefined, problems: string[]): MockBankAccount | null {
  if (!b) return null;
  const name = (b.holderName ?? '').trim();
  const number = (b.accountNumber ?? '').replace(/ /g, '');
  const ifsc = (b.ifsc ?? '').trim().toUpperCase();
  const before = problems.length;
  if (!name || name.length > 100) problems.push("the account holder's name is required (at most 100 characters)");
  if (!/^[A-Za-z0-9]{6,35}$/.test(number)) problems.push('the bank account number is 6 to 35 letters or digits');
  if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(ifsc)) problems.push('the IFSC is not a valid IFSC');
  return problems.length > before ? null : { last4: number.slice(-4), ifsc };
}

export function text(v: string | null | undefined, max: number, field: string, problems: string[]): string | null {
  if (!v || !v.trim()) return null;
  const t = v.trim();
  if (t.length > max) problems.push(`the ${field} is longer than ${max} characters`);
  return t;
}

export function findAccount(db: MockDb, id: string, family: 'CASA' | 'TERM_DEPOSIT' | null): DepositAccount {
  const a = db.deposits.accounts.find((x) => x.id === id && (!family || x.family === family));
  if (!a) throw notFound(family === 'TERM_DEPOSIT' ? `term deposit ${id}` : `account ${id}`);
  return a;
}

// ---------- writing ----------

export function move(db: MockDb, a: DepositAccount, kind: string, direction: 'DEBIT' | 'CREDIT', amount: string, lotId: string, extra: Partial<MockMovement>, maker: string, approvalId: string | null): MockMovement {
  const bal = balanceOf(db, a.id!) + (direction === 'CREDIT' ? toUnits(amount) : -toUnits(amount));
  if (bal < 0n) throw conflict('Conflict', `account ${a.accountNo}: the balance would fall below zero`);
  db.deposits.balances[a.id!] = fromUnits(bal, 4);
  const m: MockMovement = {
    id: uuid(), seq: ++db.deposits.movementSeq, accountId: a.id!, lotId, businessDate: db.businessDate, valueDate: db.businessDate, kind: kind as 'CASH_DEPOSIT',
    direction, amount: plain(toUnits(amount)), balanceAfter: plain(bal), reference: null, narration: null, counterpartyAccountNo: null, bankAccountMasked: null,
    bankIfsc: null, reverses: null, reversedBy: null, reversalReason: null, createdBy: maker, approvalId, createdAt: new Date().toISOString(), ...extra,
  };
  db.deposits.movements.push(m);
  return m;
}

function reverseLot(db: MockDb, m: MockMovement, reason: string, maker: string, approvalId: string | null): MockMovement {
  const lot = uuid();
  let mine: MockMovement | null = null;
  for (const each of db.deposits.movements.filter((x) => x.lotId === m.lotId && x.kind !== 'REVERSAL')) {
    const a = findAccount(db, each.accountId, null);
    const r = move(db, a, 'REVERSAL', each.direction === 'DEBIT' ? 'CREDIT' : 'DEBIT', each.amount!, lot, { reverses: each.id!, reversalReason: reason }, maker, approvalId);
    each.reversedBy = r.id!;
    if (each.id === m.id) mine = r;
  }
  return mine!;
}

function reversalProblems(db: MockDb, m: MockMovement): string[] {
  if (m.kind === 'REVERSAL') return ['a reversal is not reversed'];
  if (m.reversedBy) return ['the movement is already reversed'];
  if (m.kind === 'FUNDING' || m.kind === 'FUNDING_PAID') return ['this movement funded a term deposit: reverse the funding of the deposit instead'];
  if (m.gatewayStatus) return ['this transfer was sent through the payment gateway: the money comes back only when the bank fails or returns it'];
  if (['INTEREST_CREDIT', 'INTEREST_PAYOUT', 'TAX_DEDUCTED', 'INTEREST_RECOVERY'].includes(m.kind ?? '')) return ['interest credited or paid out, and tax deducted, by the day-end are not reversed by hand'];
  if (db.deposits.movements.some((x) => x.lotId === m.lotId && (x.kind === 'CLOSURE' || x.kind === 'RENEWAL'))) return ['a closure or a renewal is not reversed; the account is closed'];
  const out: string[] = [];
  for (const each of db.deposits.movements.filter((x) => x.lotId === m.lotId)) {
    const a = findAccount(db, each.accountId, null);
    for (const p of movementProblems(db, a, each.direction === 'DEBIT' ? 'CREDIT' : 'DEBIT', each.amount, true)) out.push(`account ${a.accountNo}: ${p}`);
  }
  return out;
}

function fundingProblems(d: DepositAccount): string[] {
  const out: string[] = [];
  if (d.status !== 'BOOKED') out.push(`deposit ${d.accountNo} is ${d.status}; only a booked deposit is funded`);
  return out;
}

const SOURCE_WORD: Record<string, string> = { ACCOUNT: 'from account', CASH: 'in cash', BANK_TRANSFER: 'by bank transfer', GATEWAY: 'through a payment link' };
const SOURCE_OF: Record<string, string> = Object.fromEntries(Object.entries(SOURCE_WORD).map(([k, v]) => [v, k]));

/** As RecurringDeposit.latePenalty: Rs per 100 of the instalment for each month or part of a month late. */
export function rdLateCharge(instalment: string, dueDate: string, paidOn: string, per100: string): bigint {
  if (paidOn <= dueDate) return 0n;
  let months = 0;
  while (addMonths(dueDate, months) < paidOn) months += 1;
  // per100 has at most two decimals: instalment x per100 x months / 100, to the paisa
  const raw = toUnits(instalment) * toUnits(per100) * BigInt(months);
  const den = 10000n * 100n * 100n; // to whole paise (100 units), half up
  return (raw / den + (raw % den * 2n >= den ? 1n : 0n)) * 100n;
}

/** The instalments of a recurring deposit, from its movements: the funding is instalment 1 (RecurringDeposits.schedule). */
export function rdSchedule(db: MockDb, d: DepositAccount) {
  const t = d.termDeposit!;
  const product = db.deposits.products.find((p) => p.code === d.productCode);
  const per100 = product?.rdLateChargePer100 ?? '0';
  const mine = db.deposits.movements.filter((m) => m.accountId === d.id && !m.reversedBy && (m.kind === 'FUNDING' || m.kind === 'RD_INSTALMENT')).sort((x, y) => x.seq! - y.seq!);
  const paid = mine.map((m) => {
    const late = db.deposits.movements.find((x) => x.lotId === m.lotId && x.kind === 'RD_LATE_CHARGE');
    const word = (m.narration ?? '').split(', ')[1] ?? '';
    return { paidOn: m.businessDate!, lateCharge: late?.amount ?? '0', source: m.kind === 'FUNDING' ? t.fundingSource ?? null : SOURCE_OF[word] ?? null, amount: m.amount! };
  });
  const rows: NonNullable<RdInstalments['schedule']> = [];
  let next: RdInstalments['next'] = null;
  let overdue = 0;
  if (t.startDate) {
    for (let n = 1; n <= (t.tenureMonths ?? 0); n++) {
      const due = addMonths(t.startDate, n - 1);
      const p = paid[n - 1];
      const status = p ? 'PAID' : due < db.businessDate ? 'OVERDUE' : 'DUE';
      if (status === 'OVERDUE') overdue += 1;
      rows.push({ number: n, dueDate: due, amount: plain(toUnits(t.principal!)), paidOn: p?.paidOn ?? null, lateCharge: p ? p.lateCharge : null, source: p?.source ?? null, status });
      if (!p && !next) {
        const charge = rdLateCharge(t.principal!, due, db.businessDate, per100);
        next = { number: n, dueDate: due, amount: plain(toUnits(t.principal!)), lateCharge: plain(charge), total: plain(toUnits(t.principal!) + charge),
          payableNow: d.status === 'ACTIVE' && !!t.maturityDate && db.businessDate < t.maturityDate };
      }
    }
  }
  const sum = (xs: string[]) => xs.reduce((s, x) => s + toUnits(x), 0n);
  return { instalment: plain(toUnits(t.principal!)), instalments: t.tenureMonths ?? 0, lateChargePer100: per100, paidCount: paid.length, paidIn: plain(sum(paid.map((p) => p.amount))),
    lateChargesPaid: plain(sum(paid.map((p) => p.lateCharge))), overdueCount: overdue, next, schedule: rows } satisfies RdInstalments;
}

function instalmentProblems(db: MockDb, d: DepositAccount, next: RdInstalments['next'], from: DepositAccount | null, total: string): string[] {
  const out: string[] = [];
  if (d.status !== 'ACTIVE') out.push(`recurring deposit ${d.accountNo} is ${d.status}; instalments are paid while it is active`);
  if (!next) out.push(`every instalment of recurring deposit ${d.accountNo} is paid`);
  else if (!next.payableNow && d.status === 'ACTIVE') out.push(`recurring deposit ${d.accountNo} has reached its maturity date`);
  if (from) {
    if (!(from.holders ?? []).some((h) => h.customerId === d.holders?.[0]?.customerId)) out.push(`account ${from.accountNo} is not held by the depositor`);
    out.push(...movementProblems(db, from, 'DEBIT', total, false).map((x) => `account ${from.accountNo}: ${x}`));
  }
  return out;
}

function fundingReversalProblems(db: MockDb, d: DepositAccount): string[] {
  const m = db.deposits.movements.find((x) => x.accountId === d.id && x.kind === 'FUNDING' && !x.reversedBy);
  if (d.status !== 'ACTIVE' || !m) return [`deposit ${d.accountNo} is ${d.status}; only a funded deposit has a funding to reverse`];
  const out: string[] = [];
  for (const each of db.deposits.movements.filter((x) => x.lotId === m.lotId)) {
    const a = findAccount(db, each.accountId, null);
    for (const p of movementProblems(db, a, each.direction === 'DEBIT' ? 'CREDIT' : 'DEBIT', each.amount, true)) out.push(`account ${a.accountNo}: ${p}`);
  }
  return out;
}

const addMonths = (iso: string, months: number) => {
  const [y, m, d] = iso.split('-').map(Number);
  const total = y * 12 + (m - 1) + months;
  const ny = Math.floor(total / 12);
  const nm = (total % 12) + 1;
  const last = new Date(Date.UTC(ny, nm, 0)).getUTCDate();
  return `${ny}-${String(nm).padStart(2, '0')}-${String(Math.min(d, last)).padStart(2, '0')}`;
};
const addDays = (iso: string, days: number) => new Date(Date.parse(iso + 'T00:00:00Z') + days * 86_400_000).toISOString().slice(0, 10);

// ---------- approvals ----------

export function applyDepositMoneyApproval(db: MockDb, p: ApprovalPayload, approval: { id?: string; entityId?: string | null; maker?: string; appliedRef?: string | null }, checker: string, at: string): boolean {
  const maker = approval.maker ?? checker;
  const id = approval.id ?? null;
  if (p.kind === 'DEPOSIT_TXN') {
    const t = p.txn;
    if (t.type === 'REVERSE') {
      const m = db.deposits.movements.find((x) => x.id === t.movementId);
      if (!m) throw notFound(`movement ${t.movementId}`);
      refuseIfAny('the movement cannot be reversed', reversalProblems(db, m));
      const back = reverseLot(db, m, t.reason!, maker, id);
      approval.appliedRef = back.id!;
      for (const c of db.deposits.charges.filter((x) => x.movementId === m.id && x.status === 'ISSUED')) {
        Object.assign(c, { status: 'CANCELLED', cancelledOn: db.businessDate, creditNoteNo: `GST/DEPCN/${String(db.deposits.charges.filter((y) => y.creditNoteNo).length + 1).padStart(5, '0')}` });
      }
    } else if (t.type === 'TRANSFER') {
      const from = findAccount(db, t.accountId, 'CASA');
      const to = findAccount(db, t.toAccountId!, 'CASA');
      refuseIfAny('the transfer cannot be made', [...movementProblems(db, from, 'DEBIT', t.amount, false), ...movementProblems(db, to, 'CREDIT', t.amount, false).map((x) => `account ${to.accountNo}: ${x}`)]);
      const lot = uuid();
      const out = move(db, from, 'TRANSFER_OUT', 'DEBIT', t.amount, lot, { narration: t.narration, counterpartyAccountNo: to.accountNo }, maker, id);
      move(db, to, 'TRANSFER_IN', 'CREDIT', t.amount, lot, { narration: t.narration, counterpartyAccountNo: from.accountNo }, maker, id);
      approval.appliedRef = out.id!;
    } else {
      const a = findAccount(db, t.accountId, 'CASA');
      const credit = t.type === 'CASH_DEPOSIT' || t.type === 'BANK_TRANSFER_IN';
      refuseIfAny('the transaction cannot be made', movementProblems(db, a, credit ? 'CREDIT' : 'DEBIT', t.amount, false));
      approval.appliedRef = move(db, a, t.type, credit ? 'CREDIT' : 'DEBIT', t.amount, uuid(), {
        reference: t.reference, narration: t.narration, bankAccountMasked: t.bankAccount ? `XXXXXXXX${t.bankAccount.last4}` : null, bankIfsc: t.bankAccount?.ifsc ?? null,
        ...(t.viaGateway && t.type === 'BANK_TRANSFER_OUT' ? { gatewayStatus: 'SENT' as const, gatewayUtr: null } : {}),
      }, maker, id).id!;
    }
    appendAudit(db, at, checker, 'DEPOSIT_MONEY_POSTED', 'DEPOSIT_TXN', t.accountNo, { type: t.type, amount: t.amount });
    return true;
  }
  if (p.kind === 'DEPOSIT_HOLD') {
    const h = p.hold;
    if (h.holdId) {
      const hold = db.deposits.holds.find((x) => x.id === h.holdId);
      if (!hold || hold.status !== 'ACTIVE') throw conflict('Conflict', 'the hold is already released');
      Object.assign(hold, { status: 'RELEASED', releasedOn: db.businessDate, releasedBy: maker, releaseReason: h.reason });
      approval.appliedRef = hold.id!;
    } else {
      const a = findAccount(db, h.accountId, null);
      refuseIfAny('the hold cannot be placed', newHoldProblems(db, a, h.kind, h.amount, h.reason));
      const hold: MockHold = { id: uuid(), accountId: a.id!, kind: h.kind as 'LIEN', amount: h.amount, reason: h.reason, reference: h.reference, status: 'ACTIVE',
        placedOn: db.businessDate, placedBy: maker, releasedOn: null, releasedBy: null, releaseReason: null };
      db.deposits.holds.unshift(hold);
      approval.appliedRef = hold.id!;
    }
    return true;
  }
  if (p.kind === 'TDS_DECLARATION') {
    const d = p.declaration;
    if (d.declarationId) {
      const x = db.deposits.declarations.find((y) => y.id === d.declarationId);
      if (!x || x.status !== 'ACTIVE') throw conflict('Conflict', 'the declaration is already revoked');
      Object.assign(x, { status: 'REVOKED', revokedOn: db.businessDate, revokedBy: maker, revokeReason: d.reason });
      approval.appliedRef = x.id!;
    } else {
      if (db.deposits.declarations.some((y) => y.customerId === d.customerId && y.taxYear === d.taxYear && y.status === 'ACTIVE')) {
        throw conflict('Conflict', `Form 121 for ${taxYearLabel(d.taxYear)} is already recorded for this customer`);
      }
      const x: MockDeclaration = { id: uuid(), customerId: d.customerId, taxYear: d.taxYear, form: 'FORM_121', reference: d.reference, receivedOn: d.receivedOn,
        status: 'ACTIVE', recordedBy: maker, revokedOn: null, revokedBy: null, revokeReason: null, createdAt: at };
      db.deposits.declarations.unshift(x);
      approval.appliedRef = x.id!;
    }
    return true;
  }
  if (p.kind === 'DEPOSIT_FUNDING') {
    const f = p.funding;
    const d = findAccount(db, f.depositId, 'TERM_DEPOSIT');
    const t = d.termDeposit!;
    if (f.reason !== null) {
      refuseIfAny('the funding cannot be reversed', fundingReversalProblems(db, d));
      const m = db.deposits.movements.find((x) => x.accountId === d.id && x.kind === 'FUNDING' && !x.reversedBy)!;
      reverseLot(db, m, f.reason, maker, id);
      Object.assign(t, { startDate: null, maturityDate: null, funded: false, fundingSource: null, fundingAccountNo: null, fundingReference: null,
        payoutBankAccountMasked: null, payoutBankIfsc: null, figuresFrom: db.businessDate, figuresMaturityDate: addDays(addMonths(db.businessDate, t.tenureMonths!), t.tenureDays!) });
      d.status = 'BOOKED';
    } else {
      const problems = fundingProblems(d);
      const from = f.fromAccountId ? findAccount(db, f.fromAccountId, 'CASA') : null;
      if (from) problems.push(...movementProblems(db, from, 'DEBIT', f.amount, false).map((x) => `account ${from.accountNo}: ${x}`));
      refuseIfAny('the deposit cannot be funded', problems);
      const lot = uuid();
      move(db, d, 'FUNDING', 'CREDIT', f.amount, lot, { reference: f.reference, counterpartyAccountNo: from?.accountNo ?? null, narration: t.recurring ? 'Instalment 1' : null }, maker, id);
      if (from) move(db, from, 'FUNDING_PAID', 'DEBIT', f.amount, lot, { reference: f.reference, counterpartyAccountNo: d.accountNo }, maker, id);
      const maturity = addDays(addMonths(db.businessDate, t.tenureMonths!), t.tenureDays!);
      Object.assign(t, { startDate: db.businessDate, maturityDate: maturity, funded: true, figuresFrom: db.businessDate, figuresMaturityDate: maturity, fundingSource: f.source as 'CASH',
        fundingAccountNo: from?.accountNo ?? null, fundingReference: f.reference,
        payoutBankAccountMasked: f.payoutBankAccount ? `XXXXXXXX${f.payoutBankAccount.last4}` : null, payoutBankIfsc: f.payoutBankAccount?.ifsc ?? null });
      d.status = 'ACTIVE';
    }
    approval.appliedRef = d.accountNo!;
    appendAudit(db, at, checker, f.reason !== null ? 'TERM_DEPOSIT_FUNDING_REVERSED' : 'TERM_DEPOSIT_FUNDED', 'TERM_DEPOSIT', d.id!, { accountNo: d.accountNo });
    return true;
  }
  if (p.kind === 'DEPOSIT_INSTALMENT') {
    const i = p.instalment;
    const d = findAccount(db, i.depositId, 'TERM_DEPOSIT');
    const from = i.fromAccountId ? findAccount(db, i.fromAccountId, 'CASA') : null;
    const next = rdSchedule(db, d).next;
    if (!next || next.number !== i.number) throw conflict('Conflict', `instalment ${i.number} of deposit ${d.accountNo} is already paid`);
    const total = plain(toUnits(i.amount) + toUnits(i.lateCharge));
    refuseIfAny('the instalment cannot be paid', instalmentProblems(db, d, next, from, total));
    const lot = uuid();
    const m = move(db, d, 'RD_INSTALMENT', 'CREDIT', i.amount, lot, { reference: i.reference, counterpartyAccountNo: from?.accountNo ?? null, narration: `Instalment ${i.number}, ${SOURCE_WORD[i.source]}` }, maker, id);
    if (from) move(db, from, 'FUNDING_PAID', 'DEBIT', i.amount, lot, { reference: i.reference, counterpartyAccountNo: d.accountNo }, maker, id);
    if (from && toUnits(i.lateCharge) > 0n) move(db, from, 'RD_LATE_CHARGE', 'DEBIT', i.lateCharge, lot, { narration: `Late charge on instalment ${i.number} of ${d.accountNo}` }, maker, id);
    approval.appliedRef = m.id!;
    appendAudit(db, at, checker, 'RD_INSTALMENT_PAID', 'TERM_DEPOSIT', d.id!, { accountNo: d.accountNo, number: i.number });
    return true;
  }
  return false;
}

function newHoldProblems(db: MockDb, a: DepositAccount, kind: string, amount: string | null, reason: string | null): string[] {
  const out: string[] = [];
  if (a.family === 'TERM_DEPOSIT' && kind !== 'LIEN') out.push('a term deposit takes a lien, not a freeze');
  if (a.status !== 'ACTIVE') out.push(`account ${a.accountNo} is ${a.status}; a hold is placed on an active account${a.status === 'BOOKED' ? ' (fund the deposit first)' : ''}`);
  if (!reason || !reason.trim()) out.push('a reason is required');
  const holds = activeHolds(db, a.id!);
  if (kind === 'LIEN') {
    if (!amount || Number(amount) <= 0) out.push('a lien needs an amount above zero');
    else {
      const free = balanceOf(db, a.id!) - liens(holds);
      if (toUnits(amount) > free) out.push(`a lien of ${plain(toUnits(amount))} is more than the available balance of ${plain(free > 0n ? free : 0n)}`);
    }
  } else {
    if (amount) out.push('a freeze has no amount');
    if (holds.some((h) => h.kind === kind)) out.push(`the account is already under a ${DESCRIBE[kind]}`);
  }
  return out;
}

// ---------- the gateway simulator (P4-2b) ----------

const endsInOnePaisa = (amount: string | undefined | null) => /\.01$/.test(fromUnits(toUnits(amount ?? '0'), 2));

/** Transfers waiting at the gateway are paid, or fail and come back (amount ending in .01). */
function settleTransfers(db: MockDb, accountId: string): void {
  for (const m of db.deposits.movements.filter((x) => x.accountId === accountId && x.gatewayStatus === 'SENT')) {
    if (endsInOnePaisa(m.amount)) {
      reverseLot(db, m, 'payout FAILED: payout failed (simulated)', 'system', null);
      m.gatewayStatus = 'RETURNED';
    } else {
      m.gatewayStatus = 'PAID';
      m.gatewayUtr = `SIMUTR${String(m.seq).padStart(8, '0')}`;
    }
  }
}

/** Links waiting to be paid are paid: a savings or current account is credited, a booked deposit funded; else unmatched. */
function payOrders(db: MockDb, accountId: string): void {
  for (const o of db.deposits.orders.filter((x) => x.depositAccountId === accountId && x.status === 'CREATED')) {
    if (endsInOnePaisa(o.amount)) {
      o.status = 'FAILED';
      continue;
    }
    o.status = 'PAID';
    const a = findAccount(db, accountId, null);
    const ref = `SIMPAY${o.reference}`.slice(0, 60);
    if (o.purpose === 'CREDIT') {
      if (movementProblems(db, a, 'CREDIT', o.amount, false).length === 0) move(db, a, 'BANK_TRANSFER_IN', 'CREDIT', o.amount!, uuid(), { reference: ref, narration: 'Payment link' }, 'system', null);
    } else if (a.status === 'BOOKED' && a.termDeposit) {
      const t = a.termDeposit;
      move(db, a, 'FUNDING', 'CREDIT', t.principal!, uuid(), { reference: ref, narration: 'Payment link' }, 'system', null);
      const maturity = addDays(addMonths(db.businessDate, t.tenureMonths!), t.tenureDays!);
      Object.assign(t, { startDate: db.businessDate, maturityDate: maturity, funded: true, figuresFrom: db.businessDate, figuresMaturityDate: maturity, fundingSource: 'GATEWAY' as const,
        fundingAccountNo: null, fundingReference: ref });
      a.status = 'ACTIVE';
    }
  }
}

// ---------- tax at source (P4-3b) ----------

const taxYearOf = (iso: string) => (Number(iso.slice(5, 7)) >= 4 ? Number(iso.slice(0, 4)) : Number(iso.slice(0, 4)) - 1);
const taxYearLabel = (y: number) => `${y}-${String(y + 1).slice(2)}`;

// ---------- routes ----------

export function registerDepositMoneyRoutes(db: MockDb, r: Registrar, module: (name: 'CASA' | 'TD') => void): void {
  const { on, require, propose } = r;
  const money = (v: unknown) => (v === null || v === undefined || v === '' ? null : String(v));

  on('POST', '/api/v1/casa/accounts/{id}/transactions', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const b = (body ?? {}) as Partial<DepositTransactionInput>;
    const types = ['BANK_TRANSFER_IN', 'BANK_TRANSFER_OUT', 'CASH_DEPOSIT', 'CASH_WITHDRAWAL'];
    if (!b.type || !types.includes(b.type)) throw invalid(`type is one of ${types.join(', ')}`);
    const a = findAccount(db, params.id, 'CASA');
    const credit = b.type === 'CASH_DEPOSIT' || b.type === 'BANK_TRANSFER_IN';
    const bank = b.type.startsWith('BANK_');
    const gateway = b.viaGateway === true;
    const problems: string[] = [];
    const reference = text(b.reference, 60, 'reference', problems);
    const narration = text(b.narration, 200, 'narration', problems);
    if (gateway && b.type !== 'BANK_TRANSFER_OUT') problems.push('only a bank transfer out is sent through the payment gateway');
    if (bank && !gateway && !reference) problems.push("the bank's reference (UTR) is required for a bank transfer");
    if (b.type === 'BANK_TRANSFER_OUT' && !b.bankAccount) problems.push('name the bank account the money is sent to');
    let party: MockBankAccount | null = null;
    if (b.bankAccount) {
      if (!bank) problems.push('only a bank transfer names an account at another bank');
      else party = sealBank(b.bankAccount, problems);
    }
    const amount = money(b.amount);
    problems.push(...movementProblems(db, a, credit ? 'CREDIT' : 'DEBIT', amount, false));
    refuseIfAny('the transaction cannot be made', problems);
    assertWithinLimit(db, user, credit ? 'DEPOSIT_CREDIT' : 'DEPOSIT_DEBIT', amount!, { record: true });
    const txn: MockTxn = { type: b.type, accountId: a.id!, accountNo: a.accountNo!, toAccountId: null, toAccountNo: null, amount: amount!, reference, narration, bankAccount: party, movementId: null, reason: null,
      ...(gateway ? { viaGateway: true } : {}) };
    return propose(user, 'DEPOSIT_TXN', b.type, { kind: 'DEPOSIT_TXN', txn }, { ...txn }, null, a.id!, amount);
  });

  on('POST', '/api/v1/casa/transfers', ({ user, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const b = (body ?? {}) as Partial<DepositTransferInput>;
    if (!b.fromAccountId || !b.toAccountId) throw invalid('fromAccountId and toAccountId are required');
    const from = findAccount(db, b.fromAccountId, 'CASA');
    const to = findAccount(db, b.toAccountId, 'CASA');
    const problems: string[] = [];
    if (from.id === to.id) problems.push('an account cannot transfer to itself');
    const narration = text(b.narration, 200, 'narration', problems);
    const amount = money(b.amount);
    problems.push(...movementProblems(db, from, 'DEBIT', amount, false));
    if (amount && Number(amount) > 0) problems.push(...movementProblems(db, to, 'CREDIT', amount, false).map((x) => `account ${to.accountNo}: ${x}`));
    refuseIfAny('the transfer cannot be made', problems);
    assertWithinLimit(db, user, 'DEPOSIT_DEBIT', amount!, { record: true });
    const txn: MockTxn = { type: 'TRANSFER', accountId: from.id!, accountNo: from.accountNo!, toAccountId: to.id!, toAccountNo: to.accountNo!, amount: amount!, reference: null, narration, bankAccount: null, movementId: null, reason: null };
    return propose(user, 'DEPOSIT_TXN', 'TRANSFER', { kind: 'DEPOSIT_TXN', txn }, { ...txn }, null, from.id!, amount);
  });

  on('POST', '/api/v1/casa/movements/{movementId}/reverse', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const m = db.deposits.movements.find((x) => x.id === params.movementId);
    if (!m) throw notFound(`movement ${params.movementId}`);
    const a = findAccount(db, m.accountId, null);
    const problems: string[] = [];
    const reason = text(((body ?? {}) as { reason?: string }).reason, 500, 'reason', problems);
    if (!reason) problems.push('a reason is required');
    problems.push(...reversalProblems(db, m));
    refuseIfAny('the movement cannot be reversed', problems);
    const txn: MockTxn = { type: 'REVERSE', accountId: a.id!, accountNo: a.accountNo!, toAccountId: null, toAccountNo: null, amount: m.amount!, reference: null, narration: null, bankAccount: null, movementId: m.id!, reason };
    return propose(user, 'DEPOSIT_TXN', 'REVERSE', { kind: 'DEPOSIT_TXN', txn }, { ...txn }, null, m.id!, m.amount!);
  });

  for (const [base, family, mod, view, hold] of [['casa', 'CASA', 'CASA', P.casaView, P.casaHold], ['deposits', 'TERM_DEPOSIT', 'TD', P.depositView, P.depositHold]] as const) {
    on('GET', `/api/v1/${base}/accounts/{id}/movements`, ({ user, params, url }) => {
      module(mod);
      require(user, view);
      findAccount(db, params.id, family);
      settleTransfers(db, params.id);
      payOrders(db, params.id);
      const from = url.searchParams.get('from');
      const to = url.searchParams.get('to');
      return ok(db.deposits.movements.filter((m) => m.accountId === params.id && (!from || m.businessDate! >= from) && (!to || m.businessDate! <= to))
        .sort((x, y) => y.seq! - x.seq!).map(({ accountId: _a, lotId: _l, ...m }) => m));
    });
    on('GET', `/api/v1/${base}/accounts/{id}/holds`, ({ user, params }) => {
      module(mod);
      require(user, view);
      findAccount(db, params.id, family);
      return ok(db.deposits.holds.filter((h) => h.accountId === params.id).sort((x, y) => (x.status === y.status ? 0 : x.status === 'ACTIVE' ? -1 : 1)).map(({ accountId: _a, ...h }) => h));
    });
    // P4-3: there is no deposit day-end in mock mode, so nothing is accrued; a term deposit shows its interest periods
    on('GET', `/api/v1/${base}/accounts/{id}/interest`, ({ user, params }) => {
      module(mod);
      require(user, view);
      const a = findAccount(db, params.id, family);
      const t = a.termDeposit;
      const schedule = t?.funded && t.startDate && t.recurring
        ? rdPeriods(t.startDate, t.tenureMonths ?? 0, t.principal!, t.ratePercent!, t.interestPeriodMonths ?? 3, t.rounding ?? 'PAISE_HALF_UP')
            .map((p) => ({ number: p.number, from: p.from, creditOn: p.to, balance: plain(p.balance), interest: plain(p.interest), status: 'DUE' as const }))
        : t?.funded && t.startDate && t.maturityDate
        ? periods({ principal: t.principal!, ratePercent: t.ratePercent!, start: t.startDate, maturity: t.maturityDate, periodMonths: t.interestPeriodMonths ?? 0,
            cumulative: !!t.cumulative, dayCount: t.dayCount ?? 'ACTUAL_365', rounding: t.rounding ?? 'PAISE_HALF_UP' })
            .map((p) => ({ number: p.number, from: p.from, creditOn: p.to, balance: plain(p.balance), interest: plain(p.interest), status: 'DUE' as const }))
        : [];
      return ok({ accruedThrough: null, accrued: '0', credited: '0', lastCreditOn: null, ...(t?.recurring ? { monthsWorkedOut: 0 } : {}),
        nextCreditDate: family === 'CASA' ? a.nextInterestDate ?? null : schedule[0]?.creditOn ?? null, schedule, events: [] });
    });
    on('GET', `/api/v1/${base}/accounts/{id}/collection-orders`, ({ user, params }) => {
      module(mod);
      require(user, view);
      findAccount(db, params.id, family);
      payOrders(db, params.id);
      return ok(db.deposits.orders.filter((o) => o.depositAccountId === params.id).sort((x, y) => (y.createdAt ?? '').localeCompare(x.createdAt ?? '')));
    });
    on('POST', `/api/v1/${base}/accounts/{id}/collection-orders`, ({ user, params, body }) => {
      module(mod);
      require(user, family === 'CASA' ? P.casaTransact : P.depositFund);
      const a = findAccount(db, params.id, family);
      const b = (body ?? {}) as { amount?: string | number | null; methods?: string[]; returnUrl?: string | null; payoutBankAccount?: BankAccountInput | null };
      let amount = money(b.amount);
      if ((b.methods ?? []).some((m) => !['UPI', 'CARD', 'NETBANKING'].includes(m))) throw invalid('methods may contain UPI, CARD and NETBANKING');
      if (family === 'TERM_DEPOSIT') {
        const t = a.termDeposit!;
        if (a.status !== 'BOOKED') throw conflict('Conflict', `deposit ${a.accountNo} is ${a.status}: only a booked deposit is funded`);
        if (amount === null) amount = t.principal!;
        else if (toUnits(amount) !== toUnits(t.principal!)) throw invalid(`a term deposit is funded with its principal of ${t.principal}`);
        if (db.deposits.orders.some((o) => o.depositAccountId === a.id && o.status === 'CREATED')) throw conflict('Conflict', `deposit ${a.accountNo} already has a payment link waiting to be paid`);
        if (t.payoutTo === 'BANK_ACCOUNT') {
          if (!b.payoutBankAccount && !t.payoutBankAccountMasked) throw invalid('the deposit is paid out to a bank account: give payoutBankAccount');
          if (b.payoutBankAccount) {
            const problems: string[] = [];
            const bank = sealBank(b.payoutBankAccount, problems);
            refuseIfAny('the payout account cannot be recorded', problems);
            Object.assign(t, { payoutBankAccountMasked: `XXXXXXXX${bank!.last4}`, payoutBankIfsc: bank!.ifsc });
          }
        } else if (b.payoutBankAccount) throw invalid('the deposit is paid out to an account here; it takes no bank account');
      } else {
        if (a.status !== 'ACTIVE') throw conflict('Conflict', `account ${a.accountNo} is ${a.status}: it cannot take a payment`);
        if (!amount || !/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) throw invalid('amount must be positive with at most two decimals');
        if (b.payoutBankAccount) throw invalid('only a term deposit paid out to a bank account names one');
      }
      const at = new Date().toISOString();
      const reference = `CO${a.accountNo}N${db.deposits.orders.filter((o) => o.depositAccountId === a.id).length + 1}`;
      const o: MockDepositOrder = { id: uuid(), reference, depositAccountId: a.id!, accountNo: a.accountNo!, purpose: family === 'CASA' ? 'CREDIT' : 'FUNDING', amount: fromUnits(toUnits(amount), 2),
        methods: b.methods ?? [], provider: 'SIMULATOR', paymentUrl: `https://pay.simulator.example/l/${reference}`, status: 'CREATED',
        expiresAt: new Date(Date.parse(at) + 72 * 3600_000).toISOString(), createdBy: user.username, createdAt: at };
      db.deposits.orders.push(o);
      appendAudit(db, at, user.username, 'COLLECTION_ORDER', family === 'CASA' ? 'DEPOSIT_ACCOUNT' : 'TERM_DEPOSIT', a.accountNo!, { reference, amount: o.amount });
      return { status: 201, body: o };
    });
    on('POST', `/api/v1/${base}/accounts/{id}/holds`, ({ user, params, body }) => {
      module(mod);
      require(user, hold);
      const a = findAccount(db, params.id, family);
      const b = (body ?? {}) as Partial<DepositHoldInput>;
      if (!b.kind || !['DEBIT_FREEZE', 'CREDIT_FREEZE', 'TOTAL_FREEZE', 'LIEN'].includes(b.kind)) throw invalid('kind is one of DEBIT_FREEZE, CREDIT_FREEZE, TOTAL_FREEZE, LIEN');
      const problems: string[] = [];
      const reference = text(b.reference, 100, 'reference', problems);
      const amount = money(b.amount);
      problems.push(...newHoldProblems(db, a, b.kind, amount, b.reason ?? null));
      refuseIfAny('the hold cannot be placed', problems);
      const req: MockHoldRequest = { accountId: a.id!, accountNo: a.accountNo!, kind: b.kind, amount, reason: b.reason!.trim(), reference, holdId: null };
      return propose(user, 'DEPOSIT_HOLD', 'PLACE', { kind: 'DEPOSIT_HOLD', hold: req }, { ...req }, null, a.id!, amount);
    });
    on('POST', `/api/v1/${base}/holds/{holdId}/release`, ({ user, params, body }) => {
      module(mod);
      require(user, hold);
      const h = db.deposits.holds.find((x) => x.id === params.holdId);
      if (!h) throw notFound(`hold ${params.holdId}`);
      const a = findAccount(db, h.accountId, family);
      const problems: string[] = [];
      if (h.status !== 'ACTIVE') problems.push('the hold is already released');
      const reason = text(((body ?? {}) as { reason?: string }).reason, 500, 'reason', problems);
      if (!reason) problems.push('a reason is required');
      refuseIfAny('the hold cannot be released', problems);
      const req: MockHoldRequest = { accountId: a.id!, accountNo: a.accountNo!, kind: h.kind!, amount: h.amount ?? null, reason: reason!, reference: null, holdId: h.id! };
      return propose(user, 'DEPOSIT_HOLD', 'RELEASE', { kind: 'DEPOSIT_HOLD', hold: req }, { ...req }, { kind: h.kind, amount: h.amount, reason: h.reason, placedOn: h.placedOn }, h.id!);
    });
  }

  const taxCustomer = (id: string | null) => {
    const c = db.customers.find((x) => x.id === id);
    if (!c) throw notFound(`customer ${id}`);
    return c;
  };
  on('GET', '/api/v1/deposits/tax/declarations', ({ user, url }) => {
    module('TD');
    require(user, P.depositView);
    const c = taxCustomer(url.searchParams.get('customerId'));
    return ok(db.deposits.declarations.filter((d) => d.customerId === c.id).map(({ customerId: _c, ...d }) => d));
  });
  on('POST', '/api/v1/deposits/tax/declarations', ({ user, body }) => {
    module('TD');
    require(user, P.depositTax);
    const b = (body ?? {}) as { customerId?: string; taxYear?: number | null; receivedOn?: string | null; reference?: string | null };
    if (!b.customerId) throw invalid('customerId is required');
    const c = taxCustomer(b.customerId);
    const current = taxYearOf(db.businessDate);
    const year = b.taxYear ?? current;
    const problems: string[] = [];
    if (c.input.customerType !== 'INDIVIDUAL') problems.push('Form 121 is a declaration by an individual');
    if (!c.input.pan) problems.push("a declaration without a PAN is not valid; record the customer's PAN first");
    if (year !== current && year !== current + 1) problems.push(`a declaration is recorded for the current tax year (${taxYearLabel(current)}) or the next`);
    const reference = text(b.reference, 60, 'reference', problems);
    const receivedOn = b.receivedOn || db.businessDate;
    if (receivedOn > db.businessDate) problems.push('the date received is after the business date');
    if (db.deposits.declarations.some((d) => d.customerId === c.id && d.taxYear === year && d.status === 'ACTIVE')) problems.push(`Form 121 for ${taxYearLabel(year)} is already recorded for this customer`);
    refuseIfAny('the declaration cannot be recorded', problems);
    const declaration: MockDeclarationRequest = { customerId: c.id, customerNo: c.customerNo, taxYear: year, receivedOn, reference, declarationId: null, reason: null };
    return propose(user, 'TDS_DECLARATION', 'RECORD', { kind: 'TDS_DECLARATION', declaration }, { ...declaration }, null, c.id, null);
  });
  on('POST', '/api/v1/deposits/tax/declarations/{id}/revoke', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositTax);
    const d = db.deposits.declarations.find((x) => x.id === params.id);
    if (!d) throw notFound(`tax declaration ${params.id}`);
    const c = taxCustomer(d.customerId);
    const problems: string[] = [];
    if (d.status !== 'ACTIVE') problems.push('the declaration is already revoked');
    const reason = text(((body ?? {}) as { reason?: string }).reason, 500, 'reason', problems);
    if (!reason) problems.push('a reason is required');
    refuseIfAny('the declaration cannot be revoked', problems);
    const declaration: MockDeclarationRequest = { customerId: c.id, customerNo: c.customerNo, taxYear: d.taxYear!, receivedOn: d.receivedOn!, reference: d.reference ?? null, declarationId: d.id!, reason };
    return propose(user, 'TDS_DECLARATION', 'REVOKE', { kind: 'TDS_DECLARATION', declaration }, { ...declaration }, { taxYear: d.taxYear, receivedOn: d.receivedOn }, d.id!, null);
  });
  on('GET', '/api/v1/deposits/tax/summary', ({ user, url }) => {
    module('TD');
    require(user, P.depositView);
    const c = taxCustomer(url.searchParams.get('customerId'));
    const year = url.searchParams.get('taxYear') ? Number(url.searchParams.get('taxYear')) : taxYearOf(db.businessDate);
    const dob = c.input.dateOfBirth;
    const senior = c.input.customerType === 'INDIVIDUAL' && !!dob && year + 1 - Number(dob.slice(0, 4)) - (dob.slice(5) > '03-31' ? 1 : 0) >= 60;
    const bank = db.deposits.kind === 'BANK';
    const decl = db.deposits.declarations.find((d) => d.customerId === c.id && d.taxYear === year && d.status === 'ACTIVE');
    return ok({ customerId: c.id, customerNo: c.customerNo, name: [c.input.firstName, c.input.lastName].filter(Boolean).join(' '), taxYear: year, taxYearLabel: taxYearLabel(year),
      senior, panOnFile: !!c.input.pan, declarationId: decl?.id ?? null, threshold: bank ? (senior ? '100000' : '50000') : '10000', ratePercent: c.input.pan ? '10' : '20',
      interest: '0', tax: '0', quarters: [1, 2, 3, 4].map((quarter) => ({ quarter, interest: '0', tax: '0' })), accounts: [], credits: [] });
  });

  on('POST', '/api/v1/deposits/accounts/{id}/fund', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositFund);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    const b = (body ?? {}) as Partial<TermDepositFundingInput>;
    if (!b.source || !['ACCOUNT', 'CASH', 'BANK_TRANSFER'].includes(b.source)) throw invalid('source is one of ACCOUNT, CASH, BANK_TRANSFER');
    const t = d.termDeposit!;
    const problems = fundingProblems(d);
    const reference = text(b.reference, 60, 'reference', problems);
    if (b.source === 'BANK_TRANSFER' && !reference) problems.push("the bank's reference (UTR) is required for a bank transfer");
    let from: DepositAccount | null = null;
    if (b.source === 'ACCOUNT') {
      if (!b.fromAccountId) problems.push('name the savings or current account the deposit is funded from');
      else {
        from = findAccount(db, b.fromAccountId, 'CASA');
        if (!(from.holders ?? []).some((h) => h.customerId === d.holders?.[0]?.customerId)) problems.push(`account ${from.accountNo} is not held by the depositor`);
        problems.push(...movementProblems(db, from, 'DEBIT', t.principal, false).map((x) => `account ${from!.accountNo}: ${x}`));
      }
    } else if (b.fromAccountId) problems.push('only a funding from an account names one');
    let bank: MockBankAccount | null = null;
    if (t.payoutTo === 'BANK_ACCOUNT' && !b.payoutBankAccount) problems.push('the deposit is paid out to a bank account: record that account now');
    else if (t.payoutTo !== 'BANK_ACCOUNT' && b.payoutBankAccount) problems.push(`the deposit is paid out to account ${t.payoutAccountNo} here; it takes no bank account`);
    else bank = sealBank(b.payoutBankAccount, problems);
    refuseIfAny('the deposit cannot be funded', problems);
    assertWithinLimit(db, user, from ? 'DEPOSIT_DEBIT' : 'DEPOSIT_CREDIT', t.principal!, { record: true });
    const funding: MockFunding = { depositId: d.id!, depositNo: d.accountNo!, source: b.source, fromAccountId: from?.id ?? null, fromAccountNo: from?.accountNo ?? null,
      amount: t.principal!, reference, payoutBankAccount: bank, reason: null };
    return propose(user, 'TERM_DEPOSIT', 'FUND', { kind: 'DEPOSIT_FUNDING', funding }, { ...funding }, null, d.id!, t.principal!);
  });

  on('GET', '/api/v1/deposits/accounts/{id}/instalments', ({ user, params }) => {
    module('TD');
    require(user, P.depositView);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    if (!d.termDeposit?.recurring) throw notFound(`recurring deposit ${params.id}`);
    return ok(rdSchedule(db, d));
  });

  on('POST', '/api/v1/deposits/accounts/{id}/instalments', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositFund);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    if (!d.termDeposit?.recurring) throw invalid(`deposit ${d.accountNo} is not a recurring deposit; it takes no instalments`);
    const b = (body ?? {}) as Partial<RdInstalmentInput>;
    if (!b.source || !['ACCOUNT', 'CASH', 'BANK_TRANSFER'].includes(b.source)) throw invalid('source is one of ACCOUNT, CASH, BANK_TRANSFER');
    if (d.status === 'BOOKED') throw invalid(`recurring deposit ${d.accountNo} is booked: its first instalment is its funding`);
    const problems: string[] = [];
    const reference = text(b.reference, 60, 'reference', problems);
    if (b.source === 'BANK_TRANSFER' && !reference) problems.push("the bank's reference (UTR) is required for a bank transfer");
    let from: DepositAccount | null = null;
    if (b.source === 'ACCOUNT') {
      if (!b.fromAccountId) problems.push('name the savings or current account the instalment is paid from');
      else from = findAccount(db, b.fromAccountId, 'CASA');
    } else if (b.fromAccountId) problems.push('only an instalment from an account names one');
    const next = rdSchedule(db, d).next;
    const total = next ? next.total! : '0';
    problems.push(...instalmentProblems(db, d, next, from, total));
    refuseIfAny('the instalment cannot be paid', problems);
    assertWithinLimit(db, user, from ? 'DEPOSIT_DEBIT' : 'DEPOSIT_CREDIT', total, { record: true });
    const instalment: MockInstalment = { depositId: d.id!, depositNo: d.accountNo!, number: next!.number!, dueDate: next!.dueDate!, amount: next!.amount!, lateCharge: next!.lateCharge!,
      source: b.source, fromAccountId: from?.id ?? null, fromAccountNo: from?.accountNo ?? null, reference };
    return propose(user, 'TERM_DEPOSIT', 'INSTALMENT', { kind: 'DEPOSIT_INSTALMENT', instalment }, { ...instalment }, null, d.id!, total);
  });

  on('POST', '/api/v1/deposits/accounts/{id}/funding/reverse', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositFund);
    const d = findAccount(db, params.id, 'TERM_DEPOSIT');
    const problems: string[] = [];
    const reason = text(((body ?? {}) as { reason?: string }).reason, 500, 'reason', problems);
    if (!reason) problems.push('a reason is required');
    problems.push(...fundingReversalProblems(db, d));
    refuseIfAny('the funding cannot be reversed', problems);
    const funding: MockFunding = { depositId: d.id!, depositNo: d.accountNo!, source: d.termDeposit?.fundingSource ?? 'CASH', fromAccountId: null, fromAccountNo: null,
      amount: d.termDeposit!.principal!, reference: null, payoutBankAccount: null, reason };
    return propose(user, 'TERM_DEPOSIT', 'REVERSE_FUNDING', { kind: 'DEPOSIT_FUNDING', funding }, { ...funding }, null, d.id!, d.termDeposit!.principal!);
  });
}
