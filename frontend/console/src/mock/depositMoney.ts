/**
 * Mock of money on deposit accounts (Phase 4, P4-2): cash deposits and withdrawals, transfers, bank transfers recorded
 * with their UTR, reversals, freezes and liens, and funding a term deposit. The checks follow the backend's deposit
 * engine (AccountHolds) closely enough to try the screens; the backend is the truth. Money in and out and a funding
 * post at once below 2,00,000 (as the tenant's starting approval rules); from it upward, and for every reversal, hold and
 * funding reversal, a checker is needed. No ledger is kept here: the balances are the movements.
 */
import type {
  BankAccountInput,
  DepositAccount,
  DepositHold,
  DepositHoldInput,
  DepositMovement,
  DepositTransactionInput,
  DepositTransferInput,
  TermDepositFundingInput,
} from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { fromUnits, toUnits } from '../lib/money';
import { appendAudit, uuid, type ApprovalPayload, type MockDb } from './db';
import { assertWithinLimit } from './limits';
import { conflict, HttpProblem, notFound, PROBLEM_BASE } from './problems';

/** Below this, money in and out posts at once (the tenant's starting approval rule; not a regulatory figure). */
export const DEPOSIT_CHECKER_FROM = '200000';

export type MockMovement = DepositMovement & { accountId: string; lotId: string };
export type MockHold = DepositHold & { accountId: string };

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
function refuseIfAny(what: string, problems: string[]): void {
  const distinct = [...new Set(problems)];
  if (distinct.length) throw new HttpProblem(422, 'Validation failed', `${what}: ${distinct.join('; ')}`, { problems: distinct }, PROBLEM_BASE + 'validation');
}
const plain = (units: bigint) => {
  const s = fromUnits(units, 4).replace(/\.?0+$/, '');
  return s === '' || s === '-' ? '0' : s;
};

/** Checkers a deposit money request needs, as the tenant's starting rules (tenant V30). */
export function depositCheckers(payload: ApprovalPayload, amount: string | null): number | null {
  if (payload.kind === 'DEPOSIT_HOLD') return 1;
  if (payload.kind === 'DEPOSIT_TXN') return payload.txn.type === 'REVERSE' || !amount || toUnits(amount) >= toUnits(DEPOSIT_CHECKER_FROM) ? 1 : 0;
  if (payload.kind === 'DEPOSIT_FUNDING') return payload.funding.reason !== null || !amount || toUnits(amount) >= toUnits(DEPOSIT_CHECKER_FROM) ? 1 : 0;
  return null;
}

// ---------- balances, holds, checks ----------

export function balanceOf(db: MockDb, accountId: string): bigint {
  return toUnits(db.deposits.balances[accountId] ?? '0');
}

function activeHolds(db: MockDb, accountId: string): MockHold[] {
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
function movementProblems(db: MockDb, a: DepositAccount, direction: 'DEBIT' | 'CREDIT', amount: string | null | undefined, correction: boolean): string[] {
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

function text(v: string | null | undefined, max: number, field: string, problems: string[]): string | null {
  if (!v || !v.trim()) return null;
  const t = v.trim();
  if (t.length > max) problems.push(`the ${field} is longer than ${max} characters`);
  return t;
}

function findAccount(db: MockDb, id: string, family: 'CASA' | 'TERM_DEPOSIT' | null): DepositAccount {
  const a = db.deposits.accounts.find((x) => x.id === id && (!family || x.family === family));
  if (!a) throw notFound(family === 'TERM_DEPOSIT' ? `term deposit ${id}` : `account ${id}`);
  return a;
}

// ---------- writing ----------

function move(db: MockDb, a: DepositAccount, kind: string, direction: 'DEBIT' | 'CREDIT', amount: string, lotId: string, extra: Partial<MockMovement>, maker: string, approvalId: string | null): MockMovement {
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
      approval.appliedRef = reverseLot(db, m, t.reason!, maker, id).id!;
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
      move(db, d, 'FUNDING', 'CREDIT', f.amount, lot, { reference: f.reference, counterpartyAccountNo: from?.accountNo ?? null }, maker, id);
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
    const problems: string[] = [];
    const reference = text(b.reference, 60, 'reference', problems);
    const narration = text(b.narration, 200, 'narration', problems);
    if (bank && !reference) problems.push("the bank's reference (UTR) is required for a bank transfer");
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
    const txn: MockTxn = { type: b.type, accountId: a.id!, accountNo: a.accountNo!, toAccountId: null, toAccountNo: null, amount: amount!, reference, narration, bankAccount: party, movementId: null, reason: null };
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
