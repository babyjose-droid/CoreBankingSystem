/**
 * Role amount limits (US-021) in the mock API. The most permissive of a user's roles applies; a role without a limit
 * for a transaction type is not limited (unless the property `limits.default-deny` is `true`).
 */
import type { AmountLimit, LimitTxnType } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { formatINR, fromUnits, toUnits } from '../lib/money';
import type { MockDb } from './db';
import { HttpProblem, PROBLEM_BASE } from './problems';

const usage = new WeakMap<MockDb, Map<string, bigint>>();

export function limitInForce(l: AmountLimit, businessDate: string): boolean {
  return l.effectiveFrom <= businessDate && (!l.effectiveTo || l.effectiveTo >= businessDate);
}

/** null = not limited. */
function effectiveLimit(db: MockDb, user: DemoUser, txnType: LimitTxnType): { perTxn: bigint; perDay: bigint | null; role: string } | null {
  const defaultDeny = db.systemProperties.find((p) => p.key === 'limits.default-deny')?.value === 'true';
  let best: { perTxn: bigint; perDay: bigint | null; role: string } | null = null;
  for (const role of user.roles) {
    const l = db.amountLimits.find((x) => x.roleName === role && x.txnType === txnType && limitInForce(x, db.businessDate));
    if (!l) {
      if (defaultDeny) continue;
      return null;
    }
    const cand = { perTxn: toUnits(l.perTransactionMax), perDay: l.perDayMax ? toUnits(l.perDayMax) : null, role };
    if (!best || cand.perTxn > best.perTxn) best = cand;
  }
  return best ?? (defaultDeny ? { perTxn: 0n, perDay: null, role: user.roles[0] ?? '—' } : null);
}

const LABEL: Record<LimitTxnType, string> = {
  LOAN_DISBURSEMENT: 'loan disbursement',
  LOAN_REPAYMENT: 'loan repayment',
  LOAN_WAIVER: 'loan waiver',
  VOUCHER: 'voucher',
  LOAN_PRECLOSURE: 'loan pre-closure',
  FEE_WAIVER: 'fee waiver',
};

/** 403 when `amount` is above the user's per-transaction or per-day limit. `record` adds it to the day's usage. */
export function assertWithinLimit(db: MockDb, user: DemoUser, txnType: LimitTxnType, amount: string, opts: { approving?: boolean; record?: boolean } = {}) {
  const lim = effectiveLimit(db, user, txnType);
  if (!lim) return;
  const units = toUnits(amount);
  const who = opts.approving ? 'approval' : 'transaction';
  const problem = (detail: string) => new HttpProblem(403, 'Above your amount limit', detail, { txnType, role: lim.role }, PROBLEM_BASE + 'amount-limit');
  if (units > lim.perTxn) {
    throw problem(
      `${formatINR(amount)} is above the ${LABEL[txnType]} limit of ${formatINR(fromUnits(lim.perTxn))} per ${who} for role ${lim.role}.${opts.approving ? ' A checker with a higher limit must approve.' : ''}`,
    );
  }
  const key = `${user.username}|${txnType}|${db.businessDate}`;
  const map = usage.get(db) ?? new Map<string, bigint>();
  usage.set(db, map);
  const used = map.get(key) ?? 0n;
  if (lim.perDay !== null && used + units > lim.perDay) {
    throw problem(`${formatINR(amount)} would take today's ${LABEL[txnType]} total to ${formatINR(fromUnits(used + units))}, above the daily limit of ${formatINR(fromUnits(lim.perDay))} for role ${lim.role}.`);
  }
  if (opts.record !== false) map.set(key, used + units);
}
