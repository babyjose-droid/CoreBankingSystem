import type { ReactNode } from 'react';
import { cx } from '../lib/cx';

export type Tone = 'neutral' | 'accent' | 'ok' | 'warn' | 'danger' | 'info';

export function Badge({ tone = 'neutral', children, title }: { tone?: Tone; children: ReactNode; title?: string }) {
  return (
    <span className={cx('badge', tone !== 'neutral' && `badge--${tone}`)} title={title}>
      {children}
    </span>
  );
}

const STATUS_TONE: Record<string, Tone> = {
  PENDING: 'warn',
  APPROVED: 'ok',
  REJECTED: 'danger',
  WITHDRAWN: 'neutral',
  POSTED: 'ok',
  REVERSED: 'neutral',
  RUNNING: 'info',
  COMPLETED: 'ok',
  COMPLETED_WITH_EXCEPTIONS: 'warn',
  FAILED: 'danger',
  SKIPPED: 'neutral',
  ACTIVE: 'ok',
  CLOSED: 'neutral',
  FROZEN: 'warn',
  OPEN: 'ok',
  EOD_RUNNING: 'info',
  EOD_FAILED: 'danger',
  VERIFIED: 'ok',
  EXACT: 'danger',
  STRONG: 'warn',
  POSSIBLE: 'info',
  SANCTIONED: 'info',
  CANCELLED: 'neutral',
  WRITTEN_OFF: 'danger',
  DRAFT: 'neutral',
  SUSPENDED: 'warn',
  EXITED: 'neutral',
};

export function humanize(code: string): string {
  const s = code.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function StatusBadge({ status }: { status: string | undefined | null }) {
  if (!status) return null;
  return <Badge tone={STATUS_TONE[status] ?? 'neutral'}>{humanize(status)}</Badge>;
}
