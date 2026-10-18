import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useDepositProduct, useDepositProductHistory, useWithdrawDepositProduct } from '../../api/depositHooks';
import { useMe } from '../../api/hooks';
import type { DepositFamily, DepositProduct } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Banner, Button, Card, DateText, DateTimeText, Dialog, ErrorBanner, PageHeader, Spinner, Table, Textarea } from '../../ui';
import { NotFoundPage } from '../ForbiddenPage';
import { useProposalToast } from '../proposal';
import { PERIOD_LABEL, ProductStatus, TYPE_LABEL, familyOf, familyPath, pct, tenureText } from './common';

/** The rate card as a grid: one row per tenure step, one column per amount step. */
export function RateCardGrid({ p }: { p: DepositProduct }) {
  const cells = p.rateCard ?? [];
  const amounts = [...new Set(cells.map((c) => c.fromAmount ?? '0'))].sort((a, b) => Number(a) - Number(b));
  const tenures = [...new Map(cells.map((c) => [`${c.fromMonths}|${c.fromDays}`, c])).values()].sort((a, b) => (a.fromMonths ?? 0) * 31 + (a.fromDays ?? 0) - ((b.fromMonths ?? 0) * 31 + (b.fromDays ?? 0)));
  const rate = (a: string, m: number | undefined, d: number | undefined) => cells.find((c) => c.fromAmount === a && c.fromMonths === m && c.fromDays === d)?.ratePercent;
  return (
    <div className="table-wrap">
      <table className="table" aria-label="Rate card">
        <thead>
          <tr>
            <th scope="col">Tenure from</th>
            {amounts.map((a) => (
              <th scope="col" key={a} className="num">Amount from {Number(a).toLocaleString('en-IN')}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {tenures.map((t) => (
            <tr key={`${t.fromMonths}|${t.fromDays}`}>
              <th scope="row">{tenureText(t.fromMonths, t.fromDays)}</th>
              {amounts.map((a) => (
                <td key={a} className="num">{pct(rate(a, t.fromMonths, t.fromDays))}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function WithdrawDialog({ family, code, onClose }: { family: DepositFamily; code: string; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const withdraw = useWithdrawDepositProduct(family, code);
  const toast = useProposalToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Withdraw product ${code}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={withdraw.isPending} disabled={!reason.trim()} onClick={() => withdraw.mutate(reason.trim(), { onSuccess: (a) => (toast(a, 'Withdrawal'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>A withdrawn product takes no new account. Its accounts go on as they are.</p>
      <Textarea label="Reason" required rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <ErrorBanner error={withdraw.error} />
    </Dialog>
  );
}

export function DepositProductDetailPage() {
  const params = useParams();
  const family = familyOf(params.family);
  const me = useMe().data!;
  const navigate = useNavigate();
  const q = useDepositProduct(family ?? 'CASA', family ? params.code : undefined);
  const history = useDepositProductHistory(family ?? 'CASA', family ? params.code : undefined, hasPermission(me.permissions, P.productView));
  const [withdrawing, setWithdrawing] = useState(false);
  if (!family) return <NotFoundPage />;
  if (q.isLoading) return <Spinner />;
  if (q.error || !q.data) return <ErrorBanner error={q.error ?? new Error('Product not found')} />;
  const p = q.data;
  const canPropose = hasPermission(me.permissions, P.productPropose) && p.status === 'ACTIVE';
  const path = familyPath(family);
  return (
    <div className="stack">
      <PageHeader
        title={p.name}
        subtitle={<span><span className="mono">{p.code}</span> · {TYPE_LABEL[p.productType ?? '']} · version {p.version}</span>}
        actions={
          <>
            {canPropose && <Button onClick={() => navigate(`/deposits/products/${path}/${p.code}/edit`)}>Propose change</Button>}
            {canPropose && <Button onClick={() => setWithdrawing(true)}>Withdraw</Button>}
            <Link to="/deposits/products">All products</Link>
          </>
        }
      />
      {p.status !== 'ACTIVE' && <Banner tone="warn">This product is withdrawn: it takes no new account.</Banner>}
      <div className="grid-cards">
        <Card title="Product">
          <dl className="kv">
            <dt>Status</dt>
            <dd><ProductStatus p={p} /></dd>
            <dt>In force from</dt>
            <dd><DateText value={p.effectiveFrom} /></dd>
            {family === 'CASA' ? (
              <>
                <dt>Minimum balance</dt>
                <dd className="num">{Number(p.minBalance ?? 0).toLocaleString('en-IN')}</dd>
                <dt>Cheque facility</dt>
                <dd>{p.chequeFacility ? 'Yes' : 'No'}</dd>
                <dt>Charges</dt>
                <dd data-testid="product-charges">
                  {!p.charges ? (
                    'None'
                  ) : (
                    <span>
                      {p.charges.minBalancePercent ? `Minimum balance ${p.charges.minBalancePercent}% of the shortfall, at most ${p.charges.minBalanceMax} a month` : 'No minimum balance charge'}
                      {'; '}
                      {p.charges.closureAmount ? `closure ${p.charges.closureAmount} (free within ${p.charges.closureFreeWithinDays} days${p.charges.closureWithinDays ? `, none after ${p.charges.closureWithinDays}` : ''})` : 'no closure charge'}
                      {(p.charges.service ?? []).map((x) => `; ${x.name} ${x.amount}`).join('')}
                      {`; GST ${p.charges.gstRatePercent}% on top`}
                    </span>
                  )}
                </dd>
              </>
            ) : (
              <>
                <dt>Deposit amount</dt>
                <dd className="num">{Number(p.minAmount).toLocaleString('en-IN')} {p.maxAmount ? `to ${Number(p.maxAmount).toLocaleString('en-IN')}` : 'and above'}</dd>
                <dt>Tenure</dt>
                <dd data-testid="product-tenure">{tenureText(p.minTenureMonths, p.minTenureDays)} to {tenureText(p.maxTenureMonths, p.maxTenureDays)}</dd>
                <dt>Renewal at maturity</dt>
                <dd>{p.autoRenewalAllowed ? 'Allowed' : 'Not allowed'}</dd>
              </>
            )}
            <dt>Tax deducted at source</dt>
            <dd data-testid="product-tds">{p.tdsApplicable ? 'Deducted as the tax rules require' : 'Not deducted; no preview or schedule shows any'}</dd>
          </dl>
        </Card>
        <Card title="Interest">
          <dl className="kv">
            {family === 'CASA' ? (
              (p.interestBands ?? []).length === 0 ? (
                <>
                  <dt>Interest</dt>
                  <dd>None: this account bears no interest</dd>
                </>
              ) : (
                <>
                  <dt>Rates by balance</dt>
                  <dd data-testid="product-bands">
                    {(p.interestBands ?? []).map((b) => (
                      <div key={b.fromBalance}>from {Number(b.fromBalance).toLocaleString('en-IN')}: {pct(b.ratePercent)}</div>
                    ))}
                  </dd>
                  <dt>Applied to</dt>
                  <dd>{p.bandMode === 'INCREMENTAL' ? 'Each part of the balance at the rate of its own band' : 'The whole balance at the rate of its band'}</dd>
                  <dt>Credited</dt>
                  <dd>{PERIOD_LABEL[p.interestPostingMonths ?? 3]}</dd>
                </>
              )
            ) : (
              <>
                <dt>{p.productType === 'FD_CUMULATIVE' ? 'Compounded' : 'Paid out'}</dt>
                <dd>{PERIOD_LABEL[p.interestPeriodMonths ?? 0]}</dd>
                <dt>Add-ons</dt>
                <dd data-testid="product-addons">{(p.addOns ?? []).length ? (p.addOns ?? []).map((a) => `${a.code} ${Number(a.percent) > 0 ? '+' : ''}${a.percent}%`).join(', ') : 'None'}</dd>
                <dt>Premature withdrawal</dt>
                <dd data-testid="product-premature">
                  {!p.prematureAllowed
                    ? 'Not allowed'
                    : p.prematurePenaltyPercent === null || p.prematurePenaltyPercent === undefined
                      ? 'As the rule set fixes it for this institution'
                      : `Rate for the period run less ${p.prematurePenaltyPercent} point(s); no interest under ${p.prematureMinDays} days`}
                </dd>
              </>
            )}
            {p.dayCount && (
              <>
                <dt>Day count</dt>
                <dd>{p.dayCount.replace('_', '/').toLowerCase()}</dd>
              </>
            )}
            <dt>Rounding</dt>
            <dd>{(p.rounding ?? '').replace(/_/g, ' ').toLowerCase()}</dd>
          </dl>
        </Card>
        <Card title="General ledger">
          <dl className="kv">
            <dt>Deposits (liability)</dt>
            <dd className="mono">{p.glLiability}</dd>
            <dt>Interest (expense)</dt>
            <dd className="mono">{p.glInterestExpense ?? '—'}</dd>
          </dl>
        </Card>
      </div>
      {family === 'TERM_DEPOSIT' && (
        <Card title="Rate card" flush>
          <p className="muted" style={{ margin: '8px 16px' }}>The rate is what the cell says: the full annual rate for the amount and the tenure, before add-ons. A step runs until the next one.</p>
          <RateCardGrid p={p} />
        </Card>
      )}
      <Card title="Versions" flush>
        <ErrorBanner error={history.error} />
        <Table
          caption="Versions of this product"
          captionHidden
          columns={[
            { key: 'v', header: 'Version', numeric: true, render: (h) => h.version },
            { key: 'name', header: 'Name', render: (h) => h.name },
            { key: 'status', header: 'Status', render: (h) => h.status },
            { key: 'by', header: 'Changed by', render: (h) => <span className="mono">{h.changedBy}</span> },
            { key: 'at', header: 'When', render: (h) => <DateTimeText value={h.changedAt} /> },
          ]}
          rows={history.data ?? []}
          rowKey={(h) => String(h.version)}
        />
      </Card>
      {withdrawing && <WithdrawDialog family={family} code={p.code ?? ''} onClose={() => setWithdrawing(false)} />}
    </div>
  );
}
