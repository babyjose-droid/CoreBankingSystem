import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fileNameFromDisposition } from '../lib/download';
import { tinyPdf } from '../mock/documents';
import { createMockServer, type MockServer } from '../mock/server';
import { paramFields } from '../pages/reports/ReportsPage';
import { mockCall, renderApp } from './utils';

/** jsdom has no object URLs and cannot "download": capture what would have been saved. */
const saved: Array<{ blob: Blob; name: string }> = [];
beforeEach(() => {
  saved.length = 0;
  let last: Blob | null = null;
  URL.createObjectURL = vi.fn((b: Blob) => ((last = b), 'blob:mock'));
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    if (this.download && last) saved.push({ blob: last, name: this.download });
  });
});
afterEach(() => vi.restoreAllMocks());

const loanOf = (server: MockServer, firstName: string) => {
  const c = server.db.customers.find((x) => x.input.firstName === firstName)!;
  return server.db.loans.find((l) => l.customerId === c.id)!;
};

describe('dashboard', () => {
  it('shows portfolio tiles, the DPD distribution with count and amount on every bar, approvals and the last EOD', async () => {
    renderApp({ user: 'checker' });
    expect(await screen.findByRole('heading', { name: /Welcome, Charan/ })).toBeInTheDocument();
    const d = await screen.findByTestId('dashboard');
    expect(within(d).getByTestId('tile-portfolio')).toHaveTextContent('₹4,15,483.00');
    expect(within(d).getByTestId('tile-active')).toHaveTextContent('4');
    expect(within(d).getByTestId('tile-disbursed')).toHaveTextContent('₹0.00');
    expect(within(d).getByTestId('tile-disbursed')).toHaveTextContent('month to date ₹75,000.00 (1)');
    expect(within(d).getByTestId('tile-collected')).toHaveTextContent('month to date ₹9,793.00');
    expect(within(d).getByTestId('tile-npa')).toHaveTextContent('9.68%');
    expect(within(d).getByTestId('tile-efficiency')).toHaveTextContent(/%/);

    const chart = within(d).getByRole('list', { name: /by days past due/ });
    const bars = within(chart).getAllByRole('listitem');
    expect(bars).toHaveLength(7);
    expect(bars[0]).toHaveTextContent('Current (0)2 loans · ₹2,38,307.00');
    expect(bars[2]).toHaveTextContent('31–60 days1 loan · ₹1,36,974.00');
    expect(bars[4]).toHaveTextContent('91–180 days1 loan · ₹40,202.00');
    expect(bars[1]).toHaveTextContent('0 loans · ₹0.00');
    expect(within(d).getByTestId('dash-pending')).toHaveTextContent('4');
    expect(within(d).getByText('Last end-of-day')).toBeInTheDocument();
  });

  it('keeps the plain home page for a user without dashboard:view', async () => {
    const server = createMockServer();
    renderApp({ user: 'ops', server });
    expect(await screen.findByRole('heading', { name: /Welcome, Omar/ })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Business date' })).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard')).not.toBeInTheDocument();
    expect((await mockCall(server, 'ops', 'GET', '/api/v1/dashboard')).status).toBe(403);
  });

  it('limits the figures to the caller’s branches', async () => {
    const server = createMockServer();
    server.db.staff.find((s) => s.username === 'maker')!.branches = [];
    const d = (await mockCall(server, 'maker', 'GET', '/api/v1/dashboard')).body;
    expect(d).toMatchObject({ activeLoans: 2, portfolioOutstanding: '238307.00', openEodExceptions: 0 });
  });
});

describe('reports', () => {
  it('builds the run form from the parameter schema', () => {
    const fields = paramFields({ code: 'X', name: 'X', permission: 'report:run', outputFormat: 'CSV', parameters: { type: 'object', properties: { from: { type: 'string', format: 'date', title: 'From' }, productCode: { type: 'string' } }, required: ['from'] } });
    expect(fields).toEqual([
      { key: 'from', def: { type: 'string', format: 'date', title: 'From' }, required: true, label: 'From' },
      { key: 'productCode', def: { type: 'string' }, required: false, label: 'Product code' },
    ]);
  });

  it('runs a report from its generated form and downloads the CSV through a blob', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/reports' });
    expect(await screen.findByRole('heading', { name: 'Reports' })).toBeInTheDocument();
    expect(await screen.findByText('Collections')).toBeInTheDocument();
    // The bureau file needs bureau:export
    expect(screen.queryByText(/Credit bureau file/)).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /all users/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Run Collections' }));
    const d = await screen.findByRole('dialog', { name: 'Run Collections' });
    expect(within(d).getByLabelText(/^From/)).toHaveValue('2026-06-01');
    expect(within(d).getByLabelText(/^To/)).toHaveValue('2026-06-30');
    expect(within(d).getByLabelText(/^Branch/)).toBeInTheDocument();
    expect(within(within(d).getByLabelText(/^Mode/)).getAllByRole('option').map((o) => o.textContent)).toEqual(['All', 'Cash', 'Nach', 'Upi', 'Neft']);
    await user.clear(within(d).getByLabelText(/^From/));
    await user.click(within(d).getByRole('button', { name: 'Run report' }));
    expect(within(d).getByText('From is required')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/^From/), '2026-05-01');
    await user.click(within(d).getByRole('button', { name: 'Run report' }));
    expect(await screen.findByText(/Collections completed: 4 row\(s\)/)).toBeInTheDocument();

    const runs = screen.getByRole('table', { name: 'Report runs' });
    const row = (await within(runs).findByText('from=2026-05-01 to=2026-06-30')).closest('tr')!;
    expect(row).toHaveTextContent('Completed');
    expect(row).toHaveTextContent("4");
    await user.click(within(row).getByRole('button', { name: /^Download collections-/ }));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].name).toBe('collections-2026-06-30.csv');
    const csv = await saved[0].blob.text();
    expect(csv.split('\r\n')[0]).toBe('valueDate,loanNo,customerName,branch,type,mode,amount');
    expect(csv).toContain('Anu CLAUDE-TEST');
  });

  it('warns clearly before the bureau file is run, and only bureau:export sees it', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'admin', route: '/reports' });
    const row = (await screen.findByText('Credit bureau file (consumer)')).closest('tr')!;
    expect(within(row).getByText('Personal data')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /all users/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Run Credit bureau file (consumer)' }));
    const d = await screen.findByRole('dialog', { name: /Run Credit bureau file/ });
    expect(within(d).getByText('This file contains unmasked personal data')).toBeInTheDocument();
    expect(within(d).getByText(/has not been certified by any bureau/)).toBeInTheDocument();
    const run = within(d).getByRole('button', { name: 'Run report' });
    expect(run).toBeDisabled();
    await user.click(within(d).getByRole('checkbox', { name: /I understand/ }));
    await user.click(run);
    expect(await screen.findByText(/completed: 5 row\(s\)/)).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: /Download rejections/ }));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(await saved[0].blob.text()).toContain('Not disbursed');

    // report:admin alone never opens a bureau file; a maker cannot run it; another user's run is not theirs to fetch
    const runId = server.db.reportRuns[0].run.id;
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/reports/BUREAU_CONSUMER/runs', { parameters: { asOf: '2026-06-30' } })).status).toBe(403);
    const dl = await server.fetch(new Request(`http://localhost/api/v1/reports/runs/${runId}/download`, { headers: { Authorization: 'Bearer mock.maker' } }));
    expect(dl.status).toBe(404);
    server.db.systemProperties = server.db.systemProperties.filter((p) => p.key !== 'bureau.member-code');
    expect((await mockCall(server, 'admin', 'POST', '/api/v1/reports/BUREAU_CONSUMER/runs', { parameters: { asOf: '2026-06-30' } })).status).toBe(409);
  });
});

describe('loan documents', () => {
  it('produces a small valid PDF and reads file names safely', () => {
    const text = new TextDecoder().decode(tinyPdf('Title (x)', ['₹ 100', 'line']));
    expect(text.startsWith('%PDF-1.4')).toBe(true);
    expect(text.trimEnd().endsWith('%%EOF')).toBe(true);
    const xref = Number(text.match(/startxref\n(\d+)/)![1]);
    expect(text.slice(xref, xref + 4)).toBe('xref');
    expect(fileNameFromDisposition('attachment; filename="kfs-1001.pdf"', 'x.pdf')).toBe('kfs-1001.pdf');
    expect(fileNameFromDisposition('attachment; filename="../../etc/passwd"', 'x.pdf')).toBe('.._.._etc_passwd');
    expect(fileNameFromDisposition(null, 'x.pdf')).toBe('x.pdf');
  });

  it('downloads the KFS, a statement and a fee invoice as PDFs, and shows the 409 for a NOC on an open loan', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Anu');
    renderApp({ user: 'checker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'Documents' }));
    await user.click(screen.getByRole('button', { name: 'Download Key Facts Statement' }));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].name).toBe(`kfs-${loan.loanNo}.pdf`);
    expect(saved[0].blob.type).toBe('application/pdf');
    expect((await saved[0].blob.text()).startsWith('%PDF-')).toBe(true);
    expect(await screen.findByText(`Saved kfs-${loan.loanNo}.pdf.`)).toBeInTheDocument();

    expect(screen.getByLabelText('Statement from')).toHaveValue('2026-01-15');
    await user.clear(screen.getByLabelText('Statement from'));
    await user.type(screen.getByLabelText('Statement from'), '2026-04-01');
    await user.click(screen.getByRole('button', { name: 'Download statement of account' }));
    await waitFor(() => expect(saved).toHaveLength(2));
    expect(saved[1].name).toBe(`statement-${loan.loanNo}-2026-04-01-2026-06-30.pdf`);

    const invoices = screen.getByRole('table', { name: 'GST invoices by fee' });
    await user.click(await within(invoices).findByRole('button', { name: 'Download GST invoice D1' }));
    await waitFor(() => expect(saved).toHaveLength(3));
    expect(await saved[2].blob.text()).toContain('SAC 9971');

    expect(screen.getByText('Only for closed loans')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Download no-objection certificate' }));
    expect(await screen.findByText('Loan is not closed')).toBeInTheDocument();
    expect(screen.getByText(/issued only for a closed loan/)).toBeInTheDocument();
    expect(saved).toHaveLength(3);
    // The token never travels in a URL
    expect(server.db.audit.filter((a) => a.action === 'DOCUMENT_GENERATED')).toHaveLength(3);
  });

  it('issues the NOC for a closed loan, refuses an invoice for a penal charge and hides loans outside the branch scope', async () => {
    const server = createMockServer();
    const closed = loanOf(server, 'Hari');
    const get = (u: string, path: string) => server.fetch(new Request(`http://localhost${path}`, { headers: { Authorization: `Bearer mock.${u}` } }));
    const noc = await get('maker', `/api/v1/loans/${closed.id}/documents/noc.pdf`);
    expect(noc.status).toBe(200);
    expect(noc.headers.get('Content-Disposition')).toBe(`attachment; filename="noc-${closed.loanNo}.pdf"`);
    expect(noc.headers.get('Cache-Control')).toBe('no-store');
    const npa = loanOf(server, 'Esha');
    expect(npa.state.charges.some((c) => c.id === 'P1')).toBe(true);
    expect((await get('maker', `/api/v1/loans/${npa.id}/charges/P1/invoice.pdf`)).status).toBe(409);
    expect((await get('maker', `/api/v1/loans/${npa.id}/charges/C99/invoice.pdf`)).status).toBe(404);
    const sanctioned = loanOf(server, 'Gauri');
    expect((await get('maker', `/api/v1/loans/${sanctioned.id}/documents/statement.pdf`)).status).toBe(409);
    // ops has no loan:view; the auditor (MUM + SOUTH set = HO) sees both; a HO-only user gets 404 for a MUM loan
    server.db.staff.find((s) => s.username === 'maker')!.branches = [];
    expect((await get('maker', `/api/v1/loans/${npa.id}/documents/kfs.pdf`)).status).toBe(404);
  });
});
