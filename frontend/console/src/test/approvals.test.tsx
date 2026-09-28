import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { formatAge, slaClass } from '../pages/ApprovalsPage';
import { renderApp } from './utils';

describe('approvals queue', () => {
  it('counts only requests the user can act on in the header badge', async () => {
    const { unmount } = renderApp({ user: 'checker' });
    await waitFor(() => expect(screen.getByTestId('approvals-badge')).toHaveTextContent('4'));
    unmount();
    renderApp({ user: 'maker' });
    await screen.findByText(/Welcome, Meera/);
    await waitFor(() => expect(screen.getByTestId('approvals-badge')).toHaveTextContent('0'));
  });

  it('colours ageing by SLA', () => {
    expect(slaClass({ status: 'PENDING', ageHours: 52 })).toBe('sla-red');
    expect(slaClass({ status: 'PENDING', ageHours: 30 })).toBe('sla-amber');
    expect(slaClass({ status: 'PENDING', ageHours: 3 })).toBeUndefined();
    expect(slaClass({ status: 'APPROVED', ageHours: 99 })).toBeUndefined();
    expect(formatAge(52)).toBe('2d 4h');
  });

  it("opens another maker's request in a keyboard-closable drawer with Approve enabled", async () => {
    const user = userEvent.setup();
    renderApp({ user: 'admin', route: '/approvals' });
    await screen.findByRole('heading', { name: 'Approvals' });
    const rows = await screen.findAllByRole('row', { name: /request by maker/ });
    expect(rows.length).toBe(4);
    await user.click(rows[0]);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('button', { name: 'Approve' })).toBeEnabled();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('maker sees Approve disabled on own request; checker approves it and the badge updates', async () => {
    const user = userEvent.setup();
    const first = renderApp({ user: 'admin', route: '/masters/tax-rates' });
    await user.click(await screen.findByRole('button', { name: 'Propose rate' }));
    const dlg = await screen.findByRole('dialog', { name: 'Propose tax rate' });
    await user.type(within(dlg).getByLabelText(/Code/), 'GST5');
    await user.type(within(dlg).getByLabelText(/Rate %/), '5');
    await user.click(within(dlg).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Tax rate sent for approval.')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'View request' }));

    const drawer = await screen.findByRole('dialog', { name: /Tax rate · Create/ });
    expect(await within(drawer).findByRole('button', { name: 'Approve' })).toBeDisabled();
    expect(within(drawer).getByText(/Maker-checker requires a different user/)).toBeInTheDocument();
    const server = first.server;
    first.unmount();

    renderApp({ user: 'checker', route: '/approvals', server });
    await waitFor(() => expect(screen.getByTestId('approvals-badge')).toHaveTextContent('5'));
    await user.click(await screen.findByRole('row', { name: /Tax rate Create request by admin/ }));
    const d2 = await screen.findByRole('dialog');
    await user.click(await within(d2).findByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('Approved and applied.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('approvals-badge')).toHaveTextContent('4'));
  });

  it('requires a note to reject and highlights changed fields in the diff', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'checker', route: '/approvals?status=' });
    await user.click(await screen.findByRole('row', { name: /Branch Update request by maker/ }));
    const d = await screen.findByRole('dialog');
    const changed = within(d)
      .getAllByRole('row')
      .filter((r) => r.getAttribute('data-changed'));
    expect(changed.map((r) => within(r).getByRole('rowheader').textContent)).toEqual(['State code (changed)']);
    await user.keyboard('{Escape}');

    await user.click(await screen.findByRole('row', { name: /Branch Create request by maker/ }));
    const d2 = await screen.findByRole('dialog');
    await user.click(await within(d2).findByRole('button', { name: 'Reject' }));
    expect(within(d2).getByRole('alert')).toHaveTextContent('A note is required to reject');
    await user.type(within(d2).getByLabelText(/Note/), 'Duplicate of existing TVM proposal');
    await user.click(within(d2).getByRole('button', { name: 'Reject' }));
    expect(await screen.findByText('Request rejected.')).toBeInTheDocument();
  });

  it('bulk-approves selected requests', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'checker', route: '/approvals' });
    await screen.findAllByRole('row', { name: /request by maker/ });
    await user.click(screen.getByRole('checkbox', { name: 'Select all' }));
    await user.click(screen.getByRole('button', { name: 'Approve selected (4)' }));
    expect(await screen.findByText('Approved 4 of 4.')).toBeInTheDocument();
    expect(await screen.findByText('No approval requests match these filters')).toBeInTheDocument();
  });
});
