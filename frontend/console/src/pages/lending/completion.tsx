import { useState } from 'react';
import { useLoanTranches, usePreviewSanctionChange, useProposeNpaOverride, useProposeNpaRelease, useProposeSanctionChange, useSimulateTransaction } from '../../api/lendingHooks';
import type { AssetClass, Loan, NpaOverrideRequest, SanctionChangeRequest, TransactionSimulationRequest } from '../../api/types';
import { addDays } from '../../lib/dates';
import { isZero } from '../../lib/money';
import { Badge, Banner, Button, Card, Checkbox, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, Select, Spinner, Table, Textarea, humanize } from '../../ui';
import { useProposalToast } from '../proposal';
import { AssetClassBadge, assetClassLabel, moneyInput } from './common';
import { ScheduleTable } from './KfsView';

export const IRREVERSIBLE_NOTE = 'Tranche draws, sanction changes and NPA overrides cannot be reversed, and no transaction before one of them can be reversed either.';

// ---------------------------------------------------------------- tranches
export function TranchesTab({ loan }: { loan: Loan }) {
  const q = useLoanTranches(loan.id);
  if (q.isLoading) return <Spinner />;
  if (q.error) return <ErrorBanner error={q.error} />;
  const t = q.data!;
  return (
    <div className="stack">
      <div className="stat-grid" data-testid="tranche-summary">
        <div className="stat">
          <div className="stat__label">Sanctioned</div>
          <div className="stat__value"><MoneyText value={t.sanctionedAmount} /></div>
        </div>
        <div className="stat">
          <div className="stat__label">Disbursed</div>
          <div className="stat__value"><MoneyText value={t.disbursedAmount} /></div>
        </div>
        <div className="stat">
          <div className="stat__label">Undrawn</div>
          <div className="stat__value"><MoneyText value={t.undrawnAmount} /></div>
          <div className="stat__sub">{t.multipleDisbursements ? (t.preEmi ? 'Pre-EMI: interest only on the amount drawn until fully drawn' : 'EMIs on the amount drawn') : 'Product is disbursed in one go'}</div>
        </div>
      </div>
      <Card flush>
        <Table
          caption="Tranches"
          captionHidden
          columns={[
            { key: 'no', header: '#', numeric: true, render: (x) => x.trancheNo },
            { key: 'date', header: 'Business date', render: (x) => <DateText value={x.businessDate} /> },
            { key: 'amount', header: 'Amount', numeric: true, render: (x) => <MoneyText value={x.amount} /> },
            { key: 'fees', header: 'Fees deducted', numeric: true, render: (x) => <MoneyText value={x.feesDeducted} /> },
            { key: 'int', header: 'Interest deducted', numeric: true, render: (x) => <MoneyText value={x.interestDeducted} /> },
            { key: 'net', header: 'Net paid out', numeric: true, render: (x) => <MoneyText value={x.netDisbursed} /> },
            { key: 'by', header: 'By', render: (x) => <span className="mono">{x.createdBy}</span> },
          ]}
          rows={t.tranches ?? []}
          rowKey={(x) => String(x.trancheNo)}
          empty={<EmptyState title="Not disbursed yet" />}
        />
      </Card>
      <p className="muted" style={{ margin: 0, fontSize: 12 }}>{IRREVERSIBLE_NOTE}</p>
    </div>
  );
}

// ---------------------------------------------------------------- what-if simulation
type SimType = TransactionSimulationRequest['type'];

export function WhatIfDialog({ loan, businessDate, onClose }: { loan: Loan; businessDate: string; onClose: () => void }) {
  const sim = useSimulateTransaction(loan.id!);
  const [f, setF] = useState({ type: 'REPAYMENT' as SimType, amount: loan.emi ?? '', mode: 'REDUCE_TENURE' as 'REDUCE_EMI' | 'REDUCE_TENURE', onDate: businessDate });
  const [touched, setTouched] = useState(false);
  const needsAmount = f.type !== 'PRECLOSURE';
  const amount = moneyInput(f.amount);
  const errs = {
    amount: needsAmount && (!amount || Number(amount) <= 0) ? 'Enter an amount greater than zero' : null,
    onDate: !f.onDate || f.onDate < businessDate || f.onDate > addDays(businessDate, 366) ? 'Today or up to 366 days ahead' : null,
  };
  const r = sim.data;
  const set = (patch: Partial<typeof f>) => (setF((x) => ({ ...x, ...patch })), sim.reset());
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={`What if… on loan ${loan.loanNo}`}
      footer={
        <>
          <Button onClick={onClose}>Close</Button>
          <Button
            variant="primary"
            loading={sim.isPending}
            onClick={() => {
              setTouched(true);
              if (errs.amount || errs.onDate) return;
              sim.mutate({ type: f.type, onDate: f.onDate, ...(needsAmount ? { amount: amount! } : {}), ...(f.type === 'PREPAYMENT' ? { mode: f.mode } : {}) });
            }}
          >
            Simulate
          </Button>
        </>
      }
    >
      <div className="stack">
        <Banner tone="info">A simulation only: nothing is posted or stored. For a future date the day-ends until then (demands, accrual, penal charges, classification) are run on a copy first.</Banner>
        <div className="form-grid">
          <Select
            label="Transaction"
            value={f.type}
            onChange={(e) => set({ type: e.target.value as SimType })}
            options={[
              { value: 'REPAYMENT', label: 'Repayment (receipt)' },
              { value: 'PREPAYMENT', label: 'Part-prepayment' },
              { value: 'PRECLOSURE', label: 'Pre-closure' },
            ]}
          />
          {needsAmount && <Input label="Amount" required numeric value={f.amount} onChange={(e) => set({ amount: e.target.value })} error={touched ? errs.amount : null} />}
          {f.type === 'PREPAYMENT' && (
            <Select label="Rebuild schedule" value={f.mode} onChange={(e) => set({ mode: e.target.value as typeof f.mode })} options={[{ value: 'REDUCE_TENURE', label: 'Keep EMI, reduce tenure' }, { value: 'REDUCE_EMI', label: 'Keep tenure, reduce EMI' }]} />
          )}
          <Input label="On date" type="date" required min={businessDate} max={addDays(businessDate, 366)} value={f.onDate} onChange={(e) => set({ onDate: e.target.value })} error={touched ? errs.onDate : null} />
        </div>
        <ErrorBanner error={sim.error} />
        {r && (
          <div className="stack" data-testid="whatif-result">
            {r.type === 'REPAYMENT' && (
              <>
                <dl className="kv">
                  <dt>Dues before</dt>
                  <dd><MoneyText value={r.duesBefore} /></dd>
                  <dt>Dues after</dt>
                  <dd><MoneyText value={r.duesAfter} /></dd>
                  <dt>Kept as advance</dt>
                  <dd><MoneyText value={r.advance} /></dd>
                </dl>
                <Table
                  caption="Appropriation of the receipt"
                  columns={[
                    { key: 'ref', header: 'Against', render: (a) => (/^D\d+$/.test(a.ref ?? '') ? `Instalment ${a.ref!.slice(1)}` : `Charge ${a.ref}`) },
                    { key: 'comp', header: 'Component', render: (a) => humanize(a.component ?? '') },
                    { key: 'amt', header: 'Amount', numeric: true, render: (a) => <MoneyText value={a.amount} /> },
                  ]}
                  rows={r.allocations ?? []}
                  rowKey={(a) => `${a.ref}-${a.component}`}
                  empty={<EmptyState title="Nothing is due: the whole receipt is kept as an advance" />}
                />
              </>
            )}
            {r.type === 'PREPAYMENT' && (
              <dl className="kv">
                <dt>Instalment</dt>
                <dd><MoneyText value={r.instalmentBefore} /> → <MoneyText value={r.instalmentAfter} /></dd>
                <dt>Instalments left</dt>
                <dd>{r.remainingBefore} → {r.remainingAfter}</dd>
                <dt>Fee charged</dt>
                <dd><MoneyText value={r.feeCharged} /></dd>
              </dl>
            )}
            {r.type === 'PRECLOSURE' && (
              <dl className="kv">
                <dt>Principal</dt>
                <dd><MoneyText value={r.principal} /></dd>
                <dt>Overdue instalments</dt>
                <dd><MoneyText value={r.overdueDues} /></dd>
                <dt>Interest accrued</dt>
                <dd><MoneyText value={r.accruedInterest} /></dd>
                <dt>Charges</dt>
                <dd><MoneyText value={r.charges} /></dd>
                <dt>Foreclosure fee</dt>
                <dd><MoneyText value={r.foreclosureFee} /></dd>
                <dt>Amount to close on the date</dt>
                <dd data-testid="whatif-total"><strong><MoneyText value={r.total} /></strong></dd>
              </dl>
            )}
            <dl className="kv">
              <dt>Principal outstanding after</dt>
              <dd data-testid="whatif-outstanding"><MoneyText value={r.principalOutstandingAfter} /></dd>
              <dt>DPD and class after</dt>
              <dd>
                {r.dpdAfter ?? 0} · <AssetClassBadge value={r.assetClassAfter as AssetClass} /> · {humanize(r.statusAfter ?? '')}
              </dd>
            </dl>
            {r.schedule && (
              <Card title="Schedule afterwards" flush headingLevel={3}>
                <ScheduleTable rows={r.schedule} caption="Schedule afterwards" />
              </Card>
            )}
          </div>
        )}
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- sanction change
export function SanctionChangeDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const preview = usePreviewSanctionChange(loan.id!);
  const propose = useProposeSanctionChange(loan.id!);
  const toast = useProposalToast();
  const hasUndrawn = !!loan.undrawnAmount && !isZero(loan.undrawnAmount);
  const [cancel, setCancel] = useState(false);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const clean = moneyInput(amount);
  const request: SanctionChangeRequest = cancel ? { cancelUndrawn: true } : { newAmount: clean ?? amount };
  const key = JSON.stringify(request);
  const [previewed, setPreviewed] = useState<string | null>(null);
  const fresh = !!preview.data && previewed === key;
  const amountError = !cancel && (!clean || Number(clean) <= 0) ? 'Enter the new sanctioned amount' : null;
  const p = preview.data;
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={`Change the sanctioned amount of ${loan.loanNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            loading={preview.isPending}
            onClick={() => {
              setTouched(true);
              if (amountError) return;
              propose.reset();
              preview.mutate(request, { onSuccess: () => setPreviewed(key) });
            }}
          >
            Preview
          </Button>
          <Button
            variant="primary"
            disabled={!fresh}
            title={fresh ? undefined : 'Preview the change first'}
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!reason.trim()) return;
              propose.mutate({ ...request, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Sanction change'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Sanctioned <MoneyText value={loan.amount} />, disbursed <MoneyText value={loan.disbursedAmount} />, undrawn <MoneyText value={loan.undrawnAmount} />. A reduction can only take away undrawn amount.{' '}
          {loan.topUpAllowed ? 'A top-up needs a standard account with no unpaid dues; the extra amount is then paid out as a tranche.' : 'This product does not allow a top-up.'}
        </p>
        <Banner tone="warn">A sanction change cannot be reversed, and no earlier transaction can be reversed after it.</Banner>
        {hasUndrawn && <Checkbox label="Cancel the whole undrawn amount (bring the sanction down to the amount disbursed)" checked={cancel} onChange={(e) => setCancel(e.target.checked)} />}
        {!cancel && <Input label="New sanctioned amount" required numeric value={amount} onChange={(e) => setAmount(e.target.value)} error={touched ? amountError : null} />}
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched && fresh && !reason.trim() ? 'A reason is required to propose' : null} />
        <ErrorBanner error={preview.error ?? propose.error} />
        {p && !fresh && <Banner tone="info">The change was edited since the last preview. Preview again before proposing.</Banner>}
        {p && fresh && (
          <div className="stack" data-testid="sanction-preview">
            <dl className="kv">
              <dt>Sanctioned</dt>
              <dd>
                <MoneyText value={p.sanctionedBefore} /> → <strong><MoneyText value={p.sanctionedAfter} /></strong> {p.topUp ? <Badge tone="accent">Top-up</Badge> : <Badge>Reduction</Badge>}
              </dd>
              <dt>Disbursed</dt>
              <dd><MoneyText value={p.disbursed} /></dd>
              <dt>Undrawn afterwards</dt>
              <dd><MoneyText value={p.undrawnAfter} /></dd>
            </dl>
            {p.note && <p className="muted" style={{ margin: 0 }}>{p.note}</p>}
            <Card title="Schedule afterwards" flush headingLevel={3}>
              <ScheduleTable rows={p.schedule ?? []} caption="Schedule afterwards" />
            </Card>
          </div>
        )}
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- manual NPA override
const NPA_CLASSES: NpaOverrideRequest['assetClass'][] = ['SUBSTANDARD', 'DOUBTFUL1', 'DOUBTFUL2', 'DOUBTFUL3', 'LOSS'];

export function NpaOverrideDialog({ loan, businessDate, onClose }: { loan: Loan; businessDate: string; onClose: () => void }) {
  const propose = useProposeNpaOverride(loan.id!);
  const toast = useProposalToast();
  const [f, setF] = useState({ assetClass: 'SUBSTANDARD' as NpaOverrideRequest['assetClass'], until: '', reason: '' });
  const [touched, setTouched] = useState(false);
  const errs = { until: !f.until ? 'Choose the expiry date' : f.until <= businessDate ? 'Must be after the business date' : null, reason: !f.reason.trim() ? 'A reason is required' : null };
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Mark loan ${loan.loanNo} as NPA`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="danger"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (errs.until || errs.reason) return;
              propose.mutate({ assetClass: f.assetClass, until: f.until, reason: f.reason.trim() }, { onSuccess: (a) => (toast(a, 'NPA override'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <Banner tone="warn">
          <span>
            A manual mark downgrades the account to an NPA class or holds it there until the expiry; it never upgrades. It needs <strong>two different checkers</strong> and cannot be reversed. A downgrade from a
            performing class moves unrealised interest to suspense.
          </span>
        </Banner>
        <p className="muted" style={{ margin: 0 }}>Now: {assetClassLabel(loan.assetClass)}. While the mark holds, day-end can make the class worse but not better. LOSS is permanent.</p>
        <div className="form-grid">
          <Select label="Hold at class" value={f.assetClass} onChange={(e) => setF({ ...f, assetClass: e.target.value as typeof f.assetClass })} options={NPA_CLASSES.map((c) => ({ value: c, label: assetClassLabel(c) }))} />
          <Input label="Until" required type="date" min={addDays(businessDate, 1)} value={f.until} onChange={(e) => setF({ ...f, until: e.target.value })} error={touched ? errs.until : null} />
        </div>
        <Textarea label="Reason" required maxLength={500} value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} error={touched ? errs.reason : null} />
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}

export function NpaReleaseDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const propose = useProposeNpaRelease(loan.id!);
  const toast = useProposalToast();
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Release the NPA mark on ${loan.loanNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!reason.trim()) return;
              propose.mutate(reason.trim(), { onSuccess: (a) => (toast(a, 'Release of the NPA mark'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Held at {assetClassLabel(loan.overrideClass as AssetClass)} until <DateText value={loan.overrideUntil} />. A release is refused while the account has unpaid dues (an NPA is upgraded only when all arrears are
          cleared), needs two different checkers and posts nothing: the next day-end classifies the account by the normal rules.
        </p>
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched && !reason.trim() ? 'A reason is required' : null} />
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
