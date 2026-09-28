import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderApp } from './utils';

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
