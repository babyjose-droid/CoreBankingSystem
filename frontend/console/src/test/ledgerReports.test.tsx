import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { tbTotals } from '../pages/ledger/TrialBalancePage';
import { renderApp } from './utils';

describe('ledger reports', () => {
  it('trial balance totals and shows a green Balanced banner', async () => {
    renderApp({ user: 'ops', route: '/ledger/trial-balance' });
    expect(await screen.findByText(/Balanced — Σ net = ₹0.00/)).toBeInTheDocument();
    expect(screen.getByTestId('tb-net-total')).toHaveTextContent('₹0.00');
    expect(screen.getByRole('row', { name: /Share capital/ })).toBeInTheDocument();
  });

  it('flags an unbalanced trial balance in red', () => {
    const t = tbTotals([{ glCode: '1', glName: 'x', category: 'ASSET', debit: '10.00', credit: '0.00', net: '10.00' }]);
    expect(t.balanced).toBe(false);
  });

  it('filters by branch and drills down to entries', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'ops', route: '/ledger/trial-balance' });
    await screen.findByText(/Balanced/);
    await user.selectOptions(screen.getByLabelText('Branch'), 'MUM');
    const row = await screen.findByRole('row', { name: /Show entries for 1330/ });
    expect(await screen.findByText(/Balanced/)).toBeInTheDocument();
    await user.click(row);
    const drawer = await screen.findByRole('dialog', { name: /1330 Furniture/ });
    expect(await within(drawer).findByText('Office furniture - Mumbai')).toBeInTheDocument();
  });

  it('chart of accounts is an expandable tree with posting markers', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'auditor', route: '/ledger/accounts' });
    const tree = await screen.findByRole('tree', { name: 'Chart of accounts' });
    expect(within(tree).queryByText('Personal loans - principal')).not.toBeInTheDocument();
    await user.click(within(tree).getByRole('button', { name: 'Expand Loans and advances' }));
    const item = within(tree).getByText('Personal loans - principal').closest('li')!;
    expect(within(item).getByText('Posting')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Propose new head' })).not.toBeInTheDocument();
  });

  it('balance sheet shows section subtotals and balances', async () => {
    renderApp({ user: 'auditor', route: '/ledger/balance-sheet' });
    expect(await screen.findByText('✓ Assets = Liabilities + Equity')).toBeInTheDocument();
    expect(screen.getByTestId('subtotal-EQUITY')).toHaveTextContent('₹5,00,00,000.00');
  });
});
