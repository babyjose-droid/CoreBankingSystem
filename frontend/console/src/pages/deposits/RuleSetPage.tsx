import { Badge, Banner, Card, DateText, ErrorBanner, PageHeader, Spinner, Table } from '../../ui';
import { useDepositRules } from '../../api/depositHooks';
import { NoDeposits, useDepositModules } from './common';

/** What each rule means, in a sentence; the code is shown beside it. */
const RULE_LABEL: Record<string, string> = {
  'demand-deposits-allowed': 'Savings and current accounts may be offered',
  insured: 'Deposits are insured',
  'nominees.max': 'Nominees on one account, at most',
  'td.min-tenure-months': 'Term deposit: shortest tenure (months)',
  'td.max-tenure-months': 'Term deposit: longest tenure (months)',
  'td.max-rate-percent': 'Term deposit: rate ceiling (% a year)',
  'td.min-compounding-rest-months': 'Interest paid or compounded no more often than every (months)',
  'td.lock-in-months': 'No premature repayment in the first (months)',
  'td.premature.no-interest-before-months': 'Premature repayment: no interest before (months)',
  'td.premature.rate-cut-percent': 'Premature repayment: points below the rate for the period run',
  'td.premature.rate-cut-from-minimum-percent': 'Premature repayment: points below the lowest rate, when no rate exists for the period',
  'td.premature.mandatory-up-to': 'Premature withdrawal must be allowed for deposits of individuals up to',
  'td.emergency.tiny-deposit-max': 'Emergency repayment in full: deposits in total up to',
  'td.emergency.max-percent': 'Emergency repayment: share of the principal (%)',
  'td.emergency.max-amount': 'Emergency repayment: at most',
  'td.maturity-notice-days': 'Maturity notice at least this many days before',
  'td.loan-max-percent': 'Loan against a deposit: share of the deposit (%)',
  'td.loan-spread-percent': 'Loan against a deposit: points above the deposit rate',
  'td.deposits-to-nof-multiple': 'Public deposits at most this multiple of net owned fund',
  'td.renewal-needs-consent': 'A renewal needs the depositor’s consent',
  'td.overdue-interest': 'A matured deposit left unpaid earns interest',
  'savings.uniform-rate-up-to': 'One savings rate for balances up to',
  'current.interest-allowed': 'Interest on current accounts',
  'casa.review-after-months': 'Review an account with no customer transaction after (months)',
  'casa.inoperative-after-months': 'Account inoperative after (months)',
  'unclaimed-after-years': 'Balance unclaimed after (years)',
  'tds.rate-percent': 'Tax deducted at source on interest (%)',
  'tds.no-pan-rate-percent': 'Tax deducted at source without a PAN (%)',
  'tds.threshold': 'Interest in a tax year above which tax is deducted',
  'tds.threshold-senior': 'The same threshold for a senior citizen',
  'tds.on-savings-interest': 'Tax is deducted from savings interest',
  'senior-citizen-age': 'Senior citizen from the age of',
};
const YES_NO = new Set(['demand-deposits-allowed', 'insured', 'td.renewal-needs-consent', 'td.overdue-interest', 'current.interest-allowed', 'tds.on-savings-interest']);
const value = (code: string, v: string) => (YES_NO.has(code) ? (Number(v) === 0 ? 'No' : 'Yes') : Number(v).toLocaleString('en-IN', { maximumFractionDigits: 4 }));

export function RuleSetPage() {
  const modules = useDepositModules();
  const q = useDepositRules(modules.any ?? 'TERM_DEPOSIT', modules.any !== null);
  if (!modules.any) return <NoDeposits />;
  const rs = q.data;
  return (
    <div className="stack">
      <PageHeader
        title="Deposit rule set"
        subtitle="The regulatory rules the system applies to deposits here. They are dated rows chosen by the institution's type; a product or a booking that breaks one is refused."
      />
      <ErrorBanner error={q.error} />
      {q.isLoading || !rs ? (
        <Spinner />
      ) : (
        <>
          <Card>
            <dl className="kv">
              <dt>Rule set</dt>
              <dd data-testid="rule-kind">{rs.kind === 'BANK' ? 'Bank' : rs.kind === 'NBFC_DEPOSIT' ? 'Deposit-taking NBFC' : 'None'}</dd>
              <dt>Institution type</dt>
              <dd>{rs.entityType ?? '—'}</dd>
              {rs.kind === 'NBFC_DEPOSIT' && (
                <>
                  <dt>Registration recorded</dt>
                  <dd>{rs.depositRegistrationRef ?? '—'}</dd>
                </>
              )}
              <dt>In force on</dt>
              <dd>
                <DateText value={rs.asOf} />
              </dd>
            </dl>
          </Card>
          {(rs.unverified ?? 0) > 0 && (
            <Banner tone="warn">
              {rs.unverified} of {rs.rules?.length ?? 0} rules are not verified against the regulator’s text. They are to be confirmed by compliance before the institution goes live.
            </Banner>
          )}
          <Card flush>
            <Table
              caption="Rules in force"
              captionHidden
              columns={[
                { key: 'rule', header: 'Rule', render: (r) => (<span>{RULE_LABEL[r.code ?? ''] ?? r.code}<br /><span className="muted mono" style={{ fontSize: 12 }}>{r.code}</span></span>) },
                { key: 'value', header: 'Value', numeric: true, render: (r) => <span className="num">{value(r.code ?? '', r.value ?? '0')}</span> },
                { key: 'from', header: 'From', render: (r) => <DateText value={r.effectiveFrom} /> },
                { key: 'source', header: 'Source', render: (r) => (<span>{r.source}{r.note ? <><br /><span className="muted" style={{ fontSize: 12 }}>{r.note}</span></> : null}</span>) },
                { key: 'verified', header: 'Text read', render: (r) => (r.verified ? <Badge tone="ok">Verified</Badge> : <Badge tone="warn">Not verified</Badge>) },
              ]}
              rows={rs.rules ?? []}
              rowKey={(r) => r.code ?? ''}
            />
          </Card>
        </>
      )}
    </div>
  );
}
