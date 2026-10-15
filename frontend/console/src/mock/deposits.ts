/**
 * Mock of the deposits API (Phase 4, P4-1; money movement P4-2 in depositMoney.ts): the rule set by institution type, savings, current and term deposit
 * products, account opening and term deposit booking. The checks and the figures follow the backend's deposit
 * engine closely enough to try the screens; the backend is the truth. Not mirrored here: GL heads of a product are
 * not checked against the chart, ACTUAL_ACTUAL counts a year as 365 days, and branch scope is not applied to lists.
 */
import type {
  CasaAccountInput,
  DepositAccount,
  DepositAccountSummary,
  DepositFamily,
  DepositNomineeInput,
  DepositProduct,
  DepositProductInput,
  DepositProductVersion,
  DepositRuleSet,
  TermDepositInput,
  TermDepositPreview,
} from '../api/types';
import { DEMO_TENANTS, type DemoTenantCode, type DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { addDays, ageOn, toUtcDate, fromUtcDate } from '../lib/dates';
import { fromUnits, toUnits } from '../lib/money';
import { appendAudit, uuid, type ApprovalPayload, type MockDb } from './db';
import { BANK_RULES, NBFC_DEPOSIT_RULES, type MockDepositRule } from './depositRules';
import { applyDepositMoneyApproval, balanceView, onHold, registerDepositMoneyRoutes, type MockDepositOrder, type MockHold, type MockMovement } from './depositMoney';
import { conflict, HttpProblem, notFound, PROBLEM_BASE } from './problems';

export type DepositKind = 'BANK' | 'NBFC_DEPOSIT' | null;

/** An opening as proposed: what the approval carries and what is applied when it is approved. */
export interface MockOpening {
  family: DepositFamily;
  productCode: string;
  holders: string[];
  branchCode: string;
  operatingMode: string;
  nominationMode: string;
  nominees: Array<{ name: string; relationship: string; sharePercent: string | null; minor: boolean; guardianName: string | null }>;
  amount: string | null;
  tenureMonths: number | null;
  tenureDays: number | null;
  addOns: string[];
  payoutTo: string | null;
  payoutAccountId: string | null;
  maturityInstruction: string | null;
  quotedRatePercent: string | null;
  customerNo: string;
  customerName: string;
}

export interface DepositsDb {
  kind: DepositKind;
  modules: string[];
  products: DepositProduct[];
  history: Array<DepositProductVersion & { code: string }>;
  accounts: DepositAccount[];
  casaSeq: number;
  termSeq: number;
  /** P4-2: every amount into or out of an account, its freezes and liens, and the balances the movements make. */
  movements: MockMovement[];
  holds: MockHold[];
  balances: Record<string, string>;
  movementSeq: number;
  /** P4-2b: payment links for deposit accounts (the simulator pays them when they are next listed). */
  orders: MockDepositOrder[];
}

type Result = { status: number; body: unknown };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
}
interface Registrar {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (user: DemoUser, entityType: string, action: string, payload: ApprovalPayload, proposed: Record<string, unknown>, current?: Record<string, unknown> | null, entityId?: string | null, amount?: string | null) => Result;
}

const ok = (body: unknown): Result => ({ status: 200, body });
const invalid = (detail: string) => new HttpProblem(422, 'Validation failed', detail, {}, PROBLEM_BASE + 'validation');
function refuseIfAny(what: string, problems: string[]): void {
  const distinct = [...new Set(problems)];
  if (distinct.length) throw new HttpProblem(422, 'Validation failed', `${what}: ${distinct.join('; ')}`, { problems: distinct }, PROBLEM_BASE + 'validation');
}

// ---------- tenants ----------

const TENANT_KIND: Record<DemoTenantCode, { kind: DepositKind; modules: string[]; entityType: string }> = {
  'demo-nbfc': { kind: null, modules: [], entityType: 'NBFC' },
  'demo-bank': { kind: 'BANK', modules: ['CASA', 'TD'], entityType: 'BANK' },
  'demo-nbfc-d': { kind: 'NBFC_DEPOSIT', modules: ['TD'], entityType: 'NBFC' },
};

export function emptyDeposits(): DepositsDb {
  return { kind: null, modules: [], products: [], history: [], accounts: [], casaSeq: 0, termSeq: 0, movements: [], holds: [], balances: {}, movementSeq: 0, orders: [] };
}

/** Makes the mock database the given demo tenant: its name, its deposit modules and its demo deposit products. */
export function applyTenant(db: MockDb, code: DemoTenantCode): void {
  const t = DEMO_TENANTS.find((x) => x.code === code) ?? DEMO_TENANTS[0];
  const k = TENANT_KIND[t.code];
  db.tenant = t.code;
  db.tenantName = t.name;
  db.deposits = { ...emptyDeposits(), kind: k.kind, modules: [...k.modules] };
  for (const p of k.kind === 'BANK' ? BANK_PRODUCTS : k.kind === 'NBFC_DEPOSIT' ? NBFC_PRODUCTS : []) {
    storeProduct(db, normalise(db, p.productType === 'SAVINGS' || p.productType === 'CURRENT' ? 'CASA' : 'TERM_DEPOSIT', p), 'bootstrap', null);
  }
}

const card = (rows: Array<[number, number, number, number]>) => rows.map(([fromAmount, fromMonths, fromDays, ratePercent]) => ({ fromAmount, fromMonths, fromDays, ratePercent }));

/** The demo products of the local stack (DemoDepositSeeder): demo figures, not anyone's rate card. */
const BANK_PRODUCTS: DepositProductInput[] = [
  { code: 'SB01', name: 'Demo savings account (demo data)', productType: 'SAVINGS', minBalance: 1000, bandMode: 'INCREMENTAL', interestPostingMonths: 3, chequeFacility: true,
    interestBands: [{ fromBalance: 0, ratePercent: 2.75 }, { fromBalance: 100000, ratePercent: 3.25 }] },
  { code: 'CA01', name: 'Demo current account (demo data)', productType: 'CURRENT', minBalance: 10000, chequeFacility: true },
  { code: 'FDC01', name: 'Demo fixed deposit, cumulative (demo data)', productType: 'FD_CUMULATIVE', minAmount: 5000, minTenureMonths: 6, maxTenureMonths: 120, interestPeriodMonths: 3,
    rateCard: card([[0, 6, 0, 6.25], [0, 12, 0, 7.1], [0, 36, 0, 7.25], [500000, 6, 0, 6.35], [500000, 12, 0, 7.2], [500000, 36, 0, 7.35]]),
    addOns: [{ code: 'SENIOR_CITIZEN', percent: 0.5 }], prematureAllowed: true, prematurePenaltyPercent: 1, prematureMinDays: 7, autoRenewalAllowed: true },
  { code: 'FDP01', name: 'Demo fixed deposit, monthly payout (demo data)', productType: 'FD_PAYOUT', minAmount: 5000, minTenureDays: 46, maxTenureMonths: 120, interestPeriodMonths: 1,
    rateCard: card([[0, 0, 46, 5], [0, 6, 0, 6], [0, 12, 0, 6.9], [0, 36, 0, 7]]),
    addOns: [{ code: 'SENIOR_CITIZEN', percent: 0.5 }], prematureAllowed: true, prematurePenaltyPercent: 1, prematureMinDays: 7, autoRenewalAllowed: true },
];
const NBFC_PRODUCTS: DepositProductInput[] = [
  { code: 'FD01', name: 'Demo public deposit, cumulative (demo data)', productType: 'FD_CUMULATIVE', minAmount: 10000, minTenureMonths: 12, maxTenureMonths: 60, interestPeriodMonths: 12,
    rateCard: card([[0, 12, 0, 8.5], [0, 24, 0, 8.75], [0, 36, 0, 9]]), addOns: [{ code: 'SENIOR_CITIZEN', percent: 0.25 }], prematureAllowed: true },
  { code: 'FD02', name: 'Demo public deposit, quarterly payout (demo data)', productType: 'FD_PAYOUT', minAmount: 10000, minTenureMonths: 12, maxTenureMonths: 60, interestPeriodMonths: 3,
    rateCard: card([[0, 12, 0, 8.5], [0, 24, 0, 8.75], [0, 36, 0, 9]]), addOns: [{ code: 'SENIOR_CITIZEN', percent: 0.25 }], prematureAllowed: true },
];

// ---------- rules ----------

const rulesOf = (kind: DepositKind): MockDepositRule[] => (kind === 'BANK' ? BANK_RULES : kind === 'NBFC_DEPOSIT' ? NBFC_DEPOSIT_RULES : []);
function rule(db: MockDb, code: string): number | null {
  const r = rulesOf(db.deposits.kind).find((x) => x.code === code);
  return r ? Number(r.value) : null;
}

function requireKind(db: MockDb): void {
  if (!db.deposits.kind) throw conflict('Conflict', 'this NBFC is not recorded as registered by RBI to accept public deposits');
}

// ---------- numbers and dates ----------

const plain = (v: number | string | null | undefined): string | null => {
  if (v === null || v === undefined || v === '') return null;
  const s = String(v);
  if (!/^-?\d+(\.\d+)?$/.test(s)) throw invalid('a number is expected');
  return s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '') : s;
};
const num = (v: string | null | undefined) => (v === null || v === undefined ? 0 : Number(v));
const tenureRank = (months: number, days: number) => months * 31 + days;
const tenureText = (months: number, days: number) => (months === 0 ? `${days} days` : days === 0 ? `${months} months` : `${months} months ${days} days`);

/** As java.time.LocalDate.plusMonths: the day is kept, or pulled back to the last day of a shorter month. */
export function addMonths(iso: string, months: number): string {
  const d = toUtcDate(iso);
  const day = d.getUTCDate();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() + months);
  const last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
  d.setUTCDate(Math.min(day, last));
  return fromUtcDate(d);
}
const addTenure = (start: string, months: number, days: number) => addDays(addMonths(start, months), days);
const daysBetween = (from: string, to: string) => Math.round((toUtcDate(to).getTime() - toUtcDate(from).getTime()) / 86_400_000);

/** A decimal string as an integer scaled by 10^places. */
function scaled(v: string, places: number): bigint {
  const negative = v.startsWith('-');
  const [i, f = ''] = (negative ? v.slice(1) : v).split('.');
  const n = BigInt(i) * 10n ** BigInt(places) + BigInt(f.padEnd(places, '0').slice(0, places));
  return negative ? -n : n;
}

/** num / den in money units (1/10000), rounded as the product says. */
function roundUnits(numerator: bigint, denominator: bigint, rounding: string): bigint {
  const step = rounding.startsWith('RUPEE') ? 10000n : 100n;
  const d = denominator * step;
  const q = numerator / d;
  const r = numerator % d;
  let up = false;
  if (rounding.endsWith('_UP') && !rounding.endsWith('HALF_UP')) up = r > 0n;
  else if (rounding.endsWith('HALF_UP')) up = r * 2n >= d;
  else if (rounding.endsWith('HALF_EVEN')) up = r * 2n > d || (r * 2n === d && q % 2n === 1n);
  return (q + (up ? 1n : 0n)) * step;
}

interface Terms {
  principal: string;
  ratePercent: string;
  start: string;
  maturity: string;
  periodMonths: number;
  cumulative: boolean;
  dayCount: string;
  rounding: string;
}
interface Period {
  number: number;
  from: string;
  to: string;
  balance: bigint;
  interest: bigint;
  whole: boolean;
}

/** As TermDeposit.periods: whole periods earn principal x rate x months / 12; a last part period earns for its days. */
export function periods(t: Terms): Period[] {
  const out: Period[] = [];
  let balance = toUnits(t.principal);
  const rate = scaled(t.ratePercent, 6);
  const basis = t.dayCount === 'ACTUAL_360' ? 360n : 365n;
  let from = t.start;
  let n = 0;
  while (from < t.maturity) {
    n += 1;
    let end = t.periodMonths === 0 ? t.maturity : addMonths(t.start, n * t.periodMonths);
    const whole = t.periodMonths !== 0 && end <= t.maturity;
    if (!whole) end = t.maturity;
    const interest = whole
      ? roundUnits(balance * rate * BigInt(t.periodMonths), 12n * 100n * 1_000_000n, t.rounding)
      : roundUnits(balance * rate * BigInt(daysBetween(from, end)), basis * 100n * 1_000_000n, t.rounding);
    out.push({ number: n, from, to: end, balance, interest, whole });
    if (t.cumulative) balance += interest;
    from = end;
  }
  return out;
}

// ---------- products ----------

const CASA_TYPES = ['SAVINGS', 'CURRENT'];
const TD_TYPES = ['FD_CUMULATIVE', 'FD_PAYOUT'];

function money(what: string, v: string | null, zeroAllowed: boolean): void {
  if (v === null) return;
  if (Number(v) < 0 || (!zeroAllowed && Number(v) === 0) || (v.split('.')[1] ?? '').length > 2) {
    throw invalid(`${what} is an amount ${zeroAllowed ? 'from 0' : 'above 0'} with at most two decimals`);
  }
}
function percent(what: string, v: string | null, signed: boolean): void {
  if (v === null) return;
  if ((!signed && Number(v) < 0) || Math.abs(Number(v)) > 100 || (v.split('.')[1] ?? '').length > 4) {
    throw invalid(`${what} is a percentage ${signed ? 'from -100 to 100' : 'from 0 to 100'} with at most four decimals`);
  }
}
const whole = (what: string, v: number | null | undefined, max: number): number => {
  const n = v ?? 0;
  if (!Number.isInteger(n) || n < 0 || n > max) throw invalid(`${what} is a whole number from 0 to ${max}`);
  return n;
};

/** The shape of the input, with defaults filled in (DepositProductService.normalise). */
export function normalise(db: MockDb, family: DepositFamily, b: Partial<DepositProductInput> | null | undefined): DepositProduct {
  const input = b ?? {};
  if (!/^[A-Z0-9]{2,12}$/.test(input.code ?? '')) throw invalid('code is 2 to 12 capital letters or digits');
  if (!input.name?.trim() || input.name.length > 200) throw invalid('name is required (at most 200 characters)');
  const casa = family === 'CASA';
  const type = (input.productType ?? '').toUpperCase();
  if (!(casa ? CASA_TYPES : TD_TYPES).includes(type)) throw invalid(casa ? 'productType is SAVINGS or CURRENT' : 'productType is FD_CUMULATIVE or FD_PAYOUT');
  const base = {
    code: input.code!,
    name: input.name.trim(),
    family,
    productType: type as DepositProduct['productType'],
    status: 'ACTIVE' as const,
    version: null,
    effectiveFrom: input.effectiveFrom ?? db.businessDate,
    rounding: input.rounding ?? 'PAISE_HALF_UP',
    updatedBy: null,
    updatedAt: null,
  };
  const none = { minBalance: null, interestBands: null, bandMode: null, interestPostingMonths: null, chequeFacility: null, minAmount: null, maxAmount: null,
    minTenureMonths: null, minTenureDays: null, maxTenureMonths: null, maxTenureDays: null, interestPeriodMonths: null, rateCard: null, addOns: null,
    prematureAllowed: null, prematurePenaltyPercent: null, prematureMinDays: null, autoRenewalAllowed: null };
  if (casa) {
    if (input.minAmount != null || input.rateCard != null || input.addOns != null || input.interestPeriodMonths != null || input.prematureAllowed != null) {
      throw invalid('amount and tenure limits, the rate card, add-ons, premature withdrawal and renewal are settings of a term deposit product');
    }
    const minBalance = plain(input.minBalance ?? 0)!;
    money('minBalance', minBalance, true);
    const bands = (input.interestBands ?? []).map((x, i) => {
      const band = { fromBalance: plain(x?.fromBalance), ratePercent: plain(x?.ratePercent) };
      if (band.fromBalance === null || band.ratePercent === null) throw invalid(`interest band ${i + 1}: fromBalance and ratePercent are required`);
      money(`interest band ${i + 1}: fromBalance`, band.fromBalance, true);
      percent(`interest band ${i + 1}: ratePercent`, band.ratePercent, false);
      return { fromBalance: band.fromBalance, ratePercent: band.ratePercent };
    }).sort((x, y) => Number(x.fromBalance) - Number(y.fromBalance));
    const interest = bands.length > 0;
    if (!interest && (input.bandMode != null || input.interestPostingMonths != null)) throw invalid('bandMode and interestPostingMonths go with interest bands; this product has none');
    return { ...base, ...none, dayCount: interest ? input.dayCount ?? 'ACTUAL_365' : null,
      tdsApplicable: input.tdsApplicable ?? (interest && rule(db, 'tds.on-savings-interest') === 1),
      minBalance, interestBands: interest ? bands : null, bandMode: interest ? input.bandMode ?? 'WHOLE_BALANCE' : null,
      interestPostingMonths: interest ? input.interestPostingMonths ?? 3 : null, chequeFacility: input.chequeFacility === true,
      glLiability: input.glLiability ?? (type === 'SAVINGS' ? '2401' : '2402'), glInterestExpense: interest ? input.glInterestExpense ?? '5108' : null };
  }
  if (input.minBalance != null || input.interestBands != null || input.bandMode != null || input.interestPostingMonths != null || input.chequeFacility != null) {
    throw invalid('the minimum balance, interest bands and the cheque facility are settings of a savings or current product');
  }
  const minAmount = plain(input.minAmount);
  if (minAmount === null) throw invalid('minAmount is required');
  money('minAmount', minAmount, false);
  const maxAmount = plain(input.maxAmount);
  money('maxAmount', maxAmount, false);
  if (input.interestPeriodMonths == null) throw invalid('interestPeriodMonths is required: 1, 3, 6 or 12, or 0 for interest paid once at maturity');
  const rateCard = (input.rateCard ?? []).map((x, i) => {
    const row = { fromAmount: plain(x?.fromAmount), ratePercent: plain(x?.ratePercent) };
    if (row.fromAmount === null || row.ratePercent === null) throw invalid(`rate card row ${i + 1}: fromAmount, a tenure (fromMonths, fromDays) and ratePercent are required`);
    money(`rate card row ${i + 1}: fromAmount`, row.fromAmount, true);
    percent(`rate card row ${i + 1}: ratePercent`, row.ratePercent, false);
    return { fromAmount: row.fromAmount, fromMonths: whole(`rate card row ${i + 1}: fromMonths`, x.fromMonths, 600), fromDays: whole(`rate card row ${i + 1}: fromDays`, x.fromDays, 3660), ratePercent: row.ratePercent };
  }).sort((x, y) => Number(x.fromAmount) - Number(y.fromAmount) || tenureRank(x.fromMonths, x.fromDays) - tenureRank(y.fromMonths, y.fromDays));
  const addOns = (input.addOns ?? []).map((x) => {
    if (!/^[A-Z][A-Z0-9_]{1,29}$/.test(x?.code ?? '')) throw invalid('an add-on code is 2 to 30 capitals, digits or underscores, starting with a letter');
    const p = plain(x.percent);
    if (p === null) throw invalid(`add-on ${x.code}: percent is required`);
    percent(`add-on ${x.code}: percent`, p, true);
    return { code: x.code, percent: p };
  }).sort((x, y) => x.code.localeCompare(y.code));
  if (new Set(addOns.map((x) => x.code)).size !== addOns.length) throw invalid('an add-on is listed twice');
  const penalty = plain(input.prematurePenaltyPercent);
  percent('prematurePenaltyPercent', penalty, false);
  return { ...base, ...none, dayCount: input.dayCount ?? 'ACTUAL_365', tdsApplicable: input.tdsApplicable ?? true, minAmount, maxAmount,
    minTenureMonths: whole('minTenureMonths', input.minTenureMonths, 600), minTenureDays: whole('minTenureDays', input.minTenureDays, 3660),
    maxTenureMonths: whole('maxTenureMonths', input.maxTenureMonths, 600), maxTenureDays: whole('maxTenureDays', input.maxTenureDays, 3660),
    interestPeriodMonths: input.interestPeriodMonths, rateCard, addOns, prematureAllowed: input.prematureAllowed ?? true, prematurePenaltyPercent: penalty,
    prematureMinDays: input.prematureMinDays ?? null, autoRenewalAllowed: input.autoRenewalAllowed === true,
    glLiability: input.glLiability ?? '2403', glInterestExpense: input.glInterestExpense ?? '5107' };
}

/** Everything that stops the product from being approved under the rule set (the engine's product checks). */
export function productProblems(db: MockDb, p: DepositProduct): string[] {
  const out: string[] = [];
  const kindLabel = db.deposits.kind === 'NBFC_DEPOSIT' ? 'deposit-taking NBFC' : 'bank';
  if (p.family === 'CASA') {
    if (rule(db, 'demand-deposits-allowed') !== 1) out.push(`a ${kindLabel} cannot accept deposits repayable on demand (savings or current accounts)`);
    const bands = p.interestBands ?? [];
    if (p.productType === 'SAVINGS' && !bands.length) out.push('a savings product needs at least one interest band');
    if (!bands.length) return out;
    if (p.productType === 'CURRENT' && rule(db, 'current.interest-allowed') === 0) out.push('a current account bears no interest; remove the interest bands');
    if (![1, 3, 6, 12].includes(p.interestPostingMonths ?? 0)) out.push('interest is credited every 1, 3, 6 or 12 months');
    if (num(bands[0].fromBalance) !== 0) out.push('the first band must start at 0');
    if (new Set(bands.map((x) => num(x.fromBalance))).size !== bands.length) out.push('two bands start at the same balance');
    const uniform = rule(db, 'savings.uniform-rate-up-to');
    const second = bands.find((x) => num(x.fromBalance) > 0);
    if (uniform !== null && second && (num(second.fromBalance) < uniform || (num(second.fromBalance) === uniform && p.bandMode === 'WHOLE_BALANCE'))) {
      out.push(`one rate must apply to balances up to ${uniform}; the second band starts at ${second.fromBalance}`);
    }
    return out;
  }
  const minRank = tenureRank(p.minTenureMonths ?? 0, p.minTenureDays ?? 0);
  const maxRank = tenureRank(p.maxTenureMonths ?? 0, p.maxTenureDays ?? 0);
  const amountsOk = !(p.maxAmount !== null && p.maxAmount !== undefined && num(p.maxAmount) < num(p.minAmount));
  if (!amountsOk) out.push('the largest deposit is below the smallest');
  const tenuresOk = minRank > 0 && minRank <= maxRank;
  if (minRank === 0) out.push('the shortest and the longest tenure are required, and the shortest must be above zero');
  else if (minRank > maxRank) out.push(`the shortest tenure ${tenureText(p.minTenureMonths!, p.minTenureDays!)} is longer than the longest ${tenureText(p.maxTenureMonths!, p.maxTenureDays!)}`);
  const period = p.interestPeriodMonths ?? 0;
  const restOk = [1, 3, 6, 12].includes(period) || (period === 0 && p.productType === 'FD_PAYOUT');
  if (!restOk) out.push(p.productType === 'FD_PAYOUT' ? 'interest is paid every 1, 3, 6 or 12 months, or once at maturity (0)' : 'interest is compounded every 1, 3, 6 or 12 months');
  const cells = p.rateCard ?? [];
  if (!cells.length) out.push('the rate table has no rates');
  else if (amountsOk && tenuresOk) {
    const key = (a: string, m: number, d: number) => `${num(a)}|${m}|${d}`;
    const have = new Set<string>();
    for (const c of cells) {
      if (have.has(key(c.fromAmount!, c.fromMonths!, c.fromDays!))) out.push(`two rates for amount from ${c.fromAmount} and tenure from ${tenureText(c.fromMonths!, c.fromDays!)}`);
      have.add(key(c.fromAmount!, c.fromMonths!, c.fromDays!));
    }
    const amounts = [...new Set(cells.map((c) => num(c.fromAmount)))].sort((x, y) => x - y);
    const tenures = [...new Map(cells.map((c) => [`${c.fromMonths}|${c.fromDays}`, [c.fromMonths!, c.fromDays!] as [number, number]])).values()].sort((x, y) => tenureRank(...x) - tenureRank(...y));
    if (amounts[0] > num(p.minAmount)) out.push(`no rate for amounts below ${amounts[0]}; the product accepts deposits from ${p.minAmount}`);
    if (tenureRank(...tenures[0]) > minRank) out.push(`no rate for tenures shorter than ${tenureText(...tenures[0])}; the product accepts ${tenureText(p.minTenureMonths!, p.minTenureDays!)}`);
    for (const a of amounts) for (const t of tenures) if (!have.has(`${a}|${t[0]}|${t[1]}`)) out.push(`no rate for amount from ${a} and tenure from ${tenureText(...t)}`);
    for (const c of cells) {
      if (tenureRank(c.fromMonths!, c.fromDays!) > maxRank) out.push(`the rate from ${tenureText(c.fromMonths!, c.fromDays!)} can never apply: the longest tenure is ${tenureText(p.maxTenureMonths!, p.maxTenureDays!)}`);
    }
  }
  if (cells.length) {
    const highest = Math.max(...cells.map((c) => num(c.ratePercent)));
    const up = (p.addOns ?? []).filter((a) => num(a.percent) > 0).reduce((s, a) => s + num(a.percent), 0);
    const ceiling = rule(db, 'td.max-rate-percent');
    if (ceiling !== null && highest + up > ceiling) {
      out.push(up === 0 ? `the highest rate ${highest}% is above the ceiling of ${ceiling}%` : `the highest rate ${highest}% with every add-on (${up}%) is ${highest + up}%, above the ceiling of ${ceiling}%`);
    }
  }
  if (tenuresOk && restOk) {
    const min = rule(db, 'td.min-tenure-months');
    const max = rule(db, 'td.max-tenure-months');
    const rest = rule(db, 'td.min-compounding-rest-months');
    if (min !== null && minRank < tenureRank(min, 0)) out.push(`the tenure ${tenureText(p.minTenureMonths!, p.minTenureDays!)} is shorter than ${min} months`);
    if (max !== null && maxRank > tenureRank(max, 0)) out.push(`the tenure ${tenureText(p.maxTenureMonths!, p.maxTenureDays!)} is longer than ${max} months`);
    if (rest !== null && period !== 0 && period < rest) out.push(`interest may not be paid or compounded more often than every ${rest} month(s)`);
  }
  const fixedByRules = rule(db, 'td.premature.rate-cut-percent') !== null;
  const given = p.prematurePenaltyPercent != null || p.prematureMinDays != null;
  if (fixedByRules) {
    if (given) out.push('the interest on premature repayment is fixed by the rule set for this institution; leave the penalty and the minimum period empty');
  } else if (p.prematureAllowed) {
    if (p.prematurePenaltyPercent == null) out.push('the premature-withdrawal penalty is required (0 for none)');
    if (p.prematureMinDays == null) out.push('the minimum period before which no interest is paid is required (0 days for none)');
  } else if (given) out.push('the product does not allow premature withdrawal; leave the penalty and the minimum period empty');
  const mandatory = rule(db, 'td.premature.mandatory-up-to');
  if (mandatory !== null && !p.prematureAllowed && num(p.minAmount) <= mandatory) {
    out.push(`term deposits of individuals up to ${mandatory} must allow premature withdrawal; this product accepts deposits from ${p.minAmount}`);
  }
  return out;
}

function storeProduct(db: MockDb, p: DepositProduct, by: string, approvalId: string | null): DepositProduct {
  const i = db.deposits.products.findIndex((x) => x.code === p.code);
  const version = i >= 0 ? (db.deposits.products[i].version ?? 0) + 1 : 1;
  const stored: DepositProduct = { ...p, version, updatedBy: by, updatedAt: new Date().toISOString() };
  if (i >= 0) db.deposits.products[i] = stored;
  else db.deposits.products.push(stored);
  db.deposits.products.sort((x, y) => x.code!.localeCompare(y.code!));
  db.deposits.history.push({ code: p.code!, version, changedBy: by, changedAt: stored.updatedAt!, approvalId, name: stored.name, status: stored.status });
  return stored;
}

const findProduct = (db: MockDb, family: DepositFamily, code: string): DepositProduct => {
  const p = db.deposits.products.find((x) => x.code === code && x.family === family);
  if (!p) throw notFound(`deposit product ${code}`);
  return p;
};

// ---------- term deposit figures ----------

interface Quote {
  product: DepositProduct;
  cell: NonNullable<DepositProduct['rateCard']>[number];
  addOns: Array<{ code: string; percent: string }>;
  ratePercent: string;
  start: string;
  maturity: string;
  periods: Period[];
}

interface Depositor {
  individual: boolean;
  dateOfBirth: string | null;
  panOnFile: boolean;
}

/** As TermDepositBooking: why the deposit cannot be booked, or its rate and interest periods. */
function quote(db: MockDb, p: DepositProduct, amount: string | null, months: number, days: number, addOnCodes: string[], nominees: number, who: Depositor): { problems: string[]; quote: Quote | null } {
  const problems: string[] = [];
  let ok = true;
  if (amount === null || Number(amount) <= 0) {
    problems.push('the deposit amount must be above zero');
    ok = false;
  } else {
    if ((amount.split('.')[1] ?? '').length > 2) problems.push('the deposit amount has at most two decimal places');
    if (Number(amount) < num(p.minAmount)) (problems.push(`the amount ${amount} is below the product's smallest deposit of ${p.minAmount}`), (ok = false));
    if (p.maxAmount != null && Number(amount) > num(p.maxAmount)) (problems.push(`the amount ${amount} is above the product's largest deposit of ${p.maxAmount}`), (ok = false));
  }
  if (months + days === 0) {
    problems.push('the tenure is required');
    return { problems, quote: null };
  }
  const start = db.businessDate;
  const maturity = addTenure(start, months, days);
  if (maturity < addTenure(start, p.minTenureMonths!, p.minTenureDays!)) {
    problems.push(`the tenure ${tenureText(months, days)} is shorter than the product's shortest tenure of ${tenureText(p.minTenureMonths!, p.minTenureDays!)}`);
    ok = false;
  }
  if (maturity > addTenure(start, p.maxTenureMonths!, p.maxTenureDays!)) {
    problems.push(`the tenure ${tenureText(months, days)} is longer than the product's longest tenure of ${tenureText(p.maxTenureMonths!, p.maxTenureDays!)}`);
    ok = false;
  }
  const given: Quote['addOns'] = [];
  for (const code of [...new Set(addOnCodes)].sort()) {
    const a = (p.addOns ?? []).find((x) => x.code === code);
    if (!a) {
      problems.push(`the product has no add-on ${code}`);
      ok = false;
    } else if (code === 'SENIOR_CITIZEN') {
      const age = rule(db, 'senior-citizen-age') ?? 60;
      if (!who.individual || !who.dateOfBirth || ageOn(who.dateOfBirth, start) < age) {
        problems.push(`the senior citizen add-on is for an individual aged ${age} or more on the start date`);
        ok = false;
      } else given.push({ code, percent: a.percent! });
    } else given.push({ code, percent: a.percent! });
  }
  if (!ok || amount === null) return { problems, quote: null };
  const cells = p.rateCard ?? [];
  const step = Math.max(...cells.map((c) => num(c.fromAmount)).filter((a) => a <= Number(amount)));
  const cell = cells.filter((c) => num(c.fromAmount) === step && maturity >= addTenure(start, c.fromMonths!, c.fromDays!))
    .sort((x, y) => tenureRank(y.fromMonths!, y.fromDays!) - tenureRank(x.fromMonths!, x.fromDays!))[0];
  if (!cell) return { problems: [...problems, `no deposit rate for ${amount} from ${start} to ${maturity}`], quote: null };
  const rate = given.reduce((s, a) => s + scaled(a.percent, 6), scaled(cell.ratePercent!, 6));
  const ratePercent = plain((Number(rate) / 1_000_000).toFixed(6))!;
  const min = rule(db, 'td.min-tenure-months');
  const max = rule(db, 'td.max-tenure-months');
  const ceiling = rule(db, 'td.max-rate-percent');
  const maxNominees = rule(db, 'nominees.max');
  if (min !== null && maturity < addMonths(start, min)) problems.push(`the deposit matures on ${maturity}, before it has run ${min} months`);
  if (max !== null && maturity > addMonths(start, max)) problems.push(`the deposit matures on ${maturity}, after more than ${max} months`);
  if (ceiling !== null && Number(ratePercent) > ceiling) problems.push(`the rate ${ratePercent}% is above the ceiling of ${ceiling}%`);
  if (maxNominees !== null && nominees > maxNominees) problems.push(`${nominees} nominees named; at most ${maxNominees} allowed`);
  if (problems.length) return { problems, quote: null };
  const terms: Terms = { principal: amount, ratePercent, start, maturity, periodMonths: p.interestPeriodMonths!, cumulative: p.productType === 'FD_CUMULATIVE', dayCount: p.dayCount!, rounding: p.rounding! };
  return { problems, quote: { product: p, cell, addOns: given, ratePercent, start, maturity, periods: periods(terms) } };
}

const taxYearStarting = (iso: string) => (Number(iso.slice(5, 7)) >= 4 ? Number(iso.slice(0, 4)) : Number(iso.slice(0, 4)) - 1);

function previewOf(db: MockDb, q: Quote, amount: string, months: number, days: number, who: Depositor): TermDepositPreview {
  const p = q.product;
  const total = q.periods.reduce((s, x) => s + x.interest, 0n);
  const principal = toUnits(amount);
  const tax: NonNullable<TermDepositPreview['tax']> = [];
  let totalTax = 0n;
  if (p.tdsApplicable) {
    // As TdsOnInterest: once the year's interest passes the threshold, tax is due on the whole of it.
    const age = rule(db, 'senior-citizen-age') ?? 60;
    const rate = BigInt(Math.max(rule(db, 'tds.rate-percent') ?? 10, who.panOnFile ? 0 : rule(db, 'tds.no-pan-rate-percent') ?? 20));
    let year = 0;
    let interest = 0n;
    let deducted = 0n;
    for (const x of q.periods) {
      const y = taxYearStarting(x.to);
      if (y !== year) (year = y), (interest = 0n), (deducted = 0n);
      const senior = who.individual && !!who.dateOfBirth && ageOn(who.dateOfBirth, `${y + 1}-03-31`) >= age;
      const threshold = toUnits(String(rule(db, senior ? 'tds.threshold-senior' : 'tds.threshold') ?? 0));
      interest += x.interest;
      let due = 0n;
      if (interest > threshold) {
        due = roundUnits(interest * rate, 100n, p.rounding!) - deducted;
        if (due < 0n) due = 0n;
        if (due > x.interest) due = x.interest;
      }
      deducted += due;
      totalTax += due;
      tax.push({ period: x.number, on: x.to, taxYearStarting: y, interest: fromUnits(x.interest), tax: fromUnits(due) });
    }
  }
  const years = daysBetween(q.start, q.maturity) / (p.dayCount === 'ACTUAL_360' ? 360 : 365);
  const insured = rule(db, 'insured');
  return {
    productCode: p.code, productVersion: p.version ?? 1, amount, tenureMonths: months, tenureDays: days, startDate: q.start, maturityDate: q.maturity,
    ratePercent: q.ratePercent, cardRatePercent: q.cell.ratePercent, cardFromAmount: q.cell.fromAmount, cardFromMonths: q.cell.fromMonths, cardFromDays: q.cell.fromDays,
    addOns: q.addOns, interestPeriodMonths: p.interestPeriodMonths!, cumulative: p.productType === 'FD_CUMULATIVE',
    totalInterest: fromUnits(total), maturityValue: fromUnits(p.productType === 'FD_CUMULATIVE' ? principal + total : principal),
    annualisedYieldPercent: ((Number(total) / Number(principal) / years) * 100).toFixed(2),
    periods: q.periods.map((x) => ({ number: x.number, from: x.from, to: x.to, balance: fromUnits(x.balance), interest: fromUnits(x.interest), whole: x.whole })),
    tdsApplicable: p.tdsApplicable === true, tax, totalTax: p.tdsApplicable ? fromUnits(totalTax) : null,
    taxNote: p.tdsApplicable ? "Tax is worked out on the interest of this deposit alone. The depositor's other deposits and a declaration on file change what is deducted." : null,
    insured: insured === null ? null : insured !== 0,
  };
}

// ---------- accounts ----------

const MODES: Record<string, [number, number]> = { SINGLE: [1, 1], EITHER_OR_SURVIVOR: [2, 2], FORMER_OR_SURVIVOR: [2, 2], ANYONE_OR_SURVIVOR: [2, 4], JOINTLY: [2, 4] };
const RELATIONSHIPS = ['SPOUSE', 'CHILD', 'PARENT', 'SIBLING', 'GRANDCHILD', 'OTHER_RELATIVE', 'OTHER'];

function customerName(db: MockDb, id: string): { customerNo: string; name: string } {
  const c = db.customers.find((x) => x.id === id)!;
  return { customerNo: c.customerNo, name: [c.input.firstName, c.input.middleName, c.input.lastName].filter(Boolean).join(' ') };
}

/** Every check on an opening, all the reasons together (DepositAccountService.check). */
function checkOpening(db: MockDb, family: DepositFamily, b: Partial<CasaAccountInput & TermDepositInput> | null | undefined): { opening: MockOpening; quote: Quote | null } {
  requireKind(db);
  const input = b ?? {};
  if (!input.productCode) throw invalid('productCode is required');
  if (!input.customerId) throw invalid('customerId is required');
  if (!input.branchCode) throw invalid('branchCode is required');
  if (db.dayStatus !== 'OPEN') throw conflict('Conflict', `business day is ${db.dayStatus}; try after end of day`);
  const term = family === 'TERM_DEPOSIT';
  const product = db.deposits.products.find((x) => x.code === input.productCode && x.family === family);
  if (!product) throw invalid(`${term ? 'term deposit' : 'savings or current'} product ${input.productCode} not found`);
  const problems: string[] = [];
  if (product.status !== 'ACTIVE') problems.push(`product ${product.code} is ${product.status}; it takes no new account`);
  const branch = db.branches.find((x) => x.code === input.branchCode);
  if (!branch) problems.push(`branch ${input.branchCode} does not exist`);
  else if (branch.status && branch.status !== 'ACTIVE') problems.push(`branch ${input.branchCode} is not active`);

  const holders = [input.customerId, ...(input.jointHolders ?? [])];
  if (holders.length > 4) throw invalid('an account has at most 4 holders');
  if (new Set(holders).size !== holders.length) problems.push('a customer is named as holder twice');
  for (const id of new Set(holders)) {
    const c = db.customers.find((x) => x.id === id);
    if (!c) {
      problems.push(`customer ${id} not found`);
      continue;
    }
    if (c.status !== 'ACTIVE') problems.push(`customer ${c.customerNo} is ${c.status}; an account is opened only for an ACTIVE customer`);
    if (c.kycStatus !== 'VERIFIED') problems.push(`the KYC of customer ${c.customerNo} is ${c.kycStatus}; an account is opened only for a customer whose KYC is verified`);
    if (holders.length > 1 && c.input.customerType !== 'INDIVIDUAL') problems.push(`customer ${c.customerNo} is not an individual; a joint account is held by individuals`);
    if (c.input.customerType === 'INDIVIDUAL' && c.input.dateOfBirth && ageOn(c.input.dateOfBirth, db.businessDate) < 18) {
      problems.push(`customer ${c.customerNo} is under 18; accounts for minors are not opened yet`);
    }
  }
  const mode = (input.operatingMode ?? (holders.length === 1 ? 'SINGLE' : '')).toUpperCase();
  if (!mode) problems.push('the operating mode is required');
  else if (!MODES[mode]) problems.push(`operatingMode is one of ${Object.keys(MODES).join(', ')}`);
  else if (holders.length < MODES[mode][0] || holders.length > MODES[mode][1]) {
    const [lo, hi] = MODES[mode];
    problems.push(`operating mode ${mode} needs ${lo === hi ? (lo === 1 ? 'exactly one holder' : `exactly ${lo} holders`) : `from ${lo} to ${hi} holders`}; ${holders.length} named`);
  }

  const named = (input.nominees ?? []) as DepositNomineeInput[];
  const nomination = (input.nominationMode ?? (named.length ? '' : 'NONE')).toUpperCase();
  const nominees: MockOpening['nominees'] = named.map((n) => ({ name: (n.name ?? '').trim(), relationship: (n.relationship ?? '').toUpperCase(), sharePercent: plain(n.sharePercent), minor: n.minor === true, guardianName: n.guardianName?.trim() || null }));
  if (!['NONE', 'SIMULTANEOUS', 'SUCCESSIVE'].includes(nomination)) problems.push(nomination ? 'nominationMode is one of NONE, SIMULTANEOUS, SUCCESSIVE' : 'the nomination mode is required (NONE when no nominee is named)');
  else if (nomination === 'NONE') {
    if (nominees.length) problems.push('nominees are named but the nomination mode is NONE');
  } else if (!nominees.length) problems.push(`nomination mode ${nomination} needs at least one nominee`);
  else {
    const max = rule(db, 'nominees.max');
    if (max !== null && nominees.length > max) problems.push(`${nominees.length} nominees named; at most ${max} allowed`);
    if (nomination === 'SUCCESSIVE' && nominees.length < 2) problems.push('successive nomination needs two nominees or more; name one nominee as SIMULTANEOUS with 100%');
    let total = 0;
    let complete = true;
    nominees.forEach((n, i) => {
      const who = `nominee ${i + 1}`;
      if (!n.name) problems.push(`${who}: the name is required`);
      if (!n.relationship) problems.push(`${who}: the relationship to the depositor is required`);
      else if (!RELATIONSHIPS.includes(n.relationship)) problems.push(`${who}: the relationship is one of ${[...RELATIONSHIPS].sort().join(', ')}`);
      if (n.minor && !n.guardianName) problems.push(`${who} is a minor: name the person who receives the amount on the minor's behalf`);
      if (!n.minor && n.guardianName) problems.push(`${who}: a guardian is named only for a minor`);
      if (nomination === 'SIMULTANEOUS') {
        if (n.sharePercent === null) (problems.push(`${who}: the share is required`), (complete = false));
        else if (Number(n.sharePercent) <= 0 || Number(n.sharePercent) > 100) (problems.push(`${who}: the share must be above 0 and at most 100`), (complete = false));
        else total += Math.round(Number(n.sharePercent) * 100);
      } else if (n.sharePercent !== null) problems.push(`${who}: successive nominees have no shares; the order named decides`);
    });
    if (nomination === 'SIMULTANEOUS' && complete && total !== 10000) problems.push(`nominee shares must total 100 percent; they total ${total / 100}`);
  }

  let q: Quote | null = null;
  const amount = term ? plain(input.amount) : null;
  const payoutTo = term ? (input.payoutTo ?? 'BANK_ACCOUNT').toUpperCase() : null;
  const instruction = term ? (input.maturityInstruction ?? 'PAY_OUT').toUpperCase() : null;
  if (term) {
    const first = db.customers.find((x) => x.id === input.customerId);
    const res = quote(db, product, amount, input.tenureMonths ?? 0, input.tenureDays ?? 0, input.addOns ?? [], nominees.length,
      { individual: first?.input.customerType === 'INDIVIDUAL', dateOfBirth: first?.input.dateOfBirth ?? null, panOnFile: !!first?.input.pan });
    problems.push(...res.problems);
    q = res.quote;
    if (payoutTo !== 'CASA' && payoutTo !== 'BANK_ACCOUNT') problems.push('payoutTo is CASA (a savings or current account of the depositor) or BANK_ACCOUNT');
    else if (payoutTo === 'CASA') {
      const pay = db.deposits.accounts.find((a) => a.id === input.payoutAccountId);
      if (!input.payoutAccountId) problems.push('name the savings or current account that interest and maturity proceeds are paid to');
      else if (!pay) problems.push('the payout account does not exist');
      else if (pay.family !== 'CASA' || pay.status !== 'ACTIVE') problems.push(`interest and maturity proceeds are paid to an active savings or current account; ${pay.accountNo} is not one`);
      else if (!(pay.holders ?? []).some((h) => h.customerId === input.customerId)) problems.push(`account ${pay.accountNo} is not held by the depositor`);
    } else if (input.payoutAccountId) problems.push('payoutAccountId goes with payoutTo CASA');
    if (!['PAY_OUT', 'RENEW_PRINCIPAL', 'RENEW_WITH_INTEREST'].includes(instruction!)) problems.push('maturityInstruction is PAY_OUT, RENEW_PRINCIPAL or RENEW_WITH_INTEREST');
    else if (instruction !== 'PAY_OUT') {
      if (!product.autoRenewalAllowed) problems.push(`product ${product.code} does not allow renewal at maturity`);
      if (rule(db, 'td.renewal-needs-consent') === 1) problems.push("a renewal needs the depositor's consent, recorded when the deposit matures; book the deposit to be paid out");
    }
  }
  refuseIfAny(term ? 'the deposit cannot be booked' : 'the account cannot be opened', problems);
  const firstHolder = customerName(db, input.customerId);
  return {
    opening: { family, productCode: product.code!, holders, branchCode: input.branchCode, operatingMode: mode, nominationMode: nomination, nominees, amount,
      tenureMonths: term ? input.tenureMonths ?? 0 : null, tenureDays: term ? input.tenureDays ?? 0 : null, addOns: term ? [...new Set(input.addOns ?? [])].sort() : [],
      payoutTo, payoutAccountId: term ? input.payoutAccountId ?? null : null, maturityInstruction: instruction, quotedRatePercent: q?.ratePercent ?? null,
      customerNo: firstHolder.customerNo, customerName: firstHolder.name },
    quote: q,
  };
}

/** The last day of the interest period in progress (SavingsInterest.periodEnd, financial year from April). */
function interestPeriodEnd(date: string, postingMonths: number): string {
  const month = Number(date.slice(5, 7));
  const since = (month - 4 + 12) % 12;
  const yearStart = addMonths(`${date.slice(0, 8)}01`, -since);
  return addDays(addMonths(yearStart, (Math.floor(since / postingMonths) + 1) * postingMonths), -1);
}

// Number series as in the tenant database: CASA 2001…, term deposits 3001…, nine digits and a Luhn check digit.
function accountNo(prefix: string, seq: number): string {
  const body = prefix + String(seq).padStart(9, '0');
  let sum = 0;
  [...body].reverse().forEach((ch, i) => {
    let d = Number(ch);
    if (i % 2 === 0) (d *= 2), d > 9 && (d -= 9);
    sum += d;
  });
  return body + String((10 - (sum % 10)) % 10);
}

function openAccount(db: MockDb, o: MockOpening, q: Quote | null, maker: string, approvalId: string | null): DepositAccount {
  const product = db.deposits.products.find((x) => x.code === o.productCode)!;
  const term = o.family === 'TERM_DEPOSIT';
  const total = q ? q.periods.reduce((s, x) => s + x.interest, 0n) : 0n;
  const account: DepositAccount = {
    id: uuid(),
    accountNo: term ? accountNo('3001', ++db.deposits.termSeq) : accountNo('2001', ++db.deposits.casaSeq),
    family: o.family, productCode: product.code, productVersion: product.version ?? 1, productName: product.name, productType: product.productType,
    branchCode: o.branchCode, operatingMode: o.operatingMode, nominationMode: o.nominationMode, status: term ? 'BOOKED' : 'ACTIVE',
    openedOn: db.businessDate, openedBy: maker, approvalId,
    holders: o.holders.map((id, i) => ({ holderNo: i + 1, customerId: id, ...customerName(db, id) })),
    nominees: o.nominees.map((n, i) => ({ nomineeNo: i + 1, ...n })),
    nextInterestDate: !term && product.interestBands ? interestPeriodEnd(db.businessDate, product.interestPostingMonths ?? 3) : null,
  };
  if (term && q) {
    const pay = db.deposits.accounts.find((a) => a.id === o.payoutAccountId);
    account.termDeposit = {
      principal: o.amount!, tenureMonths: o.tenureMonths!, tenureDays: o.tenureDays!, bookedOn: db.businessDate, startDate: null, maturityDate: null,
      ratePercent: q.ratePercent, cardRatePercent: q.cell.ratePercent, cardFromAmount: q.cell.fromAmount, cardFromMonths: q.cell.fromMonths, cardFromDays: q.cell.fromDays,
      addOns: q.addOns, interestPeriodMonths: product.interestPeriodMonths!, cumulative: product.productType === 'FD_CUMULATIVE', dayCount: product.dayCount!,
      rounding: product.rounding, tdsApplicable: product.tdsApplicable === true, prematureAllowed: product.prematureAllowed === true,
      prematurePenaltyPercent: product.prematurePenaltyPercent ?? null, prematureMinDays: product.prematureMinDays ?? null,
      payoutTo: o.payoutTo as 'CASA' | 'BANK_ACCOUNT', payoutAccountId: o.payoutAccountId, payoutAccountNo: pay?.accountNo ?? null,
      maturityInstruction: o.maturityInstruction as 'PAY_OUT', funded: false, figuresFrom: q.start, figuresMaturityDate: q.maturity,
      totalInterest: fromUnits(total), maturityValue: fromUnits(product.productType === 'FD_CUMULATIVE' ? toUnits(o.amount!) + total : toUnits(o.amount!)),
    };
  }
  db.deposits.accounts.unshift(account);
  return account;
}

const summaryOf = (db: MockDb, a: DepositAccount): DepositAccountSummary => ({
  id: a.id, accountNo: a.accountNo, productCode: a.productCode, productName: a.productName, productType: a.productType,
  customerId: a.holders?.[0]?.customerId, customerNo: a.holders?.[0]?.customerNo, customerName: a.holders?.[0]?.name,
  branchCode: a.branchCode, operatingMode: a.operatingMode, status: a.status, openedOn: a.openedOn, holders: a.holders?.length ?? 0,
  principal: a.termDeposit?.principal ?? null, tenureMonths: a.termDeposit?.tenureMonths ?? null, tenureDays: a.termDeposit?.tenureDays ?? null,
  ratePercent: a.termDeposit?.ratePercent ?? null, startDate: a.termDeposit?.startDate ?? null, maturityDate: a.termDeposit?.maturityDate ?? null,
  balance: balanceView(db, a.id!).balance, onHold: onHold(db, a.id!),
});

// ---------- approvals ----------

export function applyDepositApproval(db: MockDb, p: ApprovalPayload, approval: { id?: string; entityId?: string | null; maker?: string; appliedRef?: string | null }, checker: string, at: string): boolean {
  if (applyDepositMoneyApproval(db, p, approval, checker, at)) return true;
  if (p.kind === 'DEPOSIT_PRODUCT') {
    const existing = db.deposits.products.find((x) => x.code === p.product.code);
    if (existing && existing.status !== 'ACTIVE') throw conflict('Conflict', `deposit product ${p.product.code} is not active any more`);
    refuseIfAny(`deposit product ${p.product.code} cannot be approved`, productProblems(db, p.product));
    storeProduct(db, p.product, approval.maker ?? checker, approval.id ?? null);
    approval.entityId = p.product.code!;
    approval.appliedRef = p.product.code!;
    appendAudit(db, at, checker, 'DEPOSIT_PRODUCT_APPROVED', 'DEPOSIT_PRODUCT', p.product.code!, { version: db.deposits.products.find((x) => x.code === p.product.code)?.version });
    return true;
  }
  if (p.kind === 'DEPOSIT_PRODUCT_WITHDRAW') {
    const existing = db.deposits.products.find((x) => x.code === p.code);
    if (!existing || existing.status !== 'ACTIVE') throw conflict('Conflict', `product ${p.code} is not active any more`);
    storeProduct(db, { ...existing, status: 'WITHDRAWN' }, approval.maker ?? checker, approval.id ?? null);
    approval.appliedRef = p.code;
    return true;
  }
  if (p.kind === 'DEPOSIT_ACCOUNT') {
    const o = p.opening;
    // checked again as on the day of approval: the customer, the product and the rate may have changed
    const again = checkOpening(db, o.family, { productCode: o.productCode, customerId: o.holders[0], jointHolders: o.holders.slice(1), branchCode: o.branchCode,
      operatingMode: o.operatingMode as never, nominationMode: o.nominationMode as never, nominees: o.nominees as never,
      ...(o.family === 'TERM_DEPOSIT' ? { amount: o.amount!, tenureMonths: o.tenureMonths, tenureDays: o.tenureDays, addOns: o.addOns, payoutTo: o.payoutTo as never,
        payoutAccountId: o.payoutAccountId, maturityInstruction: o.maturityInstruction as never } : {}) });
    if (o.quotedRatePercent !== null && again.quote && Number(again.quote.ratePercent) !== Number(o.quotedRatePercent)) {
      throw conflict('Conflict', `the rate of product ${o.productCode} for this deposit is now ${again.quote.ratePercent}%, not the ${o.quotedRatePercent}% shown when it was proposed; reject this request and propose the deposit again`);
    }
    const account = openAccount(db, again.opening, again.quote, approval.maker ?? checker, approval.id ?? null);
    approval.entityId = account.id!;
    approval.appliedRef = account.accountNo!;
    appendAudit(db, at, checker, o.family === 'CASA' ? 'CASA_ACCOUNT_OPENED' : 'TERM_DEPOSIT_BOOKED', o.family === 'CASA' ? 'CASA_ACCOUNT' : 'TERM_DEPOSIT', account.id!, { accountNo: account.accountNo });
    return true;
  }
  return false;
}

// ---------- routes ----------

export function registerDepositRoutes(db: MockDb, r: Registrar): void {
  const { on, require, propose } = r;
  const requireAny = (user: DemoUser, perms: string[]) => {
    if (!perms.some((p) => user.permissions.includes(p))) require(user, perms[0]);
  };
  /** The tenant filter: 403 when the module the path belongs to is not enabled for this tenant. */
  const module = (name: 'CASA' | 'TD') => {
    if (!db.deposits.modules.includes(name)) throw new HttpProblem(403, 'Forbidden', `module ${name} is not enabled for this tenant`);
  };

  const rules = ({ user }: Ctx): Result => {
    requireAny(user, [P.productView, P.casaView, P.depositView]);
    const kind = db.deposits.kind;
    const list = rulesOf(kind);
    const view: DepositRuleSet = {
      kind, entityType: kind === 'BANK' ? 'BANK' : 'NBFC', depositTaking: kind === 'NBFC_DEPOSIT', depositRegistrationRef: kind === 'NBFC_DEPOSIT' ? 'CLAUDE-TEST demo registration (not a certificate)' : null,
      asOf: db.businessDate, reason: null, unverified: list.filter((x) => !x.verified).length, rules: list.map((x) => ({ ...x })),
    };
    return ok(view);
  };

  for (const [base, family, mod, view] of [['casa', 'CASA', 'CASA', P.casaView], ['deposits', 'TERM_DEPOSIT', 'TD', P.depositView]] as const) {
    on('GET', `/api/v1/${base}/rules`, (ctx) => (module(mod), rules(ctx)));
    on('GET', `/api/v1/${base}/products`, ({ user }) => {
      module(mod);
      requireAny(user, [P.productView, view]);
      return ok(db.deposits.products.filter((p) => p.family === family));
    });
    on('POST', `/api/v1/${base}/products/check`, ({ user, body }) => {
      module(mod);
      require(user, P.productPropose);
      requireKind(db);
      return ok({ problems: productProblems(db, normalise(db, family, body as Partial<DepositProductInput>)) });
    });
    on('POST', `/api/v1/${base}/products`, ({ user, body }) => {
      module(mod);
      require(user, P.productPropose);
      requireKind(db);
      const p = normalise(db, family, body as Partial<DepositProductInput>);
      const existing = db.deposits.products.find((x) => x.code === p.code);
      if (existing && (existing.family !== family || existing.productType !== p.productType)) {
        throw invalid(`product ${p.code} is a ${existing.productType} product; the family and type of a deposit product never change`);
      }
      if (existing && existing.status !== 'ACTIVE') throw conflict('Conflict', `product ${p.code} is ${existing.status}; a withdrawn product is not changed`);
      refuseIfAny(`deposit product ${p.code} cannot be approved`, productProblems(db, p));
      return propose(user, 'DEPOSIT_PRODUCT', existing ? 'UPDATE' : 'CREATE', { kind: 'DEPOSIT_PRODUCT', product: p }, { ...p }, existing ? { ...existing } : null, p.code);
    });
    on('GET', `/api/v1/${base}/products/{code}`, ({ user, params }) => {
      module(mod);
      requireAny(user, [P.productView, view]);
      return ok(findProduct(db, family, params.code));
    });
    on('GET', `/api/v1/${base}/products/{code}/history`, ({ user, params }) => {
      module(mod);
      require(user, P.productView);
      findProduct(db, family, params.code);
      return ok(db.deposits.history.filter((h) => h.code === params.code).sort((x, y) => y.version! - x.version!).map(({ code: _code, ...h }) => h));
    });
    on('POST', `/api/v1/${base}/products/{code}/withdraw`, ({ user, params, body }) => {
      module(mod);
      require(user, P.productPropose);
      const existing = findProduct(db, family, params.code);
      if (existing.status !== 'ACTIVE') throw conflict('Conflict', `product ${existing.code} is already ${existing.status}`);
      const reason = ((body ?? {}) as { reason?: string }).reason?.trim();
      if (!reason || reason.length > 500) throw invalid('a reason is required (at most 500 characters)');
      return propose(user, 'DEPOSIT_PRODUCT', 'WITHDRAW', { kind: 'DEPOSIT_PRODUCT_WITHDRAW', code: existing.code! }, { code: existing.code, status: 'WITHDRAWN', reason }, { ...existing }, existing.code);
    });
    on('GET', `/api/v1/${base}/accounts`, ({ user, url }) => {
      module(mod);
      require(user, view);
      const customerId = url.searchParams.get('customerId');
      const status = url.searchParams.get('status');
      return ok(db.deposits.accounts.filter((a) => a.family === family && (!customerId || (a.holders ?? []).some((h) => h.customerId === customerId)) && (!status || a.status === status)).map((a) => summaryOf(db, a)));
    });
    on('GET', `/api/v1/${base}/accounts/{id}`, ({ user, params }) => {
      module(mod);
      require(user, view);
      const a = db.deposits.accounts.find((x) => x.id === params.id && x.family === family);
      if (!a) throw notFound(family === 'CASA' ? `account ${params.id}` : `term deposit ${params.id}`);
      return ok({ ...a, ...balanceView(db, a.id!) });
    });
    on('POST', `/api/v1/${base}/accounts`, ({ user, body }) => {
      module(mod);
      require(user, family === 'CASA' ? P.casaOpen : P.depositBook);
      const { opening } = checkOpening(db, family, body as Partial<CasaAccountInput & TermDepositInput>);
      return propose(user, family === 'CASA' ? 'CASA_ACCOUNT' : 'TERM_DEPOSIT', family === 'CASA' ? 'OPEN' : 'BOOK', { kind: 'DEPOSIT_ACCOUNT', opening }, { ...opening }, null, null, opening.amount);
    });
  }

  registerDepositMoneyRoutes(db, r, module);

  on('POST', '/api/v1/deposits/preview', ({ user, body }) => {
    module('TD');
    requireAny(user, [P.depositView, P.depositBook]);
    requireKind(db);
    const b = (body ?? {}) as Partial<TermDepositInput>;
    if (!b.productCode) throw invalid('productCode is required');
    const product = findProduct(db, 'TERM_DEPOSIT', b.productCode);
    const c = b.customerId ? db.customers.find((x) => x.id === b.customerId) : null;
    if (b.customerId && !c) throw notFound(`customer ${b.customerId}`);
    const who: Depositor = c ? { individual: c.input.customerType === 'INDIVIDUAL', dateOfBirth: c.input.dateOfBirth ?? null, panOnFile: !!c.input.pan } : { individual: true, dateOfBirth: null, panOnFile: true };
    const amount = plain(b.amount);
    const res = quote(db, product, amount, b.tenureMonths ?? 0, b.tenureDays ?? 0, b.addOns ?? [], (b.nominees ?? []).length, who);
    refuseIfAny('the deposit cannot be booked', [...(product.status !== 'ACTIVE' ? [`product ${product.code} is ${product.status}; it takes no new deposit`] : []), ...res.problems]);
    return ok(previewOf(db, res.quote!, amount!, b.tenureMonths ?? 0, b.tenureDays ?? 0, who));
  });
}
