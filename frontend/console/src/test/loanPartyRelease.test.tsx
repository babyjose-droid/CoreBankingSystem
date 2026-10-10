import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

describe('release of a guarantor or co-applicant', () => {
  it('is proposed from the Parties tab, takes effect on approval and keeps the record', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = server.db.loans.find((l) => l.parties.some((p) => p.role === 'GUARANTOR'))!;
    const g = loan.parties.find((p) => p.role === 'GUARANTOR')!;
    loan.state.assetClass = 'STANDARD';
    const gNo = server.db.customers.find((c) => c.id === g.customerId)!.customerNo;
    const exposure = async () => (await mockCall(server, 'maker', 'GET', `/api/v1/customers/${g.customerId}/exposure`)).body as { asGuarantor: string; loansAsGuarantor: number };
    expect((await exposure()).loansAsGuarantor).toBe(1);
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'Parties' }));
    const table = await screen.findByRole('table', { name: 'Loan parties' });
    expect(within(table).queryByRole('button', { name: /Release .* from the loan/ })).toBeInTheDocument();
    expect(within(table).getAllByRole('button', { name: /Release .* from the loan/ })).toHaveLength(loan.parties.length);
    await user.click(within(table).getByRole('button', { name: `Release ${gNo} from the loan` }));
    const d = await screen.findByRole('dialog', { name: /^Release .* from / });
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('A reason is required')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/^Reason/), 'CLAUDE-TEST guarantee discharged');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Release of the party sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((s) => s.approval.entityType === 'LOAN_PARTY_RELEASE')!;
    expect(a.approval.checkersRequired).toBe(1);
    expect((await exposure()).loansAsGuarantor).toBe(1);
    // a second request for the same party is refused while one is pending
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/parties/${loan.parties[0].customerId}/release`, { reason: 'again' })).status).toBe(409);
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).status).toBe(200);
    expect(await exposure()).toMatchObject({ asGuarantor: '0.00', loansAsGuarantor: 0 });
    const parties = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/parties`)).body as Array<{ releasedOn: string | null; releaseReason: string | null; releasedBy: string | null }>;
    expect(parties.at(-1)).toMatchObject({ releasedOn: server.db.businessDate, releasedBy: 'maker', releaseReason: 'CLAUDE-TEST guarantee discharged' });
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/parties/${g.customerId}/release`, { reason: 'again' })).status).toBe(409);
  });

  it('never releases the borrower, and needs two checkers on a stressed loan', async () => {
    const server = createMockServer();
    const loan = server.db.loans.find((l) => l.parties.some((p) => p.role === 'GUARANTOR'))!;
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/parties/${loan.customerId}/release`, { reason: 'x' })).status).toBe(422);
    expect((await mockCall(server, 'auditor', 'POST', `/api/v1/loans/${loan.id}/parties/${loan.parties[0].customerId}/release`, { reason: 'x' })).status).toBe(403);
    loan.state.assetClass = 'SMA1';
    const r = await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/parties/${loan.parties[0].customerId}/release`, { reason: 'CLAUDE-TEST stressed' });
    expect(r.status).toBe(202);
    const a = server.db.approvals.find((s) => s.approval.entityType === 'LOAN_PARTY_RELEASE')!;
    expect(a.approval.checkersRequired).toBe(2);
    expect(a.approval.action).toBe('RELEASE_STRESSED');
  });
});
