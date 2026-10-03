import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

describe('benchmark rates', () => {
  it('lists benchmarks with the rate in force and their history', async () => {
    renderApp({ user: 'checker', route: '/masters/benchmarks' });
    expect(await screen.findByRole('heading', { name: 'Benchmark rates' })).toBeInTheDocument();
    const repo = await screen.findByTestId('benchmark-REPO');
    expect(repo).toHaveTextContent('Current rate: 6.5%');
    expect(repo).toHaveTextContent('External benchmark');
    const history = screen.getByRole('table', { name: 'Rate history of REPO' });
    expect(within(history).getAllByRole('row')).toHaveLength(3);
    expect(within(history).getByText('Current')).toBeInTheDocument();
    expect(within(history).getByText('Superseded')).toBeInTheDocument();
    // a benchmark without a rate says what that means
    expect(screen.getByTestId('benchmark-LENDER_PLR')).toHaveTextContent('No rate in force');
    // the checker cannot propose
    expect(screen.queryByRole('button', { name: 'Propose benchmark' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Propose rate for REPO' })).not.toBeInTheDocument();
  });

  it('proposes a rate; it is in force only after approval and the history is not rewritten', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const first = renderApp({ user: 'maker', route: '/masters/benchmarks', server });
    await user.click(await screen.findByRole('button', { name: 'Propose rate for REPO' }));
    const d = await screen.findByRole('dialog', { name: 'Propose rate for REPO' });
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText(/Enter a rate between 0 and 100/)).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/^Rate/), '6.25');
    expect(within(d).getByLabelText(/^Effective from/)).toHaveValue('2026-06-30');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Benchmark rate sent for approval.')).toBeInTheDocument();
    expect(screen.getByTestId('benchmark-REPO')).toHaveTextContent('Current rate: 6.5%');

    const a = server.db.approvals.find((s) => s.approval.entityType === 'BENCHMARK_RATE')!;
    expect(a.approval.proposed).toMatchObject({ code: 'REPO', rate: '6.25', effectiveFrom: '2026-06-30' });
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).status).toBe(403);
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).status).toBe(200);
    first.unmount();

    renderApp({ user: 'maker', route: '/masters/benchmarks', server });
    expect(await screen.findByTestId('benchmark-REPO')).toHaveTextContent('Current rate: 6.25%');
    expect(within(screen.getByRole('table', { name: 'Rate history of REPO' })).getAllByRole('row')).toHaveLength(4);
    // not on or before the last recorded date
    const again = await mockCall(server, 'maker', 'POST', '/api/v1/benchmarks/REPO/rates', { rate: '6', effectiveFrom: '2026-06-30' });
    expect(again.status).toBe(409);
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/benchmarks/NOPE/rates', { rate: '6', effectiveFrom: '2026-07-01' })).status).toBe(404);
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/benchmarks/REPO/rates', { rate: '101', effectiveFrom: '2026-07-01' })).status).toBe(422);
  });

  it('proposes a new benchmark, which appears after approval', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const first = renderApp({ user: 'maker', route: '/masters/benchmarks', server });
    await user.click(await screen.findByRole('button', { name: 'Propose benchmark' }));
    const d = await screen.findByRole('dialog', { name: 'Propose benchmark' });
    await user.type(within(d).getByLabelText(/^Code/), 'repo');
    await user.type(within(d).getByLabelText(/^Name/), 'CLAUDE-TEST duplicate');
    await user.type(within(d).getByLabelText(/^Source/), 'CLAUDE-TEST');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('This benchmark already exists')).toBeInTheDocument();
    await user.clear(within(d).getByLabelText(/^Code/));
    await user.type(within(d).getByLabelText(/^Code/), 'TBILL364');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Benchmark sent for approval.')).toBeInTheDocument();
    expect(screen.queryByTestId('benchmark-TBILL364')).not.toBeInTheDocument();

    const a = server.db.approvals.find((s) => s.approval.entityType === 'BENCHMARK')!;
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).status).toBe(200);
    first.unmount();
    renderApp({ user: 'maker', route: '/masters/benchmarks', server });
    expect(await screen.findByTestId('benchmark-TBILL364')).toHaveTextContent('No rate in force');
    expect((await mockCall(server, 'auditor', 'POST', '/api/v1/benchmarks', { code: 'X1', name: 'x', source: 'x' })).status).toBe(403);
  });
});
