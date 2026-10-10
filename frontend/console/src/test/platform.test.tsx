import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { findDemoUser } from '../auth/demoUsers';
import { createMockServer } from '../mock/server';
import { describeCron } from '../lib/cron';
import { mockCall, renderApp } from './utils';

const rowOf = (text: string | RegExp) => screen.getAllByText(text)[0].closest('tr')!;

describe('custom fields', () => {
  it('lists definitions; only a proposer sees New, and a proposal goes to approval and then applies', async () => {
    const user = userEvent.setup();
    const { unmount, server } = renderApp({ user: 'maker', route: '/masters/custom-fields' });
    expect(await screen.findByText('Sourcing agent code')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New custom field' })).not.toBeInTheDocument();
    unmount();

    renderApp({ user: 'admin', route: '/masters/custom-fields', server });
    await user.click(await screen.findByRole('button', { name: 'New custom field' }));
    const d = await screen.findByRole('dialog', { name: 'New custom field' });
    await user.type(within(d).getByLabelText(/^Key/), 'nickname');
    await user.type(within(d).getByLabelText(/^Label/), 'Nickname CLAUDE-TEST');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('New custom field sent for approval.')).toBeInTheDocument();

    const pending = (await mockCall(server, 'checker', 'GET', '/api/v1/approvals?status=PENDING&entityType=CUSTOM_FIELD')).body;
    const list = Array.isArray(pending) ? pending : pending.content;
    expect(list).toHaveLength(1);
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${list[0].id}/approve`, {})).status).toBe(200);
    const defs = (await mockCall(server, 'maker', 'GET', '/api/v1/custom-fields?entity=CUSTOMER')).body;
    expect(defs.map((f: { key: string }) => f.key)).toContain('nickname');
  });

  it('customer detail shows custom values with personal data masked', async () => {
    const server = createMockServer();
    const anu = server.db.customers[0];
    renderApp({ user: 'maker', route: `/customers/${anu.id}`, server });
    const values = await screen.findByTestId('custom-values');
    expect(await within(values).findByText('Salaried')).toBeInTheDocument();
    expect(within(values).getByText('Employee id')).toBeInTheDocument();
    expect(within(values).getByText('Personal data, masked')).toBeInTheDocument();
    expect(values.textContent).not.toContain('EMP-CLAUDE-TEST-0042');
  });

  it('new customer and new loan render inputs from the definitions', async () => {
    const user = userEvent.setup();
    const { unmount } = renderApp({ user: 'maker', route: '/customers/new' });
    await screen.findByRole('option', { name: /HO — Head Office/, hidden: true });
    await user.type(screen.getByLabelText(/First name/), 'Custom');
    await user.type(screen.getByLabelText(/Last name/), 'CLAUDE-TEST');
    await user.type(screen.getByLabelText(/Date of birth/), '1991-04-04');
    await user.type(screen.getByLabelText(/Mobile/), '9000012345');
    await user.type(screen.getByLabelText(/^PAN/), 'cccpz5555c');
    await user.click(screen.getByRole('button', { name: 'Check duplicates' }));
    const cont = screen.getByRole('button', { name: 'Continue' });
    await waitFor(() => expect(cont).toBeEnabled());
    await user.click(cont);
    await user.click(await screen.findByRole('tab', { name: /Additional details/ }));
    const c = await screen.findByTestId('custom-fields');
    expect(within(c).getByLabelText(/Occupation/)).toBeInstanceOf(HTMLSelectElement);
    expect(within(c).getByLabelText(/Dependants/)).toBeInTheDocument();
    unmount();
    renderApp({ user: 'maker', route: '/loans/new' });
    const l = await screen.findByTestId('custom-fields');
    expect(within(l).getByLabelText(/Sourcing agent code/)).toBeInTheDocument();
    expect(within(l).getByLabelText(/Credit life insurance/)).toHaveAttribute('type', 'checkbox');
  });

  it('loan and product pages show custom values; product values are proposed for approval', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = server.db.loans[0];
    const { unmount } = renderApp({ user: 'checker', route: `/loans/${loan.id}`, server });
    const lv = await screen.findByTestId('custom-values');
    expect(within(lv).getByText('Credit life insurance')).toBeInTheDocument();
    expect(within(lv).getByText('Yes')).toBeInTheDocument();
    unmount();

    renderApp({ user: 'maker', route: '/loan-products/PL01', server });
    const pv = await screen.findByTestId('custom-values');
    expect(within(pv).getByText('01 Apr 2025')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Edit additional details' }));
    const d = await screen.findByRole('dialog');
    const risk = within(d).getByLabelText(/Risk category/);
    await user.clear(risk);
    await user.type(risk, 'A');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Product details sent for approval.')).toBeInTheDocument();
    expect(server.db.productCustom.PL01.riskCategory).toBe('B');
  });

  it('API rejects a value that breaks the definition and masks PII in responses', async () => {
    const server = createMockServer();
    const anu = server.db.customers[0];
    const view = (await mockCall(server, 'maker', 'GET', `/api/v1/customers/${anu.id}`)).body;
    expect(JSON.stringify(view)).not.toContain('EMP-CLAUDE-TEST-0042');
    const bad = await mockCall(server, 'maker', 'PUT', '/api/v1/loan-products/PL01/custom', { custom: { launchedOn: 'yesterday' } });
    expect(bad.status).toBe(422);
  });
});

describe('sessions', () => {
  it('opens My sessions from the user menu and ends another session', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker' });
    await user.click(await screen.findByRole('button', { name: /User menu/ }));
    await user.click(screen.getByRole('menuitem', { name: 'My sessions' }));
    expect(await screen.findByRole('heading', { name: 'Sessions', level: 1 })).toBeInTheDocument();
    expect(await screen.findByText('This session')).toBeInTheDocument();
    expect(screen.queryByLabelText('Username')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /End session from 203\.0\.113\.40$/ }));
    const d = await screen.findByRole('dialog', { name: 'End session' });
    await user.click(within(d).getByRole('button', { name: 'End session' }));
    expect(await screen.findByText('Session ended.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('203.0.113.40')).not.toBeInTheDocument());
  });

  it('an administrator looks up and ends a session of another user; others are refused by the API', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'admin', route: '/sessions' });
    await user.type(await screen.findByLabelText(/Username/), 'checker');
    await user.click(screen.getByRole('button', { name: 'Show sessions' }));
    expect(await screen.findByText('10.20.2.15')).toBeInTheDocument();
    expect(screen.queryByText('This session')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /End session from 10\.20\.2\.15/ }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'End session' }));
    await waitFor(() => expect(server.db.sessions.some((s) => s.id === 'sess-checker-1')).toBe(false));
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/sessions?user=checker')).status).toBe(403);
  });
});

describe('jobs', () => {
  it('describes common cron shapes', () => {
    expect(describeCron('0 15 * * * *')).toBe('Every hour at 15 minutes past');
    expect(describeCron('0 30 6 * * *')).toBe('Every day at 06:30 IST');
    expect(describeCron('0 0 7 1 * *')).toBe('Day 1 of every month at 07:00 IST');
    expect(describeCron('0 0 2 * * 0')).toBe('Every Sunday at 02:00 IST');
    expect(describeCron('0 */5 * * * *')).toBeNull();
  });

  it('operations runs a job now and sees the run; cannot change schedules', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'ops', route: '/jobs' });
    expect(await screen.findByText('Every day at 06:30 IST')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Change schedule/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Run Flag expiring KYC documents now' }));
    expect(await screen.findByText(/Flag expiring KYC documents: completed/)).toBeInTheDocument();
    const runs = screen.getByRole('table', { name: 'Job runs' });
    await waitFor(() => expect(within(runs).getByText('ops')).toBeInTheDocument());
    expect(within(runs).getByText('Document store not reachable')).toBeInTheDocument();
  });

  it('a schedule change is validated and sent for approval, and applies once approved', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'admin', route: '/jobs' });
    await user.click(await screen.findByRole('button', { name: 'Change schedule of Report: DPD ageing' }));
    const d = await screen.findByRole('dialog', { name: 'Schedule of Report: DPD ageing' });
    const cron = within(d).getByLabelText(/Schedule \(cron, IST\)/);
    await user.clear(cron);
    await user.type(cron, '0 6 * *');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText(/Six fields/)).toBeInTheDocument();
    await user.clear(cron);
    await user.type(cron, '0 30 5 * * *');
    await user.click(within(d).getByLabelText('Enabled'));
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Job schedule change sent for approval.')).toBeInTheDocument();
    const job = () => server.db.jobs.find((j) => j.code === 'REPORT_DPD_AGEING')!;
    expect(job().enabled).toBe(false);
    const a = server.db.approvals.find((s) => s.approval.entityType === 'JOB')!;
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).status).toBe(200);
    expect(job()).toMatchObject({ enabled: true, schedule: '0 30 5 * * *' });
  });
});

describe('report schedule recipients', () => {
  it('shows the allowed domains and surfaces the refusal of an outside address', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'admin', route: '/jobs' });
    await user.click(await screen.findByRole('button', { name: 'Change schedule of Report: DPD ageing' }));
    const d = await screen.findByRole('dialog', { name: 'Schedule of Report: DPD ageing' });
    expect(await within(d).findByText(/Only addresses on: demo-nbfc.example/)).toBeInTheDocument();
    const to = within(d).getByLabelText(/E-mail to/);
    await user.clear(to);
    await user.type(to, 'a@outside.example');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await within(d).findByText(/not allowed: outside.example/)).toBeInTheDocument();
  });
});

describe('support access', () => {
  it('approves, rejects (note required) and revokes', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'admin', route: '/support-access' });
    expect(await screen.findByText(/1 support engineer has access right now/)).toBeInTheDocument();
    expect(within(rowOf('SUP-CLAUDE-TEST-2041')).getByText('2 h')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Reject access for ravi.k@corebanking.example' }));
    let d = await screen.findByRole('dialog', { name: 'Reject support access' });
    await user.click(within(d).getByRole('button', { name: 'Reject request' }));
    expect(within(d).getByText('Required')).toBeInTheDocument();
    await user.click(within(d).getByRole('button', { name: 'Cancel' }));

    await user.click(screen.getByRole('button', { name: 'Approve access for ravi.k@corebanking.example' }));
    d = await screen.findByRole('dialog', { name: 'Approve support access' });
    await user.click(within(d).getByRole('button', { name: 'Approve access' }));
    expect(await screen.findByText('Support access approved.')).toBeInTheDocument();
    expect(await screen.findByText(/2 support engineers have access right now/)).toBeInTheDocument();
    const approved = server.db.supportAccess.find((x) => x.ticket === 'SUP-CLAUDE-TEST-2041')!;
    expect(approved).toMatchObject({ status: 'APPROVED', decidedBy: 'admin' });
    expect(Date.parse(approved.expiresAt!) - Date.parse(approved.decidedAt!)).toBe(120 * 60_000);

    await user.click(screen.getByRole('button', { name: 'Revoke access of nisha.p@corebanking.example' }));
    d = await screen.findByRole('dialog', { name: 'Revoke support access' });
    await user.type(within(d).getByLabelText(/Reason for revoking/), 'Ticket closed CLAUDE-TEST');
    await user.click(within(d).getByRole('button', { name: 'Revoke access' }));
    expect(await screen.findByText('Support access revoked.')).toBeInTheDocument();
    expect(server.db.supportAccess.find((x) => x.ticket === 'SUP-CLAUDE-TEST-2032')).toMatchObject({ status: 'REVOKED', revokedBy: 'admin', revokeReason: 'Ticket closed CLAUDE-TEST' });
  });

  it('is hidden from and refused to users without support-access:approve', async () => {
    const { server } = renderApp({ user: 'checker', route: '/support-access' });
    expect(await screen.findByRole('heading', { name: /403/ })).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).queryByText('Support access')).not.toBeInTheDocument();
    expect((await mockCall(server, 'checker', 'GET', '/api/v1/support-access')).status).toBe(403);
  });
});

describe('deferred receipts', () => {
  const admin = findDemoUser('admin')!;
  const original = [...admin.permissions];
  afterEach(() => {
    admin.permissions = [...original];
  });

  it('the repayment dialog explains a 202 accepted for the next business date; the receipt is then listed', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    admin.permissions = [...original, 'loan:stp'];
    const loan = server.db.loans.find((l) => l.customerId === server.db.customers.find((c) => c.input.firstName === 'Deepak')!.id)!;
    server.db.dayStatus = 'EOD_RUNNING';
    const { unmount } = renderApp({ user: 'admin', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Repayment' }));
    const d = await screen.findByRole('dialog', { name: `Repayment on ${loan.loanNo}` });
    const amount = within(d).getByLabelText(/Amount/);
    await user.clear(amount);
    await user.type(amount, '5000');
    await user.click(within(d).getByRole('button', { name: 'Record repayment' }));
    expect(await screen.findByText(/Accepted for the next business date: ₹5,000.00 will be booked and valued on/)).toBeInTheDocument();
    expect(server.db.deferredReceipts).toHaveLength(1);
    unmount();

    renderApp({ user: 'admin', route: '/deferred-receipts', server });
    expect(await screen.findByText(loan.loanNo)).toBeInTheDocument();
    expect(within(rowOf(loan.loanNo)).getByText('Pending')).toBeInTheDocument();
    expect(within(rowOf(loan.loanNo)).queryByRole('button', { name: /Retry/ })).not.toBeInTheDocument();
  });

  it('staff without loan:stp get a clear 409 while end of day is running', async () => {
    const server = createMockServer();
    const loan = server.db.loans.find((l) => l.customerId === server.db.customers.find((c) => c.input.firstName === 'Deepak')!.id)!;
    server.db.dayStatus = 'EOD_RUNNING';
    const r = await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/repayments`, { amount: '100.00' });
    expect(r.status).toBe(409);
    expect(r.body.title).toBe('End of day is running');
  });

  it('a failed receipt is cancelled with a note, by loan:admin only', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    admin.permissions = [...original, 'loan:stp'];
    const loan = server.db.loans.find((l) => l.customerId === server.db.customers.find((c) => c.input.firstName === 'Deepak')!.id)!;
    server.db.dayStatus = 'EOD_RUNNING';
    await mockCall(server, 'admin', 'POST', `/api/v1/loans/${loan.id}/repayments`, { amount: '750.00' });
    server.db.dayStatus = 'OPEN';
    Object.assign(server.db.deferredReceipts[0], { status: 'FAILED', error: 'Loan is frozen', attempts: 1 });

    const { unmount } = renderApp({ user: 'maker', route: '/deferred-receipts', server });
    expect(await screen.findByText('Loan is frozen')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Cancel receipt/ })).not.toBeInTheDocument();
    unmount();

    renderApp({ user: 'admin', route: '/deferred-receipts', server });
    await user.click(await screen.findByRole('button', { name: `Cancel receipt on ${loan.loanNo}` }));
    const d = await screen.findByRole('dialog', { name: 'Cancel deferred receipt' });
    await user.click(within(d).getByRole('button', { name: 'Cancel receipt' }));
    expect(within(d).getByText('Required')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/What was done with the money/), 'Refunded to payer CLAUDE-TEST');
    await user.click(within(d).getByRole('button', { name: 'Cancel receipt' }));
    expect(await screen.findByText('Receipt cancelled.')).toBeInTheDocument();
    expect(server.db.deferredReceipts[0]).toMatchObject({ status: 'CANCELLED', resolvedBy: 'admin', resolutionNote: 'Refunded to payer CLAUDE-TEST' });
  });

  it('the help footer links to the developer portal', async () => {
    renderApp({ user: 'auditor' });
    const link = await screen.findByRole('link', { name: 'Developer portal' });
    expect(link.getAttribute('href')).toMatch(/\/developer$/);
  });
});
