import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useBusinessDay, useMe } from '../../api/hooks';
import { useLoan, useLoanAmendments, useLoanKfs, useLoanSchedule, useLoanTransactions } from '../../api/lendingHooks';
import type { Loan, LoanCharge, LoanDemand, LoanTxn } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { addDays } from '../../lib/dates';
import { isZero, subtractMoney, addMoney } from '../../lib/money';
import { Badge, Banner, Button, Card, DateText, DateTimeText, EmptyState, ErrorBanner, MoneyText, PageHeader, Spinner, StatusBadge, Table, Tabs, humanize } from '../../ui';
import { AssetClassBadge, Stat, pct } from './common';
import { KfsView, ScheduleTable } from './KfsView';
import { LoanDocumentsTab, LoanPartiesTab } from './LoanDocuments';
import { LoanActionDialog, type LoanAction } from './loanActions';
import { amendmentKindLabel } from './restructuring';

const REVERSIBLE = new Set(['REPAYMENT', 'PREPAYMENT', 'FEE_CHARGE', 'WAIVER', 'PRECLOSURE', 'CANCELLATION', 'AMENDMENT']);

export function LoanDetailPage() {
  const { id } = useParams();
  const me = useMe().data!;
  const day = useBusinessDay();
  const businessDate = day.data?.businessDate ?? me.businessDate;
  const q = useLoan(id);
  const kfs = useLoanKfs(id);
  const [tab, setTab] = useState('schedule');
  const [action, setAction] = useState<LoanAction | null>(null);
  const can = (p: string) => hasPermission(me.permissions, p);

  if (q.isLoading) return <Spinner />;
  if (q.error || !q.data) return <ErrorBanner error={q.error ?? new Error('Loan not found')} />;
  const loan = q.data;
  const status = loan.status;
  const active = status === 'ACTIVE';
  const coolingOffEnd = loan.disbursedOn && kfs.data?.coolingOffDays !== undefined ? addDays(loan.disbursedOn, kfs.data.coolingOffDays) : null;
  const inCoolingOff = active && !!coolingOffEnd && businessDate <= coolingOffEnd;

  const actions: Array<{ key: LoanAction['kind']; label: string; show: boolean; disabled?: string; variant?: 'primary' | 'danger' }> = [
    { key: 'disburse', label: 'Disburse', show: can(P.loanDisburse) && status === 'SANCTIONED', disabled: loan.kfsAcceptedAt ? undefined : 'Record the borrower’s KFS acceptance first', variant: 'primary' },
    { key: 'repay', label: 'Repayment', show: can(P.loanRepay) && active, variant: 'primary' },
    { key: 'prepay', label: 'Part-prepayment', show: can(P.loanRepay) && active },
    { key: 'preclose', label: 'Pre-closure', show: can(P.loanRepay) && active },
    { key: 'cancel', label: 'Cancel (cooling-off)', show: can(P.loanRepay) && inCoolingOff },
    { key: 'charge', label: 'Charge fee', show: can(P.loanRepay) && active },
    { key: 'amend', label: 'Amend', show: can(P.loanAmend) && active && loan.repaymentMethod === 'EQUATED' },
    { key: 'restructure', label: 'Restructure', show: can(P.loanRestructure) && active && loan.repaymentMethod === 'EQUATED', variant: 'danger' },
    { key: 'freeze', label: 'Freeze', show: can(P.loanAdmin) && active, variant: 'danger' },
    { key: 'unfreeze', label: 'Unfreeze', show: can(P.loanAdmin) && status === 'FROZEN' },
  ];
  const visible = actions.filter((a) => a.show);

  return (
    <div className="stack">
      <PageHeader
        title={`Loan ${loan.loanNo}`}
        subtitle={
          <span>
            {loan.customerId ? <Link to={`/customers/${loan.customerId}`}>{loan.customerName}</Link> : loan.customerName} <span className="mono">{loan.customerNo}</span> · product{' '}
            <Link to={`/loan-products/${encodeURIComponent(loan.productCode ?? '')}`} className="mono">
              {loan.productCode} v{loan.productVersion}
            </Link>{' '}
            · branch {loan.branch}
            {loan.externalRef && (
              <>
                {' '}
                · LOS <span className="mono">{loan.externalRef}</span>
              </>
            )}
          </span>
        }
        actions={
          <>
            <StatusBadge status={status} />
            <AssetClassBadge value={loan.assetClass} />
            <Link to="/loans">All loans</Link>
          </>
        }
      />
      {status === 'FROZEN' && <Banner tone="warn">This loan is frozen: repayments and other transactions are blocked until it is unfrozen. Interest keeps accruing.</Banner>}
      {loan.restructuredOn && (
        <Banner tone="warn">
          <span>
            Restructured on <DateText value={loan.restructuredOn} />
            {(loan.restructureCount ?? 1) > 1 ? ` (${loan.restructureCount} times)` : ''}. Under monitoring: the account cannot be upgraded before{' '}
            <DateText value={loan.upgradeNotBefore} />.
          </span>
        </Banner>
      )}
      {status === 'SANCTIONED' && !loan.kfsAcceptedAt && <Banner tone="info">Sanctioned. The borrower has not accepted the Key Fact Statement yet; record it on the KFS tab before disbursement.</Banner>}
      {visible.length > 0 && (
        <div className="action-bar" role="toolbar" aria-label="Loan actions">
          {visible.map((a) => (
            <Button key={a.key} variant={a.variant} disabled={!!a.disabled} title={a.disabled} onClick={() => setAction({ kind: a.key } as LoanAction)}>
              {a.label}
            </Button>
          ))}
        </div>
      )}
      <LoanSummaryStats loan={loan} coolingOffEnd={inCoolingOff ? coolingOffEnd : null} />
      <Tabs
        label="Loan details"
        active={tab}
        onChange={setTab}
        tabs={[
          { id: 'schedule', label: 'Schedule', content: <ScheduleTab loan={loan} canWaive={can(P.loanWaive)} onWaive={(charge) => setAction({ kind: 'waive', charge })} /> },
          { id: 'transactions', label: 'Transactions', content: <TransactionsTab loan={loan} canReverse={can(P.loanReverse)} onReverse={(txn) => setAction({ kind: 'reverse', txn })} /> },
          { id: 'amendments', label: 'Amendments', content: <AmendmentsTab loan={loan} /> },
          { id: 'parties', label: 'Parties', content: <LoanPartiesTab loan={loan} /> },
          { id: 'documents', label: 'Documents', content: <LoanDocumentsTab loan={loan} businessDate={businessDate} /> },
          {
            id: 'kfs',
            label: 'KFS',
            content: (
              <div className="stack">
                <Card title="Borrower acceptance">
                  {loan.kfsAcceptedAt ? (
                    <p style={{ margin: 0 }}>
                      Accepted <DateTimeText value={loan.kfsAcceptedAt} />.
                    </p>
                  ) : (
                    <div className="row" style={{ alignItems: 'center' }}>
                      <span>Not accepted yet.</span>
                      {status === 'SANCTIONED' && can(P.loanCreate) && (
                        <Button variant="primary" onClick={() => setAction({ kind: 'kfs' })}>
                          Record acceptance
                        </Button>
                      )}
                    </div>
                  )}
                </Card>
                <ErrorBanner error={kfs.error} />
                {kfs.isLoading ? <Spinner /> : kfs.data && <KfsView kfs={kfs.data} title="Key Fact Statement (as generated at booking)" />}
              </div>
            ),
          },
        ]}
      />
      {action && <LoanActionDialog action={action} loan={loan} businessDate={businessDate} onClose={() => setAction(null)} />}
    </div>
  );
}

function LoanSummaryStats({ loan, coolingOffEnd }: { loan: Loan; coolingOffEnd: string | null }) {
  const closed = loan.status === 'CLOSED' || loan.status === 'CANCELLED';
  return (
    <div className="stat-grid" data-testid="loan-summary">
      <Stat label="Sanctioned amount">
        <MoneyText value={loan.amount} />
      </Stat>
      <Stat label="Principal outstanding" testId="stat-outstanding">
        <MoneyText value={loan.principalOutstanding} />
      </Stat>
      <Stat label="Overdue" testId="stat-overdue">
        <MoneyText value={loan.overdueAmount} />
      </Stat>
      <Stat label="DPD" testId="stat-dpd">
        {loan.dpd ?? 0}
      </Stat>
      <Stat label="Asset class">
        <AssetClassBadge value={loan.assetClass} />
        {loan.npaSince && (
          <div className="muted" style={{ fontSize: 12, fontWeight: 400 }}>
            NPA since <DateText value={loan.npaSince} />
          </div>
        )}
      </Stat>
      <Stat label="EMI">{loan.emi ? <MoneyText value={loan.emi} /> : <span className="muted">—</span>}</Stat>
      <Stat label="Rate / APR" testId="stat-rate">
        {pct(loan.currentRate ?? loan.rate)} / {loan.apr ? pct(loan.apr) : '—'}
        {loan.currentRate && loan.rate && Number(loan.currentRate) !== Number(loan.rate) && (
          <div className="muted" style={{ fontSize: 12, fontWeight: 400 }}>
            sanctioned at {pct(loan.rate)}
          </div>
        )}
      </Stat>
      <Stat label="Tenor">
        {loan.tenorMonths} months <span className="muted" style={{ fontSize: 12, fontWeight: 400 }}>{humanize(loan.repaymentMethod ?? '')}</span>
      </Stat>
      <Stat label={closed ? 'Closed on' : 'Next due'}>
        <DateText value={closed ? loan.closedOn : loan.nextDueDate} />
      </Stat>
      <Stat label="Disbursed">
        {loan.disbursedOn ? (
          <>
            <DateText value={loan.disbursedOn} />
            <div className="muted" style={{ fontSize: 12, fontWeight: 400 }}>
              net <MoneyText value={loan.netDisbursed} />
            </div>
          </>
        ) : (
          <span className="muted">Not yet</span>
        )}
      </Stat>
      <Stat label="Provision held">
        <MoneyText value={loan.provisionHeld} />
      </Stat>
      {coolingOffEnd && (
        <Stat label="Cooling-off until">
          <DateText value={coolingOffEnd} />
        </Stat>
      )}
    </div>
  );
}

function demandState(d: LoanDemand): { label: string; tone: 'ok' | 'warn' | 'danger' | 'info' } {
  const due = addMoney(d.principalDue ?? '0', d.interestDue ?? '0');
  const paid = addMoney(d.principalPaid ?? '0', d.interestPaid ?? '0');
  const moved = addMoney(d.principalRescheduled ?? '0', d.interestCapitalised ?? '0');
  if (isZero(subtractMoney(due, paid))) return isZero(moved) ? { label: 'Paid', tone: 'ok' } : { label: 'Rescheduled', tone: 'info' };
  if (isZero(paid)) return { label: 'Unpaid', tone: 'danger' };
  return { label: 'Part paid', tone: 'warn' };
}

function ScheduleTab({ loan, canWaive, onWaive }: { loan: Loan; canWaive: boolean; onWaive: (c: LoanCharge) => void }) {
  const q = useLoanSchedule(loan.id);
  if (q.isLoading) return <Spinner />;
  if (q.error) return <ErrorBanner error={q.error} />;
  const s = q.data!;
  const demands = s.demands ?? [];
  const charges = s.charges ?? [];
  return (
    <div className="stack">
      <div className="row">
        <span>
          Interest accrued since the last demand: <MoneyText value={s.accruedInterest} />
        </span>
        <span>
          Advance held: <MoneyText value={s.advance} />
        </span>
      </div>
      <Card title={`Demands raised (${demands.length})`} flush headingLevel={3}>
        <Table
          caption="Demands raised"
          captionHidden
          columns={[
            { key: 'no', header: '#', numeric: true, render: (d) => d.instalmentNo },
            { key: 'due', header: 'Due date', render: (d) => <DateText value={d.dueDate} /> },
            { key: 'pd', header: 'Principal due', numeric: true, render: (d) => <MoneyText value={d.principalDue} /> },
            { key: 'id', header: 'Interest due', numeric: true, render: (d) => <MoneyText value={d.interestDue} /> },
            { key: 'pp', header: 'Principal paid', numeric: true, render: (d) => <MoneyText value={d.principalPaid} /> },
            { key: 'ip', header: 'Interest paid', numeric: true, render: (d) => <MoneyText value={d.interestPaid} /> },
            { key: 'st', header: 'Status', render: (d) => { const st = demandState(d); return <Badge tone={st.tone}>{st.label}</Badge>; } },
          ]}
          rows={demands}
          rowKey={(d) => String(d.instalmentNo)}
          empty={<EmptyState title="No demands raised yet" />}
        />
      </Card>
      <Card title={`Charges (${charges.length})`} flush headingLevel={3}>
        <Table
          caption="Charges"
          captionHidden
          columns={[
            { key: 'date', header: 'Date', render: (c) => <DateText value={c.date} /> },
            { key: 'name', header: 'Charge', render: (c) => (<span>{c.name} <span className="mono muted">{c.code}</span></span>) },
            { key: 'kind', header: 'Kind', render: (c) => humanize(c.kind ?? '') },
            { key: 'amount', header: 'Amount', numeric: true, render: (c) => <MoneyText value={c.amount} /> },
            { key: 'paid', header: 'Paid', numeric: true, render: (c) => <MoneyText value={c.paid} /> },
            { key: 'waived', header: 'Waived', numeric: true, render: (c) => <MoneyText value={c.waived} /> },
            { key: 'unpaid', header: 'Unpaid', numeric: true, render: (c) => <MoneyText value={c.unpaid} /> },
            ...(canWaive
              ? [{
                  key: 'act',
                  header: <span className="sr-only">Actions</span>,
                  render: (c: LoanCharge) =>
                    c.unpaid && !isZero(c.unpaid) ? (
                      <Button size="sm" aria-label={`Waive ${c.name}`} onClick={() => onWaive(c)}>
                        Waive…
                      </Button>
                    ) : null,
                }]
              : []),
          ]}
          rows={charges}
          rowKey={(c) => c.id ?? `${c.code}-${c.date}`}
          empty={<EmptyState title="No charges" />}
        />
      </Card>
      <Card title={`Future schedule (${(s.future ?? []).length})`} flush headingLevel={3}>
        <ScheduleTable rows={s.future ?? []} caption="Future schedule" />
      </Card>
    </div>
  );
}

function TransactionsTab({ loan, canReverse, onReverse }: { loan: Loan; canReverse: boolean; onReverse: (t: LoanTxn) => void }) {
  const q = useLoanTransactions(loan.id);
  if (q.isLoading) return <Spinner />;
  if (q.error) return <ErrorBanner error={q.error} />;
  const rows = q.data ?? [];
  return (
    <Card flush>
      <Table
        caption="Loan transactions"
        captionHidden
        columns={[
          { key: 'seq', header: '#', numeric: true, render: (t) => t.seq },
          { key: 'type', header: 'Type', render: (t) => humanize(t.type ?? '') },
          { key: 'value', header: 'Value date', render: (t) => <DateText value={t.valueDate} /> },
          { key: 'amount', header: 'Amount', numeric: true, render: (t) => <MoneyText value={t.amount} /> },
          { key: 'summary', header: 'Details', render: (t) => t.summary },
          { key: 'by', header: 'By', render: (t) => (<span><span className="mono">{t.createdBy}</span><br /><span className="muted" style={{ fontSize: 12 }}><DateTimeText value={t.createdAt} /></span></span>) },
          { key: 'state', header: 'Status', render: (t) => (t.reversedBy ? <Badge>Reversed</Badge> : t.reverses ? <Badge tone="info">Reversal</Badge> : null) },
          ...(canReverse
            ? [{
                key: 'act',
                header: <span className="sr-only">Actions</span>,
                render: (t: LoanTxn) =>
                  REVERSIBLE.has(t.type ?? '') && !t.reversedBy && loan.status !== 'CLOSED' && loan.status !== 'CANCELLED' ? (
                    <Button size="sm" variant="ghost" aria-label={`Reverse transaction ${t.seq}`} onClick={() => onReverse(t)}>
                      Reverse…
                    </Button>
                  ) : null,
              }]
            : []),
        ]}
        rows={rows}
        rowKey={(t) => t.id ?? String(t.seq)}
        empty={<EmptyState title="No transactions yet" />}
      />
    </Card>
  );
}

function AmendmentsTab({ loan }: { loan: Loan }) {
  const q = useLoanAmendments(loan.id);
  if (q.isLoading) return <Spinner />;
  if (q.error) return <ErrorBanner error={q.error} />;
  const arrow = (a: unknown, b: unknown) => (String(a ?? '') === String(b ?? '') ? String(b ?? '—') : `${String(a ?? '—')} → ${String(b ?? '—')}`);
  return (
    <Card flush>
      <Table
        caption="Amendment and restructure history"
        captionHidden
        columns={[
          { key: 'seq', header: '#', numeric: true, render: (a) => a.seq },
          { key: 'kind', header: 'Change', render: (a) => (a.kind === 'RESTRUCTURE' ? <Badge tone="danger">Restructure</Badge> : amendmentKindLabel(a.kind)) },
          { key: 'date', header: 'Business date', render: (a) => <DateText value={a.businessDate} /> },
          { key: 'rate', header: 'Rate %', render: (a) => arrow(a.rateBefore, a.rateAfter) },
          { key: 'emi', header: 'EMI', render: (a) => arrow(a.emiBefore, a.emiAfter) },
          { key: 'tenure', header: 'Instalments left', render: (a) => arrow(a.tenureBefore, a.tenureAfter) },
          { key: 'maturity', header: 'Maturity', render: (a) => (<span><DateText value={a.maturityBefore} /> → <DateText value={a.maturityAfter} /></span>) },
          { key: 'interest', header: 'Interest to come', render: (a) => (<span><MoneyText value={a.interestBefore} /> → <MoneyText value={a.interestAfter} /></span>) },
          { key: 'who', header: 'Maker / checker', render: (a) => <span className="mono">{a.madeBy} / {a.checkedBy}</span> },
          { key: 'reason', header: 'Reason', render: (a) => a.reason ?? '' },
          {
            key: 'flags',
            header: <span className="sr-only">Flags</span>,
            render: (a) => (
              <span className="row" style={{ gap: 4 }}>
                {a.differsFromProposal && <Badge tone="warn" title="The loan changed after the proposal; the figures at approval were applied">Differs from proposal</Badge>}
                {a.reversedBy && <Badge>Reversed</Badge>}
              </span>
            ),
          },
        ]}
        rows={q.data ?? []}
        rowKey={(a) => a.id ?? String(a.seq)}
        empty={<EmptyState title="No amendments or restructures" />}
      />
    </Card>
  );
}
