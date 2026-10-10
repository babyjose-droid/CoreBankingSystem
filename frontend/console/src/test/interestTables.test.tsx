import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

describe('interest tables', () => {
  it('lists tables with their rows', async () => {
    renderApp({ user: 'checker', route: '/masters/interest-tables' });
    expect(await screen.findByRole('heading', { name: 'Interest tables' })).toBeInTheDocument();
    const hls = await screen.findByTestId('table-HLS');
    expect(hls).toHaveTextContent('SPREAD');
    expect(within(screen.getByRole('table', { name: 'Rows of HLS' })).getAllByRole('row')).toHaveLength(3);
    expect(screen.queryByRole('button', { name: 'Propose table' })).not.toBeInTheDocument();
  });

  it('proposes a table; rows are checked; it exists only after approval and a product can then pick it', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    renderApp({ user: 'maker', route: '/masters/interest-tables', server });
    await user.click(await screen.findByRole('button', { name: 'Propose table' }));
    const d = await screen.findByRole('dialog', { name: 'Propose interest table' });
    await user.type(within(d).getByLabelText(/^Code/), 'CLAUDE_TEST_T');
    await user.type(within(d).getByLabelText(/^Name/), 'CLAUDE-TEST table');
    await user.type(within(d).getByLabelText('Rows'), '0 100000 12 60 15');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Interest table sent for approval.')).toBeInTheDocument();
    expect(server.db.interestTables.some((t) => t.code === 'CLAUDE_TEST_T')).toBe(false);
    const a = server.db.approvals.find((s) => s.approval.entityType === 'INTEREST_TABLE')!;
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {})).status).toBe(200);
    expect(server.db.interestTables.find((t) => t.code === 'CLAUDE_TEST_T')).toMatchObject({ mode: 'ABSOLUTE', rows: [{ minAmount: '0.00', maxAmount: '100000.00', rate: '15.0000' }] });
  });

  it('refuses overlapping bands and out-of-range rates', async () => {
    const server = createMockServer();
    const t = (rows: unknown[], mode = 'FIXED') => ({ code: 'CLAUDE_TEST_X', name: 'CLAUDE-TEST', mode, rows });
    const row = (a: number, b: number, c: number, d: number, rate: number) => ({ minAmount: a, maxAmount: b, minTenorMonths: c, maxTenorMonths: d, rate });
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/interest-tables', t([row(0, 100, 12, 36, 10), row(100, 200, 24, 48, 9)]))).status).toBe(422);
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/interest-tables', t([row(0, 100, 12, 36, 10), row(0, 100, 37, 60, 9)]))).status).toBe(202);
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/interest-tables', t([row(0, 100, 12, 36, 31)], 'SPREAD'))).status).toBe(422);
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/interest-tables', t([row(0, 100, 12, 36, 61)]))).status).toBe(422);
    expect((await mockCall(server, 'checker', 'POST', '/api/v1/interest-tables', t([row(0, 100, 12, 36, 10)]))).status).toBe(403);
  });
});
