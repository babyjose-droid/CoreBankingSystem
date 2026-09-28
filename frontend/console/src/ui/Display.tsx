import type { ReactNode } from 'react';
import { formatDate, formatDateTime } from '../lib/dates';
import { formatINR, isMoney, toUnits } from '../lib/money';
import { cx } from '../lib/cx';

/** Right-aligned, tabular, en-IN formatted money. Accepts the API's decimal strings. */
export function MoneyText({ value, className, signed }: { value: string | null | undefined; className?: string; signed?: boolean }) {
  if (value === null || value === undefined || value === '' || !isMoney(value)) return <span className={cx('num', className)}>—</span>;
  const negative = toUnits(value) < 0n;
  const text = formatINR(value);
  return (
    <span className={cx('num', className)} style={negative && signed ? { color: 'var(--danger)' } : undefined} data-money={value}>
      {text}
    </span>
  );
}

export function DateText({ value, weekday }: { value: string | null | undefined; weekday?: boolean }) {
  if (!value) return <span>—</span>;
  return <time dateTime={value}>{formatDate(value, weekday)}</time>;
}

export function DateTimeText({ value }: { value: string | null | undefined }) {
  if (!value) return <span>—</span>;
  return <time dateTime={value}>{formatDateTime(value)}</time>;
}

/** Shows an already-masked identifier; screen readers get a descriptive label instead of "X X X X". */
export function Masked({ value, kind }: { value: string | null | undefined; kind: 'PAN' | 'mobile' | 'account' }) {
  if (!value) return <span className="muted">—</span>;
  const visible = value.replace(/[X•]/g, '');
  return (
    <span className="masked" aria-label={`${kind} ending ${visible || 'hidden'} (masked)`} title="Masked for privacy">
      {value}
    </span>
  );
}

export function EmptyState({ title, children, action }: { title: ReactNode; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty__title">{title}</div>
      {children && <div>{children}</div>}
      {action && <div style={{ marginTop: 12 }}>{action}</div>}
    </div>
  );
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <span role="status" style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }} className="muted">
      <span className="spinner" aria-hidden="true" />
      {label}…
    </span>
  );
}

export function Banner({ tone, children }: { tone: 'ok' | 'danger' | 'warn' | 'info'; children: ReactNode }) {
  return (
    <div className={cx('banner', `banner--${tone}`)} role={tone === 'danger' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}
