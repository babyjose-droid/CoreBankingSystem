import { useState } from 'react';
import { useDepositAccounts, usePayRdInstalment, useRdInstalments } from '../../api/depositHooks';
import type { DepositAccount } from '../../api/types';
import { Badge, Banner, Button, Card, DateText, Dialog, ErrorBanner, Input, MoneyText, Select, Spinner, Table } from '../../ui';
import { Problems } from './common';
import { useMoneyToast } from './DepositMoney';

/**
 * Recurring deposits (P4-5): the instalments of a deposit, paid and to pay, and the dialog that pays the next one with
 * its late charge. The first instalment is the funding.
 */

const SOURCE_LABEL: Record<string, string> = { ACCOUNT: 'From a savings or current account', CASH: 'Cash', BANK_TRANSFER: 'Bank transfer', GATEWAY: 'Payment link' };

export function InstalmentsCard({ account, canPay }: { account: DepositAccount; canPay: boolean }) {
  const q = useRdInstalments(account.id);
  const [paying, setPaying] = useState(false);
  const r = q.data;
  const next = r?.next;
  return (
    <Card title="Instalments" flush>
      <ErrorBanner error={q.error} />
      {q.isLoading || !r ? (
        !q.error && <Spinner />
      ) : (
        <div className="stack" style={{ padding: 16 }}>
          <dl className="kv">
            <dt>Monthly instalment</dt>
            <dd><MoneyText value={r.instalment} /> × {r.instalments}</dd>
            <dt>Paid</dt>
            <dd data-testid="rd-paid">{r.paidCount} of {r.instalments}: <MoneyText value={r.paidIn} />{Number(r.lateChargesPaid) > 0 && <span className="muted"> · late charges <MoneyText value={r.lateChargesPaid} /></span>}</dd>
            <dt>Next</dt>
            <dd data-testid="rd-next">
              {next ? (
                <span>
                  No. {next.number}, due <DateText value={next.dueDate} />
                  {Number(next.lateCharge) > 0 ? <span>: <MoneyText value={next.amount} /> and a late charge of <MoneyText value={next.lateCharge} /> today</span> : <span>: <MoneyText value={next.amount} /></span>}
                </span>
              ) : (
                'Every instalment is paid'
              )}
            </dd>
            <dt>Late charge</dt>
            <dd>Rs {r.lateChargePer100} per Rs 100 for each month or part of a month late</dd>
          </dl>
          {(r.overdueCount ?? 0) > 0 && <Banner tone="warn">{r.overdueCount} instalment{r.overdueCount === 1 ? ' is' : 's are'} overdue. An instalment paid late earns interest from the month after it is paid.</Banner>}
          {canPay && next?.payableNow && (
            <div><Button variant="primary" onClick={() => setPaying(true)}>Pay instalment {next.number}</Button></div>
          )}
          <Table
            caption="Instalment schedule"
            columns={[
              { key: 'n', header: 'No.', render: (i) => i.number },
              { key: 'due', header: 'Due', render: (i) => <DateText value={i.dueDate} /> },
              { key: 'amt', header: 'Amount', numeric: true, render: (i) => <MoneyText value={i.amount} /> },
              { key: 'paid', header: 'Paid on', render: (i) => (i.paidOn ? <span><DateText value={i.paidOn} />{i.source && <span className="muted"> · {SOURCE_LABEL[i.source] ?? i.source}</span>}</span> : '—') },
              { key: 'late', header: 'Late charge', numeric: true, render: (i) => (i.lateCharge && Number(i.lateCharge) > 0 ? <MoneyText value={i.lateCharge} /> : '—') },
              {
                key: 'st',
                header: 'Status',
                render: (i) => (i.status === 'PAID' ? <Badge tone="ok">Paid</Badge> : i.status === 'OVERDUE' ? <Badge tone="warn">Overdue</Badge> : <span className="muted">Due</span>),
              },
            ]}
            rows={r.schedule ?? []}
            rowKey={(i) => String(i.number)}
          />
        </div>
      )}
      {paying && next && <PayInstalmentDialog account={account} number={next.number ?? 0} amount={next.amount ?? '0'} lateCharge={next.lateCharge ?? '0'} total={next.total ?? '0'} onClose={() => setPaying(false)} />}
    </Card>
  );
}

export function PayInstalmentDialog({ account, number, amount, lateCharge, total, onClose }: {
  account: DepositAccount; number: number; amount: string; lateCharge: string; total: string; onClose: () => void;
}) {
  const depositor = account.holders?.[0]?.customerId;
  const own = useDepositAccounts('CASA', { customerId: depositor, status: 'ACTIVE' }, !!depositor);
  const casa = (own.data ?? []).length > 0;
  const sources = casa ? ['ACCOUNT', 'CASH', 'BANK_TRANSFER'] : ['CASH', 'BANK_TRANSFER'];
  const [source, setSource] = useState(sources[0]);
  const [from, setFrom] = useState('');
  const [reference, setReference] = useState('');
  const m = usePayRdInstalment(account.id!);
  const toast = useMoneyToast();
  const src = sources.includes(source) ? source : sources[0];
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Instalment ${number} of deposit ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={m.isPending}
            onClick={() =>
              m.mutate(
                { source: src as 'CASH', fromAccountId: src === 'ACCOUNT' ? from || null : null, reference: reference.trim() || null },
                { onSuccess: (a) => (toast(a, `Instalment ${number}`), onClose()) },
              )
            }
          >
            Submit
          </Button>
        </>
      }
    >
      <p style={{ marginTop: 0 }} data-testid="instalment-total">
        <MoneyText value={amount} /> into the deposit{Number(lateCharge) > 0 && <span>, and a late charge of <MoneyText value={lateCharge} /></span>}: <strong><MoneyText value={total} /></strong> in all.
      </p>
      <div className="form-grid">
        <Select label="Paid" value={src} onChange={(e) => setSource(e.target.value)} options={sources.map((s) => ({ value: s, label: SOURCE_LABEL[s] }))} />
        {src === 'ACCOUNT' && (
          <Select
            label="Paying account"
            required
            placeholder="Choose an account of the depositor"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            options={(own.data ?? []).map((x) => ({ value: x.id!, label: `${x.accountNo} (${x.productCode}), balance ${x.balance ?? '0'}` }))}
          />
        )}
        <Input label={src === 'BANK_TRANSFER' ? 'UTR (the bank’s reference)' : 'Receipt or slip number'} required={src === 'BANK_TRANSFER'} maxLength={60} value={reference} onChange={(e) => setReference(e.target.value)} />
      </div>
      <Problems error={m.error} title="The instalment cannot be paid:" />
    </Dialog>
  );
}
