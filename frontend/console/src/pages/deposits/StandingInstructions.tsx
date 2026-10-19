import { useState } from 'react';
import { Link } from 'react-router';
import {
  useCancelStandingInstruction,
  useDepositAccounts,
  useLoanPledges,
  usePledgeQuote,
  useProposePledge,
  useProposeStandingInstruction,
  useStandingInstruction,
  useStandingInstructions,
} from '../../api/depositHooks';
import { useLoans } from '../../api/lendingHooks';
import type { DepositAccount, StandingInstruction, StandingInstructionInput } from '../../api/types';
import { Badge, Banner, Button, Card, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, Select, Spinner, Table, Textarea } from '../../ui';
import { PERIOD_LABEL, Problems } from './common';
import { useMoneyToast } from './DepositMoney';

/**
 * Standing instructions (P4-5b) on a savings or current account, and pledges of term deposits for loans. A standing
 * instruction is given and cancelled through a checker; the job STANDING_INSTRUCTIONS carries it out.
 */

export const SI_KIND: Record<string, string> = { TRANSFER: 'Transfer', LOAN_INSTALMENT: 'Loan instalments', RD_INSTALMENT: 'Recurring deposit instalments' };
const STATUS_TONE: Record<string, 'ok' | 'neutral' | 'warn'> = { ACTIVE: 'ok', CANCELLED: 'neutral', ENDED: 'neutral' };

function what(s: StandingInstruction) {
  if (s.kind === 'TRANSFER') return <span><MoneyText value={s.amount} /> to <span className="mono">{s.toAccountNo}</span>, {(PERIOD_LABEL[s.everyMonths ?? 1] ?? '').toLowerCase()}</span>;
  if (s.kind === 'LOAN_INSTALMENT') return <span>Each instalment of loan <span className="mono">{s.loanNo}</span> on its due date</span>;
  return <span>Each instalment of recurring deposit <span className="mono">{s.toAccountNo}</span></span>;
}

export function StandingInstructionsCard({ account, canGive }: { account: DepositAccount; canGive: boolean }) {
  const q = useStandingInstructions({ accountId: account.id });
  const [giving, setGiving] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const casa = account.family === 'CASA';
  const rows = q.data ?? [];
  if (!casa && !q.isLoading && rows.length === 0) return null;
  return (
    <Card title="Standing instructions" flush actions={casa && canGive && account.status === 'ACTIVE' ? <Button size="sm" onClick={() => setGiving(true)}>New standing instruction</Button> : undefined}>
      <ErrorBanner error={q.error} />
      {q.isLoading ? (
        <Spinner />
      ) : rows.length === 0 ? (
        <EmptyState title="No standing instruction">A standing instruction pays, on its own, a fixed amount to another account every month or quarter, a loan's instalments or a recurring deposit's.</EmptyState>
      ) : (
        <Table
          caption="Standing instructions"
          captionHidden
          columns={[
            { key: 'no', header: 'No.', render: (s) => <span className="mono">{s.instructionNo}</span> },
            { key: 'what', header: 'Pays', render: (s) => (s.fromAccountId === account.id ? what(s) : <span>From <span className="mono">{s.fromAccountNo}</span>: {what(s)}</span>) },
            { key: 'from', header: 'From', render: (s) => <span><DateText value={s.startOn} />{s.endOn && <span className="muted"> to <DateText value={s.endOn} /></span>}</span> },
            { key: 'paid', header: 'Paid', numeric: true, render: (s) => s.paidCount },
            { key: 'last', header: 'Last paid for', render: (s) => (s.lastPaidFor ? <DateText value={s.lastPaidFor} /> : '—') },
            { key: 'st', header: 'Status', render: (s) => <Badge tone={STATUS_TONE[s.status ?? 'ACTIVE']}>{s.status === 'ACTIVE' ? 'Active' : s.status === 'CANCELLED' ? 'Cancelled' : 'Ended'}</Badge> },
            { key: 'act', header: <span className="sr-only">Actions</span>, render: (s) => <Button size="sm" aria-label={`View ${s.instructionNo}`} onClick={() => setOpen(s.id!)}>View</Button> },
          ]}
          rows={rows}
          rowKey={(s) => s.id!}
        />
      )}
      {giving && <NewStandingInstructionDialog account={account} onClose={() => setGiving(false)} />}
      {open && <StandingInstructionDialog id={open} canCancel={canGive} onClose={() => setOpen(null)} />}
    </Card>
  );
}

export function NewStandingInstructionDialog({ account, onClose }: { account: DepositAccount; onClose: () => void }) {
  const holder = account.holders?.[0];
  const [kind, setKind] = useState('TRANSFER');
  const [to, setTo] = useState('');
  const [loanId, setLoanId] = useState('');
  const [amount, setAmount] = useState('');
  const [every, setEvery] = useState('1');
  const [startOn, setStartOn] = useState('');
  const [endOn, setEndOn] = useState('');
  const [narration, setNarration] = useState('');
  const casa = useDepositAccounts('CASA', { status: 'ACTIVE' }, kind === 'TRANSFER');
  const rds = useDepositAccounts('TERM_DEPOSIT', { customerId: holder?.customerId, status: 'ACTIVE' }, kind === 'RD_INSTALMENT' && !!holder?.customerId);
  const loans = useLoans({ q: holder?.customerNo ?? '', page: 0, size: 50 });
  const m = useProposeStandingInstruction();
  const toast = useMoneyToast();
  const body = (): StandingInstructionInput => ({
    fromAccountId: account.id!, kind: kind as 'TRANSFER', startOn: startOn || null, endOn: endOn || null, narration: narration.trim() || null,
    ...(kind === 'TRANSFER' ? { toAccountId: to || null, amount: amount || null, everyMonths: Number(every) as 1 } : {}),
    ...(kind === 'LOAN_INSTALMENT' ? { loanId: loanId || null } : {}),
    ...(kind === 'RD_INSTALMENT' ? { toAccountId: to || null } : {}),
  });
  const recurring = (rds.data ?? []).filter((d) => d.productType === 'RD');
  const borrowerLoans = (loans.data ?? []).filter((l) => l.customerNo === holder?.customerNo && (l.status === 'ACTIVE' || l.status === 'SANCTIONED'));
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={`New standing instruction on ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} onClick={() => m.mutate(body(), { onSuccess: (a) => (toast(a, 'Standing instruction'), onClose()) })}>Submit for approval</Button>
        </>
      }
    >
      <p style={{ marginTop: 0 }} className="muted">
        The instruction is carried out by the system on each date, from this account's money; a payment that cannot be made is tried again for three days and recorded with the reason.
      </p>
      <div className="form-grid">
        <Select label="Pays" value={kind} onChange={(e) => (setKind(e.target.value), setTo(''))} options={Object.entries(SI_KIND).map(([value, label]) => ({ value, label }))} />
        {kind === 'TRANSFER' && (
          <>
            <Select label="To account" required placeholder="Choose an account" value={to} onChange={(e) => setTo(e.target.value)}
              options={(casa.data ?? []).filter((x) => x.id !== account.id).map((x) => ({ value: x.id!, label: `${x.accountNo} — ${x.customerName}` }))} />
            <Input label="Amount" required numeric value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.]/g, ''))} />
            <Select label="Every" value={every} onChange={(e) => setEvery(e.target.value)} options={[1, 3, 6, 12].map((x) => ({ value: String(x), label: PERIOD_LABEL[x] }))} />
          </>
        )}
        {kind === 'LOAN_INSTALMENT' && (
          <Select label="Loan" required placeholder={loans.isLoading ? 'Loading…' : borrowerLoans.length ? 'Choose a loan of the holder' : 'The holder has no open loan'} value={loanId}
            onChange={(e) => setLoanId(e.target.value)} options={borrowerLoans.map((l) => ({ value: l.id!, label: `${l.loanNo} — ${l.productCode}, ${l.status}` }))} />
        )}
        {kind === 'RD_INSTALMENT' && (
          <Select label="Recurring deposit" required placeholder={recurring.length ? 'Choose a recurring deposit' : 'The holder has no active recurring deposit'} value={to}
            onChange={(e) => setTo(e.target.value)} options={recurring.map((d) => ({ value: d.id!, label: `${d.accountNo} — ${d.principal} a month` }))} />
        )}
        <Input label="Starts on" type="date" value={startOn} onChange={(e) => setStartOn(e.target.value)} hint="Empty for today" />
        <Input label="Ends on" type="date" value={endOn} onChange={(e) => setEndOn(e.target.value)} hint="Empty for no end" />
        <Input label="Narration" maxLength={200} value={narration} onChange={(e) => setNarration(e.target.value)} />
      </div>
      <Problems error={m.error} title="The standing instruction cannot be given:" />
    </Dialog>
  );
}

export function StandingInstructionDialog({ id, canCancel, onClose }: { id: string; canCancel: boolean; onClose: () => void }) {
  const q = useStandingInstruction(id);
  const cancel = useCancelStandingInstruction(id);
  const [reason, setReason] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const toast = useMoneyToast();
  const s = q.data;
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={s ? `Standing instruction ${s.instructionNo}` : 'Standing instruction'}
      footer={
        <>
          <Button onClick={onClose}>Close</Button>
          {s?.status === 'ACTIVE' && canCancel && !cancelling && <Button variant="danger" onClick={() => setCancelling(true)}>Cancel the instruction</Button>}
          {cancelling && (
            <Button variant="primary" loading={cancel.isPending} onClick={() => cancel.mutate(reason, { onSuccess: (a) => (toast(a, 'Cancellation'), onClose()) })}>Submit for approval</Button>
          )}
        </>
      }
    >
      <ErrorBanner error={q.error} />
      {!s ? (
        <Spinner />
      ) : (
        <div className="stack">
          <dl className="kv">
            <dt>Pays</dt>
            <dd data-testid="si-what">{SI_KIND[s.kind ?? '']}: {what(s)}</dd>
            <dt>From account</dt>
            <dd className="mono">{s.fromAccountNo}</dd>
            <dt>Runs</dt>
            <dd><DateText value={s.startOn} />{s.endOn ? <span> to <DateText value={s.endOn} /></span> : ', no end'}</dd>
            <dt>Status</dt>
            <dd>{s.status}{s.closeReason && <span className="muted"> · {s.closeReason}</span>}</dd>
            <dt>Given by</dt>
            <dd>{s.createdBy}</dd>
          </dl>
          {(s.runs ?? []).length === 0 ? (
            <p className="muted" style={{ margin: 0 }}>Nothing has been paid or attempted yet.</p>
          ) : (
            <Table
              caption="Payments and attempts"
              columns={[
                { key: 'for', header: 'Due', render: (r) => <DateText value={r.forDate} /> },
                { key: 'on', header: 'On', render: (r) => <DateText value={r.businessDate} /> },
                { key: 'st', header: 'Result', render: (r) => (r.status === 'DONE' ? <Badge tone="ok">Paid</Badge> : <Badge tone="warn">{`Not paid${(r.attempts ?? 1) > 1 ? ` (${r.attempts} tries)` : ''}`}</Badge>) },
                { key: 'amt', header: 'Amount', numeric: true, render: (r) => (r.amount ? <MoneyText value={r.amount} /> : '—') },
                { key: 'why', header: 'Reason', render: (r) => r.reason ?? '' },
              ]}
              rows={s.runs ?? []}
              rowKey={(r) => `${r.forDate}-${r.businessDate}-${r.status}`}
            />
          )}
          {cancelling && <Textarea label="Reason for cancelling" required value={reason} onChange={(e) => setReason(e.target.value)} />}
          <Problems error={cancel.error} title="The instruction cannot be cancelled:" />
        </div>
      )}
    </Dialog>
  );
}

export function PledgeDialog({ account, onClose }: { account: DepositAccount; onClose: () => void }) {
  const holder = account.holders?.[0];
  const loans = useLoans({ q: holder?.customerNo ?? '', page: 0, size: 50 });
  const [loanId, setLoanId] = useState('');
  const [reason, setReason] = useState('');
  const q = usePledgeQuote(account.id, loanId || undefined);
  const m = useProposePledge(account.id!);
  const toast = useMoneyToast();
  const open = (loans.data ?? []).filter((l) => l.customerNo === holder?.customerNo && (l.status === 'ACTIVE' || l.status === 'SANCTIONED'));
  const quote = q.data;
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={`Pledge deposit ${account.accountNo} for a loan`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!loanId} loading={m.isPending} onClick={() => m.mutate({ loanId, reason }, { onSuccess: (a) => (toast(a, 'Pledge'), onClose()) })}>Submit for approval</Button>
        </>
      }
    >
      <p style={{ marginTop: 0 }} className="muted">The deposit is held under a lien naming the loan until the loan is closed; it cannot be closed or paid out meanwhile.</p>
      <div className="form-grid">
        <Select label="Loan" required placeholder={loans.isLoading ? 'Loading…' : open.length ? 'Choose a loan of the depositor' : 'The depositor has no sanctioned or active loan'}
          value={loanId} onChange={(e) => setLoanId(e.target.value)} options={open.map((l) => ({ value: l.id!, label: `${l.loanNo} — ${l.productCode}, ${l.status}` }))} />
        <Input label="Reason" required value={reason} onChange={(e) => setReason(e.target.value)} hint="The sanction letter or loan agreement" />
      </div>
      <ErrorBanner error={q.error} />
      {quote && (
        <dl className="kv" data-testid="pledge-quote">
          <dt>Loan</dt>
          <dd><MoneyText value={quote.sanctioned} /> at {quote.loanRatePercent}%, to <DateText value={quote.loanMaturity} /></dd>
          <dt>Lien on this deposit</dt>
          <dd><MoneyText value={quote.lien} /></dd>
          <dt>Most that may be lent</dt>
          <dd data-testid="pledge-max"><MoneyText value={quote.maxLoan} /> ({quote.maxPercent}% of the deposits pledged)</dd>
          <dt>Lowest loan rate</dt>
          <dd>{quote.minRatePercent}% (the deposit rate plus {quote.spreadPercent} points){(quote.lockInMonths ?? 0) > 0 ? `; none within ${quote.lockInMonths} months of a deposit` : ''}</dd>
        </dl>
      )}
      {quote && (quote.problems ?? []).length === 0 && <Banner tone="ok">The deposits pledged cover the loan.</Banner>}
      {quote && (quote.problems ?? []).length > 0 && (
        <Banner tone="danger">
          <div>
            <strong>The deposit cannot be pledged for this loan:</strong>
            <ul style={{ margin: '4px 0 0', paddingLeft: 18 }} aria-label="Problems">{(quote.problems ?? []).map((p) => <li key={p}>{p}</li>)}</ul>
          </div>
        </Banner>
      )}
      <Problems error={m.error} title="The deposit cannot be pledged:" />
    </Dialog>
  );
}

/** On the loan page: the deposits pledged for it. */
export function LoanPledgesCard({ loanId }: { loanId: string }) {
  const q = useLoanPledges(loanId);
  const rows = q.data ?? [];
  if (q.isLoading || q.error || rows.length === 0) return null;
  return (
    <Card title="Deposits pledged" flush>
      <Table
        caption="Deposits pledged for this loan"
        captionHidden
        columns={[
          { key: 'no', header: 'Deposit', render: (p) => <Link to={`/deposits/term/${p.depositId}`} className="mono">{p.depositNo}</Link> },
          { key: 'amt', header: 'Lien', numeric: true, render: (p) => <MoneyText value={p.amount} /> },
          { key: 'rate', header: 'Deposit rate', render: (p) => `${p.depositRatePercent}%` },
          { key: 'mat', header: 'Matures', render: (p) => <DateText value={p.depositMaturity} /> },
          { key: 'st', header: 'Pledge', render: (p) => (p.status === 'ACTIVE' ? <Badge tone="warn">In force</Badge> : <span>Released <DateText value={p.releasedOn} /></span>) },
        ]}
        rows={rows}
        rowKey={(p) => p.holdId!}
      />
    </Card>
  );
}
