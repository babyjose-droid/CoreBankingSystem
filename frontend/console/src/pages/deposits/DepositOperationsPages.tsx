import { useState } from 'react';
import { Link } from 'react-router';
import { useChequesInClearing, useDepositRegister, useDormancy, useNetOwnedFund, usePresentCheque, useProposeNetOwnedFund, useSettleCheque } from '../../api/depositHooks';
import { useMe } from '../../api/hooks';
import type { ChequeDeposit, ChequePresentmentOutcome, NetOwnedFund } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Banner, Button, Card, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, Textarea } from '../../ui';
import { NoDeposits, Problems, pct, useDepositModules } from './common';
import { useMoneyToast } from './DepositMoney';

/** P4-6 pages: the clearing simulator, inoperative and unclaimed accounts, and the register of deposits with the net owned fund. */

export function ClearingPage() {
  const modules = useDepositModules();
  const me = useMe().data!;
  const canTransact = hasPermission(me.permissions, P.casaTransact);
  const q = useChequesInClearing();
  const [settling, setSettling] = useState<ChequeDeposit | null>(null);
  if (!modules.casa) return <NoDeposits />;
  return (
    <div className="stack">
      <PageHeader
        title="Clearing"
        subtitle="A simulator of the clearing house. Inward: a cheque drawn on one of our accounts, presented by another bank, is paid or returned at once. Outward: a cheque paid in by our customer is credited once it is settled as cleared."
      />
      {canTransact && <InwardCard />}
      <Card title="Cheques paid in, in clearing" flush>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <Spinner />
        ) : (q.data ?? []).length === 0 ? (
          <EmptyState title="No cheque in clearing">A cheque paid into an account (its page, Cheques › Pay in a cheque) waits here until it is settled.</EmptyState>
        ) : (
          <Table
            caption="Cheques in clearing"
            captionHidden
            columns={[
              { key: 'acct', header: 'Account', render: (c) => <Link to={`/deposits/casa/${c.accountId}`} className="mono">{c.accountNo}</Link> },
              { key: 'no', header: 'Cheque', render: (c) => <span className="mono">{c.chequeNo}</span> },
              { key: 'drawer', header: 'Drawer', render: (c) => <span>{c.drawerName} <span className="muted mono">{c.draweeIfsc}</span></span> },
              { key: 'date', header: 'Dated', render: (c) => <DateText value={c.chequeDate} /> },
              { key: 'on', header: 'Paid in', render: (c) => <DateText value={c.lodgedOn} /> },
              { key: 'amt', header: 'Amount', numeric: true, render: (c) => <MoneyText value={c.amount} /> },
              {
                key: 'act',
                header: <span className="sr-only">Actions</span>,
                render: (c) => (canTransact ? <Button size="sm" aria-label={`Settle cheque ${c.chequeNo}`} onClick={() => setSettling(c)}>Settle</Button> : null),
              },
            ]}
            rows={q.data ?? []}
            rowKey={(c) => c.id!}
          />
        )}
      </Card>
      {settling && <SettleDialog cheque={settling} onClose={() => setSettling(null)} />}
    </div>
  );
}

function InwardCard() {
  const [accountNo, setAccountNo] = useState('');
  const [leafNo, setLeafNo] = useState('');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState('');
  const [payee, setPayee] = useState('');
  const [bank, setBank] = useState('');
  const [outcome, setOutcome] = useState<ChequePresentmentOutcome | null>(null);
  const m = usePresentCheque();
  const submit = () =>
    m.mutate({ accountNo: accountNo.trim(), leafNo: Number(leafNo), amount, chequeDate: date, payee, presentingBank: bank }, { onSuccess: (o) => setOutcome(o) });
  return (
    <Card title="Present a cheque (inward clearing)" actions={<Button variant="primary" size="sm" loading={m.isPending} onClick={submit}>Present</Button>}>
      <div className="form-grid">
        <Input label="Account number" required value={accountNo} onChange={(e) => setAccountNo(e.target.value.replace(/\D/g, ''))} />
        <Input label="Cheque number" required value={leafNo} maxLength={6} onChange={(e) => setLeafNo(e.target.value.replace(/\D/g, ''))} />
        <Input label="Amount" required numeric value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.]/g, ''))} />
        <Input label="Date on the cheque" required type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        <Input label="Payee" required maxLength={200} value={payee} onChange={(e) => setPayee(e.target.value)} />
        <Input label="Presenting bank" required maxLength={100} value={bank} onChange={(e) => setBank(e.target.value)} />
      </div>
      <Problems error={m.error} title="The presentment is not complete:" />
      {outcome && (
        <div data-testid="presentment-outcome" style={{ marginTop: 12 }}>
          {outcome.status === 'PAID' ? (
            <Banner tone="ok">Cheque {leafNo} paid: account {accountNo} debited <MoneyText value={amount} />.</Banner>
          ) : (
            <Banner tone="warn">Cheque {leafNo} returned unpaid: {outcome.returnReasonText}.</Banner>
          )}
        </div>
      )}
    </Card>
  );
}

function SettleDialog({ cheque, onClose }: { cheque: ChequeDeposit; onClose: () => void }) {
  const [outcome, setOutcome] = useState<'CLEARED' | 'RETURNED'>('CLEARED');
  const [reason, setReason] = useState('');
  const m = useSettleCheque();
  return (
    <Dialog open onClose={onClose} title={`Settle cheque ${cheque.chequeNo}`}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" loading={m.isPending} onClick={() => m.mutate({ id: cheque.id!, outcome, reason: outcome === 'RETURNED' ? reason.trim() : null }, { onSuccess: onClose })}>Settle</Button></>}>
      <p style={{ marginTop: 0 }}>
        <MoneyText value={cheque.amount} /> from {cheque.drawerName}, paid into <span className="mono">{cheque.accountNo}</span>.
      </p>
      <Select label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value as 'CLEARED')} options={[{ value: 'CLEARED', label: 'Cleared: credit the account' }, { value: 'RETURNED', label: 'Returned unpaid' }]} />
      {outcome === 'RETURNED' && <Textarea label="Why it was returned" required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />}
      <Problems error={m.error} title="The cheque cannot be settled:" />
    </Dialog>
  );
}

const STAGE_LABEL: Record<string, string> = { REVIEW: 'Due for review', INOPERATIVE: 'Inoperative', UNCLAIMED: 'Unclaimed' };

export function DormancyPage() {
  const modules = useDepositModules();
  const [stage, setStage] = useState('');
  const q = useDormancy(stage || null);
  if (!modules.casa) return <NoDeposits />;
  const d = q.data;
  return (
    <div className="stack">
      <PageHeader
        title="Inoperative and unclaimed"
        subtitle="Savings and current accounts with no customer transaction for a year are due for review; after two years the day-end makes them inoperative; after ten it transfers the balance to the unclaimed deposits head for the DEA Fund. Interest, tax and charges do not count as a customer transaction."
      />
      <Card>
        <Select label="Show" value={stage} onChange={(e) => setStage(e.target.value)}
          options={[{ value: '', label: 'All' }, ...Object.entries(STAGE_LABEL).map(([value, label]) => ({ value, label }))]} />
      </Card>
      <ErrorBanner error={q.error} />
      {q.isLoading ? (
        <Spinner />
      ) : d && !d.applies ? (
        <Banner tone="info">The bank rules on inoperative accounts and unclaimed deposits do not apply to this institution's rule set.</Banner>
      ) : (d?.accounts ?? []).length === 0 ? (
        <EmptyState title="No account to show">Every account has been operated within the last {d?.reviewMonths ?? 12} months.</EmptyState>
      ) : (
        <Card flush>
          <Table
            caption="Inoperative and unclaimed accounts"
            captionHidden
            columns={[
              { key: 'no', header: 'Account', render: (r) => <Link to={`/deposits/${r.family === 'CASA' ? 'casa' : 'term'}/${r.accountId}`} className="mono">{r.accountNo}</Link> },
              { key: 'who', header: 'Customer', render: (r) => <span>{r.customerName} <span className="muted mono">{r.customerNo}</span></span> },
              { key: 'last', header: 'Last operated', render: (r) => <DateText value={r.lastOperated} /> },
              { key: 'st', header: 'Stage', render: (r) => STAGE_LABEL[r.stage ?? ''] ?? r.stage },
              {
                key: 'next',
                header: 'Next',
                render: (r) =>
                  r.stage === 'UNCLAIMED' ? (
                    <span>Transferred <DateText value={r.unclaimedTransferredOn} /></span>
                  ) : r.stage === 'INOPERATIVE' ? (
                    <span>Since <DateText value={r.inoperativeSince} />; unclaimed from <DateText value={r.unclaimedOn} /></span>
                  ) : (
                    <span>Inoperative from <DateText value={r.inoperativeOn} /></span>
                  ),
              },
              { key: 'bal', header: 'Balance', numeric: true, render: (r) => <MoneyText value={r.stage === 'UNCLAIMED' ? r.unclaimedAmount : r.balance} /> },
            ]}
            rows={d?.accounts ?? []}
            rowKey={(r) => r.accountId!}
          />
        </Card>
      )}
    </div>
  );
}

export function DepositRegisterPage() {
  const modules = useDepositModules();
  const me = useMe().data!;
  const [branch, setBranch] = useState('');
  const q = useDepositRegister(branch.trim() || null);
  if (!modules.td) return <NoDeposits />;
  const r = q.data;
  return (
    <div className="stack">
      <PageHeader title="Register of deposits" subtitle="Every term and recurring deposit funded and not closed, with its depositor and terms." />
      {r?.netOwnedFund?.applies && <NetOwnedFundCard nof={r.netOwnedFund} canRecord={hasPermission(me.permissions, P.productPropose)} />}
      <Card>
        <div className="form-grid">
          <Input label="Branch" value={branch} onChange={(e) => setBranch(e.target.value.toUpperCase())} hint="Empty for every branch" />
        </div>
      </Card>
      <ErrorBanner error={q.error} />
      {q.isLoading ? (
        <Spinner />
      ) : (r?.deposits ?? []).length === 0 ? (
        <EmptyState title="No deposit on the register">A deposit is entered once it is funded.</EmptyState>
      ) : (
        <Card title={`${r!.count} deposit${r!.count === 1 ? '' : 's'}, total ${Number(r!.total).toLocaleString('en-IN')}`} flush>
          <Table
            caption="Register of deposits"
            captionHidden
            columns={[
              { key: 'no', header: 'Deposit', render: (x) => <Link to={`/deposits/term/${x.accountId}`} className="mono">{x.accountNo}</Link> },
              { key: 'who', header: 'Depositor', render: (x) => <span>{x.depositorName}{(x.holders ?? 1) > 1 && <span className="muted"> and {(x.holders ?? 1) - 1} more</span>} <span className="muted mono">{x.customerNo}</span></span> },
              { key: 'p', header: 'Principal', numeric: true, render: (x) => <MoneyText value={x.principal} /> },
              { key: 'rate', header: 'Rate', numeric: true, render: (x) => pct(x.ratePercent) },
              { key: 'from', header: 'Start', render: (x) => <DateText value={x.startDate} /> },
              { key: 'mat', header: 'Matures', render: (x) => <DateText value={x.maturityDate} /> },
              { key: 'bal', header: 'Balance', numeric: true, render: (x) => <MoneyText value={x.balance} /> },
              { key: 'st', header: 'Status', render: (x) => <StatusBadge status={x.status ?? ''} /> },
              { key: 'n', header: 'Nominee', render: (x) => (x.nominee ? 'Yes' : 'No') },
              { key: 'l', header: 'Lien', render: (x) => (x.lien ? 'Yes' : 'No') },
            ]}
            rows={r?.deposits ?? []}
            rowKey={(x) => x.accountId!}
          />
        </Card>
      )}
    </div>
  );
}

function NetOwnedFundCard({ nof, canRecord }: { nof: NetOwnedFund; canRecord: boolean }) {
  const [open, setOpen] = useState(false);
  const full = nof.headroom !== null && Number(nof.headroom) === 0;
  return (
    <Card title="Net owned fund and the cap on public deposits" actions={canRecord ? <Button size="sm" onClick={() => setOpen(true)}>Record the net owned fund</Button> : undefined}>
      {!nof.netOwnedFund ? (
        <Banner tone="danger">No net owned fund is recorded: no new deposit is accepted until one is.</Banner>
      ) : (
        <dl className="kv" data-testid="nof">
          <dt>Net owned fund</dt>
          <dd><MoneyText value={nof.netOwnedFund} /> <span className="muted">as of <DateText value={nof.history?.[0]?.asOf} /></span></dd>
          <dt>Cap ({nof.multiple} times)</dt>
          <dd><MoneyText value={nof.cap} /></dd>
          <dt>Public deposits</dt>
          <dd><MoneyText value={nof.outstanding} /> <span className="muted">({pct(nof.usedPercent)} of the cap)</span></dd>
          <dt>May still be accepted</dt>
          <dd><strong><MoneyText value={nof.headroom} /></strong></dd>
        </dl>
      )}
      {full && <Banner tone="warn">Public deposits are at the cap: a new deposit or instalment is refused.</Banner>}
      {open && <NofDialog onClose={() => setOpen(false)} />}
    </Card>
  );
}

function NofDialog({ onClose }: { onClose: () => void }) {
  const [amount, setAmount] = useState('');
  const [asOf, setAsOf] = useState('');
  const [reference, setReference] = useState('');
  const m = useProposeNetOwnedFund();
  const toast = useMoneyToast();
  const view = useNetOwnedFund();
  return (
    <Dialog open onClose={onClose} title="Record the net owned fund"
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" loading={m.isPending} onClick={() => m.mutate({ amount, asOf, reference: reference.trim() }, { onSuccess: (a) => (toast(a, 'Net owned fund'), onClose()) })}>Submit for approval</Button></>}>
      <p style={{ marginTop: 0 }} className="muted">From the latest audited balance sheet. A checker approves it; the latest balance sheet date governs.</p>
      <div className="form-grid">
        <Input label="Net owned fund" required numeric value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.]/g, ''))} />
        <Input label="Balance sheet date" required type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
        <Input label="Reference" required maxLength={200} value={reference} onChange={(e) => setReference(e.target.value)} hint="The audited balance sheet or the auditor's certificate" />
      </div>
      <Problems error={m.error} title="The net owned fund cannot be recorded:" />
      {(view.data?.history ?? []).length > 0 && (
        <Table
          caption="Recorded"
          columns={[
            { key: 'on', header: 'As of', render: (h) => <DateText value={h.asOf} /> },
            { key: 'amt', header: 'Amount', numeric: true, render: (h) => <MoneyText value={h.amount} /> },
            { key: 'ref', header: 'Reference', render: (h) => h.reference },
          ]}
          rows={view.data?.history ?? []}
          rowKey={(h) => h.asOf!}
        />
      )}
    </Dialog>
  );
}
