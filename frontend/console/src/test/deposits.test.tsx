import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { mockCall, renderApp } from './utils';

const bank = () => createMockServer({ tenant: 'demo-bank' });
const nbfc = () => createMockServer({ tenant: 'demo-nbfc-d' });
const navLinks = async () => within(await screen.findByRole('navigation', { name: 'Main' })).getAllByRole('link').map((a) => a.textContent?.replace(/\d+$/, '').trim());
const customer = (server: MockServer, first: string) => server.db.customers.find((c) => c.input.firstName === first)!;
const approveLast = async (server: MockServer, entityType: string) => {
  const a = [...server.db.approvals].reverse().find((s) => s.approval.entityType === entityType && s.approval.status === 'PENDING')!;
  return mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
};
const card = (rows: Array<[number, number, number]>) => rows.map(([fromAmount, fromMonths, ratePercent]) => ({ fromAmount, fromMonths, fromDays: 0, ratePercent }));

describe('deposits: what a tenant may offer', () => {
  it('an NBFC that takes no deposits has no deposit menu and is refused by the API', async () => {
    const server = createMockServer();
    renderApp({ user: 'maker', server });
    expect(await navLinks()).not.toContain('Term deposits');
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/deposits/products')).status).toBe(403);
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/casa/accounts')).body.detail).toBe('module CASA is not enabled for this tenant');
  });

  it('a bank sees savings and current accounts, term deposits, products and the rule set', async () => {
    renderApp({ user: 'maker', server: bank() });
    expect(await navLinks()).toEqual(expect.arrayContaining(['Savings and current', 'Term deposits', 'Rule set']));
  });

  it('a deposit-taking NBFC sees term deposits only', async () => {
    const server = nbfc();
    renderApp({ user: 'maker', server });
    const links = await navLinks();
    expect(links).toContain('Term deposits');
    expect(links).not.toContain('Savings and current');
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/casa/products')).status).toBe(403);
  });

  it('shows the rule set with what is not verified', async () => {
    const { unmount } = renderApp({ user: 'checker', route: '/deposits/rules', server: bank() });
    expect(await screen.findByTestId('rule-kind')).toHaveTextContent('Bank');
    expect(screen.getByText(/15 of 15 rules are not verified/)).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Rules in force' })).getAllByRole('row')).toHaveLength(16);
    unmount();
    renderApp({ user: 'checker', route: '/deposits/rules', server: nbfc() });
    expect(await screen.findByTestId('rule-kind')).toHaveTextContent('Deposit-taking NBFC');
    expect(screen.getByText('Term deposit: rate ceiling (% a year)').closest('tr')).toHaveTextContent('12.5');
    expect(screen.getByText('Deposits are insured').closest('tr')).toHaveTextContent('No');
  });
});

describe('deposit products', () => {
  it('lists the products of both families and shows a rate card as a grid', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'checker', route: '/deposits/products', server: bank() });
    const savings = await screen.findByRole('table', { name: 'Savings and current products' });
    expect(within(savings).getByText('SB01').closest('tr')).toHaveTextContent('2.75% to 3.25%, credited quarterly');
    expect(within(savings).getByText('CA01').closest('tr')).toHaveTextContent('No interest');
    expect(screen.queryByRole('button', { name: 'New term deposit product' })).not.toBeInTheDocument();
    await user.click(within(screen.getByRole('table', { name: 'Term deposit products' })).getByRole('link', { name: 'FDC01' }));
    const grid = await screen.findByRole('table', { name: 'Rate card' });
    expect(within(grid).getAllByRole('row')).toHaveLength(4);
    expect(within(grid).getByRole('row', { name: /36 months/ })).toHaveTextContent('7.25%7.35%');
    expect(screen.getByTestId('product-addons')).toHaveTextContent('SENIOR_CITIZEN +0.5%');
    expect(screen.getByTestId('product-premature')).toHaveTextContent('less 1 point(s); no interest under 7 days');
    expect(within(screen.getByRole('table', { name: 'Versions of this product' })).getAllByRole('row')).toHaveLength(2);
  });

  it('checks a term deposit product before it is proposed: every amount and tenure it accepts needs a rate', async () => {
    const user = userEvent.setup();
    const server = bank();
    renderApp({ user: 'maker', route: '/deposits/products/term/new', server });
    await user.type(await screen.findByLabelText(/^Code/), 'FDT9');
    await user.type(screen.getByLabelText(/^Name/), 'CLAUDE-TEST deposit');
    await user.type(screen.getByLabelText(/^Smallest deposit/), '1000');
    await user.clear(screen.getByLabelText('Shortest tenure: months'));
    await user.type(screen.getByLabelText('Shortest tenure: months'), '6');
    await user.type(screen.getByLabelText('Rate for amount step 1, tenure step 1'), '7');
    await user.type(screen.getByLabelText(/^Penalty/), '1');
    await user.type(screen.getByLabelText(/^No interest when closed within/), '7');
    await user.click(screen.getByRole('button', { name: 'Check product' }));
    expect(await within(await screen.findByRole('list', { name: 'Problems' })).findByText('no rate for tenures shorter than 12 months; the product accepts 6 months')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add tenure step' }));
    await user.type(screen.getByLabelText('Tenure step 2: months'), '6');
    await user.type(screen.getByLabelText('Rate for amount step 1, tenure step 2'), '6.5');
    await user.click(screen.getByRole('button', { name: 'Check product' }));
    expect(await screen.findByText('Nothing in the rule set stops this product.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Deposit product sent for approval.')).toBeInTheDocument();
    expect(server.db.deposits.products.some((p) => p.code === 'FDT9')).toBe(false);
    expect((await approveLast(server, 'DEPOSIT_PRODUCT')).body).toMatchObject({ status: 'APPROVED', appliedRef: 'FDT9' });
    expect(server.db.deposits.products.find((p) => p.code === 'FDT9')).toMatchObject({ version: 1, family: 'TERM_DEPOSIT', rateCard: [{ fromMonths: 6, ratePercent: '6.5' }, { fromMonths: 12, ratePercent: '7' }], prematurePenaltyPercent: '1', glLiability: '2403' });
  });

  it('holds a product to the rule set of the institution', async () => {
    const b = bank();
    const savings = (second: number, mode = 'INCREMENTAL') => ({ code: 'SB09', name: 'CLAUDE-TEST', productType: 'SAVINGS', bandMode: mode, interestBands: [{ fromBalance: 0, ratePercent: 2.5 }, { fromBalance: second, ratePercent: 3 }] });
    expect((await mockCall(b, 'maker', 'POST', '/api/v1/casa/products', savings(50000))).body.problems).toEqual(['one rate must apply to balances up to 100000; the second band starts at 50000']);
    expect((await mockCall(b, 'maker', 'POST', '/api/v1/casa/products', savings(100000, 'WHOLE_BALANCE'))).status).toBe(422);
    expect((await mockCall(b, 'maker', 'POST', '/api/v1/casa/products', savings(100000))).status).toBe(202);
    expect((await mockCall(b, 'maker', 'POST', '/api/v1/casa/products', { code: 'CA09', name: 'CLAUDE-TEST', productType: 'CURRENT', interestBands: [{ fromBalance: 0, ratePercent: 1 }] })).body.problems).toEqual(['a current account bears no interest; remove the interest bands']);
    expect((await mockCall(b, 'checker', 'POST', '/api/v1/casa/products', savings(100000))).status).toBe(403);
    expect((await mockCall(b, 'maker', 'POST', '/api/v1/deposits/products', { code: 'SB01', name: 'CLAUDE-TEST', productType: 'FD_PAYOUT', minAmount: 1, minTenureMonths: 12, maxTenureMonths: 12, interestPeriodMonths: 0, rateCard: card([[0, 12, 7]]), prematurePenaltyPercent: 0, prematureMinDays: 0 })).body.detail).toMatch(/family and type of a deposit product never change/);

    const n = nbfc();
    const fd = (over: Record<string, unknown>) => ({ code: 'FD09', name: 'CLAUDE-TEST', productType: 'FD_CUMULATIVE', minAmount: 10000, minTenureMonths: 12, maxTenureMonths: 60, interestPeriodMonths: 12, rateCard: card([[0, 12, 9]]), ...over });
    const refused = await mockCall(n, 'maker', 'POST', '/api/v1/deposits/products/check', fd({ minTenureMonths: 6, maxTenureMonths: 72, rateCard: card([[0, 6, 12.4]]), addOns: [{ code: 'SENIOR_CITIZEN', percent: 0.25 }], prematurePenaltyPercent: 1, prematureMinDays: 7 }));
    expect(refused.body.problems).toEqual([
      'the highest rate 12.4% with every add-on (0.25%) is 12.65%, above the ceiling of 12.5%',
      'the tenure 6 months is shorter than 12 months',
      'the tenure 72 months is longer than 60 months',
      'the interest on premature repayment is fixed by the rule set for this institution; leave the penalty and the minimum period empty',
    ]);
    expect((await mockCall(n, 'maker', 'POST', '/api/v1/deposits/products/check', fd({}))).body.problems).toEqual([]);
  });

  it('withdraws a product through an approval; a withdrawn product takes no new account', async () => {
    const user = userEvent.setup();
    const server = bank();
    renderApp({ user: 'maker', route: '/deposits/products/casa/CA01', server });
    await user.click(await screen.findByRole('button', { name: 'Withdraw' }));
    const d = await screen.findByRole('dialog', { name: 'Withdraw product CA01' });
    await user.type(within(d).getByLabelText(/^Reason/), 'CLAUDE-TEST no longer offered');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Withdrawal sent for approval.')).toBeInTheDocument();
    expect((await approveLast(server, 'DEPOSIT_PRODUCT')).status).toBe(200);
    expect(server.db.deposits.products.find((p) => p.code === 'CA01')).toMatchObject({ status: 'WITHDRAWN', version: 2 });
    const refused = await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', { productCode: 'CA01', customerId: customer(server, 'Anu').id, branchCode: 'HO' });
    expect(refused.body.problems).toEqual(['product CA01 is WITHDRAWN; it takes no new account']);
  });
});

describe('savings and current accounts', () => {
  it('opens a joint savings account with nominees; it exists only after approval', async () => {
    const user = userEvent.setup();
    const server = bank();
    renderApp({ user: 'maker', route: '/deposits/casa', server });
    expect(await screen.findByText('No accounts yet')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Open an account' }));
    await screen.findByRole('option', { name: /^SB01/ });
    await user.selectOptions(screen.getByLabelText(/^Product/), 'SB01');
    await user.click(screen.getByRole('button', { name: 'Find' }));
    await user.click(await screen.findByRole('button', { name: 'Select Anu CLAUDE-TEST' }));
    await user.click(screen.getByRole('button', { name: 'Find' }));
    await user.click(await screen.findByRole('button', { name: 'Select Biju CLAUDE-TEST' }));
    expect(screen.getByLabelText('Operating mode')).toHaveValue('EITHER_OR_SURVIVOR');
    await user.selectOptions(screen.getByLabelText('Nomination'), 'SIMULTANEOUS');
    await user.type(screen.getByLabelText(/^Nominee 1 name/), 'CLAUDE-TEST Nominee One');
    await user.clear(screen.getByLabelText(/^Share %/));
    await user.type(screen.getByLabelText(/^Share %/), '60');
    expect(screen.getByTestId('share-total')).toHaveTextContent('Shares total 60%: they must total 100%');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await within(await screen.findByRole('list', { name: 'Problems' })).findByText('nominee shares must total 100 percent; they total 60')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add nominee' }));
    await user.type(screen.getByLabelText(/^Nominee 2 name/), 'CLAUDE-TEST Nominee Two');
    await user.type(within(screen.getByRole('group', { name: 'Nominee 2' })).getByLabelText(/^Share %/), '40');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Account opening sent for approval.')).toBeInTheDocument();
    expect(server.db.deposits.accounts).toHaveLength(0);
    const approved = await approveLast(server, 'CASA_ACCOUNT');
    expect(approved.body.appliedRef).toMatch(/^2001\d{10}$/);
    expect(server.db.deposits.accounts[0]).toMatchObject({ status: 'ACTIVE', operatingMode: 'EITHER_OR_SURVIVOR', nextInterestDate: '2026-06-30', holders: [{ holderNo: 1, name: 'Anu CLAUDE-TEST' }, { holderNo: 2, name: 'Biju CLAUDE-TEST' }], nominees: [{ sharePercent: '60' }, { sharePercent: '40' }] });
  });

  it('refuses an opening with every reason at once', async () => {
    const server = bank();
    const res = await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', {
      productCode: 'SB01', customerId: customer(server, 'Chitra').id, jointHolders: [customer(server, 'Anu').id, customer(server, 'Biju').id], branchCode: 'HO',
      operatingMode: 'EITHER_OR_SURVIVOR', nominationMode: 'SUCCESSIVE', nominees: [{ name: 'CLAUDE-TEST N', relationship: 'SPOUSE', sharePercent: 100 }],
    });
    expect(res.status).toBe(422);
    expect(res.body.problems).toEqual([
      expect.stringMatching(/^the KYC of customer \d+ is PENDING; an account is opened only for a customer whose KYC is verified$/),
      'operating mode EITHER_OR_SURVIVOR needs exactly 2 holders; 3 named',
      'successive nomination needs two nominees or more; name one nominee as SIMULTANEOUS with 100%',
      'nominee 1: successive nominees have no shares; the order named decides',
    ]);
    expect((await mockCall(server, 'checker', 'POST', '/api/v1/casa/accounts', { productCode: 'SB01', customerId: customer(server, 'Anu').id, branchCode: 'HO' })).status).toBe(403);
  });

  it('shows an account with its holders, nominees and next interest date', async () => {
    const server = bank();
    await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', { productCode: 'CA01', customerId: customer(server, 'Hari').id, branchCode: 'HO' });
    await approveLast(server, 'CASA_ACCOUNT');
    const a = server.db.deposits.accounts[0];
    renderApp({ user: 'auditor', route: `/deposits/casa/${a.id}`, server });
    expect(await screen.findByRole('heading', { name: `Current account ${a.accountNo}` })).toBeInTheDocument();
    expect(screen.getByTestId('next-interest')).toHaveTextContent('None: this account bears no interest');
    expect(within(screen.getByRole('table', { name: 'Holders' })).getByText('Hari CLAUDE-TEST')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Nomination: no nominee' })).toBeInTheDocument();
  });
});

describe('term deposits', () => {
  it('previews a deposit with the engine\'s figures and books it as proposed', async () => {
    const user = userEvent.setup();
    const server = bank();
    renderApp({ user: 'maker', route: '/deposits/term/new', server });
    await screen.findByRole('option', { name: /^FDC01/ });
    await user.selectOptions(screen.getByLabelText(/^Product/), 'FDC01');
    await user.type(screen.getByLabelText(/^Amount/), '100000');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await screen.findByTestId('preview-rate')).toHaveTextContent('7.1%');
    expect(screen.getByTestId('preview-maturity')).toHaveTextContent('30 Jun 2027');
    expect(screen.getByTestId('preview-value')).toHaveTextContent('1,07,291.29');
    expect(screen.getByTestId('preview-tax')).toHaveTextContent('0.00');
    const rows = within(screen.getByRole('table', { name: 'Interest added to the deposit' })).getAllByRole('row');
    expect(rows).toHaveLength(5);
    expect(rows[4]).toHaveTextContent('1,05,420.08');
    expect(rows[4]).toHaveTextContent('1,871.21');
    expect(screen.getByRole('button', { name: 'Submit for approval' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Find' }));
    await user.click(await screen.findByRole('button', { name: 'Select Hari CLAUDE-TEST' }));
    await user.click(screen.getByLabelText(/^Senior citizen/));
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await within(await screen.findByRole('list', { name: 'Problems' })).findByText('the senior citizen add-on is for an individual aged 60 or more on the start date')).toBeInTheDocument();
    await user.click(screen.getByLabelText(/^Senior citizen/));
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Term deposit sent for approval.')).toBeInTheDocument();
    const pending = server.db.approvals.find((s) => s.approval.entityType === 'TERM_DEPOSIT')!;
    expect(pending.approval).toMatchObject({ action: 'BOOK', amount: '100000', proposed: { quotedRatePercent: '7.1', payoutTo: 'BANK_ACCOUNT', maturityInstruction: 'PAY_OUT' } });
    const approved = await approveLast(server, 'TERM_DEPOSIT');
    expect(approved.body.appliedRef).toMatch(/^3001\d{10}$/);
    expect(server.db.deposits.accounts[0]).toMatchObject({ status: 'BOOKED', termDeposit: { ratePercent: '7.1', cardFromMonths: 12, startDate: null, funded: false, totalInterest: '7291.29', maturityValue: '107291.29' } });
  });

  it('shows a booked deposit: waiting for funds, rate with its parts, payout account', async () => {
    const server = bank();
    const anu = customer(server, 'Anu').id;
    await mockCall(server, 'maker', 'POST', '/api/v1/casa/accounts', { productCode: 'SB01', customerId: anu, branchCode: 'HO' });
    await approveLast(server, 'CASA_ACCOUNT');
    const savings = server.db.deposits.accounts[0];
    const booked = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FDC01', customerId: anu, branchCode: 'HO', amount: 600000, tenureMonths: 36, payoutTo: 'CASA', payoutAccountId: savings.id, maturityInstruction: 'RENEW_WITH_INTEREST' });
    expect(booked.status).toBe(202);
    await approveLast(server, 'TERM_DEPOSIT');
    const dep = server.db.deposits.accounts[0];
    renderApp({ user: 'checker', route: `/deposits/term/${dep.id}`, server });
    expect(await screen.findByRole('heading', { name: `Term deposit ${dep.accountNo}` })).toBeInTheDocument();
    expect(screen.getByText(/waits for its funds/)).toBeInTheDocument();
    expect(screen.getByTestId('deposit-rate')).toHaveTextContent('7.35% a year');
    expect(screen.getByTestId('deposit-rate')).toHaveTextContent('amount from 5,00,000, tenure from 36 months');
    expect(screen.getByText(savings.accountNo!)).toBeInTheDocument();
    expect(screen.getByText('Renew the principal with the interest')).toBeInTheDocument();
    // someone else's account cannot take the proceeds
    const other = await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FDC01', customerId: customer(server, 'Hari').id, branchCode: 'HO', amount: 10000, tenureMonths: 12, payoutTo: 'CASA', payoutAccountId: savings.id });
    expect(other.body.problems).toEqual([`account ${savings.accountNo} is not held by the depositor`]);
  });

  it('refuses the approval when the product no longer gives the rate that was shown', async () => {
    const server = bank();
    await mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FDC01', customerId: customer(server, 'Anu').id, branchCode: 'HO', amount: 100000, tenureMonths: 12 });
    const p = server.db.deposits.products.find((x) => x.code === 'FDC01')!;
    const changed = { code: p.code, name: p.name, productType: p.productType, minAmount: p.minAmount, minTenureMonths: p.minTenureMonths, maxTenureMonths: p.maxTenureMonths, interestPeriodMonths: p.interestPeriodMonths,
      rateCard: p.rateCard!.map((c) => (c.fromMonths === 12 && c.fromAmount === '0' ? { ...c, ratePercent: '7.4' } : c)), addOns: p.addOns, prematurePenaltyPercent: 1, prematureMinDays: 7, autoRenewalAllowed: true };
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/deposits/products', changed)).status).toBe(202);
    expect((await approveLast(server, 'DEPOSIT_PRODUCT')).status).toBe(200);
    const stale = await approveLast(server, 'TERM_DEPOSIT');
    expect(stale.status).toBe(409);
    expect(stale.body.detail).toMatch(/is now 7.4%, not the 7.1% shown when it was proposed/);
    expect(server.db.deposits.accounts).toHaveLength(0);
  });

  it('holds a deposit-taking NBFC to its rule set: tenure, nominees, renewal, and deposits are not insured', async () => {
    const server = nbfc();
    const anu = customer(server, 'Anu').id;
    const preview = (over: Record<string, unknown>) => mockCall(server, 'maker', 'POST', '/api/v1/deposits/preview', { productCode: 'FD01', amount: 50000, tenureMonths: 24, ...over });
    expect((await preview({ tenureMonths: 6 })).body.problems).toEqual(["the tenure 6 months is shorter than the product's shortest tenure of 12 months"]);
    expect((await preview({ tenureMonths: 61 })).body.problems).toEqual(["the tenure 61 months is longer than the product's longest tenure of 60 months"]);
    // the same figures as the backend's engine for 50,000 at 8.75% over 24 months and 76 days, compounded yearly
    const q = (await preview({ tenureDays: 76 })).body;
    expect(q).toMatchObject({ ratePercent: '8.75', maturityDate: '2028-09-14', totalInterest: '10210.16', maturityValue: '60210.16', annualisedYieldPercent: '9.24', insured: false, totalTax: '0.00' });
    expect(q.periods.map((p: { interest: string; whole: boolean }) => [p.interest, p.whole])).toEqual([['4375.00', true], ['4757.81', true], ['1077.35', false]]);
    const book = (over: Record<string, unknown>) => mockCall(server, 'maker', 'POST', '/api/v1/deposits/accounts', { productCode: 'FD01', customerId: anu, branchCode: 'HO', amount: 50000, tenureMonths: 24, ...over });
    const two = [{ name: 'CLAUDE-TEST A', relationship: 'SPOUSE', sharePercent: 50 }, { name: 'CLAUDE-TEST B', relationship: 'CHILD', sharePercent: 50 }];
    expect((await book({ nominationMode: 'SIMULTANEOUS', nominees: two, maturityInstruction: 'RENEW_PRINCIPAL', payoutTo: 'CASA' })).body.problems).toEqual([
      '2 nominees named; at most 1 allowed',
      'name the savings or current account that interest and maturity proceeds are paid to',
      'product FD01 does not allow renewal at maturity',
      "a renewal needs the depositor's consent, recorded when the deposit matures; book the deposit to be paid out",
    ]);
    expect((await book({ nominationMode: 'SIMULTANEOUS', nominees: [{ name: 'CLAUDE-TEST A', relationship: 'SPOUSE', sharePercent: 100 }] })).status).toBe(202);
    expect((await approveLast(server, 'TERM_DEPOSIT')).body.appliedRef).toMatch(/^3001/);

    renderApp({ user: 'maker', route: '/deposits/term/new', server });
    expect(await screen.findByText(/Deposits with this institution are not insured/)).toBeInTheDocument();
    expect(await screen.findByText('At most 1 nominee for this institution.')).toBeInTheDocument();
    expect(screen.getByText('A renewal needs the depositor’s consent, recorded when the deposit matures.')).toBeInTheDocument();
  });

  it('a product that deducts no tax shows none; one that does projects it by tax year', async () => {
    const server = bank();
    const big = (await mockCall(server, 'maker', 'POST', '/api/v1/deposits/preview', { productCode: 'FDC01', amount: 2000000, tenureMonths: 12 })).body;
    // 20,00,000 at 7.2% (the engine's figures): the second credit takes the tax year's interest past 50,000, so it carries
    // the tax on all of it; the fourth credit falls in the next tax year and is under the threshold on its own
    expect(big.tax.map((t: { tax: string }) => t.tax)).toEqual(['0.00', '7264.80', '3730.77', '0.00']);
    expect(big.totalTax).toBe('10995.57');
    const p = server.db.deposits.products.find((x) => x.code === 'FDC01')!;
    p.tdsApplicable = false;
    const none = (await mockCall(server, 'maker', 'POST', '/api/v1/deposits/preview', { productCode: 'FDC01', amount: 2000000, tenureMonths: 12 })).body;
    expect(none).toMatchObject({ tdsApplicable: false, tax: [], totalTax: null, taxNote: null });
  });
});
