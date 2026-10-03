import { Link, useNavigate, useParams } from 'react-router';
import { useApprovals, useMe } from '../../api/hooks';
import { useLoanProduct } from '../../api/lendingHooks';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, DateTimeText, EmptyState, ErrorBanner, MoneyText, PageHeader, Spinner, StatusBadge, Table, humanize } from '../../ui';
import { BPI_LABEL, FREQUENCY_LABEL, INTEREST_BASIS_LABEL, REPAYMENT_METHOD_LABEL, describeFee, pct } from './common';

export function LoanProductDetailPage() {
  const { code } = useParams();
  const me = useMe().data!;
  const navigate = useNavigate();
  const q = useLoanProduct(code);
  const canApprovals = hasPermission(me.permissions, P.approvalView);
  const history = useApprovals({ entityType: 'LOAN_PRODUCT' }, canApprovals);
  if (q.isLoading) return <Spinner />;
  if (q.error || !q.data) return <ErrorBanner error={q.error ?? new Error('Product not found')} />;
  const p = q.data;
  const versions = (history.data ?? []).filter((a) => a.entityId === p.code || (a.proposed as { code?: string } | undefined)?.code === p.code);
  return (
    <div className="stack">
      <PageHeader
        title={`${p.name}`}
        subtitle={
          <span>
            <span className="mono">{p.code}</span> · version {p.version ?? 1} · <StatusBadge status={p.status ?? 'ACTIVE'} />
          </span>
        }
        actions={
          <>
            <Link to="/loan-products">All products</Link>
            {hasPermission(me.permissions, P.productPropose) && (
              <Button variant="primary" onClick={() => navigate(`/loan-products/${encodeURIComponent(p.code)}/edit`)}>
                Propose change
              </Button>
            )}
          </>
        }
      />
      <div className="grid-cards">
        <Card title="Terms">
          <dl className="kv">
            <dt>Repayment method</dt>
            <dd>{REPAYMENT_METHOD_LABEL[p.repaymentMethod] ?? p.repaymentMethod}</dd>
            <dt>Amount</dt>
            <dd>
              <MoneyText value={String(p.minAmount)} /> – <MoneyText value={String(p.maxAmount)} />
            </dd>
            <dt>Tenor</dt>
            <dd>
              {p.minTenorMonths}–{p.maxTenorMonths} months
            </dd>
            <dt>Frequency</dt>
            <dd>{FREQUENCY_LABEL[p.frequency ?? 'MONTHLY']}</dd>
            {p.repaymentMethod === 'STEP_EQUATED' && (
              <>
                <dt>Step</dt>
                <dd>
                  {Number(p.stepPercent) > 0 ? '+' : ''}
                  {String(p.stepPercent)}% every {p.stepEvery} instalments
                </dd>
              </>
            )}
            {(p.principalEvery ?? 1) > 1 && (
              <>
                <dt>Principal</dt>
                <dd>Every {p.principalEvery} instalments (interest every instalment)</dd>
              </>
            )}
            <dt>Disbursement</dt>
            <dd data-testid="product-disbursement">
              {p.multipleDisbursements ? `In tranches${p.preEmi ? ', pre-EMI interest until fully drawn' : ''}` : 'In one go'}
              {p.topUpAllowed ? ' · top-up allowed' : ''}
            </dd>
            <dt>Max moratorium</dt>
            <dd>{p.maxMoratoriumMonths ?? 0} months</dd>
            <dt>Cooling-off</dt>
            <dd>{p.coolingOffDays ?? 0} days</dd>
            <dt>Secured</dt>
            <dd>{p.secured ? 'Yes' : 'No'}</dd>
          </dl>
        </Card>
        <Card title="Interest">
          <dl className="kv">
            <dt>Rate band</dt>
            <dd>
              {pct(p.minRate)} – {pct(p.maxRate)} p.a.
            </dd>
            <dt>Rate type</dt>
            <dd>{humanize(p.rateType ?? 'FIXED')}</dd>
            <dt>Interest table</dt>
            <dd data-testid="interest-table">
              {p.interestTableCode ? (
                <>
                  <span className="mono">{p.interestTableCode}</span> <span className="muted">(rate looked up by amount when a loan omits it)</span>
                </>
              ) : (
                <span className="muted">None: the rate is entered per loan</span>
              )}
            </dd>
            <dt>Interest basis</dt>
            <dd>{INTEREST_BASIS_LABEL[p.interestBasis ?? 'DAILY_REDUCING']}</dd>
            {p.benchmarkCode && (
              <>
                <dt>Benchmark</dt>
                <dd>
                  <span className="mono">{p.benchmarkCode}</span> + {String(p.spread)}% · reset every {p.resetFrequencyMonths} months
                </dd>
              </>
            )}
            <dt>Broken-period interest</dt>
            <dd>{BPI_LABEL[p.bpiMode ?? 'NONE']}</dd>
            <dt>Day count</dt>
            <dd className="mono">{p.dayCount ?? 'ACTUAL_365'}</dd>
            <dt>Rounding</dt>
            <dd className="mono">{p.rounding ?? 'RUPEE_HALF_UP'}</dd>
            <dt>Penal charge</dt>
            <dd>{p.penalChargeRate === null || p.penalChargeRate === undefined ? '—' : `${pct(p.penalChargeRate)} p.a. on overdue`}</dd>
          </dl>
        </Card>
        <Card title="Servicing">
          <dl className="kv">
            <dt>Appropriation</dt>
            <dd>{(p.appropriationSequence ?? []).map(humanize).join(' → ') || '—'}</dd>
            <dt>Appropriation mode</dt>
            <dd>{humanize(p.appropriationMode ?? 'BY_DEMAND')}</dd>
            <dt>Part-prepayment</dt>
            <dd>{humanize(p.prepaymentMode ?? 'REDUCE_TENURE')}</dd>
          </dl>
        </Card>
      </div>
      <Card title="Fee rules" flush>
        <Table
          caption="Fee rules"
          captionHidden
          columns={[
            { key: 'code', header: 'Code', render: (f) => <span className="mono">{f.code}</span> },
            { key: 'name', header: 'Name', render: (f) => f.name },
            { key: 'event', header: 'Charged on', render: (f) => humanize(f.event) },
            { key: 'calc', header: 'Calculation', render: (f) => humanize(f.calcType) },
            { key: 'desc', header: 'Rule', render: (f) => describeFee(f) },
          ]}
          rows={p.fees ?? []}
          rowKey={(f) => f.code}
          empty={<EmptyState title="No fees on this product" />}
        />
      </Card>
      {canApprovals && (
        <Card title="Versions" flush>
          <Table
            caption="Product change requests"
            captionHidden
            columns={[
              { key: 'action', header: 'Change', render: (a) => humanize(a.action) },
              { key: 'status', header: 'Status', render: (a) => <StatusBadge status={a.status} /> },
              { key: 'maker', header: 'Maker', render: (a) => <span className="mono">{a.maker}</span> },
              { key: 'made', header: 'Proposed', render: (a) => <DateTimeText value={a.madeAt} /> },
              { key: 'checker', header: 'Checker', render: (a) => <span className="mono">{a.checker ?? '—'}</span> },
              { key: 'checked', header: 'Decided', render: (a) => <DateTimeText value={a.checkedAt} /> },
              { key: 'open', header: <span className="sr-only">Open</span>, render: (a) => <Link to={`/approvals?status=&id=${encodeURIComponent(a.id)}`}>View</Link> },
            ]}
            rows={versions}
            rowKey={(a) => a.id}
            empty={<EmptyState title={`Version ${p.version ?? 1} is the original set-up`}>No change requests recorded in this console.</EmptyState>}
          />
        </Card>
      )}
    </div>
  );
}
