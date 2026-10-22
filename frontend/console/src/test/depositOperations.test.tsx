import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { markInoperative, transferUnclaimed } from '../mock/depositOperations';
import { mockCall, renderApp } from './utils';

/** Cheques, inoperative and unclaimed accounts, the register of deposits and the net owned fund (P4-6). */
const bank = () => createMockServer({ tenant: 'demo-bank' });
const nbfc = () => createMockServer({ tenant: 'demo-nbfc-d' });
const customer = (server: MockServer, first: string) => server.db.customers.find((c) => c.input.firstName === first)!;
const approveLast = async (server: MockServer, entityType: string) => {
  const a = [...server.db.approvals].reverse().find((s) => s.approval.entityType === entityType && s.approval.status === 'PENDING')!;
  return mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
};
async function savings(server: MockServer, cash: number) {
  await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', { productCode: 'SB01', customerId: customer(server, 'Anu').id, branchCode: 'HO' });
  await approveLast(server, 'CASA_ACCOUNT');
  const a = server.db.deposits.accounts[0];
  if (cash) await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${a.id}/transactions`, { type: 'CASH_DEPOSIT', amount: cash });
  return a;
}
const balance = async (server: MockServer, id: string) => (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${id}`)).body.balance;
const present = (server: MockServer, accountNo: string, leafNo: number, amount: number, chequeDate: string) =>
  mockCall(server, 'maker', 'POST', '/api/v1/casa/clearing/inward', { accountNo, leafNo, amount, chequeDate, payee: 'CLAUDE-TEST Payee', presentingBank: 'HDFC Bank' });

describe('cheques', () => {
  it('issues a book through a checker, stops a cheque at once and pays or returns cheques presented', async () => {
    const server = bank();
    const sb = await savings(server, 20000);
    const today = server.db.businessDate;
    const bad = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/cheque-books`, { leaves: 7 });
    expect(bad.body.problems).toEqual(['a cheque book has 10, 25, 50 or 100 leaves']);
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/cheque-books`, { leaves: 10 })).body).toMatchObject({ status: 'PENDING', entityType: 'CHEQUE' });
    await approveLast(server, 'CHEQUE');
    const view = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}/cheques`)).body;
    expect(view.books[0]).toMatchObject({ firstLeaf: 100001, lastLeaf: 100010, unused: 10 });
    const stop = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/cheques/stop`, { fromLeaf: 100003, reason: 'lost' });
    expect(stop.body).toMatchObject({ status: 'APPROVED', action: 'STOP' });
    expect((await present(server, sb.accountNo!, 100001, 5000, today)).body).toMatchObject({ status: 'PAID' });
    expect(await balance(server, sb.id!)).toBe('15000');
    const outcomes = await Promise.all([
      present(server, sb.accountNo!, 100001, 10, today),
      present(server, sb.accountNo!, 100003, 10, today),
      present(server, sb.accountNo!, 100002, 15001, today),
      present(server, sb.accountNo!, 100099, 10, today),
      present(server, '20019999999999', 100002, 10, today),
    ]);
    expect(outcomes.map((o) => o.body.returnReason)).toEqual(['ALREADY_PAID', 'PAYMENT_STOPPED', 'INSUFFICIENT_FUNDS', 'NOT_ISSUED', 'NO_SUCH_ACCOUNT']);
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/cheques/100003/revoke-stop`, { reason: 'found' })).body).toMatchObject({ status: 'PENDING' });
    await approveLast(server, 'CHEQUE');
    expect((await present(server, sb.accountNo!, 100003, 100, today)).body.status).toBe('PAID');
  });

  it('credits a cheque paid in only once it clears', async () => {
    const server = bank();
    const sb = await savings(server, 1000);
    const lodged = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/cheque-deposits`,
      { chequeNo: '123456', draweeIfsc: 'hdfc0001234', drawerName: 'CLAUDE-TEST Drawer', amount: 7500, chequeDate: server.db.businessDate });
    expect(lodged.body).toMatchObject({ status: 'IN_CLEARING', draweeIfsc: 'HDFC0001234' });
    expect(await balance(server, sb.id!)).toBe('1000');
    renderApp({ user: 'maker', route: '/deposits/clearing', server });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Settle cheque 123456' }));
    await user.click(screen.getByRole('button', { name: 'Settle' }));
    expect(await screen.findByText('No cheque in clearing')).toBeInTheDocument();
    expect(await balance(server, sb.id!)).toBe('8500');
  });

  it('presents a cheque from the clearing page and shows why it was returned', async () => {
    const server = bank();
    const sb = await savings(server, 100);
    renderApp({ user: 'maker', route: '/deposits/clearing', server });
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Account number/), sb.accountNo!);
    await user.type(screen.getByLabelText(/^Cheque number/), '100001');
    await user.type(screen.getByLabelText(/^Amount/), '50');
    await user.type(screen.getByLabelText(/^Date on the cheque/), server.db.businessDate);
    await user.type(screen.getByLabelText(/^Payee/), 'CLAUDE-TEST Payee');
    await user.type(screen.getByLabelText(/^Presenting bank/), 'HDFC Bank');
    await user.click(screen.getByRole('button', { name: 'Present' }));
    expect(await screen.findByText(/returned unpaid: cheque not issued to this account/)).toBeInTheDocument();
  });
});

describe('inoperative and unclaimed accounts', () => {
  it('takes credits but nothing out of an inoperative account until it is reactivated', async () => {
    const server = bank();
    const sb = await savings(server, 1000);
    markInoperative(server.db, sb.id!);
    const out = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/transactions`, { type: 'CASH_WITHDRAWAL', amount: 100 });
    expect(out.body.detail).toMatch(/is inoperative since/);
    await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/transactions`, { type: 'CASH_DEPOSIT', amount: 50 });
    expect(await balance(server, sb.id!)).toBe('1050');
    const list = (await mockCall(server, 'maker', 'GET', '/api/v1/casa/dormancy?stage=INOPERATIVE')).body;
    expect(list.accounts.map((a: { accountNo: string }) => a.accountNo)).toEqual([sb.accountNo]);
    renderApp({ user: 'maker', route: `/deposits/casa/${sb.id}`, server });
    const user = userEvent.setup();
    expect(await screen.findByTestId('dormancy-banner')).toHaveTextContent(/inoperative since/);
    await user.click(screen.getByRole('button', { name: 'Reactivate' }));
    await user.type(screen.getByLabelText(/^KYC record updated/), 'KYC 2026/77');
    await user.type(screen.getByLabelText(/^Reason/), 'visited the branch');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    await screen.findByText(/sent for approval/);
    await approveLast(server, 'DEPOSIT_DORMANCY');
    expect(server.db.deposits.accounts.find((a) => a.id === sb.id)!.inoperativeSince).toBeNull();
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/transactions`, { type: 'CASH_WITHDRAWAL', amount: 100 })).body.status).toBe('APPROVED');
  });

  it('refunds a balance transferred as unclaimed when the depositor claims it', async () => {
    const server = bank();
    const sb = await savings(server, 3000);
    markInoperative(server.db, sb.id!);
    transferUnclaimed(server.db, sb.id!);
    expect(await balance(server, sb.id!)).toBe('0');
    const blocked = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/transactions`, { type: 'CASH_DEPOSIT', amount: 10 });
    expect(blocked.body.detail).toMatch(/transferred as unclaimed/);
    await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/reactivate`, { kycReference: 'claim 9', reason: 'the depositor claimed' });
    await approveLast(server, 'DEPOSIT_DORMANCY');
    expect(await balance(server, sb.id!)).toBe('3000');
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/casa/dormancy')).body.accounts).toEqual([]);
  });
});

describe('the register of deposits and the net owned fund', () => {
  async function fd(server: MockServer, amount: number) {
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FD01', customerId: customer(server, 'Anu').id, branchCode: 'HO', amount, tenureMonths: 12, payoutTo: 'BANK_ACCOUNT', maturityInstruction: 'PAY_OUT' });
    await approveLast(server, 'TERM_DEPOSIT');
    const d = server.db.deposits.accounts[0];
    return { d, fund: () => mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${d.id}/fund`, { source: 'CASH', payoutBankAccount: { holderName: 'CLAUDE-TEST Anu', accountNumber: '123456789', ifsc: 'HDFC0001234' } }) };
  }

  it('holds an NBFC to 1.5 times its net owned fund', async () => {
    const server = nbfc();
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/deposits/net-owned-fund')).body).toMatchObject({ applies: true, multiple: '1.5', netOwnedFund: '50000000', cap: '75000000' });
    const r = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/net-owned-fund', { amount: 20000, asOf: '2026-06-01', reference: 'auditor certificate' });
    expect(r.body).toMatchObject({ status: 'PENDING', entityType: 'DEPOSIT_NOF' });
    await approveLast(server, 'DEPOSIT_NOF');
    const a = await fd(server, 20000);
    expect((await a.fund()).body.status).toBe('APPROVED');
    const b = await fd(server, 15000);
    expect((await b.fund()).body.problems).toEqual(['public deposits would be 35000, above 1.5 times the net owned fund of 20000 (30000); 10000 may still be accepted']);
    renderApp({ user: 'maker', route: '/deposits/register', server });
    expect(await screen.findByTestId('nof')).toHaveTextContent(/66\.67%/);
    expect(screen.getByText('1 deposit, total 20,000')).toBeInTheDocument();
  });

  it('a bank has no net owned fund cap', async () => {
    const server = bank();
    const r = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/net-owned-fund', { amount: 1, asOf: '2026-06-01', reference: 'x' });
    expect(r.body.problems).toEqual(["this institution's rule set holds deposits to no multiple of its net owned fund"]);
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/deposits/register')).body).toMatchObject({ count: 0, netOwnedFund: { applies: false } });
  });
});
