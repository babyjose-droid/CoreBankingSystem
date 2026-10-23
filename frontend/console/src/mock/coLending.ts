/**
 * Co-lending (P5-1) in the mock API, as the originating lender: arrangements under the RBI (Co-Lending Arrangements)
 * Directions, 2025, loans sanctioned at the blended rate, the offer to the partner at disbursement with its 15 calendar
 * days, and the partner's decisions (from its file or the simulator), all through a checker. The mock keeps the
 * co-lending state beside the loan and brings it up to date when read (offer at disbursement, lapse after the 15th
 * day); it does not post the transfer to the ledger.
 */
import type { CoLendingArrangement, CoLendingArrangementInput, CoLendingDecision, CoLendingOffer, CoLentLoan, KfsCoLending, LoanApplication } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { addDays, ISO_DATE } from '../lib/dates';
import { appendAudit, type ApprovalPayload, type MockDb, type StoredArrangement, type StoredCoLent, type StoredLoan } from './db';
import { customerOf, principalOutstanding } from './lending';
import * as C from './lendingCalc';
import { bad, conflict, notFound } from './problems';

type Result = { status: number; body: unknown; raw?: { contentType: string; data: string; fileName: string } };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
}
export interface CoLendingRouter {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (user: DemoUser, entityType: string, action: string, payload: ApprovalPayload, proposed: Record<string, unknown>, current?: Record<string, unknown> | null, entityId?: string | null, amount?: string | null) => Result;
}

export const MIN_SHARE = 10;
export const MAX_DLG = 5;
export const TRANSFER_DAYS = 15;
const ok = (body: unknown): Result => ({ status: 200, body });
const r2 = (n: number) => Math.round(n * 100) / 100;
const plain = (n: number) => String(r2(n));
const num = (v: unknown) => (v === null || v === undefined || v === '' ? NaN : Number(v));

export const blendedRate = (t: Pick<StoredArrangement, 'originatorShare' | 'originatorRate' | 'partnerRate'>) =>
  r2((t.originatorShare * t.originatorRate + (100 - t.originatorShare) * t.partnerRate) / 100);
/** The partner's share of an amount in paise; the originator keeps the rounding. */
const partnerOf = (t: StoredArrangement, paise: number) => Math.round((paise * (100 - t.originatorShare)) / 100);

/** The Directions' limits on the terms. */
export function termProblems(t: { originatorShare: number; originatorRate: number; partnerRate: number; servicingFee: number; dlgPercent: number }): string[] {
  const p: string[] = [];
  if (!(t.originatorShare >= MIN_SHARE && t.originatorShare <= 100 - MIN_SHARE)) p.push(`each lender keeps at least ${MIN_SHARE}% of every loan: the originator's share is 10 to 90%`);
  for (const [label, r] of [['the originator', t.originatorRate], ['the partner', t.partnerRate]] as const) {
    if (!(r > 0 && r < 100)) p.push(`${label}'s rate is above zero and below 100%`);
  }
  if (!(t.servicingFee >= 0) || (t.partnerRate > 0 && t.servicingFee >= t.partnerRate)) p.push("the servicing fee is 0 or more and below the partner's rate");
  if (!(t.dlgPercent >= 0 && t.dlgPercent <= MAX_DLG)) p.push(`the default loss guarantee is at most ${MAX_DLG}% of the outstanding`);
  return p;
}

function refuse(title: string, problems: string[]) {
  if (problems.length) throw bad(`${title}: ${problems.join('; ')}`, problems.map((m) => ({ field: 'arrangement', message: m })));
}

/** Bring a co-lent loan up to date: offered once disbursed, back to sanctioned if the disbursement went, lapsed after the 15th day. */
function sync(db: MockDb, c: StoredCoLent, loan: StoredLoan | undefined) {
  if (!loan) return;
  if (c.status === 'SANCTIONED' && loan.disbursedOn) {
    c.status = 'OFFERED';
    c.offeredOn = loan.disbursedOn;
    c.deadline = addDays(loan.disbursedOn, TRANSFER_DAYS);
  } else if (c.status === 'OFFERED' && !loan.disbursedOn) {
    Object.assign(c, { status: 'SANCTIONED', offeredOn: null, deadline: null });
  }
  if (c.status === 'OFFERED' && c.deadline && db.businessDate > c.deadline) {
    Object.assign(c, { status: 'LAPSED', decidedOn: c.deadline, decisionReason: 'not accepted within 15 calendar days of disbursement' });
  }
}
function syncAll(db: MockDb) {
  for (const c of db.coLending.loans) sync(db, c, db.loans.find((l) => l.id === c.loanId));
}

function arrangementView(db: MockDb, a: StoredArrangement): CoLendingArrangement {
  syncAll(db);
  const loans: Record<string, number> = {};
  let booked = 0;
  for (const c of db.coLending.loans.filter((x) => x.terms.code === a.code)) {
    loans[c.status.toLowerCase()] = (loans[c.status.toLowerCase()] ?? 0) + 1;
    if (c.status === 'ACCEPTED') booked += c.partnerPrincipal ?? 0;
  }
  return {
    code: a.code,
    name: a.name,
    role: 'ORIGINATOR',
    partnerName: a.partnerName,
    partnerKind: a.partnerKind,
    partnerReference: a.partnerReference,
    agreementReference: a.agreementReference,
    ...termsView(a),
    productCodes: [...a.productCodes],
    contact: a.contact,
    status: a.status,
    effectiveFrom: a.effectiveFrom,
    version: a.version,
    createdBy: a.createdBy,
    updatedBy: a.updatedBy,
    loans,
    partnerPrincipalBooked: C.fromPaise(booked),
  };
}
function termsView(a: StoredArrangement) {
  return {
    originatorShare: plain(a.originatorShare),
    partnerShare: plain(100 - a.originatorShare),
    originatorRate: plain(a.originatorRate),
    partnerRate: plain(a.partnerRate),
    blendedRate: plain(blendedRate(a)),
    servicingFee: plain(a.servicingFee),
    feeSharing: a.feeSharing,
    penalSharing: a.penalSharing,
    dlgPercent: plain(a.dlgPercent),
  };
}

function findArrangement(db: MockDb, code: string) {
  const a = db.coLending.arrangements.find((x) => x.code === code);
  if (!a) throw notFound('Co-lending arrangement');
  return a;
}

/**
 * A loan application naming an arrangement: checked, and priced at the blended rate. Returns the application to price
 * and the arrangement, or the application unchanged when none is named.
 */
export function coLendingApplication(db: MockDb, a: LoanApplication): { app: LoanApplication; arrangement: StoredArrangement | null } {
  const code = a?.coLendingArrangement?.trim();
  if (!code) return { app: a, arrangement: null };
  const arr = db.coLending.arrangements.find((x) => x.code === code);
  const field = (message: string) => bad(message, [{ field: 'coLendingArrangement', message }]);
  if (!arr) throw field(`No co-lending arrangement ${code}`);
  if (arr.status !== 'ACTIVE') throw field(`Co-lending arrangement ${code} is ${arr.status.toLowerCase()}`);
  if (!arr.productCodes.includes(a.productCode)) throw field(`Co-lending arrangement ${code} does not cover product ${a.productCode}`);
  const product = db.loanProducts.find((p) => p.code === a.productCode);
  if (product?.multipleDisbursements) throw field(`Product ${a.productCode} is disbursed in tranches: it is not co-lent (open item P5-01)`);
  if (product?.benchmarkCode) throw field(`Product ${a.productCode} has a floating rate: a co-lent loan carries the fixed blended rate`);
  const blended = blendedRate(arr);
  const given = num(a.rate);
  if (!Number.isNaN(given) && r2(given) !== blended) {
    throw bad(`A loan under ${code} is at the blended rate of ${blended}%`, [{ field: 'rate', message: `Give no rate, or ${blended}` }]);
  }
  if (a.instalment || a.maturityAmount) throw field('A co-lent loan is priced at the blended rate: give no instalment or maturity amount');
  return { app: { ...a, rate: String(blended) }, arrangement: arr };
}

export function kfsSection(db: MockDb, arr: StoredArrangement): KfsCoLending {
  return {
    arrangementCode: arr.code,
    lenders: [
      { name: db.tenantName, role: 'Originating lender', sharePercent: plain(arr.originatorShare), ratePercent: plain(arr.originatorRate) },
      { name: arr.partnerName, role: 'Partner lender', sharePercent: plain(100 - arr.originatorShare), ratePercent: plain(arr.partnerRate) },
    ],
    blendedRate: plain(blendedRate(arr)),
    singlePointOfContact: arr.contact,
    note: 'This loan is lent jointly by the lenders above under a co-lending arrangement; the interest rate is the blended rate. The originating lender services the loan and is the single point of contact.',
  };
}

export function attachCoLent(db: MockDb, loan: StoredLoan, arr: StoredArrangement) {
  db.coLending.loans.push({ loanId: loan.id, terms: { ...arr, productCodes: [...arr.productCodes] }, status: 'SANCTIONED', offeredOn: null, deadline: null, decidedOn: null, decisionReason: null, partnerLoanRef: null, partnerPrincipal: null });
}

/** The co-lending card of a loan, or null. */
export function coLentView(db: MockDb, loan: StoredLoan): CoLentLoan | null {
  const c = db.coLending?.loans.find((x) => x.loanId === loan.id);
  if (!c) return null;
  sync(db, c, loan);
  const t = c.terms;
  const view: CoLentLoan = {
    arrangementCode: t.code,
    arrangementVersion: t.version,
    partnerName: t.partnerName,
    ...termsView(t),
    status: c.status,
    offeredOn: c.offeredOn,
    deadline: c.deadline,
    decidedOn: c.decidedOn,
    decisionReason: c.decisionReason,
    partnerLoanRef: c.partnerLoanRef,
    partnerPrincipalAtTransfer: c.partnerPrincipal === null ? null : C.fromPaise(c.partnerPrincipal),
  };
  if (c.status === 'ACCEPTED') {
    const po = principalOutstanding(loan);
    view.partnerPrincipal = C.fromPaise(partnerOf(t, po));
    view.originatorPrincipal = C.fromPaise(po - partnerOf(t, po));
  }
  return view;
}

function offers(db: MockDb, code: string): CoLendingOffer[] {
  syncAll(db);
  return db.coLending.loans
    .filter((c) => c.terms.code === code && c.status === 'OFFERED')
    .map((c) => ({ c, loan: db.loans.find((l) => l.id === c.loanId)! }))
    .sort((x, y) => (x.c.deadline ?? '').localeCompare(y.c.deadline ?? '') || x.loan.loanNo.localeCompare(y.loan.loanNo))
    .map(({ c, loan }) => {
      const po = principalOutstanding(loan);
      return {
        loanId: loan.id,
        loanNo: loan.loanNo,
        branchCode: loan.branch,
        customerNo: customerOf(db, loan)?.customerNo ?? '',
        disbursedOn: loan.disbursedOn!,
        deadline: c.deadline!,
        amount: C.fromPaise(loan.amount),
        principalOutstanding: C.fromPaise(po),
        partnerShare: plain(100 - c.terms.originatorShare),
        partnerPrincipal: C.fromPaise(partnerOf(c.terms, po)),
        partnerRate: plain(c.terms.partnerRate),
        blendedRate: plain(blendedRate(c.terms)),
        tenorMonths: loan.tenorMonths,
        emi: loan.kfs.emi ?? null,
        assetClass: loan.state.assetClass,
        dpd: loan.state.dpd,
      };
    });
}

const CSV_HEAD = ['loan_no', 'customer_no', 'disbursed_on', 'deadline', 'sanctioned_amount', 'principal_outstanding', 'partner_share_percent', 'partner_principal', 'partner_rate', 'blended_rate', 'tenor_months', 'emi', 'asset_class', 'dpd'];
export function offersCsv(rows: CoLendingOffer[]): string {
  const line = (xs: unknown[]) => xs.map((x) => (x === null || x === undefined ? '' : String(x))).join(',') + '\r\n';
  return line(CSV_HEAD) + rows.map((r) => line([r.loanNo, r.customerNo, r.disbursedOn, r.deadline, r.amount, r.principalOutstanding, r.partnerShare, r.partnerPrincipal, r.partnerRate, r.blendedRate, r.tenorMonths, r.emi, r.assetClass, r.dpd])).join('');
}

function readArrangement(db: MockDb, body: unknown): { input: CoLendingArrangementInput; change: StoredArrangement | undefined } {
  const b = (body ?? {}) as Partial<CoLendingArrangementInput>;
  const problems: string[] = [];
  const code = (b.code ?? '').trim().toUpperCase();
  if (!/^[A-Z][A-Z0-9_]{1,19}$/.test(code)) problems.push('the code is 2 to 20 capital letters, digits or _, starting with a letter');
  const existing = db.coLending.arrangements.find((x) => x.code === code);
  if (existing?.status === 'CLOSED') problems.push(`arrangement ${code} is closed`);
  if (existing && (existing.partnerName !== b.partnerName?.trim() || existing.partnerKind !== b.partnerKind)) problems.push('the partner of an arrangement never changes; close it and agree a new one');
  for (const [v, label] of [[b.name, 'the name'], [b.partnerName, "the partner's name"], [b.agreementReference, 'the reference of the co-lending agreement'], [b.contact, "the borrower's single point of contact"]] as const) {
    if (!v?.trim()) problems.push(`${label} is required`);
  }
  if (!['BANK', 'AIFI', 'NBFC'].includes(b.partnerKind ?? '')) problems.push('the partner is a BANK, an AIFI or an NBFC');
  const t = { originatorShare: num(b.originatorShare), originatorRate: num(b.originatorRate), partnerRate: num(b.partnerRate), servicingFee: b.servicingFee == null || b.servicingFee === '' ? 0 : num(b.servicingFee), dlgPercent: b.dlgPercent == null || b.dlgPercent === '' ? 0 : num(b.dlgPercent) };
  problems.push(...termProblems(t));
  for (const s of [b.feeSharing, b.penalSharing]) if (s && !['ORIGINATOR', 'BY_SHARE'].includes(s)) problems.push('fees and penal charges go to the ORIGINATOR or are shared BY_SHARE');
  const products = [...new Set(b.productCodes ?? [])];
  if (!products.length) problems.push('name the loan products the arrangement covers');
  for (const code of products) {
    const p = db.loanProducts.find((x) => x.code === code);
    if (!p) problems.push(`no loan product ${code}`);
    else if (p.multipleDisbursements) problems.push(`product ${code} is disbursed in tranches: it is not co-lent (open item P5-01)`);
    else if (p.benchmarkCode) problems.push(`product ${code} has a floating rate: a co-lent loan carries the fixed blended rate`);
  }
  refuse(existing ? 'The arrangement cannot be changed' : 'The arrangement cannot be agreed', problems);
  return {
    input: {
      code,
      name: b.name!.trim(),
      partnerName: b.partnerName!.trim(),
      partnerKind: b.partnerKind!,
      partnerReference: b.partnerReference?.trim() || null,
      agreementReference: b.agreementReference!.trim(),
      originatorShare: plain(t.originatorShare),
      originatorRate: plain(t.originatorRate),
      partnerRate: plain(t.partnerRate),
      servicingFee: plain(t.servicingFee),
      feeSharing: b.feeSharing ?? 'ORIGINATOR',
      penalSharing: b.penalSharing ?? 'BY_SHARE',
      dlgPercent: plain(t.dlgPercent),
      productCodes: products,
      contact: b.contact!.trim(),
    },
    change: existing,
  };
}

export function applyCoLendingApproval(db: MockDb, p: ApprovalPayload, approval: { entityId?: string | null; appliedRef?: string | null; maker?: string }, checker: string, at: string): boolean {
  switch (p.kind) {
    case 'CO_LENDING_ARRANGEMENT': {
      const i = p.arrangement;
      const existing = db.coLending.arrangements.find((x) => x.code === i.code);
      if (existing?.status === 'CLOSED') throw conflict('Arrangement closed', `Arrangement ${i.code} is closed`);
      const terms = {
        name: i.name,
        partnerReference: i.partnerReference ?? null,
        agreementReference: i.agreementReference,
        originatorShare: num(i.originatorShare),
        originatorRate: num(i.originatorRate),
        partnerRate: num(i.partnerRate),
        servicingFee: num(i.servicingFee ?? 0),
        feeSharing: (i.feeSharing ?? 'ORIGINATOR') as StoredArrangement['feeSharing'],
        penalSharing: (i.penalSharing ?? 'BY_SHARE') as StoredArrangement['penalSharing'],
        dlgPercent: num(i.dlgPercent ?? 0),
        productCodes: [...i.productCodes],
        contact: i.contact,
      };
      if (existing) Object.assign(existing, terms, { version: existing.version + 1, updatedBy: checker });
      else db.coLending.arrangements.push({ code: i.code, partnerName: i.partnerName, partnerKind: i.partnerKind, ...terms, status: 'ACTIVE', effectiveFrom: db.businessDate, version: 1, createdBy: approval.maker ?? checker, updatedBy: null });
      approval.entityId = i.code;
      approval.appliedRef = i.code;
      appendAudit(db, at, checker, existing ? 'CO_LENDING_ARRANGEMENT_CHANGED' : 'CO_LENDING_ARRANGEMENT_AGREED', 'CO_LENDING_ARRANGEMENT', i.code, {});
      return true;
    }
    case 'CO_LENDING_STATUS': {
      const a = findArrangement(db, p.code);
      if (a.status === 'CLOSED') throw conflict('Arrangement closed', `Arrangement ${p.code} is closed`);
      a.status = p.status;
      a.version += 1;
      a.updatedBy = checker;
      approval.appliedRef = p.code;
      appendAudit(db, at, checker, `CO_LENDING_ARRANGEMENT_${p.status}`, 'CO_LENDING_ARRANGEMENT', p.code, { reason: p.reason });
      return true;
    }
    case 'CO_LENDING_DECISION': {
      syncAll(db);
      let accepted = 0;
      let declined = 0;
      let lapsed = 0;
      let gone = 0;
      for (const d of p.decisions) {
        const loan = db.loans.find((l) => l.loanNo === d.loanNo)!;
        const c = db.coLending.loans.find((x) => x.loanId === loan?.id);
        if (!c || c.status !== 'OFFERED') {
          gone++;
          continue;
        }
        if (d.decision === 'DECLINE') {
          Object.assign(c, { status: 'DECLINED', decidedOn: db.businessDate, decisionReason: d.reason ?? null });
          declined++;
          continue;
        }
        if (c.deadline && db.businessDate > c.deadline) {
          lapsed++;
          continue;
        }
        Object.assign(c, { status: 'ACCEPTED', decidedOn: db.businessDate, partnerLoanRef: d.partnerLoanRef ?? null, partnerPrincipal: partnerOf(c.terms, principalOutstanding(loan)) });
        accepted++;
      }
      approval.appliedRef = `${p.code}: ${accepted} accepted, ${declined} declined${lapsed ? `, ${lapsed} past their 15 days (left with the originator)` : ''}${gone ? `, ${gone} no longer offered` : ''}`;
      appendAudit(db, at, checker, 'CO_LENDING_DECIDED', 'CO_LENDING_ARRANGEMENT', p.code, { accepted, declined, lapsed, gone });
      return true;
    }
    default:
      return false;
  }
}

export function registerCoLendingRoutes(db: MockDb, r: CoLendingRouter) {
  const { on, require, propose } = r;
  const assertOpen = () => {
    if (db.dayStatus !== 'OPEN') throw conflict('Business day not open', 'The business day is not open');
  };

  function proposeDecisions(user: DemoUser, code: string, list: CoLendingDecision[] | undefined): Result {
    findArrangement(db, code);
    assertOpen();
    syncAll(db);
    if (!list?.length) throw bad('No decision given', [{ field: 'decisions', message: 'Required' }]);
    if (list.length > 1000) throw bad('At most 1,000 decisions at a time');
    const problems: string[] = [];
    const rows: CoLendingDecision[] = [];
    const seen = new Set<string>();
    let total = 0;
    for (const d of list) {
      const no = d?.loanNo?.trim();
      if (!no) { problems.push('a decision without a loan number'); continue; }
      if (seen.has(no)) { problems.push(`loan ${no} is decided twice`); continue; }
      seen.add(no);
      const decision = d.decision?.trim().toUpperCase();
      if (decision !== 'ACCEPT' && decision !== 'DECLINE') { problems.push(`loan ${no}: the decision is ACCEPT or DECLINE`); continue; }
      const loan = db.loans.find((l) => l.loanNo === no);
      const c = loan && db.coLending.loans.find((x) => x.loanId === loan.id);
      if (!loan || !c || c.terms.code !== code) { problems.push(`loan ${no} is not co-lent under ${code}`); continue; }
      if (c.status !== 'OFFERED') { problems.push(`loan ${no} is ${c.status.toLowerCase()}, not offered`); continue; }
      if (decision === 'ACCEPT' && c.deadline && db.businessDate > c.deadline) { problems.push(`loan ${no}: the offer lapsed on ${c.deadline}`); continue; }
      const reason = d.reason?.trim() || null;
      if (decision === 'DECLINE' && !reason) problems.push(`loan ${no}: the partner's reason for declining is required`);
      if ((d.partnerLoanRef ?? '').length > 60) problems.push(`loan ${no}: the partner's reference is at most 60 characters`);
      rows.push({ loanNo: no, decision, partnerLoanRef: d.partnerLoanRef?.trim() || null, reason });
      if (decision === 'ACCEPT') total += partnerOf(c.terms, principalOutstanding(loan));
    }
    refuse("The partner's decisions cannot be recorded", problems);
    const accepted = rows.filter((x) => x.decision === 'ACCEPT').length;
    return propose(user, 'CO_LENDING_DECISION', 'DECIDE', { kind: 'CO_LENDING_DECISION', code, decisions: rows },
      { arrangementCode: code, decisions: rows, accepted, declined: rows.length - accepted }, null, code, C.fromPaise(total));
  }

  on('GET', '/api/v1/co-lending/arrangements', ({ user }) => {
    require(user, P.loanView);
    return ok([...db.coLending.arrangements].sort((a, b) => a.code.localeCompare(b.code)).map((a) => arrangementView(db, a)));
  });
  on('GET', '/api/v1/co-lending/arrangements/{code}', ({ user, params }) => {
    require(user, P.loanView);
    return ok(arrangementView(db, findArrangement(db, params.code)));
  });
  on('POST', '/api/v1/co-lending/arrangements', ({ user, body }) => {
    require(user, P.productPropose);
    const { input, change } = readArrangement(db, body);
    return propose(user, 'CO_LENDING_ARRANGEMENT', change ? 'CHANGE' : 'CREATE', { kind: 'CO_LENDING_ARRANGEMENT', arrangement: input },
      { ...input, partnerShare: plain(100 - num(input.originatorShare)), blendedRate: plain(blendedRate({ originatorShare: num(input.originatorShare), originatorRate: num(input.originatorRate), partnerRate: num(input.partnerRate) })) },
      change ? (arrangementView(db, change) as Record<string, unknown>) : null, input.code);
  });
  on('POST', '/api/v1/co-lending/arrangements/{code}/status', ({ user, params, body }) => {
    require(user, P.productPropose);
    const a = findArrangement(db, params.code);
    syncAll(db);
    const b = (body ?? {}) as { status?: string; reason?: string };
    const problems: string[] = [];
    const to = b.status as StoredArrangement['status'];
    if (!['ACTIVE', 'SUSPENDED', 'CLOSED'].includes(to)) problems.push('the status is ACTIVE, SUSPENDED or CLOSED');
    else if (to === a.status) problems.push(`arrangement ${a.code} is already ${a.status}`);
    else if (a.status === 'CLOSED') problems.push(`arrangement ${a.code} is closed`);
    if (to === 'CLOSED') {
      const open = db.coLending.loans.filter((c) => c.terms.code === a.code && (c.status === 'SANCTIONED' || c.status === 'OFFERED')).length;
      if (open) problems.push(`${open} loan(s) under it are sanctioned or offered to the partner: settle them first`);
    }
    if (!b.reason?.trim()) problems.push('a reason is required');
    refuse('The status cannot change', problems);
    return propose(user, 'CO_LENDING_ARRANGEMENT', to, { kind: 'CO_LENDING_STATUS', code: a.code, status: to, reason: b.reason!.trim() },
      { code: a.code, status: to, reason: b.reason!.trim() }, { status: a.status }, a.code);
  });
  on('GET', '/api/v1/co-lending/arrangements/{code}/offers', ({ user, params }) => {
    require(user, P.loanView);
    findArrangement(db, params.code);
    return ok(offers(db, params.code));
  });
  on('GET', '/api/v1/co-lending/arrangements/{code}/offers.csv', ({ user, params }) => {
    require(user, P.loanView);
    findArrangement(db, params.code);
    return { status: 200, body: null, raw: { contentType: 'text/csv; charset=UTF-8', data: offersCsv(offers(db, params.code)), fileName: `co-lending-offers-${params.code}.csv` } };
  });
  on('POST', '/api/v1/co-lending/arrangements/{code}/decisions', ({ user, params, body }) => {
    require(user, P.loanDisburse);
    return proposeDecisions(user, params.code, (body as { decisions?: CoLendingDecision[] } | null)?.decisions);
  });
  on('POST', '/api/v1/co-lending/arrangements/{code}/simulate-partner', ({ user, params }) => {
    require(user, P.loanDisburse);
    findArrangement(db, params.code);
    assertOpen();
    const open = offers(db, params.code).filter((o) => db.businessDate <= o.deadline!);
    if (!open.length) throw conflict('Nothing to decide', `No loan is offered to the partner of ${params.code} within its 15 days`);
    return proposeDecisions(user, params.code, open.map((o) => ({ loanNo: o.loanNo!, decision: 'ACCEPT', partnerLoanRef: `SIM-${o.loanNo}`, reason: null })));
  });
}

/** Demo data: one arrangement with a partner bank on the personal loan, when the tenant has it. */
export function seedCoLending(db: MockDb) {
  if (!db.loanProducts.some((p) => p.code === 'PL01' && !p.benchmarkCode && !p.multipleDisbursements)) return;
  db.coLending.arrangements.push({
    code: 'BANKX',
    name: 'Personal loans with Demo Partner Bank',
    partnerName: 'Demo Partner Bank Ltd',
    partnerKind: 'BANK',
    partnerReference: 'DPB-CLA-01',
    agreementReference: 'CLA/2026/01',
    originatorShare: 20,
    originatorRate: 22,
    partnerRate: 10.5,
    servicingFee: 0.5,
    feeSharing: 'ORIGINATOR',
    penalSharing: 'BY_SHARE',
    dlgPercent: 5,
    productCodes: ['PL01'],
    contact: 'Customer care 1800-000-000',
    status: 'ACTIVE',
    effectiveFrom: ISO_DATE.test(db.businessDate) ? db.businessDate : '2026-01-01',
    version: 1,
    createdBy: 'maker',
    updatedBy: null,
  });
}
