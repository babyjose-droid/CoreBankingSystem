import { useState } from 'react';
import { useAuditEvents, useVerifyAudit } from '../api/hooks';
import { Banner, Button, Card, DateTimeText, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, Table, humanize } from '../ui';

const TYPES = ['CUSTOMER', 'VOUCHER', 'BRANCH', 'GL_HEAD', 'TAX_RATE', 'HOLIDAY', 'EOD_RUN', 'EOD_SCHEDULE', 'TENANT'];

export function AuditPage() {
  const [entityType, setEntityType] = useState('');
  const [entityId, setEntityId] = useState('');
  const [limit, setLimit] = useState(100);
  const q = useAuditEvents({ entityType, entityId: entityId.trim(), limit });
  const verify = useVerifyAudit();
  return (
    <div className="stack">
      <PageHeader
        title="Audit trail"
        subtitle="Append-only, hash-chained log of every change."
        actions={
          <Button variant="primary" loading={verify.isPending} onClick={() => verify.mutate()}>
            Verify chain
          </Button>
        }
      />
      {verify.data &&
        (verify.data.intact ? (
          <Banner tone="ok">✓ Audit chain intact — no tampering detected.</Banner>
        ) : (
          <Banner tone="danger">✕ Audit chain broken at event #{verify.data.firstBrokenId}.</Banner>
        ))}
      <ErrorBanner error={verify.error} />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select label="Entity type" value={entityType} onChange={(e) => setEntityType(e.target.value)} options={[{ value: '', label: 'All' }, ...TYPES.map((t) => ({ value: t, label: humanize(t) }))]} />
          <Input label="Entity id" value={entityId} onChange={(e) => setEntityId(e.target.value)} className="mono" />
          <Select label="Show" value={String(limit)} onChange={(e) => setLimit(Number(e.target.value))} options={[50, 100, 500, 1000].map((n) => ({ value: String(n), label: `Latest ${n}` }))} />
        </div>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Audit events, most recent first"
            captionHidden
            columns={[
              { key: 'id', header: '#', numeric: true, render: (e) => e.id },
              { key: 'at', header: 'When', render: (e) => <DateTimeText value={e.at} /> },
              { key: 'actor', header: 'Actor', render: (e) => <span className="mono">{e.actor}</span> },
              { key: 'action', header: 'Action', render: (e) => <span className="mono">{e.action}</span> },
              { key: 'type', header: 'Entity', render: (e) => (e.entityType ? humanize(e.entityType) : '') },
              { key: 'eid', header: 'Entity id', render: (e) => <span className="mono" style={{ fontSize: 12 }}>{e.entityId ?? '—'}</span> },
              {
                key: 'detail',
                header: 'Detail',
                render: (e) =>
                  e.detail ? (
                    <details>
                      <summary>View</summary>
                      <pre className="mono" style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>
                        {JSON.stringify(e.detail, null, 2)}
                      </pre>
                    </details>
                  ) : (
                    ''
                  ),
              },
            ]}
            rows={q.data ?? []}
            rowKey={(e) => String(e.id)}
            empty={<EmptyState title="No audit events match" />}
          />
        )}
      </Card>
    </div>
  );
}
