import type { ReactNode } from 'react';
import type { AssetClass, FeeRule, LoanProduct } from '../../api/types';
import { formatINR, isMoney } from '../../lib/money';

const ASSET_LABEL: Record<AssetClass, string> = {
  STANDARD: 'Standard',
  SMA0: 'SMA-0',
  SMA1: 'SMA-1',
  SMA2: 'SMA-2',
  SUBSTANDARD: 'Sub-standard',
  DOUBTFUL1: 'Doubtful-1',
  DOUBTFUL2: 'Doubtful-2',
  DOUBTFUL3: 'Doubtful-3',
  LOSS: 'Loss',
};

export const NPA_CLASSES: AssetClass[] = ['SUBSTANDARD', 'DOUBTFUL1', 'DOUBTFUL2', 'DOUBTFUL3', 'LOSS'];

export function assetClassLabel(c: AssetClass | undefined | null): string {
  return c ? ASSET_LABEL[c] ?? c : '—';
}

/** Asset classification badge; the title spells out SMA vs NPA for colour-blind users and tooltips. */
export function AssetClassBadge({ value }: { value: AssetClass | undefined | null }) {
  if (!value) return <span className="muted">—</span>;
  const npa = NPA_CLASSES.includes(value);
  const title = npa ? 'Non-performing asset' : value === 'STANDARD' ? 'Standard asset' : 'Special mention account (overdue, not yet NPA)';
  return (
    <span className={`badge badge--asset-${value.toLowerCase()}`} title={title} data-asset-class={value}>
      {ASSET_LABEL[value] ?? value}
      {npa && <span className="sr-only"> (NPA)</span>}
    </span>
  );
}

export const REPAYMENT_METHOD_LABEL: Record<LoanProduct['repaymentMethod'], string> = {
  EQUATED: 'EMI (equated instalments)',
  FIXED_PRINCIPAL: 'Fixed principal',
  BULLET_TOTAL_INTEREST: 'Bullet (principal + interest at maturity)',
  BULLET_PERIODIC_INTEREST: 'Bullet principal, periodic interest',
  STEP_EQUATED: 'Step-up / step-down EMI',
  STRUCTURED: 'Structured (assigned principal per instalment)',
};

const n = (v: unknown) => (v === null || v === undefined || v === '' ? null : String(v));
const inr = (v: unknown) => {
  const s = n(v);
  return s && isMoney(s) ? formatINR(s) : s ?? '';
};

/** "0.75% (min ₹500.00, max ₹10,000.00) + 18% GST, deducted from disbursal" */
export function describeFee(f: FeeRule): string {
  let base: string;
  if (f.calcType === 'FIXED') base = inr(f.amount);
  else if (f.calcType === 'PERCENT') base = `${n(f.percent)}%`;
  else base = (f.slabs ?? []).map((s) => `${inr(s.from)}–${inr(s.to)}: ${inr(s.fee)}`).join('; ');
  const limits = [n(f.minAmount) && `min ${inr(f.minAmount)}`, n(f.maxAmount) && `max ${inr(f.maxAmount)}`].filter(Boolean).join(', ');
  const gst = `${n(f.gstRate) ?? '18'}% GST ${f.taxTreatment === 'INCLUSIVE' ? 'included' : 'extra'}`;
  return `${base}${limits ? ` (${limits})` : ''} · ${gst}${f.deductFromDisbursal ? ' · deducted from disbursal' : ''}`;
}

export function pct(v: unknown): string {
  const s = n(v);
  return s === null ? '—' : `${Number(s).toFixed(2)}%`;
}

export function Stat({ label, children, testId }: { label: string; children: ReactNode; testId?: string }) {
  return (
    <div className="stat" data-testid={testId}>
      <div className="stat__label">{label}</div>
      <div className="stat__value">{children}</div>
    </div>
  );
}

/** Money input value -> API Money string ("1,00,000" / "100000" -> "100000"), or null when invalid. */
export function moneyInput(v: string): string | null {
  const s = v.replace(/[,\s₹]/g, '');
  return s && isMoney(s) ? s : null;
}
