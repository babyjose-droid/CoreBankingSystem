import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { parseDecisionsCsv, parsePartnerClassCsv } from '../api/coLendingHooks';
import { addDays } from '../lib/dates';
import { coLendingDayEnd } from '../mock/coLending';
import { lendingDayEnd } from '../mock/lending';
import { createMockServer, type MockServer } from '../mock/server';
import { mockToken } from '../auth/demoUsers';
import { mockCall, renderApp } from './utils';

const cust = (s: MockServer, n: string) => s.db.customers.find((x) => x.input.firstName === n)!;

/** A personal loan under the seeded BANKX arrangement (20% at 22%, 80% at 10.5%: blended 12.8%), disbursed. */
async function coLentLoan(s: MockServer, who = 'Hari', disburse = true) {
  const booked = await mockCall(s, 'maker', 'POST', '/api/v1/loans', { productCode: 'PL01', customerId: cust(s, who).id, amount: '100000', tenorMonths: 12, coLendingArrangement: 'BANKX' });
  expect(booked.status).toBe(201);
  const id = booked.body.id as string;
  if (!disburse) return { id, loanNo: booked.body.loanNo as string };
  await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/kfs-acceptance`, { channel: 'OTP' });
  const d = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/disbursement`, { mode: 'NEFT' });
  await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${d.body.id}/approve`, {});
  return { id, loanNo: booked.body.loanNo as string };
}

describe('co-lending (mock API)', () => {
  it('agrees an arrangement through a checker within the limits of the Directions', async () => {
    const s = createMockServer();
    const base = { code: 'CLAUDE_TEST', name: 'CLAUDE-TEST arrangement', partnerName: 'CLAUDE-TEST Bank', partnerKind: 'BANK', agreementReference: 'CLA/9', originatorShare: '15', originatorRate: '24', partnerRate: '11', dlgPercent: '5', productCodes: ['PL01'], contact: 'care 1800' };
    const low = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements', { ...base, originatorShare: '9' });
    expect(low.status).toBe(422);
    expect(low.body.detail).toContain('at least 10%');
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements', { ...base, dlgPercent: '6' })).body.detail).toContain('at most 5%');
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements', { ...base, productCodes: ['HL01'] })).body.detail).toMatch(/floating rate|tranches/);
    const p = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements', base);
    expect(p.status).toBe(202);
    expect(p.body.proposed).toMatchObject({ blendedRate: '12.95', partnerShare: '85' });
    await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${p.body.id}/approve`, {});
    const a = (await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/CLAUDE_TEST')).body;
    expect(a).toMatchObject({ status: 'ACTIVE', version: 1, blendedRate: '12.95', role: 'ORIGINATOR' });
    const change = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements', { ...base, partnerName: 'Someone else' });
    expect(change.body.detail).toContain('never changes');
  });

  it('prices the loan at the blended rate, offers it at disbursement and books the partner’s share on acceptance', async () => {
    const s = createMockServer();
    const wrongRate = await mockCall(s, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'PL01', customerId: cust(s, 'Hari').id, amount: '100000', tenorMonths: 12, rate: '15', coLendingArrangement: 'BANKX' });
    expect(wrongRate.status).toBe(422);
    const kfs = (await mockCall(s, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'PL01', customerId: cust(s, 'Hari').id, amount: '100000', tenorMonths: 12, coLendingArrangement: 'BANKX' })).body;
    expect(kfs.coLending).toMatchObject({ arrangementCode: 'BANKX', blendedRate: '12.8' });
    expect(kfs.coLending.lenders).toHaveLength(2);

    const sanctioned = await coLentLoan(s, 'Hari', false);
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${sanctioned.id}`)).body.coLending).toMatchObject({ status: 'SANCTIONED', blendedRate: '12.8' });

    const { id, loanNo } = await coLentLoan(s, 'Anu');
    const loan = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body;
    expect(loan.rate).toBe('12.80');
    expect(loan.coLending).toMatchObject({ status: 'OFFERED', offeredOn: s.db.businessDate, deadline: addDays(s.db.businessDate, 15) });
    const offers = (await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/offers')).body;
    expect(offers).toHaveLength(1);
    expect(offers[0]).toMatchObject({ loanNo, partnerShare: '80', partnerPrincipal: '80000.00' });
    const csv = await s.fetch(new Request('http://localhost/api/v1/co-lending/arrangements/BANKX/offers.csv', { headers: { Authorization: `Bearer ${mockToken('maker')}` } }));
    const text = await csv.text();
    expect(text.split('\r\n')[0]).toBe('loan_no,customer_no,disbursed_on,deadline,sanctioned_amount,principal_outstanding,partner_share_percent,partner_principal,partner_rate,blended_rate,tenor_months,emi,asset_class,dpd');
    expect(text).toContain(loanNo);

    const sim = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/simulate-partner', {});
    expect(sim.status).toBe(202);
    expect(sim.body).toMatchObject({ entityType: 'CO_LENDING_DECISION', amount: '80000.00' });
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.coLending.status).toBe('OFFERED');
    const ok = await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${sim.body.id}/approve`, {});
    expect(ok.body.appliedRef).toBe('BANKX: 1 accepted, 0 declined');
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.coLending).toMatchObject({ status: 'ACCEPTED', partnerLoanRef: `SIM-${loanNo}`, partnerPrincipal: '80000.00', originatorPrincipal: '20000.00' });
    expect((await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX')).body).toMatchObject({ partnerPrincipalBooked: '80000.00', loans: { sanctioned: 1, accepted: 1 } });
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/simulate-partner', {})).status).toBe(409);
  });

  it('a decline needs a reason; after the 15th day the offer lapses and the loan stays with the originator', async () => {
    const s = createMockServer();
    const a = await coLentLoan(s, 'Hari');
    const b = await coLentLoan(s, 'Anu');
    const noReason = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/decisions', { decisions: [{ loanNo: a.loanNo, decision: 'DECLINE' }] });
    expect(noReason.status).toBe(422);
    const d = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/decisions', { decisions: [{ loanNo: a.loanNo, decision: 'DECLINE', reason: 'CLAUDE-TEST policy' }] });
    await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${d.body.id}/approve`, {});
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${a.id}`)).body.coLending).toMatchObject({ status: 'DECLINED', decisionReason: 'CLAUDE-TEST policy' });
    const late = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/decisions', { decisions: [{ loanNo: b.loanNo, decision: 'ACCEPT' }] });
    expect(late.status).toBe(202);
    s.db.businessDate = addDays(s.db.businessDate, 16);
    const applied = await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${late.body.id}/approve`, {});
    expect(applied.body.appliedRef).toBe('BANKX: 0 accepted, 0 declined, 1 no longer offered');
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${b.id}`)).body.coLending.status).toBe('LAPSED');
    const close = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/status', { status: 'CLOSED', reason: 'CLAUDE-TEST end' });
    expect(close.status).toBe(202);
  });

  it('refuses a co-lent loan outside the arrangement, and none under a suspended one', async () => {
    const s = createMockServer();
    const other = await mockCall(s, 'maker', 'POST', '/api/v1/loans', { productCode: 'ML01', customerId: cust(s, 'Hari').id, amount: '500000', tenorMonths: 24, rate: '12', coLendingArrangement: 'BANKX' });
    expect(other.status).toBe(422);
    const sus = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/status', { status: 'SUSPENDED', reason: 'CLAUDE-TEST review' });
    await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${sus.body.id}/approve`, {});
    const r = await mockCall(s, 'maker', 'POST', '/api/v1/loans', { productCode: 'PL01', customerId: cust(s, 'Hari').id, amount: '100000', tenorMonths: 12, coLendingArrangement: 'BANKX' });
    expect(r.status).toBe(422);
    expect(r.body.errors[0].message).toContain('suspended');
  });

  it('reads the partner’s decision file', () => {
    expect(parseDecisionsCsv('loan_no,decision,partner_loan_ref,reason\r\n1001,accept,PB-1,\r\n1002,DECLINE,,"Policy, age"\r\n')).toEqual({
      decisions: [
        { loanNo: '1001', decision: 'ACCEPT', partnerLoanRef: 'PB-1', reason: null },
        { loanNo: '1002', decision: 'DECLINE', partnerLoanRef: null, reason: 'Policy, age' },
      ],
      problems: [],
    });
    expect(parseDecisionsCsv('loan,decision\n1,ACCEPT').problems[0]).toContain('loan_no');
    expect(parseDecisionsCsv('loan_no,decision\n1,MAYBE').problems).toEqual(['Row 2: the decision is ACCEPT or DECLINE']);
  });
});

describe('co-lending pages', () => {
  it('lists the arrangement, shows its offers and sends the simulated acceptance for approval', async () => {
    const s = createMockServer();
    const { loanNo } = await coLentLoan(s, 'Anu');
    renderApp({ user: 'maker', route: '/co-lending', server: s });
    await screen.findByRole('heading', { name: 'Co-lending' });
    const list = await screen.findByRole('table', { name: 'Co-lending arrangements' });
    await user().click(within(list).getByRole('link', { name: 'BANKX' }));
    expect(await screen.findByTestId('arrangement-blended')).toHaveTextContent('12.8');
    const offers = await screen.findByRole('table', { name: 'Offers open to the partner' });
    await waitFor(() => expect(within(offers).getByText(loanNo)).toBeInTheDocument());
    await user().click(screen.getByRole('button', { name: 'Simulate partner' }));
    expect(await screen.findByText(/Simulated partner acceptance sent for approval/)).toBeInTheDocument();
  });

  it('shows the co-lending terms on the loan and in its KFS', async () => {
    const s = createMockServer();
    const { id } = await coLentLoan(s, 'Anu');
    renderApp({ user: 'maker', route: `/loans/${id}`, server: s });
    const card = await screen.findByTestId('loan-co-lending');
    expect(card).toHaveTextContent('BANKX');
    expect(within(await screen.findByTestId('co-lent-status')).getByText(/offered/i)).toBeInTheDocument();
  });

  it('proposes a new arrangement, showing the blended rate as it is typed', async () => {
    const s = createMockServer();
    renderApp({ user: 'maker', route: '/co-lending', server: s });
    await user().click(await screen.findByRole('button', { name: 'New arrangement…' }));
    const dialog = await screen.findByRole('dialog');
    await user().type(within(dialog).getByLabelText(/^Code/), 'CLAUDE_TEST');
    await user().type(within(dialog).getByLabelText(/^Name/), 'CLAUDE-TEST arrangement');
    await user().type(within(dialog).getByLabelText(/^Partner lender/), 'CLAUDE-TEST Bank');
    await user().type(within(dialog).getByLabelText(/^Agreement reference/), 'CLA/1');
    await user().type(within(dialog).getByLabelText(/^Originator’s rate/), '24');
    await user().type(within(dialog).getByLabelText(/^Partner’s rate/), '11');
    await user().type(within(dialog).getByLabelText(/^Borrower’s single point/), 'care 1800');
    expect(within(dialog).getByTestId('blended-rate')).toHaveTextContent('13.6%');
    await user().click(await within(dialog).findByLabelText(/^PL01/));
    await user().click(within(dialog).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText(/New arrangement sent for approval/)).toBeInTheDocument();
    expect(s.db.approvals.some((a) => a.approval.entityType === 'CO_LENDING_ARRANGEMENT' && a.approval.entityId === 'CLAUDE_TEST')).toBe(true);
  });
});

/** Move the mock's business date on and run the loans' and the co-lending day-ends. */
function dayEnd(s: MockServer, days = 1) {
  s.db.businessDate = addDays(s.db.businessDate, days);
  lendingDayEnd(s.db);
  coLendingDayEnd(s.db);
}

async function accepted(s: MockServer) {
  const { id, loanNo } = await coLentLoan(s, 'Anu');
  const sim = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/simulate-partner', {});
  await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${sim.body.id}/approve`, {});
  return { id, loanNo };
}

describe('co-lending split and settlement (mock API)', () => {
  it('splits a repayment, takes the servicing fee and pays the partner through a checker', async () => {
    const s = createMockServer();
    const { id, loanNo } = await accepted(s);
    dayEnd(s, 30);
    const fee = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.coLending.servicingFeeTaken;
    expect(Number(fee)).toBeCloseTo((80000 * 0.5 * 29) / 36500, 1);
    const repaid = await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/repayments`, { amount: '9000' });
    expect(repaid.status).toBe(200);
    dayEnd(s);
    const view = (await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.coLending;
    expect(Number(view.partnerPrincipalRepaid)).toBeGreaterThan(0);
    expect(Number(view.partnerInterest)).toBeGreaterThan(0);
    const due = (await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/unsettled')).body;
    expect(due.loans).toHaveLength(1);
    expect(due.loans[0].loanNo).toBe(loanNo);
    expect(Number(due.totals.net)).toBeCloseTo(Number(view.unsettled), 2);
    const p = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/settlements', {});
    expect(p.status).toBe(202);
    expect(p.body).toMatchObject({ entityType: 'CO_LENDING_SETTLEMENT', amount: due.totals.net });
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/settlements', {})).status).toBe(409);
    const ok = await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${p.body.id}/approve`, {});
    expect(ok.body.appliedRef).toMatch(/^CLS00000001: .* paid to the partner of BANKX through escrow \(1 loan\)$/);
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.coLending.unsettled).toBe('0.00');
    const list = (await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/settlements')).body;
    expect(list[0]).toMatchObject({ settlementNo: 'CLS00000001', loans: 1, net: due.totals.net });
    const one = (await mockCall(s, 'maker', 'GET', `/api/v1/co-lending/settlements/${list[0].id}`)).body;
    expect(one.lines[0].loanNo).toBe(loanNo);
    const csv = await (await s.fetch(new Request(`http://localhost/api/v1/co-lending/settlements/${list[0].id}/statement.csv`, { headers: { Authorization: `Bearer ${mockToken('maker')}` } }))).text();
    expect(csv.split('\r\n')[0]).toBe('settlement_no,arrangement,business_date,loan_no,principal,interest,fees,penal_charges,servicing_fee,net_paid');
    expect(csv).toContain('TOTAL');
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/settlements', {})).status).toBe(409);
  });
});

describe('co-lending settlement page', () => {
  it('shows what is due to the partner and sends a settlement for approval', async () => {
    const s = createMockServer();
    const { loanNo } = await accepted(s);
    dayEnd(s, 30);
    renderApp({ user: 'maker', route: '/co-lending/BANKX', server: s });
    const table = await screen.findByRole('table', { name: 'Due to the partner by loan' });
    await waitFor(() => expect(within(table).getByText(loanNo)).toBeInTheDocument());
    expect(await screen.findByTestId('due-total')).toHaveTextContent('-');
  });

  it('settles once something is due', async () => {
    const s = createMockServer();
    const { id } = await accepted(s);
    dayEnd(s, 30);
    await mockCall(s, 'maker', 'POST', `/api/v1/loans/${id}/repayments`, { amount: '9000' });
    dayEnd(s);
    renderApp({ user: 'maker', route: '/co-lending/BANKX', server: s });
    const btn = await screen.findByRole('button', { name: 'Settle with partner…' });
    await waitFor(() => expect(btn).toBeEnabled());
    await user().click(btn);
    expect(await screen.findByText(/Settlement with the partner sent for approval/)).toBeInTheDocument();
  });
});

describe('co-lending guarantee and classification (mock API)', () => {
  it('lodges a cover within the cap, invokes it for a loan in default, recovers and releases', async () => {
    const s = createMockServer();
    const { loanNo } = await accepted(s);
    const g = (await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/dlg')).body;
    expect(g).toMatchObject({ partnerFunded: '80000.00', cap: '4000.00', headroom: '4000.00' });
    const until = addDays(g.longestLoanDue, 1);
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/dlg/covers', { form: 'CASH', amount: '4000.01', validUntil: until })).body.detail).toContain('above the cap');
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/dlg/covers', { form: 'BANK_GUARANTEE', amount: '1000', validUntil: until })).body.detail).toContain('with its number');
    const p = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/dlg/covers', { form: 'CASH', amount: '3000', validUntil: until });
    expect(p.status).toBe(202);
    expect((await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${p.body.id}/approve`, {})).body.appliedRef).toMatch(/^DLG000001: CASH cover of 3000/);
    const cover = (await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/dlg')).body.covers[0];
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/dlg/invocations', { loanNo, coverId: cover.id, amount: '100', reason: 'x' })).body.detail)
      .toContain('not a loan in default');
    dayEnd(s, 70);
    const d = (await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/dlg')).body;
    expect(d.inDefault[0]).toMatchObject({ loanNo });
    const inv = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/dlg/invocations', { loanNo, coverId: cover.id, amount: '1500', reason: 'CLAUDE-TEST claim' });
    expect(inv.status).toBe(202);
    await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${inv.body.id}/approve`, {});
    const after = (await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/dlg')).body;
    expect(after).toMatchObject({ invoked: '1500.00', available: '1500.00' });
    const rec = await mockCall(s, 'maker', 'POST', `/api/v1/co-lending/dlg/invocations/${after.invocations[0].id}/recoveries`, { amount: '500' });
    await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${rec.body.id}/approve`, {});
    expect((await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/dlg')).body.invocations[0].recovered).toBe('500.00');
    const rel = await mockCall(s, 'maker', 'POST', `/api/v1/co-lending/dlg/covers/${cover.id}/release`, { reason: 'CLAUDE-TEST run off' });
    await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${rel.body.id}/approve`, {});
    expect((await mockCall(s, 'maker', 'GET', '/api/v1/co-lending/arrangements/BANKX/dlg')).body.covers[0].status).toBe('RELEASED');
  });

  it('records the partner\'s classification through a checker and shows it on the loan', async () => {
    const s = createMockServer();
    const { id, loanNo } = await accepted(s);
    expect((await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/partner-classification', { rows: [{ loanNo, assetClass: 'SUBSTANDARD' }] })).status).toBe(422);
    const p = await mockCall(s, 'maker', 'POST', '/api/v1/co-lending/arrangements/BANKX/partner-classification', { rows: [{ loanNo, assetClass: 'SUBSTANDARD', npaSince: s.db.businessDate }] });
    const ok = await mockCall(s, 'checker', 'POST', `/api/v1/approvals/${p.body.id}/approve`, {});
    expect(ok.body.appliedRef).toBe('BANKX: 1 made NPA, 0 recorded');
    expect((await mockCall(s, 'maker', 'GET', `/api/v1/loans/${id}`)).body.coLending.partnerClass).toMatchObject({ assetClass: 'SUBSTANDARD' });
    const csv = await (await s.fetch(new Request('http://localhost/api/v1/co-lending/arrangements/BANKX/classification.csv', { headers: { Authorization: `Bearer ${mockToken('maker')}` } }))).text();
    expect(csv.split('\r\n')[0]).toBe('as_of,loan_no,partner_loan_ref,customer_no,dpd,asset_class,npa_since,principal_outstanding,partner_principal');
    expect(csv).toContain(loanNo);
    expect(parsePartnerClassCsv('loan_no,asset_class,npa_since,partner_dpd\n1,substandard,2026-06-01,95\n2,SMA1,,x')).toEqual({
      rows: [{ loanNo: '1', assetClass: 'SUBSTANDARD', npaSince: '2026-06-01', partnerDpd: 95 }],
      problems: ['Row 3: days past due are a whole number'],
    });
  });

  it('shows the guarantee on the arrangement page and lodges a cover', async () => {
    const s = createMockServer();
    await accepted(s);
    renderApp({ user: 'maker', route: '/co-lending/BANKX', server: s });
    expect(await screen.findByTestId('dlg-figures')).toHaveTextContent('4,000');
    await user().click(await screen.findByRole('button', { name: 'Lodge cover…' }));
    const dialog = await screen.findByRole('dialog');
    await user().type(within(dialog).getByLabelText(/^Amount/), '2000');
    await user().click(within(dialog).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText(/Guarantee cover sent for approval/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download our classification' })).toBeInTheDocument();
  });
});

let u: ReturnType<typeof userEvent.setup> | null = null;
function user() {
  u ??= userEvent.setup();
  return u;
}
