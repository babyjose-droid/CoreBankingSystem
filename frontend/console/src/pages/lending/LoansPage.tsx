import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useMe } from '../../api/hooks';
import { useLoans } from '../../api/lendingHooks';
import type { LoanStatus, LoanSummary } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, DateText, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, humanize, type Column } from '../../ui';
import { LoanClass, pct } from './common';

const PAGE_SIZE = 20;
const STATUSES: LoanStatus[] = ['SANCTIONED', 'ACTIVE', 'FROZEN', 'CLOSED', 'CANCELLED', 'WRITTEN_OFF'];

export function LoansPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const status = (params.get('status') ?? '') as LoanStatus | '';
  const page = Number(params.get('page') ?? 0);
  const [draft, setDraft] = useState(q);
  const me = useMe().data!;
  const navigate = useNavigate();
  const res = useLoans({ q, status, page, size: PAGE_SIZE });
  const go = (patch: { q?: string; status?: string; page?: number }) => {
    const next = new URLSearchParams();
    const nq = patch.q ?? q;
    const ns = patch.status ?? status;
    if (nq) next.set('q', nq);
    if (ns) next.set('status', ns);
    if (patch.page) next.set('page', String(patch.page));
    setParams(next);
  };
  const columns: Column<LoanSummary>[] = [
    { key: 'no', header: 'Loan no.', render: (l) => <Link to={`/loans/${l.id}`} className="mono" onClick={(e) => e.stopPropagation()}>{l.loanNo}</Link> },
    { key: 'customer', header: 'Customer', render: (l) => (<span>{l.customerName}<br /><span className="muted mono" style={{ fontSize: 12 }}>{l.customerNo}</span></span>) },
    { key: 'product', header: 'Product', render: (l) => <span className="mono">{l.productCode}</span> },
    { key: 'status', header: 'Status', render: (l) => <StatusBadge status={l.status} /> },
    { key: 'amount', header: 'Amount', numeric: true, render: (l) => <MoneyText value={l.amount} /> },
    { key: 'rate', header: 'Rate', numeric: true, render: (l) => pct(l.currentRate) },
    { key: 'emi', header: 'EMI', numeric: true, render: (l) => (l.currentEmi ? <MoneyText value={l.currentEmi} /> : <span className="muted">—</span>) },
    { key: 'outstanding', header: 'Outstanding', numeric: true, render: (l) => <MoneyText value={l.principalOutstanding} /> },
    { key: 'overdue', header: 'Overdue', numeric: true, render: (l) => <MoneyText value={l.overdueAmount} /> },
    { key: 'dpd', header: 'DPD', numeric: true, render: (l) => l.dpd ?? 0 },
    { key: 'asset', header: 'Asset class', render: (l) => <LoanClass status={l.status} value={l.assetClass} /> },
    { key: 'next', header: 'Next due', render: (l) => <DateText value={l.nextDueDate} /> },
    { key: 'branch', header: 'Branch', render: (l) => l.branch },
  ];
  const rows = res.data ?? [];
  return (
    <div className="stack">
      <PageHeader
        title="Loans"
        subtitle="Search by loan number, customer number, name or LOS reference."
        actions={hasPermission(me.permissions, P.loanCreate) && <Button variant="primary" onClick={() => navigate('/loans/new')}>New loan</Button>}
      />
      <Card flush>
        <form
          role="search"
          className="filters"
          style={{ padding: 12, marginBottom: 0 }}
          onSubmit={(e) => {
            e.preventDefault();
            go({ q: draft.trim(), page: 0 });
          }}
        >
          <Input label="Search" placeholder="1001… / 9001… / name / LOS-…" value={draft} onChange={(e) => setDraft(e.target.value)} fieldClassName="grow" style={{ minWidth: 240 }} />
          <Select
            label="Status"
            value={status}
            onChange={(e) => go({ status: e.target.value, page: 0 })}
            options={[{ value: '', label: 'All' }, ...STATUSES.map((s) => ({ value: s, label: humanize(s) }))]}
          />
          <Button type="submit" variant="primary">
            Search
          </Button>
          {(q || status) && (
            <Button
              onClick={() => {
                setDraft('');
                setParams(new URLSearchParams());
              }}
            >
              Clear
            </Button>
          )}
        </form>
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={res.error} />
        </div>
        {res.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Loans"
            captionHidden
            columns={columns}
            rows={rows}
            rowKey={(l) => l.id ?? l.loanNo ?? ''}
            onRowClick={(l) => navigate(`/loans/${l.id}`)}
            rowLabel={(l) => `Open loan ${l.loanNo}`}
            empty={<EmptyState title={q || status ? 'No loans match these filters' : 'No loans yet'} />}
          />
        )}
        <div className="row" style={{ padding: 12, justifyContent: 'flex-end' }}>
          <Button size="sm" disabled={page === 0} onClick={() => go({ page: page - 1 })}>
            Previous
          </Button>
          <span className="muted">Page {page + 1}</span>
          <Button size="sm" disabled={rows.length < PAGE_SIZE} onClick={() => go({ page: page + 1 })}>
            Next
          </Button>
        </div>
      </Card>
    </div>
  );
}
