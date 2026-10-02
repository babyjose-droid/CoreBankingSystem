import { Link } from 'react-router';
import { useBusinessDay, useEodRuns, useMe } from '../api/hooks';
import { P, hasPermission } from '../auth/permissions';
import { formatDateTime } from '../lib/dates';
import { branchScopeText } from '../layout/branchScope';
import { Dashboard } from './Dashboard';
import { usePendingForMe } from '../layout/usePendingForMe';
import { Badge, Card, DateText, EmptyState, PageHeader, Spinner, StatusBadge, humanize } from '../ui';

export function HomePage() {
  const me = useMe().data!;
  const can = (p: string) => hasPermission(me.permissions, p);
  const day = useBusinessDay();
  const runs = useEodRuns(can(P.eodView));
  const pending = usePendingForMe();
  const last = runs.data?.[0];
  const byType = pending.all.reduce<Record<string, number>>((acc, a) => {
    acc[a.entityType] = (acc[a.entityType] ?? 0) + 1;
    return acc;
  }, {});

  const actions = [
    can(P.approvalApprove) && { to: '/approvals', label: `Review approvals (${pending.actionable.length})` },
    can(P.customerCreate) && { to: '/customers/new', label: 'New customer' },
    can(P.loanCreate) && { to: '/loans/new', label: 'New loan' },
    can(P.loanView) && { to: '/loans', label: 'Loans' },
    can(P.voucherCreate) && { to: '/ledger/vouchers/new', label: 'New voucher' },
    can(P.eodRun) && { to: '/eod/runs', label: 'Run end-of-day' },
    can(P.glView) && { to: '/ledger/trial-balance', label: 'Trial balance' },
    can(P.reportRun) && { to: '/reports', label: 'Reports' },
    can(P.auditView) && { to: '/audit', label: 'Audit trail' },
  ].filter(Boolean) as Array<{ to: string; label: string }>;

  return (
    <div className="stack">
      <PageHeader title={`Welcome, ${me.displayName.split(' ')[0]}`} subtitle={`${me.tenantName ?? me.tenant} · home branch ${me.homeBranch ?? '—'} · ${branchScopeText(me).replace('Branch scope: ', 'sees ')}`} />
      {can(P.dashboardView) && <Dashboard />}
      <div className="grid-cards">
        {can(P.dashboardView) ? null : (
        <Card title="Business date">
          <div className="big-date">
            <DateText value={day.data?.businessDate ?? me.businessDate} weekday />
          </div>
          <p className="muted" style={{ margin: '4px 0 12px' }}>
            Changes only through end-of-day.
          </p>
          <div className="row" style={{ alignItems: 'center' }}>
            <span className="muted">Day status</span>
            {day.data ? <StatusBadge status={day.data.status} /> : <Spinner />}
          </div>
        </Card>
        )}

        {pending.canView && !can(P.dashboardView) && (
          <Card title="Pending approvals" actions={<Link to="/approvals?status=PENDING">Open queue</Link>}>
            {pending.isLoading ? (
              <Spinner />
            ) : pending.all.length === 0 ? (
              <EmptyState title="Nothing pending" />
            ) : (
              <dl className="kv">
                {Object.entries(byType).map(([t, n]) => (
                  <div key={t} style={{ display: 'contents' }}>
                    <dt>
                      <Link to={`/approvals?status=PENDING&entityType=${t}`}>{humanize(t)}</Link>
                    </dt>
                    <dd className="num" style={{ textAlign: 'left' }}>
                      {n}
                    </dd>
                  </div>
                ))}
              </dl>
            )}
            {can(P.approvalApprove) && (
              <p className="muted" style={{ marginBottom: 0 }}>
                {pending.actionable.length} awaiting your decision.
              </p>
            )}
          </Card>
        )}

        {can(P.eodView) && !can(P.dashboardView) && (
          <Card title="Last end-of-day" actions={last && <Link to={`/eod/runs/${last.id}`}>Details</Link>}>
            {runs.isLoading ? (
              <Spinner />
            ) : !last ? (
              <EmptyState title="No runs yet" />
            ) : (
              <dl className="kv">
                <dt>Run</dt>
                <dd>#{last.id}</dd>
                <dt>Business date</dt>
                <dd>
                  <DateText value={last.businessDate} />
                </dd>
                <dt>Status</dt>
                <dd>
                  <StatusBadge status={last.status} />
                </dd>
                <dt>Steps</dt>
                <dd>
                  {(last.steps ?? []).filter((s) => s.status === 'COMPLETED' || s.status === 'COMPLETED_WITH_EXCEPTIONS').length}/{last.steps?.length ?? 0} done
                </dd>
                <dt>Exceptions</dt>
                <dd>{(last.exceptions ?? []).length ? <Badge tone="warn">{last.exceptions!.length}</Badge> : '0'}</dd>
                <dt>Finished</dt>
                <dd>{formatDateTime(last.finishedAt)}</dd>
              </dl>
            )}
          </Card>
        )}

        <Card title="Quick actions">
          {actions.length === 0 ? (
            <EmptyState title="No actions available" />
          ) : (
            <ul style={{ margin: 0, paddingLeft: 18, lineHeight: 2 }}>
              {actions.map((a) => (
                <li key={a.to + a.label}>
                  <Link to={a.to}>{a.label}</Link>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}
