import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

describe('end-of-day UI', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts EOD after confirmation, polls the run and advances the business date', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { server } = renderApp({ user: 'ops', route: '/eod/runs' });
    await user.click(await screen.findByRole('button', { name: 'Start EOD' }));
    const dialog = await screen.findByRole('dialog', { name: 'Start end-of-day?' });
    await user.click(within(dialog).getByRole('button', { name: /Start EOD for/ }));

    expect(await screen.findByRole('heading', { name: 'EOD run #104' })).toBeInTheDocument();
    expect(screen.getByTestId('run-status')).toHaveTextContent('Running');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(400 * 3 + 1100);
    });
    await waitFor(() => expect(within(screen.getByTestId('step-3')).getByText('Completed with exceptions')).toBeInTheDocument());

    await act(async () => {
      await vi.advanceTimersByTimeAsync(400 * 7 + 2200);
    });
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Completed with exceptions'));
    expect(screen.getByRole('progressbar', { name: 'EOD progress' })).toHaveAttribute('aria-valuenow', '9');
    expect(screen.getByText('DPD not computable: repayment schedule missing')).toBeInTheDocument();
    expect(screen.getByText(/Exceptions \(2\)/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('business-date')).toHaveTextContent('01 Jul 2026'));
    expect(server.db.businessDate).toBe('2026-07-01');
  });

  it('warns about pending dated approvals before and during the run; a tenant may make them block the start', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { server, unmount } = renderApp({ user: 'ops', route: '/eod/runs' });
    const pending = (await mockCall(server, 'ops', 'GET', '/api/v1/eod/pending-approvals')).body as { total: number; message: string; blocking: boolean };
    expect(pending.total).toBeGreaterThan(0);
    expect(pending.blocking).toBe(false);
    expect(pending.message).toMatch(/pending: .*1 voucher.* — they stay pending and will apply on the business date on which they are approved/);
    await user.click(await screen.findByRole('button', { name: 'Start EOD' }));
    const dialog = await screen.findByRole('dialog', { name: 'Start end-of-day?' });
    expect(await within(dialog).findByTestId('eod-pending-approvals')).toHaveTextContent(pending.message);
    expect(within(dialog).getByRole('link', { name: 'Review approvals' })).toHaveAttribute('href', '/approvals?status=PENDING');
    await user.click(within(dialog).getByRole('button', { name: /Start EOD for/ }));
    expect(await screen.findByTestId('eod-warning-PENDING_APPROVALS')).toHaveTextContent(pending.message);
    unmount();

    // with eod.block-on-pending-approvals the start is refused, naming the count
    const s2 = createMockServer();
    s2.db.systemProperties.push({ key: 'eod.block-on-pending-approvals', value: 'true', description: null, updatedBy: 'CLAUDE-TEST', updatedAt: '2026-06-30T00:00:00Z' });
    const refused = await mockCall(s2, 'ops', 'POST', '/api/v1/eod/runs', {});
    expect(refused.status).toBe(409);
    expect(refused.body.detail).toMatch(/^\d+ approvals? (is|are) pending: .* end of day cannot start/);
    renderApp({ user: 'ops', route: '/eod/runs', server: s2 });
    await user.click(await screen.findByRole('button', { name: 'Start EOD' }));
    const d2 = await screen.findByRole('dialog', { name: 'Start end-of-day?' });
    await within(d2).findByTestId('eod-pending-approvals');
    expect(within(d2).getByRole('button', { name: /Start EOD for/ })).toBeDisabled();
  });

  it('offers Restart for a failed run', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { server } = renderApp({ user: 'ops', route: '/eod/runs' });
    server.db.eodFailAtStep = 'Pre-checks';
    await user.click(await screen.findByRole('button', { name: 'Start EOD' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /Start EOD for/ }));
    await screen.findByRole('heading', { name: 'EOD run #104' });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1500);
    });
    const restart = await screen.findByRole('button', { name: 'Restart from failed step' });
    await user.click(restart);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400 * 10 + 2200);
    });
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Completed with exceptions'));
  });
});
