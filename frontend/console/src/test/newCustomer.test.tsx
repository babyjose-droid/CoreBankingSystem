import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { validateBasics } from '../pages/customers/validation';
import { renderApp } from './utils';

const base = { customerType: 'INDIVIDUAL' as const, homeBranch: 'HO', firstName: 'Test', lastName: 'CLAUDE-TEST', dateOfBirth: '1990-01-01', mobile: '9876543210', pan: 'ABCDE1234F' };

describe('new customer — step 1 validation', () => {
  it('accepts valid basics', () => {
    expect(validateBasics(base, '2026-06-30')).toEqual({});
  });
  it('validates PAN and mobile patterns and minimum age 18', () => {
    const e = validateBasics({ ...base, pan: 'ABCD1234F', mobile: '5876543210', dateOfBirth: '2008-07-01' }, '2026-06-30');
    expect(Object.keys(e).sort()).toEqual(['dateOfBirth', 'mobile', 'pan']);
    expect(validateBasics({ ...base, dateOfBirth: '2008-06-30' }, '2026-06-30')).toEqual({});
    expect(validateBasics({ ...base, customerType: 'NON_INDIVIDUAL', dateOfBirth: '2020-01-01' }, '2026-06-30')).toEqual({});
  });

  it('shows inline errors and does not call dedupe when invalid', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/customers/new' });
    await user.type(await screen.findByLabelText(/First name/), 'Young');
    await user.type(screen.getByLabelText(/Date of birth/), '2012-01-01');
    await user.type(screen.getByLabelText(/Mobile/), '12345');
    await user.type(screen.getByLabelText(/^PAN/), 'bad');
    await user.click(screen.getByRole('button', { name: 'Check duplicates' }));
    expect(await screen.findByText('Customer must be at least 18 years old')).toBeInTheDocument();
    expect(screen.getByText(/Enter a 10-digit mobile/)).toBeInTheDocument();
    expect(screen.getByText(/PAN must be 5 letters/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Mobile/)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
  });

  it('shows dedupe matches with rule/strength, requires override, then submits for approval', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'maker', route: '/customers/new' });
    await screen.findByRole('option', { name: /HO — Head Office/, hidden: true });
    await user.type(screen.getByLabelText(/First name/), 'Newperson');
    await user.type(screen.getByLabelText(/Last name/), 'CLAUDE-TEST');
    await user.type(screen.getByLabelText(/Date of birth/), '1990-03-03');
    await user.type(screen.getByLabelText(/Mobile/), '9000000004'); // same mobile as a seeded customer
    await user.type(screen.getByLabelText(/^PAN/), 'nnnpz4444n');
    await user.click(screen.getByRole('button', { name: 'Check duplicates' }));

    const table = await screen.findByRole('table', { name: 'Possible duplicate customers' });
    expect(within(table).getByText('Deepak CLAUDE-TEST')).toBeInTheDocument();
    expect(within(table).getByText('Mobile')).toBeInTheDocument();
    expect(within(table).getByText('Strong')).toBeInTheDocument();
    const cont = screen.getByRole('button', { name: 'Continue' });
    expect(cont).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: /different people/ }));
    await user.type(screen.getByLabelText(/Override reason/), 'Relatives sharing a phone');
    expect(cont).toBeEnabled();
    await user.click(cont);

    await user.click(screen.getByRole('tab', { name: 'Contact / address' }));
    await user.type(screen.getByLabelText(/PIN code/), '012345');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText(/PIN code must be 6 digits/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText(/PIN code/));
    await user.type(screen.getByLabelText(/PIN code/), '682001');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('New customer sent for approval.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View request' })).toHaveAttribute('href', expect.stringMatching(/^\/approvals\?id=/));
  });
});
