import { describe, expect, it } from 'vitest';
import { mockCall } from '../test/utils';
import { createMockServer, type MockServer } from './server';

const cust = (s: MockServer, n: string) => s.db.customers.find((x) => x.input.firstName === n)!;
const loanOf = (s: MockServer, n: string) => s.db.loans.find((l) => l.customerId === cust(s, n).id)!;
const approve = (s: MockServer, id: string, user = 'checker') => mockCall(s, user, 'POST', `/api/v1/approvals/${id}/approve`, {});

/** A home loan of 10 lakh, KFS accepted, first tranche of 4 lakh disbursed. */
async function trancheLoan(s: MockServer) {
  const booked = await mockCall(s, 'maker', 'POST', '/api/v1/loans', { productCode: 'HL01', customerId: cust(s, 'Hari').id, amount: '1000000', tenorMonths: 120 });
  expect(booked.status).toBe(201);
  const id = booked.body.id as string;
  await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/kfs-acceptance`, { channel: 'OTP' });
  const d = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { amount: '400000', mode: 'NEFT' });
  expect(d.status).toBe(202);
  expect((await approve(s, d.body.id)).status).toBe(200);
  return id;
}

describe('product templates and preview', () => {
  it('lists eight templates and previews drafts through the booking engine', async () => {
    const s = createMockServer();
    const t = (await mockCall(s, 'maker', 'GET', '/api/v1/loan-product-templates')).body as Array<{ code: string; product: Record<string, unknown> }>;
    expect(t).toHaveLength(8);
    const by = (code: string) => t.find((x) => x.code === code)!.product;
    const weekly = (await mockCall(s, 'maker', 'POST', '/api/v1/loan-products/preview', { product: by('MICRO_WEEKLY'), amount: '20000', tenorMonths: 26, rate: '24' })).body;
    expect(weekly.schedule).toHaveLength(26);
    expect(weekly.schedule[1].days).toBe(7);
    const step = (await mockCall(s, 'maker', 'POST', '/api/v1/loan-products/preview', { product: by('BUSINESS_STEP_UP') })).body;
    expect(Number(step.schedule[12].instalment)).toBeCloseTo(Number(step.schedule[0].instalment) * 1.1, -1);
    expect(step.schedule[23].closingBalance).toBe('0.00');
    const flat = (await mockCall(s, 'maker', 'POST', '/api/v1/loan-products/preview', { product: by('CONSUMER_FLAT'), amount: '120000', tenorMonths: 12, rate: '10' })).body;
    expect(flat.interestRate).toBe('10.00');
    expect(flat.emi).toBe('11000.00'); // (1,20,000 + 10% flat) / 12
    expect(flat.rateExplanation).toMatch(/flat 10% p.a., which is 17\.\d+% p.a. on the reducing balance/);
    expect(Number(flat.apr)).toBeGreaterThan(17);
    const structured = (await mockCall(s, 'maker', 'POST', '/api/v1/loan-products/preview', { product: by('AGRI_STRUCTURED'), tenorMonths: 4 })).body;
    expect(structured.sampleSchedule).toBe(true);
    expect(structured.schedule).toHaveLength(4);
    const floating = (await mockCall(s, 'maker', 'POST', '/api/v1/loan-products/preview', { product: by('HOME_FLOATING_TRANCHES') })).body;
    expect(floating.interestRate).toBe('9.25');
    const refused = await mockCall(s, 'maker', 'POST', '/api/v1/loan-products/preview', { product: { ...by('GOLD_BULLET'), interestBasis: 'FLAT' } });
    expect(refused.status).toBe(422);
  });

  it('derives the rate from an agreed instalment or a maturity amount, and books a structured plan', async () => {
    const s = createMockServer();
    const customerId = cust(s, 'Hari').id;
    const emi = (await mockCall(s, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'PL01', customerId, amount: '100000', tenorMonths: 12, instalment: '9168' })).body;
    expect(Number(emi.interestRate)).toBeCloseTo(18, 0);
    expect(emi.emi).toBe('9168.00');
    const both = await mockCall(s, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'PL01', customerId, amount: '100000', tenorMonths: 12, instalment: '9168', rate: '18' });
    expect(both.status).toBe(422);
    const bullet = (await mockCall(s, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'ML01', customerId, amount: '20000', tenorMonths: 1, maturityAmount: '20400' })).body;
    expect(bullet.totalInterest).toBe('400.00');
    expect(Number(bullet.interestRate)).toBeGreaterThan(20);
  });
});

describe('tranches, simulations and sanction change', () => {
  it('draws a home loan in tranches with pre-EMI interest, then EMIs when fully drawn', async () => {
    const s = createMockServer();
    const id = await trancheLoan(s);
    let loan = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body;
    expect(loan).toMatchObject({ status: 'ACTIVE', amount: '1000000.00', disbursedAmount: '400000.00', undrawnAmount: '600000.00', principalOutstanding: '400000.00', currentEmi: null, preEmi: true });
    const sched = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}/schedule`)).body;
    expect(sched.future[0].principal).toBe('0.00'); // interest only until fully drawn

    const sim = (await mockCall(s, 'checker', 'POST', `/api/v1/loans/${id}/simulations/disbursement`, { amount: '600000' })).body;
    expect(sim).toMatchObject({ trancheNo: 2, netDisbursal: '600000.00', undrawnAfter: '0.00', fullyDrawn: true, preEmi: false });
    expect(sim.schedule).toHaveLength(120);
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.disbursedAmount).toBe('400000.00'); // nothing changed
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/simulations/disbursement`, { amount: '700000' })).status).toBe(422);

    const d2 = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { amount: '300000' });
    await approve(s, d2.body.id);
    const tr = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}/tranches`)).body;
    expect(tr).toMatchObject({ disbursedAmount: '700000.00', undrawnAmount: '300000.00', multipleDisbursements: true });
    expect(tr.tranches.map((t: { trancheNo: number; amount: string }) => [t.trancheNo, t.amount])).toEqual([[1, '400000.00'], [2, '300000.00']]);

    // a tranche cannot be reversed; cancelling the undrawn amount starts the EMIs
    const txns = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}/transactions`)).body as Array<{ id: string; type: string }>;
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/transactions/${txns[0].id}/reverse`, { reason: 'x' })).status).toBe(422);
    const pv = (await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/sanction-change/preview`, { cancelUndrawn: true })).body;
    expect(pv).toMatchObject({ sanctionedBefore: '1000000.00', sanctionedAfter: '700000.00', undrawnAfter: '0.00', topUp: false });
    expect(Number(pv.schedule[0].principal)).toBeGreaterThan(0);
    const sc = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/sanction-change`, { cancelUndrawn: true, reason: 'CLAUDE-TEST construction finished early' });
    expect(sc.body).toMatchObject({ entityType: 'LOAN_SANCTION_CHANGE', checkersRequired: 1 });
    await approve(s, sc.body.id);
    loan = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body;
    expect(loan).toMatchObject({ amount: '700000.00', undrawnAmount: '0.00' });
    expect(Number(loan.emi)).toBeGreaterThan(8000);
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}/amendments`)).body[0]).toMatchObject({ kind: 'SANCTION_CHANGE' });
  });

  it('tops up a standard account, refuses one in arrears, and never reduces below the amount disbursed', async () => {
    const s = createMockServer();
    const anu = loanOf(s, 'Anu');
    const up = (await mockCall(s, 'maker', 'POST', `/api/v1/loans/${anu.id}/sanction-change/preview`, { newAmount: '250000' })).body;
    expect(up).toMatchObject({ topUp: true, undrawnAfter: '50000.00' });
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${anu.id}/sanction-change/preview`, { newAmount: '150000' })).status).toBe(422);
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${anu.id}/sanction-change/preview`, { newAmount: '600000' })).status).toBe(422); // product max 5 lakh
    const arrears = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loanOf(s, 'Deepak').id}/sanction-change/preview`, { newAmount: '200000' });
    expect(arrears.status).toBe(409);
    expect(arrears.body.detail).toMatch(/evergreening/);
    const p = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${anu.id}/sanction-change`, { newAmount: '250000', reason: 'CLAUDE-TEST top-up' });
    await approve(s, p.body.id);
    const d = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${anu.id}/disbursement`, {});
    expect(d.body.proposed).toMatchObject({ amount: '50000.00', tranche: 2 });
    await approve(s, d.body.id);
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${anu.id}`)).body).toMatchObject({ amount: '250000.00', principalOutstanding: '213307.00', undrawnAmount: '0.00' });
  });

  it('simulates a receipt, a part-prepayment and a pre-closure on a future date without changing the loan', async () => {
    const s = createMockServer();
    const loan = loanOf(s, 'Deepak');
    const sim = (path: string, body: unknown) => mockCall(s, 'checker', 'POST', `/api/v1/loans/${loan.id}/simulations/${path}`, body);
    const r = (await sim('transaction', { type: 'REPAYMENT', amount: '6000' })).body;
    expect(r).toMatchObject({ duesBefore: '10954.00', duesAfter: '4954.00', advance: '0.00' });
    expect(r.allocations[0]).toMatchObject({ ref: 'D5', component: 'INTEREST' });
    const later = (await sim('transaction', { type: 'REPAYMENT', amount: '6000', onDate: '2026-07-20' })).body;
    expect(Number(later.duesBefore)).toBeGreaterThan(16000); // the July instalment and a penal charge have fallen due by then
    expect((await sim('transaction', { type: 'PREPAYMENT', amount: '10000' })).status).toBe(409);
    const anu = loanOf(s, 'Anu');
    const pp = (await mockCall(s, 'checker', 'POST', `/api/v1/loans/${anu.id}/simulations/transaction`, { type: 'PREPAYMENT', amount: '50000', mode: 'REDUCE_EMI' })).body;
    expect(pp).toMatchObject({ instalmentBefore: '9793.00', remainingBefore: 19, remainingAfter: 19, principalOutstandingAfter: '113307.00' });
    expect(Number(pp.instalmentAfter)).toBeLessThan(9793);
    const pc = (await mockCall(s, 'checker', 'POST', `/api/v1/loans/${anu.id}/simulations/transaction`, { type: 'PRECLOSURE' })).body;
    expect(pc).toMatchObject({ total: '168235.04', statusAfter: 'CLOSED' });
    expect((await mockCall(s, 'checker', 'POST', `/api/v1/loans/${anu.id}/simulations/transaction`, { type: 'PRECLOSURE', onDate: '2028-01-01' })).status).toBe(422);
    expect(anu.events.length).toBe(6);
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body.overdueAmount).toBe('10954.00');
  });
});

describe('manual NPA override', () => {
  it('needs two checkers, never upgrades, and cannot be released while dues are unpaid', async () => {
    const s = createMockServer();
    const loan = loanOf(s, 'Deepak'); // SMA-1 with arrears
    expect((await mockCall(s, 'checker', 'POST', `/api/v1/loans/${loan.id}/npa-override`, { assetClass: 'SUBSTANDARD', until: '2026-12-31', reason: 'x' })).status).toBe(403);
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loanOf(s, 'Esha').id}/npa-override`, { assetClass: 'SUBSTANDARD', until: '2026-06-30', reason: 'x' })).status).toBe(422);
    const p = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loan.id}/npa-override`, { assetClass: 'DOUBTFUL1', until: '2026-12-31', reason: 'CLAUDE-TEST fraud reported' });
    expect(p.body).toMatchObject({ entityType: 'LOAN_NPA_OVERRIDE', action: 'OVERRIDE', checkersRequired: 2 });
    expect((await approve(s, p.body.id)).body.status).toBe('PENDING');
    expect((await approve(s, p.body.id, 'admin')).body.status).toBe('APPROVED');
    let l = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body;
    expect(l).toMatchObject({ assetClass: 'DOUBTFUL1', overrideClass: 'DOUBTFUL1', overrideUntil: '2026-12-31' });
    // never an upgrade
    const upg = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loan.id}/npa-override`, { assetClass: 'SUBSTANDARD', until: '2026-12-31', reason: 'x' });
    expect(upg.status).toBe(409);
    // release refused while arrears exist; paying does not upgrade while the override holds
    const rel = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loan.id}/npa-override/release`, { reason: 'x' });
    expect(rel.status).toBe(409);
    expect(rel.body.detail).toMatch(/only when all arrears are cleared/);
    await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loan.id}/repayments`, { amount: '10954.00' });
    l = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body;
    expect(l).toMatchObject({ dpd: 0, assetClass: 'DOUBTFUL1' });
    const rel2 = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loan.id}/npa-override/release`, { reason: 'CLAUDE-TEST dues cleared' });
    expect(rel2.status).toBe(202);
    await approve(s, rel2.body.id);
    await approve(s, rel2.body.id, 'admin');
    l = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${loan.id}`)).body;
    expect(l).toMatchObject({ assetClass: 'STANDARD', overrideClass: null });
    // the repayment before the override can no longer be reversed
    const txns = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${loan.id}/transactions`)).body as Array<{ id: string; type: string; seq: number }>;
    const first = txns.filter((t) => t.type === 'REPAYMENT').sort((a, b) => a.seq - b.seq)[0];
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loan.id}/transactions/${first.id}/reverse`, { reason: 'x' })).status).toBe(409);
  });

  it('changes the maturity through an amendment', async () => {
    const s = createMockServer();
    const loan = loanOf(s, 'Anu');
    const pv = (await mockCall(s, 'maker', 'POST', `/api/v1/loans/${loan.id}/amendments/preview`, { kind: 'MATURITY_CHANGE', newMaturityDate: '2027-07-31' })).body;
    expect(pv).toMatchObject({ remainingBefore: 19, remainingAfter: 13, maturityAfter: '2027-07-15' });
  });
});

describe('tranche bullet, floating rate slab and elapsed-tenure steps (V25)', () => {
  const base = (s: MockServer) => ({ ...s.db.loanProducts.find((p) => p.code === 'PL01')!, version: undefined });

  async function approveProduct(s: MockServer, product: Record<string, unknown>) {
    const r = await mockCall(s, 'maker', 'POST', '/api/v1/loan-products', product);
    expect(r.status).toBe(202);
    expect((await approve(s, r.body.id)).status).toBe(200);
  }

  it('repays each tranche on its own maturity date, given at disbursement', async () => {
    const s = createMockServer();
    await approveProduct(s, { ...base(s), code: 'TB01', name: 'CLAUDE-TEST Tranche bullet', repaymentMethod: 'TRANCHE_BULLET', multipleDisbursements: true, minTenorMonths: 3, maxTenorMonths: 24, fees: [] });
    const refused = await mockCall(s, 'maker', 'POST', '/api/v1/loan-products', { ...base(s), code: 'TB02', repaymentMethod: 'TRANCHE_BULLET', multipleDisbursements: false });
    expect(refused.status).toBe(422);

    const booked = await mockCall(s, 'maker', 'POST', '/api/v1/loans', { productCode: 'TB01', customerId: cust(s, 'Hari').id, amount: '300000', tenorMonths: 12, rate: '14' });
    const id = booked.body.id as string;
    await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/kfs-acceptance`, { channel: 'OTP' });
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { amount: '100000', maturityDate: '2027-01-31' })).status).toBe(422); // first: the tenor
    const d1 = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { amount: '100000' });
    await approve(s, d1.body.id);

    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { amount: '50000' })).status).toBe(422); // no maturity
    const tooLong = s.db.businessDate.replace(/^(\d{4})/, (y) => String(Number(y) + 3));
    expect((await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { amount: '50000', maturityDate: tooLong })).status).toBe(422);
    const maturity = (await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/simulations/disbursement`, { amount: '50000', maturityDate: '2099-01-01' })).status;
    expect(maturity).toBe(422);

    const sched0 = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}/schedule`)).body.future as Array<{ dueDate: string }>;
    const at = sched0[3].dueDate; // a regular due date four periods ahead: within 3-24 months
    const sim = (await mockCall(s, 'checker', 'POST', `/api/v1/loans/${id}/simulations/disbursement`, { amount: '50000', maturityDate: at })).body;
    expect(sim.schedule.find((r: { dueDate: string }) => r.dueDate === at).principal).toBe('50000.00');
    const d2 = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { amount: '50000', maturityDate: at });
    expect(d2.status).toBe(202);
    await approve(s, d2.body.id);
    const tr = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}/tranches`)).body;
    expect(tr.tranches.map((t: { maturityDate: string | null }) => t.maturityDate)).toEqual([null, at]);
    const future = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}/schedule`)).body.future as Array<{ dueDate: string; principal: string; interest: string }>;
    expect(future.find((r) => r.dueDate === at)!.principal).toBe('50000.00');
    expect(future[future.length - 1].principal).toBe('100000.00');
    expect(future.every((r) => Number(r.interest) > 0)).toBe(true);
  });

  it('takes the spread from a SPREAD table and starts a stepped product at its first step', async () => {
    const s = createMockServer();
    await approveProduct(s, { ...base(s), code: 'HL09', name: 'CLAUDE-TEST Floating slab', rateType: 'FLOATING', benchmarkCode: 'REPO', spread: null, interestTableCode: 'HLS', resetFrequencyMonths: 3, minRate: '5', maxRate: '15', fees: [] });
    const kfs = (await mockCall(s, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'HL09', customerId: cust(s, 'Hari').id, amount: '100000', tenorMonths: 24 })).body;
    expect(kfs.rateExplanation).toMatch(/slab spread \(HLS\) 3.25%/);
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/loan-products', { ...base(s), code: 'PL09', interestTableCode: 'HLS' })).status).toBe(422);

    await approveProduct(s, { ...base(s), code: 'PL08', name: 'CLAUDE-TEST Stepped', topUpAllowed: false, interestTableCode: null, rateSteps: [{ fromMonth: 1, ratePercent: '14' }, { fromMonth: 13, ratePercent: '16' }], fees: [] });
    const stepped = (await mockCall(s, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'PL08', customerId: cust(s, 'Hari').id, amount: '100000', tenorMonths: 24 })).body;
    expect(stepped.interestRate).toBe('14.00');
    expect(stepped.rateSteps).toEqual([{ fromMonth: 1, ratePercent: '14.00' }, { fromMonth: 13, ratePercent: '16.00' }]);
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/loan-products', { ...base(s), code: 'PL07', rateSteps: [{ fromMonth: 2, ratePercent: '14' }, { fromMonth: 13, ratePercent: '16' }] })).status).toBe(422);
  });
});
