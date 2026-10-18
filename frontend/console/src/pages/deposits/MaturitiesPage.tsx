import { useState } from 'react';
import { Link } from 'react-router';
import { useDepositMaturities } from '../../api/depositHooks';
import { Banner, Card, DateText, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Spinner, StatusBadge, Table } from '../../ui';
import { INSTRUCTION_LABEL } from './DepositLifecycle';
import { NoDeposits, pct, useDepositModules } from './common';

/**
 * Term deposits maturing in a window (the next 30 days unless dates are given) and every matured deposit the day-end
 * could not pay or renew, with what is expected, the instruction, the depositor's consent and the notice sent (P4-4).
 */
export function MaturitiesPage() {
  const modules = useDepositModules();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const q = useDepositMaturities(from || null, to || null);
  if (!modules.td) return <NoDeposits />;
  const rows = q.data ?? [];
  const waiting = rows.filter((r) => r.status === 'MATURED');
  return (
    <div className="stack">
      <PageHeader
        title="Maturities"
        subtitle="Deposits maturing soon, and matured deposits waiting to be paid or renewed. The day-end pays or renews each on its maturity date as instructed, and sends the maturity notice beforehand."
      />
      <Card>
        <div className="form-grid">
          <Input label="Maturing from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} hint="Default: the business date" />
          <Input label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} hint="Default: 30 days on" />
        </div>
      </Card>
      <ErrorBanner error={q.error} />
      {waiting.length > 0 && <Banner tone="warn">{waiting.length} matured deposit{waiting.length === 1 ? ' is' : 's are'} waiting: open each to pay it out or renew it.</Banner>}
      {q.isLoading ? (
        <Spinner />
      ) : rows.length === 0 ? (
        <EmptyState title="No deposit matures in this window">No matured deposit is waiting either.</EmptyState>
      ) : (
        <Card flush>
          <Table
            caption="Maturities"
            captionHidden
            columns={[
              { key: 'no', header: 'Deposit', render: (r) => <Link to={`/deposits/term/${r.id}`} className="mono">{r.accountNo}</Link> },
              { key: 'st', header: 'Status', render: (r) => <StatusBadge status={r.status ?? ''} /> },
              { key: 'who', header: 'Depositor', render: (r) => <span>{r.customerName} <span className="muted mono">{r.customerNo}</span></span> },
              { key: 'mat', header: 'Matures', render: (r) => <DateText value={r.maturityDate} /> },
              { key: 'p', header: 'Principal', numeric: true, render: (r) => <MoneyText value={r.principal} /> },
              { key: 'rate', header: 'Rate', numeric: true, render: (r) => pct(r.ratePercent) },
              { key: 'exp', header: 'Expected', numeric: true, render: (r) => <MoneyText value={r.status === 'MATURED' ? r.balance : r.expectedAmount} /> },
              {
                key: 'ins',
                header: 'At maturity',
                render: (r) => (
                  <span>
                    {INSTRUCTION_LABEL[r.maturityInstruction ?? 'PAY_OUT']}
                    {r.consentReference && <span className="muted"> · consent {r.consentReference}</span>}
                    <br />
                    <span className="muted">
                      {r.payoutTo === 'CASA' ? `to ${r.payoutAccountNo}` : r.payoutBankAccountMasked ? `to bank account ${r.payoutBankAccountMasked}` : 'bank account not recorded'}
                    </span>
                  </span>
                ),
              },
              { key: 'n', header: 'Notice', render: (r) => (r.noticeOn ? <DateText value={r.noticeOn} /> : <span className="muted">Not sent</span>) },
              { key: 'why', header: 'Waiting because', render: (r) => r.waitingBecause ?? <span className="muted">—</span> },
            ]}
            rows={rows}
            rowKey={(r) => r.id ?? ''}
          />
        </Card>
      )}
    </div>
  );
}
