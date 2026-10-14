import { Link, useNavigate, useSearchParams } from 'react-router';
import { useDepositAccounts } from '../../api/depositHooks';
import { useMe } from '../../api/hooks';
import type { DepositAccountSummary, DepositFamily } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, DateText, EmptyState, ErrorBanner, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, humanize, type Column } from '../../ui';
import { NoDeposits, TYPE_LABEL, familyPath, pct, tenureText, useDepositModules } from './common';

const STATUSES: Record<DepositFamily, string[]> = { CASA: ['ACTIVE', 'CLOSED'], TERM_DEPOSIT: ['BOOKED', 'ACTIVE', 'MATURED', 'CLOSED', 'CANCELLED'] };

/** Savings and current accounts, or term deposits, in the user's branches. */
export function DepositAccountsPage({ family }: { family: DepositFamily }) {
  const me = useMe().data!;
  const modules = useDepositModules();
  const enabled = family === 'CASA' ? modules.casa : modules.td;
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? '';
  const navigate = useNavigate();
  const q = useDepositAccounts(family, { status }, enabled);
  if (!enabled) return <NoDeposits />;
  const term = family === 'TERM_DEPOSIT';
  const path = `/deposits/${familyPath(family)}`;
  const canOpen = hasPermission(me.permissions, term ? P.depositBook : P.casaOpen);
  const columns: Column<DepositAccountSummary>[] = [
    { key: 'no', header: term ? 'Deposit no.' : 'Account no.', render: (a) => <Link to={`${path}/${a.id}`} className="mono" onClick={(e) => e.stopPropagation()}>{a.accountNo}</Link> },
    { key: 'customer', header: term ? 'Depositor' : 'Holder', render: (a) => (<span>{a.customerName}{(a.holders ?? 1) > 1 ? <span className="muted"> and {(a.holders ?? 1) - 1} more</span> : null}<br /><span className="muted mono" style={{ fontSize: 12 }}>{a.customerNo}</span></span>) },
    { key: 'product', header: 'Product', render: (a) => (<span><span className="mono">{a.productCode}</span> <span className="muted">{TYPE_LABEL[a.productType ?? '']}</span></span>) },
    ...(term
      ? ([
          { key: 'amount', header: 'Amount', numeric: true, render: (a) => <MoneyText value={a.principal} /> },
          { key: 'tenure', header: 'Tenure', render: (a) => tenureText(a.tenureMonths, a.tenureDays) },
          { key: 'rate', header: 'Rate', numeric: true, render: (a) => pct(a.ratePercent) },
          { key: 'maturity', header: 'Matures', render: (a) => (a.maturityDate ? <DateText value={a.maturityDate} /> : <span className="muted">when funded</span>) },
        ] as Column<DepositAccountSummary>[])
      : ([
          { key: 'mode', header: 'Operated', render: (a) => humanize(a.operatingMode ?? '') },
          { key: 'balance', header: 'Balance', numeric: true, render: (a) => (<span><MoneyText value={a.balance} />{a.onHold && <><br /><span className="muted" style={{ fontSize: 12 }}>on hold</span></>}</span>) },
        ] as Column<DepositAccountSummary>[])),
    { key: 'status', header: 'Status', render: (a) => <StatusBadge status={a.status ?? ''} /> },
    { key: 'opened', header: term ? 'Booked' : 'Opened', render: (a) => <DateText value={a.openedOn} /> },
    { key: 'branch', header: 'Branch', render: (a) => a.branchCode },
  ];
  return (
    <div className="stack">
      <PageHeader
        title={term ? 'Term deposits' : 'Savings and current accounts'}
        subtitle={term ? 'A deposit is booked first and funded afterwards; interest starts on the funding date.' : 'Accounts open with no balance. Money in and out below ₹2,00,000 posts at once; from that amount a checker approves it.'}
        actions={canOpen && <Button variant="primary" onClick={() => navigate(`${path}/new`)}>{term ? 'Book a deposit' : 'Open an account'}</Button>}
      />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select
            label="Status"
            value={status}
            onChange={(e) => setParams(e.target.value ? new URLSearchParams({ status: e.target.value }) : new URLSearchParams())}
            options={[{ value: '', label: 'All' }, ...STATUSES[family].map((s) => ({ value: s, label: humanize(s) }))]}
          />
        </div>
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={q.error} />
        </div>
        {q.isLoading ? (
          <div style={{ padding: 16 }}><Spinner /></div>
        ) : (
          <Table
            caption={term ? 'Term deposits' : 'Savings and current accounts'}
            captionHidden
            columns={columns}
            rows={q.data ?? []}
            rowKey={(a) => a.id ?? ''}
            onRowClick={(a) => navigate(`${path}/${a.id}`)}
            rowLabel={(a) => `Open ${term ? 'deposit' : 'account'} ${a.accountNo}`}
            empty={<EmptyState title={status ? 'Nothing with this status' : term ? 'No term deposits yet' : 'No accounts yet'} />}
          />
        )}
      </Card>
    </div>
  );
}
