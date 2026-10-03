import { useState } from 'react';
import { useDecideSupportAccess, useSupportAccess, type SupportDecision } from '../../api/platformHooks';
import type { SupportAccess } from '../../api/types';
import { Banner, Button, Card, DateTimeText, Dialog, EmptyState, ErrorBanner, PageHeader, Select, Spinner, StatusBadge, Table, Textarea, humanize, useToast } from '../../ui';

const STATUSES: Array<NonNullable<SupportAccess['status']>> = ['REQUESTED', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED', 'LAPSED'];
const VERB: Record<SupportDecision, { title: string; button: string; done: string; noteLabel: string; required: boolean }> = {
  approve: { title: 'Approve support access', button: 'Approve access', done: 'Support access approved.', noteLabel: 'Note', required: false },
  reject: { title: 'Reject support access', button: 'Reject request', done: 'Support access rejected.', noteLabel: 'Reason for rejecting', required: true },
  revoke: { title: 'Revoke support access', button: 'Revoke access', done: 'Support access revoked.', noteLabel: 'Reason for revoking', required: false },
};

export function duration(minutes: number | undefined): string {
  if (!minutes) return '—';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return [h ? `${h} h` : '', m ? `${m} min` : ''].filter(Boolean).join(' ');
}

export function SupportAccessPage() {
  const [status, setStatus] = useState<SupportAccess['status'] | ''>('');
  const q = useSupportAccess(status);
  const [acting, setActing] = useState<{ row: SupportAccess; decision: SupportDecision } | null>(null);
  const active = (q.data ?? []).filter((x) => x.status === 'APPROVED');
  return (
    <div className="stack">
      <PageHeader
        title="Support access"
        subtitle="The vendor's support engineers cannot see this tenant's data unless someone here approves a time-limited request. Every approval, rejection and revocation is in the audit trail."
      />
      {active.length > 0 && (
        <Banner tone="warn">
          {active.length === 1 ? '1 support engineer has' : `${active.length} support engineers have`} access right now: {active.map((x) => x.engineer).join(', ')}.
        </Banner>
      )}
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select label="Status" value={status ?? ''} onChange={(e) => setStatus(e.target.value as SupportAccess['status'] | '')} options={[{ value: '', label: 'All' }, ...STATUSES.map((s) => ({ value: s, label: humanize(s) }))]} />
        </div>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Support access requests"
            captionHidden
            columns={[
              { key: 'eng', header: 'Engineer', render: (x) => <span className="mono">{x.engineer}</span> },
              { key: 'ticket', header: 'Ticket', render: (x) => <span className="mono">{x.ticket}</span> },
              { key: 'reason', header: 'Reason', render: (x) => (<span>{x.reason}{x.scope && (<><br /><span className="muted" style={{ fontSize: 12 }}>Scope: {humanize(x.scope)}</span></>)}</span>) },
              { key: 'dur', header: 'Duration', render: (x) => duration(x.durationMinutes) },
              { key: 'req', header: 'Requested', render: (x) => <DateTimeText value={x.requestedAt} /> },
              { key: 'status', header: 'Status', render: (x) => (<span><StatusBadge status={x.status} />{x.decidedBy && (<><br /><span className="muted" style={{ fontSize: 12 }}>by <span className="mono">{x.revokedBy ?? x.decidedBy}</span></span></>)}</span>) },
              { key: 'exp', header: 'Expires', render: (x) => (x.expiresAt ? <DateTimeText value={x.expiresAt} /> : <span className="muted">—</span>) },
              {
                key: 'act',
                header: <span className="sr-only">Actions</span>,
                render: (x) =>
                  x.status === 'REQUESTED' ? (
                    <span className="row" style={{ gap: 6 }}>
                      <Button size="sm" variant="primary" aria-label={`Approve access for ${x.engineer}`} onClick={() => setActing({ row: x, decision: 'approve' })}>Approve</Button>
                      <Button size="sm" aria-label={`Reject access for ${x.engineer}`} onClick={() => setActing({ row: x, decision: 'reject' })}>Reject</Button>
                    </span>
                  ) : x.status === 'APPROVED' ? (
                    <Button size="sm" variant="danger" aria-label={`Revoke access of ${x.engineer}`} onClick={() => setActing({ row: x, decision: 'revoke' })}>Revoke</Button>
                  ) : null,
              },
            ]}
            rows={q.data ?? []}
            rowKey={(x) => x.id ?? ''}
            empty={<EmptyState title="No support access requests" />}
          />
        )}
      </Card>
      {acting && <DecideDialog {...acting} onClose={() => setActing(null)} />}
    </div>
  );
}

function DecideDialog({ row, decision, onClose }: { row: SupportAccess; decision: SupportDecision; onClose: () => void }) {
  const decide = useDecideSupportAccess();
  const toast = useToast();
  const v = VERB[decision];
  const [note, setNote] = useState('');
  const [touched, setTouched] = useState(false);
  const missing = v.required && !note.trim();
  return (
    <Dialog
      open
      onClose={onClose}
      title={v.title}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant={decision === 'approve' ? 'primary' : 'danger'}
            loading={decide.isPending}
            onClick={() => {
              setTouched(true);
              if (missing) return;
              decide.mutate({ id: row.id!, decision, note: note.trim() || undefined }, { onSuccess: () => (toast({ tone: 'success', message: v.done }), onClose()) });
            }}
          >
            {v.button}
          </Button>
        </>
      }
    >
      <div className="stack">
        <p style={{ margin: 0 }}>
          <span className="mono">{row.engineer}</span> · ticket <span className="mono">{row.ticket}</span>
          <br />
          {row.reason}
        </p>
        {decision === 'approve' && <Banner tone="info">Access starts now and ends by itself after {duration(row.durationMinutes)}. You can revoke it earlier.</Banner>}
        {decision === 'revoke' && <Banner tone="warn">The engineer loses access immediately.</Banner>}
        <Textarea label={v.noteLabel} required={v.required} value={note} onChange={(e) => setNote(e.target.value)} error={touched && missing ? 'Required' : null} />
        <ErrorBanner error={decide.error} />
      </div>
    </Dialog>
  );
}
