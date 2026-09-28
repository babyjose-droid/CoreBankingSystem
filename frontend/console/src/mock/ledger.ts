import type { GlHead, StatementRow, TrialBalanceRow, Voucher, VoucherInput, VoucherLine } from '../api/types';
import { fromUnits, toUnits } from '../lib/money';
import { uuid, type MockDb, type StoredEntry } from './db';

export const INTER_BRANCH_GL = '1320';

export function headByCode(db: MockDb, code: string): GlHead | undefined {
  return db.glHeads.find((h) => h.code === code);
}

export function voucherTotals(lines: VoucherLine[]): { dr: bigint; cr: bigint } {
  let dr = 0n;
  let cr = 0n;
  for (const l of lines) {
    const u = toUnits(l.amount);
    if (l.side === 'DR') dr += u;
    else cr += u;
  }
  return { dr, cr };
}

/**
 * Lines spanning several branches get automatic inter-branch legs so that every branch balances on its own
 * (the backend's ledger-core does the same).
 */
export function withInterBranchLegs(lines: VoucherLine[]): VoucherLine[] {
  const net = new Map<string, bigint>();
  for (const l of lines) {
    const u = toUnits(l.amount) * (l.side === 'DR' ? 1n : -1n);
    net.set(l.branch, (net.get(l.branch) ?? 0n) + u);
  }
  const legs: VoucherLine[] = [];
  for (const [branch, n] of net) {
    if (n === 0n) continue;
    legs.push({
      branch,
      glCode: INTER_BRANCH_GL,
      side: n > 0n ? 'CR' : 'DR',
      amount: fromUnits(n > 0n ? n : -n),
      narration: 'Inter-branch leg (auto)',
    });
  }
  return [...lines, ...legs];
}

const PREFIX: Record<VoucherInput['voucherType'], string> = { CONTRA: 'CV', RECEIPT: 'RV', PAYMENT: 'PV', JOURNAL: 'JV' };

export function financialYearLabel(iso: string): string {
  const y = Number(iso.slice(0, 4));
  const m = Number(iso.slice(5, 7));
  const start = m >= 4 ? y : y - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

export function postVoucher(db: MockDb, input: VoucherInput, businessDate: string, lotType = 'VOUCHER'): Voucher {
  db.voucherSeq += 1;
  const id = uuid();
  const lotId = uuid();
  const lines = withInterBranchLegs(input.lines);
  const { dr } = voucherTotals(input.lines);
  const voucher: Voucher = {
    ...input,
    lines: input.lines.map((l) => ({ ...l, account: l.account ?? l.glCode })),
    id,
    voucherNo: `${PREFIX[input.voucherType]}/${financialYearLabel(businessDate)}/${String(db.voucherSeq).padStart(6, '0')}`,
    status: 'POSTED',
    amount: fromUnits(dr),
    lotId,
    reversalLotId: null,
    businessDate,
  };
  db.vouchers.push(voucher);
  for (const l of lines) {
    db.entries.push({
      lotId,
      businessDate,
      branch: l.branch,
      glCode: l.glCode,
      account: l.account ?? l.glCode,
      side: l.side,
      amount: fromUnits(toUnits(l.amount)),
      narration: l.narration ?? input.description,
      lotType,
      voucherId: id,
    });
  }
  return voucher;
}

export function reverseVoucher(db: MockDb, voucher: Voucher, businessDate: string, reason: string): void {
  const lotId = uuid();
  const original = db.entries.filter((e) => e.lotId === voucher.lotId);
  for (const e of original) {
    const mirror: StoredEntry = {
      ...e,
      lotId,
      businessDate,
      side: e.side === 'DR' ? 'CR' : 'DR',
      narration: `Reversal of ${voucher.voucherNo}: ${reason}`,
      lotType: 'REVERSAL',
    };
    db.entries.push(mirror);
  }
  voucher.status = 'REVERSED';
  voucher.reversalLotId = lotId;
}

function sums(entries: StoredEntry[]): Map<string, { dr: bigint; cr: bigint }> {
  const m = new Map<string, { dr: bigint; cr: bigint }>();
  for (const e of entries) {
    const s = m.get(e.glCode) ?? { dr: 0n, cr: 0n };
    if (e.side === 'DR') s.dr += toUnits(e.amount);
    else s.cr += toUnits(e.amount);
    m.set(e.glCode, s);
  }
  return m;
}

export function trialBalance(db: MockDb, asOf: string, branch?: string): TrialBalanceRow[] {
  const entries = db.entries.filter((e) => e.businessDate <= asOf && (!branch || e.branch === branch));
  const m = sums(entries);
  return [...m.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([code, s]) => {
      const head = headByCode(db, code);
      return {
        glCode: code,
        glName: head?.name ?? code,
        category: head?.category ?? 'ASSET',
        debit: fromUnits(s.dr),
        credit: fromUnits(s.cr),
        net: fromUnits(s.dr - s.cr),
      };
    });
}

function statementRows(db: MockDb, entries: StoredEntry[], categories: GlHead['category'][]): StatementRow[] {
  const m = sums(entries);
  const rows: StatementRow[] = [];
  for (const cat of categories) {
    const debitNature = cat === 'ASSET' || cat === 'EXPENSE';
    for (const [code, s] of [...m.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      const head = headByCode(db, code);
      if (!head || head.category !== cat) continue;
      const amount = debitNature ? s.dr - s.cr : s.cr - s.dr;
      if (amount === 0n) continue;
      rows.push({ section: cat, glCode: code, glName: head.name, amount: fromUnits(amount) });
    }
  }
  return rows;
}

function netProfit(db: MockDb, entries: StoredEntry[]): bigint {
  let income = 0n;
  let expense = 0n;
  for (const e of entries) {
    const cat = headByCode(db, e.glCode)?.category;
    const signed = toUnits(e.amount) * (e.side === 'CR' ? 1n : -1n);
    if (cat === 'INCOME') income += signed;
    if (cat === 'EXPENSE') expense -= signed;
  }
  return income - expense;
}

export function profitAndLoss(db: MockDb, from: string, to: string): StatementRow[] {
  const entries = db.entries.filter((e) => e.businessDate >= from && e.businessDate <= to);
  const rows = statementRows(db, entries, ['INCOME', 'EXPENSE']);
  rows.push({ section: 'NET_PROFIT', glCode: '', glName: 'Net profit for the period', amount: fromUnits(netProfit(db, entries)) });
  return rows;
}

export function balanceSheet(db: MockDb, asOf: string): StatementRow[] {
  const entries = db.entries.filter((e) => e.businessDate <= asOf);
  const rows = statementRows(db, entries, ['ASSET', 'LIABILITY', 'EQUITY']);
  rows.push({ section: 'NET_PROFIT', glCode: '', glName: 'Profit and loss account (current period)', amount: fromUnits(netProfit(db, entries)) });
  return rows;
}
