import type {
  Approval,
  Branch,
  CustomerInput,
  CustomerSummary,
  DedupeMatch,
  EodRun,
  EodSchedule,
  EodStep,
  GlHead,
  Holiday,
  Me,
  TaxRate,
  VoucherInput,
} from '../api/types';
import { findDemoUser, usernameFromMockToken, type DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { addDays, ageOn, dayOfWeek, ISO_DATE } from '../lib/dates';
import { BRANCH_CODE_PATTERN, EMAIL_PATTERN, IFSC_PATTERN, MOBILE_PATTERN, PAN_PATTERN, PINCODE_PATTERN, maskMobile, maskPan } from '../lib/mask';
import { formatINR, fromUnits, isMoney, toUnits } from '../lib/money';
import { appendAudit, uuid, verifyAudit, type ApprovalPayload, type MockDb, type StoredApproval, type StoredCustomer } from './db';
import { balanceSheet, headByCode, postVoucher, profitAndLoss, reverseVoucher, trialBalance, voucherTotals } from './ledger';
import { createSeedDb, customerRecord, EOD_STEPS } from './seed';

import { applyLendingApproval, lendingDayEnd, registerLendingRoutes } from './lending';
import { applyPlatformApproval, branchScope, readCsvUpload, registerPlatformRoutes, rowErrors } from './platform';
import { bad, conflict, HttpProblem, notFound, PROBLEM_BASE, type FieldProblem } from './problems';

/** Largest request body the API accepts (the contract's maxLength for CSV uploads). */
const MAX_BODY_CHARS = 5_000_000;

interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
  request: Request;
}

type Handler = (ctx: Ctx) => { status: number; body: unknown } | Promise<{ status: number; body: unknown }>;
interface Route {
  method: string;
  pattern: RegExp;
  keys: string[];
  handler: Handler;
}

export interface MockServerOptions {
  /** Delay per EOD step (ms). */
  eodStepMs?: number;
  /** Artificial latency (ms) for every request; 0 in tests. */
  latencyMs?: number;
  db?: MockDb;
}

export interface MockServer {
  db: MockDb;
  fetch: (request: Request) => Promise<Response>;
  dispose: () => void;
}

const ok = (body: unknown) => ({ status: 200, body });
const accepted = (body: unknown) => ({ status: 202, body });

export function createMockServer(options: MockServerOptions = {}): MockServer {
  const db = options.db ?? createSeedDb();
  const stepMs = options.eodStepMs ?? 400;
  const latency = options.latencyMs ?? 0;
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const nowIso = () => new Date().toISOString();

  // ---------- helpers ----------
  function require(user: DemoUser, perm: string) {
    if (!user.permissions.includes(perm)) {
      throw new HttpProblem(403, 'Forbidden', `Missing permission ${perm}`, {}, PROBLEM_BASE + 'forbidden');
    }
  }

  function approvalView(s: StoredApproval): Approval {
    const a = s.approval;
    const end = a.checkedAt ? Date.parse(a.checkedAt) : Date.now();
    return { ...a, ageHours: Math.max(0, Math.round(((end - Date.parse(a.madeAt)) / 3600_000) * 10) / 10) };
  }

  function propose(
    user: DemoUser,
    entityType: string,
    action: string,
    payload: ApprovalPayload,
    proposed: Record<string, unknown>,
    current: Record<string, unknown> | null = null,
    entityId: string | null = null,
    amount: string | null = null,
  ) {
    const approval: Approval = {
      id: uuid(),
      entityType,
      entityId,
      action,
      status: 'PENDING',
      maker: user.username,
      madeAt: nowIso(),
      checker: null,
      checkedAt: null,
      note: null,
      amount,
      current,
      proposed,
    };
    const stored = { approval, payload };
    db.approvals.push(stored);
    appendAudit(db, approval.madeAt, user.username, 'APPROVAL_REQUESTED', entityType, approval.id, { action, entityId });
    return accepted(approvalView(stored));
  }

  function summary(c: StoredCustomer): CustomerSummary {
    const i = c.input;
    return {
      id: c.id,
      customerNo: c.customerNo,
      displayName: [i.firstName, i.middleName, i.lastName].filter(Boolean).join(' '),
      customerType: i.customerType,
      dateOfBirth: i.dateOfBirth,
      panMasked: maskPan(i.pan),
      mobileMasked: maskMobile(i.mobile),
      homeBranch: i.homeBranch,
      kycStatus: c.kycStatus,
      status: c.status,
    };
  }

  const normName = (i: Pick<CustomerInput, 'firstName' | 'middleName' | 'lastName'>) =>
    [i.firstName, i.middleName, i.lastName].filter(Boolean).join(' ').toLowerCase().replace(/[^a-z]/g, '');

  function dedupe(input: CustomerInput): DedupeMatch[] {
    const matches: DedupeMatch[] = [];
    for (const c of db.customers) {
      const s = summary(c);
      if (input.pan && c.input.pan === input.pan.toUpperCase()) {
        matches.push({ customerNo: c.customerNo, displayName: s.displayName, rule: 'PAN', strength: 'EXACT' });
      } else if (input.mobile && c.input.mobile === input.mobile) {
        matches.push({ customerNo: c.customerNo, displayName: s.displayName, rule: 'MOBILE', strength: 'STRONG' });
      } else if (normName(input) && normName(input) === normName(c.input) && input.dateOfBirth === c.input.dateOfBirth) {
        matches.push({ customerNo: c.customerNo, displayName: s.displayName, rule: 'NAME_DOB', strength: 'POSSIBLE' });
      }
    }
    const rank = { EXACT: 0, STRONG: 1, POSSIBLE: 2 };
    return matches.sort((a, b) => rank[a.strength ?? 'POSSIBLE'] - rank[b.strength ?? 'POSSIBLE']);
  }

  function validateCustomer(input: CustomerInput) {
    const errors: Array<{ field: string; message: string }> = [];
    if (!input || typeof input !== 'object') throw bad('Body required');
    if (!['INDIVIDUAL', 'NON_INDIVIDUAL'].includes(input.customerType)) errors.push({ field: 'customerType', message: 'Invalid customer type' });
    if (!input.firstName?.trim()) errors.push({ field: 'firstName', message: 'First name is required' });
    if (!db.branches.some((b) => b.code === input.homeBranch && b.status !== 'CLOSED')) errors.push({ field: 'homeBranch', message: 'Unknown or closed branch' });
    if (!input.dateOfBirth || !ISO_DATE.test(input.dateOfBirth)) errors.push({ field: 'dateOfBirth', message: 'Date of birth is required' });
    else if (input.dateOfBirth > db.businessDate) errors.push({ field: 'dateOfBirth', message: 'Date of birth cannot be in the future' });
    else if (input.customerType === 'INDIVIDUAL' && ageOn(input.dateOfBirth, db.businessDate) < 18) errors.push({ field: 'dateOfBirth', message: 'Individual customers must be at least 18' });
    if (!MOBILE_PATTERN.test(input.mobile ?? '')) errors.push({ field: 'mobile', message: 'Mobile must be 10 digits starting 6-9' });
    if (input.pan && !PAN_PATTERN.test(input.pan)) errors.push({ field: 'pan', message: 'PAN must look like ABCDE1234F' });
    if (input.email && !EMAIL_PATTERN.test(input.email)) errors.push({ field: 'email', message: 'Invalid email' });
    if (input.address?.pincode && !PINCODE_PATTERN.test(input.address.pincode)) errors.push({ field: 'address.pincode', message: 'PIN code must be 6 digits, not starting with 0' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
  }

  function validateVoucher(input: VoucherInput) {
    const errors: Array<{ field: string; message: string }> = [];
    if (!input || typeof input !== 'object') throw bad('Body required');
    if (!['CONTRA', 'RECEIPT', 'PAYMENT', 'JOURNAL'].includes(input.voucherType)) errors.push({ field: 'voucherType', message: 'Invalid voucher type' });
    if (!input.valueDate || !ISO_DATE.test(input.valueDate)) errors.push({ field: 'valueDate', message: 'Value date required' });
    else if (input.valueDate > db.businessDate) errors.push({ field: 'valueDate', message: 'Value date cannot be after the business date' });
    if (!input.description?.trim()) errors.push({ field: 'description', message: 'Description required' });
    if (!Array.isArray(input.lines) || input.lines.length < 2) errors.push({ field: 'lines', message: 'At least two lines are required' });
    (input.lines ?? []).forEach((l, i) => {
      const head = headByCode(db, l.glCode);
      if (!db.branches.some((b) => b.code === l.branch && b.status === 'ACTIVE')) errors.push({ field: `lines[${i}].branch`, message: `Line ${i + 1}: unknown branch ${l.branch}` });
      if (!head) errors.push({ field: `lines[${i}].glCode`, message: `Line ${i + 1}: unknown GL head ${l.glCode}` });
      else if (!head.posting) errors.push({ field: `lines[${i}].glCode`, message: `Line ${i + 1}: ${l.glCode} is a group head; only posting heads accept entries` });
      else if (head.status !== 'ACTIVE') errors.push({ field: `lines[${i}].glCode`, message: `Line ${i + 1}: ${l.glCode} is ${head.status}` });
      if (l.side !== 'DR' && l.side !== 'CR') errors.push({ field: `lines[${i}].side`, message: `Line ${i + 1}: side must be DR or CR` });
      if (!isMoney(l.amount ?? '') || toUnits(l.amount) <= 0n) errors.push({ field: `lines[${i}].amount`, message: `Line ${i + 1}: amount must be a positive decimal` });
    });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    const { dr, cr } = voucherTotals(input.lines);
    if (dr !== cr) {
      throw new HttpProblem(
        422,
        'Voucher does not balance',
        `Debits ${formatINR(fromUnits(dr))} must equal credits ${formatINR(fromUnits(cr))}`,
        { debit: fromUnits(dr), credit: fromUnits(cr) },
        PROBLEM_BASE + 'unbalanced-voucher',
      );
    }
  }

  function applyApproval(s: StoredApproval, checker: string) {
    const p = s.payload;
    const a = s.approval;
    const at = nowIso();
    switch (p.kind) {
      case 'BRANCH': {
        const i = db.branches.findIndex((b) => b.code === p.branch.code);
        if (i >= 0) db.branches[i] = { ...p.branch };
        else db.branches.push({ ...p.branch });
        a.entityId = p.branch.code;
        break;
      }
      case 'HOLIDAY':
        for (const h of p.holidays) {
          if (!db.holidays.some((x) => x.day === h.day && (x.branchCode ?? null) === (h.branchCode ?? null))) db.holidays.push({ ...h });
        }
        break;
      case 'TAX_RATE': {
        const open = db.taxRates.find((t) => t.code === p.rate.code && !t.effectiveTo && t.effectiveFrom < p.rate.effectiveFrom);
        if (open) open.effectiveTo = addDays(p.rate.effectiveFrom, -1);
        db.taxRates.push({ ...p.rate });
        a.entityId = p.rate.code;
        break;
      }
      case 'GL_HEAD': {
        const i = db.glHeads.findIndex((h) => h.code === p.head.code);
        if (i >= 0) db.glHeads[i] = { ...p.head };
        else db.glHeads.push({ ...p.head, status: p.head.status ?? 'ACTIVE' });
        a.entityId = p.head.code;
        break;
      }
      case 'CUSTOMER': {
        if (p.input.pan && db.customers.some((c) => c.input.pan === p.input.pan)) throw conflict('Duplicate PAN', 'A customer with this PAN was created meanwhile');
        const c = customerRecord(db, { ...p.input }, at);
        a.entityId = c.id;
        appendAudit(db, at, checker, 'CUSTOMER_CREATED', 'CUSTOMER', c.id, { customerNo: c.customerNo });
        break;
      }
      case 'VOUCHER': {
        validateVoucher(p.input); // heads may have been frozen since the proposal
        const vch = postVoucher(db, p.input, db.businessDate);
        a.entityId = vch.id;
        appendAudit(db, at, checker, 'VOUCHER_POSTED', 'VOUCHER', vch.id, { voucherNo: vch.voucherNo, amount: vch.amount });
        break;
      }
      case 'VOUCHER_REVERSAL': {
        const vch = db.vouchers.find((x) => x.id === p.voucherId);
        if (!vch) throw notFound('Voucher');
        if (vch.status !== 'POSTED') throw conflict('Voucher already reversed');
        reverseVoucher(db, vch, db.businessDate, p.reason);
        appendAudit(db, at, checker, 'VOUCHER_REVERSED', 'VOUCHER', vch.id, { voucherNo: vch.voucherNo, reason: p.reason });
        break;
      }
      case 'EOD_SCHEDULE':
        db.eodSchedule = { ...p.schedule };
        break;
      default:
        if (!applyLendingApproval(db, p, a, checker, at) && !applyPlatformApproval(db, p, a, checker, at)) {
          throw new HttpProblem(500, 'Internal error', `No handler for approval kind ${p.kind}`);
        }
    }
  }

  function decide(user: DemoUser, id: string, approve: boolean, note: string | undefined): Approval {
    require(user, P.approvalApprove);
    const s = db.approvals.find((x) => x.approval.id === id);
    if (!s) throw notFound('Approval');
    if (s.approval.status !== 'PENDING') throw conflict('Approval is not pending', `Approval is already ${s.approval.status}`);
    if (s.approval.maker === user.username) {
      throw conflict('Maker cannot approve own request', `${approve ? 'Approval' : 'Rejection'} must be done by a different user than the maker (${s.approval.maker}).`);
    }
    if (!approve && !note?.trim()) throw bad('A note is required to reject', [{ field: 'note', message: 'Required' }]);
    if (note && note.length > 500) throw bad('Note must be at most 500 characters', [{ field: 'note', message: 'Too long' }]);
    if (approve) applyApproval(s, user.username);
    s.approval.status = approve ? 'APPROVED' : 'REJECTED';
    s.approval.checker = user.username;
    s.approval.checkedAt = nowIso();
    s.approval.note = note?.trim() || null;
    appendAudit(db, s.approval.checkedAt, user.username, approve ? 'APPROVAL_APPROVED' : 'APPROVAL_REJECTED', s.approval.entityType, s.approval.id, { note: s.approval.note });
    return approvalView(s);
  }

  // ---------- EOD ----------
  function nextBusinessDate(from: string): string {
    let d = addDays(from, 1);
    while (dayOfWeek(d) === 0 || db.holidays.some((h) => !h.branchCode && h.day === d)) d = addDays(d, 1);
    return d;
  }

  function schedule(fn: () => void, ms: number) {
    const t = setTimeout(() => {
      timers.delete(t);
      fn();
    }, ms);
    timers.add(t);
  }

  function processed(name: string): number {
    if (name === 'Pre-checks' || name === 'Advance business date' || name === 'Trial balance gate') return 1;
    if (name === 'GL balance snapshot') return db.glHeads.filter((h) => h.posting).length * db.branches.length;
    return 1248 + name.length * 3;
  }

  function runStep(run: EodRun) {
    const steps = run.steps ?? [];
    const step = steps.find((s) => s.status === 'PENDING');
    if (!step) return finishRun(run);
    step.status = 'RUNNING';
    step.startedAt = nowIso();
    step.finishedAt = null;
    schedule(() => completeStep(run, step), stepMs);
  }

  function completeStep(run: EodRun, step: EodStep) {
    step.finishedAt = nowIso();
    const name = step.name ?? '';
    if (db.eodFailAtStep === name) {
      db.eodFailAtStep = null;
      step.status = 'FAILED';
      step.processed = 0;
      step.failed = 1;
      run.status = 'FAILED';
      run.finishedAt = step.finishedAt;
      run.exceptions = [...(run.exceptions ?? []), { step: name, accountNo: '-', error: 'Step aborted: ledger lock timeout (injected fault)', resolved: false }];
      db.dayStatus = 'EOD_FAILED';
      appendAudit(db, step.finishedAt, 'system', 'EOD_FAILED', 'EOD_RUN', String(run.id), { step: name });
      return;
    }
    step.processed = processed(name);
    step.failed = 0;
    step.status = 'COMPLETED';
    if (name === 'NPA marking') {
      step.status = 'COMPLETED_WITH_EXCEPTIONS';
      step.failed = 2;
      run.exceptions = [
        ...(run.exceptions ?? []),
        { step: name, accountNo: 'LN00001234', error: 'DPD not computable: repayment schedule missing', resolved: false },
        { step: name, accountNo: 'LN00005678', error: 'Asset classification conflict: account under restructuring flag', resolved: false },
      ];
    }
    if (name === 'Interest accrual') {
      const vch = postVoucher(
        db,
        {
          voucherType: 'JOURNAL',
          valueDate: run.businessDate,
          description: `EOD interest accrual ${run.businessDate}`,
          lines: [
            { branch: 'HO', glCode: '1210', side: 'DR', amount: '10750.00' },
            { branch: 'HO', glCode: '4101', side: 'CR', amount: '10750.00' },
            { branch: 'MUM', glCode: '1210', side: 'DR', amount: '5210.50' },
            { branch: 'MUM', glCode: '4101', side: 'CR', amount: '5210.50' },
          ],
        },
        run.businessDate,
        'EOD_ACCRUAL',
      );
      appendAudit(db, step.finishedAt, 'system', 'VOUCHER_POSTED', 'VOUCHER', vch.id, { voucherNo: vch.voucherNo, source: 'EOD' });
    }
    if (name === 'Advance business date') {
      run.nextBusinessDate = nextBusinessDate(run.businessDate);
    }
    runStep(run);
  }

  function finishRun(run: EodRun) {
    const hasExceptions = (run.steps ?? []).some((s) => s.status === 'COMPLETED_WITH_EXCEPTIONS');
    run.status = hasExceptions ? 'COMPLETED_WITH_EXCEPTIONS' : 'COMPLETED';
    run.finishedAt = nowIso();
    const next = run.nextBusinessDate ?? nextBusinessDate(run.businessDate);
    run.nextBusinessDate = next;
    db.businessDate = next;
    lendingDayEnd(db);
    db.dayStatus = 'OPEN';
    appendAudit(db, run.finishedAt, 'system', 'EOD_COMPLETED', 'EOD_RUN', String(run.id), { businessDate: run.businessDate, next, status: run.status });
  }

  // ---------- routes ----------
  const routes: Route[] = [];
  function on(method: string, path: string, handler: Handler) {
    const keys: string[] = [];
    const pattern = new RegExp('^' + path.replace(/\{(\w+)\}/g, (_, k: string) => (keys.push(k), '([^/]+)')) + '$');
    routes.push({ method, pattern, keys, handler });
  }

  on('GET', '/api/v1/me', ({ user }) => {
    const scope = branchScope(db, user.username, user.homeBranch);
    const me: Me = {
      userId: user.username,
      displayName: user.name,
      tenant: db.tenant,
      tenantName: db.tenantName,
      homeBranch: db.staff.find((s) => s.username === user.username)?.homeBranch ?? user.homeBranch,
      allBranches: scope.allBranches,
      branches: scope.branches,
      businessDate: db.businessDate,
      permissions: [...user.permissions],
      modules: ['CUSTOMER', 'GL', 'EOD', 'LENDING'],
    };
    return ok(me);
  });
  on('GET', '/api/v1/business-day', () => ok({ businessDate: db.businessDate, status: db.dayStatus }));

  // Masters
  on('GET', '/api/v1/branches', () => ok(db.branches));
  on('POST', '/api/v1/branches', ({ user, body }) => {
    require(user, P.branchPropose);
    const b = body as Branch;
    const errors: Array<{ field: string; message: string }> = [];
    if (!b || !BRANCH_CODE_PATTERN.test(b.code ?? '')) errors.push({ field: 'code', message: 'Code must be 2-10 upper-case letters/digits' });
    if (!b?.name?.trim()) errors.push({ field: 'name', message: 'Name is required' });
    if (!/^\d{2}$/.test(b?.stateCode ?? '')) errors.push({ field: 'stateCode', message: 'GST state code must be 2 digits' });
    if (b?.ifsc && !IFSC_PATTERN.test(b.ifsc)) errors.push({ field: 'ifsc', message: 'IFSC must look like HDFC0001234' });
    if (b?.parentCode && !db.branches.some((x) => x.code === b.parentCode)) errors.push({ field: 'parentCode', message: 'Unknown parent branch' });
    if (b?.parentCode && b.parentCode === b.code) errors.push({ field: 'parentCode', message: 'A branch cannot be its own parent' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (db.approvals.some((s) => s.approval.status === 'PENDING' && s.approval.entityType === 'BRANCH' && (s.approval.proposed as { code?: string })?.code === b.code)) {
      throw conflict('Change already pending', `Branch ${b.code} already has a pending change`);
    }
    const existing = db.branches.find((x) => x.code === b.code);
    const branch: Branch = { ifsc: null, parentCode: null, headOffice: false, status: 'ACTIVE', ...b };
    return propose(user, 'BRANCH', existing ? 'UPDATE' : 'CREATE', { kind: 'BRANCH', branch }, { ...branch }, existing ? { ...existing } : null, existing ? b.code : null);
  });

  on('GET', '/api/v1/holidays', ({ user, url }) => {
    require(user, P.holidayView);
    const year = url.searchParams.get('year');
    if (!year || !/^\d{4}$/.test(year)) throw bad('year is required', [{ field: 'year', message: 'Required' }]);
    const branch = url.searchParams.get('branch');
    return ok(
      db.holidays
        .filter((h) => h.day.startsWith(year) && (!branch || !h.branchCode || h.branchCode === branch))
        .sort((a, b) => a.day.localeCompare(b.day)),
    );
  });
  function validateHolidays(list: Holiday[], rowLabel: (i: number) => string = (i) => `Row ${i + 1}`): Holiday[] {
    if (!Array.isArray(list) || list.length === 0) throw bad('At least one holiday is required');
    const errors: FieldProblem[] = [];
    const seen = new Set<string>();
    list.forEach((h, i) => {
      const at = rowLabel(i);
      if (!ISO_DATE.test(h.day ?? '')) errors.push({ field: `[${i}].day`, message: `${at}: date (YYYY-MM-DD) required` });
      if (!h.reason?.trim()) errors.push({ field: `[${i}].reason`, message: `${at}: reason required` });
      if (h.branchCode && !db.branches.some((b) => b.code === h.branchCode)) errors.push({ field: `[${i}].branchCode`, message: `${at}: unknown branch ${h.branchCode}` });
      if (h.day && ISO_DATE.test(h.day) && h.day <= db.businessDate) errors.push({ field: `[${i}].day`, message: `${at}: holidays must be after the business date` });
      if (db.holidays.some((x) => x.day === h.day && (x.branchCode ?? null) === (h.branchCode || null))) errors.push({ field: `[${i}].day`, message: `${at}: ${h.day} is already a holiday` });
      const key = `${h.day}|${h.branchCode || ''}`;
      if (seen.has(key)) errors.push({ field: `[${i}].day`, message: `${at}: ${h.day} appears twice` });
      seen.add(key);
    });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    return list.map((h) => ({ branchCode: h.branchCode || null, day: h.day, reason: h.reason.trim() }));
  }
  on('POST', '/api/v1/holidays', ({ user, body }) => {
    require(user, P.holidayPropose);
    const holidays = validateHolidays(body as Holiday[]);
    return propose(user, 'HOLIDAY', 'CREATE', { kind: 'HOLIDAY', holidays }, { holidays });
  });
  on('POST', '/api/v1/holidays/upload', ({ user, body }) => {
    require(user, P.holidayPropose);
    const rows = readCsvUpload(body, ['day', 'reason'], ['branchCode'], 366);
    let holidays: Holiday[];
    try {
      holidays = validateHolidays(rows.map((r) => ({ day: r.day, reason: r.reason, branchCode: r.branchCode || null })), (i) => `Row ${i + 2}`);
    } catch (e) {
      if (e instanceof HttpProblem && Array.isArray(e.extra.errors)) throw rowErrors(e.extra.errors as FieldProblem[]);
      throw e;
    }
    return propose(user, 'HOLIDAY', 'CREATE', { kind: 'HOLIDAY', holidays }, { holidays });
  });

  on('GET', '/api/v1/tax-rates', ({ user, url }) => {
    require(user, P.taxRateView);
    const asOf = url.searchParams.get('asOf');
    const rows = db.taxRates.filter((t) => !asOf || (t.effectiveFrom <= asOf && (!t.effectiveTo || t.effectiveTo >= asOf)));
    return ok([...rows].sort((a, b) => a.code.localeCompare(b.code) || a.effectiveFrom.localeCompare(b.effectiveFrom)));
  });
  on('POST', '/api/v1/tax-rates', ({ user, body }) => {
    require(user, P.taxRatePropose);
    const t = body as TaxRate;
    const errors: Array<{ field: string; message: string }> = [];
    if (!/^[A-Z0-9]{2,20}$/.test(t?.code ?? '')) errors.push({ field: 'code', message: 'Code must be 2-20 upper-case letters/digits' });
    if (t?.taxType !== 'GST' && t?.taxType !== 'TDS') errors.push({ field: 'taxType', message: 'Tax type must be GST or TDS' });
    if (!/^\d{1,3}(\.\d{1,4})?$/.test(t?.ratePercent ?? '') || Number(t.ratePercent) > 100) errors.push({ field: 'ratePercent', message: 'Rate must be between 0 and 100' });
    if (!ISO_DATE.test(t?.effectiveFrom ?? '')) errors.push({ field: 'effectiveFrom', message: 'Effective from is required' });
    if (t?.effectiveTo && t.effectiveTo < t.effectiveFrom) errors.push({ field: 'effectiveTo', message: 'Effective to must be on/after effective from' });
    const current = db.taxRates.find((x) => x.code === t?.code && !x.effectiveTo) ?? null;
    if (current && t.effectiveFrom <= current.effectiveFrom) errors.push({ field: 'effectiveFrom', message: `Must be after the current rate's start (${current.effectiveFrom})` });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    const rate: TaxRate = { ...t, effectiveTo: t.effectiveTo ?? null };
    return propose(user, 'TAX_RATE', current ? 'UPDATE' : 'CREATE', { kind: 'TAX_RATE', rate }, { ...rate }, current ? { ...current } : null, current ? t.code : null);
  });

  // Approvals
  on('GET', '/api/v1/approvals', ({ user, url }) => {
    require(user, P.approvalView);
    const status = url.searchParams.get('status');
    const entityType = url.searchParams.get('entityType');
    return ok(
      db.approvals
        .filter((s) => (!status || s.approval.status === status) && (!entityType || s.approval.entityType === entityType))
        .map(approvalView)
        .sort((a, b) => b.madeAt.localeCompare(a.madeAt)),
    );
  });
  on('GET', '/api/v1/approvals/{id}', ({ user, params }) => {
    require(user, P.approvalView);
    const s = db.approvals.find((x) => x.approval.id === params.id);
    if (!s) throw notFound('Approval');
    return ok(approvalView(s));
  });
  on('POST', '/api/v1/approvals/bulk-approve', ({ user, body }) => {
    require(user, P.approvalApprove);
    const { ids, note } = (body ?? {}) as { ids?: string[]; note?: string };
    if (!Array.isArray(ids) || ids.length === 0) throw bad('ids is required');
    if (ids.length > 100) throw bad('At most 100 approvals per request');
    return ok(
      ids.map((id) => {
        try {
          decide(user, id, true, note);
          return { id, ok: true };
        } catch (e) {
          return { id, ok: false, error: e instanceof HttpProblem ? e.title : String(e) };
        }
      }),
    );
  });
  on('POST', '/api/v1/approvals/{id}/approve', ({ user, params, body }) => ok(decide(user, params.id, true, (body as { note?: string } | null)?.note)));
  on('POST', '/api/v1/approvals/{id}/reject', ({ user, params, body }) => ok(decide(user, params.id, false, (body as { note?: string } | null)?.note)));

  // Customers
  on('GET', '/api/v1/customers', ({ user, url }) => {
    require(user, P.customerView);
    const q = (url.searchParams.get('q') ?? '').trim();
    const page = Math.max(0, Number(url.searchParams.get('page') ?? 0));
    const size = Math.min(100, Math.max(1, Number(url.searchParams.get('size') ?? 20)));
    const Q = q.toUpperCase();
    const rows = db.customers
      .filter((c) => !q || c.customerNo.startsWith(q) || c.input.pan === Q || c.input.mobile === q)
      .sort((a, b) => b.customerNo.localeCompare(a.customerNo))
      .map(summary);
    return ok(rows.slice(page * size, page * size + size));
  });
  on('POST', '/api/v1/customers/dedupe-check', ({ user, body }) => {
    if (!user.permissions.includes(P.customerCreate)) require(user, P.customerView);
    const input = body as CustomerInput;
    return ok(dedupe({ ...input, pan: input?.pan?.toUpperCase() }));
  });
  on('POST', '/api/v1/customers', ({ user, body }) => {
    require(user, P.customerCreate);
    const input = { ...(body as CustomerInput) };
    if (input.pan) input.pan = input.pan.toUpperCase();
    validateCustomer(input);
    if (input.pan) {
      const existing = db.customers.find((c) => c.input.pan === input.pan);
      if (existing) return ok(summary(existing));
      const pendingSame = db.approvals.find((s) => s.approval.status === 'PENDING' && s.payload.kind === 'CUSTOMER' && s.payload.input.pan === input.pan);
      if (pendingSame) throw conflict('Customer already pending approval', 'A customer with this PAN is awaiting approval', { approvalId: pendingSame.approval.id });
    }
    const matches = dedupe(input).filter((m) => m.strength !== 'EXACT');
    if (matches.length && !input.overrideDedupe) {
      throw new HttpProblem(409, 'Possible duplicate customer', `${matches.length} possible duplicate(s) found; review and override with a reason to proceed`, { matches }, PROBLEM_BASE + 'possible-duplicate');
    }
    if (input.overrideDedupe && !input.overrideReason?.trim()) throw bad('Override reason is required', [{ field: 'overrideReason', message: 'Required' }]);
    const { pan, mobile, ...rest } = input;
    const proposed: Record<string, unknown> = { ...rest, panMasked: maskPan(pan), mobileMasked: maskMobile(mobile) };
    if (matches.length) proposed.dedupeMatches = matches.map((m) => `${m.customerNo} (${m.rule})`).join(', ');
    return propose(user, 'CUSTOMER', 'CREATE', { kind: 'CUSTOMER', input }, proposed);
  });
  on('GET', '/api/v1/customers/{id}', ({ user, params }) => {
    require(user, P.customerView);
    const c = db.customers.find((x) => x.id === params.id || x.customerNo === params.id);
    if (!c) throw notFound('Customer');
    return ok(summary(c));
  });

  // Ledger
  on('GET', '/api/v1/gl/heads', ({ user }) => {
    require(user, P.glView);
    return ok([...db.glHeads].sort((a, b) => a.code.localeCompare(b.code)));
  });
  on('POST', '/api/v1/gl/heads', ({ user, body }) => {
    require(user, P.glPropose);
    const h = body as GlHead;
    const errors: Array<{ field: string; message: string }> = [];
    if (!/^[0-9]{1,10}$/.test(h?.code ?? '')) errors.push({ field: 'code', message: 'Code must be 1-10 digits' });
    if (!h?.name?.trim()) errors.push({ field: 'name', message: 'Name is required' });
    if (!['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'].includes(h?.category)) errors.push({ field: 'category', message: 'Invalid category' });
    const parent = h?.parentCode ? headByCode(db, h.parentCode) : null;
    if (h?.parentCode && !parent) errors.push({ field: 'parentCode', message: 'Unknown parent head' });
    if (parent?.posting) errors.push({ field: 'parentCode', message: 'Parent must be a group (non-posting) head' });
    if (parent && parent.category !== h.category) errors.push({ field: 'category', message: `Category must match parent (${parent.category})` });
    const existing = h?.code ? headByCode(db, h.code) : undefined;
    if (existing && existing.category !== h.category) errors.push({ field: 'category', message: 'Category of an existing head cannot change' });
    if (existing && existing.posting && !h.posting && db.entries.some((e) => e.glCode === h.code)) errors.push({ field: 'posting', message: 'Head has entries; cannot become a group' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    const head: GlHead = { parentCode: null, status: 'ACTIVE', ...h, posting: !!h.posting };
    return propose(user, 'GL_HEAD', existing ? 'UPDATE' : 'CREATE', { kind: 'GL_HEAD', head }, { ...head }, existing ? { ...existing } : null, existing ? h.code : null);
  });
  on('GET', '/api/v1/gl/vouchers', ({ user, url }) => {
    require(user, P.glView);
    const from = url.searchParams.get('from');
    const to = url.searchParams.get('to');
    return ok(
      db.vouchers
        .filter((vch) => (!from || (vch.businessDate ?? '') >= from) && (!to || (vch.businessDate ?? '') <= to))
        .sort((a, b) => (b.businessDate ?? '').localeCompare(a.businessDate ?? '') || b.voucherNo.localeCompare(a.voucherNo)),
    );
  });
  on('POST', '/api/v1/gl/vouchers', ({ user, body }) => {
    require(user, P.voucherCreate);
    const input = body as VoucherInput;
    validateVoucher(input);
    const { dr } = voucherTotals(input.lines);
    const clean: VoucherInput = { ...input, lines: input.lines.map((l) => ({ ...l, amount: fromUnits(toUnits(l.amount)) })) };
    return propose(user, 'VOUCHER', 'CREATE', { kind: 'VOUCHER', input: clean }, { ...clean }, null, null, fromUnits(dr));
  });
  on('POST', '/api/v1/gl/vouchers/upload', ({ user, body }) => {
    require(user, P.voucherCreate);
    const rows = readCsvUpload(body, ['voucherRef', 'voucherType', 'valueDate', 'description', 'branch', 'glCode', 'side', 'amount'], ['account', 'narration', 'reference'], 20_000);
    const groups = new Map<string, Array<{ line: number; r: Record<string, string> }>>();
    const errors: FieldProblem[] = [];
    rows.forEach((r, i) => {
      if (!r.voucherRef) errors.push({ field: `row ${i + 2}`, message: `Row ${i + 2}: voucherRef is required` });
      else groups.set(r.voucherRef, [...(groups.get(r.voucherRef) ?? []), { line: i + 2, r }]);
    });
    const inputs: VoucherInput[] = [];
    for (const [ref, lines] of groups) {
      const head = lines[0].r;
      const firstLine = lines[0].line;
      const mismatch = lines.find(({ r }) => r.voucherType !== head.voucherType || r.valueDate !== head.valueDate || r.description !== head.description);
      if (mismatch) {
        errors.push({ field: `row ${mismatch.line}`, message: `Row ${mismatch.line}: voucher ${ref} must have the same type, value date and description on every line` });
        continue;
      }
      const input: VoucherInput = {
        voucherType: head.voucherType.toUpperCase() as VoucherInput['voucherType'],
        valueDate: head.valueDate,
        description: head.description,
        reference: lines.find(({ r }) => r.reference)?.r.reference || ref,
        lines: lines.map(({ r }) => ({
          branch: r.branch.toUpperCase(),
          glCode: r.glCode,
          side: r.side.toUpperCase() as 'DR' | 'CR',
          amount: r.amount,
          ...(r.account ? { account: r.account } : {}),
          ...(r.narration ? { narration: r.narration } : {}),
        })),
      };
      try {
        validateVoucher(input);
        inputs.push({ ...input, lines: input.lines.map((l) => ({ ...l, amount: fromUnits(toUnits(l.amount)) })) });
      } catch (e) {
        if (!(e instanceof HttpProblem)) throw e;
        errors.push({ field: `row ${firstLine}`, message: `Voucher ${ref} (from row ${firstLine}): ${e.detail ?? e.title}` });
      }
    }
    if (errors.length) throw rowErrors(errors);
    const approvals = inputs.map((input) => propose(user, 'VOUCHER', 'CREATE', { kind: 'VOUCHER', input }, { ...input }, null, null, fromUnits(voucherTotals(input.lines).dr)).body);
    return accepted({ vouchers: inputs.length, approvals });
  });
  on('POST', '/api/v1/gl/vouchers/{id}/reverse', ({ user, params, body }) => {
    require(user, P.voucherReverse);
    const vch = db.vouchers.find((x) => x.id === params.id);
    if (!vch) throw notFound('Voucher');
    const reason = (body as { note?: string } | null)?.note?.trim();
    if (!reason) throw bad('A reason is required to reverse a voucher', [{ field: 'note', message: 'Required' }]);
    if (vch.status !== 'POSTED') throw conflict('Voucher already reversed');
    if (db.approvals.some((s) => s.approval.status === 'PENDING' && s.payload.kind === 'VOUCHER_REVERSAL' && s.payload.voucherId === vch.id)) {
      throw conflict('Reversal already pending', `A reversal of ${vch.voucherNo} is awaiting approval`);
    }
    return propose(
      user, 'VOUCHER', 'REVERSE', { kind: 'VOUCHER_REVERSAL', voucherId: vch.id, reason },
      { voucherNo: vch.voucherNo, status: 'REVERSED', reason },
      { voucherNo: vch.voucherNo, status: vch.status, description: vch.description, amount: vch.amount },
      vch.id, vch.amount,
    );
  });
  function requireDate(url: URL, name: string): string {
    const v = url.searchParams.get(name);
    if (!v || !ISO_DATE.test(v)) throw new HttpProblem(400, 'Bad request', `Query parameter ${name} (YYYY-MM-DD) is required`, {}, PROBLEM_BASE + 'bad-request');
    return v;
  }
  on('GET', '/api/v1/gl/trial-balance', ({ user, url }) => {
    require(user, P.glView);
    return ok(trialBalance(db, requireDate(url, 'asOf'), url.searchParams.get('branch') || undefined));
  });
  on('GET', '/api/v1/gl/entries', ({ user, url }) => {
    require(user, P.glView);
    const glCode = url.searchParams.get('glCode');
    if (!glCode) throw new HttpProblem(400, 'Bad request', 'glCode is required');
    const from = requireDate(url, 'from');
    const to = requireDate(url, 'to');
    const branch = url.searchParams.get('branch');
    return ok(
      db.entries
        .filter((e) => e.glCode === glCode && e.businessDate >= from && e.businessDate <= to && (!branch || e.branch === branch))
        .map(({ voucherId: _v, ...e }) => e),
    );
  });
  on('GET', '/api/v1/gl/profit-and-loss', ({ user, url }) => {
    require(user, P.glView);
    return ok(profitAndLoss(db, requireDate(url, 'from'), requireDate(url, 'to')));
  });
  on('GET', '/api/v1/gl/balance-sheet', ({ user, url }) => {
    require(user, P.glView);
    return ok(balanceSheet(db, requireDate(url, 'asOf')));
  });

  // EOD
  on('GET', '/api/v1/eod/runs', ({ user }) => {
    require(user, P.eodView);
    return ok([...db.eodRuns].sort((a, b) => b.id - a.id));
  });
  on('POST', '/api/v1/eod/runs', ({ user }) => {
    require(user, P.eodRun);
    const running = db.eodRuns.find((r) => r.status === 'RUNNING');
    if (running) throw conflict('End-of-day already running', `Run #${running.id} for ${running.businessDate} is in progress`);
    const failed = db.eodRuns.find((r) => r.status === 'FAILED' && r.businessDate === db.businessDate);
    if (failed) throw conflict('Previous run failed', `Restart run #${failed.id} instead of starting a new one`);
    db.eodRunSeq += 1;
    const run: EodRun = {
      id: db.eodRunSeq,
      businessDate: db.businessDate,
      status: 'RUNNING',
      startedAt: nowIso(),
      finishedAt: null,
      nextBusinessDate: null,
      steps: EOD_STEPS.map((name, i) => ({ stepNo: i + 1, name, status: 'PENDING', processed: 0, failed: 0, startedAt: null, finishedAt: null })),
      exceptions: [],
    };
    db.eodRuns.push(run);
    db.dayStatus = 'EOD_RUNNING';
    appendAudit(db, run.startedAt!, user.username, 'EOD_STARTED', 'EOD_RUN', String(run.id), { businessDate: run.businessDate });
    runStep(run);
    return { status: 202, body: run };
  });
  on('GET', '/api/v1/eod/runs/{runId}', ({ user, params }) => {
    require(user, P.eodView);
    const run = db.eodRuns.find((r) => String(r.id) === params.runId);
    if (!run) throw notFound('EOD run');
    return ok(run);
  });
  on('POST', '/api/v1/eod/runs/{runId}/restart', ({ user, params }) => {
    require(user, P.eodRun);
    const run = db.eodRuns.find((r) => String(r.id) === params.runId);
    if (!run) throw notFound('EOD run');
    if (run.status !== 'FAILED') throw conflict('Run cannot be restarted', `Run #${run.id} is ${run.status}; only FAILED runs can be restarted`);
    const latest = Math.max(...db.eodRuns.map((r) => r.id));
    if (run.id !== latest) throw conflict('Run cannot be restarted', 'Only the latest run can be restarted');
    for (const s of run.steps ?? []) if (s.status === 'FAILED') Object.assign(s, { status: 'PENDING', failed: 0, startedAt: null, finishedAt: null });
    run.status = 'RUNNING';
    run.finishedAt = null;
    db.dayStatus = 'EOD_RUNNING';
    appendAudit(db, nowIso(), user.username, 'EOD_RESTARTED', 'EOD_RUN', String(run.id), null);
    runStep(run);
    return { status: 202, body: run };
  });
  on('GET', '/api/v1/eod/schedule', ({ user }) => {
    require(user, P.eodView);
    return ok(db.eodSchedule);
  });
  on('PUT', '/api/v1/eod/schedule', ({ user, body }) => {
    require(user, P.eodSchedule);
    const s = body as EodSchedule;
    const errors: Array<{ field: string; message: string }> = [];
    if (s?.mode !== 'MANUAL' && s?.mode !== 'SCHEDULED') errors.push({ field: 'mode', message: 'Mode must be MANUAL or SCHEDULED' });
    if (s?.mode === 'SCHEDULED' && (!s.cron || s.cron.trim().split(/\s+/).length !== 6)) errors.push({ field: 'cron', message: 'A 6-field cron (sec min hour day month weekday) is required' });
    for (const e of s?.alertEmails ?? []) if (!EMAIL_PATTERN.test(e)) errors.push({ field: 'alertEmails', message: `Invalid email: ${e}` });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    const schedule: EodSchedule = { mode: s.mode, cron: s.mode === 'SCHEDULED' ? s.cron!.trim() : null, alertEmails: s.alertEmails ?? [] };
    return propose(user, 'EOD_SCHEDULE', 'UPDATE', { kind: 'EOD_SCHEDULE', schedule }, { ...schedule }, { ...db.eodSchedule });
  });

  // Audit
  on('GET', '/api/v1/audit/events', ({ user, url }) => {
    require(user, P.auditView);
    const entityType = url.searchParams.get('entityType');
    const entityId = url.searchParams.get('entityId');
    const limit = Math.min(1000, Math.max(1, Number(url.searchParams.get('limit') ?? 100)));
    return ok(
      [...db.audit]
        .reverse()
        .filter((e) => (!entityType || e.entityType === entityType) && (!entityId || e.entityId === entityId))
        .slice(0, limit)
        .map(({ hash: _h, prevHash: _p, ...e }) => e),
    );
  });
  on('GET', '/api/v1/audit/verify', ({ user }) => {
    require(user, P.auditView);
    return ok(verifyAudit(db));
  });

  registerLendingRoutes(db, { on, require, propose, nowIso });
  registerPlatformRoutes(db, { on, require, propose });

  // ---------- dispatcher ----------
  function respond(status: number, body: unknown): Response {
    const isProblem = status >= 400;
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': isProblem ? 'application/problem+json' : 'application/json' },
    });
  }

  async function handle(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const instance = url.pathname;
    try {
      const route = routes.find((r) => r.method === request.method && r.pattern.test(url.pathname));
      if (!route) {
        const pathExists = routes.some((r) => r.pattern.test(url.pathname));
        throw pathExists ? new HttpProblem(405, 'Method not allowed') : notFound(`Route ${request.method} ${url.pathname}`);
      }
      const auth = request.headers.get('Authorization') ?? '';
      const username = usernameFromMockToken(auth.replace(/^Bearer\s+/i, ''));
      const user = username ? findDemoUser(username) : undefined;
      if (!user) throw new HttpProblem(401, 'Unauthorized', 'Sign in again', {}, PROBLEM_BASE + 'unauthorized');

      const m = url.pathname.match(route.pattern)!;
      const params: Record<string, string> = {};
      route.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
      const text = request.method === 'GET' ? '' : await request.text();
      if (text.length > MAX_BODY_CHARS) {
        throw new HttpProblem(413, 'File too large', `The request body is ${text.length.toLocaleString('en-IN')} characters; the limit is ${MAX_BODY_CHARS.toLocaleString('en-IN')}`, {}, PROBLEM_BASE + 'payload-too-large');
      }
      let body: unknown = null;
      if (text && /^text\/csv/i.test(request.headers.get('Content-Type') ?? '')) body = text;
      else if (text) {
        try {
          body = JSON.parse(text);
        } catch {
          throw new HttpProblem(400, 'Malformed JSON');
        }
      }

      const idemKey = request.headers.get('Idempotency-Key');
      const cacheKey = idemKey ? `${user.username}|${request.method}|${url.pathname}|${idemKey}` : null;
      if (cacheKey && db.idempotency.has(cacheKey)) {
        const hit = db.idempotency.get(cacheKey)!;
        return respond(hit.status, hit.body);
      }
      const result = await route.handler({ user, url, body, params, request });
      if (cacheKey) db.idempotency.set(cacheKey, result);
      return respond(result.status, result.body);
    } catch (e) {
      if (e instanceof HttpProblem) {
        return respond(e.status, { type: e.type, title: e.title, status: e.status, detail: e.detail, instance, ...e.extra });
      }
      return respond(500, { type: 'about:blank', title: 'Internal error', status: 500, detail: String(e), instance });
    }
  }

  return {
    db,
    fetch: async (request: Request) => {
      if (latency > 0) await new Promise((r) => setTimeout(r, latency));
      return handle(request);
    },
    dispose: () => {
      for (const t of timers) clearTimeout(t);
      timers.clear();
    },
  };
}
