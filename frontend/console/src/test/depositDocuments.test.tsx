import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

/** Depositor documents and deposit reports (P4-7). */
const bank = () => createMockServer({ tenant: 'demo-bank' });
const nbfc = () => createMockServer({ tenant: 'demo-nbfc-d' });
const customer = (server: MockServer, first: string) => server.db.customers.find((c) => c.input.firstName === first)!;
const approveLast = async (server: MockServer, entityType: string) => {
  const a = [...server.db.approvals].reverse().find((s) => s.approval.entityType === entityType && s.approval.status === 'PENDING')!;
  return mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
};
const get = (server: MockServer, path: string) => server.fetch(new Request(`http://localhost${path}`, { headers: { Authorization: 'Bearer mock.maker' } }));

const saved: Array<{ blob: Blob; name: string }> = [];
beforeEach(() => {
  saved.length = 0;
  let last: Blob | null = null;
  URL.createObjectURL = vi.fn((b: Blob) => ((last = b), 'blob:mock'));
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    if (this.download && last) saved.push({ blob: last, name: this.download });
  });
});
afterEach(() => vi.restoreAllMocks());

async function savings(server: MockServer, cash: number) {
  await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', { productCode: 'SB01', customerId: customer(server, 'Anu').id, branchCode: 'HO' });
  await approveLast(server, 'CASA_ACCOUNT');
  const a = server.db.deposits.accounts[0];
  await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${a.id}/transactions`, { type: 'CASH_DEPOSIT', amount: cash });
  return a;
}
async function fd(server: MockServer, product: string, amount: number, payoutTo = 'BANK_ACCOUNT') {
  await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: product, customerId: customer(server, 'Anu').id, branchCode: 'HO', amount, tenureMonths: 12, payoutTo, maturityInstruction: 'PAY_OUT' });
  await approveLast(server, 'TERM_DEPOSIT');
  return server.db.deposits.accounts[0];
}
const BANK_ACCOUNT = { holderName: 'CLAUDE-TEST Anu', accountNumber: '123456789', ifsc: 'HDFC0001234' };

describe('depositor documents', () => {
  it('makes a savings statement for a period, never past the business date, and audits it', async () => {
    const server = bank();
    const sb = await savings(server, 5000);
    const ok = await get(server, `/api/v1/casa/accounts/${sb.id}/statement.pdf`);
    expect(ok.status).toBe(200);
    expect(ok.headers.get('Content-Type')).toBe('application/pdf');
    const text = await ok.text();
    expect(text).toContain('Closing balance Rs 5000.00');
    expect(server.db.audit.some((e) => e.action === 'DOCUMENT_GENERATED' && e.entityId === sb.accountNo)).toBe(true);
    const late = await get(server, `/api/v1/casa/accounts/${sb.id}/statement.pdf?to=2099-01-01`);
    expect(late.status).toBe(422);
  });

  it('issues a deposit receipt only once it is funded; an NBFC receipt says it is not insured', async () => {
    const server = nbfc();
    const d = await fd(server, 'FD01', 50000);
    expect((await get(server, `/api/v1/deposits/accounts/${d.id}/receipt.pdf`)).status).toBe(409);
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${d.id}/fund`, { source: 'CASH', payoutBankAccount: BANK_ACCOUNT });
    const r = await get(server, `/api/v1/deposits/accounts/${d.id}/receipt.pdf`);
    expect(r.status).toBe(200);
    const text = await r.text();
    expect(text).toContain('Amount of deposit Rs 50000');
    expect(text).toContain('This deposit is not insured');
  });

  it('downloads a statement and a receipt from the deposit page', async () => {
    const server = nbfc();
    const d = await fd(server, 'FD01', 50000);
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${d.id}/fund`, { source: 'CASH', payoutBankAccount: BANK_ACCOUNT });
    renderApp({ user: 'maker', route: `/deposits/term/${d.id}`, server });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Receipt' }));
    await waitFor(() => expect(saved.map((s) => s.name)).toEqual([`receipt-${d.accountNo}.pdf`]));
    await user.click(screen.getByRole('button', { name: 'Statement' }));
    await user.click(await screen.findByRole('button', { name: 'Download PDF' }));
    await waitFor(() => expect(saved).toHaveLength(2));
    expect(saved[1].name).toMatch(new RegExp(`^statement-${d.accountNo}-`));
  });

  it('makes the interest certificate of a tax year that has begun', async () => {
    const server = bank();
    await savings(server, 1000);
    const c = customer(server, 'Anu');
    const r = await get(server, `/api/v1/deposits/tax/interest-certificate.pdf?customerId=${c.id}`);
    expect(r.status).toBe(200);
    expect(await r.text()).toContain('No interest was paid or credited in the year');
    expect((await get(server, `/api/v1/deposits/tax/interest-certificate.pdf?customerId=${c.id}&taxYear=2099`)).status).toBe(422);
  });
});

describe('deposit reports', () => {
  it('lists the deposit reports and runs the deposit book', async () => {
    const server = bank();
    await savings(server, 7000);
    const list = (await mockCall(server, 'maker', 'GET', '/api/v1/reports')).body as Array<{ code: string }>;
    expect(list.map((r) => r.code)).toEqual(expect.arrayContaining(['DEPOSIT_BOOK', 'DEPOSIT_MATURITY_PROFILE', 'DEPOSIT_INTEREST_PAID', 'DEPOSIT_TDS']));
    const run = await mockCall(server, 'maker', 'POST', '/api/v1/reports/DEPOSIT_BOOK/runs', { parameters: {} });
    expect(run.body).toMatchObject({ status: 'COMPLETED', rowCount: 1 });
    const missing = await mockCall(server, 'maker', 'POST', '/api/v1/reports/DEPOSIT_INTEREST_PAID/runs', { parameters: {} });
    expect(missing.status).toBe(422);
  });

  it('a lending-only tenant has no deposit reports', async () => {
    const server = createMockServer({ tenant: 'demo-nbfc' });
    const list = (await mockCall(server, 'maker', 'GET', '/api/v1/reports')).body as Array<{ code: string }>;
    expect(list.some((r) => r.code.startsWith('DEPOSIT_'))).toBe(false);
  });
});
