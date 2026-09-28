import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Approval, CustomerSummary, DedupeMatch, EodRun, TrialBalanceRow, Voucher } from '../api/types';
import { isLuhnValid } from '../lib/luhn';
import { toUnits } from '../lib/money';
import { mockCall } from '../test/utils';
import { createMockServer, type MockServer } from './server';

let server: MockServer;
const call = (user: string, method: string, path: string, body?: unknown, headers?: Record<string, string>) => mockCall(server, user, method, path, body, headers);

beforeEach(() => {
  server = createMockServer({ eodStepMs: 400 });
});
afterEach(() => server.dispose());

const balancedVoucher = {
  voucherType: 'JOURNAL',
  valueDate: '2026-06-30',
  description: 'CLAUDE-TEST accrual',
  lines: [
    { branch: 'HO', glCode: '1210', side: 'DR', amount: '1500.00' },
    { branch: 'HO', glCode: '4101', side: 'CR', amount: '1500.00' },
  ],
};

describe('maker-checker', () => {
  it('returns 202 with a PENDING approval and applies only after a different user approves', async () => {
    const res = await call('maker', 'POST', '/api/v1/branches', { code: 'KTM', name: 'CLAUDE-TEST Kottayam', stateCode: '32', parentCode: 'HO' });
    expect(res.status).toBe(202);
    const a = res.body as Approval;
    expect(a).toMatchObject({ status: 'PENDING', entityType: 'BRANCH', action: 'CREATE', maker: 'maker', current: null });
    expect((await call('maker', 'GET', '/api/v1/branches')).body.some((b: { code: string }) => b.code === 'KTM')).toBe(false);

    const approved = await call('checker', 'POST', `/api/v1/approvals/${a.id}/approve`, {});
    expect(approved.status).toBe(200);
    expect(approved.body).toMatchObject({ status: 'APPROVED', checker: 'checker' });
    expect((await call('maker', 'GET', '/api/v1/branches')).body.some((b: { code: string }) => b.code === 'KTM')).toBe(true);
  });

  it('rejects approving your own request with 409 problem+json', async () => {
    const { body } = await call('admin', 'POST', '/api/v1/tax-rates', { code: 'GST5', taxType: 'GST', ratePercent: '5.00', effectiveFrom: '2026-07-01' });
    const res = await call('admin', 'POST', `/api/v1/approvals/${(body as Approval).id}/approve`, {});
    expect(res.status).toBe(409);
    expect(res.contentType).toBe('application/problem+json');
    expect(res.body.title).toBe('Maker cannot approve own request');
  });

  it('requires a note to reject', async () => {
    const pending = (await call('checker', 'GET', '/api/v1/approvals?status=PENDING')).body as Approval[];
    const id = pending[0].id;
    const noNote = await call('checker', 'POST', `/api/v1/approvals/${id}/reject`, {});
    expect(noNote.status).toBe(422);
    const ok = await call('checker', 'POST', `/api/v1/approvals/${id}/reject`, { note: 'Missing documents' });
    expect(ok.body).toMatchObject({ status: 'REJECTED', note: 'Missing documents' });
    const again = await call('checker', 'POST', `/api/v1/approvals/${id}/approve`, {});
    expect(again.status).toBe(409);
  });

  it('shows current vs proposed for updates and closes the old tax rate on approval', async () => {
    const { body } = await call('maker', 'POST', '/api/v1/tax-rates', { code: 'GST18', taxType: 'GST', ratePercent: '28.00', effectiveFrom: '2026-10-01' });
    const a = body as Approval;
    expect(a.action).toBe('UPDATE');
    expect(a.current).toMatchObject({ ratePercent: '18.00' });
    expect(a.proposed).toMatchObject({ ratePercent: '28.00' });
    await call('checker', 'POST', `/api/v1/approvals/${a.id}/approve`, {});
    const rates = (await call('maker', 'GET', '/api/v1/tax-rates?asOf=2026-09-30')).body as Array<{ code: string; ratePercent: string; effectiveTo: string | null }>;
    expect(rates.find((r) => r.code === 'GST18')).toMatchObject({ ratePercent: '18.00', effectiveTo: '2026-09-30' });
  });

  it('bulk-approves with per-item results', async () => {
    const pending = (await call('checker', 'GET', '/api/v1/approvals?status=PENDING')).body as Approval[];
    const own = await call('checker', 'POST', '/api/v1/approvals/bulk-approve', { ids: [pending[0].id, 'does-not-exist'] });
    expect(own.body).toEqual([
      { id: pending[0].id, ok: true },
      { id: 'does-not-exist', ok: false, error: 'Not found' },
    ]);
  });

  it('enforces permissions server-side (403)', async () => {
    expect((await call('ops', 'GET', '/api/v1/customers')).status).toBe(403);
    expect((await call('maker', 'POST', '/api/v1/approvals/x/approve', {})).status).toBe(403);
    expect((await call('checker', 'POST', '/api/v1/gl/vouchers', balancedVoucher)).status).toBe(403);
  });
});

describe('ledger', () => {
  it('rejects unbalanced vouchers with 422', async () => {
    const res = await call('maker', 'POST', '/api/v1/gl/vouchers', {
      ...balancedVoucher,
      lines: [balancedVoucher.lines[0], { ...balancedVoucher.lines[1], amount: '1499.99' }],
    });
    expect(res.status).toBe(422);
    expect(res.body.title).toBe('Voucher does not balance');
  });

  it('accepts only posting heads', async () => {
    const res = await call('maker', 'POST', '/api/v1/gl/vouchers', { ...balancedVoucher, lines: [{ ...balancedVoucher.lines[0], glCode: '1200' }, balancedVoucher.lines[1]] });
    expect(res.status).toBe(422);
    expect(res.body.detail).toMatch(/group head/);
  });

  it('posts on approval, keeps the trial balance at zero, and reverses with mirror entries', async () => {
    const before = (await call('maker', 'GET', '/api/v1/gl/vouchers')).body as Voucher[];
    const { body } = await call('maker', 'POST', '/api/v1/gl/vouchers', {
      ...balancedVoucher,
      lines: [
        { branch: 'MUM', glCode: '1201', side: 'DR', amount: '100000' },
        { branch: 'HO', glCode: '1110', side: 'CR', amount: '100000' },
      ],
    });
    await call('checker', 'POST', `/api/v1/approvals/${(body as Approval).id}/approve`, {});
    const after = (await call('maker', 'GET', '/api/v1/gl/vouchers')).body as Voucher[];
    expect(after).toHaveLength(before.length + 1);
    const v = after.find((x) => !before.some((b) => b.id === x.id))!;
    expect(v).toMatchObject({ status: 'POSTED', amount: '100000.00', businessDate: '2026-06-30' });

    for (const branch of ['', 'HO', 'MUM']) {
      const tb = (await call('auditor', 'GET', `/api/v1/gl/trial-balance?asOf=2026-06-30${branch ? `&branch=${branch}` : ''}`)).body as TrialBalanceRow[];
      expect(tb.reduce((s, r) => s + toUnits(r.net), 0n)).toBe(0n);
    }

    const rev = await call('maker', 'POST', `/api/v1/gl/vouchers/${v.id}/reverse`, { note: 'Posted to wrong branch' });
    expect(rev.status).toBe(202);
    expect((await call('maker', 'POST', `/api/v1/gl/vouchers/${v.id}/reverse`, { note: 'again' })).status).toBe(409);
    await call('checker', 'POST', `/api/v1/approvals/${(rev.body as Approval).id}/approve`, {});
    const reversed = ((await call('maker', 'GET', '/api/v1/gl/vouchers')).body as Voucher[]).find((x) => x.id === v.id)!;
    expect(reversed.status).toBe('REVERSED');
    const entries = (await call('maker', 'GET', '/api/v1/gl/entries?glCode=1201&from=2026-06-30&to=2026-06-30&branch=MUM')).body as Array<{ side: string; lotType: string }>;
    expect(entries.map((e) => `${e.side}:${e.lotType}`).sort()).toEqual(['CR:REVERSAL', 'DR:VOUCHER']);
  });

  it('computes P&L with a net profit row and a balance sheet where A = L + E + P&L', async () => {
    const pnl = (await call('maker', 'GET', '/api/v1/gl/profit-and-loss?from=2026-04-01&to=2026-06-30')).body as Array<{ section: string; amount: string }>;
    const income = pnl.filter((r) => r.section === 'INCOME').reduce((s, r) => s + toUnits(r.amount), 0n);
    const expense = pnl.filter((r) => r.section === 'EXPENSE').reduce((s, r) => s + toUnits(r.amount), 0n);
    expect(toUnits(pnl.find((r) => r.section === 'NET_PROFIT')!.amount)).toBe(income - expense);
    const bs = (await call('maker', 'GET', '/api/v1/gl/balance-sheet?asOf=2026-06-30')).body as Array<{ section: string; amount: string }>;
    const sum = (s: string) => bs.filter((r) => r.section === s).reduce((a, r) => a + toUnits(r.amount), 0n);
    expect(sum('ASSET')).toBe(sum('LIABILITY') + sum('EQUITY') + sum('NET_PROFIT'));
  });
});

describe('customers', () => {
  const input = { customerType: 'INDIVIDUAL', firstName: 'Zed', lastName: 'CLAUDE-TEST', dateOfBirth: '1991-01-01', mobile: '9123456789', pan: 'ZZZPZ9999Z', homeBranch: 'HO' };

  it('returns masked summaries only', async () => {
    const list = (await call('maker', 'GET', '/api/v1/customers')).body as CustomerSummary[];
    expect(list.length).toBeGreaterThan(0);
    for (const c of list) {
      expect(c.panMasked).toMatch(/^XXXXX\d{4}X$/);
      expect(c.mobileMasked).toMatch(/^XXXXXX\d{4}$/);
      expect(JSON.stringify(c)).not.toMatch(/(?!XXXXX)\b[A-Z]{5}\d{4}[A-Z]\b/);
    }
  });

  it('dedupe: PAN = EXACT, mobile = STRONG, name + DOB = POSSIBLE', async () => {
    const d = async (body: object) => (await call('maker', 'POST', '/api/v1/customers/dedupe-check', { ...input, ...body })).body as DedupeMatch[];
    expect(await d({ pan: 'AAAPZ1234C' })).toEqual([expect.objectContaining({ rule: 'PAN', strength: 'EXACT' })]);
    expect(await d({ mobile: '9000000002' })).toEqual([expect.objectContaining({ rule: 'MOBILE', strength: 'STRONG' })]);
    expect(await d({ firstName: ' chitra ', lastName: 'claude-test', dateOfBirth: '1992-02-29' })).toEqual([expect.objectContaining({ rule: 'NAME_DOB', strength: 'POSSIBLE' })]);
    expect(await d({})).toEqual([]);
  });

  it('is idempotent on PAN: creating an existing PAN returns 200 with the existing customer', async () => {
    const res = await call('maker', 'POST', '/api/v1/customers', { ...input, pan: 'BBBPZ2345D' });
    expect(res.status).toBe(200);
    expect(res.body.displayName).toBe('Biju CLAUDE-TEST');
  });

  it('requires an override reason for possible duplicates, then creates a Luhn-valid customer number on approval', async () => {
    const dup = await call('maker', 'POST', '/api/v1/customers', { ...input, mobile: '9000000001' });
    expect(dup.status).toBe(409);
    expect(dup.body.matches).toHaveLength(1);
    const res = await call('maker', 'POST', '/api/v1/customers', { ...input, mobile: '9000000001', overrideDedupe: true, overrideReason: 'Shared family phone' });
    expect(res.status).toBe(202);
    expect(JSON.stringify(res.body)).not.toContain('ZZZPZ9999Z');
    const approved = await call('checker', 'POST', `/api/v1/approvals/${res.body.id}/approve`, {});
    const created = (await call('maker', 'GET', `/api/v1/customers/${approved.body.entityId}`)).body as CustomerSummary;
    expect(created.customerNo).toMatch(/^9001\d{10}$/);
    expect(isLuhnValid(created.customerNo)).toBe(true);
  });

  it('validates contract patterns and minimum age', async () => {
    const res = await call('maker', 'POST', '/api/v1/customers', { ...input, pan: 'BAD', mobile: '12345', dateOfBirth: '2010-01-01' });
    expect(res.status).toBe(422);
    expect(res.body.errors.map((e: { field: string }) => e.field).sort()).toEqual(['dateOfBirth', 'mobile', 'pan']);
  });

  it('replays the stored response for a repeated Idempotency-Key', async () => {
    const h = { 'Idempotency-Key': 'CLAUDE-TEST-key-1' };
    const a = await call('maker', 'POST', '/api/v1/customers', input, h);
    const b = await call('maker', 'POST', '/api/v1/customers', input, h);
    expect(a.status).toBe(202);
    expect(b.body.id).toBe(a.body.id);
    const pending = (await call('checker', 'GET', '/api/v1/approvals?status=PENDING&entityType=CUSTOMER')).body as Approval[];
    expect(pending.filter((p) => p.id === a.body.id)).toHaveLength(1);
  });
});

describe('end-of-day', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('progresses steps on a timer, reports exceptions, and advances the business date', async () => {
    const start = await call('ops', 'POST', '/api/v1/eod/runs');
    expect(start.status).toBe(202);
    const run = start.body as EodRun;
    expect(run.steps).toHaveLength(9);
    expect((await call('ops', 'GET', '/api/v1/business-day')).body.status).toBe('EOD_RUNNING');
    expect((await call('ops', 'POST', '/api/v1/eod/runs')).status).toBe(409);

    await vi.advanceTimersByTimeAsync(400 * 2 + 10);
    let now = (await call('ops', 'GET', `/api/v1/eod/runs/${run.id}`)).body as EodRun;
    expect(now.status).toBe('RUNNING');
    expect(now.steps!.slice(0, 2).every((s) => s.status === 'COMPLETED')).toBe(true);
    expect(now.steps![2].status).toBe('RUNNING');

    await vi.advanceTimersByTimeAsync(400 * 8);
    now = (await call('ops', 'GET', `/api/v1/eod/runs/${run.id}`)).body as EodRun;
    expect(now.status).toBe('COMPLETED_WITH_EXCEPTIONS');
    expect(now.steps!.find((s) => s.name === 'NPA marking')).toMatchObject({ status: 'COMPLETED_WITH_EXCEPTIONS', failed: 2 });
    expect(now.exceptions).toHaveLength(2);
    expect(now.nextBusinessDate).toBe('2026-07-01');
    expect((await call('ops', 'GET', '/api/v1/business-day')).body).toEqual({ businessDate: '2026-07-01', status: 'OPEN' });
  });

  it('skips Sundays and tenant-wide holidays when advancing', async () => {
    server.db.businessDate = '2026-07-03'; // Friday; 4 Jul is a mock holiday, 5 Jul a Sunday
    await call('ops', 'POST', '/api/v1/eod/runs');
    await vi.advanceTimersByTimeAsync(400 * 10);
    expect((await call('ops', 'GET', '/api/v1/business-day')).body.businessDate).toBe('2026-07-06');
  });

  it('restarts a failed run from the first incomplete step', async () => {
    server.db.eodFailAtStep = 'Demand raising';
    const run = (await call('ops', 'POST', '/api/v1/eod/runs')).body as EodRun;
    await vi.advanceTimersByTimeAsync(400 * 6);
    let now = (await call('ops', 'GET', `/api/v1/eod/runs/${run.id}`)).body as EodRun;
    expect(now.status).toBe('FAILED');
    expect(now.steps![4]).toMatchObject({ name: 'Demand raising', status: 'FAILED' });
    const firstStarted = now.steps![0].startedAt;

    expect((await call('ops', 'POST', `/api/v1/eod/runs/${run.id}/restart`)).status).toBe(202);
    await vi.advanceTimersByTimeAsync(400 * 6);
    now = (await call('ops', 'GET', `/api/v1/eod/runs/${run.id}`)).body as EodRun;
    expect(now.status).toBe('COMPLETED_WITH_EXCEPTIONS');
    expect(now.steps![0].startedAt).toBe(firstStarted); // completed steps were not re-run
    expect((await call('ops', 'POST', `/api/v1/eod/runs/${run.id}/restart`)).status).toBe(409);
  });
});

describe('audit', () => {
  it('appends an event for every mutation and verifies the hash chain', async () => {
    const before = ((await call('auditor', 'GET', '/api/v1/audit/events?limit=1000')).body as unknown[]).length;
    const { body } = await call('maker', 'POST', '/api/v1/holidays', [{ day: '2026-09-15', reason: 'CLAUDE-TEST holiday' }]);
    await call('checker', 'POST', `/api/v1/approvals/${(body as Approval).id}/approve`, {});
    const events = (await call('auditor', 'GET', '/api/v1/audit/events?limit=1000')).body as Array<{ action: string }>;
    expect(events.length).toBe(before + 2);
    expect(events[0].action).toBe('APPROVAL_APPROVED');
    expect((await call('auditor', 'GET', '/api/v1/audit/verify')).body).toEqual({ intact: true, firstBrokenId: null });

    server.db.audit[3].actor = 'tampered';
    expect((await call('auditor', 'GET', '/api/v1/audit/verify')).body).toEqual({ intact: false, firstBrokenId: server.db.audit[3].id });
  });
});
