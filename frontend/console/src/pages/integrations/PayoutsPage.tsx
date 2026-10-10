import { useState } from 'react';
import { Link } from 'react-router';
import { useMe } from '../../api/hooks';
import { useBeneficiary, usePayout, usePayoutAction, usePayouts, useSetBeneficiary, useSimulatePayoutOutcome } from '../../api/integrationHooks';
import type { Payout } from '../../api/integrationTypes';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, Checkbox, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, Masked, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, humanize, useToast } from '../../ui';
import { StatusTimeline } from './common';

const STATUSES = ['INITIATED', 'ON_HOLD', 'SENT', 'SUCCESS', 'FAILED', 'RETURNED', 'CANCELLED'];

/** What became of the disbursement behind a failed or returned payout. */
export function ReversalNote({ payout }: { payout: Payout }) {
  if (!['FAILED', 'RETURNED'].includes(payout.status ?? '')) return null;
  if (payout.failureAction === 'REVERSED') return <Badge tone="ok">Disbursement reversed</Badge>;
  if (payout.failureAction === 'PROPOSED') {
    return (
      <span>
        <Badge tone="warn">Reversal awaiting approval</Badge>{' '}
        {payout.reversalApprovalId && <Link to={`/approvals?id=${encodeURIComponent(payout.reversalApprovalId)}`}>View request</Link>}
      </span>
    );
  }
  if (payout.failureAction === 'PARKED') return <Badge tone="warn">Parked: disbursement not reversed</Badge>;
  return <Badge>Reversal not decided yet</Badge>;
}

const retryLabel = (p: Payout): string | null =>
  p.status === 'ON_HOLD' ? 'Resume' : p.status === 'INITIATED' ? 'Send again' : ['FAILED', 'RETURNED'].includes(p.status ?? '') && p.failureAction === 'PARKED' ? 'New attempt' : null;

export function PayoutsPage() {
  const me = useMe().data!;
  const canAdmin = hasPermission(me.permissions, P.payoutAdmin);
  const canBeneficiary = hasPermission(me.permissions, P.payoutBeneficiary);
  const canSimulate = hasPermission(me.permissions, P.integrationSimulate);
  const simulate = useSimulatePayoutOutcome();
  const [status, setStatus] = useState('');
  const [needsAction, setNeedsAction] = useState(false);
  const q = usePayouts({ status, needsAction });
  const act = usePayoutAction();
  const toast = useToast();
  const [open, setOpen] = useState<string | null>(null);
  const [beneficiaryFor, setBeneficiaryFor] = useState<Payout | null>(null);
  return (
    <div className="stack">
      <PageHeader title="Payouts" subtitle="Disbursements paid out through the payout provider. A payout on hold is waiting for a beneficiary account; a failed one shows what happened to its disbursement." />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select label="Status" value={status} onChange={(e) => setStatus(e.target.value)} options={[{ value: '', label: 'All' }, ...STATUSES.map((s) => ({ value: s, label: humanize(s) }))]} />
          <Checkbox label="Only those needing action" checked={needsAction} onChange={(e) => setNeedsAction(e.target.checked)} />
        </div>
        <ErrorBanner error={q.error ?? act.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Payouts"
            captionHidden
            columns={[
              { key: 'ref', header: 'Payout', render: (p) => (<span><button type="button" className="link-button mono" onClick={() => setOpen(p.id!)} aria-label={`Open payout ${p.reference}`}>{p.reference}</button><br /><Link to={`/loans/${p.loanId}`} className="mono muted" style={{ fontSize: 12 }}>{p.loanNo}</Link></span>) },
              { key: 'amt', header: 'Amount', numeric: true, render: (p) => <MoneyText value={p.amount} /> },
              { key: 'ben', header: 'Beneficiary', render: (p) => (p.beneficiaryAccountMasked ? (<span><Masked value={p.beneficiaryAccountMasked} kind="account" /><br /><span className="muted mono" style={{ fontSize: 12 }}>{p.beneficiaryIfsc}</span></span>) : <span className="muted">None recorded</span>) },
              { key: 'status', header: 'Status', render: (p) => (<span><StatusBadge status={p.status} /> {p.needsAction && <Badge tone="warn">Needs action</Badge>}{p.failureReason && (<><br /><span className="muted" style={{ fontSize: 12 }}>{p.failureReason}</span></>)}</span>) },
              { key: 'utr', header: 'UTR', render: (p) => (p.utr ? <span className="mono">{p.utr}</span> : <span className="muted">—</span>) },
              { key: 'rev', header: 'Disbursement', render: (p) => <ReversalNote payout={p} /> },
              { key: 'at', header: 'Created', render: (p) => <DateTimeText value={p.createdAt} /> },
              {
                key: 'act',
                header: <span className="sr-only">Actions</span>,
                render: (p) => (
                  <span className="row" style={{ gap: 6 }}>
                    {canBeneficiary && ['ON_HOLD', 'INITIATED', 'FAILED', 'RETURNED'].includes(p.status ?? '') && (
                      <Button size="sm" aria-label={`Set beneficiary for ${p.loanNo}`} onClick={() => setBeneficiaryFor(p)}>Set beneficiary</Button>
                    )}
                    {canAdmin && retryLabel(p) && (
                      <Button size="sm" aria-label={`${retryLabel(p)} payout ${p.reference}`} loading={act.isPending && act.variables?.id === p.id} onClick={() => act.mutate({ id: p.id!, action: 'retry' }, { onSuccess: (r) => toast({ tone: 'success', message: `Payout ${r.reference} is ${humanize(r.status ?? '').toLowerCase()}.` }) })}>
                        {retryLabel(p)}
                      </Button>
                    )}
                    {canSimulate && ['INITIATED', 'SENT', 'SUCCESS'].includes(p.status ?? '') && (
                      <Button
                        size="sm"
                        aria-label={`${p.status === 'SUCCESS' ? 'Simulate return of' : 'Simulate failure of'} payout ${p.reference}`}
                        loading={simulate.isPending && simulate.variables?.id === p.id}
                        onClick={() => simulate.mutate({ id: p.id!, status: p.status === 'SUCCESS' ? 'RETURNED' : 'FAILED' }, { onSuccess: () => toast({ tone: 'info', message: `Payout ${p.reference} reported ${p.status === 'SUCCESS' ? 'returned' : 'failed'} (simulated).` }) })}
                      >
                        {p.status === 'SUCCESS' ? 'Simulate return' : 'Simulate failure'}
                      </Button>
                    )}
                    {canAdmin && p.status === 'SENT' && (
                      <Button size="sm" aria-label={`Ask the provider about ${p.reference}`} onClick={() => act.mutate({ id: p.id!, action: 'refresh' }, { onSuccess: (r) => toast({ tone: 'info', message: `Provider says ${r.reference} is ${humanize(r.status ?? '').toLowerCase()}.` }) })}>Refresh</Button>
                    )}
                  </span>
                ),
              },
            ]}
            rows={q.data ?? []}
            rowKey={(p) => p.id ?? ''}
            empty={<EmptyState title="No payouts match these filters" />}
          />
        )}
      </Card>
      {open && <PayoutDialog id={open} onClose={() => setOpen(null)} />}
      {beneficiaryFor && <BeneficiaryDialog loanId={beneficiaryFor.loanId!} loanNo={beneficiaryFor.loanNo ?? ''} onClose={() => setBeneficiaryFor(null)} />}
    </div>
  );
}

function PayoutDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const q = usePayout(id);
  const p = q.data;
  return (
    <Dialog open onClose={onClose} title={p ? `Payout ${p.reference}` : 'Payout'} footer={<Button onClick={onClose}>Close</Button>}>
      <div className="stack">
        <ErrorBanner error={q.error} />
        {q.isLoading && <Spinner />}
        {p && (
          <>
            <dl className="kv">
              <dt>Loan</dt>
              <dd><Link to={`/loans/${p.loanId}`} className="mono">{p.loanNo}</Link></dd>
              <dt>Amount</dt>
              <dd><MoneyText value={p.amount} /> {p.mode && <span className="muted">via {p.mode}</span>}</dd>
              <dt>Status</dt>
              <dd><StatusBadge status={p.status} /> <span className="muted">attempt {p.attemptNo}</span></dd>
              <dt>Beneficiary</dt>
              <dd>{p.beneficiaryAccountMasked ? (<><Masked value={p.beneficiaryAccountMasked} kind="account" /> <span className="mono muted">{p.beneficiaryIfsc}</span></>) : 'None recorded'}</dd>
              <dt>UTR</dt>
              <dd className="mono">{p.utr ?? '—'}</dd>
              <dt>Provider</dt>
              <dd>{p.provider ?? '—'} {p.providerRef && <span className="mono muted">{p.providerRef}</span>}</dd>
            </dl>
            {['FAILED', 'RETURNED'].includes(p.status ?? '') && (
              <Banner tone="warn">
                {humanize(p.status ?? '')}: {p.failureReason ?? 'no reason given'}{p.failureCode ? ` (${p.failureCode})` : ''}. <ReversalNote payout={p} />
              </Banner>
            )}
            {p.actionNote && <p className="muted" style={{ margin: 0 }}>{p.actionNote}</p>}
            <h3 style={{ margin: 0, fontSize: 14 }}>Status history</h3>
            <StatusTimeline events={p.events} />
          </>
        )}
      </div>
    </Dialog>
  );
}

export function BeneficiaryDialog({ loanId, loanNo, onClose }: { loanId: string; loanNo: string; onClose: () => void }) {
  const current = useBeneficiary(loanId);
  const save = useSetBeneficiary();
  const toast = useToast();
  const [f, setF] = useState({ holderName: '', accountNumber: '', confirm: '', ifsc: '' });
  const [touched, setTouched] = useState(false);
  const errs = {
    holderName: !f.holderName.trim() ? 'Required' : null,
    accountNumber: !/^[A-Za-z0-9]{6,35}$/.test(f.accountNumber) ? '6 to 35 letters or digits' : null,
    confirm: f.confirm !== f.accountNumber ? 'The two account numbers differ' : null,
    ifsc: !/^[A-Z]{4}0[A-Z0-9]{6}$/.test(f.ifsc) ? 'Like HDFC0001234' : null,
  };
  const err = (k: keyof typeof errs) => (touched ? errs[k] : null);
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Payout beneficiary for ${loanNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={save.isPending}
            onClick={() => {
              setTouched(true);
              if (Object.values(errs).some(Boolean)) return;
              const input = { holderName: f.holderName.trim(), accountNumber: f.accountNumber, ifsc: f.ifsc };
              // The full number is not kept after it is sent.
              setF({ ...f, accountNumber: '', confirm: '' });
              setTouched(false);
              save.mutate({ loanId, input }, { onSuccess: (b) => (toast({ tone: 'success', message: `Beneficiary ${b.accountMasked} recorded; the bank confirmed the account.` }), onClose()) });
            }}
          >
            Validate and save
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          {current.data ? (<>Current account <Masked value={current.data.accountMasked} kind="account" /> ({current.data.ifsc}). Saving replaces it.</>) : 'No beneficiary account is recorded for this loan.'} The bank validates the account before it is saved; the number is stored encrypted and shown masked.
        </p>
        <div className="form-grid">
          <Input label="Account holder" required maxLength={100} value={f.holderName} onChange={(e) => setF({ ...f, holderName: e.target.value })} error={err('holderName')} />
          <Input label="IFSC" required className="mono" value={f.ifsc} onChange={(e) => setF({ ...f, ifsc: e.target.value.toUpperCase().trim() })} error={err('ifsc')} />
          <Input label="Account number" required type="password" autoComplete="off" className="mono" value={f.accountNumber} onChange={(e) => setF({ ...f, accountNumber: e.target.value.trim() })} error={err('accountNumber')} />
          <Input label="Confirm account number" required autoComplete="off" className="mono" value={f.confirm} onChange={(e) => setF({ ...f, confirm: e.target.value.trim() })} error={err('confirm')} />
        </div>
        {save.isError && <p className="muted" style={{ margin: 0 }}>The account number was cleared from this form; type it again to retry.</p>}
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}
