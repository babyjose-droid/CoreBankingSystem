import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useCustomers, useMe } from '../../api/hooks';
import type { CustomerSummary } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, DateText, EmptyState, ErrorBanner, Input, Masked, PageHeader, Spinner, StatusBadge, Table, type Column } from '../../ui';

const PAGE_SIZE = 20;

export function CustomersPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? 0);
  const [draft, setDraft] = useState(q);
  const me = useMe().data!;
  const navigate = useNavigate();
  const res = useCustomers(q, page, PAGE_SIZE);
  const columns: Column<CustomerSummary>[] = [
    { key: 'no', header: 'Customer no.', render: (c) => <Link to={`/customers/${c.id}`} className="mono" onClick={(e) => e.stopPropagation()}>{c.customerNo}</Link> },
    { key: 'name', header: 'Name', render: (c) => c.displayName },
    { key: 'type', header: 'Type', render: (c) => (c.customerType === 'NON_INDIVIDUAL' ? 'Non-individual' : 'Individual') },
    { key: 'dob', header: 'DOB', render: (c) => <DateText value={c.dateOfBirth} /> },
    { key: 'pan', header: 'PAN', render: (c) => <Masked value={c.panMasked} kind="PAN" /> },
    { key: 'mobile', header: 'Mobile', render: (c) => <Masked value={c.mobileMasked} kind="mobile" /> },
    { key: 'branch', header: 'Branch', render: (c) => c.homeBranch },
    { key: 'kyc', header: 'KYC', render: (c) => <StatusBadge status={c.kycStatus} /> },
    { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
  ];
  const rows = res.data ?? [];
  return (
    <div className="stack">
      <PageHeader
        title="Customers"
        subtitle="Search by customer number, exact PAN or exact mobile. Identifiers are masked."
        actions={hasPermission(me.permissions, P.customerCreate) && <Button variant="primary" onClick={() => navigate('/customers/new')}>New customer</Button>}
      />
      <Card flush>
        <form
          role="search"
          className="filters"
          style={{ padding: 12, marginBottom: 0 }}
          onSubmit={(e) => {
            e.preventDefault();
            const next = new URLSearchParams();
            if (draft.trim()) next.set('q', draft.trim());
            setParams(next);
          }}
        >
          <Input label="Search" placeholder="9001… / ABCDE1234F / 98XXXXXXXX" value={draft} onChange={(e) => setDraft(e.target.value)} fieldClassName="grow" style={{ minWidth: 260 }} />
          <Button type="submit" variant="primary">
            Search
          </Button>
          {q && (
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
            caption="Customers"
            captionHidden
            columns={columns}
            rows={rows}
            rowKey={(c) => c.id}
            onRowClick={(c) => navigate(`/customers/${c.id}`)}
            rowLabel={(c) => `Open customer ${c.customerNo}`}
            empty={<EmptyState title={q ? `No customer matches “${q}”` : 'No customers yet'} />}
          />
        )}
        <div className="row" style={{ padding: 12, justifyContent: 'flex-end' }}>
          <Button size="sm" disabled={page === 0} onClick={() => setParams({ ...(q ? { q } : {}), page: String(page - 1) })}>
            Previous
          </Button>
          <span className="muted">Page {page + 1}</span>
          <Button size="sm" disabled={rows.length < PAGE_SIZE} onClick={() => setParams({ ...(q ? { q } : {}), page: String(page + 1) })}>
            Next
          </Button>
        </div>
      </Card>
    </div>
  );
}
