import { Link, useNavigate } from 'react-router';
import { useDepositProducts } from '../../api/depositHooks';
import { useMe } from '../../api/hooks';
import type { DepositFamily, DepositProduct } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, EmptyState, ErrorBanner, PageHeader, Spinner, Table, type Column } from '../../ui';
import { NoDeposits, PERIOD_LABEL, ProductStatus, TYPE_LABEL, familyPath, pct, tenureText, useDepositModules } from './common';

function terms(p: DepositProduct): string {
  if (p.family === 'CASA') {
    const bands = p.interestBands ?? [];
    if (!bands.length) return 'No interest';
    const rates = bands.map((b) => Number(b.ratePercent));
    const range = Math.min(...rates) === Math.max(...rates) ? pct(Math.min(...rates)) : `${pct(Math.min(...rates))} to ${pct(Math.max(...rates))}`;
    return `${range}, credited ${(PERIOD_LABEL[p.interestPostingMonths ?? 3] ?? '').toLowerCase()}`;
  }
  const rates = (p.rateCard ?? []).map((c) => Number(c.ratePercent));
  return `${tenureText(p.minTenureMonths, p.minTenureDays)} to ${tenureText(p.maxTenureMonths, p.maxTenureDays)}; ${pct(Math.min(...rates))} to ${pct(Math.max(...rates))}`;
}

function ProductTable({ family, canPropose }: { family: DepositFamily; canPropose: boolean }) {
  const q = useDepositProducts(family);
  const navigate = useNavigate();
  const path = familyPath(family);
  const title = family === 'CASA' ? 'Savings and current products' : 'Term deposit products';
  const columns: Column<DepositProduct>[] = [
    { key: 'code', header: 'Code', render: (p) => <Link to={`/deposits/products/${path}/${p.code}`} className="mono" onClick={(e) => e.stopPropagation()}>{p.code}</Link> },
    { key: 'name', header: 'Name', render: (p) => p.name },
    { key: 'type', header: 'Type', render: (p) => TYPE_LABEL[p.productType ?? ''] ?? p.productType },
    { key: 'terms', header: family === 'CASA' ? 'Interest' : 'Tenure and rates', render: (p) => terms(p) },
    { key: 'status', header: 'Status', render: (p) => <ProductStatus p={p} /> },
    { key: 'version', header: 'Version', numeric: true, render: (p) => p.version },
  ];
  return (
    <Card
      flush
      title={title}
      actions={canPropose && <Button variant="primary" size="sm" onClick={() => navigate(`/deposits/products/${path}/new`)}>{family === 'CASA' ? 'New savings or current product' : 'New term deposit product'}</Button>}
    >
      <div style={{ padding: '0 12px' }}>
        <ErrorBanner error={q.error} />
      </div>
      {q.isLoading ? (
        <div style={{ padding: 16 }}><Spinner /></div>
      ) : (
        <Table
          caption={title}
          captionHidden
          columns={columns}
          rows={q.data ?? []}
          rowKey={(p) => p.code ?? ''}
          onRowClick={(p) => navigate(`/deposits/products/${path}/${p.code}`)}
          rowLabel={(p) => `Open product ${p.code}`}
          empty={<EmptyState title="No products yet" />}
        />
      )}
    </Card>
  );
}

export function DepositProductsPage() {
  const me = useMe().data!;
  const modules = useDepositModules();
  const canPropose = hasPermission(me.permissions, P.productPropose);
  if (!modules.any) return <NoDeposits />;
  return (
    <div className="stack">
      <PageHeader
        title="Deposit products"
        subtitle="A product is approved only when nothing in the rule set stops it. A term deposit product must have a rate for every amount and tenure it accepts."
      />
      {modules.casa && <ProductTable family="CASA" canPropose={canPropose} />}
      {modules.td && <ProductTable family="TERM_DEPOSIT" canPropose={canPropose} />}
    </div>
  );
}
