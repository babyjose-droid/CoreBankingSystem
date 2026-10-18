import { useState } from 'react';
import {
  useCasaCharges,
  useCasaClosureQuote,
  useCloseCasa,
  useCloseDeposit,
  useDepositAccounts,
  useDepositClosureQuote,
  useMaturityInstruction,
  useMinimumBalanceMonths,
  useProposeCasaCharge,
  useRenewDeposit,
} from '../../api/depositHooks';
import type { DepositAccount, DepositClosure, DepositProduct } from '../../api/types';
import { Banner, Button, Card, DateText, Dialog, Input, MoneyText, Select, Spinner, StatusBadge, Table, Textarea, humanize } from '../../ui';
import { Problems, pct } from './common';
import { useMoneyToast } from './DepositMoney';

/**
 * The life of a deposit account after opening (P4-4): charges with their GST invoices and the minimum balance months
 * (savings and current), closing an account or a deposit, the maturity instruction and renewing a matured deposit.
 * Every request goes to a checker; the figures come from the backend's quote, so what the maker sees is what is posted.
 */

export const INSTRUCTION_LABEL: Record<string, string> = {
  PAY_OUT: 'Pay out',
  RENEW_PRINCIPAL: 'Renew the principal, pay out the interest',
  RENEW_WITH_INTEREST: 'Renew the principal with the interest',
};
const REASON_LABEL: Record<string, string> = {
  DEPOSITOR_REQUEST: 'The depositor asks for it',
  DEATH: 'Death of the depositor',
  EMERGENCY_EXPENSE: 'An emergency expense (individual)',
  CRITICAL_ILLNESS: 'Critical illness (individual)',
  MATURITY: 'Maturity',
};
const PAY_LABEL: Record<string, string> = {
  ACCOUNT: 'To a savings or current account',
  CASH: 'In cash',
  BANK_TRANSFER: 'By bank transfer (recorded with its UTR)',
  GATEWAY: 'Through the payment gateway, to the bank account recorded',
  RENEWAL: 'Renewed',
};

// ---------- charges ----------

export function ChargesCard({ account, product, canLevy }: { account: DepositAccount; product: DepositProduct | undefined; canLevy: boolean }) {
  const charges = useCasaCharges(account.id);
  const months = useMinimumBalanceMonths(account.id);
  const [open, setOpen] = useState(false);
  const s = product?.charges;
  const rows = charges.data ?? [];
  const short = (months.data ?? []).filter((m) => Number(m.shortfall) > 0);
  return (
    <Card
      title="Charges"
      flush
      actions={canLevy && account.status === 'ACTIVE' && (s?.service ?? []).length > 0 ? <Button onClick={() => setOpen(true)}>Levy a charge</Button> : undefined}
    >
      <div style={{ padding: '0 16px' }}>
        <p className="muted" data-testid="charge-schedule">
          {!s
            ? 'The product has no schedule of charges.'
            : [
                s.minBalancePercent ? `Minimum balance: ${s.minBalancePercent}% of the month's shortfall, at most ${s.minBalanceMax}` : 'No minimum balance charge',
                s.closureAmount ? `closure ${s.closureAmount} (free within ${s.closureFreeWithinDays} days of opening${s.closureWithinDays ? `, none after ${s.closureWithinDays} days` : ''})` : 'no closure charge',
                `GST ${s.gstRatePercent}% on top`,
              ].join('; ') + '.'}
        </p>
      </div>
      {charges.isLoading ? (
        <Spinner />
      ) : rows.length === 0 ? (
        <p className="muted" style={{ padding: '0 16px 12px' }}>No charge has been levied.</p>
      ) : (
        <Table
          caption="Charges levied"
          captionHidden
          columns={[
            { key: 'd', header: 'Date', render: (c) => <DateText value={c.businessDate} /> },
            { key: 'what', header: 'Charge', render: (c) => <span>{c.description} <span className="muted mono">{c.code}</span></span> },
            { key: 'tv', header: 'Taxable', numeric: true, render: (c) => <MoneyText value={c.taxableValue} /> },
            { key: 'gst', header: 'GST', numeric: true, render: (c) => <MoneyText value={String(Number(c.cgst) + Number(c.sgst) + Number(c.igst))} /> },
            { key: 'tot', header: 'Total', numeric: true, render: (c) => <strong><MoneyText value={c.total} /></strong> },
            { key: 'inv', header: 'Invoice', render: (c) => <span className="mono">{c.invoiceNo}{c.creditNoteNo ? ` · credit note ${c.creditNoteNo}` : ''}</span> },
            { key: 'st', header: 'Status', render: (c) => <StatusBadge status={c.status ?? ''} /> },
          ]}
          rows={rows}
          rowKey={(c) => c.id ?? ''}
        />
      )}
      {short.length > 0 && (
        <div style={{ padding: '8px 16px 12px' }}>
          <strong>Months below the minimum balance</strong>
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }} aria-label="Minimum balance months">
            {short.map((m) => (
              <li key={m.month}>
                <DateText value={m.from} /> to <DateText value={m.to} />: average <MoneyText value={m.average} />, required <MoneyText value={m.required} />, short <MoneyText value={m.shortfall} />
                {m.charged ? <span> — charged <MoneyText value={m.charged} /> ({m.chargeInvoiceNo})</span> : m.notified ? ' — the depositor was told' : ''}
                {m.note && <span className="muted"> · {m.note}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {open && <ChargeDialog account={account} product={product} onClose={() => setOpen(false)} />}
    </Card>
  );
}

function ChargeDialog({ account, product, onClose }: { account: DepositAccount; product: DepositProduct | undefined; onClose: () => void }) {
  const service = product?.charges?.service ?? [];
  const [code, setCode] = useState(service[0]?.code ?? '');
  const [reason, setReason] = useState('');
  const m = useProposeCasaCharge(account.id!);
  const toast = useMoneyToast();
  const chosen = service.find((x) => x.code === code);
  const gst = product?.charges?.gstRatePercent ?? '18';
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Levy a charge on ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} disabled={!code || !reason.trim()} onClick={() => m.mutate({ code, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Charge'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      <Select label="Charge" value={code} onChange={(e) => setCode(e.target.value)} options={service.map((x) => ({ value: x.code!, label: `${x.name} (${x.amount})` }))} />
      {chosen && (
        <p className="muted" data-testid="charge-total">
          {chosen.amount} plus GST at {gst}%: {(Number(chosen.amount) * (1 + Number(gst) / 100)).toFixed(2)} in all, with a GST invoice.
        </p>
      )}
      <Textarea label="Reason" required rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problems error={m.error} title="The charge cannot be levied:" />
    </Dialog>
  );
}

// ---------- where the proceeds go ----------

interface PayTo {
  mode: string;
  accountId: string;
  reference: string;
}

function PayToFields({ account, value, onChange, modes }: { account: DepositAccount; value: PayTo; onChange: (p: PayTo) => void; modes: string[] }) {
  const depositor = account.holders?.[0]?.customerId;
  const accounts = useDepositAccounts('CASA', { customerId: depositor, status: 'ACTIVE' }, modes.includes('ACCOUNT') && !!depositor);
  const options = (accounts.data ?? []).filter((x) => x.id !== account.id).map((x) => ({ value: x.id!, label: `${x.accountNo} (${x.productCode})` }));
  return (
    <div className="form-grid">
      <Select label="Pay the proceeds" value={value.mode} onChange={(e) => onChange({ ...value, mode: e.target.value })} options={modes.map((m) => ({ value: m, label: PAY_LABEL[m] }))} />
      {value.mode === 'ACCOUNT' && (
        <Select label="To account" required placeholder={accounts.isLoading ? 'Loading…' : 'Choose an account of the depositor'} value={value.accountId}
          onChange={(e) => onChange({ ...value, accountId: e.target.value })} options={options} />
      )}
      {(value.mode === 'CASH' || value.mode === 'BANK_TRANSFER') && (
        <Input label={value.mode === 'BANK_TRANSFER' ? 'UTR (the bank’s reference)' : 'Slip or voucher number'} required={value.mode === 'BANK_TRANSFER'} maxLength={60}
          value={value.reference} onChange={(e) => onChange({ ...value, reference: e.target.value })} />
      )}
    </div>
  );
}
const payBody = (p: PayTo) => ({ mode: p.mode as 'CASH', accountId: p.mode === 'ACCOUNT' ? p.accountId || null : null, reference: p.reference.trim() || null });

// ---------- closing a savings or current account ----------

export function CasaCloseDialog({ account, onClose }: { account: DepositAccount; onClose: () => void }) {
  const q = useCasaClosureQuote(account.id);
  const [pay, setPay] = useState<PayTo>({ mode: 'ACCOUNT', accountId: '', reference: '' });
  const [reason, setReason] = useState('');
  const m = useCloseCasa(account.id!);
  const toast = useMoneyToast();
  const quote = q.data;
  const nothing = quote && Number(quote.payable) <= 0;
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={`Close account ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" loading={m.isPending} disabled={!quote || (quote.problems ?? []).length > 0 || !reason.trim()}
            onClick={() => m.mutate({ payTo: payBody(pay), reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Closure'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      {q.isLoading || !quote ? (
        <Spinner />
      ) : (
        <>
          <dl className="kv" data-testid="casa-quote">
            <dt>Balance</dt>
            <dd><MoneyText value={quote.balance} /></dd>
            <dt>Interest to yesterday</dt>
            <dd><MoneyText value={quote.interest} /></dd>
            <dt>Closure charge</dt>
            <dd>{quote.closureCharge ? <span><MoneyText value={quote.closureCharge.total} /> <span className="muted">({quote.closureCharge.taxableValue} plus GST {quote.closureCharge.gst})</span></span> : 'None'}</dd>
            <dt>Paid to the depositor</dt>
            <dd><strong><MoneyText value={quote.payable} /></strong></dd>
          </dl>
          {(quote.problems ?? []).length > 0 && (
            <Banner tone="warn">
              <div>
                <strong>The account cannot be closed yet:</strong>
                <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>{(quote.problems ?? []).map((p) => <li key={p}>{p}</li>)}</ul>
              </div>
            </Banner>
          )}
          {!nothing && <PayToFields account={account} value={pay} onChange={setPay} modes={['ACCOUNT', 'CASH', 'BANK_TRANSFER']} />}
          <Textarea label="Reason" required rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </>
      )}
      <Problems error={m.error ?? q.error} title="The account cannot be closed:" />
    </Dialog>
  );
}

// ---------- closing a term deposit ----------

export function DepositCloseDialog({ account, isNbfc, onClose }: { account: DepositAccount; isNbfc: boolean; onClose: () => void }) {
  const t = account.termDeposit!;
  const [reason, setReason] = useState('DEPOSITOR_REQUEST');
  const q = useDepositClosureQuote(account.id, reason);
  const toBank = t.payoutTo === 'BANK_ACCOUNT' && !!t.payoutBankIfsc;
  const [pay, setPay] = useState<PayTo>({ mode: t.payoutTo === 'CASA' ? 'ACCOUNT' : toBank ? 'GATEWAY' : 'CASH', accountId: t.payoutAccountId ?? '', reference: '' });
  const [note, setNote] = useState('');
  const m = useCloseDeposit(account.id!);
  const toast = useMoneyToast();
  const quote = q.data;
  const premature = quote?.kind === 'PREMATURE';
  const reasons = isNbfc ? ['DEPOSITOR_REQUEST', 'DEATH', 'EMERGENCY_EXPENSE', 'CRITICAL_ILLNESS'] : ['DEPOSITOR_REQUEST', 'DEATH'];
  const needsNote = premature && reason !== 'DEPOSITOR_REQUEST';
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={quote?.kind === 'MATURITY' ? `Pay deposit ${account.accountNo} at maturity` : `Close deposit ${account.accountNo} before maturity`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" loading={m.isPending} disabled={!quote || (quote.problems ?? []).length > 0 || (needsNote && !note.trim())}
            onClick={() => m.mutate({ reason: premature ? (reason as 'DEATH') : null, payTo: payBody(pay), note: note.trim() || null }, { onSuccess: (a) => (toast(a, 'Closure'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      {quote?.kind !== 'MATURITY' && (
        <Select label="Why it is closed early" value={reason} onChange={(e) => setReason(e.target.value)} options={reasons.map((r) => ({ value: r, label: REASON_LABEL[r] }))} />
      )}
      {q.isLoading || !quote ? (
        <Spinner />
      ) : (
        <>
          <dl className="kv" data-testid="deposit-quote">
            <dt>Principal</dt>
            <dd><MoneyText value={quote.principal} /></dd>
            <dt>Held now</dt>
            <dd><MoneyText value={quote.balance} /></dd>
            <dt>Rate allowed</dt>
            <dd>{quote.ratePercent == null ? '—' : pct(quote.ratePercent)} <span className="muted">{quote.basis}</span></dd>
            <dt>Interest due</dt>
            <dd><MoneyText value={quote.interestDue} /> <span className="muted">(credited so far <MoneyText value={quote.interestCredited} />)</span></dd>
            {Number(quote.toRecover ?? 0) > 0 && (
              <>
                <dt>Recovered</dt>
                <dd><MoneyText value={quote.toRecover} /> <span className="muted">interest already paid above what is due</span></dd>
              </>
            )}
            {Number(quote.overdueInterest ?? 0) > 0 && (
              <>
                <dt>Interest after maturity</dt>
                <dd><MoneyText value={quote.overdueInterest} /></dd>
              </>
            )}
            <dt>Paid before tax</dt>
            <dd><strong><MoneyText value={quote.payableBeforeTax} /></strong></dd>
          </dl>
          <p className="muted">{quote.taxNote}</p>
          {(quote.problems ?? []).length > 0 && (
            <Banner tone="warn">
              <div>
                <strong>The deposit cannot be closed now:</strong>
                <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>{(quote.problems ?? []).map((p) => <li key={p}>{p}</li>)}</ul>
              </div>
            </Banner>
          )}
          <PayToFields account={account} value={pay} onChange={setPay} modes={toBank ? ['ACCOUNT', 'CASH', 'BANK_TRANSFER', 'GATEWAY'] : ['ACCOUNT', 'CASH', 'BANK_TRANSFER']} />
          <Textarea label={needsNote ? 'What supports it (certificate or claim seen)' : 'Note'} required={needsNote} rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
        </>
      )}
      <Problems error={m.error ?? q.error} title="The deposit cannot be closed:" />
    </Dialog>
  );
}

// ---------- maturity instruction and renewal ----------

function ConsentFields({ reference, receivedOn, onChange }: { reference: string; receivedOn: string; onChange: (r: string, d: string) => void }) {
  return (
    <>
      <Banner tone="info">A deposit-taking NBFC renews a deposit only with the depositor’s express consent (NBFC Directions para 17). Record their signed request.</Banner>
      <div className="form-grid">
        <Input label="Reference of the depositor’s request" required maxLength={60} value={reference} onChange={(e) => onChange(e.target.value, receivedOn)} />
        <Input label="Received on" required type="date" value={receivedOn} onChange={(e) => onChange(reference, e.target.value)} />
      </div>
    </>
  );
}

export function InstructionDialog({ account, isNbfc, renewalAllowed, onClose }: { account: DepositAccount; isNbfc: boolean; renewalAllowed: boolean; onClose: () => void }) {
  const t = account.termDeposit!;
  const options = (renewalAllowed ? ['PAY_OUT', 'RENEW_PRINCIPAL', 'RENEW_WITH_INTEREST'] : ['PAY_OUT']).filter((x) => x !== t.maturityInstruction);
  const [instruction, setInstruction] = useState(options[0] ?? 'PAY_OUT');
  const [ref, setRef] = useState('');
  const [on, setOn] = useState('');
  const m = useMaturityInstruction(account.id!);
  const toast = useMoneyToast();
  const consent = isNbfc && instruction !== 'PAY_OUT';
  return (
    <Dialog
      open
      onClose={onClose}
      title={`At maturity of deposit ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} disabled={options.length === 0 || (consent && (!ref.trim() || !on))}
            onClick={() => m.mutate({ maturityInstruction: instruction as 'PAY_OUT', consent: consent ? { reference: ref.trim(), receivedOn: on } : null }, { onSuccess: (a) => (toast(a, 'Maturity instruction'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      <p style={{ marginTop: 0 }}>Now: <strong>{INSTRUCTION_LABEL[t.maturityInstruction ?? 'PAY_OUT']}</strong>. The deposit matures on <DateText value={t.maturityDate} />.</p>
      {options.length === 0 ? (
        <Banner tone="info">The product does not allow renewal, so the deposit is paid out at maturity.</Banner>
      ) : (
        <Select label="Change to" value={instruction} onChange={(e) => setInstruction(e.target.value)} options={options.map((x) => ({ value: x, label: INSTRUCTION_LABEL[x] }))} />
      )}
      {consent && <ConsentFields reference={ref} receivedOn={on} onChange={(r, d) => (setRef(r), setOn(d))} />}
      <Problems error={m.error} title="The instruction cannot be changed:" />
    </Dialog>
  );
}

export function RenewDialog({ account, isNbfc, onClose }: { account: DepositAccount; isNbfc: boolean; onClose: () => void }) {
  const [instruction, setInstruction] = useState('RENEW_PRINCIPAL');
  const [ref, setRef] = useState('');
  const [on, setOn] = useState('');
  const m = useRenewDeposit(account.id!);
  const toast = useMoneyToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Renew matured deposit ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} disabled={isNbfc && (!ref.trim() || !on)}
            onClick={() => m.mutate({ instruction: instruction as 'RENEW_PRINCIPAL', consent: isNbfc ? { reference: ref.trim(), receivedOn: on } : null }, { onSuccess: (a) => (toast(a, 'Renewal'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      <p style={{ marginTop: 0 }}>A new deposit is booked and funded today for the same tenure, at today’s card rate; this one is closed.</p>
      <Select label="Renew" value={instruction} onChange={(e) => setInstruction(e.target.value)} options={['RENEW_PRINCIPAL', 'RENEW_WITH_INTEREST'].map((x) => ({ value: x, label: INSTRUCTION_LABEL[x] }))} />
      {isNbfc && <ConsentFields reference={ref} receivedOn={on} onChange={(r, d) => (setRef(r), setOn(d))} />}
      <Problems error={m.error} title="The deposit cannot be renewed:" />
    </Dialog>
  );
}

// ---------- how it was closed ----------

export function ClosureCard({ closure }: { closure: DepositClosure }) {
  const c = closure;
  const casa = c.kind === 'CASA';
  return (
    <Card title={casa ? 'Closed' : c.kind === 'RENEWAL' ? 'Renewed' : c.kind === 'MATURITY' ? 'Paid at maturity' : 'Closed before maturity'}>
      <dl className="kv" data-testid="closure">
        <dt>On</dt>
        <dd><DateText value={c.closedOn} /> {c.closedBy && <span className="muted">by {c.closedBy}</span>}</dd>
        {!casa && (
          <>
            <dt>Reason</dt>
            <dd>{REASON_LABEL[c.reason ?? ''] ?? humanize(c.reason ?? '')}</dd>
            <dt>Rate allowed</dt>
            <dd>{c.ratePercent == null ? '—' : pct(c.ratePercent)} {c.basis && <span className="muted">{c.basis}</span>}</dd>
            <dt>Interest due</dt>
            <dd><MoneyText value={c.interestDue} /></dd>
          </>
        )}
        {Number(c.interestRecovered ?? 0) > 0 && (
          <>
            <dt>Recovered</dt>
            <dd><MoneyText value={c.interestRecovered} /></dd>
          </>
        )}
        {Number(c.overdueInterest ?? 0) > 0 && (
          <>
            <dt>Interest after maturity</dt>
            <dd><MoneyText value={c.overdueInterest} /></dd>
          </>
        )}
        {Number(c.charges ?? 0) > 0 && (
          <>
            <dt>Charges</dt>
            <dd><MoneyText value={c.charges} /></dd>
          </>
        )}
        {Number(c.tax ?? 0) > 0 && (
          <>
            <dt>Tax deducted</dt>
            <dd><MoneyText value={c.tax} /></dd>
          </>
        )}
        <dt>Paid</dt>
        <dd>
          <strong><MoneyText value={c.paidAmount} /></strong> {c.payTo && <span>{PAY_LABEL[c.payTo]?.toLowerCase()}</span>}
          {c.payAccountNo && <span> <span className="mono">{c.payAccountNo}</span></span>}
          {c.payReference && <span className="muted"> · {c.payReference}</span>}
          {c.renewedToAccountNo && <span> into new deposit <span className="mono">{c.renewedToAccountNo}</span></span>}
        </dd>
        {c.note && (
          <>
            <dt>Note</dt>
            <dd>{c.note}</dd>
          </>
        )}
      </dl>
    </Card>
  );
}
