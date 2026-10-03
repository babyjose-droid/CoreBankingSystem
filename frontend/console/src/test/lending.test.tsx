import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

/** Loan rows are labelled "Open loan …"; find one by a customer name inside it. */
const loanRow = (name: string) => screen.getAllByRole('row', { name: /Open loan/ }).find((r) => within(r).queryByText(name))!;

const loanByCustomer = (server: MockServer, firstName: string) => {
  const c = server.db.customers.find((x) => x.input.firstName === firstName)!;
  return server.db.loans.find((l) => l.customerId === c.id)!;
};

describe('loan products', () => {
  it('lists products and shows terms, fees and the interest table', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'checker', route: '/loan-products' });
    expect(await screen.findByRole('heading', { name: 'Loan products' })).toBeInTheDocument();
    expect(await screen.findByText('Personal Loan')).toBeInTheDocument();
    expect(screen.getByText('Micro Bullet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New product' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('row', { name: 'Open product PL01' }));
    expect(await screen.findByRole('heading', { name: 'Personal Loan' })).toBeInTheDocument();
    expect(screen.getByTestId('interest-table')).toHaveTextContent('PL1');
    expect(screen.getByText(/0.75% \(min ₹500.00, max ₹10,000.00\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Propose change' })).not.toBeInTheDocument();
  });

  it('maker proposes a new product; approval creates version 1 and a change bumps the version', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/loan-products' });
    await user.click(await screen.findByRole('button', { name: 'New product' }));
    await screen.findByRole('heading', { name: 'New loan product' });
    await user.type(screen.getByLabelText(/^Code/), 'GL01');
    await user.type(screen.getByLabelText(/^Name/), 'CLAUDE-TEST Gold Loan');
    await user.type(screen.getByLabelText(/^Minimum amount/), '5000');
    await user.type(screen.getByLabelText(/^Maximum amount/), '200000');
    await user.type(screen.getByLabelText(/^Minimum tenor/), '3');
    await user.type(screen.getByLabelText(/^Maximum tenor/), '12');
    await user.type(screen.getByLabelText(/^Minimum rate/), '10');
    await user.type(screen.getByLabelText(/^Maximum rate/), '9');
    await user.click(screen.getByRole('button', { name: 'Add fee' }));
    const fee = screen.getByRole('group', { name: 'Fee 1' });
    await user.type(within(fee).getByLabelText('Fee 1 code'), 'PF');
    await user.type(within(fee).getByLabelText('Fee 1 name'), 'Processing fee');
    await user.type(within(fee).getByLabelText('Fee 1 percent'), '1');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    // client-side check: max rate below min rate
    expect(await screen.findByText('Fix the highlighted fields.')).toBeInTheDocument();
    await user.clear(screen.getByLabelText(/^Maximum rate/));
    await user.type(screen.getByLabelText(/^Maximum rate/), '18');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('New loan product sent for approval.')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Loan products' })).toBeInTheDocument();

    const pending = server.db.approvals.find((s) => s.approval.entityType === 'LOAN_PRODUCT' && s.approval.status === 'PENDING')!;
    expect(pending.approval.maker).toBe('maker');
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${pending.approval.id}/approve`, {})).status).toBe(200);
    const created = await mockCall(server, 'maker', 'GET', '/api/v1/loan-products/GL01');
    expect(created.body).toMatchObject({ code: 'GL01', version: 1, maxRate: '18.00', fees: [{ code: 'PF', percent: '1' }] });

    const change = await mockCall(server, 'maker', 'POST', '/api/v1/loan-products', { ...created.body, maxRate: '20' });
    expect(change.status).toBe(202);
    expect(change.body).toMatchObject({ entityType: 'LOAN_PRODUCT', action: 'UPDATE', entityId: 'GL01' });
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${change.body.id}/approve`, {});
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/loan-products/GL01')).body).toMatchObject({ version: 2, maxRate: '20.00' });
  });
});

describe('loans list', () => {
  it('shows DPD and asset-class badges and filters by status', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'checker', route: '/loans' });
    await screen.findByText('Deepak CLAUDE-TEST');
    const sma = loanRow('Deepak CLAUDE-TEST');
    expect(within(sma).getByText('SMA-1')).toHaveAttribute('data-asset-class', 'SMA1');
    expect(within(sma).getByText('52')).toBeInTheDocument();
    const npa = loanRow('Esha CLAUDE-TEST');
    expect(within(npa).getByText(/Sub-standard/)).toHaveClass('badge--asset-substandard');
    expect(screen.queryByRole('button', { name: 'New loan' })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Status'), 'SANCTIONED');
    await waitFor(() => expect(screen.queryByText('Deepak CLAUDE-TEST')).not.toBeInTheDocument());
    expect(loanRow('Gauri CLAUDE-TEST')).toBeTruthy();

    await user.selectOptions(screen.getByLabelText('Status'), '');
    await user.type(screen.getByLabelText('Search'), 'LOS-CLAUDE-TEST-0002');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(screen.getAllByRole('row', { name: /Open loan/ })).toHaveLength(1));
    expect(loanRow('Biju CLAUDE-TEST')).toBeTruthy();
  });
});

describe('new loan', () => {
  it('previews the KFS (EMI, APR, fees with GST split, schedule) and creates the loan', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/loans/new' });
    await screen.findByRole('heading', { name: 'New loan' });
    await user.click(await screen.findByRole('button', { name: 'Select Anu CLAUDE-TEST' }));
    expect(screen.getByTestId('selected-customer')).toHaveTextContent('Anu CLAUDE-TEST');
    await within(screen.getByLabelText(/^Product/)).findByRole('option', { name: /PL01/, hidden: true });
    await user.selectOptions(screen.getByLabelText(/^Product/), 'PL01');
    expect(screen.getByText(/From interest table/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Rate % p.a./)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/^Moratorium/)).toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Amount/), '100000');
    await user.type(screen.getByLabelText(/^Tenor/), '12');
    const create = screen.getByRole('button', { name: 'Create loan' });
    expect(create).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    const kfs = await screen.findByTestId('kfs');
    expect(within(kfs).getByTestId('kfs-emi')).toHaveTextContent('₹9,168.00');
    expect(within(kfs).getByTestId('kfs-apr')).toHaveTextContent('19.45%');
    expect(within(kfs).getByTestId('kfs-net')).toHaveTextContent('₹99,115.00');
    const fees = within(kfs).getByRole('table', { name: 'Fees and GST' });
    const pf = within(fees).getByRole('row', { name: /Processing fee/ });
    expect(pf).toHaveTextContent('₹750.00');
    expect(pf).toHaveTextContent('₹67.50');
    expect(within(kfs).getByText(/intra-state: CGST \+ SGST/)).toBeInTheDocument();
    const schedule = within(kfs).getByRole('table', { name: 'Repayment schedule' });
    expect(within(schedule).getAllByRole('row')).toHaveLength(13);

    // Changing the terms makes the preview stale until previewed again
    await user.type(screen.getByLabelText(/^Tenor/), '{backspace}8');
    expect(create).toBeDisabled();
    expect(screen.getByText(/Terms changed since the last preview/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    await waitFor(() => expect(create).toBeEnabled());
    await user.click(create);

    expect(await screen.findByRole('heading', { name: /^Loan 1001/ })).toBeInTheDocument();
    expect(screen.getByText(/booked as sanctioned/)).toBeInTheDocument();
    expect(screen.getAllByText('Sanctioned').length).toBeGreaterThan(0);
  });

  it('asks for the rate when the product has no interest table and shows server validation', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/loans/new' });
    await user.click(await screen.findByRole('button', { name: 'Select Chitra CLAUDE-TEST' }));
    expect(screen.getByText(/KYC is pending/)).toBeInTheDocument();
    await within(screen.getByLabelText(/^Product/)).findByRole('option', { name: /ML01/, hidden: true });
    await user.selectOptions(screen.getByLabelText(/^Product/), 'ML01');
    await user.type(screen.getByLabelText(/^Amount/), '10000');
    await user.type(screen.getByLabelText(/^Tenor/), '1');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await screen.findByText('Enter the rate')).toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Rate % p.a./), '24');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    await screen.findByTestId('kfs');
    await user.click(screen.getByRole('button', { name: 'Create loan' }));
    expect(await screen.findByText(/Customer KYC is not verified/)).toBeInTheDocument();
  });
});

describe('loan detail and servicing', () => {
  it('records a repayment and shows it in transactions', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanByCustomer(server, 'Deepak');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    expect(await screen.findByRole('heading', { name: `Loan ${loan.loanNo}` })).toBeInTheDocument();
    expect(screen.getByTestId('stat-dpd')).toHaveTextContent('52');
    expect(screen.getByTestId('stat-overdue')).toHaveTextContent('₹10,954.00');
    const demands = await screen.findByRole('table', { name: 'Demands raised' });
    expect(within(demands).getAllByText('Unpaid')).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: 'Repayment' }));
    const d = await screen.findByRole('dialog', { name: `Repayment on ${loan.loanNo}` });
    expect(within(d).getByLabelText(/^Amount/)).toHaveValue('10954.00');
    await user.selectOptions(within(d).getByLabelText('Mode'), 'UPI');
    await user.type(within(d).getByLabelText('Reference'), 'UTR-CLAUDE-TEST-1');
    await user.click(within(d).getByRole('button', { name: 'Record repayment' }));
    expect(await screen.findByText('Repayment of ₹10,954.00 recorded.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('stat-dpd')).toHaveTextContent('0'));
    expect(screen.getByTestId('stat-overdue')).toHaveTextContent('₹0.00');

    await user.click(screen.getByRole('tab', { name: 'Transactions' }));
    const txns = await screen.findByRole('table', { name: 'Loan transactions' });
    expect(within(txns).getByText(/Receipt via UPI \(UTR-CLAUDE-TEST-1\)/)).toBeInTheDocument();
    expect(within(txns).getAllByRole('button', { name: /Reverse transaction/ }).length).toBeGreaterThan(0);
  });

  it('lists day-end entries only when asked', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanByCustomer(server, 'Deepak');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'Transactions' }));
    const box = await screen.findByRole('checkbox', { name: 'Show day-end entries' });
    expect(box).not.toBeChecked();
    const txns = await screen.findByRole('table', { name: 'Loan transactions' });
    expect(within(txns).queryByText(/^Day end:/)).not.toBeInTheDocument();

    await user.click(box);
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Loan transactions' })).getAllByText(/^Day end: instalment \d+ demanded/).length).toBeGreaterThan(0));
    // a day-end entry is not a transaction a user can reverse
    const eod = within(screen.getByRole('table', { name: 'Loan transactions' })).getAllByText(/^Day end:/)[0].closest('tr')!;
    expect(within(eod).queryByRole('button', { name: /Reverse transaction/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Show day-end entries' }));
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Loan transactions' })).queryByText(/^Day end:/)).not.toBeInTheDocument());
  });

  it('pre-closes a loan for exactly the quoted amount', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanByCustomer(server, 'Anu');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Pre-closure' }));
    const d = await screen.findByRole('dialog', { name: `Pre-close loan ${loan.loanNo}` });
    const quote = await within(d).findByRole('table', { name: 'Pre-closure quote' });
    expect(within(quote).getByRole('row', { name: /Principal outstanding/ })).toHaveTextContent('₹1,63,307.00');
    expect(within(quote).getByRole('row', { name: /Foreclosure fee/ })).toHaveTextContent('₹3,854.04');
    expect(within(d).getByTestId('preclosure-total')).toHaveTextContent('₹1,68,235.04');
    await user.click(within(d).getByRole('button', { name: 'Pre-close for ₹1,68,235.04' }));
    expect(await screen.findByText(`Loan ${loan.loanNo} pre-closed.`)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('stat-outstanding')).toHaveTextContent('₹0.00'));
    expect(screen.getAllByText('Closed').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Repayment' })).not.toBeInTheDocument();
  });

  it('part-prepays with reduce EMI and cancels within cooling-off', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanByCustomer(server, 'Biju');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Part-prepayment' }));
    const d = await screen.findByRole('dialog', { name: /Part-prepayment/ });
    await user.type(within(d).getByLabelText(/^Amount/), '25000');
    await user.selectOptions(within(d).getByLabelText('Rebuild schedule'), 'REDUCE_EMI');
    await user.click(within(d).getByRole('button', { name: 'Record part-prepayment' }));
    expect(await screen.findByText(/Part-prepayment recorded. New EMI/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('stat-outstanding')).toHaveTextContent('₹50,000.00'));

    await user.click(screen.getByRole('button', { name: 'Cancel (cooling-off)' }));
    const c = await screen.findByRole('dialog', { name: /cooling-off/ });
    expect(await within(c).findByTestId('cancellation-total')).toBeInTheDocument();
    await user.click(within(c).getByRole('button', { name: /^Cancel for ₹/ }));
    expect(await screen.findByText(`Loan ${loan.loanNo} cancelled.`)).toBeInTheDocument();
  });

  it('shows the problem detail when a transaction is refused', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanByCustomer(server, 'Deepak');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Part-prepayment' }));
    const d = await screen.findByRole('dialog', { name: /Part-prepayment/ });
    await user.type(within(d).getByLabelText(/^Amount/), '1000');
    await user.click(within(d).getByRole('button', { name: 'Record part-prepayment' }));
    expect(await within(d).findByText('Overdue dues must be cleared first')).toBeInTheDocument();
    expect(within(d).getByText(/Pay the overdue ₹10,954.00 before a part-prepayment/)).toBeInTheDocument();
  });

  it('charges a bounce fee with GST and proposes a waiver', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanByCustomer(server, 'Anu');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Charge fee' }));
    const d = await screen.findByRole('dialog', { name: /Charge a fee/ });
    await within(within(d).getByLabelText(/^Fee/)).findByRole('option', { name: /Bounce charge/, hidden: true });
    await user.selectOptions(within(d).getByLabelText(/^Fee/), 'BNC');
    await user.click(within(d).getByRole('button', { name: 'Charge fee' }));
    expect(await screen.findByText('Fee charged.')).toBeInTheDocument();
    const charges = await screen.findByRole('table', { name: 'Charges' });
    const row = await within(charges).findByRole('row', { name: /Bounce charge/ });
    expect(row).toHaveTextContent('₹354.00'); // EMI below ₹10,000: ₹300 slab + 18% GST
    await user.click(within(row).getByRole('button', { name: 'Waive Bounce charge' }));
    const w = await screen.findByRole('dialog', { name: 'Waive Bounce charge' });
    await user.type(within(w).getByLabelText(/^Reason/), 'CLAUDE-TEST first bounce, bank error');
    await user.click(within(w).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Waiver sent for approval.')).toBeInTheDocument();
    expect(server.db.approvals.some((s) => s.approval.entityType === 'LOAN_WAIVER' && s.approval.amount === '354.00')).toBe(true);
  });

  it('records KFS acceptance, then disbursement goes to a checker (202) and applies on approval', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanByCustomer(server, 'Gauri');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    const disburse = await screen.findByRole('button', { name: 'Disburse' });
    expect(disburse).toBeDisabled();
    await user.click(screen.getByRole('tab', { name: 'KFS' }));
    await user.click(await screen.findByRole('button', { name: 'Record acceptance' }));
    const k = await screen.findByRole('dialog', { name: 'Record KFS acceptance' });
    await user.click(within(k).getByRole('button', { name: 'Record acceptance' }));
    expect(await screen.findByText('KFS acceptance recorded.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Disburse' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Disburse' }));
    const d = await screen.findByRole('dialog', { name: `Disburse loan ${loan.loanNo}` });
    await user.type(within(d).getByLabelText(/^Beneficiary account/), '50100012345678');
    await user.type(within(d).getByLabelText(/^IFSC/), 'HDFC0001234');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Disbursement sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((s) => s.approval.entityType === 'LOAN_DISBURSEMENT')!;
    expect(a.approval.proposed).toMatchObject({ beneficiaryAccount: 'XXXX5678', mode: 'IMPS' });
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body).toMatchObject({ status: 'ACTIVE', disbursedOn: '2026-06-30', netDisbursed: '297345.00' });
  });

  it('hides servicing actions from a checker', async () => {
    const server = createMockServer();
    const active = loanByCustomer(server, 'Anu');
    const { unmount } = renderApp({ user: 'checker', route: `/loans/${active.id}`, server });
    expect(await screen.findByRole('heading', { name: `Loan ${active.loanNo}` })).toBeInTheDocument();
    await screen.findByRole('table', { name: 'Demands raised' });
    for (const name of ['Repayment', 'Part-prepayment', 'Pre-closure', 'Charge fee', 'Freeze', 'Disburse']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
    // the only action left for a checker is the read-only simulation
    expect(within(screen.getByRole('toolbar', { name: 'Loan actions' })).getAllByRole('button').map((b) => b.textContent)).toEqual(['What if…']);
    unmount();
    const sanctioned = loanByCustomer(server, 'Gauri');
    renderApp({ user: 'checker', route: `/loans/${sanctioned.id}`, server });
    expect(await screen.findByRole('heading', { name: `Loan ${sanctioned.loanNo}` })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Disburse' })).not.toBeInTheDocument();
  });

  it('admin freezes a loan and repayments are then hidden', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanByCustomer(server, 'Anu');
    renderApp({ user: 'admin', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Freeze' }));
    const d = await screen.findByRole('dialog', { name: /Freeze loan/ });
    await user.type(within(d).getByLabelText(/^Reason/), 'CLAUDE-TEST legal hold');
    await user.click(within(d).getByRole('button', { name: 'Freeze' }));
    expect(await screen.findByText(/This loan is frozen/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Repayment' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unfreeze' })).toBeInTheDocument();
  });
});
