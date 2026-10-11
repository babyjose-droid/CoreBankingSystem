import { Link, useParams } from 'react-router';
import { useDepositAccount, useDepositRules } from '../../api/depositHooks';
import type { DepositFamily } from '../../api/types';
import { Banner, Card, DateText, ErrorBanner, MoneyText, PageHeader, Spinner, StatusBadge, Table, humanize } from '../../ui';
import { NotInsured, PERIOD_LABEL, TYPE_LABEL, familyPath, pct, tenureText } from './common';

const INSTRUCTION: Record<string, string> = { PAY_OUT: 'Pay out', RENEW_PRINCIPAL: 'Renew the principal, pay out the interest', RENEW_WITH_INTEREST: 'Renew the principal with the interest' };

export function DepositAccountDetailPage({ family }: { family: DepositFamily }) {
  const { id } = useParams();
  const q = useDepositAccount(family, id);
  const rules = useDepositRules(family);
  if (q.isLoading) return <Spinner />;
  if (q.error || !q.data) return <ErrorBanner error={q.error ?? new Error('Not found')} />;
  const a = q.data;
  const t = a.termDeposit;
  const term = family === 'TERM_DEPOSIT';
  return (
    <div className="stack">
      <PageHeader
        title={`${term ? 'Term deposit' : TYPE_LABEL[a.productType ?? ''] + ' account'} ${a.accountNo}`}
        subtitle={<span>{a.holders?.[0]?.name} · <span className="mono">{a.productCode}</span> {a.productName} (version {a.productVersion})</span>}
        actions={<Link to={`/deposits/${familyPath(family)}`}>Back to the list</Link>}
      />
      {term && a.status === 'BOOKED' && <Banner tone="info">This deposit is booked and waits for its funds. Interest starts on the funding date; the dates and figures below are for funding on {t?.figuresFrom}.</Banner>}
      {term && <NotInsured rules={rules.data} />}
      <div className="grid-cards">
        <Card title={term ? 'Deposit' : 'Account'}>
          <dl className="kv">
            <dt>Status</dt>
            <dd><StatusBadge status={a.status ?? ''} /></dd>
            <dt>Branch</dt>
            <dd>{a.branchCode}</dd>
            <dt>{term ? 'Booked on' : 'Opened on'}</dt>
            <dd><DateText value={a.openedOn} /> <span className="muted">by {a.openedBy}</span></dd>
            <dt>Operated</dt>
            <dd>{humanize(a.operatingMode ?? '')}</dd>
            {!term && (
              <>
                <dt>Next interest credit</dt>
                <dd data-testid="next-interest">{a.nextInterestDate ? <DateText value={a.nextInterestDate} /> : 'None: this account bears no interest'}</dd>
              </>
            )}
            {t && (
              <>
                <dt>Amount</dt>
                <dd><MoneyText value={t.principal} /></dd>
                <dt>Tenure</dt>
                <dd>{tenureText(t.tenureMonths, t.tenureDays)}</dd>
                <dt>{t.funded ? 'Started' : 'Start (if funded today)'}</dt>
                <dd><DateText value={t.figuresFrom} /></dd>
                <dt>{t.funded ? 'Matures' : 'Maturity (if funded today)'}</dt>
                <dd><DateText value={t.figuresMaturityDate} /></dd>
              </>
            )}
          </dl>
        </Card>
        {t && (
          <Card title="Rate and interest">
            <dl className="kv">
              <dt>Rate</dt>
              <dd data-testid="deposit-rate">
                <strong>{pct(t.ratePercent)}</strong> a year
                <br />
                <span className="muted">
                  card rate {pct(t.cardRatePercent)} (amount from {Number(t.cardFromAmount).toLocaleString('en-IN')}, tenure from {tenureText(t.cardFromMonths, t.cardFromDays)})
                  {(t.addOns ?? []).map((x) => `, ${x.code} ${Number(x.percent) > 0 ? '+' : ''}${x.percent}`).join('')}
                </span>
              </dd>
              <dt>Interest</dt>
              <dd>{t.cumulative ? 'Compounded' : 'Paid out'} {(PERIOD_LABEL[t.interestPeriodMonths ?? 0] ?? '').toLowerCase()}</dd>
              <dt>Total interest</dt>
              <dd><MoneyText value={t.totalInterest} /></dd>
              <dt>Repaid at maturity</dt>
              <dd><MoneyText value={t.maturityValue} /></dd>
              <dt>Tax at source</dt>
              <dd>{t.tdsApplicable ? 'Deducted as the tax rules require' : 'Not deducted'}</dd>
              <dt>Premature withdrawal</dt>
              <dd>{!t.prematureAllowed ? 'Not allowed' : t.prematurePenaltyPercent == null ? 'As the rule set fixes it' : `Rate for the period run less ${t.prematurePenaltyPercent} point(s); no interest under ${t.prematureMinDays} days`}</dd>
              <dt>Proceeds go to</dt>
              <dd>{t.payoutTo === 'CASA' ? <span>Account <span className="mono">{t.payoutAccountNo}</span></span> : 'The depositor’s bank account'}</dd>
              <dt>At maturity</dt>
              <dd>{INSTRUCTION[t.maturityInstruction ?? 'PAY_OUT']}</dd>
            </dl>
          </Card>
        )}
      </div>
      <Card title="Holders" flush>
        <Table
          caption="Holders"
          captionHidden
          columns={[
            { key: 'n', header: 'No.', render: (h) => h.holderNo },
            { key: 'no', header: 'Customer no.', render: (h) => <Link to={`/customers/${h.customerId}`} className="mono">{h.customerNo}</Link> },
            { key: 'name', header: 'Name', render: (h) => h.name },
          ]}
          rows={a.holders ?? []}
          rowKey={(h) => String(h.holderNo)}
        />
      </Card>
      <Card title={`Nomination: ${a.nominationMode === 'NONE' ? 'no nominee' : a.nominationMode === 'SUCCESSIVE' ? 'one after another, in this order' : 'together, each a share'}`} flush>
        {(a.nominees ?? []).length > 0 && (
          <Table
            caption="Nominees"
            captionHidden
            columns={[
              { key: 'n', header: 'No.', render: (n) => n.nomineeNo },
              { key: 'name', header: 'Name', render: (n) => n.name },
              { key: 'rel', header: 'Relationship', render: (n) => humanize(n.relationship ?? '') },
              { key: 'share', header: 'Share', numeric: true, render: (n) => (n.sharePercent ? pct(n.sharePercent) : '—') },
              { key: 'minor', header: 'Minor', render: (n) => (n.minor ? `Yes; guardian ${n.guardianName}` : 'No') },
            ]}
            rows={a.nominees ?? []}
            rowKey={(n) => String(n.nomineeNo)}
          />
        )}
      </Card>
    </div>
  );
}
