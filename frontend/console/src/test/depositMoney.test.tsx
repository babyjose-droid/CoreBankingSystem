import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

/** Money on deposit accounts (P4-2). */
const bank = () => createMockServer({ tenant: 'demo-bank' });
const nbfc = () => createMockServer({ tenant: 'demo-nbfc-d' });
const customer = (server: MockServer, first: string) => server.db.customers.find((c) => c.input.firstName === first)!;
const approveLast = async (server: MockServer, entityType: string) => {
  const a = [...server.db.approvals].reverse().find((s) => s.approval.entityType === entityType && s.approval.status === 'PENDING')!;
  return mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
};
async function openSavings(server: MockServer, first: string, product = 'SB01') {
  await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', { productCode: product, customerId: customer(server, first).id, branchCode: 'HO' });
  await approveLast(server, 'CASA_ACCOUNT');
  return server.db.deposits.accounts[0];
}
const txn = (server: MockServer, id: string, type: string, amount: number, extra: Record<string, unknown> = {}) =>
  mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${id}/transactions`, { type, amount, ...extra });

describe('money on savings and current accounts', () => {
  it('posts a cash deposit at once below the threshold and sends a larger one to a checker', async () => {
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    const small = await txn(server, sb.id!, 'CASH_DEPOSIT', 50000);
    expect(small.status).toBe(202);
    expect(small.body).toMatchObject({ status: 'APPROVED', entityType: 'DEPOSIT_TXN', action: 'CASH_DEPOSIT' });
    const large = await txn(server, sb.id!, 'CASH_DEPOSIT', 200000);
    expect(large.body.status).toBe('PENDING');
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body).toMatchObject({ balance: '50000', availableBalance: '50000' });
    await approveLast(server, 'DEPOSIT_TXN');
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body.balance).toBe('250000');
  });

  it('refuses a withdrawal with every reason at once', async () => {
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    await txn(server, sb.id!, 'CASH_DEPOSIT', 1000);
    const res = await txn(server, sb.id!, 'BANK_TRANSFER_OUT', 1000.005);
    expect(res.status).toBe(422);
    expect(res.body.problems).toEqual(["the bank's reference (UTR) is required for a bank transfer", 'name the bank account the money is sent to', 'the amount has more than two decimals (paise)', 'the balance is 1000; 1000.005 would take it below zero']);
    const ifsc = await txn(server, sb.id!, 'BANK_TRANSFER_OUT', 10, { reference: 'UTR1', bankAccount: { holderName: 'CLAUDE-TEST Payee', accountNumber: '123456789', ifsc: 'HDFC12' } });
    expect(ifsc.body.problems).toEqual(['the IFSC is not a valid IFSC']);
  });

  it('records a bank transfer out with its UTR and the payee shown masked', async () => {
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    await txn(server, sb.id!, 'CASH_DEPOSIT', 10000);
    const res = await txn(server, sb.id!, 'BANK_TRANSFER_OUT', 2500, { reference: 'UTR-77', narration: 'rent', bankAccount: { holderName: 'CLAUDE-TEST Landlord', accountNumber: '1234 5678 9012', ifsc: 'hdfc0001234' } });
    expect(res.body.status).toBe('APPROVED');
    const moves = (await mockCall(server, 'auditor', 'GET', `/api/v1/casa/accounts/${sb.id}/movements`)).body;
    expect(moves[0]).toMatchObject({ kind: 'BANK_TRANSFER_OUT', direction: 'DEBIT', amount: '2500', balanceAfter: '7500', reference: 'UTR-77', bankAccountMasked: 'XXXXXXXX9012', bankIfsc: 'HDFC0001234' });
    expect(JSON.stringify(server.db.approvals.at(-1)!.payload)).not.toContain('123456789012');
  });

  it('transfers between accounts and reverses the transfer on both sides', async () => {
    const server = bank();
    const from = await openSavings(server, 'Anu');
    const to = await openSavings(server, 'Biju', 'CA01');
    await txn(server, from.id!, 'CASH_DEPOSIT', 5000);
    const t = await mockCall(server, 'maker', 'POST', '/api/v1/casa/transfers', { fromAccountId: from.id, toAccountId: to.id, amount: 3000 });
    expect(t.body.status).toBe('APPROVED');
    const moved = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${to.id}/movements`)).body[0];
    expect(moved).toMatchObject({ kind: 'TRANSFER_IN', counterpartyAccountNo: from.accountNo, balanceAfter: '3000' });
    const rev = await mockCall(server, 'maker', 'POST', `/api/v1/casa/movements/${moved.id}/reverse`, { reason: 'wrong account' });
    expect(rev.body.status).toBe('PENDING');
    await approveLast(server, 'DEPOSIT_TXN');
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${from.id}`)).body.balance).toBe('5000');
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${to.id}`)).body.balance).toBe('0');
    const again = await mockCall(server, 'maker', 'POST', `/api/v1/casa/movements/${moved.id}/reverse`, { reason: 'again' });
    expect(again.body.problems).toEqual(['the movement is already reversed']);
  });

  it('a debit freeze stops money going out until it is released; a lien holds part of the balance', async () => {
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    await txn(server, sb.id!, 'CASH_DEPOSIT', 10000);
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/holds`, { kind: 'DEBIT_FREEZE', reason: 'CLAUDE-TEST court order' })).body.status).toBe('PENDING');
    await approveLast(server, 'DEPOSIT_HOLD');
    expect((await txn(server, sb.id!, 'CASH_WITHDRAWAL', 1)).body.problems).toEqual(['the account is under a debit freeze (CLAUDE-TEST court order)']);
    expect((await txn(server, sb.id!, 'CASH_DEPOSIT', 1)).body.status).toBe('APPROVED');
    const hold = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}/holds`)).body[0];
    await mockCall(server, 'maker', 'POST', `/api/v1/casa/holds/${hold.id}/release`, { reason: 'lifted' });
    await approveLast(server, 'DEPOSIT_HOLD');
    await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/holds`, { kind: 'LIEN', amount: 6000, reason: 'CLAUDE-TEST security' });
    await approveLast(server, 'DEPOSIT_HOLD');
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body).toMatchObject({ balance: '10001', lienAmount: '6000', availableBalance: '4001', freezes: [] });
    expect((await txn(server, sb.id!, 'CASH_WITHDRAWAL', 4002)).body.problems).toEqual(['only 4001 is available: 6000 is held under lien']);
  });

  it('shows the balance and statement and posts a cash deposit from the account page', async () => {
    const user = userEvent.setup();
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    renderApp({ user: 'maker', route: `/deposits/casa/${sb.id}`, server });
    expect(await screen.findByTestId('balance')).toHaveTextContent('0.00');
    expect(await screen.findByText('No money has moved on this account yet')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Money in' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Amount/), '12500.50');
    await user.type(within(dialog).getByLabelText(/^Slip number/), 'SLIP-9');
    await user.click(within(dialog).getByRole('button', { name: 'Submit' }));
    expect(await screen.findByText('Cash deposit: done.')).toBeInTheDocument();
    expect(await screen.findByTestId('balance')).toHaveTextContent('12,500.50');
    const statement = await screen.findByRole('table', { name: 'Movements' });
    expect(within(statement).getByText(/Cash deposit/).closest('tr')).toHaveTextContent('SLIP-9');
  });

  it('a withdrawal refused on the page lists the reason; an auditor sees no money buttons', async () => {
    const user = userEvent.setup();
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    const { unmount } = renderApp({ user: 'maker', route: `/deposits/casa/${sb.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Money out' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Amount/), '100');
    await user.click(within(dialog).getByRole('button', { name: 'Submit' }));
    expect(await within(dialog).findByText('the balance is 0; 100 would take it below zero')).toBeInTheDocument();
    unmount();
    renderApp({ user: 'auditor', route: `/deposits/casa/${sb.id}`, server });
    expect(await screen.findByTestId('balance')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Money in' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Freeze or lien' })).not.toBeInTheDocument();
  });
});

describe('funding a term deposit', () => {
  it('funds a booked deposit from the depositor\'s savings account, then reverses the funding', async () => {
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    await txn(server, sb.id!, 'CASH_DEPOSIT', 150000);
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FDC01', customerId: customer(server, 'Anu').id, branchCode: 'HO', amount: 100000, tenureMonths: 12, payoutTo: 'CASA', payoutAccountId: sb.id });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    const other = await openSavings(server, 'Biju');
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/fund`, { source: 'ACCOUNT', fromAccountId: other.id })).body.problems).toEqual(
      [`account ${other.accountNo} is not held by the depositor`, `account ${other.accountNo}: the balance is 0; 100000 would take it below zero`],
    );
    const funded = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/fund`, { source: 'ACCOUNT', fromAccountId: sb.id, reference: 'FUND-1' });
    expect(funded.body).toMatchObject({ status: 'APPROVED', action: 'FUND', appliedRef: fd.accountNo });
    const view = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}`)).body;
    expect(view).toMatchObject({ status: 'ACTIVE', balance: '100000', termDeposit: { startDate: '2026-06-30', maturityDate: '2027-06-30', funded: true, fundingSource: 'ACCOUNT', fundingAccountNo: sb.accountNo } });
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body.balance).toBe('50000');
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/holds`, { kind: 'TOTAL_FREEZE', reason: 'x' })).body.problems).toEqual(['a term deposit takes a lien, not a freeze']);
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/funding/reverse`, { reason: 'funded from the wrong account' });
    await approveLast(server, 'TERM_DEPOSIT');
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}`)).body).toMatchObject({ status: 'BOOKED', balance: '0', termDeposit: { startDate: null, funded: false } });
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body.balance).toBe('150000');
  });

  it('a deposit-taking NBFC funds a deposit in cash with the payout bank account, from the page', async () => {
    const user = userEvent.setup();
    const server = nbfc();
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FD01', customerId: customer(server, 'Anu').id, branchCode: 'HO', amount: 50000, tenureMonths: 24, payoutTo: 'BANK_ACCOUNT', maturityInstruction: 'PAY_OUT' });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    renderApp({ user: 'maker', route: `/deposits/term/${fd.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Fund the deposit' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('option', { name: 'From a savings or current account' })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Submit' }));
    expect(await within(dialog).findByText('the IFSC is not a valid IFSC')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/^Account holder/), 'CLAUDE-TEST Anu');
    await user.type(within(dialog).getByLabelText(/^Account number/), '99887766554433');
    await user.type(within(dialog).getByLabelText(/^IFSC/), 'icic0004321');
    await user.type(within(dialog).getByLabelText(/^Receipt or slip number/), 'RCPT-1');
    await user.click(within(dialog).getByRole('button', { name: 'Submit' }));
    expect(await screen.findByText('Funding: done.')).toBeInTheDocument();
    expect(await screen.findByTestId('funding')).toHaveTextContent('In cash');
    expect(screen.getByText(/XXXXXXXX4433, IFSC ICIC0004321/)).toBeInTheDocument();
    expect(screen.getByTestId('balance')).toHaveTextContent('50,000.00');
  });
});
