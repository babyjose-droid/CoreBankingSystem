import { useState } from 'react';
import { Link } from 'react-router';
import { loanDocument, useFileDownload, useLoanParties, type FileRequest } from '../../api/extraHooks';
import { useLoanSchedule } from '../../api/lendingHooks';
import type { Loan } from '../../api/types';
import { Badge, Button, Card, DateText, EmptyState, ErrorBanner, Input, MoneyText, Spinner, StatusBadge, Table, humanize, useToast } from '../../ui';

/**
 * Loan documents as PDF. Each file is fetched with the bearer token in a header and saved from a blob: a PDF is
 * never opened by URL, so no token or personal data ends up in the browser history.
 */
export function LoanDocumentsTab({ loan, businessDate }: { loan: Loan; businessDate: string }) {
  const id = loan.id!;
  const download = useFileDownload();
  const schedule = useLoanSchedule(id);
  const toast = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  const [from, setFrom] = useState(loan.disbursedOn ?? '');
  const [to, setTo] = useState(businessDate);
  const get = (key: string, req: FileRequest) => {
    setBusy(key);
    download.mutate(req, { onSuccess: (name) => toast({ tone: 'success', message: `Saved ${name}.` }), onSettled: () => setBusy(null) });
  };
  const btn = (key: string, label: string, req: () => FileRequest, disabled = false) => (
    <Button aria-label={label} loading={busy === key} disabled={disabled || (busy !== null && busy !== key)} onClick={() => get(key, req())}>
      Download PDF
    </Button>
  );
  const range = from && to && from > to ? 'From must be on or before To' : to > businessDate ? 'Not after the business date' : null;
  const fees = (schedule.data?.charges ?? []).filter((c) => c.kind === 'FEE');
  return (
    <div className="stack">
      <ErrorBanner error={download.error} />
      <ul className="doc-list" aria-label="Loan documents">
        <li>
          <div>
            <strong>Key Facts Statement</strong>
            <div className="muted">The KFS stored at booking, in the RBI layout, with the APR illustration and schedule.</div>
          </div>
          {btn('kfs', 'Download Key Facts Statement', () => loanDocument.kfs(id))}
        </li>
        <li>
          <div>
            <strong>Statement of account</strong>
            <div className="muted">Every transaction with its split and the running outstanding.</div>
            <div className="row" style={{ marginTop: 8 }}>
              <Input label="Statement from" type="date" max={businessDate} value={from} onChange={(e) => setFrom(e.target.value)} error={range && range.startsWith('From') ? range : null} />
              <Input label="Statement to" type="date" max={businessDate} value={to} onChange={(e) => setTo(e.target.value)} error={range && !range.startsWith('From') ? range : null} />
            </div>
          </div>
          {btn('statement', 'Download statement of account', () => loanDocument.statement(id, from, to), !!range)}
        </li>
        <li>
          <div>
            <strong>Repayment schedule</strong>
            <div className="muted">Instalments fallen due with what was paid, and the instalments to come.</div>
          </div>
          {btn('schedule', 'Download repayment schedule', () => loanDocument.schedule(id))}
        </li>
        <li>
          <div>
            <strong>No-objection certificate</strong> {loan.status !== 'CLOSED' && <Badge tone="warn">Only for closed loans</Badge>}
            <div className="muted">Closure letter confirming that all dues were received.</div>
          </div>
          {btn('noc', 'Download no-objection certificate', () => loanDocument.noc(id))}
        </li>
      </ul>
      <Card title="GST invoices" flush headingLevel={3}>
        {schedule.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="GST invoices by fee"
            captionHidden
            columns={[
              { key: 'id', header: 'Charge', render: (c) => <span className="mono">{c.id}</span> },
              { key: 'date', header: 'Date', render: (c) => <DateText value={c.date} /> },
              { key: 'name', header: 'Fee', render: (c) => c.name },
              { key: 'amount', header: 'Amount incl. GST', numeric: true, render: (c) => <MoneyText value={c.amount} /> },
              {
                key: 'dl',
                header: <span className="sr-only">Invoice</span>,
                render: (c) => (
                  <Button size="sm" aria-label={`Download GST invoice ${c.id}`} loading={busy === `inv-${c.id}`} disabled={busy !== null && busy !== `inv-${c.id}`} onClick={() => get(`inv-${c.id}`, loanDocument.invoice(id, c.id!))}>
                    Invoice PDF
                  </Button>
                ),
              },
            ]}
            rows={fees}
            rowKey={(c) => c.id ?? ''}
            empty={<EmptyState title="No fees charged">Penal charges carry no GST, so they have no invoice.</EmptyState>}
          />
        )}
      </Card>
    </div>
  );
}

const ROLE_TONE = { BORROWER: 'accent', CO_APPLICANT: 'info', GUARANTOR: 'warn' } as const;

export function LoanPartiesTab({ loan }: { loan: Loan }) {
  const q = useLoanParties(loan.id);
  if (q.isLoading) return <Spinner />;
  if (q.error) return <ErrorBanner error={q.error} />;
  return (
    <Card flush>
      <Table
        caption="Loan parties"
        captionHidden
        columns={[
          { key: 'role', header: 'Role', render: (p) => <Badge tone={ROLE_TONE[p.role]}>{humanize(p.role)}</Badge> },
          { key: 'name', header: 'Customer', render: (p) => <Link to={`/customers/${p.customerId}`}>{p.customerName ?? p.customerNo}</Link> },
          { key: 'no', header: 'Customer no.', render: (p) => <span className="mono">{p.customerNo}</span> },
          { key: 'status', header: 'Status', render: (p) => <StatusBadge status={p.customerStatus} /> },
          { key: 'by', header: 'Added by', render: (p) => <span className="mono">{p.addedBy ?? '—'}</span> },
        ]}
        rows={q.data ?? []}
        rowKey={(p) => `${p.role}-${p.customerId}`}
        empty={<EmptyState title="No parties" />}
      />
    </Card>
  );
}
