/**
 * Platform completion (P2-7) in the mock API: custom fields, login sessions, the job catalogue, support access and
 * receipts accepted during end of day (deferred receipts).
 */
import type { CustomField, CustomFieldInput, DeferredReceipt, Job, JobRun, JobScheduleInput, LoginSession, SupportAccess } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { DEMO_USERS } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { ISO_DATE } from '../lib/dates';
import { appendAudit, uuid, type ApprovalPayload, type MockDb, type StoredLoan } from './db';
import { addEvent, refreshLoan } from './lending';
import * as C from './lendingCalc';
import { branchScope } from './platform';
import { bad, conflict, HttpProblem, notFound, PROBLEM_BASE, type FieldProblem } from './problems';

type Result = { status: number; body: unknown };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
}
export interface PlatformMoreRouter {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (user: DemoUser, entityType: string, action: string, payload: ApprovalPayload, proposed: Record<string, unknown>, current?: Record<string, unknown> | null, entityId?: string | null, amount?: string | null) => Result;
  nowIso: () => string;
}
const ok = (body: unknown): Result => ({ status: 200, body });
export type CustomEntity = CustomFieldInput['entity'];

// ------------------------------------------------------------------ custom fields
/** Every problem at once, as `custom.<key>` field errors (unknown key, wrong type, missing required field). */
export function customProblems(db: MockDb, entity: CustomEntity, values: unknown): FieldProblem[] {
  const errors: FieldProblem[] = [];
  const v = (values ?? {}) as Record<string, unknown>;
  if (typeof v !== 'object' || Array.isArray(v)) return [{ field: 'custom', message: 'Custom values must be an object' }];
  const defs = db.customFields.filter((f) => f.entity === entity && f.active);
  for (const k of Object.keys(v)) if (!defs.some((d) => d.key === k)) errors.push({ field: `custom.${k}`, message: `Unknown custom field ${k}` });
  for (const d of defs) {
    const x = v[d.key!];
    const at = `custom.${d.key}`;
    const empty = x === undefined || x === null || x === '';
    if (empty) {
      if (d.required) errors.push({ field: at, message: `${d.label} is required` });
      continue;
    }
    if (d.dataType === 'NUMBER') {
      if (typeof x !== 'number' && !/^-?\d+(\.\d+)?$/.test(String(x))) errors.push({ field: at, message: `${d.label} must be a number` });
      else if (d.min !== null && d.min !== undefined && Number(x) < Number(d.min)) errors.push({ field: at, message: `${d.label} must be at least ${d.min}` });
      else if (d.max !== null && d.max !== undefined && Number(x) > Number(d.max)) errors.push({ field: at, message: `${d.label} must be at most ${d.max}` });
    } else if (d.dataType === 'DATE') {
      if (!ISO_DATE.test(String(x))) errors.push({ field: at, message: `${d.label} must be a date (YYYY-MM-DD)` });
    } else if (d.dataType === 'BOOLEAN') {
      if (typeof x !== 'boolean') errors.push({ field: at, message: `${d.label} must be true or false` });
    } else if (d.dataType === 'ENUM') {
      if (!db.enumerations[d.enumType ?? '']?.some((e) => e.code === x && e.active)) errors.push({ field: at, message: `${d.label}: ${String(x)} is not a value of ${d.enumType}` });
    } else {
      const s = String(x);
      if (typeof x !== 'string') errors.push({ field: at, message: `${d.label} must be text` });
      else if (d.regex && !new RegExp(`^(?:${d.regex})$`).test(s)) errors.push({ field: at, message: `${d.label} is not in the expected format` });
      else if (d.min !== null && d.min !== undefined && s.length < Number(d.min)) errors.push({ field: at, message: `${d.label} must have at least ${d.min} characters` });
      else if (d.max !== null && d.max !== undefined && s.length > Number(d.max)) errors.push({ field: at, message: `${d.label} must have at most ${d.max} characters` });
    }
  }
  return errors;
}

export function assertCustom(db: MockDb, entity: CustomEntity, values: unknown): Record<string, unknown> {
  const errors = customProblems(db, entity, values);
  if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
  return Object.fromEntries(Object.entries((values ?? {}) as Record<string, unknown>).filter(([, x]) => x !== undefined && x !== null && x !== ''));
}

/** Personal-data fields are returned masked (last two characters). */
export function maskCustom(db: MockDb, entity: CustomEntity, values: Record<string, unknown> | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, x] of Object.entries(values ?? {})) {
    const d = db.customFields.find((f) => f.entity === entity && f.key === k);
    out[k] = d?.pii && typeof x === 'string' ? `${'X'.repeat(Math.max(2, x.length - 2))}${x.slice(-2)}` : x;
  }
  return out;
}

// ------------------------------------------------------------------ cron (six fields, IST)
const IST_OFFSET_MS = 330 * 60_000;
export function cronProblem(expr: string): string | null {
  const f = expr.trim().split(/\s+/);
  if (f.length !== 6) return 'A six-field cron (second minute hour day-of-month month day-of-week) is required';
  const ranges: Array<[number, number]> = [[0, 59], [0, 59], [0, 23], [1, 31], [1, 12], [0, 7]];
  for (let i = 0; i < 6; i++) {
    if (f[i] === '*' || f[i] === '?') continue;
    for (const part of f[i].split(',')) {
      const m = part.match(/^(\d+)(?:-(\d+))?$/);
      if (!m || Number(m[1]) < ranges[i][0] || Number(m[2] ?? m[1]) > ranges[i][1]) return `Cron field ${i + 1} ("${f[i]}") is not valid`;
    }
  }
  return null;
}
const cronSet = (field: string, lo: number, hi: number): number[] => {
  if (field === '*' || field === '?') return Array.from({ length: hi - lo + 1 }, (_, i) => lo + i);
  return field.split(',').flatMap((p) => {
    const [a, b] = p.split('-').map(Number);
    return Array.from({ length: (b ?? a) - a + 1 }, (_, i) => a + i);
  });
};
/** Next fire time of a six-field cron in IST after `after` (ISO, UTC); null when none in the next 400 days. */
export function nextCronFire(expr: string, after: string): string | null {
  if (cronProblem(expr)) return null;
  const [, m, h, dom, mon, dow] = expr.trim().split(/\s+/);
  const minutes = m === '*' ? [0] : cronSet(m, 0, 59);
  const hours = cronSet(h, 0, 23);
  const [doms, mons, dows] = [cronSet(dom, 1, 31), cronSet(mon, 1, 12), cronSet(dow, 0, 7).map((d) => d % 7)];
  const start = new Date(Date.parse(after) + IST_OFFSET_MS); // IST wall clock held in a UTC date
  for (let day = 0; day < 400; day++) {
    const d = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), start.getUTCDate() + day));
    if (!mons.includes(d.getUTCMonth() + 1) || !doms.includes(d.getUTCDate()) || !dows.includes(d.getUTCDay())) continue;
    for (const hh of hours) for (const mm of minutes) {
      const t = d.getTime() + (hh * 60 + mm) * 60_000;
      if (t > start.getTime()) return new Date(t - IST_OFFSET_MS).toISOString();
    }
  }
  return null;
}

// ------------------------------------------------------------------ seed
export function seedPlatformMore(db: MockDb, seedAt: string, now: number) {
  const field = (entity: CustomEntity, key: string, label: string, dataType: string, extra: Partial<CustomField> = {}): CustomField => ({
    entity, key, label, dataType, enumType: null, widget: { TEXT: 'TEXT', NUMBER: 'NUMBER', DATE: 'DATE', BOOLEAN: 'CHECKBOX', ENUM: 'SELECT' }[dataType] ?? 'TEXT',
    required: false, regex: null, min: null, max: null, pii: false, active: true, sortOrder: 0, updatedBy: 'system', updatedAt: seedAt, ...extra,
  });
  db.enumerations.occupation = [['SALARIED', 'Salaried'], ['SELF_EMPLOYED', 'Self-employed'], ['FARMER', 'Farmer'], ['RETIRED', 'Retired']].map(([code, label], i) => ({ code, label, sortOrder: (i + 1) * 10, active: true }));
  db.customFields = [
    field('CUSTOMER', 'occupation', 'Occupation', 'ENUM', { enumType: 'occupation', sortOrder: 10 }),
    field('CUSTOMER', 'employeeId', 'Employee id', 'TEXT', { pii: true, max: '20', sortOrder: 20 }),
    field('CUSTOMER', 'dependants', 'Dependants', 'NUMBER', { min: '0', max: '20', sortOrder: 30 }),
    field('LOAN_ACCOUNT', 'purpose', 'Loan purpose', 'ENUM', { enumType: 'loan-purpose', sortOrder: 10 }),
    field('LOAN_ACCOUNT', 'dsaCode', 'Sourcing agent code', 'TEXT', { regex: '[A-Z]{3}[0-9]{4}', sortOrder: 20 }),
    field('LOAN_ACCOUNT', 'insured', 'Credit life insurance', 'BOOLEAN', { sortOrder: 30 }),
    field('LOAN_PRODUCT', 'riskCategory', 'Risk category', 'TEXT', { sortOrder: 10 }),
    field('LOAN_PRODUCT', 'launchedOn', 'Launched on', 'DATE', { sortOrder: 20 }),
  ];
  db.productCustom = { PL01: { riskCategory: 'B', launchedOn: '2025-04-01' } };
  db.customers[0].custom = { occupation: 'SALARIED', employeeId: 'EMP-CLAUDE-TEST-0042', dependants: 2 };
  if (db.loans[0]) db.loans[0].custom = { purpose: 'HOME_RENOVATION', insured: true };

  const ago = (h: number) => new Date(now - h * 3600_000).toISOString();
  db.sessions = DEMO_USERS.flatMap((u, i) => [
    { id: `sess-${u.username}-1`, username: u.username, ipAddress: `10.20.${i + 1}.15`, startedAt: ago(2), lastAccessAt: ago(0.05), clients: ['corebanking-console'], current: false },
    { id: `sess-${u.username}-2`, username: u.username, ipAddress: `203.0.113.${40 + i}`, startedAt: ago(30), lastAccessAt: ago(26), clients: ['corebanking-console', 'account'], current: false },
  ]);

  const job = (code: string, name: string, kind: Job['kind'], schedule: string | null, enabled = true, parameters: Record<string, unknown> = {}): Job => ({ code, name, kind, schedule, enabled, parameters, builtIn: kind !== 'REPORT', updatedBy: 'system', updatedAt: seedAt, runnable: true });
  db.jobs = [
    job('DEFERRED_RECEIPTS', 'Book receipts accepted during end of day', 'DEFERRED_RECEIPTS', '0 15 * * * *', true),
    job('DASHBOARD_REFRESH', 'Refresh dashboard figures', 'DASHBOARD_REFRESH', '0 0 * * * *'),
    job('KYC_EXPIRY', 'Flag expiring KYC documents', 'KYC_EXPIRY', '0 30 6 * * *'),
    job('CONSENT_EXPIRY', 'Expire consents past their date', 'CONSENT_EXPIRY', '0 45 6 * * *'),
    job('EXPORT_CLEANUP', 'Remove report files past retention', 'EXPORT_CLEANUP', '0 0 2 * * 0'),
    job('USAGE_SNAPSHOT', 'Usage snapshot for billing', 'USAGE_SNAPSHOT', '0 0 1 1 * *'),
    job('REPORT_DPD_AGEING', 'Report: DPD ageing', 'REPORT', '0 0 6 * * *', false, { period: 'BUSINESS_DATE', parameters: {}, emailTo: 'credit@demo-nbfc.example' }),
    job('REPORT_COLLECTIONS', 'Report: Collections', 'REPORT', '0 0 7 1 * *', true, { period: 'PREVIOUS_MONTH', parameters: {}, emailTo: 'collections@demo-nbfc.example' }),
  ];
  db.jobRuns = [
    { id: uuid(), jobCode: 'KYC_EXPIRY', origin: 'SCHEDULE', scheduledFor: ago(6), requestedBy: 'system', status: 'COMPLETED', startedAt: ago(6), finishedAt: ago(5.99), processed: 1, failed: 0, error: null, artifact: null },
    { id: uuid(), jobCode: 'REPORT_COLLECTIONS', origin: 'SCHEDULE', scheduledFor: ago(50), requestedBy: 'maker', status: 'COMPLETED', startedAt: ago(50), finishedAt: ago(49.99), processed: 12, failed: 0, error: null, artifact: { delivery: 'SENT', recipientsSent: 1, recipientsLeftOut: 0 } },
    { id: uuid(), jobCode: 'EXPORT_CLEANUP', origin: 'SCHEDULE', scheduledFor: ago(70), requestedBy: 'system', status: 'FAILED', startedAt: ago(70), finishedAt: ago(69.98), processed: 0, failed: 1, error: 'Document store not reachable', artifact: null },
  ];

  const sa = (engineer: string, ticket: string, reason: string, durationMinutes: number, status: SupportAccess['status'], extra: Partial<SupportAccess> = {}): SupportAccess => ({
    id: uuid(), engineer, ticket, reason, scope: 'READ_ONLY', durationMinutes, requestedAt: ago(1), status, decidedBy: null, decidedAt: null, decisionNote: null, expiresAt: null, revokedBy: null, revokedAt: null, revokeReason: null, ...extra,
  });
  db.supportAccess = [
    sa('ravi.k@corebanking.example', 'SUP-CLAUDE-TEST-2041', 'Investigate a failed end-of-day step reported by the tenant', 120, 'REQUESTED'),
    sa('nisha.p@corebanking.example', 'SUP-CLAUDE-TEST-2032', 'Check a voucher that does not show in the trial balance', 60, 'APPROVED', { requestedAt: ago(1.5), decidedBy: 'admin', decidedAt: ago(0.5), decisionNote: 'Read-only is fine', expiresAt: new Date(now + 30 * 60_000).toISOString() }),
    sa('ravi.k@corebanking.example', 'SUP-CLAUDE-TEST-1987', 'Report file layout question', 30, 'EXPIRED', { requestedAt: ago(200), decidedBy: 'admin', decidedAt: ago(199), expiresAt: ago(198.5) }),
  ];
}

// ------------------------------------------------------------------ deferred receipts
export function deferReceipt(db: MockDb, loan: StoredLoan, user: DemoUser, amount: number, mode: string | null, reference: string | null, at: string, nextDate: string): DeferredReceipt {
  const r: DeferredReceipt = {
    id: uuid(), loanId: loan.id, loanNo: loan.loanNo, amount: C.fromPaise(amount), mode, reference, receivedAt: at, receivedBy: user.username, cutoffBusinessDate: db.businessDate, expectedPostingDate: nextDate,
    status: 'PENDING', postingDate: null, valueDate: null, loanTxnId: null, attempts: 0, error: null, resolvedBy: null, resolutionNote: null,
  };
  db.deferredReceipts.push(r);
  appendAudit(db, at, user.username, 'RECEIPT_DEFERRED', 'LOAN', loan.id, { amount: r.amount, expectedPostingDate: nextDate });
  return r;
}

/** Books PENDING receipts on the (new) business date; one that cannot be booked becomes FAILED and waits for a person. */
export function bookDeferredReceipts(db: MockDb, at: string): { processed: number; failed: number } {
  let processed = 0;
  let failed = 0;
  for (const r of db.deferredReceipts.filter((x) => x.status === 'PENDING' && (x.expectedPostingDate ?? '') <= db.businessDate)) {
    const loan = db.loans.find((l) => l.id === r.loanId);
    r.attempts = (r.attempts ?? 0) + 1;
    if (!loan || loan.state.status !== 'ACTIVE') {
      r.status = 'FAILED';
      r.error = !loan ? 'Loan not found' : loan.state.status === 'FROZEN' ? 'Loan is frozen: transactions are blocked' : `Loan is ${loan.state.status}`;
      failed += 1;
      continue;
    }
    const ev = addEvent(db, loan, r.receivedBy ?? 'system', at, { type: 'REPAYMENT', valueDate: db.businessDate, amount: C.toPaise(r.amount ?? '0'), summary: `Receipt via ${r.mode ?? 'API'} accepted during end of day on ${r.cutoffBusinessDate}`, data: { mode: r.mode ?? undefined, reference: r.reference ?? undefined } });
    refreshLoan(db, loan);
    Object.assign(r, { status: 'APPLIED', postingDate: db.businessDate, valueDate: db.businessDate, loanTxnId: ev.id, error: null });
    processed += 1;
  }
  return { processed, failed };
}

// ------------------------------------------------------------------ approvals
export function applyPlatformMoreApproval(db: MockDb, p: ApprovalPayload, approval: { entityId?: string | null; appliedRef?: string | null }, checker: string, at: string): boolean {
  switch (p.kind) {
    case 'CUSTOM_FIELD': {
      const i = db.customFields.findIndex((f) => f.entity === p.field.entity && f.key === p.field.key);
      const rec = { ...p.field, updatedBy: checker, updatedAt: at };
      if (i >= 0) db.customFields[i] = rec;
      else db.customFields.push(rec);
      approval.entityId = `${p.field.entity}.${p.field.key}`;
      return true;
    }
    case 'PRODUCT_CUSTOM':
      db.productCustom[p.code] = { ...p.custom };
      approval.entityId = p.code;
      return true;
    case 'JOB_SCHEDULE': {
      const j = db.jobs.find((x) => x.code === p.code);
      if (!j) throw notFound('Job');
      Object.assign(j, { schedule: p.schedule, enabled: p.enabled, parameters: p.parameters, updatedBy: checker, updatedAt: at });
      approval.entityId = p.code;
      return true;
    }
    default:
      return false;
  }
}

// ------------------------------------------------------------------ routes
export function registerPlatformMoreRoutes(db: MockDb, r: PlatformMoreRouter) {
  const { on, require, propose, nowIso } = r;
  const pendingOf = (kind: ApprovalPayload['kind'], pred: (p: ApprovalPayload) => boolean) => db.approvals.find((s) => s.approval.status === 'PENDING' && s.payload.kind === kind && pred(s.payload));

  // ---- custom fields
  on('GET', '/api/v1/custom-fields', ({ user, url }) => {
    require(user, P.customFieldView);
    const entity = url.searchParams.get('entity');
    return ok(db.customFields.filter((f) => !entity || f.entity === entity).sort((a, b) => (a.entity ?? '').localeCompare(b.entity ?? '') || (a.sortOrder ?? 0) - (b.sortOrder ?? 0)));
  });
  on('POST', '/api/v1/custom-fields', ({ user, body }) => {
    require(user, P.customFieldPropose);
    const b = (body ?? {}) as CustomFieldInput;
    const errors: FieldProblem[] = [];
    if (!['CUSTOMER', 'LOAN_ACCOUNT', 'LOAN_PRODUCT'].includes(b.entity)) errors.push({ field: 'entity', message: 'Unknown entity' });
    if (!/^[a-z][A-Za-z0-9]{1,39}$/.test(b.key ?? '')) errors.push({ field: 'key', message: 'Key must start with a lower-case letter and have 2-40 letters or digits' });
    if (!b.label?.trim() || b.label.length > 80) errors.push({ field: 'label', message: 'Label is required (at most 80 characters)' });
    if (!['TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'ENUM'].includes(b.dataType)) errors.push({ field: 'dataType', message: 'Unknown data type' });
    if (b.dataType === 'ENUM' && !db.enumerations[b.enumType ?? '']) errors.push({ field: 'enumType', message: 'Choose the enumeration that lists the choices' });
    if (b.pii && b.dataType !== 'TEXT') errors.push({ field: 'pii', message: 'A personal-data field must be TEXT' });
    if (b.regex) {
      try {
        new RegExp(b.regex);
      } catch {
        errors.push({ field: 'regex', message: 'The pattern is not a valid regular expression' });
      }
      if (b.dataType !== 'TEXT') errors.push({ field: 'regex', message: 'A pattern applies to TEXT only' });
    }
    if (b.min !== null && b.min !== undefined && b.max !== null && b.max !== undefined && b.max < b.min) errors.push({ field: 'max', message: 'Max must be at least min' });
    const existing = db.customFields.find((f) => f.entity === b.entity && f.key === b.key);
    if (existing && existing.dataType !== b.dataType) errors.push({ field: 'dataType', message: 'The type of an existing field cannot change' });
    if (existing && !!existing.pii !== !!b.pii) errors.push({ field: 'pii', message: 'The personal-data flag of an existing field cannot change' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (pendingOf('CUSTOM_FIELD', (p) => p.kind === 'CUSTOM_FIELD' && p.field.entity === b.entity && p.field.key === b.key)) throw conflict('Change already pending', `${b.entity}.${b.key} already has a change awaiting approval`);
    const field: CustomField = {
      entity: b.entity, key: b.key, label: b.label.trim(), dataType: b.dataType, enumType: b.dataType === 'ENUM' ? b.enumType : null,
      widget: b.widget ?? { TEXT: 'TEXT', NUMBER: 'NUMBER', DATE: 'DATE', BOOLEAN: 'CHECKBOX', ENUM: 'SELECT' }[b.dataType], required: !!b.required, regex: b.regex || null,
      min: b.min === null || b.min === undefined ? null : String(b.min), max: b.max === null || b.max === undefined ? null : String(b.max), pii: !!b.pii, active: b.active ?? true, sortOrder: b.sortOrder ?? 0,
    };
    const strip = ({ updatedBy: _b, updatedAt: _a, ...rest }: CustomField) => rest;
    return propose(user, 'CUSTOM_FIELD', existing ? 'UPDATE' : 'CREATE', { kind: 'CUSTOM_FIELD', field }, { ...field }, existing ? strip(existing) : null, existing ? `${b.entity}.${b.key}` : null);
  });
  on('GET', '/api/v1/loan-products/{code}/custom', ({ user, params }) => {
    require(user, P.productView);
    if (!db.loanProducts.some((p) => p.code === params.code)) throw notFound(`Loan product ${params.code}`);
    return ok({ productCode: params.code, custom: maskCustom(db, 'LOAN_PRODUCT', db.productCustom[params.code]) });
  });
  on('PUT', '/api/v1/loan-products/{code}/custom', ({ user, params, body }) => {
    require(user, P.productPropose);
    if (!db.loanProducts.some((p) => p.code === params.code)) throw notFound(`Loan product ${params.code}`);
    const custom = assertCustom(db, 'LOAN_PRODUCT', (body as { custom?: unknown } | null)?.custom);
    if (pendingOf('PRODUCT_CUSTOM', (p) => p.kind === 'PRODUCT_CUSTOM' && p.code === params.code)) throw conflict('Change already pending', `Product ${params.code} already has custom values awaiting approval`);
    return propose(user, 'LOAN_PRODUCT_CUSTOM', 'UPDATE', { kind: 'PRODUCT_CUSTOM', code: params.code, custom }, { code: params.code, ...custom }, { code: params.code, ...(db.productCustom[params.code] ?? {}) }, params.code);
  });

  // ---- deferred receipts
  const inScope = (user: DemoUser) => {
    const s = branchScope(db, user.username, user.homeBranch);
    return (rec: DeferredReceipt) => s.allBranches || s.branches.includes(db.loans.find((l) => l.id === rec.loanId)?.branch ?? '');
  };
  on('GET', '/api/v1/deferred-receipts', ({ user, url }) => {
    require(user, P.loanView);
    const status = url.searchParams.get('status');
    return ok(db.deferredReceipts.filter(inScope(user)).filter((x) => !status || x.status === status).sort((a, b) => (b.receivedAt ?? '').localeCompare(a.receivedAt ?? '')));
  });
  on('GET', '/api/v1/loans/{id}/deferred-receipts', ({ user, params }) => {
    require(user, P.loanView);
    return ok(db.deferredReceipts.filter((x) => x.loanId === params.id).sort((a, b) => (b.receivedAt ?? '').localeCompare(a.receivedAt ?? '')));
  });
  const failed = (id: string) => {
    const x = db.deferredReceipts.find((y) => y.id === id);
    if (!x) throw notFound('Deferred receipt');
    if (x.status !== 'FAILED') throw conflict('Receipt is not failed', `This receipt is ${x.status}`);
    return x;
  };
  on('POST', '/api/v1/deferred-receipts/{id}/retry', ({ user, params }) => {
    require(user, P.loanAdmin);
    const x = failed(params.id);
    Object.assign(x, { status: 'PENDING', error: null, expectedPostingDate: db.businessDate });
    bookDeferredReceipts(db, nowIso());
    appendAudit(db, nowIso(), user.username, 'DEFERRED_RECEIPT_RETRIED', 'LOAN', x.loanId ?? null, { id: x.id, status: x.status });
    return ok(x);
  });
  on('POST', '/api/v1/deferred-receipts/{id}/cancel', ({ user, params, body }) => {
    require(user, P.loanAdmin);
    const x = failed(params.id);
    const note = (body as { note?: string } | null)?.note?.trim();
    if (!note) throw bad('A note saying what was done with the money is required', [{ field: 'note', message: 'Required' }]);
    Object.assign(x, { status: 'CANCELLED', resolvedBy: user.username, resolutionNote: note });
    appendAudit(db, nowIso(), user.username, 'DEFERRED_RECEIPT_CANCELLED', 'LOAN', x.loanId ?? null, { id: x.id, note });
    return ok(x);
  });

  // ---- sessions
  const sessionsOf = (user: DemoUser, target: string | null): LoginSession[] => {
    const name = target || user.username;
    if (name !== user.username) require(user, P.sessionAdmin);
    const mine = db.sessions.filter((s) => s.username === name);
    // The newest session of the caller is the one this request's token belongs to.
    return mine.map((s, i) => ({ ...s, current: name === user.username && i === 0 }));
  };
  on('GET', '/api/v1/sessions', ({ user, url }) => ok(sessionsOf(user, url.searchParams.get('user'))));
  on('DELETE', '/api/v1/sessions/{id}', ({ user, url, params }) => {
    const target = url.searchParams.get('user');
    const list = sessionsOf(user, target);
    if (!list.some((s) => s.id === params.id)) throw notFound('Session');
    db.sessions = db.sessions.filter((s) => s.id !== params.id);
    appendAudit(db, nowIso(), user.username, 'SESSION_TERMINATED', 'SESSION', params.id, { user: target || user.username });
    return { status: 204, body: null };
  });

  // ---- jobs
  const jobView = (j: Job): Job => ({ ...j, nextRunAt: j.enabled && j.schedule ? nextCronFire(j.schedule, nowIso()) : null, lastRun: [...db.jobRuns].sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? '')).find((x) => x.jobCode === j.code) ?? null });
  on('GET', '/api/v1/jobs', ({ user }) => {
    require(user, P.jobView);
    return ok(db.jobs.map(jobView));
  });
  on('GET', '/api/v1/jobs/runs', ({ user, url }) => {
    require(user, P.jobView);
    const job = url.searchParams.get('job');
    const page = Math.max(0, Number(url.searchParams.get('page') ?? 0));
    const size = Math.min(100, Math.max(1, Number(url.searchParams.get('size') ?? 20)));
    const rows = db.jobRuns.filter((x) => !job || x.jobCode === job).sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''));
    return ok(rows.slice(page * size, page * size + size));
  });
  on('PUT', '/api/v1/jobs/{code}', ({ user, params, body }) => {
    require(user, P.jobSchedule);
    const j = db.jobs.find((x) => x.code === params.code);
    if (!j) throw notFound('Job');
    const b = (body ?? {}) as JobScheduleInput;
    const schedule = b.schedule === undefined ? (j.schedule ?? null) : b.schedule?.trim() || null;
    const enabled = b.enabled ?? !!j.enabled;
    if (schedule && cronProblem(schedule)) throw bad(cronProblem(schedule)!, [{ field: 'schedule', message: 'Invalid cron' }]);
    if (enabled && !schedule) throw bad('An enabled job needs a schedule', [{ field: 'schedule', message: 'Required while enabled' }]);
    if (j.kind === 'REPORT' && !user.permissions.includes(P.reportRun)) throw new HttpProblem(403, 'Forbidden', 'A report job can be scheduled only by someone who may run the report', {}, PROBLEM_BASE + 'forbidden');
    const parameters = b.parameters ?? j.parameters ?? {};
    if (j.kind === 'REPORT' && parameters.emailTo !== undefined) {
      const list = (Array.isArray(parameters.emailTo) ? parameters.emailTo : String(parameters.emailTo).split(/[,;\s]+/)).map((x) => String(x).trim()).filter(Boolean);
      const allowed = (db.systemProperties.find((p) => p.key === 'mail.internal-domains')?.value ?? '').split(',').map((x) => x.trim().toLowerCase()).filter(Boolean);
      const outside = [...new Set(list.map((x) => x.slice(x.lastIndexOf('@') + 1).toLowerCase()).filter((d) => !allowed.includes(d)))];
      if (outside.length) throw bad(`emailTo: reports are e-mailed only to the lender's own domains; not allowed: ${outside.join(', ')}. Allowed domains (tenant property mail.internal-domains): ${allowed.join(', ') || 'none configured'}`, [{ field: 'emailTo', message: 'Not an internal domain' }]);
    }
    if (schedule === (j.schedule ?? null) && enabled === !!j.enabled && JSON.stringify(parameters) === JSON.stringify(j.parameters ?? {})) throw bad('Nothing to change');
    if (pendingOf('JOB_SCHEDULE', (p) => p.kind === 'JOB_SCHEDULE' && p.code === j.code)) throw conflict('Change already pending', `Job ${j.code} already has a change awaiting approval`);
    return propose(user, 'JOB', 'UPDATE', { kind: 'JOB_SCHEDULE', code: j.code!, schedule, enabled, parameters }, { code: j.code, schedule, enabled, parameters }, { code: j.code, schedule: j.schedule, enabled: j.enabled, parameters: j.parameters }, j.code);
  });
  on('POST', '/api/v1/jobs/{code}/run', ({ user, params }) => {
    require(user, P.jobRun);
    const j = db.jobs.find((x) => x.code === params.code);
    if (!j) throw notFound('Job');
    if (db.jobRuns.some((x) => x.jobCode === j.code && x.status === 'RUNNING')) throw conflict('Job is already running', `${j.name} is running; wait for it to finish`);
    const now = nowIso();
    const run: JobRun = { id: uuid(), jobCode: j.code, origin: 'MANUAL', scheduledFor: null, requestedBy: user.username, status: 'COMPLETED', startedAt: now, finishedAt: now, processed: 0, failed: 0, error: null, artifact: null };
    if (j.kind === 'DEFERRED_RECEIPTS') Object.assign(run, bookDeferredReceipts(db, now));
    else if (j.kind === 'KYC_EXPIRY') run.processed = db.kycDocuments.filter((d) => d.meta.expiryDate && d.meta.expiryDate < db.businessDate).length;
    else if (j.kind === 'CONSENT_EXPIRY') run.processed = db.consents.filter((c) => c.status === 'ACTIVE' && c.expiresAt && c.expiresAt <= now).length;
    else if (j.kind === 'REPORT') Object.assign(run, { processed: db.loans.length, artifact: { delivery: 'SENT', recipientsSent: 1, recipientsLeftOut: 0 } });
    else run.processed = 1;
    db.jobRuns.push(run);
    appendAudit(db, now, user.username, 'JOB_RUN', 'JOB', j.code ?? null, { processed: run.processed, failed: run.failed });
    return ok(run);
  });

  // ---- support access
  const saView = (x: SupportAccess): SupportAccess => ({ ...x, status: x.status === 'APPROVED' && x.expiresAt && x.expiresAt <= nowIso() ? 'EXPIRED' : x.status });
  const sa = (user: DemoUser, id: string, from: SupportAccess['status']) => {
    require(user, P.supportAccessApprove);
    const x = db.supportAccess.find((y) => y.id === id);
    if (!x) throw notFound('Support access request');
    if (saView(x).status !== from) throw conflict(`Request is not ${from === 'REQUESTED' ? 'waiting for a decision' : 'an active grant'}`, `This request is ${saView(x).status}`);
    return x;
  };
  const noteOf = (body: unknown) => (body as { note?: string } | null)?.note?.trim() || null;
  on('GET', '/api/v1/support-access', ({ user, url }) => {
    require(user, P.supportAccessApprove);
    const status = url.searchParams.get('status');
    return ok(db.supportAccess.map(saView).filter((x) => !status || x.status === status).sort((a, b) => (b.requestedAt ?? '').localeCompare(a.requestedAt ?? '')));
  });
  on('POST', '/api/v1/support-access/{id}/approve', ({ user, params, body }) => {
    const x = sa(user, params.id, 'REQUESTED');
    const now = nowIso();
    Object.assign(x, { status: 'APPROVED', decidedBy: user.username, decidedAt: now, decisionNote: noteOf(body), expiresAt: new Date(Date.parse(now) + (x.durationMinutes ?? 60) * 60_000).toISOString() });
    appendAudit(db, now, user.username, 'SUPPORT_ACCESS_APPROVED', 'SUPPORT_ACCESS', x.id ?? null, { engineer: x.engineer, ticket: x.ticket, expiresAt: x.expiresAt });
    return ok(x);
  });
  on('POST', '/api/v1/support-access/{id}/reject', ({ user, params, body }) => {
    const x = sa(user, params.id, 'REQUESTED');
    const note = noteOf(body);
    if (!note) throw bad('A note is required to reject', [{ field: 'note', message: 'Required' }]);
    Object.assign(x, { status: 'REJECTED', decidedBy: user.username, decidedAt: nowIso(), decisionNote: note });
    appendAudit(db, nowIso(), user.username, 'SUPPORT_ACCESS_REJECTED', 'SUPPORT_ACCESS', x.id ?? null, { note });
    return ok(x);
  });
  on('POST', '/api/v1/support-access/{id}/revoke', ({ user, params, body }) => {
    const x = sa(user, params.id, 'APPROVED');
    Object.assign(x, { status: 'REVOKED', revokedBy: user.username, revokedAt: nowIso(), revokeReason: noteOf(body) });
    appendAudit(db, nowIso(), user.username, 'SUPPORT_ACCESS_REVOKED', 'SUPPORT_ACCESS', x.id ?? null, { reason: x.revokeReason });
    return ok(x);
  });
}
