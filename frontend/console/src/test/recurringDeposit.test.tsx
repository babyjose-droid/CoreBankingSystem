import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

/** Recurring deposits (P4-5): the product, the booking quote, instalments with their late charge, premature closure. */
const bank = () => createMockServer({ tenant: 'demo-bank' });
const nbfc = () => createMockServer({ tenant: 'demo-nbfc-d' });
const customer = (server: MockServer, first: string) => server.db.customers.find((c) => c.input.firstName === first)!;
const approveLast = async (server: MockServer, entityType: string) => {
  const a = [...server.db.approvals].reverse().find((s) => s.approval.entityType === entityType && s.approval.status === 'PENDING')!;
  return mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
};
const rdInput = (extra: Record<string, unknown> = {}) => ({
  code: 'RD9', name: 'Test recurring deposit', productType: 'RD', minAmount: 500, minTenureMonths: 6, maxTenureMonths: 60, interestPeriodMonths: 3,
  rateCard: [{ fromAmount: 0, fromMonths: 6, fromDays: 0, ratePercent: 6.5 }], prematureAllowed: true, prematurePenaltyPercent: 1, prematureMinDays: 7, rdLateChargePer100: 1.5, ...extra,
});

async function bookAndFund(server: MockServer) {
  const anu = customer(server, 'Anu');
  await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', { productCode: 'SB01', customerId: anu.id, branchCode: 'HO' });
  await approveLast(server, 'CASA_ACCOUNT');
  const sb = server.db.deposits.accounts[0];
  await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/transactions`, { type: 'CASH_DEPOSIT', amount: 50000 });
  await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'RD01', customerId: anu.id, branchCode: 'HO', amount: 1000, tenureMonths: 12, payoutTo: 'CASA', payoutAccountId: sb.id });
  await approveLast(server, 'TERM_DEPOSIT');
  const rd = server.db.deposits.accounts[0];
  const f = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${rd.id}/fund`, { source: 'ACCOUNT', fromAccountId: sb.id });
  expect(f.body.status).toBe('APPROVED');
  return { sb, rd, start: rd.termDeposit!.startDate! };
}

describe('recurring deposit products', () => {
  it('needs a late charge, runs for whole months and is never renewed', async () => {
    const server = bank();
    expect(server.db.deposits.products.find((p) => p.code === 'RD01')).toMatchObject({ productType: 'RD', rdLateChargePer100: '1.5', glLiability: '2405' });
    const none = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/products', rdInput({ rdLateChargePer100: null }));
    expect(none.body.detail).toMatch(/rdLateChargePer100 is required/);
    const renew = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/products', rdInput({ autoRenewalAllowed: true }));
    expect(renew.body.detail).toMatch(/not renewed/);
    const days = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/products/check', rdInput({ maxTenureDays: 15 }));
    expect(days.body.problems).toContain('a recurring deposit runs for whole months: its tenures have no days');
    const fd = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/products', { ...rdInput(), productType: 'FD_CUMULATIVE' });
    expect(fd.body.detail).toMatch(/setting of a recurring deposit product/);
  });
});

describe('booking a recurring deposit', () => {
  it('previews the monthly product with quarterly compounding', async () => {
    const server = bank();
    const r = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/preview', { productCode: 'RD01', amount: 1000, tenureMonths: 12 });
    expect(r.body).toMatchObject({ recurring: true, cumulative: true, ratePercent: '7', totalInterest: '462.40', maturityValue: '12462.40', annualisedYieldPercent: '7.11' });
    expect(r.body.periods.map((p: { interest: string }) => p.interest)).toEqual(['35.00', '88.11', '142.15', '197.14']);
    const days = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/preview', { productCode: 'RD01', amount: 1000, tenureMonths: 12, tenureDays: 10 });
    expect(days.body.problems).toContain('a recurring deposit runs for a whole number of months');
  });
});

describe('instalments', () => {
  it('records the funding as instalment 1, charges a late instalment, and pays it from the savings account', async () => {
    const server = bank();
    const { sb, rd, start } = await bookAndFund(server);
    const s1 = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${rd.id}/instalments`)).body;
    expect(s1).toMatchObject({ instalment: '1000', instalments: 12, paidCount: 1, paidIn: '1000', overdueCount: 0, next: { number: 2, lateCharge: '0', total: '1000', payableNow: true } });
    expect(s1.schedule[0]).toMatchObject({ number: 1, status: 'PAID', source: 'ACCOUNT', paidOn: start });
    const due = s1.next.dueDate as string;
    server.db.businessDate = new Date(Date.parse(due + 'T00:00:00Z') + 6 * 86_400_000).toISOString().slice(0, 10);
    const s2 = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${rd.id}/instalments`)).body;
    expect(s2).toMatchObject({ overdueCount: 1, next: { number: 2, lateCharge: '15', total: '1015' } });
    const utr = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${rd.id}/instalments`, { source: 'BANK_TRANSFER' });
    expect(utr.body.problems).toEqual(["the bank's reference (UTR) is required for a bank transfer"]);
    const before = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body.balance;
    const r = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${rd.id}/instalments`, { source: 'ACCOUNT', fromAccountId: sb.id });
    expect(r.body).toMatchObject({ status: 'APPROVED', entityType: 'TERM_DEPOSIT', action: 'INSTALMENT' });
    expect(Number(before) - Number((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body.balance)).toBe(1015);
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${rd.id}`)).body.balance).toBe('2000');
    const kinds = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}/movements`)).body.map((m: { kind: string }) => m.kind);
    expect(kinds.slice(0, 2).sort()).toEqual(['FUNDING_PAID', 'RD_LATE_CHARGE']);
    const s3 = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${rd.id}/instalments`)).body;
    expect(s3).toMatchObject({ paidCount: 2, lateChargesPaid: '15', next: { number: 3 } });
    expect(s3.schedule[1]).toMatchObject({ status: 'PAID', lateCharge: '15', source: 'ACCOUNT' });
  });

  it('closes early on whole months at the rate allowed, on the instalments held', async () => {
    const server = bank();
    const { rd, start } = await bookAndFund(server);
    const day = (months: number, days = 0) => {
      const d = new Date(Date.parse(start + 'T00:00:00Z'));
      d.setUTCMonth(d.getUTCMonth() + months);
      return new Date(d.getTime() + days * 86_400_000).toISOString().slice(0, 10);
    };
    server.db.businessDate = day(1, 6); // instalment 2 paid late: month 2 holds 1,000 only
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${rd.id}/instalments`, { source: 'CASH', reference: 'SLIP 2' });
    server.db.businessDate = day(3, 1);
    const q = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${rd.id}/closure-quote`)).body;
    // under 6 months the card has no rate: 7% less 1 point = 6% on 1,000 + 1,000 + 2,000 for a month each = 20.00
    expect(q).toMatchObject({ kind: 'PREMATURE', ratePercent: '6', interestDue: '20', payableBeforeTax: '2020', problems: [] });
  });

  it('a booked deposit is funded first, and a fixed deposit takes no instalments', async () => {
    const server = nbfc();
    const anu = customer(server, 'Anu');
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'RD02', customerId: anu.id, branchCode: 'HO', amount: 2000, tenureMonths: 12, payoutTo: 'BANK_ACCOUNT' });
    await approveLast(server, 'TERM_DEPOSIT');
    const rd = server.db.deposits.accounts[0];
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${rd.id}/instalments`, { source: 'CASH' })).body.detail).toMatch(/first instalment is its funding/);
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FD01', customerId: anu.id, branchCode: 'HO', amount: 20000, tenureMonths: 12, payoutTo: 'BANK_ACCOUNT' });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/instalments`)).status).toBe(404);
  });
});

describe('the console', () => {
  it('offers the recurring type with its late charge on the product form', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/deposits/products/term/new', server: bank() });
    await user.selectOptions(await screen.findByLabelText('Type'), 'RD');
    expect(screen.getByLabelText(/Late charge: Rs per 100/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Smallest monthly instalment/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Shortest tenure: days')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Renewal at maturity may be chosen')).not.toBeInTheDocument();
  });

  it('shows the instalments of a funded deposit and pays the next one', async () => {
    const server = bank();
    const { rd } = await bookAndFund(server);
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: `/deposits/term/${rd.id}`, server });
    expect(await screen.findByRole('heading', { name: new RegExp(`Recurring deposit ${rd.accountNo}`) })).toBeInTheDocument();
    expect(await screen.findByTestId('rd-paid')).toHaveTextContent('1 of 12');
    expect(screen.queryByRole('button', { name: 'Maturity instruction' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Pay instalment 2' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByTestId('instalment-total')).toHaveTextContent('1,000');
    await user.selectOptions(within(dialog).getByLabelText('Paid'), 'CASH');
    await user.type(within(dialog).getByLabelText(/Receipt or slip number/), 'SLIP 2');
    await user.click(within(dialog).getByRole('button', { name: 'Submit' }));
    expect(await screen.findByText(/Instalment 2: done/)).toBeInTheDocument();
    expect(await screen.findByTestId('rd-paid')).toHaveTextContent('2 of 12');
  });
});
