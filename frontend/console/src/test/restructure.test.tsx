import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

const loanOf = (server: MockServer, firstName: string) => {
  const c = server.db.customers.find((x) => x.input.firstName === firstName)!;
  return server.db.loans.find((l) => l.customerId === c.id)!;
};

describe('loan amendments', () => {
  it('previews a rate change (keep EMI, change tenure), proposes it, and the approved change shows in history', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Anu');
    const first = renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Amend' }));
    const d = await screen.findByRole('dialog', { name: `Amend loan ${loan.loanNo}` });
    const submit = within(d).getByRole('button', { name: 'Submit for approval' });
    expect(submit).toBeDisabled();
    await user.clear(within(d).getByLabelText(/^New rate/));
    await user.type(within(d).getByLabelText(/^New rate/), '18');
    expect(within(d).getByLabelText("Borrower's choice")).toHaveValue('KEEP_EMI_CHANGE_TENURE');
    await user.click(within(d).getByRole('button', { name: 'Preview' }));

    const cmp = await within(d).findByRole('table', { name: 'Before and after' });
    const row = (name: RegExp) => within(cmp).getByRole('row', { name });
    expect(row(/^Rate/)).toHaveTextContent('16.00%18.00%');
    expect(row(/^EMI/)).toHaveTextContent('₹9,793.00₹9,793.00');
    expect(row(/^Remaining instalments/)).toHaveTextContent('1920');
    expect(row(/^Total interest/)).toHaveTextContent('₹22,655.00₹26,094.00');
    expect(within(within(d).getByRole('table', { name: 'New schedule' })).getAllByRole('row')).toHaveLength(21);

    await user.click(submit);
    expect(await within(d).findByText('A reason is required to propose')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/^Reason/), 'CLAUDE-TEST MCLR reset');
    await user.click(submit);
    expect(await screen.findByText('Amendment sent for approval.')).toBeInTheDocument();

    const a = server.db.approvals.find((s) => s.approval.entityType === 'LOAN_AMENDMENT')!;
    expect(a.approval.action).toBe('RATE_CHANGE');
    expect(a.approval.proposed).toMatchObject({ rate: '18.00%', emi: '9793.00', remainingInstalments: 20 });
    // Nothing changes before approval
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body.currentRate).toBe('16.00');
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).status).toBe(200);
    first.unmount();

    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    expect(await screen.findByTestId('stat-rate')).toHaveTextContent('18.00%');
    expect(screen.getByTestId('stat-rate')).toHaveTextContent('sanctioned at 16.00%');
    await user.click(screen.getByRole('tab', { name: 'Amendments' }));
    const hist = await screen.findByRole('table', { name: 'Amendment and restructure history' });
    const r = within(hist).getAllByRole('row')[1];
    expect(r).toHaveTextContent('Rate change');
    expect(r).toHaveTextContent('16.00 → 18.00');
    expect(r).toHaveTextContent('19 → 20');
    expect(r).toHaveTextContent('maker / checker');
  });

  it('refuses to extend the tenure of a borrower in arrears (that is a restructure)', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Deepak');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Amend' }));
    const d = await screen.findByRole('dialog', { name: /Amend loan/ });
    await user.selectOptions(within(d).getByLabelText('Change'), 'TENURE_CHANGE');
    await user.type(within(d).getByLabelText(/^Remaining instalments/), '40');
    await user.click(within(d).getByRole('button', { name: 'Preview' }));
    expect(await within(d).findByText('Borrower is in arrears')).toBeInTheDocument();
    expect(within(d).getByText(/is a restructure, not an amendment/)).toBeInTheDocument();
  });

  it('previews a due-day change', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Anu');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Amend' }));
    const d = await screen.findByRole('dialog', { name: /Amend loan/ });
    await user.selectOptions(within(d).getByLabelText('Change'), 'DUE_DAY_CHANGE');
    await user.type(within(d).getByLabelText(/^New due day/), '5');
    await user.click(within(d).getByRole('button', { name: 'Preview' }));
    const cmp = await within(d).findByRole('table', { name: 'Before and after' });
    expect(within(cmp).getByRole('row', { name: /^Next due/ })).toHaveTextContent('15 Jul 202605 Jul 2026');
  });
});

describe('restructure', () => {
  it('simulates options side by side, proposes the chosen one, and needs two checkers', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Deepak');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Restructure' }));
    const d = await screen.findByRole('dialog', { name: `Restructure loan ${loan.loanNo}` });
    expect(within(d).getByText(/downgrades this standard account to sub-standard/)).toBeInTheDocument();
    expect(within(d).getByText('two different checkers')).toBeInTheDocument();

    await user.type(within(d).getByLabelText(/^Option 1 instalments/), '36');
    await user.click(within(d).getByRole('button', { name: 'Add option' }));
    await user.type(within(d).getByLabelText(/^Option 2 instalments/), '48');
    await user.type(within(d).getByLabelText(/^Option 2 new rate/), '15');
    await user.clear(within(d).getByLabelText(/^Option 2 principal moratorium/));
    await user.type(within(d).getByLabelText(/^Option 2 principal moratorium/), '3');
    await user.selectOptions(within(d).getByLabelText('Option 2 overdue interest'), 'KEEP_AS_ARREARS');
    await user.click(within(d).getByRole('button', { name: 'Add option' }));
    await user.type(within(d).getByLabelText(/^Option 3 instalments/), '24');
    expect(within(d).getByRole('button', { name: 'Add option' })).toBeDisabled();
    await user.click(within(d).getByRole('button', { name: 'Simulate' }));

    const table = await within(d).findByRole('table', { name: 'Restructure options' });
    const headers = within(table).getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual(['Figure', 'Current 52 DPD', 'Option 1', 'Option 2', 'Option 3']);
    const row = (name: RegExp) => within(table).getByRole('row', { name });
    expect(row(/^Asset class/)).toHaveTextContent('SMA-1Sub-standard (NPA)Sub-standard (NPA)Sub-standard (NPA)');
    expect(row(/^Remaining instalments/)).toHaveTextContent('30364824');
    expect(row(/^EMI/)).toHaveTextContent('₹5,423.00');
    expect(row(/^Overdue interest capitalised/)).toHaveTextContent('₹4,068.00₹0.00₹4,068.00');
    expect(row(/^Overdue interest kept as arrears/)).toHaveTextContent('₹0.00₹4,068.00₹0.00');
    expect(within(table).getAllByTestId('npv-loss')).toHaveLength(3);

    const propose = within(d).getByRole('button', { name: 'Propose restructure' });
    expect(propose).toBeDisabled();
    await user.click(within(d).getByRole('radio', { name: 'Choose option 1' }));
    await user.type(within(d).getByLabelText(/^Reason/), 'CLAUDE-TEST job loss, income restored');
    await user.click(within(d).getByRole('button', { name: 'Propose option 1' }));
    expect(await screen.findByText('Restructure sent for approval.')).toBeInTheDocument();

    const a = server.db.approvals.find((s) => s.approval.entityType === 'LOAN_RESTRUCTURE')!;
    expect(a.approval.proposed).toMatchObject({ assetClass: 'SUBSTANDARD', remainingInstalments: 36 });
    expect((await mockCall(server, 'checker', 'GET', `/api/v1/approvals/${a.approval.id}`)).body).toMatchObject({ checkersRequired: 2, approvalsSoFar: 0 });
    const first = await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, { note: 'ok' });
    expect(first.body).toMatchObject({ status: 'PENDING', checkersRequired: 2, approvalsSoFar: 1 });
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).status).toBe(409);
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body.restructuredOn).toBeNull();
    expect((await mockCall(server, 'admin', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).body).toMatchObject({ status: 'APPROVED', approvalsSoFar: 2, appliedRef: `${loan.loanNo} #1` });
    const after = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body;
    expect(after).toMatchObject({ assetClass: 'SUBSTANDARD', restructuredOn: '2026-06-30', restructureCount: 1, dpd: 0, emi: '5099.00' });
    const hist = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/amendments`)).body;
    expect(hist[0]).toMatchObject({ kind: 'RESTRUCTURE', checkedBy: 'checker, admin', differsFromProposal: false });
    const sched = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/schedule`)).body;
    expect(sched.demands.filter((x: { principalRescheduled: string }) => Number(x.principalRescheduled) > 0)).toHaveLength(2);
    // A restructure cannot be reversed, nor anything before it
    const txns = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/transactions`)).body as Array<{ id: string; type: string }>;
    const restr = txns.find((t) => t.type === 'RESTRUCTURE')!;
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/transactions/${restr.id}/reverse`, { reason: 'x' })).status).toBe(422);
    const earlier = txns.find((t) => t.type === 'REPAYMENT')!;
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/transactions/${earlier.id}/reverse`, { reason: 'x' })).status).toBe(409);
  });

  it('shows the restructured banner with the upgrade date', async () => {
    const server = createMockServer();
    const loan = loanOf(server, 'Deepak');
    const p = await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/restructure`, { remainingInstalments: 36, overdueInterest: 'CAPITALISE', reason: 'CLAUDE-TEST' });
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${p.body.id}/approve`, {});
    await mockCall(server, 'admin', 'POST', `/api/v1/approvals/${p.body.id}/approve`, {});
    renderApp({ user: 'checker', route: `/loans/${loan.id}`, server });
    expect(await screen.findByText(/Restructured on/)).toHaveTextContent('cannot be upgraded before 10 Jul 2027');
    const demands = await screen.findByRole('table', { name: 'Demands raised' });
    expect(within(demands).getAllByText('Rescheduled')).toHaveLength(2);
  });
});

describe('permissions', () => {
  it('a checker sees neither Amend nor Restructure but can read the history', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Deepak');
    renderApp({ user: 'checker', route: `/loans/${loan.id}`, server });
    expect(await screen.findByRole('heading', { name: `Loan ${loan.loanNo}` })).toBeInTheDocument();
    await screen.findByRole('table', { name: 'Demands raised' });
    expect(screen.queryByRole('button', { name: 'Amend' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Restructure' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Amendments' }));
    expect(await screen.findByText('No amendments or restructures')).toBeInTheDocument();
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/loans/${loan.id}/restructure`, { remainingInstalments: 36, overdueInterest: 'CAPITALISE', reason: 'x' })).status).toBe(403);
    // The simulation only needs loan:view
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/loans/${loan.id}/restructure/simulation`, { options: [{ remainingInstalments: 36, overdueInterest: 'CAPITALISE' }] })).status).toBe(200);
  });
});
