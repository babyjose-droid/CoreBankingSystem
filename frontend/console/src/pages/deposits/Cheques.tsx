import { useState } from 'react';
import { useCasaCheques, useIssueChequeBook, useLodgeCheque, useReactivate, useRevokeChequeStop, useStopCheques } from '../../api/depositHooks';
import type { DepositAccount, DepositFamily } from '../../api/types';
import { Badge, Banner, Button, Card, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, Select, Spinner, Table, Textarea } from '../../ui';
import { Problems } from './common';
import { useMoneyToast } from './DepositMoney';

/**
 * Cheques on a savings or current account (P4-6): its cheque books (issued through a checker), stop payments (at once)
 * and their revocation (through a checker), the cheques presented on it and the cheques paid into it; and the banner of
 * an inoperative account or a balance transferred as unclaimed, with reactivation or the claim.
 */

const LEAF_TONE: Record<string, 'ok' | 'warn' | 'neutral'> = { PAID: 'ok', STOPPED: 'warn', UNUSED: 'neutral' };
const CLEARING_TONE: Record<string, 'ok' | 'warn' | 'neutral' | 'info'> = { IN_CLEARING: 'info', CLEARED: 'ok', RETURNED: 'warn', PAID: 'ok' };
const CLEARING_LABEL: Record<string, string> = { IN_CLEARING: 'In clearing', CLEARED: 'Cleared', RETURNED: 'Returned', PAID: 'Paid' };
export const clearingBadge = (s: string | undefined) => <Badge tone={CLEARING_TONE[s ?? ''] ?? 'neutral'}>{CLEARING_LABEL[s ?? ''] ?? s}</Badge>;

export function ChequesCard({ account, canTransact }: { account: DepositAccount; canTransact: boolean }) {
  const q = useCasaCheques(account.id);
  const [dialog, setDialog] = useState<'BOOK' | 'STOP' | 'LODGE' | null>(null);
  const [revoke, setRevoke] = useState<number | null>(null);
  const d = q.data;
  const operative = account.status === 'ACTIVE' && !account.inoperativeSince && !account.unclaimed;
  const facility = d?.chequeFacility === true;
  const books = d?.books ?? [];
  const actions = canTransact && operative ? (
    <div className="row" style={{ gap: 8 }}>
      {facility && <Button size="sm" onClick={() => setDialog('BOOK')}>Issue a cheque book</Button>}
      {books.length > 0 && <Button size="sm" onClick={() => setDialog('STOP')}>Stop payment</Button>}
      <Button size="sm" onClick={() => setDialog('LODGE')}>Pay in a cheque</Button>
    </div>
  ) : undefined;
  return (
    <Card title="Cheques" actions={actions}>
      <ErrorBanner error={q.error} />
      {q.isLoading ? (
        <Spinner />
      ) : (
        <div className="stack">
          {books.length === 0 ? (
            <EmptyState title="No cheque book issued">
              {facility ? 'A cheque book is issued through a checker; its cheques are numbered once across the institution.' : "This account's product has no cheque facility."}
            </EmptyState>
          ) : (
            <Table
              caption="Cheque books"
              columns={[
                { key: 'no', header: 'Book', render: (b) => <span className="mono">{b.bookNo}</span> },
                { key: 'leaves', header: 'Cheques', render: (b) => <span className="mono">{b.firstLeaf} – {b.lastLeaf}</span> },
                { key: 'unused', header: 'Unused', numeric: true, render: (b) => b.unused },
                { key: 'on', header: 'Issued', render: (b) => <span><DateText value={b.issuedOn} /> <span className="muted">by {b.issuedBy}</span></span> },
              ]}
              rows={books}
              rowKey={(b) => b.id!}
            />
          )}
          {(d?.leaves ?? []).length > 0 && (
            <Table
              caption="Cheques stopped or paid"
              columns={[
                { key: 'no', header: 'Cheque', render: (l) => <span className="mono">{l.leafNo}</span> },
                { key: 'st', header: 'Status', render: (l) => <Badge tone={LEAF_TONE[l.status ?? 'UNUSED']}>{l.status === 'STOPPED' ? 'Stopped' : l.status === 'PAID' ? 'Paid' : 'Unused'}</Badge> },
                { key: 'on', header: 'On', render: (l) => <DateText value={l.stoppedOn ?? l.paidOn} /> },
                { key: 'why', header: 'Reason', render: (l) => l.stopReason ?? '—' },
                {
                  key: 'act',
                  header: <span className="sr-only">Actions</span>,
                  render: (l) => (l.status === 'STOPPED' && canTransact ? <Button size="sm" aria-label={`Revoke the stop on ${l.leafNo}`} onClick={() => setRevoke(l.leafNo!)}>Revoke the stop</Button> : null),
                },
              ]}
              rows={d?.leaves ?? []}
              rowKey={(l) => String(l.leafNo)}
            />
          )}
          {(d?.presented ?? []).length > 0 && (
            <Table
              caption="Cheques presented (inward clearing)"
              columns={[
                { key: 'no', header: 'Cheque', render: (p) => <span className="mono">{p.leafNo}</span> },
                { key: 'on', header: 'Presented', render: (p) => <DateText value={p.presentedOn} /> },
                { key: 'to', header: 'Payee', render: (p) => <span>{p.payee} <span className="muted">via {p.presentingBank}</span></span> },
                { key: 'amt', header: 'Amount', numeric: true, render: (p) => <MoneyText value={p.amount} /> },
                { key: 'st', header: 'Outcome', render: (p) => <span>{clearingBadge(p.status)}{p.returnReasonText && <span className="muted"> {p.returnReasonText}</span>}</span> },
              ]}
              rows={d?.presented ?? []}
              rowKey={(p) => p.id!}
            />
          )}
          {(d?.paidIn ?? []).length > 0 && (
            <Table
              caption="Cheques paid in (outward clearing)"
              columns={[
                { key: 'no', header: 'Cheque', render: (c) => <span className="mono">{c.chequeNo}</span> },
                { key: 'drawer', header: 'Drawer', render: (c) => <span>{c.drawerName} <span className="muted mono">{c.draweeIfsc}</span></span> },
                { key: 'on', header: 'Paid in', render: (c) => <DateText value={c.lodgedOn} /> },
                { key: 'amt', header: 'Amount', numeric: true, render: (c) => <MoneyText value={c.amount} /> },
                { key: 'st', header: 'Status', render: (c) => <span>{clearingBadge(c.status)}{c.returnReason && <span className="muted"> {c.returnReason}</span>}</span> },
              ]}
              rows={d?.paidIn ?? []}
              rowKey={(c) => c.id!}
            />
          )}
        </div>
      )}
      {dialog === 'BOOK' && <IssueBookDialog account={account} onClose={() => setDialog(null)} />}
      {dialog === 'STOP' && <StopDialog account={account} onClose={() => setDialog(null)} />}
      {dialog === 'LODGE' && <LodgeDialog account={account} onClose={() => setDialog(null)} />}
      {revoke !== null && <RevokeDialog account={account} leafNo={revoke} onClose={() => setRevoke(null)} />}
    </Card>
  );
}

function IssueBookDialog({ account, onClose }: { account: DepositAccount; onClose: () => void }) {
  const [leaves, setLeaves] = useState('25');
  const m = useIssueChequeBook(account.id!);
  const toast = useMoneyToast();
  return (
    <Dialog open onClose={onClose} title={`Issue a cheque book on ${account.accountNo}`}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" loading={m.isPending} onClick={() => m.mutate(Number(leaves), { onSuccess: (a) => (toast(a, 'Cheque book'), onClose()) })}>Submit for approval</Button></>}>
      <Select label="Leaves" value={leaves} onChange={(e) => setLeaves(e.target.value)} options={['10', '25', '50', '100'].map((v) => ({ value: v, label: v }))} />
      <Problems error={m.error} title="The cheque book cannot be issued:" />
    </Dialog>
  );
}

function StopDialog({ account, onClose }: { account: DepositAccount; onClose: () => void }) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  const m = useStopCheques(account.id!);
  const toast = useMoneyToast();
  const submit = () => m.mutate({ fromLeaf: Number(from), toLeaf: to ? Number(to) : null, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Stop payment'), onClose()) });
  return (
    <Dialog open onClose={onClose} title={`Stop payment on ${account.accountNo}`}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" loading={m.isPending} onClick={submit}>Stop payment</Button></>}>
      <p style={{ marginTop: 0 }} className="muted">The stop takes effect at once, without a checker. Revoking it later needs a checker.</p>
      <div className="form-grid">
        <Input label="Cheque number" required numeric value={from} onChange={(e) => setFrom(e.target.value.replace(/\D/g, ''))} />
        <Input label="To cheque number" numeric value={to} onChange={(e) => setTo(e.target.value.replace(/\D/g, ''))} hint="For a run of cheques (at most 100)" />
      </div>
      <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} hint="The drawer's request: lost, stolen, dispute …" />
      <Problems error={m.error} title="Payment cannot be stopped:" />
    </Dialog>
  );
}

function RevokeDialog({ account, leafNo, onClose }: { account: DepositAccount; leafNo: number; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const m = useRevokeChequeStop(account.id!);
  const toast = useMoneyToast();
  return (
    <Dialog open onClose={onClose} title={`Revoke the stop on cheque ${leafNo}`}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" loading={m.isPending} onClick={() => m.mutate({ leafNo, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Revocation'), onClose()) })}>Submit for approval</Button></>}>
      <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problems error={m.error} title="The stop cannot be revoked:" />
    </Dialog>
  );
}

function LodgeDialog({ account, onClose }: { account: DepositAccount; onClose: () => void }) {
  const [chequeNo, setChequeNo] = useState('');
  const [ifsc, setIfsc] = useState('');
  const [drawer, setDrawer] = useState('');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState('');
  const m = useLodgeCheque(account.id!);
  const [done, setDone] = useState(false);
  const submit = () => m.mutate({ chequeNo, draweeIfsc: ifsc, drawerName: drawer, amount, chequeDate: date }, { onSuccess: () => setDone(true) });
  return (
    <Dialog open onClose={onClose} title={`Pay a cheque into ${account.accountNo}`}
      footer={done ? <Button variant="primary" onClick={onClose}>Close</Button> : <><Button onClick={onClose}>Cancel</Button><Button variant="primary" loading={m.isPending} onClick={submit}>Pay in</Button></>}>
      {done ? (
        <Banner tone="ok">Cheque {chequeNo} is in clearing. The account is credited once it is settled as cleared (Deposits › Clearing).</Banner>
      ) : (
        <>
          <p style={{ marginTop: 0 }} className="muted">Another bank's cheque. It waits in clearing and is credited only once it clears.</p>
          <div className="form-grid">
            <Input label="Cheque number" required value={chequeNo} maxLength={6} onChange={(e) => setChequeNo(e.target.value.replace(/\D/g, ''))} />
            <Input label="Drawee bank IFSC" required value={ifsc} maxLength={11} onChange={(e) => setIfsc(e.target.value.toUpperCase())} />
            <Input label="Drawer" required maxLength={200} value={drawer} onChange={(e) => setDrawer(e.target.value)} />
            <Input label="Amount" required numeric value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.]/g, ''))} />
            <Input label="Date on the cheque" required type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </div>
          <Problems error={m.error} title="The cheque cannot be paid in:" />
        </>
      )}
    </Dialog>
  );
}

/** An inoperative account, or one whose balance was transferred as unclaimed: what it means, and reactivation or the claim. */
export function DormancyBanner({ family, account, canAct }: { family: DepositFamily; account: DepositAccount; canAct: boolean }) {
  const [open, setOpen] = useState(false);
  if (!account.inoperativeSince && !account.unclaimed) return null;
  const casa = family === 'CASA';
  return (
    <>
      <Banner tone="warn">
        <span data-testid="dormancy-banner">
          {account.unclaimed ? (
            <>
              The balance of <MoneyText value={account.unclaimed.amount} /> was transferred as unclaimed on <DateText value={account.unclaimed.transferredOn} /> (for the DEA Fund).
              Nothing moves on {casa ? 'the account' : 'the deposit'} until it is refunded to the {casa ? 'depositor' : 'claimant'}.
            </>
          ) : (
            <>
              This account is inoperative since <DateText value={account.inoperativeSince} />: it takes credits, but nothing is taken out of it, no cheque is paid and no
              charge is levied until it is reactivated with the KYC record updated.
            </>
          )}{' '}
          {canAct && <Button size="sm" onClick={() => setOpen(true)}>{casa ? 'Reactivate' : 'Pay the claim'}</Button>}
        </span>
      </Banner>
      {open && <ReactivateDialog family={family} account={account} onClose={() => setOpen(false)} />}
    </>
  );
}

function ReactivateDialog({ family, account, onClose }: { family: DepositFamily; account: DepositAccount; onClose: () => void }) {
  const [kyc, setKyc] = useState('');
  const [reason, setReason] = useState('');
  const m = useReactivate(family, account.id!);
  const toast = useMoneyToast();
  const casa = family === 'CASA';
  return (
    <Dialog open onClose={onClose} title={casa ? `Reactivate ${account.accountNo}` : `Pay the claim on ${account.accountNo}`}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" loading={m.isPending} onClick={() => m.mutate({ kycReference: kyc.trim(), reason: reason.trim() }, { onSuccess: (a) => (toast(a, casa ? 'Reactivation' : 'Claim'), onClose()) })}>Submit for approval</Button></>}>
      <p style={{ marginTop: 0 }} className="muted">
        A checker approves it.{account.unclaimed ? ' The unclaimed balance comes back to the account first.' : ''} No charge is levied.
      </p>
      <Input label="KYC record updated" required maxLength={100} value={kyc} onChange={(e) => setKyc(e.target.value)} hint="Or the claim documents seen" />
      <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problems error={m.error} title={casa ? 'The account cannot be reactivated:' : 'The claim cannot be paid:'} />
    </Dialog>
  );
}
