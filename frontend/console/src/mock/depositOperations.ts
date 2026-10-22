/**
 * Mock cheques, inoperative and unclaimed accounts, and the register of deposits (P4-6), as the backend's DepositCheques,
 * DepositDormancy and DepositRegister: a cheque book through a checker, a stop payment at once, inward clearing paying or
 * returning a cheque, outward clearing crediting a cheque once it is settled cleared; reactivation and claims through a
 * checker; a deposit-taking NBFC's net owned fund and the 1.5 × NOF cap. Mock mode has no deposit day-end: the tests mark an
 * account inoperative or transfer its balance as unclaimed with markInoperative and transferUnclaimed.
 */
import type { ChequeDeposit, DepositAccount } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { toUnits } from '../lib/money';
import { appendAudit, uuid, type ApprovalPayload, type MockDb } from './db';
import { activeHolds, balanceOf, findAccount, move, movementProblems, plain, refuseIfAny, text } from './depositMoney';
import { addMonths } from './deposits';
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
const conflict = (detail: string) => new HttpProblem(409, 'Conflict', detail, {}, PROBLEM_BASE + 'conflict');

export interface MockChequeBook { id: string; accountId: string; bookNo: string; firstLeaf: number; leaves: number; issuedOn: string; issuedBy: string }
export interface MockLeaf { accountId: string; leafNo: number; bookId: string; status: 'UNUSED' | 'STOPPED' | 'PAID'; stoppedOn: string | null; stopReason: string | null; paidOn: string | null }
export interface MockPresentment {
  id: string; accountId: string | null; accountNo: string; leafNo: number; amount: string; chequeDate: string; payee: string; presentingBank: string;
  presentedOn: string; status: 'PAID' | 'RETURNED'; returnReason: string | null; movementId: string | null;
}
export interface MockNof { asOf: string; amount: string; reference: string; recordedBy: string }
export interface MockChequeRequest { op: 'BOOK' | 'STOP' | 'REVOKE'; accountId: string; accountNo: string; leaves: number | null; fromLeaf: number | null; toLeaf: number | null; reason: string | null }
export interface MockReactivation { accountId: string; accountNo: string; family: string; kycReference: string; reason: string; refund: string | null }

/** The movements made by or for the customer: they keep an account operative. */
export const CUSTOMER_INDUCED = new Set(['CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'BANK_TRANSFER_IN', 'BANK_TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_OUT', 'FUNDING_PAID',
  'CHEQUE_PAID', 'CHEQUE_DEPOSIT', 'LOAN_REPAYMENT']);
export const RETURN_TEXT: Record<string, string> = {
  NO_SUCH_ACCOUNT: 'no such account', ACCOUNT_CLOSED: 'account closed', NOT_ISSUED: 'cheque not issued to this account', ALREADY_PAID: 'cheque already paid',
  PAYMENT_STOPPED: 'payment stopped by the drawer', POST_DATED: 'cheque post-dated', STALE: 'cheque out of date (stale)', ACCOUNT_FROZEN: 'account frozen for debits',
  ACCOUNT_INOPERATIVE: 'account inoperative', AMOUNT_HELD: 'funds held under lien', INSUFFICIENT_FUNDS: 'funds insufficient',
};
const VALID_MONTHS = 3;
const RULES = { reviewMonths: 12, inoperativeMonths: 24, unclaimedYears: 10 };
const MULTIPLE = '1.5';

const multiple = (db: MockDb) => (db.deposits.kind === 'NBFC_DEPOSIT' ? MULTIPLE : null);
const latestNof = (db: MockDb) => [...db.deposits.nof].sort((x, y) => y.asOf.localeCompare(x.asOf))[0] ?? null;
export const outstanding = (db: MockDb) =>
  db.deposits.accounts.filter((a) => a.family === 'TERM_DEPOSIT' && (a.status === 'ACTIVE' || a.status === 'MATURED')).reduce((s, a) => s + balanceOf(db, a.id!), 0n);
const cap = (m: string, nof: string) => (toUnits(nof) * toUnits(m)) / 10000n;

/** Why a new deposit of {@code adding} cannot be accepted under the cap; empty when it can (or there is no cap). */
export function capProblems(db: MockDb, adding: string): string[] {
  const m = multiple(db);
  if (!m) return [];
  const nof = latestNof(db);
  if (!nof) return [`the net owned fund is not recorded: public deposits are held to ${m} times it`];
  const c = cap(m, nof.amount);
  const out = outstanding(db);
  const after = out + toUnits(adding);
  if (after > c) return [`public deposits would be ${plain(after)}, above ${m} times the net owned fund of ${plain(toUnits(nof.amount))} (${plain(c)}); ${plain(c - out > 0n ? c - out : 0n)} may still be accepted`];
  return [];
}

function nofView(db: MockDb) {
  const m = multiple(db);
  const nof = latestNof(db);
  const out = outstanding(db);
  const c = m && nof ? cap(m, nof.amount) : null;
  return {
    applies: !!m, multiple: m, netOwnedFund: nof ? plain(toUnits(nof.amount)) : null, outstanding: plain(out), cap: c === null ? null : plain(c),
    headroom: c === null ? null : plain(c - out > 0n ? c - out : 0n), usedPercent: c === null || c === 0n ? null : (Number((out * 20000n / c + 1n) / 2n) / 100).toFixed(2).replace(/\.?0+$/, ''),
    history: [...db.deposits.nof].sort((x, y) => y.asOf.localeCompare(x.asOf)),
  };
}

const lastOperated = (a: DepositAccount) => (a.family === 'CASA' ? a.lastCustomerTxnOn ?? a.openedOn! : a.termDeposit?.maturityDate ?? a.openedOn!);

/** The day-end's inoperative step, for one account (mock mode has no deposit day-end). */
export function markInoperative(db: MockDb, accountId: string): void {
  const a = findAccount(db, accountId, 'CASA');
  a.inoperativeSince = db.businessDate;
}

/** The day-end's unclaimed step, for one account: the whole balance to the unclaimed deposits head. */
export function transferUnclaimed(db: MockDb, accountId: string): void {
  const a = findAccount(db, accountId, null);
  const bal = balanceOf(db, a.id!);
  if (bal <= 0n) return;
  move(db, a, 'UNCLAIMED_TRANSFER', 'DEBIT', plain(bal), uuid(), { narration: 'Unclaimed balance transferred (DEA Fund)' }, 'system', null);
  a.unclaimed = { transferredOn: db.businessDate, amount: plain(bal) };
}

function chequesOf(db: MockDb, accountId: string) {
  const a = findAccount(db, accountId, 'CASA');
  const facility = db.deposits.products.find((p) => p.code === a.productCode)?.chequeFacility === true;
  return {
    chequeFacility: facility,
    books: db.deposits.chequeBooks.filter((b) => b.accountId === a.id).sort((x, y) => y.firstLeaf - x.firstLeaf).map((b) => ({
      ...b, lastLeaf: b.firstLeaf + b.leaves - 1, unused: db.deposits.chequeLeaves.filter((l) => l.bookId === b.id && l.status === 'UNUSED').length,
    })),
    leaves: db.deposits.chequeLeaves.filter((l) => l.accountId === a.id && l.status !== 'UNUSED').sort((x, y) => y.leafNo - x.leafNo).map(({ accountId: _a, bookId: _b, ...l }) => l),
    presented: db.deposits.presentments.filter((p) => p.accountId === a.id).map(({ accountId: _a, accountNo: _n, movementId: _m, ...p }) => ({ ...p, returnReasonText: p.returnReason ? RETURN_TEXT[p.returnReason] : null })),
    paidIn: db.deposits.chequeDeposits.filter((d) => d.accountId === a.id),
  };
}

function present(db: MockDb, user: DemoUser, b: { accountNo?: string; leafNo?: number; amount?: string | number; chequeDate?: string; payee?: string; presentingBank?: string }) {
  const problems: string[] = [];
  if (!b.accountNo?.trim()) problems.push('the account number on the cheque is required');
  if (!b.leafNo || b.leafNo < 1 || b.leafNo > 999999) problems.push('the cheque number is six digits');
  const amount = b.amount == null ? '' : String(b.amount);
  if (!/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) problems.push('the amount is above zero, to the paisa');
  if (!b.chequeDate) problems.push('the date on the cheque is required');
  const payee = text(b.payee, 200, 'payee', problems);
  const bank = text(b.presentingBank, 100, 'presenting bank', problems);
  if (!payee) problems.push('the payee is required');
  if (!bank) problems.push('the presenting bank is required');
  refuseIfAny('the presentment is not complete', problems);
  const a = db.deposits.accounts.find((x) => x.accountNo === b.accountNo!.trim() && x.family === 'CASA') ?? null;
  const leaf = a ? db.deposits.chequeLeaves.find((l) => l.accountId === a.id && l.leafNo === b.leafNo) ?? null : null;
  const today = db.businessDate;
  let reason: string | null = null;
  if (!a) reason = 'NO_SUCH_ACCOUNT';
  else if (a.status !== 'ACTIVE') reason = 'ACCOUNT_CLOSED';
  else if (!leaf) reason = 'NOT_ISSUED';
  else if (leaf.status === 'PAID') reason = 'ALREADY_PAID';
  else if (leaf.status === 'STOPPED') reason = 'PAYMENT_STOPPED';
  else if (b.chequeDate! > today) reason = 'POST_DATED';
  else if (today > addMonths(b.chequeDate!, VALID_MONTHS)) reason = 'STALE';
  else if (activeHolds(db, a.id!).some((h) => h.kind === 'DEBIT_FREEZE' || h.kind === 'TOTAL_FREEZE')) reason = 'ACCOUNT_FROZEN';
  else if (a.inoperativeSince) reason = 'ACCOUNT_INOPERATIVE';
  else {
    const bal = balanceOf(db, a.id!);
    const held = activeHolds(db, a.id!).filter((h) => h.kind === 'LIEN').reduce((s, h) => s + toUnits(h.amount ?? '0'), 0n);
    if (toUnits(amount) > bal - held) reason = toUnits(amount) <= bal ? 'AMOUNT_HELD' : 'INSUFFICIENT_FUNDS';
  }
  const id = uuid();
  let movementId: string | null = null;
  if (!reason) {
    movementId = move(db, a!, 'CHEQUE_PAID', 'DEBIT', amount, uuid(), { reference: `Cheque ${b.leafNo}`, narration: `Cheque ${b.leafNo} to ${payee} (${bank})` }, user.username, null).id!;
    Object.assign(leaf!, { status: 'PAID', paidOn: today });
  }
  db.deposits.presentments.unshift({ id, accountId: a?.id ?? null, accountNo: b.accountNo!.trim(), leafNo: b.leafNo!, amount: plain(toUnits(amount)), chequeDate: b.chequeDate!, payee: payee!,
    presentingBank: bank!, presentedOn: today, status: reason ? 'RETURNED' : 'PAID', returnReason: reason, movementId });
  return { id, status: reason ? 'RETURNED' : 'PAID', returnReason: reason, returnReasonText: reason ? RETURN_TEXT[reason] : null, movementId };
}

// ---------- approvals ----------

export function applyDepositOperationsApproval(db: MockDb, p: ApprovalPayload, approval: { id?: string; appliedRef?: string | null; maker?: string }, checker: string, at: string): boolean {
  if (p.kind === 'DEPOSIT_CHEQUE') {
    const q = p.request;
    if (q.op === 'BOOK') {
      const first = db.deposits.leafSeq + 1;
      db.deposits.leafSeq += q.leaves!;
      const book: MockChequeBook = { id: uuid(), accountId: q.accountId, bookNo: `CB${String(++db.deposits.bookSeq).padStart(8, '0')}`, firstLeaf: first, leaves: q.leaves!,
        issuedOn: db.businessDate, issuedBy: approval.maker ?? checker };
      db.deposits.chequeBooks.unshift(book);
      for (let n = first; n < first + q.leaves!; n++) db.deposits.chequeLeaves.push({ accountId: q.accountId, leafNo: n, bookId: book.id, status: 'UNUSED', stoppedOn: null, stopReason: null, paidOn: null });
      approval.appliedRef = book.bookNo;
    } else if (q.op === 'STOP') {
      const run = db.deposits.chequeLeaves.filter((l) => l.accountId === q.accountId && l.leafNo >= q.fromLeaf! && l.leafNo <= q.toLeaf! && l.status === 'UNUSED');
      if (run.length !== q.toLeaf! - q.fromLeaf! + 1) throw conflict('a cheque in the run was paid or stopped meanwhile');
      for (const l of run) Object.assign(l, { status: 'STOPPED', stoppedOn: db.businessDate, stopReason: q.reason });
      approval.appliedRef = q.fromLeaf === q.toLeaf ? String(q.fromLeaf) : `${q.fromLeaf}-${q.toLeaf}`;
    } else {
      const l = db.deposits.chequeLeaves.find((x) => x.accountId === q.accountId && x.leafNo === q.fromLeaf && x.status === 'STOPPED');
      if (!l) throw conflict(`payment of cheque ${q.fromLeaf} is no longer stopped`);
      Object.assign(l, { status: 'UNUSED', stoppedOn: null, stopReason: null });
      approval.appliedRef = String(q.fromLeaf);
    }
    appendAudit(db, at, checker, `CHEQUE_${q.op}`, 'CHEQUE', approval.appliedRef!, {});
    return true;
  }
  if (p.kind === 'DEPOSIT_DORMANCY') {
    const q = p.request;
    const a = findAccount(db, q.accountId, null);
    if (a.unclaimed) {
      move(db, a, 'UNCLAIMED_REFUND', 'CREDIT', a.unclaimed.amount!, uuid(), { narration: `Unclaimed balance refunded (${q.kycReference})` }, approval.maker ?? checker, approval.id ?? null);
      a.unclaimed = null;
    }
    if (a.family === 'CASA') Object.assign(a, { inoperativeSince: null, lastCustomerTxnOn: db.businessDate });
    approval.appliedRef = a.accountNo!;
    appendAudit(db, at, checker, a.family === 'CASA' ? 'CASA_REACTIVATED' : 'UNCLAIMED_REFUNDED', 'DEPOSIT_DORMANCY', a.id!, {});
    return true;
  }
  if (p.kind === 'DEPOSIT_NOF') {
    if (db.deposits.nof.some((x) => x.asOf === p.nof.asOf)) throw conflict(`a net owned fund is already recorded as of ${p.nof.asOf}`);
    db.deposits.nof.push({ ...p.nof, recordedBy: approval.maker ?? checker });
    approval.appliedRef = p.nof.asOf;
    return true;
  }
  return false;
}

// ---------- routes ----------

export function registerDepositOperationsRoutes(db: MockDb, r: Registrar, module: (name: 'CASA' | 'TD') => void): void {
  const { on, require, propose } = r;
  const leavesOf = (accountId: string, from: number, to: number) => db.deposits.chequeLeaves.filter((l) => l.accountId === accountId && l.leafNo >= from && l.leafNo <= to);

  on('GET', '/api/v1/casa/accounts/{id}/cheques', ({ user, params }) => {
    module('CASA');
    require(user, P.casaView);
    return ok(chequesOf(db, params.id));
  });

  on('POST', '/api/v1/casa/accounts/{id}/cheque-books', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const a = findAccount(db, params.id, 'CASA');
    const leaves = ((body ?? {}) as { leaves?: number | null }).leaves ?? 25;
    const problems: string[] = [];
    if (![10, 25, 50, 100].includes(leaves)) problems.push('a cheque book has 10, 25, 50 or 100 leaves');
    if (!chequesOf(db, a.id!).chequeFacility) problems.push(`account ${a.accountNo}: its product has no cheque facility`);
    if (a.status !== 'ACTIVE') problems.push(`account ${a.accountNo} is ${a.status}`);
    if (a.inoperativeSince) problems.push(`account ${a.accountNo} is inoperative: reactivate it first`);
    refuseIfAny('the cheque book cannot be issued', problems);
    const request: MockChequeRequest = { op: 'BOOK', accountId: a.id!, accountNo: a.accountNo!, leaves, fromLeaf: null, toLeaf: null, reason: null };
    return propose(user, 'CHEQUE', 'ISSUE_BOOK', { kind: 'DEPOSIT_CHEQUE', request }, { accountNo: a.accountNo, leaves }, null, a.id!);
  });

  on('POST', '/api/v1/casa/accounts/{id}/cheques/stop', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const a = findAccount(db, params.id, 'CASA');
    const b = (body ?? {}) as { fromLeaf?: number; toLeaf?: number | null; reason?: string };
    const problems: string[] = [];
    const reason = text(b.reason, 500, 'reason', problems);
    if (!reason) problems.push("a reason is required (the drawer's request)");
    const from = b.fromLeaf;
    const to = b.toLeaf ?? from;
    if (!from) problems.push('give the cheque number to stop');
    else if (to! < from || to! - from >= 100) problems.push('stop one cheque or a run of at most 100');
    else {
      const rows = leavesOf(a.id!, from, to!);
      if (rows.length !== to! - from + 1) problems.push(`cheque${from === to ? ` ${from} was` : `s ${from} to ${to} were`} not all issued to account ${a.accountNo}`);
      for (const l of rows) if (l.status !== 'UNUSED') problems.push(`cheque ${l.leafNo} is ${l.status.toLowerCase()}`);
    }
    refuseIfAny('payment cannot be stopped', problems);
    const request: MockChequeRequest = { op: 'STOP', accountId: a.id!, accountNo: a.accountNo!, leaves: null, fromLeaf: from!, toLeaf: to!, reason };
    return propose(user, 'CHEQUE', 'STOP', { kind: 'DEPOSIT_CHEQUE', request }, { accountNo: a.accountNo, fromLeaf: from, toLeaf: to, reason }, null, a.id!);
  });

  on('POST', '/api/v1/casa/accounts/{id}/cheques/{leafNo}/revoke-stop', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const a = findAccount(db, params.id, 'CASA');
    const leafNo = Number(params.leafNo);
    const problems: string[] = [];
    const rows = leavesOf(a.id!, leafNo, leafNo);
    if (!rows.length) problems.push(`cheque ${leafNo} was not issued to account ${a.accountNo}`);
    else if (rows[0].status !== 'STOPPED') problems.push(`payment of cheque ${leafNo} is not stopped`);
    const reason = text(((body ?? {}) as { reason?: string }).reason, 500, 'reason', problems);
    if (!reason) problems.push('a reason is required');
    refuseIfAny('the stop cannot be revoked', problems);
    const request: MockChequeRequest = { op: 'REVOKE', accountId: a.id!, accountNo: a.accountNo!, leaves: null, fromLeaf: leafNo, toLeaf: leafNo, reason };
    return propose(user, 'CHEQUE', 'REVOKE_STOP', { kind: 'DEPOSIT_CHEQUE', request }, { accountNo: a.accountNo, leafNo, reason }, null, a.id!);
  });

  on('POST', '/api/v1/casa/clearing/inward', ({ user, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    return ok(present(db, user, (body ?? {}) as never));
  });

  on('POST', '/api/v1/casa/accounts/{id}/cheque-deposits', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const a = findAccount(db, params.id, 'CASA');
    const b = (body ?? {}) as { chequeNo?: string; draweeIfsc?: string; drawerName?: string; amount?: string | number; chequeDate?: string };
    const problems: string[] = [];
    if (a.status !== 'ACTIVE') problems.push(`account ${a.accountNo} is ${a.status}`);
    if (!/^[0-9]{6}$/.test(b.chequeNo ?? '')) problems.push('the cheque number is six digits');
    const ifsc = (b.draweeIfsc ?? '').trim().toUpperCase();
    if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(ifsc)) problems.push("the drawee bank's IFSC is 11 characters (4 letters, 0, 6 letters or digits)");
    const drawer = text(b.drawerName, 200, 'drawer name', problems);
    if (!drawer) problems.push("the drawer's name is required");
    const amount = b.amount == null ? '' : String(b.amount);
    if (!/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) problems.push('the amount is above zero, to the paisa');
    if (!b.chequeDate) problems.push('the date on the cheque is required');
    else if (b.chequeDate > db.businessDate) problems.push(`the cheque is post-dated (${b.chequeDate})`);
    else if (db.businessDate > addMonths(b.chequeDate, VALID_MONTHS)) problems.push(`the cheque is stale: dated more than ${VALID_MONTHS} months ago`);
    refuseIfAny('the cheque cannot be paid in', problems);
    const d: ChequeDeposit = { id: uuid(), accountId: a.id!, accountNo: a.accountNo!, chequeNo: b.chequeNo!, draweeIfsc: ifsc, drawerName: drawer!, amount: plain(toUnits(amount)),
      chequeDate: b.chequeDate!, lodgedOn: db.businessDate, status: 'IN_CLEARING', settledOn: null, returnReason: null };
    db.deposits.chequeDeposits.unshift(d);
    return { status: 201, body: d };
  });

  on('GET', '/api/v1/casa/cheque-deposits', ({ user }) => {
    module('CASA');
    require(user, P.casaView);
    return ok(db.deposits.chequeDeposits.filter((d) => d.status === 'IN_CLEARING').reverse());
  });

  on('POST', '/api/v1/casa/cheque-deposits/{id}/settle', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaTransact);
    const d = db.deposits.chequeDeposits.find((x) => x.id === params.id);
    if (!d) throw notFound(`cheque deposit ${params.id}`);
    const b = (body ?? {}) as { outcome?: string; reason?: string | null };
    if (b.outcome !== 'CLEARED' && b.outcome !== 'RETURNED') throw invalid('outcome is CLEARED or RETURNED');
    const problems: string[] = [];
    if (d.status !== 'IN_CLEARING') problems.push(`cheque ${d.chequeNo} is already ${d.status!.toLowerCase()}`);
    const a = findAccount(db, d.accountId!, 'CASA');
    const reason = text(b.reason, 200, 'reason', problems);
    if (b.outcome === 'RETURNED' && !reason) problems.push('the reason the cheque was returned is required');
    if (b.outcome === 'CLEARED') problems.push(...movementProblems(db, a, 'CREDIT', d.amount, false).map((x) => `account ${a.accountNo}: ${x} (return the cheque instead)`));
    refuseIfAny('the cheque cannot be settled', problems);
    if (b.outcome === 'CLEARED') move(db, a, 'CHEQUE_DEPOSIT', 'CREDIT', d.amount!, uuid(), { reference: `Cheque ${d.chequeNo}`, narration: `Cheque ${d.chequeNo} of ${d.drawerName} cleared` }, user.username, null);
    Object.assign(d, { status: b.outcome, settledOn: db.businessDate, returnReason: b.outcome === 'RETURNED' ? reason : null });
    return ok(d);
  });

  on('GET', '/api/v1/casa/dormancy', ({ user, url }) => {
    module('CASA');
    require(user, P.casaView);
    const today = db.businessDate;
    if (db.deposits.kind !== 'BANK') return ok({ asOf: today, applies: false, accounts: [] });
    const stage = url.searchParams.get('stage');
    const accounts = db.deposits.accounts
      .filter((a) => a.unclaimed || (a.family === 'CASA' && a.status === 'ACTIVE' && (a.inoperativeSince || lastOperated(a) <= addMonths(today, -RULES.reviewMonths))))
      .map((a) => {
        const last = lastOperated(a);
        const st = a.unclaimed ? 'UNCLAIMED' : a.inoperativeSince ? 'INOPERATIVE' : 'REVIEW';
        const h = a.holders?.[0];
        return { accountId: a.id, accountNo: a.accountNo, family: a.family, productCode: a.productCode, branchCode: a.branchCode, customerNo: h?.customerNo, customerName: h?.name,
          balance: plain(balanceOf(db, a.id!)), lastOperated: last, stage: st, inoperativeSince: a.inoperativeSince ?? null,
          inoperativeOn: a.family === 'CASA' ? addMonths(last, RULES.inoperativeMonths) : null, unclaimedOn: addMonths(last, RULES.unclaimedYears * 12),
          unclaimedTransferredOn: a.unclaimed?.transferredOn ?? null, unclaimedAmount: a.unclaimed?.amount ?? null };
      })
      .filter((x) => !stage || x.stage === stage)
      .sort((x, y) => x.lastOperated.localeCompare(y.lastOperated));
    return ok({ asOf: today, applies: true, ...RULES, accounts });
  });

  const reactivation = (user: DemoUser, a: DepositAccount, body: unknown) => {
    const b = (body ?? {}) as { kycReference?: string; reason?: string };
    const casa = a.family === 'CASA';
    const problems: string[] = [];
    if (casa && !a.inoperativeSince && !a.unclaimed) problems.push(`account ${a.accountNo} is operative`);
    if (!casa && !a.unclaimed) problems.push(`deposit ${a.accountNo} has no unclaimed balance`);
    const kyc = text(b.kycReference, 100, 'KYC reference', problems);
    const reason = text(b.reason, 500, 'reason', problems);
    if (!kyc) problems.push('the KYC record updated (or the claim documents seen) is required');
    if (!reason) problems.push('a reason is required');
    refuseIfAny(casa ? 'the account cannot be reactivated' : 'the claim cannot be paid', problems);
    const request: MockReactivation = { accountId: a.id!, accountNo: a.accountNo!, family: a.family!, kycReference: kyc!, reason: reason!, refund: a.unclaimed?.amount ?? null };
    return propose(user, 'DEPOSIT_DORMANCY', casa ? 'REACTIVATE' : 'CLAIM', { kind: 'DEPOSIT_DORMANCY', request }, { ...request }, null, a.id!, request.refund);
  };

  on('POST', '/api/v1/casa/accounts/{id}/reactivate', ({ user, params, body }) => {
    module('CASA');
    require(user, P.casaOpen);
    return reactivation(user, findAccount(db, params.id, 'CASA'), body);
  });

  on('POST', '/api/v1/deposits/accounts/{id}/claim', ({ user, params, body }) => {
    module('TD');
    require(user, P.depositFund);
    return reactivation(user, findAccount(db, params.id, 'TERM_DEPOSIT'), body);
  });

  on('GET', '/api/v1/deposits/register', ({ user, url }) => {
    module('TD');
    require(user, P.depositView);
    const branch = url.searchParams.get('branch');
    const rows = db.deposits.accounts
      .filter((a) => a.family === 'TERM_DEPOSIT' && (a.status === 'ACTIVE' || a.status === 'MATURED') && (!branch || a.branchCode === branch))
      .sort((x, y) => (x.termDeposit?.startDate ?? '').localeCompare(y.termDeposit?.startDate ?? '') || x.accountNo!.localeCompare(y.accountNo!))
      .map((a) => {
        const t = a.termDeposit!;
        return { accountId: a.id, accountNo: a.accountNo, branchCode: a.branchCode, status: a.status, productCode: a.productCode, productType: a.productType,
          customerNo: a.holders?.[0]?.customerNo, depositorName: a.holders?.[0]?.name, holders: a.holders?.length ?? 1, principal: t.principal, ratePercent: String(t.ratePercent),
          tenureMonths: t.tenureMonths, tenureDays: t.tenureDays, startDate: t.startDate, maturityDate: t.maturityDate, maturityInstruction: t.maturityInstruction,
          balance: plain(balanceOf(db, a.id!)), nominee: a.nominationMode !== 'NONE', lien: activeHolds(db, a.id!).some((h) => h.kind === 'LIEN') };
      });
    const total = rows.reduce((s, x) => s + toUnits(x.balance), 0n);
    return ok({ asOf: db.businessDate, count: rows.length, total: plain(total), netOwnedFund: nofView(db), deposits: rows });
  });

  on('GET', '/api/v1/deposits/net-owned-fund', ({ user }) => {
    module('TD');
    require(user, P.depositView);
    return ok(nofView(db));
  });

  on('POST', '/api/v1/deposits/net-owned-fund', ({ user, body }) => {
    module('TD');
    require(user, P.productPropose);
    const b = (body ?? {}) as { amount?: string | number; asOf?: string; reference?: string };
    const problems: string[] = [];
    if (!multiple(db)) problems.push("this institution's rule set holds deposits to no multiple of its net owned fund");
    const amount = b.amount == null ? '' : String(b.amount);
    if (!/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) problems.push('the amount is above zero, to the paisa');
    if (!b.asOf) problems.push('the date of the audited balance sheet is required');
    else if (b.asOf > db.businessDate) problems.push('the balance sheet date is after the business date');
    else if (db.deposits.nof.some((x) => x.asOf === b.asOf)) problems.push(`a net owned fund is already recorded as of ${b.asOf}`);
    const reference = text(b.reference, 200, 'reference', problems);
    if (!reference) problems.push("the reference (the audited balance sheet or the auditor's certificate) is required");
    refuseIfAny('the net owned fund cannot be recorded', problems);
    const nof = { asOf: b.asOf!, amount: plain(toUnits(amount)), reference: reference! };
    return propose(user, 'DEPOSIT_NOF', 'RECORD', { kind: 'DEPOSIT_NOF', nof }, { ...nof }, null, b.asOf!, nof.amount);
  });
}
