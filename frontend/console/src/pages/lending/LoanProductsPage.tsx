import { Link, useNavigate } from 'react-router';
import { useMe } from '../../api/hooks';
import { useLoanProducts } from '../../api/lendingHooks';
import type { LoanProduct } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, EmptyState, ErrorBanner, MoneyText, PageHeader, Spinner, StatusBadge, Table } from '../../ui';
import { REPAYMENT_METHOD_LABEL, pct } from './common';

export function LoanProductsPage() {
  const me = useMe().data!;
  const q = useLoanProducts();
  const navigate = useNavigate();
  const canPropose = hasPermission(me.permissions, P.productPropose);
  return (
    <div className="stack">
      <PageHeader
        title="Loan products"
        subtitle="Terms, fees and interest rules. Changes go through maker-checker; each approval creates a new version."
        actions={canPropose && <Button variant="primary" onClick={() => navigate('/loan-products/new')}>New product</Button>}
      />
      <Card flush>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Loan products"
            captionHidden
            columns={[
              { key: 'code', header: 'Code', render: (p) => <Link to={`/loan-products/${encodeURIComponent(p.code)}`} className="mono" onClick={(e) => e.stopPropagation()}>{p.code}</Link> },
              { key: 'name', header: 'Name', render: (p) => p.name },
              { key: 'method', header: 'Repayment', render: (p) => REPAYMENT_METHOD_LABEL[p.repaymentMethod] ?? p.repaymentMethod },
              { key: 'amount', header: 'Amount', render: (p) => <span><MoneyText value={String(p.minAmount)} /> – <MoneyText value={String(p.maxAmount)} /></span> },
              { key: 'tenor', header: 'Tenor (months)', render: (p) => `${p.minTenorMonths}–${p.maxTenorMonths}` },
              { key: 'rate', header: 'Rate band', render: (p) => `${pct(p.minRate)} – ${pct(p.maxRate)}` },
              { key: 'table', header: 'Interest table', render: (p) => (p.interestTableCode ? <span className="mono">{p.interestTableCode}</span> : <span className="muted">Rate per loan</span>) },
              { key: 'fees', header: 'Fees', numeric: true, render: (p) => (p.fees ?? []).length },
              { key: 'version', header: 'Version', numeric: true, render: (p) => `v${p.version ?? 1}` },
              { key: 'status', header: 'Status', render: (p) => <StatusBadge status={p.status ?? 'ACTIVE'} /> },
            ]}
            rows={q.data ?? []}
            rowKey={(p: LoanProduct) => p.code}
            onRowClick={(p) => navigate(`/loan-products/${encodeURIComponent(p.code)}`)}
            rowLabel={(p) => `Open product ${p.code}`}
            empty={<EmptyState title="No loan products yet" />}
          />
        )}
      </Card>
    </div>
  );
}
