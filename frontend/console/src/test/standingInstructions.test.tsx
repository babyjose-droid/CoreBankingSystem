import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { runStandingInstructions } from '../mock/depositStanding';
import { addDays } from '../lib/dates';
import { mockCall, renderApp } from './utils';

/** Standing instructions and loans against deposits (P4-5b). */
const bank = () => createMockServer({ tenant: 'demo-bank' });
const customer = (server: MockServer, first: string) => server.db.customers.find((c) => c.input.firstName === first)!;
const approveLast = async (server: MockServer, entityType: string) => {
  const a = [...server.db.approvals].reverse().find((s) => s.approval.entityType === entityType && s.approval.status === 'PENDING')!;
  return mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
};
async function savings(server: MockServer, customerId: string, cash: number) {
  await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', { productCode: 'SB01', customerId, branchCode: 'HO' });
  await approveLast(server, 'CASA_ACCOUNT');
  const a = server.db.deposits.accounts[0];
  if (cash) await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${a.id}/transactions`, { type: 'CASH_DEPOSIT', amount: cash });
  return a;
}
const balance = async (server: MockServer, id: string) => (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${id}`)).body.balance;
const run = (server: MockServer) => runStandingInstructions(server.db, new Date().toISOString());
async function give(server: MockServer, body: Record<string, unknown>) {
  const r = await mockCall(server, 'maker', 'POST', '/api/v1/casa/standing-instructions', body);
  expect(r.body).toMatchObject({ status: 'PENDING', entityType: 'STANDING_INSTRUCTION', action: 'CREATE' });
  const a = await approveLast(server, 'STANDING_INSTRUCTION');
  return server.db.deposits.instructions.find((s) => s.instructionNo === a.body.appliedRef)!;
}
function activeLoanWithCustomer(server: MockServer) {
  const loan = server.db.loans.find((l) => l.state.status === 'ACTIVE')!;
  return { loan, customerId: loan.customerId };
}

describe('standing instructions', () => {
  it('pays a monthly transfer once on its date, through a checker', async () => {
    const server = bank();
    const from = await savings(server, customer(server, 'Anu').id, 20000);
    const to = await savings(server, customer(server, 'Anu').id, 0);
    const bad = await mockCall(server, 'maker', 'POST', '/api/v1/casa/standing-instructions', { fromAccountId: from.id, kind: 'TRANSFER', toAccountId: from.id, amount: 0, everyMonths: 2 });
    expect(bad.body.problems).toEqual(['an account does not pay itself', 'the amount is above zero, to the paisa', 'everyMonths is 1, 3, 6 or 12']);
    const s = await give(server, { fromAccountId: from.id, kind: 'TRANSFER', toAccountId: to.id, amount: 5000, everyMonths: 1, narration: 'rent' });
    expect(s).toMatchObject({ status: 'ACTIVE', startOn: server.db.businessDate });
    expect(run(server)).toEqual({ processed: 1, failed: 0 });
    expect(run(server)).toEqual({ processed: 0, failed: 0 });
    expect(await balance(server, to.id!)).toBe('5000');
    const list = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/standing-instructions?accountId=${from.id}`)).body;
    expect(list[0]).toMatchObject({ instructionNo: s.instructionNo, paidCount: 1, lastPaidFor: server.db.businessDate });
    await mockCall(server, 'maker', 'POST', `/api/v1/casa/standing-instructions/${s.id}/cancel`, { reason: 'moved out' });
    await approveLast(server, 'STANDING_INSTRUCTION');
    expect(server.db.deposits.instructions[0].status).toBe('CANCELLED');
  });

  it('records a payment that cannot be made and tries it again for three days', async () => {
    const server = bank();
    const from = await savings(server, customer(server, 'Anu').id, 100);
    const to = await savings(server, customer(server, 'Anu').id, 0);
    const s = await give(server, { fromAccountId: from.id, kind: 'TRANSFER', toAccountId: to.id, amount: 5000, everyMonths: 1 });
    expect(run(server)).toEqual({ processed: 0, failed: 1 });
    run(server);
    const detail = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/standing-instructions/${s.id}`)).body;
    expect(detail.runs).toHaveLength(1);
    expect(detail.runs[0]).toMatchObject({ status: 'FAILED', attempts: 2 });
    expect(detail.runs[0].reason).toContain('5000');
    const start = server.db.businessDate;
    server.db.businessDate = addDays(start, 2);
    await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${from.id}/transactions`, { type: 'CASH_DEPOSIT', amount: 6000 });
    expect(run(server)).toEqual({ processed: 1, failed: 0 });
    server.db.businessDate = addDays(start, 31); // the next month's 5,000 is due on the 30th: 1,100 is left
    expect(run(server)).toEqual({ processed: 0, failed: 1 });
    server.db.businessDate = addDays(start, 40); // past its retry days: given up
    expect(run(server)).toEqual({ processed: 0, failed: 0 });
  });

  it('pays the instalments of a recurring deposit', async () => {
    const server = bank();
    const anu = customer(server, 'Anu');
    const sb = await savings(server, anu.id, 10000);
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'RD01', customerId: anu.id, branchCode: 'HO', amount: 1000, tenureMonths: 12, payoutTo: 'CASA', payoutAccountId: sb.id });
    await approveLast(server, 'TERM_DEPOSIT');
    const rd = server.db.deposits.accounts[0];
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${rd.id}/fund`, { source: 'ACCOUNT', fromAccountId: sb.id });
    await give(server, { fromAccountId: sb.id, kind: 'RD_INSTALMENT', toAccountId: rd.id });
    expect(run(server).processed).toBe(0);
    const due = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${rd.id}/instalments`)).body.next.dueDate;
    server.db.businessDate = due;
    expect(run(server).processed).toBe(1);
    const s = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${rd.id}/instalments`)).body;
    expect(s).toMatchObject({ paidCount: 2 });
    expect(s.schedule[1]).toMatchObject({ status: 'PAID', source: 'STANDING_INSTRUCTION' });
    expect(await balance(server, sb.id!)).toBe('8000');
  });

  it('sweeps a loan instalment from the borrower\'s savings on its due date', async () => {
    const server = bank();
    const { loan, customerId } = activeLoanWithCustomer(server);
    const sb = await savings(server, customerId, 150000); // below the checker threshold: posted at once
    const other = await savings(server, customer(server, 'Anu').id === customerId ? server.db.customers.find((c) => c.id !== customerId)!.id : customer(server, 'Anu').id, 0);
    const refused = await mockCall(server, 'maker', 'POST', '/api/v1/casa/standing-instructions', { fromAccountId: other.id, kind: 'LOAN_INSTALMENT', loanId: loan.id });
    expect(refused.body.problems[0]).toContain('not held by the borrower');
    await give(server, { fromAccountId: sb.id, kind: 'LOAN_INSTALMENT', loanId: loan.id });
    const due = loan.state.rows[loan.state.raised].dueDate; // the next instalment, not yet demanded
    server.db.businessDate = due;
    const events = loan.events.length;
    const r = run(server);
    expect(r.processed).toBeGreaterThanOrEqual(1);
    expect(loan.events.length).toBeGreaterThan(events);
    expect(server.db.deposits.movements.some((m) => m.accountId === sb.id && m.kind === 'LOAN_REPAYMENT')).toBe(true);
  });
});

describe('loans against deposits', () => {
  it('quotes and pledges a deposit for a loan of its holder, and keeps the pledge while the loan is open', async () => {
    const server = bank();
    const { loan, customerId } = activeLoanWithCustomer(server);
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FDC01', customerId, branchCode: 'HO', amount: 1000000, tenureMonths: 120, payoutTo: 'BANK_ACCOUNT' });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/fund`, { source: 'CASH', reference: 'SLIP', payoutBankAccount: { holderName: 'X', accountNumber: '123456789012', ifsc: 'HDFC0001234' } });
    await approveLast(server, 'TERM_DEPOSIT');
    server.db.loans.find((l) => l.id === loan.id)!.statedRate = 20;
    const q = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/pledge-quote?loanId=${loan.id}`)).body;
    expect(q).toMatchObject({ maxPercent: '90', spreadPercent: '1', maxLoan: '900000', problems: [] });
    const noReason = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/pledge`, { loanId: loan.id, reason: '' });
    expect(noReason.body.problems).toContain('a reason is required (the sanction letter or the loan agreement)');
    const p = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/pledge`, { loanId: loan.id, reason: 'sanction letter' });
    expect(p.body).toMatchObject({ status: 'PENDING', entityType: 'DEPOSIT_HOLD', action: 'PLEDGE' });
    await approveLast(server, 'DEPOSIT_HOLD');
    const holds = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/holds`)).body;
    expect(holds[0]).toMatchObject({ kind: 'LIEN', amount: '1000000', loanNo: loan.loanNo, status: 'ACTIVE' });
    const again = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/pledge`, { loanId: loan.id, reason: 'again' });
    expect(again.body.problems[0]).toContain('already secures');
    const release = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/holds/${holds[0].id}/release`, { reason: 'free it' });
    expect(release.body.problems[0]).toContain('released when the loan is closed');
    const pledges = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/pledges?loanId=${loan.id}`)).body;
    expect(pledges).toHaveLength(1);
    expect(pledges[0]).toMatchObject({ depositNo: fd.accountNo, status: 'ACTIVE' });
  });
});

describe('the console', () => {
  it('lists and gives standing instructions on a savings account', async () => {
    const server = bank();
    const anu = customer(server, 'Anu');
    const from = await savings(server, anu.id, 20000);
    const to = await savings(server, anu.id, 0);
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: `/deposits/casa/${from.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'New standing instruction' }));
    const dialog = await screen.findByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText(/To account/), to.id!);
    await user.type(within(dialog).getByLabelText(/Amount/), '2500');
    await user.click(within(dialog).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText(/Standing instruction sent for approval/)).toBeInTheDocument();
    await approveLast(server, 'STANDING_INSTRUCTION');
    run(server);
  });

  it('shows the pledge quote for a deposit and a loan', async () => {
    const server = bank();
    const { loan, customerId } = activeLoanWithCustomer(server);
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FDC01', customerId, branchCode: 'HO', amount: 1000000, tenureMonths: 120, payoutTo: 'BANK_ACCOUNT' });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/fund`, { source: 'CASH', reference: 'SLIP', payoutBankAccount: { holderName: 'X', accountNumber: '123456789012', ifsc: 'HDFC0001234' } });
    await approveLast(server, 'TERM_DEPOSIT');
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: `/deposits/term/${fd.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Pledge for a loan' }));
    const dialog = await screen.findByRole('dialog');
    await user.selectOptions(await within(dialog).findByLabelText(/^Loan/), loan.id);
    expect(await within(dialog).findByTestId('pledge-max')).toHaveTextContent('9,00,000');
  });
});
