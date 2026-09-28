import type { ReactNode } from 'react';
import { cx } from '../lib/cx';

export function Card({ title, actions, children, flush, className, headingLevel = 2 }: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  flush?: boolean;
  className?: string;
  headingLevel?: 2 | 3;
}) {
  const H = headingLevel === 2 ? 'h2' : 'h3';
  return (
    <section className={cx('card', className)}>
      {(title || actions) && (
        <div className="card__head">
          {title ? <H>{title}</H> : <span />}
          {actions && <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>{actions}</div>}
        </div>
      )}
      <div className={cx('card__body', flush && 'card__body--flush')}>{children}</div>
    </section>
  );
}
