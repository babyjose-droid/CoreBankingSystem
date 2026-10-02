import { useState } from 'react';
import { useAmountLimits, useProposeAmountLimit } from '../../api/extraHooks';
import { useMe } from '../../api/hooks';
import type { AmountLimit, LimitTxnType } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { addDays } from '../../lib/dates';
import { isMoney } from '../../lib/money';
import { Badge, Button, Card, Checkbox, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Select, Spinner, Table, humanize } from '../../ui';
import { useProposalToast } from '../proposal';

export const LIMIT_TXN_TYPES: LimitTxnType[] = ['LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'LOAN_PRECLOSURE', 'LOAN_WAIVER', 'FEE_WAIVER', 'VOUCHER'];

export function AmountLimitsPage() {
  const me = useMe().data!;
  const canPropose = hasPermission(me.permissions, P.limitPropose);
  const [currentOnly, setCurrentOnly] = useState(true);
  const q = useAmountLimits(currentOnly);
  const [editing, setEditing] = useState<AmountLimit | 'new' | null>(null);
  return (
    <div className="stack">
      <PageHeader
        title="Role amount limits"
        subtitle="What each role may put through in one transaction and in one business day. The most permissive of a user's roles applies; a role without a limit is not limited."
        actions={canPropose && <Button variant="primary" onClick={() => setEditing('new')}>Propose limit</Button>}
      />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Checkbox label="Only limits in force today" checked={currentOnly} onChange={(e) => setCurrentOnly(e.target.checked)} />
        </div>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Role amount limits"
            captionHidden
            columns={[
              { key: 'role', header: 'Role', render: (l) => <span className="mono">{l.roleName}</span> },
              { key: 'txn', header: 'Transaction', render: (l) => humanize(l.txnType) },
              { key: 'per', header: 'Per transaction', numeric: true, render: (l) => <MoneyText value={l.perTransactionMax} /> },
              { key: 'day', header: 'Per day', numeric: true, render: (l) => (l.perDayMax ? <MoneyText value={l.perDayMax} /> : <span className="muted">No day limit</span>) },
              { key: 'from', header: 'Effective', render: (l) => (<span><DateText value={l.effectiveFrom} /> – {l.effectiveTo ? <DateText value={l.effectiveTo} /> : 'open'}</span>) },
              { key: 'force', header: 'Status', render: (l) => (l.inForce ? <Badge tone="ok">In force</Badge> : <Badge>{l.effectiveFrom > me.businessDate ? 'Future' : 'Ended'}</Badge>) },
              ...(canPropose
                ? [{ key: 'act', header: <span className="sr-only">Actions</span>, render: (l: AmountLimit) => (l.inForce ? <Button size="sm" aria-label={`Change ${l.roleName} ${humanize(l.txnType)} limit`} onClick={() => setEditing(l)}>Change</Button> : null) }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(l) => l.id}
            empty={<EmptyState title="No amount limits">Without limits, roles are not limited by amount.</EmptyState>}
          />
        )}
      </Card>
      {editing && <LimitDialog initial={editing === 'new' ? null : editing} businessDate={me.businessDate} onClose={() => setEditing(null)} />}
    </div>
  );
}

function LimitDialog({ initial, businessDate, onClose }: { initial: AmountLimit | null; businessDate: string; onClose: () => void }) {
  const propose = useProposeAmountLimit();
  const toast = useProposalToast();
  const [l, setL] = useState({
    roleName: initial?.roleName ?? '',
    txnType: initial?.txnType ?? ('LOAN_REPAYMENT' as LimitTxnType),
    perTransactionMax: initial?.perTransactionMax ?? '',
    perDayMax: initial?.perDayMax ?? '',
    effectiveFrom: addDays(businessDate, initial ? 1 : 0),
    effectiveTo: '',
  });
  const [touched, setTouched] = useState(false);
  const money = (v: string) => v.replace(/[,\s₹]/g, '');
  const per = money(l.perTransactionMax);
  const day = money(l.perDayMax);
  const errs = {
    roleName: !/^[A-Za-z0-9][A-Za-z0-9_.:-]{1,63}$/.test(l.roleName) ? 'A realm role, e.g. MAKER' : null,
    perTransactionMax: !isMoney(per) || Number(per) <= 0 ? 'Enter a positive amount' : null,
    perDayMax: day && (!isMoney(day) || Number(day) <= 0) ? 'Enter a positive amount or leave empty' : day && isMoney(per) && Number(day) < Number(per) ? 'Cannot be below the per-transaction limit' : null,
    effectiveFrom: !l.effectiveFrom ? 'Required' : l.effectiveFrom < businessDate ? 'Not before the business date' : null,
    effectiveTo: l.effectiveTo && l.effectiveTo < l.effectiveFrom ? 'On or after effective from' : null,
  };
  const valid = Object.values(errs).every((e) => !e);
  const err = (k: keyof typeof errs) => (touched ? errs[k] : null);
  return (
    <Dialog
      open
      onClose={onClose}
      title={initial ? `Change ${initial.roleName} ${humanize(initial.txnType).toLowerCase()} limit` : 'Propose amount limit'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              propose.mutate(
                { roleName: l.roleName, txnType: l.txnType, perTransactionMax: per, perDayMax: day || null, effectiveFrom: l.effectiveFrom, effectiveTo: l.effectiveTo || null },
                { onSuccess: (a) => (toast(a, 'Amount limit'), onClose()) },
              );
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>The new limit takes over from its effective date; the limit then in force ends the day before.</p>
        <div className="form-grid">
          <Input label="Role" required disabled={!!initial} className="mono" value={l.roleName} onChange={(e) => setL({ ...l, roleName: e.target.value.trim() })} hint="As in the access token, e.g. MAKER" error={err('roleName')} />
          <Select label="Transaction type" disabled={!!initial} value={l.txnType} onChange={(e) => setL({ ...l, txnType: e.target.value as LimitTxnType })} options={LIMIT_TXN_TYPES.map((t) => ({ value: t, label: humanize(t) }))} />
          <Input label="Per transaction" required numeric value={l.perTransactionMax} onChange={(e) => setL({ ...l, perTransactionMax: e.target.value })} error={err('perTransactionMax')} />
          <Input label="Per day" numeric value={l.perDayMax} onChange={(e) => setL({ ...l, perDayMax: e.target.value })} hint="Empty = no day limit" error={err('perDayMax')} />
          <Input label="Effective from" required type="date" min={businessDate} value={l.effectiveFrom} onChange={(e) => setL({ ...l, effectiveFrom: e.target.value })} error={err('effectiveFrom')} />
          <Input label="Effective to" type="date" min={l.effectiveFrom} value={l.effectiveTo} onChange={(e) => setL({ ...l, effectiveTo: e.target.value })} hint="Empty = open-ended" error={err('effectiveTo')} />
        </div>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
