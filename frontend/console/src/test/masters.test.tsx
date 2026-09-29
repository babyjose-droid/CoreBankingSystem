import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { toCsv } from '../lib/csv';
import { createMockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

const csvFile = (name: string, text: string) => new File([text], name, { type: 'text/csv' });

describe('staff and branch scope', () => {
  it('lists staff with scope and proposes a new profile within the maker’s branches', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/masters/staff' });
    expect(await screen.findByRole('heading', { name: 'Staff' })).toBeInTheDocument();
    const checker = (await screen.findByText('Charan Checker')).closest('tr')!;
    expect(within(checker).getByText('All branches')).toBeInTheDocument();
    const auditor = screen.getByText('Anita Auditor').closest('tr')!;
    expect(auditor).toHaveTextContent('sets SOUTH');

    await user.click(screen.getByRole('button', { name: 'New staff profile' }));
    const d = await screen.findByRole('dialog', { name: 'New staff profile' });
    // The maker sees HO and MUM only, so cannot grant all branches.
    expect(within(d).getByRole('checkbox', { name: /All branches/ })).toBeDisabled();
    await user.type(within(d).getByLabelText(/^Username/), 'claude.test');
    await user.type(within(d).getByLabelText(/^Display name/), 'CLAUDE-TEST Teller');
    await user.selectOptions(within(d).getByLabelText(/^Home branch/), 'MUM');
    await user.click(within(d).getByRole('checkbox', { name: /HO — Head Office/ }));
    await user.click(within(d).getByRole('checkbox', { name: /WEST — West zone/ }));
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('New staff profile sent for approval.')).toBeInTheDocument();

    const pending = server.db.approvals.find((s) => s.approval.entityType === 'STAFF')!;
    expect(pending.approval.proposed).toMatchObject({ username: 'claude.test', homeBranch: 'MUM', branches: ['HO'], branchSets: ['WEST'], allBranches: false });
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${pending.approval.id}/approve`, {});
    const staff = await mockCall(server, 'admin', 'GET', '/api/v1/staff');
    expect(staff.body.find((s: { username: string }) => s.username === 'claude.test')).toMatchObject({ displayName: 'CLAUDE-TEST Teller', status: 'ACTIVE' });
  });

  it('refuses to let a maker grant branches outside their own scope', async () => {
    const server = createMockServer();
    server.db.staff.find((s) => s.username === 'maker')!.branches = [];
    const res = await mockCall(server, 'maker', 'POST', '/api/v1/staff', { username: 'x.y', displayName: 'X', homeBranch: 'MUM' });
    expect(res.status).toBe(403);
    expect(res.body.detail).toMatch(/MUM/);
  });

  it('shows the user’s branch scope in the user menu', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker' });
    await screen.findByText(/Welcome, Meera/);
    expect(screen.getByText(/sees HO \(home\), MUM/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /User menu/ }));
    expect(screen.getByTestId('branch-scope')).toHaveTextContent('Branch scope: HO (home), MUM');
  });

  it('proposes a branch set', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/masters/branch-sets' });
    await user.click(await screen.findByRole('button', { name: 'New branch set' }));
    const d = await screen.findByRole('dialog', { name: 'New branch set' });
    await user.type(within(d).getByLabelText(/^Code/), 'ALL_IN');
    await user.type(within(d).getByLabelText(/^Name/), 'CLAUDE-TEST all');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('Select at least one branch')).toBeInTheDocument();
    await user.click(within(d).getByRole('checkbox', { name: /HO — / }));
    await user.click(within(d).getByRole('checkbox', { name: /MUM — / }));
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('New branch set sent for approval.')).toBeInTheDocument();
  });
});

describe('system properties and enumerations', () => {
  it('edits a GL property through maker-checker', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/masters/system-properties' });
    await user.click(await screen.findByRole('button', { name: 'Edit lending.disbursement-gl' }));
    const d = await screen.findByRole('dialog', { name: 'Edit lending.disbursement-gl' });
    const select = within(d).getByLabelText(/^Value \(GL head\)/);
    await within(select).findByRole('option', { name: /1111 — Bank - current account \(SBI\)/, hidden: true });
    await user.selectOptions(select, '1111');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Property change sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((s) => s.approval.entityType === 'SYSTEM_PROPERTY')!;
    expect(a.approval).toMatchObject({ action: 'UPDATE', current: { value: '1110' }, proposed: { value: '1111' } });
    // A non-GL value for a -gl key is refused by the server
    const bad = await mockCall(server, 'admin', 'PUT', '/api/v1/system-properties/lending.penal-income-gl', { value: '4200' });
    expect(bad.status).toBe(422);
  });

  it('deactivates and adds enumeration values (never deletes)', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/masters/enumerations' });
    await user.click(await screen.findByRole('row', { name: 'Open enumeration LOAN_PURPOSE' }));
    expect(await screen.findByRole('heading', { name: 'Enumeration LOAN_PURPOSE' })).toBeInTheDocument();
    const submit = screen.getByRole('button', { name: 'Submit for approval' });
    expect(submit).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Active: TRAVEL' }));
    await user.click(screen.getByRole('button', { name: 'Add value' }));
    await user.type(screen.getByLabelText('Code of new value 6'), 'wedding');
    await user.type(screen.getByLabelText('Label of new value 6'), 'Wedding');
    expect(screen.getByText('2 value(s) changed or added')).toBeInTheDocument();
    await user.click(submit);
    expect(await screen.findByText('2 value change(s) to LOAN_PURPOSE sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((s) => s.approval.entityType === 'ENUMERATION')!;
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
    const values = (await mockCall(server, 'maker', 'GET', '/api/v1/enumerations/LOAN_PURPOSE')).body as Array<{ code: string; active: boolean }>;
    expect(values).toHaveLength(6);
    expect(values.find((v) => v.code === 'TRAVEL')).toMatchObject({ active: false });
    expect(values.find((v) => v.code === 'WEDDING')).toMatchObject({ active: true });
  });
});

describe('territory', () => {
  it('looks up a pincode and reports unknown ones', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'checker', route: '/masters/territory' });
    expect(await screen.findByText('Kerala')).toBeInTheDocument();
    expect(screen.queryByLabelText('Territory file')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Pincode'), '68200');
    await user.click(screen.getByRole('button', { name: 'Look up' }));
    expect(screen.getByText('6 digits, not starting with 0')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Pincode'), '1');
    await user.click(screen.getByRole('button', { name: 'Look up' }));
    const places = await screen.findByRole('table', { name: 'Places served by 682001' });
    expect(within(places).getByText('Kochi (Fort Kochi)')).toBeInTheDocument();
    expect(within(places).getByText('Mattancherry')).toBeInTheDocument();
    expect(within(places).getAllByText('Kerala (KL)')).toHaveLength(2);
    await user.clear(screen.getByLabelText('Pincode'));
    await user.type(screen.getByLabelText('Pincode'), '999999');
    await user.click(screen.getByRole('button', { name: 'Look up' }));
    expect(await screen.findByText(/Pincode 999999 not found/)).toBeInTheDocument();
  });

  it('uploads a territory file (row count first) and the approval loads it', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/masters/territory' });
    const input = await screen.findByLabelText('Territory file');
    const text = toCsv(['state', 'district', 'city', 'pincode'], [['KL', 'Ernakulam', 'Aluva', '683101'], ['Karnataka', 'Mysuru', 'Mysuru', '570001'], ['29', 'Mysuru', 'Nazarbad', '570010']]);
    await user.upload(input, csvFile('territory.csv', text));
    expect(await screen.findByTestId('csv-row-count')).toHaveTextContent('3 data rows in territory.csv');
    await user.click(screen.getByRole('button', { name: 'Upload 3 rows' }));
    expect(await screen.findByText('Territory file (3 rows) sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((s) => s.approval.entityType === 'TERRITORY')!;
    expect(a.approval.proposed).toMatchObject({ rows: 3, pincodes: 3, states: 'KL, KA' });
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/pincodes/570010')).body).toEqual([
      { pincode: '570010', city: 'Nazarbad', district: 'Mysuru', stateCode: 'KA', stateName: 'Karnataka', gstStateCode: '29' },
    ]);
  });

  it('shows per-row problems (422) and the row limit (413) for a bad territory file', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/masters/territory' });
    const input = await screen.findByLabelText('Territory file');
    await user.upload(input, csvFile('bad.csv', toCsv(['state', 'district', 'city', 'pincode'], [['Atlantis', 'X', 'Y', '012345'], ['KL', '', 'Aluva', '683101']])));
    await user.click(await screen.findByRole('button', { name: 'Upload 2 rows' }));
    expect(await screen.findByText(/3 problem\(s\) in the file; nothing was loaded/)).toBeInTheDocument();
    const list = screen.getByRole('list', { name: 'Problems in the file' });
    expect(within(list).getByText('Row 2: unknown state "Atlantis"')).toBeInTheDocument();
    expect(within(list).getByText(/Row 2: pincode "012345"/)).toBeInTheDocument();
    expect(within(list).getByText('Row 3: district is required')).toBeInTheDocument();

    const rows = Array.from({ length: 20_001 }, (_, i) => ['KL', 'Ernakulam', `Town ${i}`, String(680000 + (i % 9999))]);
    await user.upload(input, csvFile('huge.csv', toCsv(['state', 'district', 'city', 'pincode'], rows)));
    expect(await screen.findByText(/More than 20,000 rows/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Upload 20001 rows' }));
    expect(await screen.findByText('File too large')).toBeInTheDocument();
    expect(screen.getByText(/at most 20,000 are allowed per upload/)).toBeInTheDocument();
  });

  it('rejects a file with the wrong header before sending anything useful', async () => {
    const server = createMockServer();
    const res = await server.fetch(
      new Request('http://localhost/api/v1/territory/upload', { method: 'POST', headers: { Authorization: 'Bearer mock.maker', 'Content-Type': 'text/csv' }, body: 'state,city\nKL,Kochi\n' }),
    );
    expect(res.status).toBe(422);
    expect((await res.json()).detail).toMatch(/missing column\(s\): district, pincode/);
    const big = await server.fetch(
      new Request('http://localhost/api/v1/holidays/upload', { method: 'POST', headers: { Authorization: 'Bearer mock.maker', 'Content-Type': 'text/csv' }, body: 'x'.repeat(5_000_001) }),
    );
    expect(big.status).toBe(413);
  });
});

describe('CSV uploads on existing pages', () => {
  it('uploads holidays as one approval', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/masters/holidays' });
    await user.click(await screen.findByRole('button', { name: 'Upload file' }));
    const d = await screen.findByRole('dialog', { name: 'Upload holidays' });
    expect(within(d).getByRole('button', { name: 'Download template' })).toBeInTheDocument();
    await user.upload(within(d).getByLabelText('Holiday file'), csvFile('h.csv', 'day,reason,branchCode\r\n2026-11-01,CLAUDE-TEST Kerala Piravi,HO\r\n2026-11-14,"CLAUDE-TEST Children\'s Day, all",\r\n'));
    await user.click(await within(d).findByRole('button', { name: 'Upload 2 rows' }));
    expect(await screen.findByText('2 holiday(s) sent for approval.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const a = server.db.approvals.find((s) => s.approval.entityType === 'HOLIDAY')!;
    expect(a.payload).toMatchObject({ kind: 'HOLIDAY', holidays: [{ day: '2026-11-01', branchCode: 'HO' }, { day: '2026-11-14', reason: "CLAUDE-TEST Children's Day, all", branchCode: null }] });
  });

  it('shows the problem for a holiday file with past dates', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/masters/holidays' });
    await user.click(await screen.findByRole('button', { name: 'Upload file' }));
    const d = await screen.findByRole('dialog', { name: 'Upload holidays' });
    await user.upload(within(d).getByLabelText('Holiday file'), csvFile('h.csv', 'day,reason\n2026-01-01,CLAUDE-TEST past\n'));
    await user.click(await within(d).findByRole('button', { name: 'Upload 1 row' }));
    expect(await within(d).findByText(/Row 2: holidays must be after the business date/)).toBeInTheDocument();
  });

  it('uploads vouchers: one approval per voucher, all or nothing', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/ledger/vouchers' });
    await user.click(await screen.findByRole('button', { name: 'Upload file' }));
    const d = await screen.findByRole('dialog', { name: 'Upload vouchers' });
    const header = ['voucherRef', 'voucherType', 'valueDate', 'description', 'branch', 'glCode', 'side', 'amount'];
    const unbalanced = toCsv(header, [
      ['A', 'JOURNAL', '2026-06-30', 'CLAUDE-TEST accrual', 'HO', '1210', 'DR', '100.00'],
      ['A', 'JOURNAL', '2026-06-30', 'CLAUDE-TEST accrual', 'HO', '4101', 'CR', '90.00'],
    ]);
    await user.upload(within(d).getByLabelText('Voucher file'), csvFile('v.csv', unbalanced));
    await user.click(await within(d).findByRole('button', { name: 'Upload 2 rows' }));
    expect(await within(d).findByText(/Voucher A \(from row 2\): Debits ₹100.00 must equal credits ₹90.00/)).toBeInTheDocument();
    const before = server.db.approvals.length;

    const good = toCsv(header, [
      ['A', 'JOURNAL', '2026-06-30', 'CLAUDE-TEST accrual', 'HO', '1210', 'DR', '100.00'],
      ['A', 'JOURNAL', '2026-06-30', 'CLAUDE-TEST accrual', 'HO', '4101', 'CR', '100.00'],
      ['B', 'CONTRA', '2026-06-30', 'CLAUDE-TEST petty cash', 'HO', '1101', 'DR', '500'],
      ['B', 'CONTRA', '2026-06-30', 'CLAUDE-TEST petty cash', 'HO', '1110', 'CR', '500'],
    ]);
    await user.upload(within(d).getByLabelText('Voucher file'), csvFile('v2.csv', good));
    await user.click(await within(d).findByRole('button', { name: 'Upload 4 rows' }));
    expect(await screen.findByText('2 voucher(s) sent for approval.')).toBeInTheDocument();
    expect(server.db.approvals.length).toBe(before + 2);
  });
});
