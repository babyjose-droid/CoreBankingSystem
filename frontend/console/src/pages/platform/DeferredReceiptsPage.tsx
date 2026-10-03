import { useState } from 'react';
import { Link } from 'react-router';
import { useMe } from '../../api/hooks';
import { useDeferredReceipts, useResolveDeferredReceipt } from '../../api/platformHooks';
import type { DeferredReceipt } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, DateText, DateTimeText, Dialog, EmptyState, ErrorBanner, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, Textarea, humanize, useToast } from '../../ui';

const STATUSES: Array<NonNullable<DeferredReceipt['status']>> = ['PENDING', 'APPLIED', 'FAILED', 'CANCELLED'];

export function DeferredReceiptsPage() {
  const me = useMe().data!;
  const canResolve = hasPermission(me.permissions, P.loanAdmin);
  const [status, setStatus] = useState<DeferredReceipt['status'] | ''>('');
  const q = useDeferredReceipts(status);
  const resolve = useResolveDeferredReceipt();
  const toast = useToast();
  const [cancelling, setCancelling] = useState<DeferredReceipt | null>(null);
  return (
    <div className="stack">
      <PageHeader
        title="Deferred receipts"
        subtitle="Repayments that arrived after the end-of-day cut-off. Each is booked and valued on the next business date once end of day has finished; nothing is back-dated."
      />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select label="Status" value={status ?? ''} onChange={(e) => setStatus(e.target.value as DeferredReceipt['status'] | '')} options={[{ value: '', label: 'All' }, ...STATUSES.map((s) => ({ value: s, label: humanize(s) }))]} />
        </div>
        <ErrorBanner error={q.error ?? (cancelling ? null : resolve.error)} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Deferred receipts"
            captionHidden
            columns={[
              { key: 'loan', header: 'Loan', render: (x) => <Link to={`/loans/${x.loanId}`} className="mono">{x.loanNo}</Link> },
              { key: 'amt', header: 'Amount', numeric: true, render: (x) => <MoneyText value={x.amount} /> },
              { key: 'mode', header: 'Mode / reference', render: (x) => [x.mode, x.reference].filter(Boolean).join(' · ') || '—' },
              { key: 'rcv', header: 'Received', render: (x) => (<span><DateTimeText value={x.receivedAt} /><br /><span className="muted mono" style={{ fontSize: 12 }}>{x.receivedBy}</span></span>) },
              { key: 'cut', header: 'Day being closed', render: (x) => <DateText value={x.cutoffBusinessDate} /> },
              { key: 'post', header: 'Booked on', render: (x) => (x.postingDate ? <DateText value={x.postingDate} /> : <span className="muted">Expected <DateText value={x.expectedPostingDate} /></span>) },
              { key: 'status', header: 'Status', render: (x) => (<span><StatusBadge status={x.status} />{(x.error || x.resolutionNote) && (<><br /><span className="muted" style={{ fontSize: 12 }}>{x.error ?? x.resolutionNote}</span></>)}</span>) },
              ...(canResolve
                ? [{
                    key: 'act',
                    header: <span className="sr-only">Actions</span>,
                    render: (x: DeferredReceipt) =>
                      x.status === 'FAILED' ? (
                        <span className="row" style={{ gap: 6 }}>
                          <Button size="sm" aria-label={`Retry receipt on ${x.loanNo}`} loading={resolve.isPending && resolve.variables?.id === x.id && resolve.variables?.note === undefined} onClick={() => resolve.mutate({ id: x.id! }, { onSuccess: (r) => toast({ tone: r.status === 'APPLIED' ? 'success' : 'error', message: r.status === 'APPLIED' ? 'Receipt booked.' : `Receipt still not booked: ${r.error ?? humanize(r.status ?? '')}` }) })}>Retry</Button>
                          <Button size="sm" aria-label={`Cancel receipt on ${x.loanNo}`} onClick={() => setCancelling(x)}>Cancel</Button>
                        </span>
                      ) : null,
                  }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(x) => x.id ?? ''}
            empty={<EmptyState title="No deferred receipts">Receipts appear here only when they arrive while end of day is running.</EmptyState>}
          />
        )}
      </Card>
      {cancelling && <CancelDialog receipt={cancelling} onClose={() => setCancelling(null)} />}
    </div>
  );
}

function CancelDialog({ receipt, onClose }: { receipt: DeferredReceipt; onClose: () => void }) {
  const resolve = useResolveDeferredReceipt();
  const toast = useToast();
  const [note, setNote] = useState('');
  const [touched, setTouched] = useState(false);
  return (
    <Dialog
      open
      onClose={onClose}
      title="Cancel deferred receipt"
      footer={
        <>
          <Button onClick={onClose}>Keep</Button>
          <Button
            variant="danger"
            loading={resolve.isPending}
            onClick={() => {
              setTouched(true);
              if (!note.trim()) return;
              resolve.mutate({ id: receipt.id!, note: note.trim() }, { onSuccess: () => (toast({ tone: 'success', message: 'Receipt cancelled.' }), onClose()) });
            }}
          >
            Cancel receipt
          </Button>
        </>
      }
    >
      <div className="stack">
        <p style={{ margin: 0 }}>
          The receipt of <MoneyText value={receipt.amount} /> on <span className="mono">{receipt.loanNo}</span> will not be booked on the loan. The money itself is not moved by this; say what was done with it.
        </p>
        <Textarea label="What was done with the money" required value={note} onChange={(e) => setNote(e.target.value)} error={touched && !note.trim() ? 'Required' : null} />
        <ErrorBanner error={resolve.error} />
      </div>
    </Dialog>
  );
}
