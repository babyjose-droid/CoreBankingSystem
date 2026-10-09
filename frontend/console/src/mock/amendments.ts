/**
 * Loan amendments and restructures in the mock API: previews, simulation, maker-checker proposals and history.
 * Amendments: one checker. Restructures: two different checkers (see decide() in server.ts).
 */
import type { AmendmentPreview, AmendmentRequest, LoanAmendment, RestructureSimulation, RestructureTerms } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { appendAudit, uuid, type ApprovalPayload, type MockDb, type StoredLoan } from './db';
import { amendState, cloneState, figuresOf, simulateOption, toRow, type Figures } from './amendCalc';
import { addEvent, arrearsOf, futurePrincipal, refreshLoan } from './lending';
import * as C from './lendingCalc';
import { bad, conflict, notFound } from './problems';

type Result = { status: number; body: unknown };
interface Ctx {
  user: DemoUser;
  body: unknown;
  params: Record<string, string>;
}
export interface AmendmentRouter {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (
    user: DemoUser,
    entityType: string,
    action: string,
    payload: ApprovalPayload,
    proposed: Record<string, unknown>,
    current?: Record<string, unknown> | null,
    entityId?: string | null,
    amount?: string | null,
  ) => Result;
  nowIso: () => string;
}

const ok = (body: unknown): Result => ({ status: 200, body });

function figureFields(before: Figures, after: Figures) {
  const d = (x: string | null) => x ?? undefined;
  return {
    rateBefore: C.pct(before.rate),
    rateAfter: C.pct(after.rate),
    emiBefore: C.fromPaise(before.emi),
    emiAfter: C.fromPaise(after.emi),
    remainingBefore: before.remaining,
    remainingAfter: after.remaining,
    nextDueBefore: d(before.nextDue),
    nextDueAfter: d(after.nextDue),
    maturityBefore: d(before.maturity),
    maturityAfter: d(after.maturity),
    interestBefore: C.fromPaise(before.interest),
    interestAfter: C.fromPaise(after.interest),
  };
}

function assertServiceable(loan: StoredLoan) {
  const s = loan.state.status;
  if (s === 'FROZEN') throw conflict('Loan is frozen', `Loan ${loan.loanNo} is frozen; unfreeze it first`);
  if (s !== 'ACTIVE') throw conflict('Loan is not active', `Loan ${loan.loanNo} is ${s}`);
}

function accruedNow(loan: StoredLoan, asOf: string): number {
  const st = loan.state;
  const from = st.lastInterestDate ?? asOf;
  return asOf > from ? C.interestFor(futurePrincipal(st), st.rate, C.daysBetween(from, asOf)) : 0;
}

export function previewAmendment(db: MockDb, loan: StoredLoan, req: AmendmentRequest): AmendmentPreview {
  const asOf = db.businessDate;
  if (req?.kind === 'SWITCH_TO_FIXED' && (!loan.product.benchmarkCode || loan.rateFixedSince)) {
    throw conflict('Not on a floating rate', `Loan ${loan.loanNo} is not on a floating rate: change the rate with a rate change`);
  }
  const st = cloneState(loan.state);
  const before = figuresOf(st);
  const current = st.rows.slice(st.raised).map(toRow);
  const broken = amendState(loan, st, req, asOf);
  return {
    ...figureFields(before, figuresOf(st)),
    brokenPeriodInterest: C.fromPaise(broken),
    asOf,
    kind: req.kind,
    principal: C.fromPaise(futurePrincipal(loan.state)),
    accruedInterest: C.fromPaise(accruedNow(loan, asOf)),
    schedule: st.rows.slice(st.raised).map(toRow),
    currentSchedule: current,
  };
}

function cleanRequest(req: AmendmentRequest): AmendmentRequest {
  const keep: Record<string, unknown> = { kind: req.kind };
  if (req.kind === 'RATE_CHANGE' || req.kind === 'SWITCH_TO_FIXED') Object.assign(keep, { newRatePercent: String(req.newRatePercent), rateOption: req.rateOption });
  if (req.remainingInstalments !== undefined && req.remainingInstalments !== null && (req.kind === 'TENURE_CHANGE' || req.rateOption === 'CHANGE_BOTH')) keep.remainingInstalments = req.remainingInstalments;
  if (req.newEmi !== undefined && req.newEmi !== null && req.newEmi !== '' && (req.kind === 'EMI_CHANGE' || (req.rateOption === 'CHANGE_BOTH' && keep.remainingInstalments === undefined))) keep.newEmi = String(req.newEmi);
  if (req.kind === 'DUE_DAY_CHANGE') keep.newDueDay = req.newDueDay;
  if (req.kind === 'MATURITY_CHANGE') keep.newMaturityDate = req.newMaturityDate;
  if (req.reason) keep.reason = req.reason.trim();
  return keep as AmendmentRequest;
}

function reasonOf(v: unknown): string {
  const r = typeof v === 'string' ? v.trim() : '';
  if (!r) throw bad('A reason is required', [{ field: 'reason', message: 'Required' }]);
  if (r.length > 500) throw bad('Reason must be at most 500 characters', [{ field: 'reason', message: 'Too long' }]);
  return r;
}

const summaryOf = (f: Record<string, unknown>) => {
  const g = (k: string) => f[k] as string | number | undefined;
  return `EMI ${g('emiBefore')} → ${g('emiAfter')}, ${g('remainingBefore')} → ${g('remainingAfter')} instalments, rate ${g('rateBefore')}% → ${g('rateAfter')}%`;
};

export function applyAmendmentApproval(
  db: MockDb,
  p: ApprovalPayload,
  approval: { id?: string; entityId?: string | null; maker?: string; appliedRef?: string | null },
  checker: string,
  at: string,
  priorCheckers: string[] = [],
): boolean {
  if (p.kind !== 'LOAN_AMENDMENT' && p.kind !== 'LOAN_RESTRUCTURE') return false;
  const loan = db.loans.find((l) => l.id === p.loanId);
  if (!loan) throw notFound('Loan');
  assertServiceable(loan);
  const before = figuresOf(loan.state);
  let applied: Record<string, unknown>;
  let txnId: string;
  if (p.kind === 'LOAN_AMENDMENT') {
    const preview = previewAmendment(db, loan, p.request); // re-run on the loan as it is now
    const { schedule: _s, currentSchedule: _c, ...figs } = preview;
    applied = figs;
    const ev = addEvent(db, loan, checker, at, { type: 'AMENDMENT', valueDate: db.businessDate, amount: null, summary: `${p.request.kind.replace(/_/g, ' ').toLowerCase()}: ${summaryOf(figs)}`, data: { amendment: p.request, reason: p.request.reason } });
    txnId = ev.id;
    if (p.request.kind === 'SWITCH_TO_FIXED') {
      loan.rateFixedSince = db.businessDate;   // no more resets
      loan.nextRateReset = null;
      loan.rateOutsideBand = false;
    }
  } else {
    const option = simulateOption(loan, loan.state, p.terms, db.businessDate);
    const { schedule: _s, ...figs } = option;
    applied = figs;
    const ev = addEvent(db, loan, checker, at, { type: 'RESTRUCTURE', valueDate: db.businessDate, amount: null, summary: `Restructured: ${summaryOf(figs)}; ${figs.classBefore} → ${figs.classAfter}`, data: { restructure: p.terms, reason: p.terms.reason } });
    txnId = ev.id;
  }
  refreshLoan(db, loan);
  const after = figuresOf(loan.state);
  const pick = (f: Record<string, unknown>) => JSON.stringify(['emiAfter', 'remainingAfter', 'rateAfter', 'interestAfter'].map((k) => f[k]));
  const checkers = [...priorCheckers, checker].join(', ');
  const rec: LoanAmendment & { id: string; txnId: string } = {
    id: uuid(),
    seq: loan.amendments.length + 1,
    txnId,
    kind: p.kind === 'LOAN_RESTRUCTURE' ? 'RESTRUCTURE' : p.request.kind,
    parameters: { ...(p.kind === 'LOAN_RESTRUCTURE' ? p.terms : p.request) },
    emiBefore: C.fromPaise(before.emi),
    emiAfter: C.fromPaise(after.emi),
    tenureBefore: before.remaining,
    tenureAfter: after.remaining,
    rateBefore: C.pct(before.rate),
    rateAfter: C.pct(after.rate),
    maturityBefore: before.maturity,
    maturityAfter: after.maturity ?? undefined,
    interestBefore: C.fromPaise(before.interest),
    interestAfter: C.fromPaise(after.interest),
    proposedFigures: p.figures,
    appliedFigures: applied,
    differsFromProposal: pick(p.figures) !== pick(applied),
    approvalId: approval.id,
    madeBy: approval.maker,
    checkedBy: checkers,
    businessDate: db.businessDate,
    reason: (p.kind === 'LOAN_RESTRUCTURE' ? p.terms.reason : p.request.reason) ?? null,
    reversedBy: null,
    createdAt: at,
  };
  loan.amendments.push(rec);
  approval.entityId = loan.id;
  approval.appliedRef = `${loan.loanNo} #${rec.seq}`;
  appendAudit(db, at, checker, p.kind === 'LOAN_RESTRUCTURE' ? 'LOAN_RESTRUCTURED' : 'LOAN_AMENDED', 'LOAN', loan.id, { kind: rec.kind, differsFromProposal: rec.differsFromProposal });
  return true;
}

export function registerAmendmentRoutes(db: MockDb, r: AmendmentRouter) {
  const { on, require, propose } = r;
  const findLoan = (id: string) => {
    const loan = db.loans.find((l) => l.id === id || l.loanNo === id);
    if (!loan) throw notFound('Loan');
    return loan;
  };
  const pendingFor = (loan: StoredLoan) =>
    db.approvals.find((s) => s.approval.status === 'PENDING' && (s.payload.kind === 'LOAN_AMENDMENT' || s.payload.kind === 'LOAN_RESTRUCTURE') && s.payload.loanId === loan.id);
  const assertNoPending = (loan: StoredLoan) => {
    const p = pendingFor(loan);
    if (p) throw conflict('Change already pending', `Loan ${loan.loanNo} already has a ${p.approval.entityType === 'LOAN_RESTRUCTURE' ? 'restructure' : 'amendment'} awaiting approval`, { approvalId: p.approval.id });
  };

  on('POST', '/api/v1/loans/{id}/amendments/preview', ({ user, params, body }) => {
    require(user, P.loanView);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    return ok(previewAmendment(db, loan, body as AmendmentRequest));
  });
  on('GET', '/api/v1/loans/{id}/amendments', ({ user, params }) => {
    require(user, P.loanView);
    return ok([...findLoan(params.id).amendments].sort((a, b) => (b.seq ?? 0) - (a.seq ?? 0)));
  });
  on('POST', '/api/v1/loans/{id}/amendments', ({ user, params, body }) => {
    require(user, P.loanAmend);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    const req = body as AmendmentRequest;
    const reason = reasonOf(req?.reason);
    const preview = previewAmendment(db, loan, req);
    assertNoPending(loan);
    const { schedule: _s, currentSchedule: _c, ...figs } = preview;
    const request = cleanRequest({ ...req, reason });
    return propose(
      user, 'LOAN_AMENDMENT', req.kind,
      { kind: 'LOAN_AMENDMENT', loanId: loan.id, request, figures: figs },
      { loanNo: loan.loanNo, ...request, rate: `${figs.rateAfter}%`, emi: figs.emiAfter, remainingInstalments: figs.remainingAfter, maturity: figs.maturityAfter, totalInterest: figs.interestAfter },
      { loanNo: loan.loanNo, rate: `${figs.rateBefore}%`, emi: figs.emiBefore, remainingInstalments: figs.remainingBefore, maturity: figs.maturityBefore, totalInterest: figs.interestBefore },
      loan.id,
    );
  });
  on('POST', '/api/v1/loans/{id}/restructure/simulation', ({ user, params, body }) => {
    require(user, P.loanView);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    const options = (body as { options?: RestructureTerms[] } | null)?.options;
    if (!Array.isArray(options) || options.length < 1 || options.length > 3) throw bad('Give one to three options', [{ field: 'options', message: '1 to 3 options' }]);
    const st = loan.state;
    const fut = st.rows.slice(st.raised);
    const sim: RestructureSimulation = {
      asOf: db.businessDate,
      current: {
        rate: C.pct(st.rate),
        emi: C.fromPaise(st.emi ?? fut[0]?.instalment ?? 0),
        remainingInstalments: fut.length,
        nextDueDate: fut[0]?.dueDate ?? null,
        principalOutstanding: C.fromPaise(futurePrincipal(st) + st.demands.reduce((s, d) => s + d.principalDue - d.principalPaid, 0)),
        assetClass: st.assetClass,
        dpd: st.dpd,
      },
      options: options.map((o) => simulateOption(loan, st, o, db.businessDate)),
    };
    return ok(sim);
  });
  on('POST', '/api/v1/loans/{id}/restructure', ({ user, params, body }) => {
    require(user, P.loanRestructure);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    const t = body as RestructureTerms;
    const reason = reasonOf(t?.reason);
    const option = simulateOption(loan, loan.state, t, db.businessDate);
    assertNoPending(loan);
    const { schedule: _s, ...figs } = option;
    const terms: RestructureTerms = {
      remainingInstalments: t.remainingInstalments,
      overdueInterest: t.overdueInterest,
      principalMoratoriumMonths: t.principalMoratoriumMonths ?? 0,
      ...(figs.newRatePercent ? { newRatePercent: figs.newRatePercent } : {}),
      reason,
    };
    return propose(
      user, 'LOAN_RESTRUCTURE', 'RESTRUCTURE',
      { kind: 'LOAN_RESTRUCTURE', loanId: loan.id, terms, figures: figs },
      {
        loanNo: loan.loanNo, reason,
        assetClass: figs.classAfter, rate: `${figs.rateAfter}%`, emi: figs.emiAfter, remainingInstalments: figs.remainingAfter, principal: figs.principalAfter,
        overdueInterest: t.overdueInterest, principalMoratoriumMonths: terms.principalMoratoriumMonths, npvLoss: figs.npvLoss, upgradeNotBefore: figs.upgradeNotBefore,
      },
      {
        loanNo: loan.loanNo, assetClass: figs.classBefore, rate: `${figs.rateBefore}%`, emi: figs.emiBefore, remainingInstalments: figs.remainingBefore, principal: figs.principalBefore,
        overdue: C.fromPaise(arrearsOf(loan.state)),
      },
      loan.id, figs.principalAfter,
    );
  });
}
