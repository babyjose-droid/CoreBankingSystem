import { Link, useParams } from 'react-router';
import { useAuditEvents, useCustomer, useMe } from '../../api/hooks';
import { P, hasPermission } from '../../auth/permissions';
import { Card, DateText, DateTimeText, ErrorBanner, Masked, PageHeader, Spinner, StatusBadge, Table } from '../../ui';

export function CustomerDetailPage() {
  const { id } = useParams();
  const me = useMe().data!;
  const q = useCustomer(id);
  const canAudit = hasPermission(me.permissions, P.auditView);
  const audit = useAuditEvents({ entityType: 'CUSTOMER', entityId: q.data?.id, limit: 20 }, canAudit && !!q.data);
  if (q.isLoading) return <Spinner />;
  if (q.error || !q.data) return <ErrorBanner error={q.error ?? new Error('Customer not found')} />;
  const c = q.data;
  return (
    <div className="stack">
      <PageHeader title={c.displayName} subtitle={<span className="mono">{c.customerNo}</span>} actions={<Link to="/customers">Back to search</Link>} />
      <div className="grid-cards">
        <Card title="Profile">
          <dl className="kv">
            <dt>Customer no.</dt>
            <dd className="mono">{c.customerNo}</dd>
            <dt>Type</dt>
            <dd>{c.customerType === 'NON_INDIVIDUAL' ? 'Non-individual' : 'Individual'}</dd>
            <dt>{c.customerType === 'NON_INDIVIDUAL' ? 'Incorporated' : 'Date of birth'}</dt>
            <dd>
              <DateText value={c.dateOfBirth} />
            </dd>
            <dt>Home branch</dt>
            <dd>{c.homeBranch}</dd>
            <dt>Status</dt>
            <dd>
              <StatusBadge status={c.status} />
            </dd>
          </dl>
        </Card>
        <Card title="Identification & contact">
          <dl className="kv">
            <dt>PAN</dt>
            <dd>
              <Masked value={c.panMasked} kind="PAN" />
            </dd>
            <dt>Mobile</dt>
            <dd>
              <Masked value={c.mobileMasked} kind="mobile" />
            </dd>
            <dt>KYC</dt>
            <dd>
              <StatusBadge status={c.kycStatus} />
            </dd>
          </dl>
          <p className="muted" style={{ marginBottom: 0, fontSize: 12 }}>
            Full identifiers are never shown in the console.
          </p>
        </Card>
      </div>
      {canAudit && (
        <Card title="History" flush>
          <Table
            caption="Audit events for this customer"
            captionHidden
            columns={[
              { key: 'at', header: 'When', render: (e) => <DateTimeText value={e.at} /> },
              { key: 'actor', header: 'Actor', render: (e) => <span className="mono">{e.actor}</span> },
              { key: 'action', header: 'Action', render: (e) => e.action },
            ]}
            rows={audit.data ?? []}
            rowKey={(e) => String(e.id)}
          />
        </Card>
      )}
    </div>
  );
}
