import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

const cust = (s: MockServer, n: string) => s.db.customers.find((x) => x.input.firstName === n)!;
const loanOf = (s: MockServer, n: string) => s.db.loans.find((l) => l.customerId === cust(s, n).id)!;
const approve = (s: MockServer, id: string, user = 'checker') => mockCall(s, user, 'POST', `/api/v1/approvals/${id}/approve`, {});

async function trancheLoan(s: MockServer) {
  const booked = await mockCall(s, 'maker', 'POST', '/api/v1/loans', { productCode: 'HL01', customerId: cust(s, 'Hari').id, amount: '1000000', tenorMonths: 120 });
  const id = booked.body.id as string;
  await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/kfs-acceptance`, { channel: 'OTP' });
  const d = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { amount: '400000', mode: 'NEFT' });
  await approve(s, d.body.id);
  return id;
}

describe('products: templates, new options and preview', () => {
  it('starts from a template, previews the draft and submits it', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/loan-products/new' });
    const tpl = await screen.findByLabelText('Template');
    await within(tpl).findByRole('option', { name: /Business loan \(step-up EMI\)/, hidden: true });
    await user.selectOptions(tpl, 'BUSINESS_STEP_UP');
    expect(screen.getByLabelText('Repayment method')).toHaveValue('STEP_EQUATED');
    expect(screen.getByLabelText(/^Step %/)).toHaveValue('10');
    expect(screen.getByLabelText(/^Step every/)).toHaveValue('12');
    expect(screen.getByLabelText('Frequency')).toHaveValue('MONTHLY');
    expect(screen.getByLabelText(/^Minimum tenor/)).toHaveValue('24');

    // the preview needs no code
    await user.click(screen.getByRole('button', { name: 'Preview sample loan' }));
    const kfs = await screen.findByTestId('kfs');
    expect(screen.getByText(/Sample loan on this draft \(nothing is stored\)/)).toBeInTheDocument();
    expect(within(within(kfs).getByRole('table', { name: 'Repayment schedule' })).getAllByRole('row')).toHaveLength(25);

    // a step so steep that an instalment does not cover its interest is refused by the engine
    await user.clear(screen.getByLabelText(/^Step %/));
    await user.type(screen.getByLabelText(/^Step %/), '100');
    await user.clear(screen.getByLabelText(/^Step every/));
    await user.type(screen.getByLabelText(/^Step every/), '2');
    await user.click(screen.getByRole('button', { name: 'Preview sample loan' }));
    expect(await screen.findByText(/an instalment would not cover its interest/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText(/^Step %/));
    await user.type(screen.getByLabelText(/^Step %/), '5');
    await user.clear(screen.getByLabelText(/^Step every/));
    await user.type(screen.getByLabelText(/^Step every/), '12');

    await user.type(screen.getByLabelText(/^Code/), 'BL02');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('New loan product sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((x) => x.approval.entityType === 'LOAN_PRODUCT')!;
    expect(a.approval.proposed).toMatchObject({ code: 'BL02', repaymentMethod: 'STEP_EQUATED', stepPercent: '5', stepEvery: 12, frequency: 'MONTHLY', interestBasis: 'DAILY_REDUCING' });
  });

  it('shows floating, tranche and top-up options on the product page and the form', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/loan-products/HL01' });
    expect(await screen.findByRole('heading', { name: 'Home Loan (tranches)' })).toBeInTheDocument();
    expect(screen.getByTestId('product-disbursement')).toHaveTextContent('In tranches, pre-EMI interest until fully drawn · top-up allowed');
    expect(screen.getByText(/\+ 2.75% · reset every 3 months/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Propose change' }));
    expect(await screen.findByLabelText('Benchmark')).toHaveValue('REPO');
    expect(screen.getByRole('checkbox', { name: /Multiple disbursements/ })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /Pre-EMI/ })).toBeChecked();
    await user.selectOptions(screen.getByLabelText('Interest basis'), 'FLAT');
    await user.selectOptions(screen.getByLabelText('Repayment method'), 'FIXED_PRINCIPAL');
    expect(screen.getByLabelText(/^Principal every/)).toHaveValue('1');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Flat rate is for EMI (equated) products only')).toBeInTheDocument();
  });
});

describe('new loan: rate from an agreed instalment', () => {
  it('previews with the instalment instead of a rate', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/loans/new' });
    await user.click(await screen.findByRole('button', { name: 'Select Hari CLAUDE-TEST' }));
    await within(screen.getByLabelText(/^Product/)).findByRole('option', { name: /PL01/, hidden: true });
    await user.selectOptions(screen.getByLabelText(/^Product/), 'PL01');
    await user.type(screen.getByLabelText(/^Amount/), '100000');
    await user.type(screen.getByLabelText(/^Tenor/), '12');
    await user.type(screen.getByLabelText('Agreed instalment'), '9168');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    const kfs = await screen.findByTestId('kfs');
    expect(within(kfs).getByTestId('kfs-emi')).toHaveTextContent('₹9,168.00');
    expect(within(kfs).getByText(/rate that follows from the agreed instalment/)).toBeInTheDocument();
  });
});

describe('tranches, what-if, sanction change', () => {
  it('shows tranches and draws the next one after a simulation', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const id = await trancheLoan(server);
    renderApp({ user: 'maker', route: `/loans/${id}`, server });
    expect(await screen.findByTestId('stat-undrawn')).toHaveTextContent('₹6,00,000.00');
    await user.click(screen.getByRole('tab', { name: 'Tranches' }));
    expect(await screen.findByTestId('tranche-summary')).toHaveTextContent('Disbursed₹4,00,000.00');
    expect(within(screen.getByRole('table', { name: 'Tranches' })).getAllByRole('row')).toHaveLength(2);
    expect(screen.getByText(/Tranche draws, sanction changes and NPA overrides cannot be reversed/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Draw tranche' }));
    const d = await screen.findByRole('dialog', { name: /Draw a tranche on/ });
    expect(within(d).getByText(/A tranche draw cannot be reversed/)).toBeInTheDocument();
    const amount = within(d).getByLabelText(/^Amount to disburse/);
    expect(amount).toHaveValue('600000.00');
    await user.clear(amount);
    await user.type(amount, '250000');
    await user.click(within(d).getByRole('button', { name: 'Simulate' }));
    const sim = await within(d).findByTestId('disbursement-simulation');
    expect(within(sim).getByTestId('sim-net')).toHaveTextContent('₹2,50,000.00');
    expect(sim).toHaveTextContent('Undrawn afterwards₹3,50,000.00');
    expect(within(sim).getByText(/Pre-EMI: interest only until fully drawn/)).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/^Beneficiary account/), '50100012345678');
    await user.type(within(d).getByLabelText(/^IFSC/), 'HDFC0001234');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Tranche sent for approval.')).toBeInTheDocument();
    expect(server.db.approvals.find((x) => x.approval.entityType === 'LOAN_DISBURSEMENT' && x.approval.status === 'PENDING')!.approval.proposed).toMatchObject({ amount: '250000.00', tranche: 2 });
  });

  it('simulates a receipt on a future date', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Deepak');
    renderApp({ user: 'checker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'What if…' }));
    const d = await screen.findByRole('dialog', { name: /What if/ });
    expect(within(d).getByText(/nothing is posted or stored/)).toBeInTheDocument();
    await user.clear(within(d).getByLabelText(/^Amount/));
    await user.type(within(d).getByLabelText(/^Amount/), '6000');
    await user.click(within(d).getByRole('button', { name: 'Simulate' }));
    const r = await within(d).findByTestId('whatif-result');
    const alloc = within(r).getByRole('table', { name: 'Appropriation of the receipt' });
    expect(within(alloc).getAllByRole('row')[1]).toHaveTextContent('Instalment 5Interest');
    expect(r).toHaveTextContent('Dues after₹4,954.00');
    await user.selectOptions(within(d).getByLabelText('Transaction'), 'PRECLOSURE');
    await user.click(within(d).getByRole('button', { name: 'Simulate' }));
    expect(await within(d).findByTestId('whatif-total')).toHaveTextContent('₹');
    // the loan is untouched
    expect((await mockCall(server, 'checker', 'GET', `/api/v1/loans/${loan.id}`)).body.overdueAmount).toBe('10954.00');
  });

  it('previews and proposes a top-up; shows the refusal for an account in arrears', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const first = renderApp({ user: 'maker', route: `/loans/${loanOf(server, 'Anu').id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Change sanction' }));
    const d = await screen.findByRole('dialog', { name: /Change the sanctioned amount/ });
    expect(within(d).getByText(/A sanction change cannot be reversed/)).toBeInTheDocument();
    const submit = within(d).getByRole('button', { name: 'Submit for approval' });
    expect(submit).toBeDisabled();
    await user.type(within(d).getByLabelText(/^New sanctioned amount/), '250000');
    await user.click(within(d).getByRole('button', { name: 'Preview' }));
    const p = await within(d).findByTestId('sanction-preview');
    expect(p).toHaveTextContent('₹2,00,000.00 → ₹2,50,000.00 Top-up');
    expect(p).toHaveTextContent('Undrawn afterwards₹50,000.00');
    await user.type(within(d).getByLabelText(/^Reason/), 'CLAUDE-TEST top-up for renovation');
    await user.click(submit);
    expect(await screen.findByText('Sanction change sent for approval.')).toBeInTheDocument();
    first.unmount();

    renderApp({ user: 'maker', route: `/loans/${loanOf(server, 'Deepak').id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Change sanction' }));
    const d2 = await screen.findByRole('dialog', { name: /Change the sanctioned amount/ });
    await user.type(within(d2).getByLabelText(/^New sanctioned amount/), '200000');
    await user.click(within(d2).getByRole('button', { name: 'Preview' }));
    expect(await within(d2).findByText('Top-up refused')).toBeInTheDocument();
    expect(within(d2).getByText(/would be evergreening/)).toBeInTheDocument();
  });

  it('previews a maturity change in the Amend dialog', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    renderApp({ user: 'maker', route: `/loans/${loanOf(server, 'Anu').id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Amend' }));
    const d = await screen.findByRole('dialog', { name: /Amend loan/ });
    await user.selectOptions(within(d).getByLabelText('Change'), 'MATURITY_CHANGE');
    await user.type(within(d).getByLabelText(/^New maturity date/), '2027-07-31');
    await user.click(within(d).getByRole('button', { name: 'Preview' }));
    const cmp = await within(d).findByRole('table', { name: 'Before and after' });
    expect(within(cmp).getByRole('row', { name: /^Remaining instalments/ })).toHaveTextContent('1913');
    expect(within(cmp).getByRole('row', { name: /^Maturity/ })).toHaveTextContent('15 Jan 202815 Jul 2027');
  });
});

describe('manual NPA override', () => {
  it('proposes a mark (two checkers) and shows the refusal to release while dues are unpaid', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Deepak');
    const first = renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Mark NPA' }));
    const d = await screen.findByRole('dialog', { name: /Mark loan .* as NPA/ });
    expect(within(d).getByText('two different checkers')).toBeInTheDocument();
    expect(within(d).getByText(/cannot be reversed/)).toBeInTheDocument();
    await user.selectOptions(within(d).getByLabelText('Hold at class'), 'DOUBTFUL1');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('Choose the expiry date')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/^Until/), '2026-12-31');
    await user.type(within(d).getByLabelText(/^Reason/), 'CLAUDE-TEST fraud reported');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('NPA override sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((x) => x.approval.entityType === 'LOAN_NPA_OVERRIDE')!;
    expect(a.approval.checkersRequired).toBe(2);
    await approve(server, a.approval.id);
    await approve(server, a.approval.id, 'admin');
    first.unmount();

    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    expect(await screen.findByText(/Manual NPA mark: held at Doubtful-1 or worse until/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mark NPA' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Release NPA mark' }));
    const r = await screen.findByRole('dialog', { name: /Release the NPA mark/ });
    await user.type(within(r).getByLabelText(/^Reason/), 'CLAUDE-TEST');
    await user.click(within(r).getByRole('button', { name: 'Submit for approval' }));
    expect(await within(r).findByText('Account has unpaid dues')).toBeInTheDocument();
    await user.click(within(r).getByRole('button', { name: 'Cancel' }));
    await user.click(screen.getByRole('tab', { name: 'Amendments' }));
    await waitFor(() => expect(screen.getByRole('table', { name: 'Amendment and restructure history' })).toHaveTextContent('NPA override'));
  });

  it('hides money and classification actions from a checker but keeps the simulation', async () => {
    const server = createMockServer();
    const id = await trancheLoan(server);
    renderApp({ user: 'checker', route: `/loans/${id}`, server });
    expect(await screen.findByRole('button', { name: 'What if…' })).toBeInTheDocument();
    for (const name of ['Draw tranche', 'Change sanction', 'Mark NPA', 'Release NPA mark']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
  });
});
