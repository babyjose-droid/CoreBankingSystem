import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useDepositAccount, useDepositProduct, useDepositRules } from '../../api/depositHooks';
import { useBusinessDay, useMe } from '../../api/hooks';
import type { DepositFamily } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Banner, Button, Card, DateText, ErrorBanner, MoneyText, PageHeader, Spinner, StatusBadge, Table, humanize } from '../../ui';
import { NotInsured, PERIOD_LABEL, TYPE_LABEL, familyPath, pct, tenureText, useDepositModules } from './common';
import { CasaCloseDialog, ChargesCard, ClosureCard, DepositCloseDialog, INSTRUCTION_LABEL, InstructionDialog, RenewDialog } from './DepositLifecycle';
import { ChequesCard, DormancyBanner } from './Cheques';
import { ReceiptButton, StatementDialog } from './DepositDocuments';
import { InstalmentsCard } from './RecurringDeposit';
import { PledgeDialog, StandingInstructionsCard } from './StandingInstructions';
import { BalanceCard, FundingDialog, HoldDialog, HoldsCard, InterestCard, MovementsCard, PaymentLinkDialog, PaymentLinksCard, ReverseFundingDialog, TransactionDialog, TransferDialog } from './DepositMoney';

type Action = 'IN' | 'OUT' | 'TRANSFER' | 'HOLD' | 'FUND' | 'UNFUND' | 'LINK' | 'CLOSE' | 'INSTRUCTION' | 'RENEW' | 'PLEDGE' | 'STATEMENT' | null;
const SOURCE: Record<string, string> = { ACCOUNT: 'From account', CASH: 'In cash', BANK_TRANSFER: 'By bank transfer', GATEWAY: 'Through a payment link' };

export function DepositAccountDetailPage({ family }: { family: DepositFamily }) {
  const { id } = useParams();
  const q = useDepositAccount(family, id);
  const rules = useDepositRules(family);
  const me = useMe().data!;
  const day = useBusinessDay();
  const modules = useDepositModules();
  const [action, setAction] = useState<Action>(null);
  const product = useDepositProduct(family, q.data?.productCode);
  if (q.isLoading) return <Spinner />;
  if (q.error || !q.data) return <ErrorBanner error={q.error ?? new Error('Not found')} />;
  const a = q.data;
  const t = a.termDeposit;
  const term = family === 'TERM_DEPOSIT';
  const can = (p: string) => hasPermission(me.permissions, p);
  const active = a.status === 'ACTIVE';
  const matured = a.status === 'MATURED';
  const isNbfc = rules.data?.kind === 'NBFC_DEPOSIT';
  const recurring = !!t?.recurring;
  const beforeMaturity = !!t?.funded && !!t.maturityDate && a.status === 'ACTIVE';
  const actions = (
    <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
      {!term && active && can(P.casaTransact) && (
        <>
          <Button variant="primary" onClick={() => setAction('IN')}>Money in</Button>
          <Button onClick={() => setAction('OUT')}>Money out</Button>
          <Button onClick={() => setAction('TRANSFER')}>Transfer</Button>
          <Button onClick={() => setAction('LINK')}>Payment link</Button>
        </>
      )}
      {!term && active && can(P.casaHold) && <Button onClick={() => setAction('HOLD')}>Freeze or lien</Button>}
      {term && a.status === 'BOOKED' && can(P.depositFund) && <Button variant="primary" onClick={() => setAction('FUND')}>Fund the deposit</Button>}
      {term && a.status === 'BOOKED' && can(P.depositFund) && <Button onClick={() => setAction('LINK')}>Payment link</Button>}
      {term && active && can(P.depositHold) && <Button onClick={() => setAction('HOLD')}>Lien</Button>}
      {term && active && can(P.depositHold) && <Button onClick={() => setAction('PLEDGE')}>Pledge for a loan</Button>}
      {term && active && can(P.depositFund) && <Button variant="ghost" onClick={() => setAction('UNFUND')}>Reverse the funding</Button>}
      {term && beforeMaturity && !recurring && can(P.depositFund) && <Button onClick={() => setAction('INSTRUCTION')}>Maturity instruction</Button>}
      {term && (active || matured) && can(P.depositFund) && <Button variant={matured ? 'primary' : 'ghost'} onClick={() => setAction('CLOSE')}>{matured ? 'Pay out' : 'Close the deposit'}</Button>}
      {term && matured && can(P.depositFund) && <Button onClick={() => setAction('RENEW')}>Renew</Button>}
      {!term && active && can(P.casaTransact) && <Button variant="ghost" onClick={() => setAction('CLOSE')}>Close the account</Button>}
      {a.status !== 'BOOKED' && can(term ? P.depositView : P.casaView) && <Button variant="ghost" onClick={() => setAction('STATEMENT')}>Statement</Button>}
      {term && t?.funded && can(P.depositView) && <ReceiptButton account={a} />}
      {a.holders?.[0]?.customerId && modules.td && can(P.depositView) && <Link to={`/deposits/tax?customer=${a.holders[0].customerId}`}>Tax at source</Link>}
      <Link to={`/deposits/${familyPath(family)}`}>Back to the list</Link>
    </div>
  );
  return (
    <div className="stack">
      <PageHeader
        title={`${recurring ? 'Recurring deposit' : term ? 'Term deposit' : TYPE_LABEL[a.productType ?? ''] + ' account'} ${a.accountNo}`}
        subtitle={<span>{a.holders?.[0]?.name} · <span className="mono">{a.productCode}</span> {a.productName} (version {a.productVersion})</span>}
        actions={actions}
      />
      {term && a.status === 'BOOKED' && <Banner tone="info">This deposit is booked and waits for its funds. Interest starts on the funding date; the dates and figures below are for funding on <DateText value={t?.figuresFrom} />.</Banner>}
      {term && <NotInsured rules={rules.data} />}
      {matured && <Banner tone="warn">This deposit has matured and is waiting to be paid out or renewed{isNbfc ? '' : '; it earns overdue interest as the rule set says until it is'}.</Banner>}
      {a.closure && <ClosureCard closure={a.closure} />}
      <DormancyBanner family={family} account={a} canAct={can(term ? P.depositFund : P.casaOpen)} />
      {(a.freezes ?? []).length > 0 && <Banner tone="warn">This account is under a {(a.freezes ?? []).map((f) => humanize(f).toLowerCase()).join(' and a ')}.</Banner>}
      <div className="grid-cards">
        {(!term || a.status !== 'BOOKED') && <BalanceCard a={a} />}
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
                <dt>Last customer transaction</dt>
                <dd>{a.lastCustomerTxnOn ? <DateText value={a.lastCustomerTxnOn} /> : '—'}</dd>
              </>
            )}
            {t && (
              <>
                <dt>{recurring ? 'Monthly instalment' : 'Amount'}</dt>
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
              <dd><MoneyText value={t.maturityValue} />{recurring && <span className="muted"> if every instalment is paid on time</span>}</dd>
              <dt>Tax at source</dt>
              <dd>{t.tdsApplicable ? 'Deducted as the tax rules require' : 'Not deducted'}</dd>
              <dt>Premature withdrawal</dt>
              <dd>{!t.prematureAllowed ? 'Not allowed' : t.prematurePenaltyPercent == null ? 'As the rule set fixes it' : `Rate for the period run less ${t.prematurePenaltyPercent} point(s); no interest under ${t.prematureMinDays} days`}</dd>
              <dt>Proceeds go to</dt>
              <dd>
                {t.payoutTo === 'CASA' ? (
                  <span>Account <span className="mono">{t.payoutAccountNo}</span></span>
                ) : t.payoutBankAccountMasked ? (
                  <span>The depositor’s bank account {t.payoutBankAccountMasked}, IFSC {t.payoutBankIfsc}</span>
                ) : (
                  'The depositor’s bank account (recorded when the deposit is funded)'
                )}
              </dd>
              {t.funded && (
                <>
                  <dt>Funded</dt>
                  <dd data-testid="funding">
                    {SOURCE[t.fundingSource ?? ''] ?? t.fundingSource}
                    {t.fundingAccountNo && <span> <span className="mono">{t.fundingAccountNo}</span></span>}
                    {t.fundingReference && <span className="muted"> · {t.fundingReference}</span>}
                  </dd>
                </>
              )}
              <dt>At maturity</dt>
              <dd data-testid="maturity-instruction">{INSTRUCTION_LABEL[t.maturityInstruction ?? 'PAY_OUT']}</dd>
            </dl>
          </Card>
        )}
      </div>
      {recurring && t?.funded && <InstalmentsCard account={a} canPay={can(P.depositFund)} />}
      {((!term && a.nextInterestDate) || (term && t?.funded)) && <InterestCard family={family} accountId={a.id!} />}
      {!term && a.status !== 'BOOKED' && <ChequesCard account={a} canTransact={can(P.casaTransact)} />}
      {(!term || recurring) && a.status !== 'BOOKED' && modules.casa && <StandingInstructionsCard account={a} canGive={can(P.casaTransact)} />}
      {(!term || a.status !== 'BOOKED') && <MovementsCard family={family} accountId={a.id!} canReverse={can(P.casaTransact)} />}
      {!term && a.status !== 'BOOKED' && <ChargesCard account={a} product={product.data} canLevy={can(P.casaTransact)} />}
      <PaymentLinksCard family={family} accountId={a.id!} />
      <HoldsCard family={family} accountId={a.id!} canRelease={can(term ? P.depositHold : P.casaHold)} />
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
      {(action === 'IN' || action === 'OUT') && <TransactionDialog account={a} type={action === 'IN' ? 'CASH_DEPOSIT' : 'CASH_WITHDRAWAL'} onClose={() => setAction(null)} />}
      {action === 'TRANSFER' && <TransferDialog account={a} onClose={() => setAction(null)} />}
      {action === 'HOLD' && <HoldDialog family={family} account={a} onClose={() => setAction(null)} />}
      {action === 'FUND' && <FundingDialog account={a} casaEnabled={modules.casa} onClose={() => setAction(null)} />}
      {action === 'UNFUND' && <ReverseFundingDialog account={a} onClose={() => setAction(null)} />}
      {action === 'CLOSE' && (term ? <DepositCloseDialog account={a} isNbfc={isNbfc} onClose={() => setAction(null)} /> : <CasaCloseDialog account={a} onClose={() => setAction(null)} />)}
      {action === 'INSTRUCTION' && <InstructionDialog account={a} isNbfc={isNbfc} renewalAllowed={product.data?.autoRenewalAllowed === true} onClose={() => setAction(null)} />}
      {action === 'RENEW' && <RenewDialog account={a} isNbfc={isNbfc} onClose={() => setAction(null)} />}
      {action === 'PLEDGE' && <PledgeDialog account={a} onClose={() => setAction(null)} />}
      {action === 'STATEMENT' && <StatementDialog family={family} account={a} businessDate={day.data?.businessDate ?? me.businessDate ?? ''} onClose={() => setAction(null)} />}
      {action === 'LINK' && <PaymentLinkDialog family={family} account={a} onClose={() => setAction(null)} />}
    </div>
  );
}
