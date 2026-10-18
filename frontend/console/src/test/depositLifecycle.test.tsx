import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

/** The life of a deposit account after opening (P4-4): charges, closures, maturity instructions, maturities. */
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
const deposit = (server: MockServer, id: string, amount: number) => mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${id}/transactions`, { type: 'CASH_DEPOSIT', amount });
function withCharges(server: MockServer) {
  const p = server.db.deposits.products.find((x) => x.code === 'SB01')!;
  p.charges = { gstRatePercent: '18', minBalancePercent: '10', minBalanceMax: '300', closureAmount: '500', closureFreeWithinDays: 14, closureWithinDays: 365,
    service: [{ code: 'DUP_STATEMENT', name: 'Duplicate statement', amount: '100' }] };
}

describe('charges on savings and current accounts', () => {
  it('levies a service charge with GST through a checker, invoices it, and cancels it with a credit note when reversed', async () => {
    const server = bank();
    withCharges(server);
    const sb = await openSavings(server, 'Anu');
    await deposit(server, sb.id!, 1000);
    const bad = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/charges`, { code: 'NOPE', reason: '' });
    expect(bad.body.problems).toEqual(['a reason is required', 'product SB01 has no service charge NOPE; it has DUP_STATEMENT']);
    const r = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/charges`, { code: 'DUP_STATEMENT', reason: 'asked for a copy' });
    expect(r.body).toMatchObject({ status: 'PENDING', entityType: 'DEPOSIT_TXN', action: 'CHARGE' });
    await approveLast(server, 'DEPOSIT_TXN');
    const charges = (await mockCall(server, 'auditor', 'GET', `/api/v1/casa/accounts/${sb.id}/charges`)).body;
    expect(charges[0]).toMatchObject({ code: 'DUP_STATEMENT', taxableValue: '100', cgst: '9', sgst: '9', igst: '0', total: '118', status: 'ISSUED', invoiceNo: 'GST/DEP/00001' });
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body.balance).toBe('882');
    await mockCall(server, 'maker', 'POST', `/api/v1/casa/movements/${charges[0].movementId}/reverse`, { reason: 'waived' });
    await approveLast(server, 'DEPOSIT_TXN');
    expect((await mockCall(server, 'auditor', 'GET', `/api/v1/casa/accounts/${sb.id}/charges`)).body[0]).toMatchObject({ status: 'CANCELLED', creditNoteNo: 'GST/DEPCN/00001' });
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body.balance).toBe('1000');
  });

  it('a charge needs the money free after liens', async () => {
    const server = bank();
    withCharges(server);
    const sb = await openSavings(server, 'Anu');
    await deposit(server, sb.id!, 100);
    const r = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/charges`, { code: 'DUP_STATEMENT', reason: 'copy' });
    expect(r.body.problems).toEqual([`account ${sb.accountNo} has 100 free (after liens, and nothing when frozen for debits); the charge with GST is 118`]);
  });
});

describe('closing a savings or current account', () => {
  it('quotes the closure, refuses while a deposit pays into it, and pays the balance to another account', async () => {
    const server = bank();
    withCharges(server);
    const sb = await openSavings(server, 'Anu');
    const other = await openSavings(server, 'Anu', 'CA01');
    await deposit(server, sb.id!, 5000);
    const quote = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}/closure-quote`)).body;
    expect(quote).toMatchObject({ balance: '5000', closureCharge: null, payable: '5000', problems: [] });
    server.db.businessDate = '2026-08-31';
    const later = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}/closure-quote`)).body;
    expect(later).toMatchObject({ closureCharge: { taxableValue: '500', gst: '90', total: '590' }, payable: '4410' });
    const noPay = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/close`, { reason: 'moving away' });
    expect(noPay.body.problems).toEqual(['say where the proceeds go: payTo.mode is ACCOUNT, CASH, BANK_TRANSFER or GATEWAY']);
    const r = await mockCall(server, 'maker', 'POST', `/api/v1/casa/accounts/${sb.id}/close`, { reason: 'moving away', payTo: { mode: 'ACCOUNT', accountId: other.id } });
    expect(r.body).toMatchObject({ status: 'PENDING', entityType: 'CASA_ACCOUNT', action: 'CLOSE' });
    await approveLast(server, 'CASA_ACCOUNT');
    const closed = (await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body;
    expect(closed).toMatchObject({ status: 'CLOSED', balance: '0', closedOn: '2026-08-31', closure: { kind: 'CASA', charges: '590', paidAmount: '4410', payTo: 'ACCOUNT', payAccountNo: other.accountNo } });
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${other.id}`)).body.balance).toBe('4410');
    expect((await deposit(server, sb.id!, 1)).body.problems).toEqual([`account ${sb.accountNo} is CLOSED`]);
  });
});

describe('closing a term deposit', () => {
  async function bookAndFund(server: MockServer) {
    const sb = await openSavings(server, 'Anu');
    await deposit(server, sb.id!, 150000);
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FDC01', customerId: customer(server, 'Anu').id, branchCode: 'HO', amount: 100000, tenureMonths: 12, payoutTo: 'CASA', payoutAccountId: sb.id });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/fund`, { source: 'ACCOUNT', fromAccountId: sb.id });
    return { sb, fd };
  }

  it('a bank pays the rate for the period run less the penalty, and the proceeds go to the savings account', async () => {
    const server = bank();
    const { sb, fd } = await bookAndFund(server);
    server.db.businessDate = '2026-12-31';
    const q = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/closure-quote`)).body;
    expect(q).toMatchObject({ kind: 'PREMATURE', reason: 'DEPOSITOR_REQUEST', ratePercent: '5.25', basis: 'rate for the period run 6.25% less penalty 1 points', problems: [] });
    expect(q.interestDue).toBe('2656.99');
    const r = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/close`, { payTo: { mode: 'ACCOUNT', accountId: sb.id } });
    expect(r.body).toMatchObject({ status: 'PENDING', entityType: 'TERM_DEPOSIT', action: 'CLOSE' });
    await approveLast(server, 'TERM_DEPOSIT');
    const closed = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}`)).body;
    expect(closed).toMatchObject({ status: 'CLOSED', balance: '0', closure: { kind: 'PREMATURE', ratePercent: '5.25', interestDue: '2656.99', paidAmount: '102656.99', payAccountNo: sb.accountNo } });
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/casa/accounts/${sb.id}`)).body.balance).toBe('152656.99');
  });

  it('a deposit-taking NBFC repays nothing in the lock-in except on death, and needs a note for it', async () => {
    const server = nbfc();
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FD01', customerId: customer(server, 'Anu').id, branchCode: 'HO', amount: 50000, tenureMonths: 24, payoutTo: 'BANK_ACCOUNT', maturityInstruction: 'PAY_OUT' });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/fund`, { source: 'CASH', payoutBankAccount: { holderName: 'CLAUDE-TEST Anu', accountNumber: '123456789', ifsc: 'HDFC0001234' } });
    server.db.businessDate = '2026-08-31';
    const refused = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/closure-quote`)).body;
    expect(refused.problems).toEqual(['no repayment within 3 months of the deposit except on death or, for an individual, an emergency expense or critical illness']);
    const death = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/closure-quote?reason=DEATH`)).body;
    expect(death).toMatchObject({ ratePercent: '0', interestDue: '0', payableBeforeTax: '50000', problems: [] });
    const noNote = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/close`, { reason: 'DEATH', payTo: { mode: 'CASH', reference: 'V-1' } });
    expect(noNote.body.problems).toEqual(['say what supports a closure for death (the note: the certificate or claim seen)']);
    server.db.businessDate = '2026-11-30';
    const five = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/closure-quote`)).body;
    expect(five).toMatchObject({ ratePercent: '0', basis: 'closed before 6 months: no interest' });
    server.db.businessDate = '2027-01-31';
    const seven = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/closure-quote`)).body;
    expect(seven).toMatchObject({ ratePercent: '5.5', basis: 'no rate for the period run: lowest rate 8.5% less 3 points' });
    server.db.businessDate = '2027-07-31';
    const year = (await mockCall(server, 'maker', 'GET', `/api/v1/deposits/accounts/${fd.id}/closure-quote`)).body;
    expect(year).toMatchObject({ ratePercent: '6.5', basis: 'rate for the period run 8.5% less 2 points' });
  });
});

describe('maturity instruction and maturities', () => {
  it('a deposit-taking NBFC renews only with the depositor\'s consent', async () => {
    const server = nbfc();
    server.db.deposits.products.find((p) => p.code === 'FD01')!.autoRenewalAllowed = true;
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FD01', customerId: customer(server, 'Anu').id, branchCode: 'HO', amount: 50000, tenureMonths: 12, payoutTo: 'BANK_ACCOUNT', maturityInstruction: 'PAY_OUT' });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/fund`, { source: 'CASH', payoutBankAccount: { holderName: 'CLAUDE-TEST Anu', accountNumber: '123456789', ifsc: 'HDFC0001234' } });
    const no = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/maturity-instruction`, { maturityInstruction: 'RENEW_PRINCIPAL' });
    expect(no.body.problems).toEqual(["a renewal needs the depositor's express consent (NBFC Directions para 17): give the reference of their signed request and the date it was received"]);
    const yes = await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/maturity-instruction`, { maturityInstruction: 'RENEW_PRINCIPAL', consent: { reference: 'REQ-77', receivedOn: '2026-06-30' } });
    expect(yes.body).toMatchObject({ status: 'PENDING', action: 'INSTRUCTION' });
    await approveLast(server, 'TERM_DEPOSIT');
    const rows = (await mockCall(server, 'maker', 'GET', '/api/v1/deposits/maturities?from=2027-06-01&to=2027-07-31')).body;
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ accountNo: fd.accountNo, maturityDate: '2027-06-30', maturityInstruction: 'RENEW_PRINCIPAL', consentReference: 'REQ-77' });
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/renew`, { instruction: 'RENEW_PRINCIPAL', consent: { reference: 'R', receivedOn: '2026-06-30' } })).body.problems)
      .toEqual([`deposit ${fd.accountNo} is ACTIVE; a deposit is renewed on request once it has MATURED`]);
  });

  it('closes a savings account from its page and shows how it was closed', async () => {
    const user = userEvent.setup();
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    const other = await openSavings(server, 'Anu', 'CA01');
    await deposit(server, sb.id!, 700);
    renderApp({ user: 'maker', route: `/deposits/casa/${sb.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Close the account' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByTestId('casa-quote')).toHaveTextContent('700');
    await user.selectOptions(within(dialog).getByLabelText(/To account/), other.id!);
    await user.type(within(dialog).getByLabelText(/Reason/), 'customer request');
    await user.click(within(dialog).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText(/Closure sent for approval/)).toBeInTheDocument();
    await approveLast(server, 'CASA_ACCOUNT');
    expect(server.db.deposits.accounts.find((a) => a.id === sb.id)!.status).toBe('CLOSED');
  });

  it('lists maturities on their page', async () => {
    const server = bank();
    const sb = await openSavings(server, 'Anu');
    await deposit(server, sb.id!, 150000);
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FDP01', customerId: customer(server, 'Anu').id, branchCode: 'HO', amount: 100000, tenureDays: 50, tenureMonths: 0, payoutTo: 'CASA', payoutAccountId: sb.id });
    await approveLast(server, 'TERM_DEPOSIT');
    const fd = server.db.deposits.accounts[0];
    await mockCall(server, 'maker', 'POST', `/api/v1/deposits/accounts/${fd.id}/fund`, { source: 'ACCOUNT', fromAccountId: sb.id });
    renderApp({ user: 'maker', route: '/deposits/maturities', server });
    expect(await screen.findByText('No deposit matures in this window')).toBeInTheDocument();
    await userEvent.setup().type(screen.getByLabelText(/^To/), '2026-09-30');
    expect(await screen.findByRole('link', { name: fd.accountNo! })).toBeInTheDocument();
  });
});
