/**
 * Lending in the mock API: products, loan booking, KFS, disbursement, servicing and day-end.
 *
 * A loan's state is never edited in place. It is derived by replaying the loan's financial transactions in order,
 * raising demands for every due date reached on the way, up to the business date. That keeps every figure
 * consistent and makes reversals exact: a reversed transaction (and every later one) is dropped and the days since
 * are replayed, which is what the backend does.
 */
import type { FeeRule, Loan, LoanApplication, LoanKfs, LoanProduct, LoanSchedule, LoanSummary, LoanTxn, PreclosureQuote, ScheduleRow } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { addDays, formatDate, ISO_DATE } from '../lib/dates';
import { customerNumber } from '../lib/luhn';
import { formatINR, isMoney } from '../lib/money';
import { appendAudit, uuid, type ApprovalPayload, type ChargeState, type LoanState, type MockDb, type StoredCustomer, type StoredLoan, type StoredLoanEvent } from './db';
import * as C from './lendingCalc';
import { bad, conflict, notFound, type FieldProblem } from './problems';

export const LOAN_SERIES_PREFIX = '1001';
/** Straight-through processing (LOS integration clients): disbursement without maker-checker. */
const LOAN_STP = 'loan:stp';

const inr = (p: number) => formatINR(C.fromPaise(p));
const FINANCIAL = new Set(['REPAYMENT', 'PREPAYMENT', 'FEE_CHARGE', 'WAIVER', 'PRECLOSURE', 'CANCELLATION']);
const REVERSIBLE = FINANCIAL;
const PENAL_NOTE = 'Penal charges apply only on overdue amounts, are not added to the interest rate and are not compounded.';

// ------------------------------------------------------------------ seed products
const APPROPRIATION: LoanProduct['appropriationSequence'] = ['INTEREST', 'PRINCIPAL', 'PENAL', 'FEE'];

export const SEED_PRODUCTS: LoanProduct[] = [
  {
    code: 'PL01',
    name: 'Personal Loan',
    repaymentMethod: 'EQUATED',
    minAmount: '10000.00',
    maxAmount: '500000.00',
    minTenorMonths: 6,
    maxTenorMonths: 60,
    minRate: '12.00',
    maxRate: '24.00',
    interestTableCode: 'PL1',
    rateType: 'FIXED',
    dayCount: 'ACTUAL_365',
    rounding: 'RUPEE_HALF_UP',
    penalChargeRate: '24.00',
    maxMoratoriumMonths: 3,
    coolingOffDays: 3,
    secured: false,
    appropriationSequence: APPROPRIATION,
    appropriationMode: 'BY_DEMAND',
    prepaymentMode: 'REDUCE_TENURE',
    status: 'ACTIVE',
    version: 1,
    fees: [
      { code: 'PF', name: 'Processing fee', event: 'DISBURSEMENT', calcType: 'PERCENT', percent: '0.75', minAmount: '500.00', maxAmount: '10000.00', gstRate: '18', taxTreatment: 'EXCLUSIVE', deductFromDisbursal: true },
      { code: 'FC', name: 'Foreclosure charge', event: 'PRECLOSURE', calcType: 'PERCENT', percent: '2', gstRate: '18', taxTreatment: 'EXCLUSIVE', deductFromDisbursal: false },
      {
        code: 'BNC', name: 'Bounce charge', event: 'BOUNCE', calcType: 'SLAB', gstRate: '18', taxTreatment: 'EXCLUSIVE', deductFromDisbursal: false,
        slabs: [{ from: '0', to: '10000', fee: '300' }, { from: '10000.01', to: '99999999', fee: '500' }],
      },
    ],
  },
  {
    code: 'ML01',
    name: 'Micro Bullet',
    repaymentMethod: 'BULLET_TOTAL_INTEREST',
    minAmount: '1000.00',
    maxAmount: '30000.00',
    minTenorMonths: 1,
    maxTenorMonths: 2,
    minRate: '18.00',
    maxRate: '30.00',
    interestTableCode: null,
    rateType: 'FIXED',
    dayCount: 'ACTUAL_365',
    rounding: 'RUPEE_HALF_UP',
    penalChargeRate: '24.00',
    maxMoratoriumMonths: 0,
    coolingOffDays: 1,
    secured: false,
    appropriationSequence: APPROPRIATION,
    appropriationMode: 'BY_DEMAND',
    prepaymentMode: 'REDUCE_EMI',
    status: 'ACTIVE',
    version: 1,
    fees: [],
  },
];

/** Mock interest tables (absolute slab rates by amount). */
const RATE_TABLES: Record<string, Array<{ upTo: number; rate: number }>> = {
  PL1: [
    { upTo: 100_000_00, rate: 18 },
    { upTo: 300_000_00, rate: 16 },
    { upTo: Number.MAX_SAFE_INTEGER, rate: 14 },
  ],
};

// ------------------------------------------------------------------ helpers
const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x)) as T;
const displayName = (c: StoredCustomer) => [c.input.firstName, c.input.middleName, c.input.lastName].filter(Boolean).join(' ');

function money(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null;
  const s = String(v).trim();
  if (!isMoney(s)) return NaN;
  return C.toPaise(s);
}

function arrearsOf(st: LoanState): number {
  return st.demands.reduce((s, d) => s + (d.principalDue - d.principalPaid) + (d.interestDue - d.interestPaid), 0);
}
const unpaidOf = (c: ChargeState) => c.amount - c.paid - c.waived;
const unpaidCharges = (st: LoanState) => st.charges.reduce((s, c) => s + unpaidOf(c), 0);
const futurePrincipal = (st: LoanState) => st.rows.slice(st.raised).reduce((s, r) => s + r.principal, 0);
function oldestUnpaidDue(st: LoanState): string | null {
  const d = st.demands.find((x) => x.principalDue - x.principalPaid + x.interestDue - x.interestPaid > 0);
  return d ? d.dueDate : null;
}

function toScheduleRow(r: C.Row): ScheduleRow {
  return {
    instalmentNo: r.no,
    dueDate: r.dueDate,
    days: r.days,
    openingBalance: C.fromPaise(r.opening),
    interest: C.fromPaise(r.interest),
    principal: C.fromPaise(r.principal),
    instalment: C.fromPaise(r.instalment),
    closingBalance: C.fromPaise(r.closing),
  };
}
function fromScheduleRow(r: ScheduleRow): C.Row {
  return {
    no: r.instalmentNo ?? 0,
    dueDate: r.dueDate ?? '',
    days: r.days ?? 0,
    opening: C.toPaise(r.openingBalance),
    interest: C.toPaise(r.interest),
    principal: C.toPaise(r.principal),
    instalment: C.toPaise(r.instalment),
    closing: C.toPaise(r.closingBalance),
  };
}

// ------------------------------------------------------------------ application → KFS
interface Resolved {
  product: LoanProduct;
  customer: StoredCustomer;
  branch: string;
  supplierState: string;
  recipientState: string;
  amount: number;
  rate: number;
  rateExplanation: string;
  tenor: number;
  moratorium: number;
  balloon: number;
  disbursal: string;
  firstDue: string | null;
}

function resolve(db: MockDb, a: LoanApplication, opts: { allowPastDisbursal?: boolean } = {}): Resolved {
  const errors: FieldProblem[] = [];
  if (!a || typeof a !== 'object') throw bad('Body required');
  const product = db.loanProducts.find((p) => p.code === a.productCode);
  if (!product) errors.push({ field: 'productCode', message: 'Unknown product' });
  else if (product.status !== 'ACTIVE') errors.push({ field: 'productCode', message: `Product ${product.code} is ${product.status}` });
  const customer = db.customers.find((c) => c.id === a.customerId);
  if (!customer) errors.push({ field: 'customerId', message: 'Customer not found' });
  else if (customer.status !== 'ACTIVE') errors.push({ field: 'customerId', message: `Customer is ${customer.status}` });

  const amount = money(a.amount);
  const tenor = Number(a.tenorMonths);
  const balloonIn = money(a.balloon);
  const moratorium = a.moratoriumMonths === undefined || a.moratoriumMonths === null ? 0 : Number(a.moratoriumMonths);
  const rateIn = a.rate === null || a.rate === undefined || a.rate === '' ? null : Number(a.rate);
  let rate = NaN;
  let rateExplanation = 'rate set on the account';

  if (amount === null || Number.isNaN(amount) || amount <= 0) errors.push({ field: 'amount', message: 'Amount must be a positive decimal' });
  if (!Number.isInteger(tenor) || tenor <= 0) errors.push({ field: 'tenorMonths', message: 'Tenor must be a whole number of months' });
  if (!Number.isInteger(moratorium) || moratorium < 0) errors.push({ field: 'moratoriumMonths', message: 'Moratorium must be 0 or more months' });

  if (product) {
    const min = C.toPaise(product.minAmount);
    const max = C.toPaise(product.maxAmount);
    if (amount !== null && !Number.isNaN(amount) && amount > 0 && (amount < min || amount > max)) {
      errors.push({ field: 'amount', message: `Amount must be between ${inr(min)} and ${inr(max)}` });
    }
    if (Number.isInteger(tenor) && tenor > 0 && (tenor < product.minTenorMonths || tenor > product.maxTenorMonths)) {
      errors.push({ field: 'tenorMonths', message: `Tenor must be ${product.minTenorMonths} to ${product.maxTenorMonths} months` });
    }
    if (moratorium > (product.maxMoratoriumMonths ?? 0)) errors.push({ field: 'moratoriumMonths', message: `Moratorium above the product limit of ${product.maxMoratoriumMonths ?? 0} months` });
    else if (Number.isInteger(tenor) && moratorium > 0 && moratorium >= tenor) errors.push({ field: 'moratoriumMonths', message: 'Moratorium must be shorter than the tenor' });
    if (balloonIn !== null && (Number.isNaN(balloonIn) || balloonIn < 0)) errors.push({ field: 'balloon', message: 'Balloon must be a decimal of 0 or more' });
    else if (balloonIn && product.repaymentMethod !== 'EQUATED') errors.push({ field: 'balloon', message: 'A balloon is allowed only on EMI (equated) products' });
    else if (balloonIn && amount !== null && !Number.isNaN(amount) && balloonIn >= amount / 2) errors.push({ field: 'balloon', message: 'Balloon must be less than half the loan amount' });
    if (rateIn === null) {
      const table = product.interestTableCode ? RATE_TABLES[product.interestTableCode] : undefined;
      if (!table) errors.push({ field: 'rate', message: 'Rate is required (the product has no interest table)' });
      else if (amount !== null && !Number.isNaN(amount)) {
        rate = table.find((s) => amount <= s.upTo)!.rate;
        rateExplanation = `slab rate ${rate}% from interest table ${product.interestTableCode}`;
      }
    } else if (!Number.isFinite(rateIn)) errors.push({ field: 'rate', message: 'Rate must be a number' });
    else rate = rateIn;
    if (Number.isFinite(rate) && (rate < C.num(product.minRate) || rate > C.num(product.maxRate))) {
      errors.push({ field: 'rate', message: `Rate must be within the product band ${C.num(product.minRate)}% to ${C.num(product.maxRate)}%` });
    }
  }

  const disbursal = a.disbursalDate || db.businessDate;
  if (!ISO_DATE.test(disbursal)) errors.push({ field: 'disbursalDate', message: 'Invalid date' });
  else if (!opts.allowPastDisbursal && disbursal < db.businessDate) errors.push({ field: 'disbursalDate', message: 'Disbursal date cannot be before the business date' });
  const firstDue = a.firstDueDate || null;
  if (firstDue) {
    if (!ISO_DATE.test(firstDue)) errors.push({ field: 'firstDueDate', message: 'Invalid date' });
    else if (firstDue <= disbursal) errors.push({ field: 'firstDueDate', message: 'First due date must be after the disbursal date' });
    else if (firstDue > addDays(disbursal, 62)) errors.push({ field: 'firstDueDate', message: 'First due date must be within two months of disbursal' });
  }
  const branchCode = a.branch || customer?.input.homeBranch || '';
  const branch = db.branches.find((b) => b.code === branchCode && b.status === 'ACTIVE');
  if (customer && !branch) errors.push({ field: 'branch', message: 'Unknown or inactive branch' });

  if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
  const supplierState = branch!.stateCode;
  return {
    product: product!,
    customer: customer!,
    branch: branch!.code,
    supplierState,
    recipientState: customer!.input.address?.stateCode || supplierState,
    amount: amount!,
    rate,
    rateExplanation,
    tenor,
    moratorium,
    balloon: balloonIn && !Number.isNaN(balloonIn) ? balloonIn : 0,
    disbursal,
    firstDue,
  };
}

function dueDates(method: C.RepaymentMethod, disbursal: string, firstDue: string | null, tenor: number): string[] {
  if (method === 'BULLET_TOTAL_INTEREST') return [C.dueDate(disbursal, firstDue, tenor)];
  return Array.from({ length: tenor }, (_, i) => C.dueDate(disbursal, firstDue, i + 1));
}

function planRows(product: LoanProduct, amount: number, rate: number, tenor: number, moratorium: number, disbursal: string, firstDue: string | null, balloon = 0) {
  const method = product.repaymentMethod;
  const emi = method === 'EQUATED' ? C.pmtBalloon(amount, rate, tenor - moratorium, balloon) : null;
  const rows = C.buildRows({ balance: amount, ratePct: rate, from: disbursal, dueDates: dueDates(method, disbursal, firstDue, tenor), method, moratoriumRows: moratorium, emi });
  return { rows, emi };
}

function upfrontFees(product: LoanProduct, amount: number, supplier: string, recipient: string) {
  return (product.fees ?? []).filter((f) => f.event === 'DISBURSEMENT').map((f) => ({ rule: f, charge: C.computeFee(f, amount, supplier, recipient) }));
}

/** KFS figures from the same code that books and services the loan (preview = post). */
function buildKfs(r: Resolved): LoanKfs {
  const p = r.product;
  const { rows, emi } = planRows(p, r.amount, r.rate, r.tenor, r.moratorium, r.disbursal, r.firstDue, r.balloon);
  const fees = upfrontFees(p, r.amount, r.supplierState, r.recipientState);
  const deducted = fees.filter((f) => f.rule.deductFromDisbursal).reduce((s, f) => s + f.charge.total, 0);
  const feesExGst = fees.reduce((s, f) => s + f.charge.fee, 0);
  const totalInterest = rows.reduce((s, x) => s + x.interest, 0);
  return {
    productCode: p.code,
    productName: p.name,
    productVersion: p.version ?? 1,
    amount: C.fromPaise(r.amount),
    tenorMonths: r.tenor,
    repaymentMethod: p.repaymentMethod,
    rateType: p.rateType ?? 'FIXED',
    interestRate: C.pct(r.rate),
    rateExplanation: r.rateExplanation,
    emi: emi === null ? null : C.fromPaise(emi),
    instalments: rows.length,
    totalInterest: C.fromPaise(totalInterest),
    fees: fees.map(({ charge: c }) => ({
      code: c.code,
      name: c.name,
      fee: C.fromPaise(c.fee),
      cgst: C.fromPaise(c.cgst),
      sgst: C.fromPaise(c.sgst),
      igst: C.fromPaise(c.igst),
      total: C.fromPaise(c.total),
    })),
    netDisbursal: C.fromPaise(r.amount - deducted),
    totalRepayable: C.fromPaise(r.amount + totalInterest),
    apr: C.apr(r.amount, feesExGst, rows, p.repaymentMethod, r.disbursal, emi),
    penalChargeRate: p.penalChargeRate === null || p.penalChargeRate === undefined ? null : C.pct(p.penalChargeRate),
    penalChargeNote: PENAL_NOTE,
    coolingOffDays: p.coolingOffDays ?? 0,
    placeOfSupply: r.recipientState,
    schedule: rows.map(toScheduleRow),
  };
}

export function previewLoan(db: MockDb, a: LoanApplication): LoanKfs {
  return buildKfs(resolve(db, a));
}

// ------------------------------------------------------------------ replay
function payDemands(st: LoanState, amount: number): number {
  let rem = amount;
  for (const d of st.demands) {
    if (rem <= 0) break;
    const i = Math.min(rem, d.interestDue - d.interestPaid);
    d.interestPaid += i;
    rem -= i;
    const p = Math.min(rem, d.principalDue - d.principalPaid);
    d.principalPaid += p;
    st.principalPaid += p;
    rem -= p;
  }
  return rem;
}

function payCharges(st: LoanState, amount: number, kind: ChargeState['kind']): number {
  let rem = amount;
  for (const c of st.charges) {
    if (rem <= 0) break;
    if (c.kind !== kind) continue;
    const x = Math.min(rem, unpaidOf(c));
    c.paid += x;
    rem -= x;
  }
  return rem;
}

function markNpa(st: LoanState, date: string) {
  const oldest = oldestUnpaidDue(st);
  if (!st.npaSince && oldest && C.dpdOf(date, oldest) > 90) st.npaSince = addDays(oldest, 90);
}

function maybeClose(st: LoanState, date: string) {
  if (st.closedOn) return;
  if (st.raised === st.rows.length && arrearsOf(st) === 0 && unpaidCharges(st) === 0) {
    st.status = 'CLOSED';
    st.closedOn = date;
  }
}

/** Raise demands for every due date on or before `date`; penal accrues monthly on arrears still unpaid at a due date. */
function advanceTo(loan: StoredLoan, st: LoanState, date: string) {
  const penalRate = C.num(loan.product.penalChargeRate);
  while (!st.closedOn && st.raised < st.rows.length && st.rows[st.raised].dueDate <= date) {
    const row = st.rows[st.raised];
    const arrears = arrearsOf(st);
    if (arrears > 0 && penalRate > 0) {
      const amt = C.roundRupee((arrears * penalRate) / 1200);
      if (amt > 0) st.charges.push({ id: `PEN-${row.dueDate}`, code: 'PENAL', name: `Penal charge on overdue ${inr(arrears)}`, kind: 'PENAL', date: row.dueDate, amount: amt, paid: 0, waived: 0 });
    }
    st.demands.push({ no: row.no, dueDate: row.dueDate, principalDue: row.principal, interestDue: row.interest, principalPaid: 0, interestPaid: 0 });
    st.raised += 1;
    st.lastInterestDate = row.dueDate;
    if (st.advance > 0) st.advance = payDemands(st, st.advance);
    maybeClose(st, row.dueDate);
  }
  markNpa(st, date);
}

function settleAll(st: LoanState, date: string, status: 'CLOSED' | 'CANCELLED') {
  for (const d of st.demands) {
    st.principalPaid += d.principalDue - d.principalPaid;
    d.principalPaid = d.principalDue;
    d.interestPaid = d.interestDue;
  }
  for (const c of st.charges) c.paid = c.amount - c.waived;
  st.principalPaid += futurePrincipal(st);
  st.rows = st.rows.slice(0, st.raised);
  st.advance = 0;
  st.status = status;
  st.closedOn = date;
  st.npaSince = null;
}

function rebuildFuture(loan: StoredLoan, st: LoanState, balance: number, mode: string) {
  const future = st.rows.slice(st.raised);
  const dates = future.map((r) => r.dueDate);
  const method = loan.product.repaymentMethod;
  const morLeft = Math.max(0, loan.moratoriumMonths - st.raised);
  const common = { balance, ratePct: loan.rate, from: st.lastInterestDate!, dueDates: dates, method, moratoriumRows: morLeft, startNo: st.raised + 1 };
  let rows: C.Row[];
  if (balance <= 0) rows = [];
  else if (method === 'EQUATED') {
    const emi = mode === 'REDUCE_EMI' ? C.pmt(balance, loan.rate, dates.length - morLeft) : st.emi;
    rows = C.buildRows({ ...common, emi });
    st.emi = emi;
  } else if (method === 'FIXED_PRINCIPAL' && mode === 'REDUCE_TENURE') {
    rows = C.buildRows({ ...common, principalPart: future[0]?.principal ?? null });
  } else rows = C.buildRows(common);
  st.rows = [...st.rows.slice(0, st.raised), ...rows];
}

function initialState(loan: StoredLoan, asOf: string): LoanState {
  const base: LoanState = {
    asOf, status: 'SANCTIONED', rows: [], raised: 0, demands: [], charges: [], advance: 0, principalPaid: 0,
    lastInterestDate: null, emi: loan.kfs.emi ? C.toPaise(loan.kfs.emi) : null, npaSince: null, dpd: 0, assetClass: 'STANDARD', closedOn: null,
  };
  if (!loan.disbursedOn) return { ...base, rows: (loan.kfs.schedule ?? []).map(fromScheduleRow) };
  const { rows, emi } = planRows(loan.product, loan.amount, loan.rate, loan.tenorMonths, loan.moratoriumMonths, loan.disbursedOn, loan.firstDueDate, loan.balloon);
  const charges: ChargeState[] = upfrontFees(loan.product, loan.amount, loan.supplierState, loan.recipientState).map(({ rule, charge }) => ({
    id: `DISB-${charge.code}`,
    code: charge.code,
    name: charge.name,
    kind: 'FEE',
    date: loan.disbursedOn!,
    amount: charge.total,
    paid: rule.deductFromDisbursal ? charge.total : 0,
    waived: 0,
  }));
  return { ...base, status: 'ACTIVE', rows, emi, charges, lastInterestDate: loan.disbursedOn };
}

function applyEvent(loan: StoredLoan, st: LoanState, e: StoredLoanEvent) {
  const amt = e.amount ?? 0;
  switch (e.type) {
    case 'REPAYMENT': {
      let rem = payDemands(st, amt);
      rem = payCharges(st, rem, 'PENAL');
      rem = payCharges(st, rem, 'FEE');
      st.advance += rem;
      break;
    }
    case 'PREPAYMENT': {
      const fut = futurePrincipal(st);
      const x = Math.min(amt, fut);
      st.principalPaid += x;
      rebuildFuture(loan, st, fut - x, e.data.mode ?? loan.product.prepaymentMode ?? 'REDUCE_TENURE');
      st.advance += amt - x;
      break;
    }
    case 'FEE_CHARGE':
      st.charges.push({ id: e.data.chargeId!, code: e.data.code ?? 'FEE', name: e.data.name ?? 'Fee', kind: 'FEE', date: e.valueDate, amount: amt, paid: 0, waived: 0 });
      break;
    case 'WAIVER': {
      const c = st.charges.find((x) => x.id === e.data.chargeId);
      if (c) c.waived += Math.min(amt, unpaidOf(c));
      break;
    }
    case 'PRECLOSURE': {
      const q = quoteFrom(loan, st, e.valueDate);
      if (q.fc) st.charges.push({ id: `FC-${e.id}`, code: q.fc.code, name: q.fc.name, kind: 'FEE', date: e.valueDate, amount: q.fc.total, paid: 0, waived: 0 });
      settleAll(st, e.valueDate, 'CLOSED');
      break;
    }
    case 'CANCELLATION':
      settleAll(st, e.valueDate, 'CANCELLED');
      break;
  }
  if (st.npaSince && arrearsOf(st) === 0) st.npaSince = null; // upgrade only when all arrears of interest and principal are paid
  maybeClose(st, e.valueDate);
}

/** Derive the loan's state as of `asOf` by replaying its live financial transactions. */
export function replay(loan: StoredLoan, asOf: string): LoanState {
  const st = initialState(loan, asOf);
  if (!loan.disbursedOn) return st;
  const events = loan.events.filter((e) => FINANCIAL.has(e.type) && !e.reversedBy).sort((a, b) => a.seq - b.seq);
  for (const e of events) {
    if (st.closedOn) break;
    advanceTo(loan, st, e.valueDate);
    applyEvent(loan, st, e);
  }
  if (!st.closedOn) advanceTo(loan, st, asOf);
  if (st.closedOn) {
    st.dpd = 0;
    st.assetClass = 'STANDARD';
  } else {
    st.dpd = C.dpdOf(asOf, oldestUnpaidDue(st));
    markNpa(st, asOf);
    st.assetClass = st.npaSince ? C.npaAge(st.npaSince, asOf) : C.smaClass(st.dpd);
    st.status = loan.frozen ? 'FROZEN' : 'ACTIVE';
  }
  return st;
}

// ------------------------------------------------------------------ quotes
function quoteFrom(loan: StoredLoan, st: LoanState, asOf: string) {
  const principal = futurePrincipal(st);
  const overdue = arrearsOf(st);
  const from = st.lastInterestDate ?? asOf;
  const accrued = asOf > from ? C.interestFor(principal, loan.rate, C.daysBetween(from, asOf)) : 0;
  const charges = unpaidCharges(st);
  const rule = (loan.product.fees ?? []).find((f) => f.event === 'PRECLOSURE');
  const fc = rule && principal > 0 ? C.computeFee(rule, principal, loan.supplierState, loan.recipientState) : null;
  const total = Math.max(0, principal + overdue + accrued + charges + (fc?.total ?? 0) - st.advance);
  return { principal, overdue, accrued, charges, fc, advance: Math.min(st.advance, principal + overdue + accrued + charges + (fc?.total ?? 0)), total };
}

export function preclosureQuote(loan: StoredLoan, asOf: string): PreclosureQuote {
  const q = quoteFrom(loan, loan.state, asOf);
  return {
    asOf,
    principal: C.fromPaise(q.principal),
    overdueDues: C.fromPaise(q.overdue),
    accruedInterest: C.fromPaise(q.accrued),
    charges: C.fromPaise(q.charges),
    foreclosureFee: C.fromPaise(q.fc?.total ?? 0),
    advanceAdjusted: C.fromPaise(q.advance),
    total: C.fromPaise(q.total),
  };
}

function withinCoolingOff(loan: StoredLoan, asOf: string): boolean {
  if (!loan.disbursedOn) return false;
  return asOf >= loan.disbursedOn && asOf <= addDays(loan.disbursedOn, loan.product.coolingOffDays ?? 0);
}

/** Cooling-off exit: principal plus APR-based cost for the days used; disclosed fees are retained. */
function cancellationTotal(loan: StoredLoan, asOf: string): number {
  const st = loan.state;
  const days = C.daysBetween(loan.disbursedOn!, asOf);
  const interest = C.roundRupee((loan.amount * C.num(loan.kfs.apr) * days) / 36500);
  return Math.max(0, loan.amount - st.principalPaid + interest - st.advance);
}

// ------------------------------------------------------------------ views
function customerOf(db: MockDb, loan: StoredLoan) {
  return db.customers.find((c) => c.id === loan.customerId);
}

function principalOutstanding(loan: StoredLoan): number {
  const st = loan.state;
  if (!loan.disbursedOn || st.closedOn) return 0;
  return loan.amount - st.principalPaid;
}

export function summaryView(db: MockDb, loan: StoredLoan): LoanSummary {
  const st = loan.state;
  const c = customerOf(db, loan);
  const open = !!loan.disbursedOn && !st.closedOn;
  return {
    id: loan.id,
    loanNo: loan.loanNo,
    customerName: c ? displayName(c) : '—',
    customerNo: c?.customerNo ?? '',
    productCode: loan.product.code,
    status: st.status,
    amount: C.fromPaise(loan.amount),
    principalOutstanding: C.fromPaise(principalOutstanding(loan)),
    overdueAmount: C.fromPaise(open ? arrearsOf(st) + unpaidCharges(st) : 0),
    dpd: st.dpd,
    assetClass: st.assetClass,
    nextDueDate: open ? (st.rows[st.raised]?.dueDate ?? null) : null,
    branch: loan.branch,
  };
}

export function loanView(db: MockDb, loan: StoredLoan): Loan {
  const st = loan.state;
  return {
    ...summaryView(db, loan),
    customerId: loan.customerId,
    productVersion: loan.product.version ?? 1,
    rate: C.pct(loan.rate),
    tenorMonths: loan.tenorMonths,
    repaymentMethod: loan.product.repaymentMethod,
    emi: st.emi === null ? null : C.fromPaise(st.emi),
    apr: loan.kfs.apr ?? null,
    openDate: loan.openDate,
    disbursedOn: loan.disbursedOn,
    netDisbursed: loan.netDisbursed === null ? null : C.fromPaise(loan.netDisbursed),
    npaSince: st.npaSince,
    provisionHeld: C.fromPaise(C.roundPaise((principalOutstanding(loan) * C.PROVISION_PCT[st.assetClass]) / 100)),
    kfsAcceptedAt: loan.kfsAcceptedAt,
    externalRef: loan.externalRef,
    closedOn: st.closedOn,
  };
}

export function scheduleView(loan: StoredLoan): LoanSchedule {
  const st = loan.state;
  const q = loan.disbursedOn && !st.closedOn ? quoteFrom(loan, st, st.asOf) : null;
  return {
    demands: st.demands.map((d) => ({
      instalmentNo: d.no,
      dueDate: d.dueDate,
      principalDue: C.fromPaise(d.principalDue),
      interestDue: C.fromPaise(d.interestDue),
      principalPaid: C.fromPaise(d.principalPaid),
      interestPaid: C.fromPaise(d.interestPaid),
    })),
    charges: st.charges.map((c) => ({
      id: c.id,
      code: c.code,
      name: c.name,
      kind: c.kind,
      date: c.date,
      amount: C.fromPaise(c.amount),
      paid: C.fromPaise(c.paid),
      waived: C.fromPaise(c.waived),
      unpaid: C.fromPaise(unpaidOf(c)),
    })),
    future: st.rows.slice(st.raised).map(toScheduleRow),
    accruedInterest: C.fromPaise(q?.accrued ?? 0),
    advance: C.fromPaise(st.advance),
  };
}

function txnView(e: StoredLoanEvent): LoanTxn {
  return {
    id: e.id,
    seq: e.seq,
    type: e.type,
    valueDate: e.valueDate,
    businessDate: e.businessDate,
    amount: e.amount === null ? null : C.fromPaise(e.amount),
    summary: e.summary,
    reversedBy: e.reversedBy,
    reverses: e.reverses,
    createdBy: e.createdBy,
    createdAt: e.createdAt,
  };
}

const txnLabel = (e: StoredLoanEvent) => `#${e.seq} ${e.type}${e.amount !== null ? ` ${inr(e.amount)}` : ''} on ${formatDate(e.valueDate)}`;

// ------------------------------------------------------------------ mutations
function addEvent(db: MockDb, loan: StoredLoan, by: string, at: string, e: Pick<StoredLoanEvent, 'type' | 'valueDate' | 'amount' | 'summary'> & Partial<StoredLoanEvent>): StoredLoanEvent {
  const ev: StoredLoanEvent = {
    id: uuid(),
    seq: loan.events.reduce((m, x) => Math.max(m, x.seq), 0) + 1,
    businessDate: db.businessDate,
    reversedBy: null,
    reverses: null,
    createdBy: by,
    createdAt: at,
    data: {},
    ...e,
  };
  loan.events.push(ev);
  return ev;
}

export function refreshLoan(db: MockDb, loan: StoredLoan) {
  loan.state = replay(loan, db.businessDate);
}

function bookLoan(db: MockDb, r: Resolved, externalRef: string | null, openDate: string): StoredLoan {
  db.loanSeq += 1;
  const kfs = buildKfs(r);
  const loan: StoredLoan = {
    id: uuid(),
    loanNo: customerNumber(LOAN_SERIES_PREFIX, db.loanSeq),
    customerId: r.customer.id,
    product: clone(r.product),
    branch: r.branch,
    supplierState: r.supplierState,
    recipientState: r.recipientState,
    amount: r.amount,
    rate: r.rate,
    tenorMonths: r.tenor,
    moratoriumMonths: r.moratorium,
    balloon: r.balloon,
    firstDueDate: r.firstDue,
    openDate,
    externalRef,
    kfs,
    kfsAcceptedAt: null,
    kfsChannel: null,
    disbursedOn: null,
    netDisbursed: null,
    frozen: false,
    events: [],
    state: undefined as unknown as LoanState,
  };
  loan.state = replay(loan, db.businessDate);
  db.loans.push(loan);
  return loan;
}

function disburse(db: MockDb, loan: StoredLoan, by: string, at: string, mode: string) {
  loan.disbursedOn = db.businessDate;
  if (loan.firstDueDate && loan.firstDueDate <= loan.disbursedOn) loan.firstDueDate = null;
  loan.netDisbursed = C.toPaise(loan.kfs.netDisbursal);
  addEvent(db, loan, by, at, {
    type: 'DISBURSEMENT',
    valueDate: loan.disbursedOn,
    amount: loan.amount,
    summary: `Disbursed ${inr(loan.amount)} via ${mode}; net ${inr(loan.netDisbursed)} after deducted fees`,
  });
  refreshLoan(db, loan);
}

// ------------------------------------------------------------------ approvals
export function applyLendingApproval(db: MockDb, p: ApprovalPayload, approval: { entityId?: string | null }, checker: string, at: string): boolean {
  switch (p.kind) {
    case 'LOAN_PRODUCT': {
      const i = db.loanProducts.findIndex((x) => x.code === p.product.code);
      const version = i >= 0 ? (db.loanProducts[i].version ?? 1) + 1 : 1;
      const product = { ...clone(p.product), version };
      if (i >= 0) db.loanProducts[i] = product;
      else db.loanProducts.push(product);
      approval.entityId = product.code;
      appendAudit(db, at, checker, 'LOAN_PRODUCT_APPROVED', 'LOAN_PRODUCT', product.code, { version });
      return true;
    }
    case 'LOAN_DISBURSEMENT': {
      const loan = db.loans.find((l) => l.id === p.loanId);
      if (!loan) throw notFound('Loan');
      if (loan.state.status !== 'SANCTIONED') throw conflict('Loan is not sanctioned', `Loan ${loan.loanNo} is ${loan.state.status}`);
      if (!loan.kfsAcceptedAt) throw conflict('KFS not accepted', 'The borrower must accept the Key Fact Statement before disbursement');
      disburse(db, loan, checker, at, p.mode);
      appendAudit(db, at, checker, 'LOAN_DISBURSED', 'LOAN', loan.id, { loanNo: loan.loanNo, amount: C.fromPaise(loan.amount) });
      return true;
    }
    case 'LOAN_WAIVER': {
      const loan = db.loans.find((l) => l.id === p.loanId);
      if (!loan) throw notFound('Loan');
      const charge = loan.state.charges.find((c) => c.id === p.chargeId);
      if (!charge) throw notFound('Charge');
      const amount = C.toPaise(p.amount);
      if (amount > unpaidOf(charge)) throw conflict('Waiver exceeds the unpaid charge', `Only ${inr(unpaidOf(charge))} of ${charge.name} is unpaid now`);
      addEvent(db, loan, checker, at, {
        type: 'WAIVER',
        valueDate: db.businessDate,
        amount,
        summary: `Waived ${inr(amount)} of ${charge.name} — ${p.reason}`,
        data: { chargeId: charge.id, reason: p.reason },
      });
      refreshLoan(db, loan);
      appendAudit(db, at, checker, 'LOAN_CHARGE_WAIVED', 'LOAN', loan.id, { chargeId: charge.id, amount: p.amount });
      return true;
    }
    case 'LOAN_REVERSAL': {
      const loan = db.loans.find((l) => l.id === p.loanId);
      if (!loan) throw notFound('Loan');
      const target = loan.events.find((e) => e.id === p.txnId);
      if (!target) throw notFound('Transaction');
      if (target.reversedBy) throw conflict('Transaction already reversed');
      const later = loan.events.filter((e) => FINANCIAL.has(e.type) && !e.reversedBy && e.seq > target.seq);
      const rev = addEvent(db, loan, checker, at, {
        type: 'REVERSAL',
        valueDate: db.businessDate,
        amount: target.amount,
        reverses: target.id,
        summary: `Reversed ${txnLabel(target)}${later.length ? ` and ${later.length} later transaction(s): ${later.map((e) => `#${e.seq}`).join(', ')}` : ''} — ${p.reason}`,
        data: { reason: p.reason },
      });
      for (const e of [target, ...later]) e.reversedBy = rev.id;
      refreshLoan(db, loan);
      appendAudit(db, at, checker, 'LOAN_TXN_REVERSED', 'LOAN', loan.id, { txnId: target.id, alsoReversed: later.map((e) => e.id) });
      return true;
    }
    default:
      return false;
  }
}

/** Day-end: raise demands, DPD and asset class for every open loan as of the new business date. */
export function lendingDayEnd(db: MockDb): number {
  let n = 0;
  for (const loan of db.loans) {
    if (!loan.disbursedOn || loan.state.closedOn) continue;
    refreshLoan(db, loan);
    n += 1;
  }
  return n;
}

// ------------------------------------------------------------------ product validation
function validateProduct(p: LoanProduct): LoanProduct {
  const errors: FieldProblem[] = [];
  if (!p || typeof p !== 'object') throw bad('Body required');
  const n = (v: unknown) => (v === null || v === undefined || v === '' ? NaN : Number(v));
  if (!/^[A-Z0-9]{2,12}$/.test(p.code ?? '')) errors.push({ field: 'code', message: 'Code must be 2-12 upper-case letters/digits' });
  if (!p.name?.trim()) errors.push({ field: 'name', message: 'Name is required' });
  if (!['EQUATED', 'FIXED_PRINCIPAL', 'BULLET_TOTAL_INTEREST', 'BULLET_PERIODIC_INTEREST'].includes(p.repaymentMethod)) errors.push({ field: 'repaymentMethod', message: 'Invalid repayment method' });
  const [minA, maxA] = [n(p.minAmount), n(p.maxAmount)];
  if (!(minA > 0)) errors.push({ field: 'minAmount', message: 'Minimum amount must be positive' });
  if (!(maxA >= minA)) errors.push({ field: 'maxAmount', message: 'Maximum amount must be at least the minimum' });
  if (!Number.isInteger(p.minTenorMonths) || p.minTenorMonths < 1) errors.push({ field: 'minTenorMonths', message: 'Minimum tenor must be at least 1 month' });
  if (!Number.isInteger(p.maxTenorMonths) || p.maxTenorMonths < p.minTenorMonths) errors.push({ field: 'maxTenorMonths', message: 'Maximum tenor must be at least the minimum' });
  const [minR, maxR] = [n(p.minRate), n(p.maxRate)];
  if (!(minR >= 0 && minR <= 100)) errors.push({ field: 'minRate', message: 'Rate must be between 0 and 100' });
  if (!(maxR >= minR && maxR <= 100)) errors.push({ field: 'maxRate', message: 'Maximum rate must be between the minimum and 100' });
  if (p.penalChargeRate !== null && p.penalChargeRate !== undefined && p.penalChargeRate !== '' && !(n(p.penalChargeRate) >= 0 && n(p.penalChargeRate) <= 100)) errors.push({ field: 'penalChargeRate', message: 'Penal rate must be between 0 and 100' });
  if (p.coolingOffDays !== undefined && (!Number.isInteger(p.coolingOffDays) || p.coolingOffDays < 0)) errors.push({ field: 'coolingOffDays', message: 'Cooling-off days must be 0 or more' });
  if (p.interestTableCode && !RATE_TABLES[p.interestTableCode]) errors.push({ field: 'interestTableCode', message: `Unknown interest table ${p.interestTableCode}` });
  const seen = new Set<string>();
  (p.fees ?? []).forEach((f: FeeRule, i) => {
    const at = `Fee ${i + 1}`;
    if (!/^[A-Z0-9_]{2,20}$/.test(f.code ?? '')) errors.push({ field: `fees[${i}].code`, message: `${at}: code must be 2-20 upper-case letters, digits or _` });
    else if (seen.has(f.code)) errors.push({ field: `fees[${i}].code`, message: `${at}: duplicate code ${f.code}` });
    seen.add(f.code);
    if (!f.name?.trim()) errors.push({ field: `fees[${i}].name`, message: `${at}: name is required` });
    if (!['DISBURSEMENT', 'PRECLOSURE', 'PART_PREPAYMENT', 'BOUNCE', 'LATE_PAYMENT', 'CANCELLATION', 'ADHOC'].includes(f.event)) errors.push({ field: `fees[${i}].event`, message: `${at}: invalid event` });
    if (f.calcType === 'FIXED' && !(n(f.amount) >= 0)) errors.push({ field: `fees[${i}].amount`, message: `${at}: amount is required` });
    else if (f.calcType === 'PERCENT' && !(n(f.percent) > 0 && n(f.percent) <= 100)) errors.push({ field: `fees[${i}].percent`, message: `${at}: percent must be above 0 and at most 100` });
    else if (f.calcType === 'SLAB') {
      if (!f.slabs?.length) errors.push({ field: `fees[${i}].slabs`, message: `${at}: at least one slab is required` });
      (f.slabs ?? []).forEach((s, j) => {
        if (!(n(s.from) >= 0 && n(s.to) >= n(s.from) && n(s.fee) >= 0)) errors.push({ field: `fees[${i}].slabs[${j}]`, message: `${at}, slab ${j + 1}: from ≤ to and fee ≥ 0 are required` });
      });
    } else if (!['FIXED', 'PERCENT', 'SLAB'].includes(f.calcType)) errors.push({ field: `fees[${i}].calcType`, message: `${at}: invalid calculation type` });
    if (!Number.isNaN(n(f.minAmount)) && !Number.isNaN(n(f.maxAmount)) && n(f.maxAmount) < n(f.minAmount)) errors.push({ field: `fees[${i}].maxAmount`, message: `${at}: max must be at least min` });
    if (f.event === 'DISBURSEMENT' && f.calcType === 'PERCENT' && Number.isNaN(n(f.maxAmount)) && n(f.percent) > 10) errors.push({ field: `fees[${i}].percent`, message: `${at}: an upfront fee above 10% needs a cap` });
  });
  if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
  const s = (v: unknown) => (v === null || v === undefined || v === '' ? null : String(v));
  return {
    code: p.code,
    name: p.name.trim(),
    repaymentMethod: p.repaymentMethod,
    minAmount: C.fromPaise(C.toPaise(String(p.minAmount))),
    maxAmount: C.fromPaise(C.toPaise(String(p.maxAmount))),
    minTenorMonths: p.minTenorMonths,
    maxTenorMonths: p.maxTenorMonths,
    minRate: C.pct(p.minRate),
    maxRate: C.pct(p.maxRate),
    interestTableCode: p.interestTableCode || null,
    rateType: p.rateType ?? 'FIXED',
    dayCount: p.dayCount ?? 'ACTUAL_365',
    rounding: p.rounding ?? 'RUPEE_HALF_UP',
    penalChargeRate: s(p.penalChargeRate) === null ? null : C.pct(p.penalChargeRate),
    maxMoratoriumMonths: p.maxMoratoriumMonths ?? 0,
    coolingOffDays: p.coolingOffDays ?? 0,
    secured: !!p.secured,
    appropriationSequence: p.appropriationSequence?.length ? p.appropriationSequence : APPROPRIATION,
    appropriationMode: p.appropriationMode ?? 'BY_DEMAND',
    prepaymentMode: p.prepaymentMode ?? 'REDUCE_TENURE',
    status: p.status ?? 'ACTIVE',
    fees: (p.fees ?? []).map((f) => ({
      code: f.code,
      name: f.name.trim(),
      event: f.event,
      calcType: f.calcType,
      amount: f.calcType === 'FIXED' ? s(f.amount) : null,
      percent: f.calcType === 'PERCENT' ? s(f.percent) : null,
      slabs: f.calcType === 'SLAB' ? (f.slabs ?? []).map((x) => ({ from: String(x.from), to: String(x.to), fee: String(x.fee) })) : undefined,
      minAmount: s(f.minAmount),
      maxAmount: s(f.maxAmount),
      gstRate: s(f.gstRate) ?? '18',
      taxTreatment: f.taxTreatment ?? 'EXCLUSIVE',
      deductFromDisbursal: !!f.deductFromDisbursal,
    })),
  };
}

// ------------------------------------------------------------------ routes
type Result = { status: number; body: unknown };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
}
export interface LendingRouter {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (
    user: DemoUser,
    entityType: string,
    action: string,
    payload: ApprovalPayload,
    proposed: Record<string, unknown>,
    current?: Record<string, unknown> | null,
    entityId?: string | null,
    amount?: string | null,
  ) => Result;
  nowIso: () => string;
}

const ok = (body: unknown): Result => ({ status: 200, body });

export function registerLendingRoutes(db: MockDb, r: LendingRouter) {
  const { on, require, propose, nowIso } = r;
  const can = (u: DemoUser, p: string) => u.permissions.includes(p);
  const requireAny = (u: DemoUser, ...ps: string[]) => {
    if (!ps.some((p) => can(u, p))) require(u, ps[0]);
  };
  const findLoan = (id: string) => {
    const loan = db.loans.find((l) => l.id === id || l.loanNo === id);
    if (!loan) throw notFound('Loan');
    return loan;
  };
  const view = (loan: StoredLoan) => loanView(db, loan);
  const hasPending = (kind: ApprovalPayload['kind'], pred: (p: ApprovalPayload) => boolean) =>
    db.approvals.find((s) => s.approval.status === 'PENDING' && s.payload.kind === kind && pred(s.payload));

  function assertServiceable(loan: StoredLoan) {
    const s = loan.state.status;
    if (s === 'FROZEN') throw conflict('Loan is frozen', `Loan ${loan.loanNo}: the account is frozen; transactions are blocked until it is unfrozen`);
    if (s !== 'ACTIVE') throw conflict('Loan is not active', `Loan ${loan.loanNo} is ${s}`);
  }
  function positiveAmount(v: unknown, field = 'amount'): number {
    const a = money(v);
    if (a === null || Number.isNaN(a) || a <= 0) throw bad('Amount must be a positive decimal', [{ field, message: 'Enter an amount greater than zero' }]);
    return a;
  }
  function reasonOf(body: unknown): string {
    const reason = (body as { reason?: string } | null)?.reason?.trim();
    if (!reason) throw bad('A reason is required', [{ field: 'reason', message: 'Required' }]);
    if (reason.length > 500) throw bad('Reason must be at most 500 characters', [{ field: 'reason', message: 'Too long' }]);
    return reason;
  }
  const lastFinancialDate = (loan: StoredLoan) =>
    loan.events.filter((e) => (FINANCIAL.has(e.type) || e.type === 'DISBURSEMENT') && !e.reversedBy).reduce((m, e) => (e.valueDate > m ? e.valueDate : m), '');

  // products
  on('GET', '/api/v1/loan-products', ({ user }) => {
    requireAny(user, P.productView, P.loanView);
    return ok([...db.loanProducts].sort((a, b) => a.code.localeCompare(b.code)));
  });
  on('GET', '/api/v1/loan-products/{code}', ({ user, params }) => {
    requireAny(user, P.productView, P.loanView);
    const p = db.loanProducts.find((x) => x.code === params.code);
    if (!p) throw notFound(`Loan product ${params.code}`);
    return ok(p);
  });
  on('POST', '/api/v1/loan-products', ({ user, body }) => {
    require(user, P.productPropose);
    const product = validateProduct(body as LoanProduct);
    if (hasPending('LOAN_PRODUCT', (p) => p.kind === 'LOAN_PRODUCT' && p.product.code === product.code)) {
      throw conflict('Change already pending', `Product ${product.code} already has a pending change`);
    }
    const existing = db.loanProducts.find((x) => x.code === product.code);
    const { version: _v, ...current } = existing ?? ({} as LoanProduct);
    return propose(user, 'LOAN_PRODUCT', existing ? 'UPDATE' : 'CREATE', { kind: 'LOAN_PRODUCT', product }, { ...product }, existing ? { ...current } : null, existing ? product.code : null);
  });

  // preview and booking
  on('POST', '/api/v1/loans/preview', ({ user, body }) => {
    requireAny(user, P.loanCreate, P.loanView);
    return ok(previewLoan(db, body as LoanApplication));
  });
  on('GET', '/api/v1/loans', ({ user, url }) => {
    require(user, P.loanView);
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase();
    const status = url.searchParams.get('status');
    const page = Math.max(0, Number(url.searchParams.get('page') ?? 0));
    const size = Math.min(100, Math.max(1, Number(url.searchParams.get('size') ?? 20)));
    const rows = db.loans
      .map((l) => ({ l, s: summaryView(db, l) }))
      .filter(({ l, s }) =>
        (!status || s.status === status) &&
        (!q || l.loanNo.startsWith(q) || s.customerNo === q || (s.customerName ?? '').toLowerCase().includes(q) || (l.externalRef ?? '').toLowerCase() === q),
      )
      .sort((a, b) => a.l.loanNo.localeCompare(b.l.loanNo))
      .map(({ s }) => s);
    return ok(rows.slice(page * size, page * size + size));
  });
  on('POST', '/api/v1/loans', ({ user, body }) => {
    require(user, P.loanCreate);
    const a = body as LoanApplication;
    const ref = a?.externalRef?.trim() || null;
    if (ref) {
      const existing = db.loans.find((l) => l.externalRef === ref);
      if (existing) return ok(view(existing));
    }
    const resolved = resolve(db, a);
    if (resolved.customer.kycStatus !== 'VERIFIED') throw bad('Customer KYC is not verified', [{ field: 'customerId', message: 'KYC must be verified before a loan is sanctioned' }]);
    const loan = bookLoan(db, resolved, ref, db.businessDate);
    appendAudit(db, nowIso(), user.username, 'LOAN_CREATED', 'LOAN', loan.id, { loanNo: loan.loanNo, amount: C.fromPaise(loan.amount) });
    return { status: 201, body: view(loan) };
  });
  on('GET', '/api/v1/loans/{id}', ({ user, params }) => {
    require(user, P.loanView);
    return ok(view(findLoan(params.id)));
  });
  on('GET', '/api/v1/loans/{id}/schedule', ({ user, params }) => {
    require(user, P.loanView);
    return ok(scheduleView(findLoan(params.id)));
  });
  on('GET', '/api/v1/loans/{id}/transactions', ({ user, params }) => {
    require(user, P.loanView);
    return ok([...findLoan(params.id).events].sort((a, b) => b.seq - a.seq).map(txnView));
  });
  on('GET', '/api/v1/loans/{id}/kfs', ({ user, params }) => {
    require(user, P.loanView);
    return ok(findLoan(params.id).kfs);
  });
  on('POST', '/api/v1/loans/{id}/kfs-acceptance', ({ user, params, body }) => {
    require(user, P.loanCreate);
    const loan = findLoan(params.id);
    if (loan.state.status !== 'SANCTIONED') throw conflict('Loan is not sanctioned', `Loan ${loan.loanNo} is ${loan.state.status}`);
    if (loan.kfsAcceptedAt) throw conflict('KFS already accepted', `Accepted at ${loan.kfsAcceptedAt}`);
    const b = (body ?? {}) as { channel?: string; evidenceRef?: string };
    loan.kfsAcceptedAt = nowIso();
    loan.kfsChannel = b.channel?.trim() || 'BRANCH';
    appendAudit(db, loan.kfsAcceptedAt, user.username, 'LOAN_KFS_ACCEPTED', 'LOAN', loan.id, { channel: loan.kfsChannel, evidenceRef: b.evidenceRef ?? null });
    return ok(view(loan));
  });
  on('POST', '/api/v1/loans/{id}/disbursement', ({ user, params, body }) => {
    require(user, P.loanDisburse);
    const loan = findLoan(params.id);
    if (loan.state.status !== 'SANCTIONED') throw conflict('Loan is not sanctioned', `Loan ${loan.loanNo} is ${loan.state.status}`);
    if (!loan.kfsAcceptedAt) throw conflict('KFS not accepted', 'The borrower must accept the Key Fact Statement before disbursement');
    const pending = hasPending('LOAN_DISBURSEMENT', (p) => p.kind === 'LOAN_DISBURSEMENT' && p.loanId === loan.id);
    if (pending) throw conflict('Disbursement already pending', `Loan ${loan.loanNo} already has a disbursement awaiting approval`, { approvalId: pending.approval.id });
    const b = (body ?? {}) as { beneficiaryName?: string; beneficiaryAccount?: string; ifsc?: string; mode?: string };
    const mode = b.mode?.trim() || 'IMPS';
    if (b.ifsc && !/^[A-Z]{4}0[A-Z0-9]{6}$/.test(b.ifsc)) throw bad('IFSC must look like HDFC0001234', [{ field: 'ifsc', message: 'Invalid IFSC' }]);
    if (can(user, LOAN_STP)) {
      disburse(db, loan, user.username, nowIso(), mode);
      return ok(view(loan));
    }
    const account = b.beneficiaryAccount?.trim() || null;
    const cust = customerOf(db, loan);
    return propose(
      user,
      'LOAN_DISBURSEMENT',
      'DISBURSE',
      { kind: 'LOAN_DISBURSEMENT', loanId: loan.id, mode, beneficiaryName: b.beneficiaryName?.trim() || null, beneficiaryAccount: account, ifsc: b.ifsc || null },
      {
        loanNo: loan.loanNo,
        customer: cust ? displayName(cust) : null,
        productCode: loan.product.code,
        amount: C.fromPaise(loan.amount),
        netDisbursal: loan.kfs.netDisbursal,
        mode,
        beneficiaryName: b.beneficiaryName?.trim() || null,
        beneficiaryAccount: account ? `XXXX${account.slice(-4)}` : null,
        ifsc: b.ifsc || null,
      },
      null,
      loan.id,
      C.fromPaise(loan.amount),
    );
  });

  // servicing
  on('POST', '/api/v1/loans/{id}/repayments', ({ user, params, body }) => {
    require(user, P.loanRepay);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    const b = (body ?? {}) as { amount?: string; valueDate?: string; mode?: string; reference?: string };
    const amount = positiveAmount(b.amount);
    const valueDate = b.valueDate || db.businessDate;
    if (!ISO_DATE.test(valueDate)) throw bad('Invalid value date', [{ field: 'valueDate', message: 'Invalid date' }]);
    if (valueDate > db.businessDate) throw bad('Value date cannot be after the business date', [{ field: 'valueDate', message: 'After business date' }]);
    const last = lastFinancialDate(loan);
    if (valueDate < last) throw bad(`Value date cannot be before the last transaction (${formatDate(last)})`, [{ field: 'valueDate', message: 'Before the last transaction' }]);
    const before = loan.state;
    const mode = b.mode?.trim() || 'CASH';
    const ev = addEvent(db, loan, user.username, nowIso(), { type: 'REPAYMENT', valueDate, amount, summary: '', data: { mode, reference: b.reference?.trim() || undefined } });
    refreshLoan(db, loan);
    const after = loan.state;
    const sum = (s: LoanState, f: (d: LoanState['demands'][number]) => number) => s.demands.reduce((a, d) => a + f(d), 0);
    const interest = sum(after, (d) => d.interestPaid) - sum(before, (d) => d.interestPaid);
    const principal = after.principalPaid - before.principalPaid;
    const charges = after.charges.reduce((a, c) => a + c.paid, 0) - before.charges.reduce((a, c) => a + c.paid, 0);
    const advance = after.advance - before.advance;
    const parts = [interest && `interest ${inr(interest)}`, principal && `principal ${inr(principal)}`, charges && `charges ${inr(charges)}`, advance > 0 && `advance ${inr(advance)}`].filter(Boolean);
    ev.summary = `Receipt via ${mode}${b.reference ? ` (${b.reference.trim()})` : ''}${parts.length ? `: ${parts.join(', ')}` : ''}`;
    appendAudit(db, ev.createdAt, user.username, 'LOAN_REPAYMENT', 'LOAN', loan.id, { amount: C.fromPaise(amount) });
    return ok(view(loan));
  });
  on('POST', '/api/v1/loans/{id}/prepayments', ({ user, params, body }) => {
    require(user, P.loanRepay);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    const b = (body ?? {}) as { amount?: string; mode?: string };
    const amount = positiveAmount(b.amount);
    const mode = b.mode || loan.product.prepaymentMode || 'REDUCE_TENURE';
    if (mode !== 'REDUCE_EMI' && mode !== 'REDUCE_TENURE') throw bad('Mode must be REDUCE_EMI or REDUCE_TENURE', [{ field: 'mode', message: 'Invalid mode' }]);
    const st = loan.state;
    const overdue = arrearsOf(st) + unpaidCharges(st);
    if (overdue > 0) throw conflict('Overdue dues must be cleared first', `Pay the overdue ${inr(overdue)} before a part-prepayment`);
    const fut = futurePrincipal(st);
    if (amount >= fut) throw bad(`Amount must be less than the future principal ${inr(fut)}; use pre-closure to close the loan`, [{ field: 'amount', message: 'Too large for a part-prepayment' }]);
    const ev = addEvent(db, loan, user.username, nowIso(), { type: 'PREPAYMENT', valueDate: db.businessDate, amount, summary: '', data: { mode } });
    refreshLoan(db, loan);
    const left = loan.state.rows.length - loan.state.raised;
    ev.summary = mode === 'REDUCE_EMI'
      ? `Part-prepayment, reduce EMI: new EMI ${loan.state.emi !== null ? inr(loan.state.emi) : '—'} over ${left} instalment(s)`
      : `Part-prepayment, reduce tenure: ${left} instalment(s) left`;
    const ppRule = (loan.product.fees ?? []).find((f) => f.event === 'PART_PREPAYMENT');
    if (ppRule) {
      const c = C.computeFee(ppRule, amount, loan.supplierState, loan.recipientState);
      if (c.total > 0) {
        addEvent(db, loan, user.username, nowIso(), { type: 'FEE_CHARGE', valueDate: db.businessDate, amount: c.total, summary: `${c.code} ${c.name} ${inr(c.total)} incl. GST`, data: { chargeId: `CHG-${uuid().slice(0, 8)}`, code: c.code, name: c.name } });
        refreshLoan(db, loan);
      }
    }
    appendAudit(db, ev.createdAt, user.username, 'LOAN_PREPAYMENT', 'LOAN', loan.id, { amount: C.fromPaise(amount), mode });
    return ok(view(loan));
  });
  on('GET', '/api/v1/loans/{id}/preclosure-quote', ({ user, params }) => {
    require(user, P.loanView);
    const loan = findLoan(params.id);
    if (loan.state.status !== 'ACTIVE' && loan.state.status !== 'FROZEN') throw conflict('Loan is not active', `Loan ${loan.loanNo} is ${loan.state.status}`);
    return ok(preclosureQuote(loan, db.businessDate));
  });
  on('POST', '/api/v1/loans/{id}/preclosure', ({ user, params, body }) => {
    require(user, P.loanRepay);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    const amount = positiveAmount((body as { amount?: string } | null)?.amount);
    const quote = preclosureQuote(loan, db.businessDate);
    if (amount !== C.toPaise(quote.total)) {
      throw conflict('Amount does not match the pre-closure quote', `Pre-closure needs exactly ${formatINR(quote.total!)} today; ${inr(amount)} was offered`, { quote });
    }
    addEvent(db, loan, user.username, nowIso(), { type: 'PRECLOSURE', valueDate: db.businessDate, amount, summary: `Pre-closed: principal ${formatINR(quote.principal!)}, dues ${formatINR(quote.overdueDues!)}, interest ${formatINR(quote.accruedInterest!)}, charges ${formatINR(quote.charges!)}, foreclosure fee ${formatINR(quote.foreclosureFee!)}` });
    refreshLoan(db, loan);
    appendAudit(db, nowIso(), user.username, 'LOAN_PRECLOSED', 'LOAN', loan.id, { amount: quote.total });
    return ok(view(loan));
  });
  on('GET', '/api/v1/loans/{id}/cancellation-quote', ({ user, params }) => {
    require(user, P.loanView);
    const loan = findLoan(params.id);
    if (loan.state.status !== 'ACTIVE' && loan.state.status !== 'FROZEN') throw conflict('Loan is not active', `Loan ${loan.loanNo} is ${loan.state.status}`);
    if (!withinCoolingOff(loan, db.businessDate)) throw conflict('Cooling-off period is over', `Cancellation was possible until ${formatDate(addDays(loan.disbursedOn!, loan.product.coolingOffDays ?? 0))}`);
    return ok({ total: C.fromPaise(cancellationTotal(loan, db.businessDate)), asOf: db.businessDate });
  });
  on('POST', '/api/v1/loans/{id}/cancellation', ({ user, params, body }) => {
    require(user, P.loanRepay);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    if (!withinCoolingOff(loan, db.businessDate)) throw conflict('Cooling-off period is over', `Cancellation was possible until ${formatDate(addDays(loan.disbursedOn!, loan.product.coolingOffDays ?? 0))}`);
    const amount = positiveAmount((body as { amount?: string } | null)?.amount);
    const total = cancellationTotal(loan, db.businessDate);
    if (amount !== total) throw conflict('Amount does not match the cancellation quote', `Cancellation needs exactly ${inr(total)} today`, { total: C.fromPaise(total) });
    addEvent(db, loan, user.username, nowIso(), { type: 'CANCELLATION', valueDate: db.businessDate, amount, summary: `Cancelled in the cooling-off period (${C.daysBetween(loan.disbursedOn!, db.businessDate)} day(s) used); disclosed fees retained` });
    refreshLoan(db, loan);
    appendAudit(db, nowIso(), user.username, 'LOAN_CANCELLED', 'LOAN', loan.id, { amount: C.fromPaise(amount) });
    return ok(view(loan));
  });
  on('POST', '/api/v1/loans/{id}/charges', ({ user, params, body }) => {
    require(user, P.loanRepay);
    const loan = findLoan(params.id);
    assertServiceable(loan);
    const b = (body ?? {}) as { feeCode?: string; base?: string };
    const rule = (loan.product.fees ?? []).find((f) => f.code === b.feeCode);
    if (!rule) throw bad('Unknown fee for this product', [{ field: 'feeCode', message: 'Select a fee of the loan’s product' }]);
    if (rule.event === 'DISBURSEMENT' || rule.event === 'PRECLOSURE') throw bad(`${rule.name} is charged automatically`, [{ field: 'feeCode', message: 'Charged by the system' }]);
    const st = loan.state;
    const explicit = money(b.base);
    if (explicit !== null && (Number.isNaN(explicit) || explicit < 0)) throw bad('Base must be a decimal', [{ field: 'base', message: 'Invalid amount' }]);
    const base = explicit ?? (rule.event === 'BOUNCE' ? (st.emi ?? st.rows[st.raised]?.instalment ?? loan.amount) : principalOutstanding(loan));
    const c = C.computeFee(rule, base, loan.supplierState, loan.recipientState);
    if (c.total <= 0) throw bad('The fee works out to zero for this base', [{ field: 'base', message: 'Zero fee' }]);
    const gst = c.cgst + c.sgst + c.igst;
    addEvent(db, loan, user.username, nowIso(), {
      type: 'FEE_CHARGE',
      valueDate: db.businessDate,
      amount: c.total,
      summary: `${c.name} ${inr(c.fee)} + GST ${inr(gst)}${c.igst ? ' (IGST)' : ' (CGST+SGST)'}`,
      data: { chargeId: `CHG-${uuid().slice(0, 8)}`, code: c.code, name: c.name },
    });
    refreshLoan(db, loan);
    appendAudit(db, nowIso(), user.username, 'LOAN_FEE_CHARGED', 'LOAN', loan.id, { feeCode: c.code, amount: C.fromPaise(c.total) });
    return ok(view(loan));
  });
  on('POST', '/api/v1/loans/{id}/charges/{chargeId}/waiver', ({ user, params, body }) => {
    require(user, P.loanWaive);
    const loan = findLoan(params.id);
    const charge = loan.state.charges.find((c) => c.id === params.chargeId);
    if (!charge) throw notFound('Charge');
    const b = (body ?? {}) as { amount?: string; reason?: string };
    const amount = positiveAmount(b.amount);
    const reason = reasonOf(b);
    if (amount > unpaidOf(charge)) throw bad(`Waiver cannot exceed the unpaid ${inr(unpaidOf(charge))}`, [{ field: 'amount', message: 'More than the unpaid amount' }]);
    if (hasPending('LOAN_WAIVER', (p) => p.kind === 'LOAN_WAIVER' && p.loanId === loan.id && p.chargeId === charge.id)) throw conflict('Waiver already pending', `${charge.name} already has a waiver awaiting approval`);
    return propose(
      user, 'LOAN_WAIVER', 'WAIVE',
      { kind: 'LOAN_WAIVER', loanId: loan.id, chargeId: charge.id, amount: C.fromPaise(amount), reason },
      { loanNo: loan.loanNo, charge: `${charge.code} — ${charge.name}`, chargeDate: charge.date, unpaid: C.fromPaise(unpaidOf(charge)), waiver: C.fromPaise(amount), reason },
      null, loan.id, C.fromPaise(amount),
    );
  });
  on('POST', '/api/v1/loans/{id}/transactions/{txnId}/reverse', ({ user, params, body }) => {
    require(user, P.loanReverse);
    const loan = findLoan(params.id);
    const txn = loan.events.find((e) => e.id === params.txnId);
    if (!txn) throw notFound('Transaction');
    const reason = reasonOf(body);
    if (!REVERSIBLE.has(txn.type)) throw bad(`${txn.type} transactions cannot be reversed`, [{ field: 'txnId', message: 'Not reversible' }]);
    if (txn.reversedBy) throw conflict('Transaction already reversed');
    if (hasPending('LOAN_REVERSAL', (p) => p.kind === 'LOAN_REVERSAL' && p.loanId === loan.id)) throw conflict('Reversal already pending', `Loan ${loan.loanNo} already has a reversal awaiting approval`);
    const later = loan.events.filter((e) => FINANCIAL.has(e.type) && !e.reversedBy && e.seq > txn.seq);
    return propose(
      user, 'LOAN_REVERSAL', 'REVERSE',
      { kind: 'LOAN_REVERSAL', loanId: loan.id, txnId: txn.id, reason },
      { loanNo: loan.loanNo, transaction: txnLabel(txn), alsoReversed: later.map(txnLabel), reason },
      { loanNo: loan.loanNo, transaction: txnLabel(txn), summary: txn.summary },
      loan.id, txn.amount === null ? null : C.fromPaise(txn.amount),
    );
  });
  on('POST', '/api/v1/loans/{id}/freeze', ({ user, params, body }) => {
    require(user, P.loanAdmin);
    const loan = findLoan(params.id);
    const reason = reasonOf(body);
    if (loan.state.status !== 'ACTIVE') throw conflict('Only active loans can be frozen', `Loan ${loan.loanNo} is ${loan.state.status}`);
    loan.frozen = true;
    addEvent(db, loan, user.username, nowIso(), { type: 'FREEZE', valueDate: db.businessDate, amount: null, summary: `Frozen — ${reason}`, data: { reason } });
    refreshLoan(db, loan);
    appendAudit(db, nowIso(), user.username, 'LOAN_FROZEN', 'LOAN', loan.id, { reason });
    return ok(view(loan));
  });
  on('POST', '/api/v1/loans/{id}/unfreeze', ({ user, params, body }) => {
    require(user, P.loanAdmin);
    const loan = findLoan(params.id);
    const reason = reasonOf(body);
    if (loan.state.status !== 'FROZEN') throw conflict('Loan is not frozen', `Loan ${loan.loanNo} is ${loan.state.status}`);
    loan.frozen = false;
    addEvent(db, loan, user.username, nowIso(), { type: 'UNFREEZE', valueDate: db.businessDate, amount: null, summary: `Unfrozen — ${reason}`, data: { reason } });
    refreshLoan(db, loan);
    appendAudit(db, nowIso(), user.username, 'LOAN_UNFROZEN', 'LOAN', loan.id, { reason });
    return ok(view(loan));
  });
}

// ------------------------------------------------------------------ seed loans
interface SeedLoan {
  customer: number;
  product: string;
  amount: string;
  rate: string | null;
  tenor: number;
  disbursedOn: string | null;
  openDate: string;
  externalRef?: string;
  /** Instalment numbers repaid in full on their due dates. */
  paidRows?: number[];
  extra?: Array<{ date: string; amount: string; mode: string }>;
}

const SEED_LOANS: SeedLoan[] = [
  // ACTIVE and current: every EMI paid on time.
  { customer: 0, product: 'PL01', amount: '200000', rate: '16', tenor: 24, openDate: '2026-01-14', disbursedOn: '2026-01-15', externalRef: 'LOS-CLAUDE-TEST-0001', paidRows: [1, 2, 3, 4, 5] },
  // ACTIVE, disbursed two days ago: still inside the 3-day cooling-off window.
  { customer: 1, product: 'PL01', amount: '75000', rate: '20', tenor: 12, openDate: '2026-06-27', disbursedOn: '2026-06-28', externalRef: 'LOS-CLAUDE-TEST-0002' },
  // SMA-1: May and June EMIs unpaid (52 days past due on 30 Jun).
  { customer: 3, product: 'PL01', amount: '150000', rate: '18', tenor: 36, openDate: '2025-12-09', disbursedOn: '2025-12-10', paidRows: [1, 2, 3, 4] },
  // Sub-standard NPA; a part payment in May does not upgrade it (all arrears must be cleared).
  { customer: 4, product: 'PL01', amount: '50000', rate: '22', tenor: 12, openDate: '2025-10-04', disbursedOn: '2025-10-05', paidRows: [1, 2], extra: [{ date: '2026-05-20', amount: '3000', mode: 'UPI' }] },
  // SANCTIONED, waiting for the borrower to accept the KFS; rate from the product table.
  { customer: 6, product: 'PL01', amount: '300000', rate: null, tenor: 48, openDate: '2026-06-29', disbursedOn: null, externalRef: 'LOS-CLAUDE-TEST-0005' },
  // CLOSED micro bullet loan, repaid at maturity.
  { customer: 7, product: 'ML01', amount: '20000', rate: '24', tenor: 1, openDate: '2026-04-01', disbursedOn: '2026-04-01', paidRows: [1] },
];

export function seedLending(db: MockDb) {
  db.loanProducts = clone(SEED_PRODUCTS);
  for (const s of SEED_LOANS) {
    const customer = db.customers[s.customer];
    const r = resolve(
      db,
      { productCode: s.product, customerId: customer.id, amount: s.amount, tenorMonths: s.tenor, rate: s.rate, disbursalDate: s.disbursedOn ?? db.businessDate, externalRef: s.externalRef },
      { allowPastDisbursal: true },
    );
    const loan = bookLoan(db, r, s.externalRef ?? null, s.openDate);
    const at = `${s.openDate}T10:00:00.000Z`;
    appendAudit(db, at, 'maker', 'LOAN_CREATED', 'LOAN', loan.id, { loanNo: loan.loanNo });
    if (!s.disbursedOn) continue;
    loan.kfsAcceptedAt = `${s.openDate}T11:30:00.000Z`;
    loan.kfsChannel = 'OTP';
    const saved = db.businessDate;
    db.businessDate = s.disbursedOn;
    disburse(db, loan, 'checker', `${s.disbursedOn}T12:00:00.000Z`, 'IMPS');
    const rows = loan.state.rows;
    const txns: Array<{ date: string; amount: number; mode: string }> = [
      ...(s.paidRows ?? []).map((n) => ({ date: rows[n - 1].dueDate, amount: rows[n - 1].instalment, mode: 'NACH' })),
      ...(s.extra ?? []).map((x) => ({ date: x.date, amount: C.toPaise(x.amount), mode: x.mode })),
    ].sort((a, b) => a.date.localeCompare(b.date));
    for (const t of txns) {
      db.businessDate = t.date;
      addEvent(db, loan, 'system', `${t.date}T09:00:00.000Z`, { type: 'REPAYMENT', valueDate: t.date, amount: t.amount, summary: `Receipt via ${t.mode}`, data: { mode: t.mode } });
    }
    db.businessDate = saved;
    refreshLoan(db, loan);
  }
}

