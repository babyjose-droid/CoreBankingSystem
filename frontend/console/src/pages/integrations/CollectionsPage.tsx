import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { useMe } from '../../api/hooks';
import { useCollectionOrders, useCreateCollectionOrder, useReconciliation, useResolvePayment, useUploadSettlements } from '../../api/integrationHooks';
import type { ReconCategory, ReconRow } from '../../api/integrationTypes';
import { P, hasPermission } from '../../auth/permissions';
import { isMoney } from '../../lib/money';
import { Badge, Banner, Button, Card, Checkbox, DateText, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, Tabs, Textarea, useToast, type Tone } from '../../ui';
import { LoanPicker } from './common';

const METHODS = ['UPI', 'CARD', 'NETBANKING'] as const;
export const RECON: Record<ReconCategory, { label: string; meaning: string; tone: Tone }> = {
  PAYMENT_NOT_POSTED: { label: 'Received, not posted', meaning: 'The gateway took the money but it is not on any loan yet.', tone: 'danger' },
  POSTED_NOT_SETTLED: { label: 'Posted, not settled', meaning: 'On the loan, but the gateway has not paid it into the bank account yet.', tone: 'warn' },
  SETTLED_NOT_RECEIVED: { label: 'Settled, not received', meaning: 'In the settlement report, but no payment was ever reported to us.', tone: 'danger' },
  AMOUNT_MISMATCH: { label: 'Amount mismatch', meaning: 'Settled for a different amount than was received.', tone: 'danger' },
  REFUND_DUE: { label: 'Refund due', meaning: 'Marked to be returned to the payer.', tone: 'warn' },
  MATCHED: { label: 'Matched', meaning: 'Received, posted and settled for the same amount.', tone: 'ok' },
};

export function CollectionsPage() {
  const [tab, setTab] = useState('links');
  return (
    <div className="stack">
      <PageHeader title="Collections" subtitle="Payment links for borrowers, and the reconciliation of what the gateway received against what is on the loans and what it settled." />
      <Tabs label="Collections" active={tab} onChange={setTab} tabs={[{ id: 'links', label: 'Payment links', content: <PaymentLinks /> }, { id: 'recon', label: 'Reconciliation', content: <Reconciliation /> }]} />
    </div>
  );
}

function PaymentLinks() {
  const me = useMe().data!;
  const canCreate = hasPermission(me.permissions, P.collectionCreate);
  const [loanId, setLoanId] = useState('');
  const orders = useCollectionOrders(loanId);
  const create = useCreateCollectionOrder();
  const toast = useToast();
  const [amount, setAmount] = useState('');
  const [methods, setMethods] = useState<string[]>(['UPI']);
  const [touched, setTouched] = useState(false);
  const amountError = !isMoney(amount) || Number(amount) <= 0 ? 'Enter a positive amount' : null;
  return (
    <div className="stack">
      <Card title="Loan">
        <div className="form-grid">
          <LoanPicker value={loanId} onChange={setLoanId} />
        </div>
      </Card>
      {loanId && canCreate && (
        <Card title="Create payment link">
          <div className="stack">
            <div className="form-grid">
              <Input label="Amount" required numeric value={amount} onChange={(e) => setAmount(e.target.value.replace(/[,\s₹]/g, ''))} error={touched ? amountError : null} />
              <fieldset className="fieldset">
                <legend>Payment methods</legend>
                {METHODS.map((m) => (
                  <Checkbox key={m} label={m === 'NETBANKING' ? 'Net banking' : m === 'CARD' ? 'Card' : 'UPI'} checked={methods.includes(m)} onChange={(e) => setMethods(e.target.checked ? [...methods, m] : methods.filter((x) => x !== m))} />
                ))}
              </fieldset>
            </div>
            <div>
              <Button
                variant="primary"
                loading={create.isPending}
                onClick={() => {
                  setTouched(true);
                  if (amountError) return;
                  create.mutate({ loanId, input: { amount, methods: methods as Array<(typeof METHODS)[number]> } }, { onSuccess: (o) => (toast({ tone: 'success', message: `Payment link ${o.reference} created.` }), setAmount(''), setTouched(false)) });
                }}
              >
                Create payment link
              </Button>
            </div>
            <ErrorBanner error={create.error} />
          </div>
        </Card>
      )}
      {loanId && (
        <Card title="Payment links of this loan" flush>
          <ErrorBanner error={orders.error} />
          {orders.isLoading ? (
            <div style={{ padding: 16 }}>
              <Spinner />
            </div>
          ) : (
            <Table
              caption="Payment links of this loan"
              captionHidden
              columns={[
                { key: 'ref', header: 'Reference', render: (o) => <span className="mono">{o.reference}</span> },
                { key: 'amt', header: 'Amount', numeric: true, render: (o) => <MoneyText value={o.amount} /> },
                { key: 'm', header: 'Methods', render: (o) => (o.methods?.length ? o.methods.join(', ') : 'Any') },
                { key: 'status', header: 'Status', render: (o) => <StatusBadge status={o.status} /> },
                { key: 'url', header: 'Link', render: (o) => (o.paymentUrl && o.status === 'CREATED' ? <CopyLink url={o.paymentUrl} reference={o.reference ?? ''} /> : <span className="muted">—</span>) },
                { key: 'exp', header: 'Expires', render: (o) => <DateTimeText value={o.expiresAt} /> },
                { key: 'by', header: 'Created', render: (o) => (<span><DateTimeText value={o.createdAt} /> <span className="muted mono">{o.createdBy}</span></span>) },
              ]}
              rows={orders.data ?? []}
              rowKey={(o) => o.id ?? ''}
              empty={<EmptyState title="No payment links for this loan" />}
            />
          )}
        </Card>
      )}
    </div>
  );
}

function CopyLink({ url, reference }: { url: string; reference: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <span className="row" style={{ gap: 6 }}>
      <span className="mono" style={{ fontSize: 12, wordBreak: 'break-all' }}>{url}</span>
      <Button size="sm" aria-label={`Copy link ${reference}`} onClick={() => void navigator.clipboard?.writeText(url).then(() => setCopied(true), () => setCopied(false))}>
        {copied ? 'Copied' : 'Copy'}
      </Button>
    </span>
  );
}

function Reconciliation() {
  const me = useMe().data!;
  const canAdmin = hasPermission(me.permissions, P.collectionAdmin);
  const [category, setCategory] = useState<ReconCategory | ''>('');
  const q = useReconciliation(category);
  const [resolving, setResolving] = useState<ReconRow | null>(null);
  const rows = q.data ?? [];
  const ours = rows.filter((r) => r.category === 'PAYMENT_NOT_POSTED' || r.category === 'POSTED_NOT_SETTLED' || r.category === 'REFUND_DUE').length;
  const theirs = rows.filter((r) => r.category === 'SETTLED_NOT_RECEIVED' || r.category === 'AMOUNT_MISMATCH').length;
  return (
    <div className="stack">
      {category === '' && !q.isLoading && (
        <Banner tone={rows.length ? 'warn' : 'ok'}>
          {rows.length ? (<>{rows.length} unmatched: <strong>{ours}</strong> received by us and not fully through (not posted, not settled, or to refund), <strong>{theirs}</strong> in the gateway's settlement that do not agree with what we received.</>) : 'Everything the gateway received is posted and settled.'}
        </Banner>
      )}
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select label="Show" value={category} onChange={(e) => setCategory(e.target.value as ReconCategory | '')} options={[{ value: '', label: 'Everything unmatched' }, ...(Object.keys(RECON) as ReconCategory[]).map((c) => ({ value: c, label: RECON[c].label }))]} />
        </div>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Gateway reconciliation"
            captionHidden
            columns={[
              { key: 'cat', header: 'Finding', render: (r) => (<span><Badge tone={RECON[r.category!]?.tone}>{RECON[r.category!]?.label ?? r.category}</Badge><br /><span className="muted" style={{ fontSize: 12 }}>{RECON[r.category!]?.meaning}</span></span>) },
              { key: 'id', header: 'Gateway payment', render: (r) => (<span><span className="mono">{r.providerPaymentId}</span>{r.lastError && (<><br /><span className="muted" style={{ fontSize: 12 }}>{r.lastError}</span></>)}</span>) },
              { key: 'loan', header: 'Loan', render: (r) => (r.loanId ? <Link to={`/loans/${r.loanId}`} className="mono">{r.loanNo}</Link> : <span className="muted">None</span>) },
              { key: 'rcv', header: 'Received', numeric: true, render: (r) => (r.paymentAmount ? (<span><MoneyText value={r.paymentAmount} /><br /><span className="muted" style={{ fontSize: 12 }}><DateTimeText value={r.paidAt} /></span></span>) : <span className="muted">Nothing received</span>) },
              { key: 'set', header: 'Settled', numeric: true, render: (r) => (r.settledAmount ? (<span><MoneyText value={r.settledAmount} /><br /><span className="muted" style={{ fontSize: 12 }}><DateText value={r.settledOn} /> · {r.settlementUtr}</span></span>) : <span className="muted">Not settled</span>) },
              { key: 'note', header: 'Note', render: (r) => r.review ?? '' },
              ...(canAdmin
                ? [{
                    key: 'act',
                    header: <span className="sr-only">Actions</span>,
                    render: (r: ReconRow) => (r.category === 'PAYMENT_NOT_POSTED' && r.paymentStatus === 'UNMATCHED' && r.paymentId ? <Button size="sm" aria-label={`Resolve ${r.providerPaymentId}`} onClick={() => setResolving(r)}>Resolve</Button> : null),
                  }]
                : []),
            ]}
            rows={rows}
            rowKey={(r) => `${r.provider}-${r.providerPaymentId}`}
            empty={<EmptyState title="Nothing to reconcile here" />}
          />
        )}
      </Card>
      {canAdmin && <SettlementUpload />}
      {resolving && <ResolveDialog row={resolving} onClose={() => setResolving(null)} />}
    </div>
  );
}

function SettlementUpload() {
  const upload = useUploadSettlements();
  const [fileRef, setFileRef] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const [touched, setTouched] = useState(false);
  const refError = !/^[A-Za-z0-9._-]{1,60}$/.test(fileRef) ? "1 to 60 letters, digits, '.', '_' or '-'" : null;
  return (
    <Card title="Load a settlement report">
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          CSV with the columns <span className="mono">provider_payment_id, amount, settled_on</span> and optionally <span className="mono">fee, utr</span>. Rows already loaded are skipped.
        </p>
        <div className="form-grid">
          <Input label="File reference" required className="mono" value={fileRef} onChange={(e) => setFileRef(e.target.value.trim())} hint="As named by the gateway, e.g. settlement-2026-06-30" error={touched ? refError : null} />
          <div className="field">
            <label className="field__label" htmlFor="settlement-file">Settlement file (CSV)</label>
            <input
              id="settlement-file"
              ref={input}
              type="file"
              accept=".csv,text/csv"
              onChange={async (e) => {
                const file = e.target.files?.[0];
                setTouched(true);
                if (!file || refError) {
                  e.target.value = '';
                  return;
                }
                const csv = await file.text();
                upload.mutate({ fileRef, csv }, { onSettled: () => input.current && (input.current.value = '') });
              }}
            />
          </div>
        </div>
        <ErrorBanner error={upload.error} />
        {upload.data && (
          <Banner tone="ok">
            {upload.data.fileRef}: {upload.data.rows} rows, {upload.data.added} added, {upload.data.alreadyLoaded} already loaded.
          </Banner>
        )}
      </div>
    </Card>
  );
}

function ResolveDialog({ row, onClose }: { row: ReconRow; onClose: () => void }) {
  const resolve = useResolvePayment();
  const toast = useToast();
  const [how, setHow] = useState<'loan' | 'refund'>('loan');
  const [loanId, setLoanId] = useState('');
  const [note, setNote] = useState('');
  const [touched, setTouched] = useState(false);
  const errs = { loanId: how === 'loan' && !loanId ? 'Choose the loan' : null, note: !note.trim() ? 'Required' : null };
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Resolve ${row.providerPaymentId}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={resolve.isPending}
            onClick={() => {
              setTouched(true);
              if (errs.loanId || errs.note) return;
              resolve.mutate(
                { id: row.paymentId!, input: how === 'refund' ? { refund: true, note: note.trim() } : { loanId, note: note.trim() } },
                { onSuccess: () => (toast({ tone: 'success', message: how === 'refund' ? 'Payment marked for refund.' : 'Payment posted to the loan.' }), onClose()) },
              );
            }}
          >
            {how === 'refund' ? 'Mark for refund' : 'Post to loan'}
          </Button>
        </>
      }
    >
      <div className="stack">
        <p style={{ margin: 0 }}>
          The gateway received <MoneyText value={row.paymentAmount} /> that could not be matched to a loan{row.lastError ? ` (${row.lastError})` : ''}.
        </p>
        <Select label="What to do" value={how} onChange={(e) => setHow(e.target.value as 'loan' | 'refund')} options={[{ value: 'loan', label: 'Post it to a loan' }, { value: 'refund', label: 'Mark it for refund' }]} />
        {how === 'loan' && <LoanPicker value={loanId} onChange={setLoanId} required error={touched ? errs.loanId : null} />}
        <Textarea label="Note" required value={note} onChange={(e) => setNote(e.target.value)} error={touched ? errs.note : null} />
        <ErrorBanner error={resolve.error} />
      </div>
    </Dialog>
  );
}
