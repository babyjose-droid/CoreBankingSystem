import { describe, expect, it } from 'vitest';
import { mockCall } from '../test/utils';
import { createMockServer } from './server';

const setup = (firstName: string) => {
  const server = createMockServer({ eodStepMs: 1 });
  const c = server.db.customers.find((x) => x.input.firstName === firstName)!;
  const loan = server.db.loans.find((l) => l.customerId === c.id)!;
  return { server, loan };
};

describe('lending mock API', () => {
  it('builds a reducing-balance EMI schedule whose principal adds up to the amount', async () => {
    const { server } = setup('Anu');
    const customerId = server.db.customers[0].id;
    const res = await mockCall(server, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'PL01', customerId, amount: '250000', tenorMonths: 24, rate: '15' });
    expect(res.status).toBe(200);
    const rows = res.body.schedule as Array<{ principal: string; interest: string; closingBalance: string }>;
    expect(rows).toHaveLength(24);
    expect(rows.reduce((s, r) => s + Number(r.principal), 0)).toBeCloseTo(250000, 2);
    expect(Number(rows[0].interest)).toBeGreaterThan(Number(rows[23].interest));
    expect(rows[23].closingBalance).toBe('0.00');
    const band = await mockCall(server, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'PL01', customerId, amount: '250000', tenorMonths: 24, rate: '30' });
    expect(band.status).toBe(422);
    expect(band.body.errors).toEqual([{ field: 'rate', message: 'Rate must be within the product band 12% to 24%' }]);
  });

  it('lists day-end entries only with dayEnd=true', async () => {
    const { server, loan } = setup('Deepak');
    const plain = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/transactions`)).body as Array<{ type: string }>;
    expect(plain.some((t) => t.type === 'EOD')).toBe(false);
    const all = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/transactions?dayEnd=true`)).body as Array<{ type: string; businessDate: string }>;
    expect(all.filter((t) => t.type === 'EOD').length).toBeGreaterThan(0);
    expect(all.filter((t) => t.type !== 'EOD')).toHaveLength(plain.length);
    expect(all.map((t) => t.businessDate)).toEqual([...all.map((t) => t.businessDate)].sort().reverse());
  });

  it('reverses a repayment (and later ones) only after approval, replaying the loan', async () => {
    const { server, loan } = setup('Deepak');
    const repaid = await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/repayments`, { amount: '10954.00' });
    expect(repaid.body.dpd).toBe(0);
    const txns = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/transactions`)).body;
    const rev = await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/transactions/${txns[0].id}/reverse`, { reason: 'CLAUDE-TEST cheque bounced' });
    expect(rev.status).toBe(202);
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body.dpd).toBe(0);
    const own = await mockCall(server, 'maker', 'POST', `/api/v1/approvals/${rev.body.id}/approve`, {});
    expect(own.status).toBe(403); // the maker has no approval:approve
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${rev.body.id}/approve`, {});
    const after = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body;
    expect(after).toMatchObject({ dpd: 52, assetClass: 'SMA1', overdueAmount: '10954.00' });
  });

  it('checker cannot repay; waiver applies on approval', async () => {
    const { server, loan } = setup('Anu');
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/loans/${loan.id}/repayments`, { amount: '10' })).status).toBe(403);
    await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/charges`, { feeCode: 'BNC' });
    const sched = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/schedule`)).body;
    const charge = sched.charges.find((c: { code: string }) => c.code === 'BNC');
    const w = await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/charges/${charge.id}/waiver`, { amount: charge.unpaid, reason: 'CLAUDE-TEST goodwill' });
    expect(w.status).toBe(202);
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${w.body.id}/approve`, {});
    const after = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/schedule`)).body;
    expect(after.charges.find((c: { code: string }) => c.code === 'BNC')).toMatchObject({ waived: '354.00', unpaid: '0.00' });
  });

  it('day-end raises the next demand and ages DPD', async () => {
    const { server, loan } = setup('Deepak');
    for (let i = 0; i < 11; i++) {
      const run = await mockCall(server, 'ops', 'POST', '/api/v1/eod/runs');
      expect(run.status).toBe(202);
      await new Promise((r) => setTimeout(r, 40));
      while (server.db.eodRuns.some((r) => r.status === 'RUNNING')) await new Promise((r) => setTimeout(r, 10));
    }
    const l = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body;
    expect(server.db.businessDate > '2026-07-10').toBe(true);
    expect(l.dpd).toBeGreaterThan(52);
    const sched = (await mockCall(server, 'maker', 'GET', `/api/v1/loans/${loan.id}/schedule`)).body;
    expect(sched.demands.length).toBe(7);
  });
});
