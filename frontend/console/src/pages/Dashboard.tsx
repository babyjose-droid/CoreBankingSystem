import { Link } from 'react-router';
import { useDashboard } from '../api/extraHooks';
import type { Dashboard as DashboardData, DpdBucket } from '../api/types';
import { formatINR, isMoney, toUnits } from '../lib/money';
import { Card, DateTimeText, ErrorBanner, MoneyText, Spinner, StatusBadge } from '../ui';

const BUCKET_LABEL: Record<DpdBucket['bucket'], string> = {
  '0': 'Current (0)',
  '1-30': '1–30 days',
  '31-60': '31–60 days',
  '61-90': '61–90 days',
  '91-180': '91–180 days',
  '181-365': '181–365 days',
  '>365': 'Over 365 days',
};

const pct = (v: string | null | undefined) => (v === null || v === undefined ? '—' : `${Number(v).toFixed(2)}%`);

function Tile({ label, children, sub, testId }: { label: string; children: React.ReactNode; sub?: React.ReactNode; testId?: string }) {
  return (
    <div className="stat" data-testid={testId}>
      <div className="stat__label">{label}</div>
      <div className="stat__value">{children}</div>
      {sub && <div className="stat__sub">{sub}</div>}
    </div>
  );
}

/**
 * Horizontal bars, one per DPD bucket, sized by principal outstanding. Every bar carries its count and amount as
 * text, so nothing is conveyed by the bar (or its colour) alone and the list doubles as the table view.
 */
export function DpdChart({ buckets }: { buckets: DpdBucket[] }) {
  const units = (b: DpdBucket) => (isMoney(b.amount) ? toUnits(b.amount) : 0n);
  const max = buckets.reduce((m, b) => (units(b) > m ? units(b) : m), 0n);
  return (
    <ul className="barchart" aria-label="Loans and principal outstanding by days past due">
      {buckets.map((b) => {
        const width = max > 0n ? Number((units(b) * 1000n) / max) / 10 : 0;
        const text = `${b.loans} loan${b.loans === 1 ? '' : 's'} · ${formatINR(b.amount)}`;
        return (
          <li key={b.bucket} className="barchart__row" data-bucket={b.bucket} title={`${BUCKET_LABEL[b.bucket] ?? b.bucket}: ${text}`}>
            <span className="barchart__label">{BUCKET_LABEL[b.bucket] ?? b.bucket}</span>
            <span className="barchart__track" aria-hidden="true">
              <span className="barchart__bar" style={{ width: `${width}%` }} />
            </span>
            <span className="barchart__value">{text}</span>
          </li>
        );
      })}
    </ul>
  );
}

export function DashboardView({ d }: { d: DashboardData }) {
  return (
    <div className="stack" data-testid="dashboard">
      <div className="stat-grid">
        <Tile label="Portfolio outstanding" testId="tile-portfolio" sub={<>overdue <MoneyText value={d.overdueAmount} /></>}>
          <MoneyText value={d.portfolioOutstanding} />
        </Tile>
        <Tile label="Active loans" testId="tile-active">
          {d.activeLoans}
        </Tile>
        <Tile label="Disbursed today" testId="tile-disbursed" sub={<>month to date <MoneyText value={d.disbursedMtd} /> ({d.disbursementsMtd ?? 0})</>}>
          <MoneyText value={d.disbursedToday} />
        </Tile>
        <Tile label="Collected today" testId="tile-collected" sub={<>month to date <MoneyText value={d.collectedMtd} /></>}>
          <MoneyText value={d.collectedToday} />
        </Tile>
        <Tile label="Collection efficiency (MTD)" testId="tile-efficiency" sub={<><MoneyText value={d.collectedAgainstDemandMtd} /> of <MoneyText value={d.demandMtd} /> due</>}>
          {pct(d.collectionEfficiencyMtd)}
        </Tile>
        <Tile label="Gross NPA" testId="tile-npa" sub={<><MoneyText value={d.grossNpa} /> in {d.npaLoans ?? 0} loan(s)</>}>
          {pct(d.npaPercent)}
        </Tile>
      </div>
      <div className="grid-cards">
        <Card title="Days past due" className="span-2">
          <DpdChart buckets={d.dpdBuckets} />
          <p className="muted" style={{ margin: '8px 0 0', fontSize: 12 }}>Bar length shows principal outstanding. Figures cover the branches you can see.</p>
        </Card>
        <Card title="Pending approvals" actions={<Link to="/approvals?status=PENDING">Open queue</Link>}>
          <div className="big-date" data-testid="dash-pending">{d.pendingApprovals}</div>
          <p className="muted" style={{ margin: '4px 0 0' }}>request(s) awaiting a checker.</p>
        </Card>
        <Card title="Last end-of-day" actions={<Link to="/eod/runs">Runs</Link>}>
          {d.lastEod ? (
            <dl className="kv">
              <dt>Business date</dt>
              <dd>{d.lastEod.businessDate}</dd>
              <dt>Status</dt>
              <dd>
                <StatusBadge status={d.lastEod.status} />
              </dd>
              <dt>Finished</dt>
              <dd>
                <DateTimeText value={d.lastEod.finishedAt} />
              </dd>
              <dt>Open exceptions</dt>
              <dd>{d.openEodExceptions ?? 0}</dd>
            </dl>
          ) : (
            <p className="muted" style={{ margin: 0 }}>No end-of-day run yet.</p>
          )}
        </Card>
      </div>
    </div>
  );
}

export function Dashboard() {
  const q = useDashboard(true);
  if (q.isLoading) return <Spinner label="Loading the dashboard" />;
  if (q.error || !q.data) return <ErrorBanner error={q.error ?? new Error('Dashboard not available')} />;
  return <DashboardView d={q.data} />;
}
