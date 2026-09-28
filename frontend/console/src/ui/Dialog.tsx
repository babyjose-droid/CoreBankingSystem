import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { cx } from '../lib/cx';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export interface DialogProps {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  variant?: 'modal' | 'drawer';
  wide?: boolean;
  /** Element to focus first; defaults to the first focusable element in the body. */
  initialFocusRef?: React.RefObject<HTMLElement | null>;
}

/** Accessible modal dialog / side drawer: focus trap, Escape to close, focus restored on close. */
export function Dialog({ open, onClose, title, children, footer, variant = 'modal', wide, initialFocusRef }: DialogProps) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const previously = document.activeElement as HTMLElement | null;
    const node = ref.current;
    const first =
      initialFocusRef?.current ??
      node?.querySelector('.dialog__body')?.querySelector<HTMLElement>(FOCUSABLE) ??
      node?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? node)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onCloseRef.current();
      } else if (e.key === 'Tab' && node) {
        const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => !el.hasAttribute('hidden'));
        if (items.length === 0) return;
        const firstEl = items[0];
        const lastEl = items[items.length - 1];
        if (e.shiftKey && document.activeElement === firstEl) {
          e.preventDefault();
          lastEl.focus();
        } else if (!e.shiftKey && document.activeElement === lastEl) {
          e.preventDefault();
          firstEl.focus();
        }
      }
    };
    document.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
      previously?.focus?.();
    };
  }, [open, initialFocusRef]);

  if (!open) return null;
  return createPortal(
    <div
      className={cx('overlay', variant === 'drawer' && 'overlay--drawer')}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={cx('dialog', wide && 'dialog--wide', variant === 'drawer' && 'dialog--drawer')}
      >
        <div className="dialog__head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="btn btn--ghost btn--sm" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="dialog__body">{children}</div>
        {footer && <div className="dialog__foot">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}
