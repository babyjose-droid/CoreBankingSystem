import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { cx } from '../lib/cx';

export interface ToastInput {
  message: ReactNode;
  tone?: 'info' | 'success' | 'error';
  link?: { to: string; label: string };
  timeoutMs?: number;
}
interface ToastItem extends ToastInput {
  id: number;
}

const ToastContext = createContext<((t: ToastInput) => void) | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const seq = useRef(0);
  const dismiss = useCallback((id: number) => setItems((xs) => xs.filter((x) => x.id !== id)), []);
  const push = useCallback(
    (t: ToastInput) => {
      seq.current += 1;
      const id = seq.current;
      setItems((xs) => [...xs.slice(-3), { ...t, id }]);
      const ms = t.timeoutMs ?? (t.tone === 'error' ? 10000 : 6000);
      if (ms > 0) setTimeout(() => dismiss(id), ms);
    },
    [dismiss],
  );
  const value = useMemo(() => push, [push]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      {/* Separate live regions so errors are announced assertively. */}
      <div className="toasts">
        <div aria-live="polite" role="status" style={{ display: 'contents' }}>
          {items.filter((t) => t.tone !== 'error').map((t) => (
            <ToastView key={t.id} t={t} onDismiss={() => dismiss(t.id)} />
          ))}
        </div>
        <div aria-live="assertive" role="alert" style={{ display: 'contents' }}>
          {items.filter((t) => t.tone === 'error').map((t) => (
            <ToastView key={t.id} t={t} onDismiss={() => dismiss(t.id)} />
          ))}
        </div>
      </div>
    </ToastContext.Provider>
  );
}

function ToastView({ t, onDismiss }: { t: ToastItem; onDismiss: () => void }) {
  return (
    <div className={cx('toast', t.tone && `toast--${t.tone}`)}>
      <div className="toast__msg">
        {t.message}
        {t.link && (
          <>
            {' '}
            <Link to={t.link.to} onClick={onDismiss}>
              {t.link.label}
            </Link>
          </>
        )}
      </div>
      <button type="button" className="btn btn--ghost btn--sm" onClick={onDismiss} aria-label="Dismiss notification">
        ✕
      </button>
    </div>
  );
}

export function useToast(): (t: ToastInput) => void {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside <ToastProvider>');
  return ctx;
}
