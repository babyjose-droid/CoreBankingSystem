import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

const cust = (s: MockServer, n: string) => s.db.customers.find((x) => x.input.firstName === n)!;

/** A fully disbursed floating-rate home loan (REPO + 2.75%, reset every 3 months). */
async function floatingLoan(s: MockServer) {
  const booked = await mockCall(s, 'maker', 'POST', '/api/v1/loans', { productCode: 'HL01', customerId: cust(s, 'Hari').id, amount: '1000000', tenorMonths: 120 });
  const id = booked.body.id as string;
  await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/kfs-acceptance`, { channel: 'OTP' });
  const d = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { mode: 'NEFT' });
  await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${d.body.id}/approve`, {});
  return id;
}

describe('floating-rate resets', () => {
  it('lists upcoming resets with the rate at the reset and the estimated EMI (tenure kept by default)', async () => {
    const s = createMockServer();
    const id = await floatingLoan(s);
    const rows = (await mockCall(s, 'maker', 'GET', '/api/v1/rate-resets/upcoming?days=120')).body as Array<Record<string, unknown>>;
    const r = rows.find((x) => x.loanId === id)!;
    expect(r).toMatchObject({ resetOption: 'KEEP_TENURE_CHANGE_EMI', held: false, benchmarkCode: 'REPO' });
    expect(r.estimatedEmi).toBeTruthy();
    expect(r.estimatedInstalmentsLeft).toBeGreaterThan(0);
    expect((await mockCall(s, 'maker', 'GET', '/api/v1/rate-resets/upcoming?days=400')).status).toBe(422);

    renderApp({ user: 'maker', route: '/rate-resets', server: s });
    await screen.findByRole('heading', { name: 'Rate resets' });
    await user().clear(screen.getByLabelText(/^Days ahead/));
    await user().type(screen.getByLabelText(/^Days ahead/), '120');
    const table = await screen.findByRole('table', { name: 'Loans due for a rate reset' });
    await waitFor(() => expect(within(table).getAllByRole('row').length).toBeGreaterThan(1));
    expect(within(table).getByText('Keep the tenure, change the EMI')).toBeInTheDocument();
  });

  it("the borrower's choice goes through maker-checker and applies only once approved", async () => {
    const s = createMockServer();
    const id = await floatingLoan(s);
    const bad = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/rate-reset-preference`, { option: 'KEEP_EMI_CHANGE_TENURE' });
    expect(bad.status).toBe(422);
    const p = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/rate-reset-preference`, { option: 'KEEP_EMI_CHANGE_TENURE', reason: 'CLAUDE-TEST letter' });
    expect(p.status).toBe(202);
    expect(p.body).toMatchObject({ entityType: 'LOAN_RESET_PREFERENCE', status: 'PENDING' });
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.resetPreference ?? null).toBeNull();
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/approvals/${p.body.id}/approve`, {})).status).toBe(403);
    expect((await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${p.body.id}/approve`, {})).status).toBe(200);
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.resetPreference).toBe('KEEP_EMI_CHANGE_TENURE');
    const r = ((await mockCall(s, 'maker', 'GET', '/api/v1/rate-resets/upcoming?days=120')).body as Array<Record<string, unknown>>).find((x) => x.loanId === id)!;
    expect(r.resetOption).toBe('KEEP_EMI_CHANGE_TENURE');
    expect(r.estimatedInstalmentsLeft).toBeNull();
  });
});

let u: ReturnType<typeof userEvent.setup> | null = null;
function user() {
  u ??= userEvent.setup();
  return u;
}
