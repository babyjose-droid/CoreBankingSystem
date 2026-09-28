import { Link, useParams } from 'react-router';
import { useEodRun, useMe, useRestartEod } from '../../api/hooks';
import type { EodStep } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { formatDuration } from '../../lib/dates';
import { Banner, Button, Card, DateText, DateTimeText, EmptyState, ErrorBanner, PageHeader, Spinner, StatusBadge, Table } from '../../ui';

const ICON: Record<string, string> = {
  COMPLETED: '✓',
  COMPLETED_WITH_EXCEPTIONS: '!',
  FAILED: '✕',
  RUNNING: '…',
  SKIPPED: '–',
};

function stepDuration(s: EodStep): string {
  if (!s.startedAt) return '';
  const end = s.finishedAt ? Date.parse(s.finishedAt) : Date.now();
  return formatDuration(end - Date.parse(s.startedAt));
}

export function EodRunDetailPage() {
  const { runId } = useParams();
  const id = Number(runId);
  const me = useMe().data!;
  const q = useEodRun(Number.isFinite(id) ? id : null);
  const restart = useRestartEod();
  const run = q.data;
  if (q.isLoading) return <Spinner />;
  if (q.error || !run) return <ErrorBanner error={q.error ?? new Error('Run not found')} />;
  const steps = run.steps ?? [];
  const done = steps.filter((s) => s.status === 'COMPLETED' || s.status === 'COMPLETED_WITH_EXCEPTIONS' || s.status === 'SKIPPED').length;
  return (
    <div className="stack">
      <PageHeader
        title={`EOD run #${run.id}`}
        subtitle={
          <>
            Business date <DateText value={run.businessDate} weekday />
          </>
        }
        actions={
          <>
            <Link to="/eod/runs">All runs</Link>
            {run.status === 'FAILED' && hasPermission(me.permissions, P.eodRun) && (
              <Button variant="primary" loading={restart.isPending} onClick={() => restart.mutate(run.id)}>
                Restart from failed step
              </Button>
            )}
          </>
        }
      />
      <ErrorBanner error={restart.error} />
      <Card title="Progress">
        <div className="stack">
          <div className="row" style={{ alignItems: 'center' }} aria-live="polite" data-testid="run-status">
            <StatusBadge status={run.status} />
            <span className="muted">
              {done}/{steps.length} steps · started <DateTimeText value={run.startedAt} />
              {run.finishedAt && (
                <>
                  {' '}
                  · finished <DateTimeText value={run.finishedAt} />
                </>
              )}
            </span>
            {run.status === 'RUNNING' && <Spinner label="Running" />}
          </div>
          <div
            role="progressbar"
            aria-label="EOD progress"
            aria-valuemin={0}
            aria-valuemax={steps.length}
            aria-valuenow={done}
            style={{ height: 6, background: 'var(--surface-3)', borderRadius: 3, overflow: 'hidden' }}
          >
            <div style={{ width: `${steps.length ? (done / steps.length) * 100 : 0}%`, height: '100%', background: run.status === 'FAILED' ? 'var(--danger)' : 'var(--accent)', transition: 'width .3s' }} />
          </div>
          {run.nextBusinessDate && run.status !== 'RUNNING' && run.status !== 'FAILED' && (
            <Banner tone={run.status === 'COMPLETED' ? 'ok' : 'warn'}>
              Business date advanced to&nbsp;<strong><DateText value={run.nextBusinessDate} weekday /></strong>
              {run.status === 'COMPLETED_WITH_EXCEPTIONS' && ' — with exceptions to review below.'}
            </Banner>
          )}
          {run.status === 'FAILED' && <Banner tone="danger">The run failed. Fix the cause and restart; completed steps are not repeated.</Banner>}
          <ol className="timeline" aria-label="Steps">
            {steps.map((s) => (
              <li key={s.stepNo} className="timeline__item" data-testid={`step-${s.stepNo}`}>
                <span className={`timeline__icon timeline__icon--${s.status}`} aria-hidden="true">
                  {ICON[s.status ?? ''] ?? s.stepNo}
                </span>
                <div>
                  <div style={{ fontWeight: 500 }}>
                    {s.stepNo}. {s.name}
                  </div>
                  <StatusBadge status={s.status} />
                </div>
                <div className="timeline__meta">
                  {(s.processed ?? 0) > 0 && <div>{s.processed?.toLocaleString('en-IN')} processed</div>}
                  {(s.failed ?? 0) > 0 && <div style={{ color: 'var(--danger)' }}>{s.failed} failed</div>}
                  <div>{stepDuration(s)}</div>
                </div>
              </li>
            ))}
          </ol>
        </div>
      </Card>
      <Card title={`Exceptions (${(run.exceptions ?? []).length})`} flush>
        <Table
          caption="Exceptions"
          captionHidden
          columns={[
            { key: 'step', header: 'Step', render: (e) => e.step },
            { key: 'acct', header: 'Account', render: (e) => <span className="mono">{e.accountNo}</span> },
            { key: 'err', header: 'Error', render: (e) => e.error },
            { key: 'res', header: 'Resolved', render: (e) => (e.resolved ? 'Yes' : <StatusBadge status="PENDING" />) },
          ]}
          rows={run.exceptions ?? []}
          rowKey={(e) => `${e.step}-${e.accountNo}-${e.error}`}
          empty={<EmptyState title="No exceptions" />}
        />
      </Card>
    </div>
  );
}
