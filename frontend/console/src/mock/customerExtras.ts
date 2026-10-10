/**
 * Mock API for role amount limits, customer relationships and exposure, consent records (DPDP Act 2023) and KYC
 * documents. Document numbers are never stored: only the masked last four characters are kept.
 */
import type { AmountLimit, AmountLimitInput, Consent, ConsentInput, CustomerExposure, CustomerRelationship, KycDocument, LimitTxnType, RelationshipInput } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { addDays, ISO_DATE } from '../lib/dates';
import { isMoney } from '../lib/money';
import { appendAudit, fnv1a, uuid, type ApprovalPayload, type MockDb, type StoredCustomer } from './db';
import { displayName, exposureOf } from './lending';
import * as C from './lendingCalc';
import { limitInForce } from './limits';
import { branchScope } from './platform';
import { bad, conflict, HttpProblem, notFound, PROBLEM_BASE, type FieldProblem } from './problems';

type Result = { status: number; body: unknown; raw?: { contentType: string; data: Uint8Array | string; fileName: string } };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
  request: Request;
}
export interface ExtrasRouter {
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
const created = (body: unknown): Result => ({ status: 201, body });
const forbidden = (title: string, detail: string) => new HttpProblem(403, title, detail, {}, PROBLEM_BASE + 'forbidden');

export const LIMIT_TXN_TYPES: LimitTxnType[] = ['LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'LOAN_WAIVER', 'VOUCHER', 'LOAN_PRECLOSURE', 'FEE_WAIVER'];
const MAX_KYC_BYTES = 5 * 1024 * 1024;
const KYC_TYPES = ['PAN', 'AADHAAR_MASKED', 'ADDRESS_PROOF', 'PHOTO', 'PASSPORT', 'VOTER_ID', 'DRIVING_LICENCE'];
const CONSENT_PURPOSES = ['LOAN_PROCESSING', 'CREDIT_BUREAU_REPORTING', 'MARKETING', 'KYC_VERIFICATION', 'ACCOUNT_AGGREGATOR'];
/** Purposes needed to service a loan: a withdrawal is recorded but the data is retained while a loan exists. */
const SERVICING_PURPOSES = ['LOAN_PROCESSING', 'CREDIT_BUREAU_REPORTING', 'KYC_VERIFICATION'];
const CHANNELS = ['BRANCH', 'WEB', 'MOBILE_APP', 'API', 'PAPER', 'CALL_CENTRE'];
const RELATION_TYPES = ['CO_APPLICANT', 'GUARANTOR', 'NOMINEE', 'AUTHORISED_SIGNATORY'];

// ------------------------------------------------------------------ seed
export function seedCustomerExtras(db: MockDb, seedAt: string) {
  const limit = (roleName: string, txnType: LimitTxnType, perTransactionMax: string, perDayMax: string | null, effectiveFrom = '2026-04-01', effectiveTo: string | null = null): AmountLimit => ({
    id: uuid(), roleName, txnType, perTransactionMax, perDayMax, effectiveFrom, effectiveTo, createdBy: 'system', createdAt: seedAt, inForce: false,
  });
  db.amountLimits = [
    limit('MAKER', 'LOAN_DISBURSEMENT', '1000000.00', '5000000.00'),
    limit('MAKER', 'LOAN_REPAYMENT', '500000.00', null),
    limit('MAKER', 'LOAN_REPAYMENT', '200000.00', null, '2025-04-01', '2026-03-31'),
    limit('MAKER', 'VOUCHER', '5000000.00', null),
    limit('MAKER', 'FEE_WAIVER', '10000.00', '50000.00'),
    limit('CHECKER', 'LOAN_DISBURSEMENT', '2500000.00', null),
    limit('CHECKER', 'VOUCHER', '10000000.00', null),
  ];
  for (const [key, value, description] of [
    ['bureau.member-code', 'NB12345678', 'Member code issued by the credit bureau; needed for the bureau file'],
    ['kyc.required-documents', 'pan,address-proof,photo', 'Documents that must be verified for KYC to be VERIFIED'],
    ['limits.default-deny', 'false', 'true: a role without an amount limit for a transaction type may not do it'],
  ]) db.systemProperties.push({ key, value, description, updatedBy: 'system', updatedAt: seedAt });
  db.enumerations['kyc-document-type'] = KYC_TYPES.map((code, i) => ({ code, label: code.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()), sortOrder: (i + 1) * 10, active: true }));
  db.enumerations['consent-purpose'] = CONSENT_PURPOSES.map((code, i) => ({ code, label: code.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()), sortOrder: (i + 1) * 10, active: true }));

  const [anu, biju, , deepak, , , , hari] = db.customers;
  // Deepak's loan is guaranteed by Hari; Anu's has Biju as co-applicant.
  const loanOf = (c: StoredCustomer) => db.loans.find((l) => l.customerId === c.id);
  loanOf(deepak)?.parties.push({ customerId: hari.id, role: 'GUARANTOR', addedBy: 'maker', addedAt: seedAt });
  loanOf(anu)?.parties.push({ customerId: biju.id, role: 'CO_APPLICANT', addedBy: 'maker', addedAt: seedAt });
  const rel = (c: StoredCustomer, o: StoredCustomer, relationType: RelationshipInput['relationType'], sharePercent: string | null = null) =>
    db.relationships.push({ id: uuid(), customerId: c.id, relatedCustomerId: o.id, relationType, loanId: null, sharePercent, status: 'ACTIVE', createdBy: 'maker', createdAt: seedAt, endedAt: null });
  rel(deepak, hari, 'GUARANTOR');
  rel(anu, biju, 'CO_APPLICANT');
  rel(anu, biju, 'NOMINEE', '100');
  anu.exposureLimit = 500_000_00;

  const consent = (c: StoredCustomer, purpose: string, lawfulBasis: Consent['lawfulBasis'], extra: Partial<Consent> = {}) =>
    db.consents.push({
      id: uuid(), customerId: c.id, purpose, lawfulBasis, noticeVersion: 'PN-2026.1', channel: 'BRANCH', evidenceRef: lawfulBasis === 'CONSENT' ? 'OTP-CLAUDE-TEST-0001' : null,
      grantedAt: c.createdAt, expiresAt: null, status: 'ACTIVE', withdrawnAt: null, withdrawalReason: null, withdrawnBy: null, retainedForLegalObligation: false, recordedBy: 'maker', recordedAt: c.createdAt, ...extra,
    });
  consent(anu, 'LOAN_PROCESSING', 'CONSENT');
  consent(anu, 'CREDIT_BUREAU_REPORTING', 'LEGITIMATE_USE');
  consent(anu, 'MARKETING', 'CONSENT', { status: 'EXPIRED', expiresAt: seedAt });

  const doc = (c: StoredCustomer, docType: string, numberMasked: string | null, status: KycDocument['status'], extra: Partial<KycDocument> = {}) => {
    const content = new TextEncoder().encode(`%PDF-1.4\n% mock ${docType} of ${c.customerNo}\n%%EOF\n`);
    db.kycDocuments.push({
      content,
      meta: {
        id: uuid(), customerId: c.id, docType, numberMasked, issueDate: null, expiryDate: null, expired: false, status, statusReason: null,
        verifiedBy: status === 'VERIFIED' ? 'checker' : null, verifiedAt: status === 'VERIFIED' ? c.createdAt : null, maskingConfirmed: false,
        contentType: 'application/pdf', sizeBytes: content.length, sha256: fnv1a(String(content.length) + docType).padEnd(64, '0'), uploadedBy: 'maker', uploadedAt: c.createdAt, ...extra,
      },
    });
  };
  doc(anu, 'PAN', 'XXXXXX234C', 'VERIFIED');
  doc(anu, 'ADDRESS_PROOF', 'XXXX4521', 'VERIFIED', { expiryDate: '2031-03-31' });
  doc(anu, 'PHOTO', null, 'VERIFIED');
  doc(anu, 'PASSPORT', 'XXXX7788', 'PENDING', { expiryDate: '2026-01-31', expired: true });
}

// ------------------------------------------------------------------ helpers
function maskNumber(n: string): string | null {
  const v = n.replace(/\s+/g, '');
  if (!v) return null;
  return `${'X'.repeat(Math.max(0, Math.min(v.length, 10) - 4))}${v.slice(-4)}`;
}

function sniff(bytes: Uint8Array): KycDocument['contentType'] | null {
  const at = (i: number) => bytes[i];
  if (at(0) === 0x25 && at(1) === 0x50 && at(2) === 0x44 && at(3) === 0x46) return 'application/pdf';
  if (at(0) === 0xff && at(1) === 0xd8 && at(2) === 0xff) return 'image/jpeg';
  if (at(0) === 0x89 && at(1) === 0x50 && at(2) === 0x4e && at(3) === 0x47) return 'image/png';
  return null;
}

const normaliseDocType = (v: string) => v.trim().toUpperCase().replace(/-/g, '_');

/** KYC is VERIFIED when every required document is verified and unexpired. */
function recomputeKyc(db: MockDb, c: StoredCustomer): StoredCustomer['kycStatus'] {
  const required = (db.systemProperties.find((p) => p.key === 'kyc.required-documents')?.value ?? 'pan,address-proof,photo').split(',').map(normaliseDocType).filter(Boolean);
  const good = (t: string) => db.kycDocuments.some((d) => d.meta.customerId === c.id && d.meta.docType === t && d.meta.status === 'VERIFIED' && !(d.meta.expiryDate && d.meta.expiryDate < db.businessDate));
  // The mock only upgrades: seeded customers are VERIFIED without documents on file.
  if (required.every(good)) c.kycStatus = 'VERIFIED';
  return c.kycStatus;
}

export function exposureView(db: MockDb, c: StoredCustomer): CustomerExposure {
  const own = db.loans.filter((l) => l.customerId === c.id && exposureOf(l) > 0);
  const as = (role: 'CO_APPLICANT' | 'GUARANTOR') => db.loans.filter((l) => exposureOf(l) > 0 && l.parties.some((p) => p.customerId === c.id && p.role === role && !p.releasedOn));
  const sum = (ls: typeof own) => ls.reduce((s, l) => s + exposureOf(l), 0);
  const limit = c.exposureLimit ?? null;
  return {
    customerId: c.id,
    exposureLimit: limit === null ? null : C.fromPaise(limit),
    asBorrower: C.fromPaise(sum(own)),
    asCoApplicant: C.fromPaise(sum(as('CO_APPLICANT'))),
    asGuarantor: C.fromPaise(sum(as('GUARANTOR'))),
    loansAsBorrower: own.length,
    loansAsCoApplicant: as('CO_APPLICANT').length,
    loansAsGuarantor: as('GUARANTOR').length,
    available: limit === null ? null : C.fromPaise(Math.max(0, limit - sum(own))),
  };
}

// ------------------------------------------------------------------ approvals
export function applyExtrasApproval(db: MockDb, p: ApprovalPayload, approval: { entityId?: string | null; appliedRef?: string | null }, checker: string, at: string): boolean {
  switch (p.kind) {
    case 'AMOUNT_LIMIT': {
      // The limit then in force ends the day before the new one starts.
      for (const l of db.amountLimits) {
        if (l.roleName === p.limit.roleName && l.txnType === p.limit.txnType && l.effectiveFrom < p.limit.effectiveFrom && (!l.effectiveTo || l.effectiveTo >= p.limit.effectiveFrom)) {
          l.effectiveTo = addDays(p.limit.effectiveFrom, -1);
        }
      }
      db.amountLimits.push({ ...p.limit, createdAt: at });
      approval.entityId = p.limit.id;
      approval.appliedRef = `${p.limit.roleName} ${p.limit.txnType}`;
      return true;
    }
    case 'CUSTOMER_RELATIONSHIPS': {
      const c = db.customers.find((x) => x.id === p.customerId);
      if (!c) throw notFound('Customer');
      const nominees = p.relationships.filter((r) => r.relationType === 'NOMINEE');
      if (nominees.length) {
        // The set replaces the current nominees of the same account (or of the customer).
        const loanId = nominees[0].loanId ?? null;
        for (const r of db.relationships) if (r.customerId === c.id && r.relationType === 'NOMINEE' && r.status === 'ACTIVE' && r.loanId === loanId) Object.assign(r, { status: 'ENDED', endedAt: at });
      }
      for (const r of p.relationships) {
        db.relationships.push({
          id: uuid(), customerId: c.id, relatedCustomerId: r.relatedCustomerId, relationType: r.relationType, loanId: r.loanId ?? null,
          sharePercent: r.sharePercent === null || r.sharePercent === undefined || r.sharePercent === '' ? null : String(r.sharePercent), status: 'ACTIVE', createdBy: checker, createdAt: at, endedAt: null,
        });
      }
      approval.entityId = c.id;
      approval.appliedRef = c.customerNo;
      appendAudit(db, at, checker, 'CUSTOMER_RELATIONSHIPS_SET', 'CUSTOMER', c.id, { count: p.relationships.length });
      return true;
    }
    case 'EXPOSURE_LIMIT': {
      const c = db.customers.find((x) => x.id === p.customerId);
      if (!c) throw notFound('Customer');
      c.exposureLimit = p.limit;
      approval.entityId = c.id;
      approval.appliedRef = c.customerNo;
      appendAudit(db, at, checker, 'EXPOSURE_LIMIT_SET', 'CUSTOMER', c.id, { limit: p.limit === null ? null : C.fromPaise(p.limit), reason: p.reason });
      return true;
    }
    default:
      return false;
  }
}

// ------------------------------------------------------------------ routes
export function registerCustomerExtraRoutes(db: MockDb, r: ExtrasRouter) {
  const { on, require, propose, nowIso } = r;
  const customer = (id: string) => {
    const c = db.customers.find((x) => x.id === id || x.customerNo === id);
    if (!c) throw notFound('Customer');
    return c;
  };
  const inScope = (user: DemoUser, c: StoredCustomer) => {
    const s = branchScope(db, user.username, user.homeBranch);
    return s.allBranches || s.branches.includes(c.input.homeBranch);
  };
  const reasonOf = (v: unknown, what = 'A reason') => {
    const s = typeof v === 'string' ? v.trim() : '';
    if (!s) throw bad(`${what} is required`, [{ field: 'reason', message: 'Required' }]);
    if (s.length > 500) throw bad('Reason must be at most 500 characters', [{ field: 'reason', message: 'Too long' }]);
    return s;
  };
  const pendingOf = (kind: ApprovalPayload['kind'], pred: (p: ApprovalPayload) => boolean) => db.approvals.find((s) => s.approval.status === 'PENDING' && s.payload.kind === kind && pred(s.payload));

  // ---- role amount limits
  on('GET', '/api/v1/amount-limits', ({ user, url }) => {
    require(user, P.limitView);
    const role = url.searchParams.get('roleName');
    const txn = url.searchParams.get('txnType');
    const currentOnly = url.searchParams.get('currentOnly') === 'true';
    return ok(
      db.amountLimits
        .map((l) => ({ ...l, inForce: limitInForce(l, db.businessDate) }))
        .filter((l) => (!role || l.roleName === role) && (!txn || l.txnType === txn) && (!currentOnly || l.inForce))
        .sort((a, b) => a.roleName.localeCompare(b.roleName) || a.txnType.localeCompare(b.txnType) || b.effectiveFrom.localeCompare(a.effectiveFrom)),
    );
  });
  on('POST', '/api/v1/amount-limits', ({ user, body }) => {
    require(user, P.limitPropose);
    const b = (body ?? {}) as AmountLimitInput;
    const errors: FieldProblem[] = [];
    const money = (v: unknown) => (v === null || v === undefined || v === '' ? null : String(v));
    if (!/^[A-Za-z0-9][A-Za-z0-9_.:-]{1,63}$/.test(b.roleName ?? '')) errors.push({ field: 'roleName', message: 'Role must be 2-64 letters, digits or _ . : -' });
    if (!LIMIT_TXN_TYPES.includes(b.txnType)) errors.push({ field: 'txnType', message: 'Unknown transaction type' });
    const per = money(b.perTransactionMax);
    if (!per || !isMoney(per) || Number(per) <= 0) errors.push({ field: 'perTransactionMax', message: 'Per-transaction limit must be a positive amount' });
    const day = money(b.perDayMax);
    if (day !== null && (!isMoney(day) || Number(day) <= 0)) errors.push({ field: 'perDayMax', message: 'Per-day limit must be a positive amount' });
    else if (day !== null && per && isMoney(per) && Number(day) < Number(per)) errors.push({ field: 'perDayMax', message: 'Per-day limit cannot be below the per-transaction limit' });
    if (!ISO_DATE.test(b.effectiveFrom ?? '')) errors.push({ field: 'effectiveFrom', message: 'Effective from is required' });
    else if (b.effectiveFrom < db.businessDate) errors.push({ field: 'effectiveFrom', message: 'Effective from cannot be before the business date' });
    if (b.effectiveTo && (!ISO_DATE.test(b.effectiveTo) || b.effectiveTo < b.effectiveFrom)) errors.push({ field: 'effectiveTo', message: 'Effective to must be on or after effective from' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (db.amountLimits.some((l) => l.roleName === b.roleName && l.txnType === b.txnType && l.effectiveFrom === b.effectiveFrom)) {
      throw conflict('Limit already exists', `${b.roleName} already has a ${b.txnType} limit effective ${b.effectiveFrom}`);
    }
    if (pendingOf('AMOUNT_LIMIT', (p) => p.kind === 'AMOUNT_LIMIT' && p.limit.roleName === b.roleName && p.limit.txnType === b.txnType)) throw conflict('Change already pending', `${b.roleName} ${b.txnType} already has a limit awaiting approval`);
    const current = db.amountLimits.find((l) => l.roleName === b.roleName && l.txnType === b.txnType && limitInForce(l, db.businessDate));
    const limit: AmountLimit = {
      id: uuid(), roleName: b.roleName, txnType: b.txnType, perTransactionMax: C.fromPaise(C.toPaise(per!)), perDayMax: day === null ? null : C.fromPaise(C.toPaise(day)),
      effectiveFrom: b.effectiveFrom, effectiveTo: b.effectiveTo || null, createdBy: user.username, createdAt: nowIso(), inForce: false,
    };
    const view = (l: AmountLimit) => ({ roleName: l.roleName, txnType: l.txnType, perTransactionMax: l.perTransactionMax, perDayMax: l.perDayMax, effectiveFrom: l.effectiveFrom, effectiveTo: l.effectiveTo });
    return propose(user, 'AMOUNT_LIMIT', current ? 'UPDATE' : 'CREATE', { kind: 'AMOUNT_LIMIT', limit }, view(limit), current ? view(current) : null, null, limit.perTransactionMax);
  });

  // ---- relationships and exposure
  on('GET', '/api/v1/customers/{id}/relationships', ({ user, params }) => {
    require(user, P.customerView);
    const c = customer(params.id);
    const view = (x: (typeof db.relationships)[number], direction: CustomerRelationship['direction']): CustomerRelationship => {
      const other = db.customers.find((o) => o.id === (direction === 'OUTGOING' ? x.relatedCustomerId : x.customerId))!;
      return {
        id: x.id, direction, relationType: x.relationType, relatedCustomerId: other.id, relatedCustomerNo: other.customerNo, relatedCustomerName: inScope(user, other) ? displayName(other) : null,
        loanId: x.loanId, sharePercent: x.sharePercent, status: x.status, createdBy: x.createdBy, createdAt: x.createdAt, endedAt: x.endedAt,
      };
    };
    return ok([
      ...db.relationships.filter((x) => x.customerId === c.id).map((x) => view(x, 'OUTGOING')),
      ...db.relationships.filter((x) => x.relatedCustomerId === c.id).map((x) => view(x, 'INCOMING')),
    ]);
  });
  on('POST', '/api/v1/customers/{id}/relationships', ({ user, params, body }) => {
    require(user, P.customerCreate);
    const c = customer(params.id);
    const list = (body as { relationships?: RelationshipInput[] } | null)?.relationships;
    if (!Array.isArray(list) || list.length < 1 || list.length > 20) throw bad('Give 1 to 20 relationships', [{ field: 'relationships', message: '1 to 20' }]);
    const errors: FieldProblem[] = [];
    const seen = new Set<string>();
    list.forEach((x, i) => {
      const at = `Relationship ${i + 1}`;
      const other = db.customers.find((o) => o.id === x?.relatedCustomerId);
      if (!RELATION_TYPES.includes(x?.relationType)) errors.push({ field: `relationships[${i}].relationType`, message: `${at}: unknown relation type` });
      if (!other) errors.push({ field: `relationships[${i}].relatedCustomerId`, message: `${at}: customer not found` });
      else if (other.status !== 'ACTIVE') errors.push({ field: `relationships[${i}].relatedCustomerId`, message: `${at}: the related party must be an ACTIVE customer` });
      else if (other.id === c.id) errors.push({ field: `relationships[${i}].relatedCustomerId`, message: `${at}: a customer cannot be related to themselves` });
      const key = `${x?.relationType}|${x?.relatedCustomerId}|${x?.loanId ?? ''}`;
      if (seen.has(key)) errors.push({ field: `relationships[${i}]`, message: `${at}: named twice` });
      seen.add(key);
      if (x?.relationType === 'AUTHORISED_SIGNATORY') {
        if (c.input.customerType !== 'NON_INDIVIDUAL') errors.push({ field: `relationships[${i}].relationType`, message: `${at}: an authorised signatory acts for a non-individual customer` });
        else if (other && other.input.customerType !== 'INDIVIDUAL') errors.push({ field: `relationships[${i}].relatedCustomerId`, message: `${at}: an authorised signatory must be an individual` });
      }
      if (x?.relationType !== 'NOMINEE' && x?.sharePercent !== undefined && x?.sharePercent !== null && x?.sharePercent !== '') errors.push({ field: `relationships[${i}].sharePercent`, message: `${at}: a share applies to nominees only` });
      if (x?.loanId && !db.loans.some((l) => l.id === x.loanId && l.customerId === c.id)) errors.push({ field: `relationships[${i}].loanId`, message: `${at}: not a loan of this customer` });
    });
    const nominees = list.filter((x) => x?.relationType === 'NOMINEE');
    if (nominees.length) {
      const total = nominees.reduce((s, x) => s + Number(x.sharePercent ?? NaN), 0);
      if (nominees.some((x) => !(Number(x.sharePercent) > 0))) errors.push({ field: 'relationships', message: 'Every nominee needs a share above 0' });
      else if (Math.abs(total - 100) > 0.001) errors.push({ field: 'relationships', message: `Nominee shares must total 100 (they total ${total})` });
      if (new Set(nominees.map((x) => x.loanId ?? '')).size > 1) errors.push({ field: 'relationships', message: 'Send the nominees of one account at a time' });
    }
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (pendingOf('CUSTOMER_RELATIONSHIPS', (p) => p.kind === 'CUSTOMER_RELATIONSHIPS' && p.customerId === c.id)) throw conflict('Change already pending', `Customer ${c.customerNo} already has relationships awaiting approval`);
    const proposed = list.map((x) => {
      const other = db.customers.find((o) => o.id === x.relatedCustomerId)!;
      return { relationType: x.relationType, customer: `${other.customerNo} ${displayName(other)}`, ...(x.sharePercent !== undefined && x.sharePercent !== null && x.sharePercent !== '' ? { sharePercent: String(x.sharePercent) } : {}) };
    });
    return propose(user, 'CUSTOMER_RELATIONSHIP', 'CREATE', { kind: 'CUSTOMER_RELATIONSHIPS', customerId: c.id, relationships: list }, { customerNo: c.customerNo, name: displayName(c), relationships: proposed }, null, c.id);
  });
  on('GET', '/api/v1/customers/{id}/exposure', ({ user, params }) => {
    require(user, P.customerView);
    return ok(exposureView(db, customer(params.id)));
  });
  on('POST', '/api/v1/customers/{id}/exposure-limit', ({ user, params, body }) => {
    require(user, P.limitPropose);
    const c = customer(params.id);
    const b = (body ?? {}) as { exposureLimit?: string | number | null; reason?: string };
    const reason = reasonOf(b.reason);
    const raw = b.exposureLimit === null || b.exposureLimit === undefined || b.exposureLimit === '' ? null : String(b.exposureLimit);
    if (raw !== null && (!isMoney(raw) || Number(raw) <= 0)) throw bad('Exposure limit must be a positive amount (or empty to remove it)', [{ field: 'exposureLimit', message: 'Invalid amount' }]);
    const limit = raw === null ? null : C.toPaise(raw);
    const current = c.exposureLimit ?? null;
    if (limit === current) throw bad('Nothing to change: the limit is the same as today');
    if (pendingOf('EXPOSURE_LIMIT', (p) => p.kind === 'EXPOSURE_LIMIT' && p.customerId === c.id)) throw conflict('Change already pending', `Customer ${c.customerNo} already has an exposure limit awaiting approval`);
    const show = (v: number | null) => (v === null ? null : C.fromPaise(v));
    return propose(
      user, 'EXPOSURE_LIMIT', limit === null ? 'REMOVE' : current === null ? 'CREATE' : 'UPDATE',
      { kind: 'EXPOSURE_LIMIT', customerId: c.id, limit, reason },
      { customerNo: c.customerNo, name: displayName(c), exposureLimit: show(limit), reason },
      { customerNo: c.customerNo, name: displayName(c), exposureLimit: show(current) },
      c.id, show(limit),
    );
  });

  // ---- consents
  const consentView = (x: Consent): Consent => ({ ...x, status: x.status === 'ACTIVE' && x.expiresAt && x.expiresAt <= nowIso() ? 'EXPIRED' : x.status });
  on('GET', '/api/v1/customers/{id}/consents', ({ user, params }) => {
    require(user, P.consentView);
    const c = customer(params.id);
    return ok(db.consents.filter((x) => x.customerId === c.id).map(consentView).sort((a, b) => b.grantedAt.localeCompare(a.grantedAt) || b.recordedAt!.localeCompare(a.recordedAt!)));
  });
  on('POST', '/api/v1/customers/{id}/consents', ({ user, params, body }) => {
    require(user, P.consentRecord);
    const c = customer(params.id);
    const b = (body ?? {}) as ConsentInput;
    const errors: FieldProblem[] = [];
    const now = nowIso();
    if (!db.enumerations['consent-purpose']?.some((v) => v.code === b.purpose && v.active)) errors.push({ field: 'purpose', message: 'Unknown purpose' });
    if (b.lawfulBasis !== 'CONSENT' && b.lawfulBasis !== 'LEGITIMATE_USE') errors.push({ field: 'lawfulBasis', message: 'Lawful basis must be CONSENT or LEGITIMATE_USE' });
    if (!b.noticeVersion?.trim()) errors.push({ field: 'noticeVersion', message: 'Notice version is required' });
    if (!CHANNELS.includes(b.channel)) errors.push({ field: 'channel', message: 'Unknown channel' });
    if (b.lawfulBasis === 'CONSENT' && !b.evidenceRef?.trim()) errors.push({ field: 'evidenceRef', message: 'Evidence of the consent is required' });
    if (b.grantedAt && (Number.isNaN(Date.parse(b.grantedAt)) || b.grantedAt > now)) errors.push({ field: 'grantedAt', message: 'Granted at cannot be in the future' });
    if (b.expiresAt && (Number.isNaN(Date.parse(b.expiresAt)) || b.expiresAt <= (b.grantedAt ?? now))) errors.push({ field: 'expiresAt', message: 'Expiry must be after the grant' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (db.consents.some((x) => x.customerId === c.id && x.purpose === b.purpose && consentView(x).status === 'ACTIVE')) {
      throw conflict('A record is already in force', `${c.customerNo} already has a record in force for ${b.purpose}; withdraw it first`);
    }
    const rec: Consent = {
      id: uuid(), customerId: c.id, purpose: b.purpose, lawfulBasis: b.lawfulBasis, noticeVersion: b.noticeVersion.trim(), channel: b.channel, evidenceRef: b.evidenceRef?.trim() || null,
      grantedAt: b.grantedAt ?? now, expiresAt: b.expiresAt ?? null, status: 'ACTIVE', withdrawnAt: null, withdrawalReason: null, withdrawnBy: null, retainedForLegalObligation: false, recordedBy: user.username, recordedAt: now,
    };
    db.consents.push(rec);
    appendAudit(db, now, user.username, 'CONSENT_RECORDED', 'CUSTOMER', c.id, { purpose: rec.purpose, lawfulBasis: rec.lawfulBasis });
    return created(rec);
  });
  on('POST', '/api/v1/customers/{id}/consents/{consentId}/withdraw', ({ user, params, body }) => {
    require(user, P.consentRecord);
    const c = customer(params.id);
    const rec = db.consents.find((x) => x.id === params.consentId && x.customerId === c.id);
    if (!rec) throw notFound('Consent');
    const reason = reasonOf((body as { reason?: string } | null)?.reason);
    if (rec.lawfulBasis === 'LEGITIMATE_USE') throw conflict('Not a consent', 'A legitimate-use record cannot be withdrawn');
    if (consentView(rec).status !== 'ACTIVE') throw conflict('Consent is not in force', `This record is already ${consentView(rec).status}`);
    const live = db.loans.some((l) => exposureOf(l) > 0 && (l.customerId === c.id || l.parties.some((p) => p.customerId === c.id)));
    Object.assign(rec, { status: 'WITHDRAWN', withdrawnAt: nowIso(), withdrawalReason: reason, withdrawnBy: user.username, retainedForLegalObligation: live && SERVICING_PURPOSES.includes(rec.purpose) });
    appendAudit(db, rec.withdrawnAt!, user.username, 'CONSENT_WITHDRAWN', 'CUSTOMER', c.id, { purpose: rec.purpose, retained: rec.retainedForLegalObligation });
    return ok(rec);
  });

  // ---- KYC documents
  const findDoc = (c: StoredCustomer, docId: string) => {
    const d = db.kycDocuments.find((x) => x.meta.id === docId && x.meta.customerId === c.id);
    if (!d) throw notFound('KYC document');
    return d;
  };
  const docView = (m: KycDocument): KycDocument => ({ ...m, expired: !!m.expiryDate && m.expiryDate < db.businessDate });
  on('GET', '/api/v1/customers/{id}/kyc-documents', ({ user, params }) => {
    require(user, P.customerView);
    const c = customer(params.id);
    return ok(db.kycDocuments.filter((d) => d.meta.customerId === c.id).map((d) => docView(d.meta)).sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt)));
  });
  on('POST', '/api/v1/customers/{id}/kyc-documents', ({ user, params, url, body, request }) => {
    require(user, P.kycUpload);
    const c = customer(params.id);
    const docType = normaliseDocType(url.searchParams.get('docType') ?? '');
    if (!db.enumerations['kyc-document-type']?.some((v) => v.code === docType && v.active)) throw bad('Unknown document type', [{ field: 'docType', message: 'Not a value of kyc-document-type' }]);
    const declared = (request.headers.get('Content-Type') ?? '').split(';')[0].trim().toLowerCase();
    const bytes = body instanceof Uint8Array ? body : null;
    if (!bytes || bytes.length === 0) throw bad('The file is empty', [{ field: 'file', message: 'Required' }]);
    if (bytes.length > MAX_KYC_BYTES) throw new HttpProblem(413, 'File too large', 'A KYC document can be at most 5 MB', {}, PROBLEM_BASE + 'payload-too-large');
    const actual = sniff(bytes);
    if (!actual || actual !== declared) throw new HttpProblem(415, 'Unsupported file', 'The file must be a PDF, JPEG or PNG and match its Content-Type', {}, PROBLEM_BASE + 'unsupported-media-type');
    const number = (request.headers.get('X-Document-Number') ?? '').trim();
    if (number.length > 40) throw bad('Document number is too long', [{ field: 'X-Document-Number', message: 'At most 40 characters' }]);
    if (/^\d{4}\s?\d{4}\s?\d{4}$/.test(number)) throw bad('A full Aadhaar number must never be sent; give only its last four digits', [{ field: 'X-Document-Number', message: 'Full Aadhaar number refused' }]);
    if (docType === 'AADHAAR_MASKED' && number && !/^\d{4}$/.test(number)) throw bad('For Aadhaar send only the last four digits', [{ field: 'X-Document-Number', message: 'Last four digits only' }]);
    const [issueDate, expiryDate] = [url.searchParams.get('issueDate'), url.searchParams.get('expiryDate')];
    for (const [k, v] of [['issueDate', issueDate], ['expiryDate', expiryDate]] as const) if (v && !ISO_DATE.test(v)) throw bad(`${k} must be a date (YYYY-MM-DD)`, [{ field: k, message: 'Invalid date' }]);
    if (issueDate && issueDate > db.businessDate) throw bad('Issue date cannot be in the future', [{ field: 'issueDate', message: 'In the future' }]);
    const now = nowIso();
    const meta: KycDocument = {
      id: uuid(), customerId: c.id, docType, numberMasked: docType === 'AADHAAR_MASKED' && number ? `XXXX XXXX ${number}` : maskNumber(number), issueDate: issueDate || null, expiryDate: expiryDate || null, expired: false,
      status: 'PENDING', statusReason: null, verifiedBy: null, verifiedAt: null, maskingConfirmed: false, contentType: actual, sizeBytes: bytes.length,
      sha256: fnv1a(Array.from(bytes.slice(0, 2048)).join(',')).repeat(8), uploadedBy: user.username, uploadedAt: now,
    };
    db.kycDocuments.push({ meta, content: bytes });
    appendAudit(db, now, user.username, 'KYC_DOCUMENT_UPLOADED', 'CUSTOMER', c.id, { docType, docId: meta.id });
    return created({ ...docView(meta), customerKycStatus: c.kycStatus });
  });
  on('GET', '/api/v1/customers/{id}/kyc-documents/{docId}/content', ({ user, params }) => {
    require(user, P.kycViewDocument);
    const c = customer(params.id);
    const d = findDoc(c, params.docId);
    appendAudit(db, nowIso(), user.username, 'KYC_DOCUMENT_DOWNLOADED', 'CUSTOMER', c.id, { docId: d.meta.id });
    const ext = { 'application/pdf': 'pdf', 'image/jpeg': 'jpg', 'image/png': 'png' }[d.meta.contentType];
    return { status: 200, body: null, raw: { contentType: d.meta.contentType, data: d.content, fileName: `kyc-${d.meta.docType.toLowerCase().replace(/_/g, '-')}-${d.meta.id.slice(0, 4)}.${ext}` } };
  });
  const decide = (user: DemoUser, params: Record<string, string>) => {
    require(user, P.kycVerify);
    const c = customer(params.id);
    const d = findDoc(c, params.docId);
    if (d.meta.uploadedBy === user.username) throw forbidden('Uploader cannot verify', 'A KYC document must be verified or rejected by someone other than the person who uploaded it');
    if (d.meta.status !== 'PENDING') throw conflict('Document is not pending', `This document is already ${d.meta.status}`);
    return { c, d };
  };
  on('POST', '/api/v1/customers/{id}/kyc-documents/{docId}/verify', ({ user, params, body }) => {
    const { c, d } = decide(user, params);
    const b = (body ?? {}) as { maskingConfirmed?: boolean; note?: string };
    if (docView(d.meta).expired) throw conflict('Document has expired', `It expired on ${d.meta.expiryDate}; ask for a current document`);
    if (d.meta.docType === 'AADHAAR_MASKED' && b.maskingConfirmed !== true) throw bad('Confirm that the copy shows only the last four Aadhaar digits', [{ field: 'maskingConfirmed', message: 'Required for Aadhaar' }]);
    Object.assign(d.meta, { status: 'VERIFIED', verifiedBy: user.username, verifiedAt: nowIso(), statusReason: b.note?.trim() || null, maskingConfirmed: b.maskingConfirmed === true });
    appendAudit(db, d.meta.verifiedAt!, user.username, 'KYC_DOCUMENT_VERIFIED', 'CUSTOMER', c.id, { docId: d.meta.id, docType: d.meta.docType });
    return ok({ ...docView(d.meta), customerKycStatus: recomputeKyc(db, c) });
  });
  on('POST', '/api/v1/customers/{id}/kyc-documents/{docId}/reject', ({ user, params, body }) => {
    const { c, d } = decide(user, params);
    const reason = reasonOf((body as { reason?: string } | null)?.reason);
    Object.assign(d.meta, { status: 'REJECTED', verifiedBy: user.username, verifiedAt: nowIso(), statusReason: reason });
    appendAudit(db, d.meta.verifiedAt!, user.username, 'KYC_DOCUMENT_REJECTED', 'CUSTOMER', c.id, { docId: d.meta.id, reason });
    return ok({ ...docView(d.meta), customerKycStatus: recomputeKyc(db, c) });
  });
}
