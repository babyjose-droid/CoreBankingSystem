import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderApp } from './utils';

const ROUTES: Array<[string, RegExp]> = [
  ['/', /Welcome, Arjun/],
  ['/approvals', /^Approvals$/],
  ['/customers', /^Customers$/],
  ['/customers/new', /^New customer$/],
  ['/ledger/accounts', /^Chart of accounts$/],
  ['/ledger/vouchers', /^Vouchers$/],
  ['/ledger/vouchers/new', /^New voucher$/],
  ['/ledger/trial-balance', /^Trial balance$/],
  ['/ledger/profit-and-loss', /^Profit and loss$/],
  ['/ledger/balance-sheet', /^Balance sheet$/],
  ['/eod/runs', /^End-of-day runs$/],
  ['/eod/runs/102', /^EOD run #102$/],
  ['/eod/schedule', /^EOD schedule$/],
  ['/masters/branches', /^Branches$/],
  ['/masters/holidays', /^Holidays$/],
  ['/masters/tax-rates', /^Tax rates$/],
  ['/loans', /^Loans$/],
  ['/loans/new', /^New loan$/],
  ['/loan-products', /^Loan products$/],
  ['/loan-products/new', /^New loan product$/],
  ['/loan-products/PL01', /^Personal Loan$/],
  ['/loan-products/PL01/edit', /^Change product PL01$/],
  ['/masters/staff', /^Staff$/],
  ['/masters/branch-sets', /^Branch sets$/],
  ['/masters/territory', /^Territory$/],
  ['/masters/system-properties', /^System properties$/],
  ['/masters/enumerations', /^Enumerations$/],
  ['/masters/enumerations/LOAN_PURPOSE', /^Enumeration LOAN_PURPOSE$/],
  ['/audit', /^Audit trail$/],
  ['/no-such-page', /^Page not found$/],
];

describe('every route renders for admin', () => {
  it.each(ROUTES)('%s', async (route, heading) => {
    renderApp({ user: 'admin', route });
    expect(await screen.findByRole('heading', { name: heading, level: route === '/no-such-page' ? 2 : 1 })).toBeInTheDocument();
    expect(document.querySelector('.banner--danger')).toBeNull();
  });
});

describe('masters and audit flows', () => {
  it('adds several holidays in one proposal', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/masters/holidays' });
    await user.click(await screen.findByRole('button', { name: 'Add holidays' }));
    const d = await screen.findByRole('dialog', { name: 'Add holidays' });
    await user.type(within(d).getByLabelText('Date 1'), '2026-09-15');
    await user.type(within(d).getByLabelText('Reason 1'), 'CLAUDE-TEST one');
    await user.click(within(d).getByRole('button', { name: 'Add another' }));
    await user.type(within(d).getByLabelText('Date 2'), '2026-09-16');
    await user.type(within(d).getByLabelText('Reason 2'), 'CLAUDE-TEST two');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('2 holiday(s) sent for approval.')).toBeInTheDocument();
  });

  it('verifies the audit chain', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'auditor', route: '/audit' });
    await screen.findByText('TENANT_PROVISIONED');
    await user.click(screen.getByRole('button', { name: 'Verify chain' }));
    expect(await screen.findByText(/Audit chain intact/)).toBeInTheDocument();
  });

  it('keyboard: Escape closes a dialog and returns focus to the trigger', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/masters/branches' });
    const trigger = await screen.findByRole('button', { name: 'New branch' });
    await user.click(trigger);
    expect(await screen.findByRole('dialog', { name: 'New branch' })).toBeInTheDocument();
    expect(screen.getByLabelText(/Code/)).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});
