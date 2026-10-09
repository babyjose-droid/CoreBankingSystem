import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderApp } from './utils';

const navLinks = async () => {
  const nav = await screen.findByRole('navigation', { name: 'Main' });
  return within(nav)
    .getAllByRole('link')
    .map((a) => a.textContent?.replace(/\d+$/, '').trim());
};

describe('permission-aware navigation', () => {
  it('ops sees only Home, Loans, Ledger and Day-end', async () => {
    renderApp({ user: 'ops' });
    expect(await navLinks()).toEqual(['Home', 'Loans', 'Deferred receipts', 'Rate resets', 'Chart of accounts', 'Vouchers', 'Trial balance', 'P&L', 'Balance sheet', 'Runs', 'Schedule', 'Payouts', 'Collections', 'Mandates', 'NACH files', 'Jobs']);
    expect(screen.queryByTestId('approvals-badge')).not.toBeInTheDocument();
  });

  it('auditor sees Audit but maker does not', async () => {
    const { unmount } = renderApp({ user: 'auditor' });
    expect(await navLinks()).toContain('Audit');
    unmount();
    renderApp({ user: 'maker' });
    const links = await navLinks();
    expect(links).not.toContain('Audit');
    expect(links).toEqual(expect.arrayContaining(['Approvals', 'Customers', 'Loans', 'Products', 'Branches', 'Holidays', 'Tax rates', 'Staff', 'Branch sets', 'Territory', 'System properties', 'Enumerations', 'Amount limits', 'Reports']));
  });

  it('shows a clear 403 page on direct URL access without permission', async () => {
    renderApp({ user: 'ops', route: '/customers' });
    expect(await screen.findByRole('heading', { name: /403/ })).toBeInTheDocument();
    expect(screen.getByText('customer:view')).toBeInTheDocument();
  });

  it('hides action buttons the user lacks (checker cannot create a customer)', async () => {
    renderApp({ user: 'checker', route: '/customers' });
    expect(await screen.findByRole('heading', { name: 'Customers' })).toBeInTheDocument();
    expect(await screen.findByText('Anu CLAUDE-TEST')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New customer' })).not.toBeInTheDocument();
  });

  it('redirects to login when signed out and signs in as a demo user', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/' });
    await user.click(await screen.findByRole('button', { name: /Anita Auditor/ }));
    expect(await screen.findByText(/Welcome, Anita/)).toBeInTheDocument();
    expect(screen.getByTestId('business-date')).toHaveTextContent('30 Jun 2026');
    expect(screen.getByTestId('business-date')).toHaveAttribute('title', 'Business date changes only through end-of-day');
    expect(screen.getByTestId('tenant-name')).toHaveTextContent('Demo NBFC Ltd');
  });
});
