import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { lineTotals } from '../pages/ledger/VoucherFormPage';
import { renderApp } from './utils';

describe('voucher form', () => {
  it('sums DR/CR exactly', () => {
    expect(lineTotals([{ side: 'DR', amount: '0.10' }, { side: 'DR', amount: '0.20' }, { side: 'CR', amount: '0.30' }, { side: 'CR', amount: 'x' }])).toEqual({ dr: 3000n, cr: 3000n });
  });

  it('shows running totals and blocks submit until balanced, then sends for approval', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/ledger/vouchers/new' });
    const submit = await screen.findByRole('button', { name: 'Submit for approval' });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/Description/), 'CLAUDE-TEST journal');
    // wait for heads to load
    await within(screen.getByLabelText('Line 1 GL head')).findByRole('option', { name: /1210 — Interest receivable/, hidden: true });
    await user.selectOptions(screen.getByLabelText('Line 1 GL head'), '1210');
    await user.type(screen.getByLabelText('Line 1 amount'), '100000');
    await user.selectOptions(screen.getByLabelText('Line 2 GL head'), '4101');
    await user.type(screen.getByLabelText('Line 2 amount'), '99999.5');

    expect(screen.getByTestId('total-dr')).toHaveTextContent('₹1,00,000.00');
    expect(screen.getByTestId('total-cr')).toHaveTextContent('₹99,999.50');
    expect(screen.getByText('Out of balance by ₹0.50')).toBeInTheDocument();
    expect(submit).toBeDisabled();

    // Add a third line to balance
    await user.click(screen.getByRole('button', { name: 'Add line' }));
    const line3 = screen.getByRole('group', { name: 'Line 3' });
    expect(within(line3).getByLabelText('Line 3 side')).toHaveValue('CR');
    await user.selectOptions(within(line3).getByLabelText('Line 3 GL head'), '4202');
    await user.type(within(line3).getByLabelText('Line 3 amount'), '0.50');
    expect(screen.getByText('Balanced')).toBeInTheDocument();
    expect(submit).toBeEnabled();

    await user.click(submit);
    expect(await screen.findByText('Voucher sent for approval.')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Vouchers' })).toBeInTheDocument();
  });

  it('offers only posting heads', async () => {
    renderApp({ user: 'maker', route: '/ledger/vouchers/new' });
    const select = await screen.findByLabelText('Line 1 GL head');
    await within(select).findByRole('option', { name: /1201 — Personal loans/, hidden: true });
    const values = within(select).getAllByRole('option', { hidden: true }).map((o) => (o as HTMLOptionElement).value);
    expect(values).toContain('1201');
    expect(values).not.toContain('1200');
    expect(values).not.toContain('1');
  });
});
