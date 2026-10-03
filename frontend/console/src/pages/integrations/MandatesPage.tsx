import { useState } from 'react';
import { Link } from 'react-router';
import { useMe } from '../../api/hooks';
import { useLoanPresentations, useMandate, useMandates, useRegisterMandate, useUpdateMandateStatus } from '../../api/integrationHooks';
import type { Mandate, MandateInput, MandateStatusUpdate } from '../../api/integrationTypes';
import { P, hasPermission } from '../../auth/permissions';
import { isMoney } from '../../lib/money';
import { Banner, Button, Card, DateText, Dialog, EmptyState, ErrorBanner, Input, Masked, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, humanize, useToast } from '../../ui';
import { LoanPicker, StatusTimeline } from './common';

const STATUSES = ['DRAFT', 'SUBMITTED', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'CANCELLED', 'EXPIRED'];
const NEXT: Record<string, MandateStatusUpdate['status'][]> = { DRAFT: ['SUBMITTED', 'CANCELLED'], SUBMITTED: ['ACTIVE', 'REJECTED', 'CANCELLED'], ACTIVE: ['SUSPENDED', 'CANCELLED', 'EXPIRED'], SUSPENDED: ['ACTIVE', 'CANCELLED', 'EXPIRED'] };
const FREQUENCIES: Array<NonNullable<MandateInput['frequency']>> = ['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'YEARLY', 'AS_PRESENTED'];
const ACCOUNT_TYPES: Array<{ value: NonNullable<MandateInput['accountType']>; label: string }> = [{ value: 'SB', label: 'Savings' }, { value: 'CA', label: 'Current' }, { value: 'CC', label: 'Cash credit' }, { value: 'OT', label: 'Other' }];

export function MandatesPage() {
  const me = useMe().data!;
  const canRegister = hasPermission(me.permissions, P.mandateRegister);
  const [status, setStatus] = useState('');
  const q = useMandates(status);
  const [open, setOpen] = useState<string | null>(null);
  const [registering, setRegistering] = useState(false);
  return (
    <div className="stack">
      <PageHeader title="Mandates" subtitle="e-NACH mandates that let instalments be collected from the borrower's bank account. Account numbers are stored encrypted and shown masked." actions={canRegister && <Button variant="primary" onClick={() => setRegistering(true)}>Register mandate</Button>} />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select label="Status" value={status} onChange={(e) => setStatus(e.target.value)} options={[{ value: '', label: 'All' }, ...STATUSES.map((s) => ({ value: s, label: humanize(s) }))]} />
        </div>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Mandates"
            captionHidden
            columns={[
              { key: 'ref', header: 'Mandate', render: (m) => (<span><span className="mono">{m.mandateRef}</span><br /><Link to={`/loans/${m.loanId}`} className="mono muted" style={{ fontSize: 12 }} onClick={(e) => e.stopPropagation()}>{m.loanNo}</Link></span>) },
              { key: 'acct', header: 'Debit account', render: (m) => (<span><Masked value={m.debitAccountMasked} kind="account" /><br /><span className="muted mono" style={{ fontSize: 12 }}>{m.ifsc}</span></span>) },
              { key: 'max', header: 'Up to', numeric: true, render: (m) => (<span><MoneyText value={m.maxAmount} /><br /><span className="muted" style={{ fontSize: 12 }}>{humanize(m.frequency ?? '')}</span></span>) },
              { key: 'umrn', header: 'UMRN', render: (m) => (m.umrn ? <span className="mono">{m.umrn}</span> : <span className="muted">Not issued</span>) },
              { key: 'status', header: 'Status', render: (m) => (<span><StatusBadge status={m.status} />{m.rejectReason && (<><br /><span className="muted" style={{ fontSize: 12 }}>{m.rejectReason}</span></>)}</span>) },
              { key: 'from', header: 'Valid', render: (m) => (<span><DateText value={m.startDate} /> – {m.endDate ? <DateText value={m.endDate} /> : 'until cancelled'}</span>) },
            ]}
            rows={q.data ?? []}
            rowKey={(m) => m.id ?? ''}
            onRowClick={(m) => setOpen(m.id!)}
            rowLabel={(m) => `Open mandate ${m.mandateRef}`}
            empty={<EmptyState title="No mandates match" />}
          />
        )}
      </Card>
      {open && <MandateDialog id={open} canAdmin={hasPermission(me.permissions, P.mandateAdmin)} onClose={() => setOpen(null)} />}
      {registering && <RegisterDialog businessDate={me.businessDate} onClose={() => setRegistering(false)} />}
    </div>
  );
}

function MandateDialog({ id, canAdmin, onClose }: { id: string; canAdmin: boolean; onClose: () => void }) {
  const q = useMandate(id);
  const m = q.data;
  const presentations = useLoanPresentations(m?.loanId);
  const update = useUpdateMandateStatus();
  const toast = useToast();
  const [u, setU] = useState<{ status: string; umrn: string; rejectCode: string; rejectReason: string }>({ status: '', umrn: '', rejectCode: '', rejectReason: '' });
  const [touched, setTouched] = useState(false);
  const next = NEXT[m?.status ?? ''] ?? [];
  const umrnError = u.status === 'ACTIVE' && !m?.umrn && !/^[A-Z0-9]{20}$/.test(u.umrn) ? '20 letters or digits' : null;
  return (
    <Dialog open onClose={onClose} wide title={m ? `Mandate ${m.mandateRef}` : 'Mandate'} footer={<Button onClick={onClose}>Close</Button>}>
      <div className="stack">
        <ErrorBanner error={q.error} />
        {q.isLoading && <Spinner />}
        {m && (
          <>
            <dl className="kv">
              <dt>Loan</dt>
              <dd><Link to={`/loans/${m.loanId}`} className="mono">{m.loanNo}</Link></dd>
              <dt>Status</dt>
              <dd><StatusBadge status={m.status} /></dd>
              <dt>Debit account</dt>
              <dd><Masked value={m.debitAccountMasked} kind="account" /> <span className="mono muted">{m.ifsc}</span> · {ACCOUNT_TYPES.find((t) => t.value === m.accountType)?.label ?? m.accountType}</dd>
              <dt>UMRN</dt>
              <dd className="mono">{m.umrn ?? '—'}</dd>
              <dt>Limit</dt>
              <dd><MoneyText value={m.maxAmount} /> · {humanize(m.frequency ?? '')}</dd>
              <dt>Sponsor bank / utility</dt>
              <dd className="mono">{m.sponsorBankCode ?? '—'} / {m.utilityCode ?? '—'}</dd>
            </dl>
            {m.status === 'REJECTED' && <Banner tone="danger">Rejected by the bank: {m.rejectReason ?? 'no reason given'}{m.rejectCode ? ` (${m.rejectCode})` : ''}</Banner>}
            {m.authenticationUrl && m.status === 'SUBMITTED' && <Banner tone="info">Waiting for the borrower to authorise the mandate with their bank.</Banner>}
            <h3 style={{ margin: 0, fontSize: 14 }}>Status history</h3>
            <StatusTimeline events={m.events} />
            <h3 style={{ margin: 0, fontSize: 14 }}>NACH presentations of the loan</h3>
            <ErrorBanner error={presentations.error} />
            <Table
              caption="NACH presentations of the loan"
              captionHidden
              columns={[
                { key: 'due', header: 'Due', render: (p) => <DateText value={p.dueDate} /> },
                { key: 'amt', header: 'Amount', numeric: true, render: (p) => <MoneyText value={p.amount} /> },
                { key: 'try', header: 'Attempt', numeric: true, render: (p) => p.attemptNo },
                { key: 'st', header: 'Outcome', render: (p) => (<span><StatusBadge status={p.status} />{p.returnReason && <span className="muted"> {p.returnCode} {p.returnReason}</span>}</span>) },
              ]}
              rows={presentations.data ?? []}
              rowKey={(p) => p.id ?? ''}
              empty={<EmptyState title="Never presented" />}
            />
            {canAdmin && next.length > 0 && (
              <fieldset className="fieldset">
                <legend>Set status by hand</legend>
                <p className="muted" style={{ marginTop: 0 }}>For a status reported outside the provider, such as a sponsor-bank report or a cancellation by the borrower.</p>
                <div className="form-grid">
                  <Select label="New status" value={u.status} placeholder="Select…" onChange={(e) => setU({ ...u, status: e.target.value })} options={next.map((s) => ({ value: s, label: humanize(s) }))} />
                  {u.status === 'ACTIVE' && !m.umrn && <Input label="UMRN" required className="mono" value={u.umrn} onChange={(e) => setU({ ...u, umrn: e.target.value.toUpperCase().trim() })} error={touched ? umrnError : null} />}
                  {u.status === 'REJECTED' && <Input label="Reject code" className="mono" value={u.rejectCode} onChange={(e) => setU({ ...u, rejectCode: e.target.value })} />}
                  {u.status === 'REJECTED' && <Input label="Reject reason" value={u.rejectReason} onChange={(e) => setU({ ...u, rejectReason: e.target.value })} />}
                </div>
                <Button
                  style={{ marginTop: 8 }}
                  disabled={!u.status}
                  loading={update.isPending}
                  onClick={() => {
                    setTouched(true);
                    if (umrnError) return;
                    update.mutate(
                      { id, input: { status: u.status as MandateStatusUpdate['status'], ...(u.status === 'ACTIVE' && u.umrn ? { umrn: u.umrn } : {}), ...(u.status === 'REJECTED' ? { rejectCode: u.rejectCode.trim() || undefined, rejectReason: u.rejectReason.trim() || undefined } : {}) } },
                      { onSuccess: (r) => (toast({ tone: 'success', message: `Mandate ${r.mandateRef} is now ${humanize(r.status ?? '').toLowerCase()}.` }), setU({ status: '', umrn: '', rejectCode: '', rejectReason: '' }), setTouched(false)) },
                    );
                  }}
                >
                  Set status
                </Button>
                <ErrorBanner error={update.error} />
              </fieldset>
            )}
          </>
        )}
      </div>
    </Dialog>
  );
}

function RegisterDialog({ businessDate, onClose }: { businessDate: string; onClose: () => void }) {
  const register = useRegisterMandate();
  const toast = useToast();
  const [loanId, setLoanId] = useState('');
  const [f, setF] = useState({ holderName: '', accountNumber: '', confirm: '', ifsc: '', accountType: 'SB' as NonNullable<MandateInput['accountType']>, maxAmount: '', frequency: 'MONTHLY' as NonNullable<MandateInput['frequency']>, startDate: businessDate, endDate: '', sponsorBankCode: '', utilityCode: '' });
  const [touched, setTouched] = useState(false);
  const errs = {
    loanId: !loanId ? 'Choose the loan' : null,
    holderName: !f.holderName.trim() ? 'Required' : null,
    accountNumber: !/^[A-Za-z0-9]{6,35}$/.test(f.accountNumber) ? '6 to 35 letters or digits' : null,
    confirm: f.confirm !== f.accountNumber ? 'The two account numbers differ' : null,
    ifsc: !/^[A-Z]{4}0[A-Z0-9]{6}$/.test(f.ifsc) ? 'Like HDFC0001234' : null,
    maxAmount: !isMoney(f.maxAmount) || Number(f.maxAmount) <= 0 ? 'Enter a positive amount' : null,
    startDate: !f.startDate ? 'Required' : null,
    endDate: f.endDate && f.endDate <= f.startDate ? 'After the start date' : null,
  };
  const err = (k: keyof typeof errs) => (touched ? errs[k] : null);
  return (
    <Dialog
      open
      onClose={onClose}
      wide
      title="Register mandate"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={register.isPending}
            onClick={() => {
              setTouched(true);
              if (Object.values(errs).some(Boolean)) return;
              const input: MandateInput = {
                holderName: f.holderName.trim(), accountNumber: f.accountNumber, ifsc: f.ifsc, accountType: f.accountType, maxAmount: f.maxAmount, frequency: f.frequency, startDate: f.startDate,
                ...(f.endDate ? { endDate: f.endDate } : {}), ...(f.sponsorBankCode.trim() ? { sponsorBankCode: f.sponsorBankCode.trim() } : {}), ...(f.utilityCode.trim() ? { utilityCode: f.utilityCode.trim() } : {}),
              };
              // The full number is not kept after it is sent.
              setF({ ...f, accountNumber: '', confirm: '' });
              setTouched(false);
              register.mutate({ loanId, input }, { onSuccess: (m) => (toast({ tone: 'success', message: `Mandate ${m.mandateRef} registered on account ${m.debitAccountMasked}; it is ${humanize(m.status ?? '').toLowerCase()}.` }), onClose()) });
            }}
          >
            Register mandate
          </Button>
        </>
      }
    >
      <div className="stack">
        <div className="form-grid">
          <LoanPicker value={loanId} onChange={setLoanId} required error={err('loanId')} />
          <Input label="Account holder" required maxLength={100} value={f.holderName} onChange={(e) => setF({ ...f, holderName: e.target.value })} error={err('holderName')} />
          <Input label="Account number" required type="password" autoComplete="off" className="mono" value={f.accountNumber} onChange={(e) => setF({ ...f, accountNumber: e.target.value.trim() })} hint="Shown masked once saved" error={err('accountNumber')} />
          <Input label="Confirm account number" required autoComplete="off" className="mono" value={f.confirm} onChange={(e) => setF({ ...f, confirm: e.target.value.trim() })} error={err('confirm')} />
          <Input label="IFSC" required className="mono" value={f.ifsc} onChange={(e) => setF({ ...f, ifsc: e.target.value.toUpperCase().trim() })} error={err('ifsc')} />
          <Select label="Account type" value={f.accountType} onChange={(e) => setF({ ...f, accountType: e.target.value as typeof f.accountType })} options={ACCOUNT_TYPES} />
          <Input label="Maximum amount per debit" required numeric value={f.maxAmount} onChange={(e) => setF({ ...f, maxAmount: e.target.value.replace(/[,\s₹]/g, '') })} error={err('maxAmount')} />
          <Select label="Frequency" value={f.frequency} onChange={(e) => setF({ ...f, frequency: e.target.value as typeof f.frequency })} options={FREQUENCIES.map((x) => ({ value: x, label: humanize(x) }))} />
          <Input label="Start date" required type="date" value={f.startDate} onChange={(e) => setF({ ...f, startDate: e.target.value })} error={err('startDate')} />
          <Input label="End date" type="date" value={f.endDate} onChange={(e) => setF({ ...f, endDate: e.target.value })} hint="Empty = until cancelled" error={err('endDate')} />
          <Input label="Sponsor bank code" className="mono" value={f.sponsorBankCode} onChange={(e) => setF({ ...f, sponsorBankCode: e.target.value })} hint="Empty = the tenant's default" />
          <Input label="Utility code" className="mono" value={f.utilityCode} onChange={(e) => setF({ ...f, utilityCode: e.target.value })} hint="Empty = the tenant's default" />
        </div>
        {register.isError && <p className="muted" style={{ margin: 0 }}>The account number was cleared from this form; type it again to retry.</p>}
        <ErrorBanner error={register.error} />
      </div>
    </Dialog>
  );
}

export type { Mandate };
