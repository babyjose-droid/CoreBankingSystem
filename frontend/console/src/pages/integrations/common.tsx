import { useState, type ReactNode } from 'react';
import { useLoans } from '../../api/lendingHooks';
import type { StatusEvent } from '../../api/integrationTypes';
import { Banner, Button, Checkbox, DateTimeText, Dialog, Select, humanize } from '../../ui';

/** Loans the user may see, for screens whose API is per loan (payment links, mandates, beneficiaries). */
export function LoanPicker({ value, onChange, label = 'Loan', required, error }: { value: string; onChange: (id: string) => void; label?: string; required?: boolean; error?: string | null }) {
  const loans = useLoans({ q: '', page: 0, size: 100 });
  return (
    <Select
      label={label}
      required={required}
      value={value}
      placeholder={loans.isLoading ? 'Loading…' : 'Select a loan…'}
      onChange={(e) => onChange(e.target.value)}
      options={(loans.data ?? []).map((l) => ({ value: l.id ?? '', label: `${l.loanNo} · ${l.customerName} · ${humanize(l.status ?? '')}` }))}
      error={error}
    />
  );
}

export function StatusTimeline({ events }: { events: StatusEvent[] | undefined }) {
  if (!events?.length) return <p className="muted">No status history.</p>;
  return (
    <ol className="status-history" aria-label="Status history">
      {events.map((e, i) => (
        <li key={i}>
          <strong>{e.from && e.from !== e.to ? `${humanize(e.from)} → ${humanize(e.to ?? '')}` : humanize(e.to ?? '')}</strong>
          <span className="muted">
            {' '}
            · <DateTimeText value={e.at} /> · {humanize(e.source ?? '')}
            {e.actor ? ` · ${e.actor}` : ''}
          </span>
        </li>
      ))}
    </ol>
  );
}

export interface SecretRow {
  label: string;
  value: string;
  /** Shown in a copyable field. */
  copy?: boolean;
}

/**
 * Shows a secret exactly once. The value is passed in from a mutation result and is never written to state, storage,
 * the URL or a log; closing the dialog (after confirming it was stored) is the caller's cue to drop the result.
 */
export function OneTimeSecretDialog({ title, rows, warning, onClose }: { title: string; rows: SecretRow[]; warning: ReactNode; onClose: () => void }) {
  const [stored, setStored] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  const [copyFailed, setCopyFailed] = useState(false);
  const copy = async (row: SecretRow) => {
    try {
      await navigator.clipboard.writeText(row.value);
      setCopied(row.label);
      setCopyFailed(false);
    } catch {
      setCopyFailed(true);
    }
  };
  return (
    <Dialog
      open
      // Escape and the backdrop must not lose a secret that cannot be shown again.
      onClose={() => stored && onClose()}
      title={title}
      footer={
        <Button variant="primary" disabled={!stored} onClick={onClose}>
          Done
        </Button>
      }
    >
      <div className="stack">
        <Banner tone="warn">{warning}</Banner>
        {rows.map((r) => (
          <div key={r.label} className="secret-row">
            <label className="field__label" htmlFor={`secret-${r.label.replace(/\s+/g, "-")}`}>
              {r.label}
            </label>
            <div className="row" style={{ gap: 8 }}>
              <input id={`secret-${r.label.replace(/\s+/g, "-")}`} className="input mono" readOnly value={r.value} onFocus={(e) => e.target.select()} autoComplete="off" spellCheck={false} style={{ flex: 1 }} />
              {r.copy && (
                <Button size="sm" aria-label={`Copy ${r.label.toLowerCase()}`} onClick={() => void copy(r)}>
                  {copied === r.label ? 'Copied' : 'Copy'}
                </Button>
              )}
            </div>
          </div>
        ))}
        <span role="status" className="muted" style={{ fontSize: 12 }}>
          {copyFailed ? 'Could not copy: select the value and copy it by hand.' : copied ? `${copied} copied to the clipboard.` : ''}
        </span>
        <Checkbox label="I have stored it in a safe place. I understand it cannot be shown again." checked={stored} onChange={(e) => setStored(e.target.checked)} />
      </div>
    </Dialog>
  );
}

export const TEST_ONLY = 'Test only';
