import { useState } from 'react';
import {
  useCasaTransaction,
  useCasaTransfer,
  useCreateDepositCollectionOrder,
  useDepositAccounts,
  useDepositCollectionOrders,
  useDepositHolds,
  useDepositInterest,
  useDepositMovements,
  useFundTermDeposit,
  usePlaceHold,
  useReleaseHold,
  useReverseFunding,
  useReverseMovement,
} from '../../api/depositHooks';
import type { Approval, BankAccountInput, DepositAccount, DepositFamily, DepositHold, DepositMovement } from '../../api/types';
import { Badge, Banner, Button, Card, Checkbox, DateText, DateTimeText, Dialog, EmptyState, Input, MoneyText, Select, Spinner, StatusBadge, Table, Textarea, humanize, useToast } from '../../ui';
import { Problems } from './common';

/**
 * Money on deposit accounts (P4-2): balances, statement, holds, and the dialogs that move money or hold it. P4-2b adds
 * transfers out through the payment gateway and payment links (a savings or current account credited, a booked term
 * deposit funded).
 */

const KIND_LABEL: Record<string, string> = {
  CASH_DEPOSIT: 'Cash deposit',
  CASH_WITHDRAWAL: 'Cash withdrawal',
  BANK_TRANSFER_IN: 'Bank transfer in',
  BANK_TRANSFER_OUT: 'Bank transfer out',
  TRANSFER_IN: 'Transfer in',
  TRANSFER_OUT: 'Transfer out',
  FUNDING: 'Funding',
  FUNDING_PAID: 'Paid to a term deposit',
  REVERSAL: 'Reversal',
  INTEREST_CREDIT: 'Interest',
  INTEREST_PAYOUT: 'Interest paid out',
  TAX_DEDUCTED: 'Tax deducted at source',
};
const HOLD_LABEL: Record<string, string> = { DEBIT_FREEZE: 'Debit freeze', CREDIT_FREEZE: 'Credit freeze', TOTAL_FREEZE: 'Total freeze', LIEN: 'Lien' };
const GATEWAY: Record<string, { label: string; tone: 'info' | 'ok' | 'warn' }> = {
  SENT: { label: 'With the gateway', tone: 'info' },
  PAID: { label: 'Paid by the bank', tone: 'ok' },
  RETURNED: { label: 'Came back', tone: 'warn' },
};

/** Below the threshold money posts at once (APPROVED); from it upward it waits for a checker. */
export function useMoneyToast() {
  const toast = useToast();
  return (a: Approval, what: string) =>
    a.status === 'APPROVED'
      ? toast({ tone: 'success', message: `${what}: done.` })
      : toast({ tone: 'success', message: `${what} sent for approval.`, link: { to: `/approvals?id=${encodeURIComponent(a.id)}`, label: 'View request' } });
}

export function BalanceCard({ a }: { a: DepositAccount }) {
  const freezes = a.freezes ?? [];
  return (
    <Card title="Balance">
      <dl className="kv">
        <dt>Balance</dt>
        <dd data-testid="balance"><strong><MoneyText value={a.balance} /></strong></dd>
        <dt>Held under lien</dt>
        <dd><MoneyText value={a.lienAmount} /></dd>
        <dt>Available</dt>
        <dd data-testid="available"><MoneyText value={a.availableBalance} /></dd>
        <dt>Freezes</dt>
        <dd data-testid="freezes">{freezes.length ? freezes.map((f) => HOLD_LABEL[f]).join(', ') : 'None'}</dd>
      </dl>
    </Card>
  );
}

export function MovementsCard({ family, accountId, canReverse }: { family: DepositFamily; accountId: string; canReverse: boolean }) {
  const q = useDepositMovements(family, accountId);
  const [reversing, setReversing] = useState<DepositMovement | null>(null);
  const reversible = (m: DepositMovement) =>
    canReverse && family === 'CASA' && !['REVERSAL', 'FUNDING', 'FUNDING_PAID', 'INTEREST_CREDIT', 'INTEREST_PAYOUT', 'TAX_DEDUCTED'].includes(m.kind ?? '') && !m.reversedBy && !m.gatewayStatus;
  return (
    <Card title="Statement" flush>
      {q.isLoading ? (
        <Spinner />
      ) : (q.data ?? []).length === 0 ? (
        <EmptyState title="No money has moved on this account yet" />
      ) : (
        <Table
          caption="Movements"
          captionHidden
          columns={[
            { key: 'date', header: 'Date', render: (m) => <DateText value={m.businessDate} /> },
            {
              key: 'what',
              header: 'Movement',
              render: (m) => (
                <span>
                  {KIND_LABEL[m.kind ?? ''] ?? m.kind}
                  {m.counterpartyAccountNo && <span className="muted"> · <span className="mono">{m.counterpartyAccountNo}</span></span>}
                  {m.bankAccountMasked && <span className="muted"> · {m.bankAccountMasked} {m.bankIfsc}</span>}
                  {m.reference && <span className="muted"> · {m.reference}</span>}
                  {m.narration && <span className="muted"> · {m.narration}</span>}
                  {m.reversalReason && <span className="muted"> · {m.reversalReason}</span>}
                  {m.reversedBy && <span className="muted"> (reversed)</span>}
                  {m.gatewayStatus && (
                    <>
                      {' '}
                      <Badge tone={GATEWAY[m.gatewayStatus].tone}>{GATEWAY[m.gatewayStatus].label}</Badge>
                      {m.gatewayUtr && <span className="muted"> UTR {m.gatewayUtr}</span>}
                    </>
                  )}
                </span>
              ),
            },
            { key: 'dr', header: 'Debit', numeric: true, render: (m) => (m.direction === 'DEBIT' ? <MoneyText value={m.amount} /> : '') },
            { key: 'cr', header: 'Credit', numeric: true, render: (m) => (m.direction === 'CREDIT' ? <MoneyText value={m.amount} /> : '') },
            { key: 'bal', header: 'Balance', numeric: true, render: (m) => <MoneyText value={m.balanceAfter} /> },
            {
              key: 'act',
              header: <span className="sr-only">Actions</span>,
              render: (m) =>
                reversible(m) ? (
                  <Button size="sm" aria-label={`Reverse ${KIND_LABEL[m.kind ?? '']} of ${m.amount}`} onClick={() => setReversing(m)}>
                    Reverse
                  </Button>
                ) : null,
            },
          ]}
          rows={q.data ?? []}
          rowKey={(m) => m.id!}
        />
      )}
      {reversing && <ReverseDialog movement={reversing} onClose={() => setReversing(null)} />}
    </Card>
  );
}

function ReverseDialog({ movement, onClose }: { movement: DepositMovement; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const m = useReverseMovement();
  const toast = useMoneyToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Reverse ${(KIND_LABEL[movement.kind ?? ''] ?? '').toLowerCase()} of ${movement.amount}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} disabled={!reason.trim()} onClick={() => m.mutate({ movementId: movement.id!, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Reversal'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>The movement is reversed with its mirror entry; a transfer is reversed on both accounts. A checker approves every reversal.</p>
      <Textarea label="Reason" required rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problems error={m.error} title="The movement cannot be reversed:" />
    </Dialog>
  );
}

export function HoldsCard({ family, accountId, canRelease }: { family: DepositFamily; accountId: string; canRelease: boolean }) {
  const q = useDepositHolds(family, accountId);
  const [releasing, setReleasing] = useState<DepositHold | null>(null);
  const rows = q.data ?? [];
  if (!q.isLoading && rows.length === 0) return null;
  return (
    <Card title={family === 'CASA' ? 'Freezes and liens' : 'Liens'} flush>
      {q.isLoading ? (
        <Spinner />
      ) : (
        <Table
          caption="Holds"
          captionHidden
          columns={[
            { key: 'kind', header: 'Hold', render: (h) => HOLD_LABEL[h.kind ?? ''] },
            { key: 'amount', header: 'Amount', numeric: true, render: (h) => (h.amount ? <MoneyText value={h.amount} /> : '—') },
            { key: 'reason', header: 'Reason', render: (h) => <span>{h.reason}{h.reference && <span className="muted"> · {h.reference}</span>}</span> },
            { key: 'placed', header: 'Placed', render: (h) => <span><DateText value={h.placedOn} /> <span className="muted">by {h.placedBy}</span></span> },
            { key: 'status', header: 'Status', render: (h) => (h.status === 'ACTIVE' ? 'In force' : <span>Released <DateText value={h.releasedOn} /><span className="muted"> · {h.releaseReason}</span></span>) },
            {
              key: 'act',
              header: <span className="sr-only">Actions</span>,
              render: (h) =>
                canRelease && h.status === 'ACTIVE' ? (
                  <Button size="sm" aria-label={`Release ${HOLD_LABEL[h.kind ?? '']}`} onClick={() => setReleasing(h)}>
                    Release
                  </Button>
                ) : null,
            },
          ]}
          rows={rows}
          rowKey={(h) => h.id!}
        />
      )}
      {releasing && <ReleaseDialog family={family} hold={releasing} onClose={() => setReleasing(null)} />}
    </Card>
  );
}

function ReleaseDialog({ family, hold, onClose }: { family: DepositFamily; hold: DepositHold; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const m = useReleaseHold(family);
  const toast = useMoneyToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Release the ${(HOLD_LABEL[hold.kind ?? ''] ?? '').toLowerCase()}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} disabled={!reason.trim()} onClick={() => m.mutate({ holdId: hold.id!, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Release'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      <Textarea label="Reason" required rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problems error={m.error} title="The hold cannot be released:" />
    </Dialog>
  );
}

function BankAccountFields({ value, onChange, legend }: { value: BankAccountInput; onChange: (b: BankAccountInput) => void; legend: string }) {
  return (
    <fieldset className="stack" style={{ border: 0, padding: 0, margin: 0 }}>
      <legend className="field__label">{legend}</legend>
      <div className="form-grid">
        <Input label="Account holder" required maxLength={100} value={value.holderName} onChange={(e) => onChange({ ...value, holderName: e.target.value })} />
        <Input label="Account number" required value={value.accountNumber} onChange={(e) => onChange({ ...value, accountNumber: e.target.value })} />
        <Input label="IFSC" required maxLength={11} value={value.ifsc} onChange={(e) => onChange({ ...value, ifsc: e.target.value.toUpperCase() })} />
      </div>
      <span className="field__hint">The account number is stored encrypted and shown with its last four digits only.</span>
    </fieldset>
  );
}

const emptyBank = (): BankAccountInput => ({ holderName: '', accountNumber: '', ifsc: '' });
const bankGiven = (b: BankAccountInput) => !!(b.holderName.trim() || b.accountNumber.trim() || b.ifsc.trim());

/** Cash in or out, a bank transfer in or out recorded with the bank's reference (UTR), or a transfer out sent through the gateway. */
export function TransactionDialog({ account, type: initial, onClose }: { account: DepositAccount; type: string; onClose: () => void }) {
  const [type, setType] = useState(initial);
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const [narration, setNarration] = useState('');
  const [bank, setBank] = useState(emptyBank());
  const [viaGateway, setViaGateway] = useState(false);
  const m = useCasaTransaction(account.id!);
  const toast = useMoneyToast();
  const isBank = type.startsWith('BANK_');
  const gateway = viaGateway && type === 'BANK_TRANSFER_OUT';
  const submit = () =>
    m.mutate(
      {
        type: type as 'CASH_DEPOSIT',
        amount: Number(amount),
        reference: reference.trim() || null,
        narration: narration.trim() || null,
        ...(isBank && bankGiven(bank) ? { bankAccount: { ...bank, accountNumber: bank.accountNumber.replace(/ /g, '') } } : {}),
        ...(gateway ? { viaGateway: true } : {}),
      },
      { onSuccess: (a) => (toast(a, KIND_LABEL[type]), onClose()) },
    );
  return (
    <Dialog
      open
      onClose={onClose}
      wide
      title={`Account ${account.accountNo}: money in or out`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} disabled={!amount} onClick={submit}>
            Submit
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <Select label="Transaction" value={type} onChange={(e) => setType(e.target.value)} options={['CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'BANK_TRANSFER_IN', 'BANK_TRANSFER_OUT'].map((t) => ({ value: t, label: KIND_LABEL[t] }))} />
        <Input label="Amount" required numeric inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} hint={`Available: ${account.availableBalance ?? '0'}`} />
        <Input
          label={gateway ? 'Reference' : isBank ? 'UTR (the bank’s reference)' : 'Slip number'}
          required={isBank && !gateway}
          hint={gateway ? 'Optional: the bank gives the UTR when it pays' : undefined}
          maxLength={60}
          value={reference}
          onChange={(e) => setReference(e.target.value)}
        />
        <Input label="Narration" maxLength={200} value={narration} onChange={(e) => setNarration(e.target.value)} />
      </div>
      {type === 'BANK_TRANSFER_OUT' && <Checkbox label="Send it through the payment gateway" checked={viaGateway} onChange={(e) => setViaGateway(e.target.checked)} />}
      {isBank && <BankAccountFields value={bank} onChange={setBank} legend={type === 'BANK_TRANSFER_OUT' ? 'Paid to (required)' : 'Received from (optional)'} />}
      {isBank && (
        <p className="muted">
          {gateway
            ? 'The money leaves the account when the request is applied and the gateway sends it; if the payout fails or the bank sends it back, it returns to the account.'
            : 'The transfer is recorded here once the bank has made it.'}
        </p>
      )}
      <p className="muted">Below ₹2,00,000 it is posted at once (the tenant's starting rule); from that amount a checker approves it first.</p>
      <Problems error={m.error} title="The transaction cannot be made:" />
    </Dialog>
  );
}

/** A transfer from this account to another savings or current account here. */
export function TransferDialog({ account, onClose }: { account: DepositAccount; onClose: () => void }) {
  const accounts = useDepositAccounts('CASA', { status: 'ACTIVE' });
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [narration, setNarration] = useState('');
  const m = useCasaTransfer();
  const toast = useMoneyToast();
  const options = (accounts.data ?? []).filter((x) => x.id !== account.id).map((x) => ({ value: x.id!, label: `${x.accountNo} — ${x.customerName} (${x.productCode})` }));
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Transfer from ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} disabled={!to || !amount} onClick={() => m.mutate({ fromAccountId: account.id!, toAccountId: to, amount: Number(amount), narration: narration.trim() || null }, { onSuccess: (a) => (toast(a, 'Transfer'), onClose()) })}>
            Submit
          </Button>
        </>
      }
    >
      <Select label="To account" required placeholder="Choose an account" value={to} onChange={(e) => setTo(e.target.value)} options={options} />
      <Input label="Amount" required numeric inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} hint={`Available: ${account.availableBalance ?? '0'}`} />
      <Input label="Narration" maxLength={200} value={narration} onChange={(e) => setNarration(e.target.value)} />
      <Problems error={m.error} title="The transfer cannot be made:" />
    </Dialog>
  );
}

/** A freeze (savings and current accounts) or a lien. */
export function HoldDialog({ family, account, onClose }: { family: DepositFamily; account: DepositAccount; onClose: () => void }) {
  const kinds = family === 'CASA' ? ['DEBIT_FREEZE', 'CREDIT_FREEZE', 'TOTAL_FREEZE', 'LIEN'] : ['LIEN'];
  const [kind, setKind] = useState(kinds[0]);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const m = usePlaceHold(family, account.id!);
  const toast = useMoneyToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={family === 'CASA' ? `Freeze or lien on ${account.accountNo}` : `Lien on deposit ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={m.isPending}
            disabled={!reason.trim() || (kind === 'LIEN' && !amount)}
            onClick={() => m.mutate({ kind: kind as 'LIEN', amount: kind === 'LIEN' ? Number(amount) : null, reason: reason.trim(), reference: reference.trim() || null }, { onSuccess: (a) => (toast(a, HOLD_LABEL[kind]), onClose()) })}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      {family === 'CASA' && <Select label="Hold" value={kind} onChange={(e) => setKind(e.target.value)} options={kinds.map((k) => ({ value: k, label: HOLD_LABEL[k] }))} />}
      {kind === 'LIEN' ? (
        <Input label="Amount held" required numeric inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} hint={`Available: ${account.availableBalance ?? '0'}`} />
      ) : (
        <p className="muted">{kind === 'DEBIT_FREEZE' ? 'No money goes out; money still comes in.' : kind === 'CREDIT_FREEZE' ? 'No money comes in; money still goes out.' : 'No money comes in or goes out.'} Reversals (corrections) are not stopped.</p>
      )}
      <Textarea label="Reason" required rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Input label="Reference" hint="The order, or the loan the lien secures" maxLength={100} value={reference} onChange={(e) => setReference(e.target.value)} />
      <Problems error={m.error} title="The hold cannot be placed:" />
    </Dialog>
  );
}

/** Funding a booked term deposit with its principal. */
export function FundingDialog({ account, casaEnabled, onClose }: { account: DepositAccount; casaEnabled: boolean; onClose: () => void }) {
  const t = account.termDeposit!;
  const depositor = account.holders?.[0]?.customerId;
  const own = useDepositAccounts('CASA', { customerId: depositor, status: 'ACTIVE' }, casaEnabled && !!depositor);
  const sources = casaEnabled ? ['ACCOUNT', 'CASH', 'BANK_TRANSFER'] : ['CASH', 'BANK_TRANSFER'];
  const [source, setSource] = useState(sources[0]);
  const [from, setFrom] = useState('');
  const [reference, setReference] = useState('');
  const [bank, setBank] = useState(emptyBank());
  const m = useFundTermDeposit(account.id!);
  const toast = useMoneyToast();
  const toBank = t.payoutTo === 'BANK_ACCOUNT';
  const SOURCE_LABEL: Record<string, string> = { ACCOUNT: 'From a savings or current account', CASH: 'Cash', BANK_TRANSFER: 'Bank transfer' };
  return (
    <Dialog
      open
      onClose={onClose}
      wide
      title={`Fund deposit ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={m.isPending}
            onClick={() =>
              m.mutate(
                {
                  source: source as 'CASH',
                  fromAccountId: source === 'ACCOUNT' ? from || null : null,
                  reference: reference.trim() || null,
                  ...(toBank ? { payoutBankAccount: { ...bank, accountNumber: bank.accountNumber.replace(/ /g, '') } } : {}),
                },
                { onSuccess: (a) => (toast(a, 'Funding'), onClose()) },
              )
            }
          >
            Submit
          </Button>
        </>
      }
    >
      <p style={{ marginTop: 0 }}>
        The principal of <strong><MoneyText value={t.principal} /></strong> is received today; interest starts today and the deposit matures {t.tenureMonths ? `${t.tenureMonths} months` : ''}
        {t.tenureDays ? ` ${t.tenureDays} days` : ''} later.
      </p>
      <div className="form-grid">
        <Select label="Funded" value={source} onChange={(e) => setSource(e.target.value)} options={sources.map((s) => ({ value: s, label: SOURCE_LABEL[s] }))} />
        {source === 'ACCOUNT' && (
          <Select
            label="Paying account"
            required
            placeholder={own.isLoading ? 'Loading…' : 'Choose an account of the depositor'}
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            options={(own.data ?? []).map((x) => ({ value: x.id!, label: `${x.accountNo} (${x.productCode}), balance ${x.balance ?? '0'}` }))}
          />
        )}
        <Input label={source === 'BANK_TRANSFER' ? 'UTR (the bank’s reference)' : 'Receipt or slip number'} required={source === 'BANK_TRANSFER'} maxLength={60} value={reference} onChange={(e) => setReference(e.target.value)} />
      </div>
      {toBank && <BankAccountFields value={bank} onChange={setBank} legend="The depositor’s bank account (interest and maturity proceeds are paid to it)" />}
      <Problems error={m.error} title="The deposit cannot be funded:" />
    </Dialog>
  );
}

export function ReverseFundingDialog({ account, onClose }: { account: DepositAccount; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const m = useReverseFunding(account.id!);
  const toast = useMoneyToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Reverse the funding of deposit ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" loading={m.isPending} disabled={!reason.trim()} onClick={() => m.mutate(reason.trim(), { onSuccess: (a) => (toast(a, 'Reversal of the funding'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      <Banner tone="warn">The money goes back where it came from and the deposit is booked again, waiting for its funds.</Banner>
      <Textarea label="Reason" required rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problems error={m.error} title="The funding cannot be reversed:" />
    </Dialog>
  );
}

export const kindLabel = (k: string | undefined) => KIND_LABEL[k ?? ''] ?? humanize(k ?? '');

/** The payment links of an account: a savings or current account (credits) or a term deposit (its funding). */
export function PaymentLinksCard({ family, accountId }: { family: DepositFamily; accountId: string }) {
  const q = useDepositCollectionOrders(family, accountId);
  const rows = q.data ?? [];
  if (!q.isLoading && rows.length === 0) return null;
  return (
    <Card title="Payment links" flush>
      {q.isLoading ? (
        <Spinner />
      ) : (
        <Table
          caption="Payment links"
          captionHidden
          columns={[
            { key: 'ref', header: 'Reference', render: (o) => <span className="mono">{o.reference}</span> },
            { key: 'amt', header: 'Amount', numeric: true, render: (o) => <MoneyText value={o.amount} /> },
            { key: 'status', header: 'Status', render: (o) => <StatusBadge status={o.status ?? ''} /> },
            {
              key: 'url',
              header: 'Link',
              render: (o) => (o.paymentUrl && o.status === 'CREATED' ? <span className="mono" style={{ fontSize: 12, wordBreak: 'break-all' }}>{o.paymentUrl}</span> : <span className="muted">—</span>),
            },
            { key: 'exp', header: 'Expires', render: (o) => <DateTimeText value={o.expiresAt} /> },
            { key: 'by', header: 'Created', render: (o) => <span><DateTimeText value={o.createdAt} /> <span className="muted mono">{o.createdBy}</span></span> },
          ]}
          rows={rows}
          rowKey={(o) => o.id ?? ''}
        />
      )}
    </Card>
  );
}

const METHODS = ['UPI', 'CARD', 'NETBANKING'] as const;

/** A payment link the customer pays through the gateway: a credit to a savings or current account, or the funding of a booked deposit. */
export function PaymentLinkDialog({ family, account, onClose }: { family: DepositFamily; account: DepositAccount; onClose: () => void }) {
  const term = family === 'TERM_DEPOSIT';
  const t = account.termDeposit;
  const needsBank = term && t?.payoutTo === 'BANK_ACCOUNT' && !t?.payoutBankAccountMasked;
  const [amount, setAmount] = useState('');
  const [methods, setMethods] = useState<string[]>(['UPI']);
  const [bank, setBank] = useState(emptyBank());
  const m = useCreateDepositCollectionOrder(family, account.id!);
  const toast = useToast();
  const submit = () =>
    m.mutate(
      {
        ...(term ? {} : { amount }),
        methods: methods as Array<(typeof METHODS)[number]>,
        ...(needsBank ? { payoutBankAccount: { ...bank, accountNumber: bank.accountNumber.replace(/ /g, '') } } : {}),
      },
      { onSuccess: (o) => (toast({ tone: 'success', message: `Payment link ${o.reference} created.` }), onClose()) },
    );
  return (
    <Dialog
      open
      onClose={onClose}
      wide={needsBank}
      title={term ? `Payment link to fund deposit ${account.accountNo}` : `Payment link for account ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={m.isPending} disabled={!term && !amount} onClick={submit}>
            Create payment link
          </Button>
        </>
      }
    >
      {term ? (
        <p style={{ marginTop: 0 }}>
          The customer pays the principal of <strong><MoneyText value={t?.principal} /></strong>. The deposit is funded on the business day the payment is posted, and its interest starts that day.
        </p>
      ) : (
        <Input label="Amount" required numeric inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[,\s₹]/g, ''))} />
      )}
      <fieldset className="fieldset">
        <legend>Payment methods</legend>
        {METHODS.map((x) => (
          <Checkbox key={x} label={x === 'NETBANKING' ? 'Net banking' : x === 'CARD' ? 'Card' : 'UPI'} checked={methods.includes(x)} onChange={(e) => setMethods(e.target.checked ? [...methods, x] : methods.filter((y) => y !== x))} />
        ))}
      </fieldset>
      {needsBank && <BankAccountFields value={bank} onChange={setBank} legend="The depositor’s bank account (interest and maturity proceeds are paid to it)" />}
      <p className="muted">A payment the account cannot take (a freeze, a deposit funded another way) goes to the unmatched receipts queue under Collections.</p>
      <Problems error={m.error} title="The payment link cannot be created:" />
    </Dialog>
  );
}

const EVENT_LABEL: Record<string, string> = {
  ACCRUAL: 'Accrued',
  CREDIT: 'Credited',
  PAYOUT: 'Paid out',
  PAYOUT_HELD: 'Kept on the deposit',
  REVERSAL: 'Accrual taken back',
  TAX: 'Tax deducted at source',
};

/** Interest (P4-3): what the day-end has accrued and credited, the next credit date and, for a term deposit, its periods. */
export function InterestCard({ family, accountId }: { family: DepositFamily; accountId: string }) {
  const q = useDepositInterest(family, accountId);
  const i = q.data;
  const credits = (i?.events ?? []).filter((e) => e.kind !== 'ACCRUAL').slice(0, 10);
  return (
    <Card title="Interest" flush>
      {q.isLoading || !i ? (
        <Spinner />
      ) : (
        <div className="stack" style={{ padding: 16 }}>
          <dl className="kv">
            <dt>Accrued, not yet credited</dt>
            <dd data-testid="interest-accrued"><MoneyText value={i.accrued} /></dd>
            <dt>Accrued through</dt>
            <dd>{i.accruedThrough ? <DateText value={i.accruedThrough} /> : <span className="muted">The first day-end has not run yet</span>}</dd>
            <dt>Credited so far</dt>
            <dd data-testid="interest-credited"><MoneyText value={i.credited} />{i.lastCreditOn && <span className="muted"> · last for the period to <DateText value={i.lastCreditOn} /></span>}</dd>
            <dt>Tax deducted at source</dt>
            <dd data-testid="interest-tax"><MoneyText value={i.taxDeducted} /></dd>
            <dt>Next credit</dt>
            <dd data-testid="interest-next">{i.nextCreditDate ? <DateText value={i.nextCreditDate} /> : '—'}</dd>
          </dl>
          {(i.schedule ?? []).length > 0 && (
            <Table
              caption="Interest periods"
              columns={[
                { key: 'n', header: 'Period', render: (p) => p.number },
                { key: 'from', header: 'From', render: (p) => <DateText value={p.from} /> },
                { key: 'on', header: 'Credited on', render: (p) => <DateText value={p.creditOn} /> },
                { key: 'int', header: 'Interest', numeric: true, render: (p) => <MoneyText value={p.interest} /> },
                { key: 'st', header: 'Status', render: (p) => (p.status === 'CREDITED' ? <Badge tone="ok">Credited</Badge> : <span className="muted">Due</span>) },
              ]}
              rows={i.schedule ?? []}
              rowKey={(p) => String(p.number)}
            />
          )}
          {credits.length > 0 && (
            <Table
              caption="Credits and payouts"
              columns={[
                { key: 'd', header: 'Business date', render: (e) => <DateText value={e.businessDate} /> },
                { key: 'k', header: 'What', render: (e) => <span>{EVENT_LABEL[e.kind ?? ''] ?? e.kind}{e.note && <span className="muted"> · {e.note}</span>}</span> },
                { key: 'p', header: 'Period', render: (e) => (e.periodFrom ? <span><DateText value={e.periodFrom} /> – <DateText value={e.periodTo} /></span> : '—') },
                { key: 'a', header: 'Amount', numeric: true, render: (e) => <MoneyText value={e.amount} /> },
              ]}
              rows={credits}
              rowKey={(e) => String(e.id)}
            />
          )}
          <p className="muted" style={{ margin: 0 }}>The day-end accrues interest every calendar day and credits it on the credit dates, then deducts tax at source when the depositor's interest for the tax year passes the threshold (none with Form 121).</p>
        </div>
      )}
    </Card>
  );
}
