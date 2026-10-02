import type { Approval, Branch, EodRun, EodStep, GlHead, Holiday, TaxRate, VoucherInput } from '../api/types';
import { DEMO_TENANT, DEMO_TENANT_NAME } from '../auth/demoUsers';
import { customerNumber } from '../lib/luhn';
import { maskMobile, maskPan } from '../lib/mask';
import { appendAudit, uuid, type ApprovalPayload, type MockDb, type StoredCustomer } from './db';
import { postVoucher } from './ledger';
import { seedCustomerExtras } from './customerExtras';
import { seedLending } from './lending';
import { seedPlatform } from './platform';

export const SEED_BUSINESS_DATE = '2026-06-30';
export const CUSTOMER_SERIES_PREFIX = '9001';

export const EOD_STEPS = [
  'Pre-checks',
  'Close inactive accounts',
  'NPA marking',
  'Interest accrual',
  'Demand raising',
  'Provisioning',
  'GL balance snapshot',
  'Trial balance gate',
  'Advance business date',
];

type H = [code: string, name: string, category: GlHead['category'], parent: string | null, posting: boolean];

/** A compact NBFC chart of accounts. Group heads are non-posting; only leaves accept entries. */
const COA: H[] = [
  ['1', 'Assets', 'ASSET', null, false],
  ['1100', 'Cash and bank balances', 'ASSET', '1', false],
  ['1101', 'Cash in hand', 'ASSET', '1100', true],
  ['1110', 'Bank - current account (HDFC)', 'ASSET', '1100', true],
  ['1111', 'Bank - current account (SBI)', 'ASSET', '1100', true],
  ['1200', 'Loans and advances', 'ASSET', '1', false],
  ['1201', 'Personal loans - principal', 'ASSET', '1200', true],
  ['1202', 'Business loans - principal', 'ASSET', '1200', true],
  ['1210', 'Interest receivable', 'ASSET', '1200', true],
  ['1211', 'Penal charges receivable', 'ASSET', '1200', true],
  ['1300', 'Other assets', 'ASSET', '1', false],
  ['1301', 'GST input credit - CGST', 'ASSET', '1300', true],
  ['1302', 'GST input credit - SGST', 'ASSET', '1300', true],
  ['1303', 'GST input credit - IGST', 'ASSET', '1300', true],
  ['1310', 'Prepaid expenses', 'ASSET', '1300', true],
  ['1320', 'Inter-branch account', 'ASSET', '1300', true],
  ['1330', 'Furniture and fixtures', 'ASSET', '1300', true],
  ['2', 'Liabilities', 'LIABILITY', null, false],
  ['2100', 'Borrowings', 'LIABILITY', '2', false],
  ['2101', 'Term loans from banks', 'LIABILITY', '2100', true],
  ['2102', 'Non-convertible debentures', 'LIABILITY', '2100', true],
  ['2200', 'Statutory dues', 'LIABILITY', '2', false],
  ['2201', 'GST output - CGST', 'LIABILITY', '2200', true],
  ['2202', 'GST output - SGST', 'LIABILITY', '2200', true],
  ['2203', 'GST output - IGST', 'LIABILITY', '2200', true],
  ['2210', 'TDS payable', 'LIABILITY', '2200', true],
  ['2300', 'Provisions', 'LIABILITY', '2', false],
  ['2301', 'Provision for standard assets', 'LIABILITY', '2300', true],
  ['2302', 'Provision for NPA', 'LIABILITY', '2300', true],
  ['2400', 'Other liabilities', 'LIABILITY', '2', false],
  ['2401', 'Sundry creditors', 'LIABILITY', '2400', true],
  ['2402', 'Interest payable on borrowings', 'LIABILITY', '2400', true],
  ['3', 'Equity', 'EQUITY', null, false],
  ['3001', 'Share capital', 'EQUITY', '3', true],
  ['3002', 'Statutory reserve (Sec 45-IC)', 'EQUITY', '3', true],
  ['3003', 'Retained earnings', 'EQUITY', '3', true],
  ['4', 'Income', 'INCOME', null, false],
  ['4100', 'Interest income', 'INCOME', '4', false],
  ['4101', 'Interest income - personal loans', 'INCOME', '4100', true],
  ['4102', 'Interest income - business loans', 'INCOME', '4100', true],
  ['4200', 'Fee and other income', 'INCOME', '4', false],
  ['4201', 'Processing fee income', 'INCOME', '4200', true],
  ['4202', 'Penal charges income', 'INCOME', '4200', true],
  ['4203', 'Foreclosure charges', 'INCOME', '4200', true],
  ['5', 'Expenses', 'EXPENSE', null, false],
  ['5100', 'Finance costs', 'EXPENSE', '5', false],
  ['5101', 'Interest expense - borrowings', 'EXPENSE', '5100', true],
  ['5200', 'Provisions and write-offs', 'EXPENSE', '5', false],
  ['5201', 'Provision expense - standard assets', 'EXPENSE', '5200', true],
  ['5202', 'Provision expense - NPA', 'EXPENSE', '5200', true],
  ['5300', 'Operating expenses', 'EXPENSE', '5', false],
  ['5301', 'Salaries and wages', 'EXPENSE', '5300', true],
  ['5302', 'Rent', 'EXPENSE', '5300', true],
  ['5303', 'Bank charges', 'EXPENSE', '5300', true],
];

const BRANCHES: Branch[] = [
  { code: 'HO', name: 'Head Office - Kochi', ifsc: null, stateCode: '32', parentCode: null, headOffice: true, status: 'ACTIVE' },
  { code: 'MUM', name: 'Mumbai - Andheri', ifsc: null, stateCode: '27', parentCode: 'HO', headOffice: false, status: 'ACTIVE' },
];

const HOLIDAYS: Holiday[] = [
  { branchCode: null, day: '2026-01-26', reason: 'Republic Day' },
  { branchCode: 'MUM', day: '2026-05-01', reason: 'Maharashtra Day' },
  { branchCode: null, day: '2026-08-15', reason: 'Independence Day' },
  { branchCode: 'HO', day: '2026-08-26', reason: 'Thiruvonam' },
  { branchCode: null, day: '2026-10-02', reason: 'Gandhi Jayanti' },
  { branchCode: null, day: '2026-12-25', reason: 'Christmas' },
  { branchCode: null, day: '2026-07-04', reason: 'Demo holiday (mock calendar)' },
];

const TAX_RATES: TaxRate[] = [
  { code: 'GST18', taxType: 'GST', ratePercent: '18.00', effectiveFrom: '2017-07-01', effectiveTo: null },
  { code: 'GST12', taxType: 'GST', ratePercent: '12.00', effectiveFrom: '2017-07-01', effectiveTo: null },
  { code: 'TDS194A', taxType: 'TDS', ratePercent: '10.00', effectiveFrom: '2020-04-01', effectiveTo: null },
  { code: 'TDS194I', taxType: 'TDS', ratePercent: '10.00', effectiveFrom: '2020-04-01', effectiveTo: null },
  { code: 'TDS194C', taxType: 'TDS', ratePercent: '2.00', effectiveFrom: '2020-04-01', effectiveTo: null },
];

type L = [branch: string, gl: string, side: 'DR' | 'CR', amount: string];
function v(type: VoucherInput['voucherType'], date: string, description: string, lines: L[], reference?: string): VoucherInput {
  return {
    voucherType: type,
    valueDate: date,
    description,
    reference,
    lines: lines.map(([branch, glCode, side, amount]) => ({ branch, glCode, side, amount })),
  };
}

const VOUCHERS: VoucherInput[] = [
  v('RECEIPT', '2026-06-01', 'Equity share capital infusion', [['HO', '1110', 'DR', '50000000.00'], ['HO', '3001', 'CR', '50000000.00']], 'BOARD/2026/07'),
  v('RECEIPT', '2026-06-02', 'Term loan drawdown - lender bank', [['HO', '1111', 'DR', '20000000.00'], ['HO', '2101', 'CR', '20000000.00']], 'TL-0042'),
  v('PAYMENT', '2026-06-05', 'Personal loan disbursements - Kochi', [['HO', '1201', 'DR', '7500000.00'], ['HO', '1110', 'CR', '7500000.00']]),
  v('PAYMENT', '2026-06-08', 'Personal loan disbursements - Mumbai (funded from HO)', [['MUM', '1201', 'DR', '4000000.00'], ['HO', '1110', 'CR', '4000000.00']]),
  v('RECEIPT', '2026-06-10', 'Processing fees with GST (intra-state, Kerala)', [['HO', '1110', 'DR', '118000.00'], ['HO', '4201', 'CR', '100000.00'], ['HO', '2201', 'CR', '9000.00'], ['HO', '2202', 'CR', '9000.00']]),
  v('RECEIPT', '2026-06-11', 'Processing fees with GST (intra-state, Maharashtra)', [['MUM', '1110', 'DR', '59000.00'], ['MUM', '4201', 'CR', '50000.00'], ['MUM', '2201', 'CR', '4500.00'], ['MUM', '2202', 'CR', '4500.00']]),
  v('JOURNAL', '2026-06-15', 'Interest accrual on personal loans - Kochi', [['HO', '1210', 'DR', '325000.00'], ['HO', '4101', 'CR', '325000.00']]),
  v('RECEIPT', '2026-06-16', 'EMI interest collections - Kochi', [['HO', '1110', 'DR', '280000.00'], ['HO', '1210', 'CR', '280000.00']]),
  v('JOURNAL', '2026-06-15', 'Interest accrual on personal loans - Mumbai', [['MUM', '1210', 'DR', '160000.00'], ['MUM', '4101', 'CR', '160000.00']]),
  v('JOURNAL', '2026-06-20', 'Interest expense on term loan', [['HO', '5101', 'DR', '150000.00'], ['HO', '2402', 'CR', '150000.00']]),
  v('PAYMENT', '2026-06-22', 'Branch rent June with TDS u/s 194-I', [['MUM', '5302', 'DR', '120000.00'], ['MUM', '2210', 'CR', '12000.00'], ['MUM', '1110', 'CR', '108000.00']]),
  v('PAYMENT', '2026-06-25', 'Salaries June', [['HO', '5301', 'DR', '450000.00'], ['HO', '1110', 'CR', '450000.00']]),
  v('JOURNAL', '2026-06-26', 'Provision on standard assets (0.25%)', [['HO', '5201', 'DR', '28750.00'], ['HO', '2301', 'CR', '28750.00']]),
  v('RECEIPT', '2026-06-26', 'Penal charges collected with GST', [['MUM', '1110', 'DR', '11800.00'], ['MUM', '4202', 'CR', '10000.00'], ['MUM', '2201', 'CR', '900.00'], ['MUM', '2202', 'CR', '900.00']]),
  v('CONTRA', '2026-06-27', 'Cash withdrawn for petty cash', [['HO', '1101', 'DR', '50000.00'], ['HO', '1110', 'CR', '50000.00']]),
  v('PAYMENT', '2026-06-29', 'Office furniture - Mumbai', [['MUM', '1330', 'DR', '250000.00'], ['MUM', '1110', 'CR', '250000.00']]),
];

interface SeedCustomer {
  first: string;
  last: string;
  dob: string;
  pan: string;
  mobile: string;
  branch: string;
  kyc: 'PENDING' | 'VERIFIED';
}

const CUSTOMERS: SeedCustomer[] = [
  { first: 'Anu', last: 'CLAUDE-TEST', dob: '1990-05-14', pan: 'AAAPZ1234C', mobile: '9000000001', branch: 'HO', kyc: 'VERIFIED' },
  { first: 'Biju', last: 'CLAUDE-TEST', dob: '1985-11-02', pan: 'BBBPZ2345D', mobile: '9000000002', branch: 'HO', kyc: 'VERIFIED' },
  { first: 'Chitra', last: 'CLAUDE-TEST', dob: '1992-02-29', pan: 'CCCPZ3456E', mobile: '9000000003', branch: 'HO', kyc: 'PENDING' },
  { first: 'Deepak', last: 'CLAUDE-TEST', dob: '1979-07-21', pan: 'DDDPZ4567F', mobile: '9000000004', branch: 'MUM', kyc: 'VERIFIED' },
  { first: 'Esha', last: 'CLAUDE-TEST', dob: '1998-09-09', pan: 'EEEPZ5678G', mobile: '9000000005', branch: 'MUM', kyc: 'VERIFIED' },
  { first: 'Farhan', last: 'CLAUDE-TEST', dob: '1988-12-31', pan: 'FFFPZ6789H', mobile: '9000000006', branch: 'MUM', kyc: 'PENDING' },
  { first: 'Gauri', last: 'CLAUDE-TEST', dob: '2001-03-15', pan: 'GGGPZ7890J', mobile: '9000000007', branch: 'HO', kyc: 'VERIFIED' },
  { first: 'Hari', last: 'CLAUDE-TEST', dob: '1970-01-01', pan: 'HHHPZ8901K', mobile: '9000000008', branch: 'HO', kyc: 'VERIFIED' },
];

const hoursAgo = (now: number, h: number) => new Date(now - h * 3600_000).toISOString();

function completedRun(id: number, businessDate: string, next: string, startIso: string, withExceptions: boolean): EodRun {
  let t = Date.parse(startIso);
  const steps: EodStep[] = EOD_STEPS.map((name, i) => {
    const started = new Date(t).toISOString();
    t += 20_000 + i * 7_000;
    const exc = withExceptions && name === 'NPA marking';
    return {
      stepNo: i + 1,
      name,
      status: exc ? 'COMPLETED_WITH_EXCEPTIONS' : 'COMPLETED',
      processed: name === 'Pre-checks' || name === 'Advance business date' ? 1 : 1240 + i * 3,
      failed: exc ? 1 : 0,
      startedAt: started,
      finishedAt: new Date(t).toISOString(),
    };
  });
  return {
    id,
    businessDate,
    status: withExceptions ? 'COMPLETED_WITH_EXCEPTIONS' : 'COMPLETED',
    startedAt: startIso,
    finishedAt: new Date(t).toISOString(),
    nextBusinessDate: next,
    steps,
    exceptions: withExceptions
      ? [{ step: 'NPA marking', accountNo: 'LN00000417', error: 'Repayment schedule missing for DPD computation', resolved: true }]
      : [],
  };
}

export function customerRecord(db: MockDb, input: StoredCustomer['input'], createdAt: string, kyc: StoredCustomer['kycStatus'] = 'PENDING'): StoredCustomer {
  db.customerSeq += 1;
  const c: StoredCustomer = {
    id: uuid(),
    customerNo: customerNumber(CUSTOMER_SERIES_PREFIX, db.customerSeq),
    input,
    kycStatus: kyc,
    status: 'ACTIVE',
    createdAt,
  };
  db.customers.push(c);
  return c;
}

export function createSeedDb(now: number = Date.now()): MockDb {
  const db: MockDb = {
    tenant: DEMO_TENANT,
    tenantName: DEMO_TENANT_NAME,
    businessDate: SEED_BUSINESS_DATE,
    dayStatus: 'OPEN',
    branches: BRANCHES.map((b) => ({ ...b })),
    holidays: HOLIDAYS.map((h) => ({ ...h })),
    taxRates: TAX_RATES.map((t) => ({ ...t })),
    glHeads: COA.map(([code, name, category, parentCode, posting]) => ({ code, name, category, parentCode, posting, status: 'ACTIVE' as const })),
    customers: [],
    customerSeq: 0,
    vouchers: [],
    voucherSeq: 0,
    entries: [],
    approvals: [],
    eodRuns: [],
    eodRunSeq: 103,
    eodSchedule: { mode: 'MANUAL', cron: null, alertEmails: ['eod-alerts@demo-nbfc.example'] },
    audit: [],
    loanProducts: [],
    loans: [],
    loanSeq: 0,
    staff: [],
    branchSets: [],
    systemProperties: [],
    enumerations: {},
    states: [],
    pincodes: [],
    amountLimits: [],
    relationships: [],
    consents: [],
    kycDocuments: [],
    reportRuns: [],
    idempotency: new Map(),
    eodFailAtStep: null,
  };

  const seedAt = hoursAgo(now, 24 * 30);
  appendAudit(db, seedAt, 'system', 'TENANT_PROVISIONED', 'TENANT', db.tenant, { starterKit: 'NBFC' });

  CUSTOMERS.forEach((c, i) => {
    const rec = customerRecord(
      db,
      { customerType: 'INDIVIDUAL', firstName: c.first, lastName: c.last, dateOfBirth: c.dob, pan: c.pan, mobile: c.mobile, homeBranch: c.branch, gender: i % 2 ? 'MALE' : 'FEMALE' },
      hoursAgo(now, 24 * (20 - i)),
      c.kyc,
    );
    appendAudit(db, rec.createdAt, 'checker', 'CUSTOMER_CREATED', 'CUSTOMER', rec.id, { customerNo: rec.customerNo });
  });

  for (const input of VOUCHERS) {
    const vch = postVoucher(db, input, input.valueDate);
    appendAudit(db, `${input.valueDate}T12:00:00.000Z`, 'checker', 'VOUCHER_POSTED', 'VOUCHER', vch.id, { voucherNo: vch.voucherNo, amount: vch.amount });
  }

  seedPlatform(db, seedAt);
  seedLending(db);
  seedCustomerExtras(db, seedAt);

  db.eodRuns.push(
    completedRun(101, '2026-06-26', '2026-06-27', '2026-06-26T18:00:00.000Z', false),
    completedRun(102, '2026-06-27', '2026-06-29', '2026-06-27T18:00:00.000Z', true),
    completedRun(103, '2026-06-29', '2026-06-30', '2026-06-29T18:00:00.000Z', false),
  );
  for (const r of db.eodRuns) appendAudit(db, r.finishedAt ?? r.startedAt ?? seedAt, 'ops', 'EOD_COMPLETED', 'EOD_RUN', String(r.id), { businessDate: r.businessDate, status: r.status });

  // Pending requests from the maker, with a spread of ages to show SLA colouring.
  const pending: Array<{ hours: number; entityType: string; action: string; entityId?: string | null; amount?: string | null; current?: Record<string, unknown> | null; proposed: Record<string, unknown>; payload: ApprovalPayload }> = [
    {
      hours: 52,
      entityType: 'BRANCH',
      action: 'CREATE',
      proposed: { code: 'TVM', name: 'Thiruvananthapuram', ifsc: null, stateCode: '32', parentCode: 'HO', headOffice: false, status: 'ACTIVE' },
      payload: { kind: 'BRANCH', branch: { code: 'TVM', name: 'Thiruvananthapuram', ifsc: null, stateCode: '32', parentCode: 'HO', headOffice: false, status: 'ACTIVE' } },
    },
    {
      hours: 30,
      entityType: 'TAX_RATE',
      action: 'CREATE',
      proposed: { code: 'TDS194J', taxType: 'TDS', ratePercent: '10.00', effectiveFrom: '2026-07-01', effectiveTo: null },
      payload: { kind: 'TAX_RATE', rate: { code: 'TDS194J', taxType: 'TDS', ratePercent: '10.00', effectiveFrom: '2026-07-01', effectiveTo: null } },
    },
    {
      hours: 5,
      entityType: 'CUSTOMER',
      action: 'CREATE',
      proposed: { customerType: 'INDIVIDUAL', firstName: 'Kiran', lastName: 'CLAUDE-TEST', dateOfBirth: '1994-08-19', homeBranch: 'MUM', panMasked: maskPan('KKKPZ1111L'), mobileMasked: maskMobile('9000000011') },
      payload: { kind: 'CUSTOMER', input: { customerType: 'INDIVIDUAL', firstName: 'Kiran', lastName: 'CLAUDE-TEST', dateOfBirth: '1994-08-19', homeBranch: 'MUM', pan: 'KKKPZ1111L', mobile: '9000000011' } },
    },
    {
      hours: 2,
      entityType: 'VOUCHER',
      action: 'CREATE',
      amount: '25000.00',
      proposed: {
        voucherType: 'JOURNAL', valueDate: SEED_BUSINESS_DATE, description: 'Interest accrual true-up - Mumbai',
        lines: [{ branch: 'MUM', glCode: '1210', side: 'DR', amount: '25000.00' }, { branch: 'MUM', glCode: '4101', side: 'CR', amount: '25000.00' }],
      },
      payload: {
        kind: 'VOUCHER',
        input: v('JOURNAL', SEED_BUSINESS_DATE, 'Interest accrual true-up - Mumbai', [['MUM', '1210', 'DR', '25000.00'], ['MUM', '4101', 'CR', '25000.00']]),
      },
    },
  ];
  for (const p of pending) {
    const approval: Approval = {
      id: uuid(),
      entityType: p.entityType,
      entityId: p.entityId ?? null,
      action: p.action,
      status: 'PENDING',
      maker: 'maker',
      madeAt: hoursAgo(now, p.hours),
      checker: null,
      checkedAt: null,
      note: null,
      amount: p.amount ?? null,
      current: p.current ?? null,
      proposed: p.proposed,
    };
    db.approvals.push({ approval, payload: p.payload });
    appendAudit(db, approval.madeAt, 'maker', 'APPROVAL_REQUESTED', p.entityType, approval.id, { action: p.action });
  }

  // Some decided history.
  const rejected: Approval = {
    id: uuid(), entityType: 'BRANCH', entityId: 'MUM', action: 'UPDATE', status: 'REJECTED', maker: 'maker',
    madeAt: hoursAgo(now, 100), checker: 'checker', checkedAt: hoursAgo(now, 96), note: 'State code must stay 27 for Mumbai',
    amount: null, current: { ...BRANCHES[1] }, proposed: { ...BRANCHES[1], stateCode: '24' },
  };
  db.approvals.push({ approval: rejected, payload: { kind: 'BRANCH', branch: { ...BRANCHES[1], stateCode: '24' } } });
  const approved: Approval = {
    id: uuid(), entityType: 'EOD_SCHEDULE', entityId: null, action: 'UPDATE', status: 'APPROVED', maker: 'maker',
    madeAt: hoursAgo(now, 200), checker: 'checker', checkedAt: hoursAgo(now, 190), note: 'OK',
    amount: null, current: { mode: 'MANUAL', cron: null, alertEmails: [] }, proposed: { ...db.eodSchedule },
  };
  db.approvals.push({ approval: approved, payload: { kind: 'EOD_SCHEDULE', schedule: { ...db.eodSchedule } } });
  appendAudit(db, approved.checkedAt!, 'checker', 'APPROVAL_APPROVED', 'EOD_SCHEDULE', approved.id, null);
  appendAudit(db, rejected.checkedAt!, 'checker', 'APPROVAL_REJECTED', 'BRANCH', rejected.id, { note: rejected.note });

  // Keep audit chronological (ids are assigned in append order; re-sort not needed for chain integrity).
  return db;
}
