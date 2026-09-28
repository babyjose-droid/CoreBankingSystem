import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useBusinessDay, useEodRuns, useMe, useStartEod } from '../../api/hooks';
import { P, hasPermission } from '../../auth/permissions';
import { formatDuration } from '../../lib/dates';
import { Banner, Button, Card, DateText, DateTimeText, Dialog, EmptyState, ErrorBanner, PageHeader, Spinner, StatusBadge, Table } from '../../ui';

export function EodRunsPage() {
  const me = useMe().data!;
  const runs = useEodRuns();
  const day = useBusinessDay();
  const start = useStartEod();
  const navigate = useNavigate();
  const [confirm, setConfirm] = useState(false);
  const running = runs.data?.find((r) => r.status === 'RUNNING');
  const canRun = hasPermission(me.permissions, P.eodRun);
  const businessDate = day.data?.businessDate ?? me.businessDate;
  return (
    <div className="stack">
      <PageHeader
        title="End-of-day runs"
        subtitle="End-of-day closes the business date and is the only way it advances."
        actions={
          canRun && (
            <Button variant="primary" disabled={!!running} title={running ? `Run #${running.id} is in progress` : undefined} onClick={() => setConfirm(true)}>
              Start EOD
            </Button>
          )
        }
      />
      {running && (
        <Banner tone="info">
          Run #{running.id} for <DateText value={running.businessDate} /> is running.{' '}
          <Link to={`/eod/runs/${running.id}`}>Watch progress</Link>
        </Banner>
      )}
      <Card flush>
        <ErrorBanner error={runs.error} />
        {runs.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="End-of-day runs, most recent first"
            captionHidden
            columns={[
              { key: 'id', header: 'Run', render: (r) => <span className="mono">#{r.id}</span> },
              { key: 'date', header: 'Business date', render: (r) => <DateText value={r.businessDate} /> },
              { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
              { key: 'started', header: 'Started', render: (r) => <DateTimeText value={r.startedAt} /> },
              {
                key: 'dur',
                header: 'Duration',
                numeric: true,
                render: (r) => (r.startedAt && r.finishedAt ? formatDuration(Date.parse(r.finishedAt) - Date.parse(r.startedAt)) : '—'),
              },
              { key: 'exc', header: 'Exceptions', numeric: true, render: (r) => (r.exceptions ?? []).length },
              { key: 'next', header: 'Next business date', render: (r) => <DateText value={r.nextBusinessDate} /> },
            ]}
            rows={runs.data ?? []}
            rowKey={(r) => String(r.id)}
            onRowClick={(r) => navigate(`/eod/runs/${r.id}`)}
            rowLabel={(r) => `Open run ${r.id}`}
            empty={<EmptyState title="No end-of-day runs yet" />}
          />
        )}
      </Card>
      <Dialog
        open={confirm}
        onClose={() => (setConfirm(false), start.reset())}
        title="Start end-of-day?"
        footer={
          <>
            <Button onClick={() => (setConfirm(false), start.reset())}>Cancel</Button>
            <Button
              variant="primary"
              loading={start.isPending}
              onClick={() =>
                start.mutate(undefined, {
                  onSuccess: (run) => {
                    setConfirm(false);
                    navigate(`/eod/runs/${run.id}`);
                  },
                })
              }
            >
              Start EOD for <DateText value={businessDate} />
            </Button>
          </>
        }
      >
        <p style={{ marginTop: 0 }}>
          This closes business date <strong><DateText value={businessDate} weekday /></strong>: accrues interest, marks NPAs, raises demands, provisions, snapshots GL balances and
          then advances the business date to the next working day.
        </p>
        <p className="muted">Users can keep working; postings made during EOD are dated to the next business date.</p>
        <ErrorBanner error={start.error} />
      </Dialog>
    </div>
  );
}
