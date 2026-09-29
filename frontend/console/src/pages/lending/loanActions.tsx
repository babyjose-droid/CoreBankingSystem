import { useState } from 'react';
import {
  useAcceptKfs,
  useCancelLoan,
  useCancellationQuote,
  useChargeLoanFee,
  useDisburseLoan,
  useFreezeLoan,
  useLoanProduct,
  usePrecloseLoan,
  usePreclosureQuote,
  usePrepayLoan,
  useRepayLoan,
  useReverseLoanTxn,
  useWaiveLoanCharge,
} from '../../api/lendingHooks';
import type { Loan, LoanCharge, LoanTxn } from '../../api/types';
import { formatDate, ISO_DATE } from '../../lib/dates';
import { formatINR, isMoney, isZero } from '../../lib/money';
import { IFSC_PATTERN } from '../../lib/mask';
import { Banner, Button, Dialog, ErrorBanner, Input, MoneyText, Select, Spinner, Textarea, humanize, useToast } from '../../ui';
import { useProposalToast } from '../proposal';
import { moneyInput } from './common';

export type LoanAction =
  | { kind: 'disburse' }
  | { kind: 'repay' }
  | { kind: 'prepay' }
  | { kind: 'preclose' }
  | { kind: 'cancel' }
  | { kind: 'charge' }
  | { kind: 'freeze' }
  | { kind: 'unfreeze' }
  | { kind: 'kfs' }
  | { kind: 'waive'; charge: LoanCharge }
  | { kind: 'reverse'; txn: LoanTxn };

export function LoanActionDialog({ action, loan, businessDate, onClose }: { action: LoanAction; loan: Loan; businessDate: string; onClose: () => void }) {
  switch (action.kind) {
    case 'disburse':
      return <DisburseDialog loan={loan} onClose={onClose} />;
    case 'repay':
      return <RepayDialog loan={loan} businessDate={businessDate} onClose={onClose} />;
    case 'prepay':
      return <PrepayDialog loan={loan} onClose={onClose} />;
    case 'preclose':
      return <PreclosureDialog loan={loan} onClose={onClose} />;
    case 'cancel':
      return <CancellationDialog loan={loan} onClose={onClose} />;
    case 'charge':
      return <ChargeDialog loan={loan} onClose={onClose} />;
    case 'freeze':
    case 'unfreeze':
      return <FreezeDialog loan={loan} freeze={action.kind === 'freeze'} onClose={onClose} />;
    case 'kfs':
      return <KfsAcceptanceDialog loan={loan} onClose={onClose} />;
    case 'waive':
      return <WaiveDialog loan={loan} charge={action.charge} onClose={onClose} />;
    case 'reverse':
      return <ReverseDialog loan={loan} txn={action.txn} onClose={onClose} />;
  }
}

function Footer({ onClose, label, onSubmit, loading, variant = 'primary', disabled }: { onClose: () => void; label: string; onSubmit: () => void; loading: boolean; variant?: 'primary' | 'danger'; disabled?: boolean }) {
  return (
    <>
      <Button onClick={onClose}>Cancel</Button>
      <Button variant={variant} loading={loading} disabled={disabled} onClick={onSubmit}>
        {label}
      </Button>
    </>
  );
}

const amountError = (v: string) => {
  const m = moneyInput(v);
  return !m || Number(m) <= 0 ? 'Enter an amount greater than zero' : null;
};

// ---------------------------------------------------------------- disbursement
function DisburseDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const [b, setB] = useState({ mode: 'IMPS', beneficiaryName: loan.customerName ?? '', beneficiaryAccount: '', ifsc: '' });
  const [touched, setTouched] = useState(false);
  const m = useDisburseLoan(loan.id!);
  const proposal = useProposalToast();
  const toast = useToast();
  const cash = b.mode === 'CASH';
  const errs = {
    beneficiaryName: !cash && !b.beneficiaryName.trim() ? 'Required' : null,
    beneficiaryAccount: !cash && !/^\d{6,18}$/.test(b.beneficiaryAccount) ? '6-18 digits' : null,
    ifsc: !cash && !IFSC_PATTERN.test(b.ifsc) ? 'IFSC looks like HDFC0001234' : null,
  };
  const valid = Object.values(errs).every((e) => !e);
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Disburse loan ${loan.loanNo}`}
      footer={
        <Footer
          onClose={onClose}
          label="Submit for approval"
          loading={m.isPending}
          onSubmit={() => {
            setTouched(true);
            if (!valid) return;
            m.mutate(cash ? { mode: b.mode } : { mode: b.mode, beneficiaryName: b.beneficiaryName.trim(), beneficiaryAccount: b.beneficiaryAccount, ifsc: b.ifsc }, {
              onSuccess: (r) => {
                if (r.kind === 'pending') proposal(r.approval, 'Disbursement');
                else toast({ tone: 'success', message: `Loan ${r.loan.loanNo} disbursed.` });
                onClose();
              },
            });
          }}
        />
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Disburses <MoneyText value={loan.amount} /> (net of deducted fees) on the business date. A checker approves before money moves.
        </p>
        <div className="form-grid">
          <Select label="Mode" value={b.mode} onChange={(e) => setB({ ...b, mode: e.target.value })} options={['IMPS', 'NEFT', 'CASH'].map((x) => ({ value: x, label: x }))} />
          {!cash && (
            <>
              <Input label="Beneficiary name" required value={b.beneficiaryName} onChange={(e) => setB({ ...b, beneficiaryName: e.target.value })} error={touched ? errs.beneficiaryName : null} />
              <Input label="Beneficiary account" required className="mono" inputMode="numeric" value={b.beneficiaryAccount} onChange={(e) => setB({ ...b, beneficiaryAccount: e.target.value.replace(/\D/g, '') })} error={touched ? errs.beneficiaryAccount : null} />
              <Input label="IFSC" required className="mono" value={b.ifsc} onChange={(e) => setB({ ...b, ifsc: e.target.value.toUpperCase() })} error={touched ? errs.ifsc : null} />
            </>
          )}
        </div>
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- repayment
function RepayDialog({ loan, businessDate, onClose }: { loan: Loan; businessDate: string; onClose: () => void }) {
  const suggested = loan.overdueAmount && isMoney(loan.overdueAmount) && !isZero(loan.overdueAmount) ? loan.overdueAmount : loan.emi ?? '';
  const [b, setB] = useState({ amount: suggested, valueDate: businessDate, mode: 'CASH', reference: '' });
  const [touched, setTouched] = useState(false);
  const m = useRepayLoan(loan.id!);
  const toast = useToast();
  const errs = {
    amount: amountError(b.amount),
    valueDate: !ISO_DATE.test(b.valueDate) ? 'Required' : b.valueDate > businessDate ? 'Cannot be after the business date' : null,
  };
  const valid = !errs.amount && !errs.valueDate;
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Repayment on ${loan.loanNo}`}
      footer={
        <Footer
          onClose={onClose}
          label="Record repayment"
          loading={m.isPending}
          onSubmit={() => {
            setTouched(true);
            if (!valid) return;
            const amount = moneyInput(b.amount)!;
            m.mutate(
              { amount, valueDate: b.valueDate, mode: b.mode, ...(b.reference.trim() ? { reference: b.reference.trim() } : {}) },
              { onSuccess: () => (toast({ tone: 'success', message: `Repayment of ${formatINR(amount)} recorded.` }), onClose()) },
            );
          }}
        />
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Appropriated to overdue interest and principal first, then charges; any excess is kept as an advance for the next instalment.
          {loan.overdueAmount && !isZero(loan.overdueAmount) ? <> Overdue now: <MoneyText value={loan.overdueAmount} />.</> : null}
        </p>
        <div className="form-grid">
          <Input label="Amount" required numeric value={b.amount} onChange={(e) => setB({ ...b, amount: e.target.value })} error={touched ? errs.amount : null} />
          <Input label="Value date" type="date" required max={businessDate} value={b.valueDate} onChange={(e) => setB({ ...b, valueDate: e.target.value })} error={touched ? errs.valueDate : null} />
          <Select label="Mode" value={b.mode} onChange={(e) => setB({ ...b, mode: e.target.value })} options={['CASH', 'NACH', 'UPI', 'NEFT'].map((x) => ({ value: x, label: x }))} />
          <Input label="Reference" value={b.reference} onChange={(e) => setB({ ...b, reference: e.target.value })} hint="UTR, receipt or mandate reference" />
        </div>
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- part-prepayment
function PrepayDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const [amount, setAmount] = useState('');
  const [mode, setMode] = useState<'REDUCE_EMI' | 'REDUCE_TENURE'>('REDUCE_TENURE');
  const [touched, setTouched] = useState(false);
  const m = usePrepayLoan(loan.id!);
  const toast = useToast();
  const err = amountError(amount);
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Part-prepayment on ${loan.loanNo}`}
      footer={
        <Footer
          onClose={onClose}
          label="Record part-prepayment"
          loading={m.isPending}
          onSubmit={() => {
            setTouched(true);
            if (err) return;
            m.mutate(
              { amount: moneyInput(amount)!, mode },
              { onSuccess: (l) => (toast({ tone: 'success', message: `Part-prepayment recorded. ${mode === 'REDUCE_EMI' && l.emi ? `New EMI ${formatINR(l.emi)}.` : 'Tenure reduced.'}` }), onClose()) },
            );
          }}
        />
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Reduces principal outstanding (<MoneyText value={loan.principalOutstanding} />) and rebuilds the future schedule. Overdue dues must be cleared first.
        </p>
        <div className="form-grid">
          <Input label="Amount" required numeric value={amount} onChange={(e) => setAmount(e.target.value)} error={touched ? err : null} />
          <Select
            label="Rebuild schedule"
            value={mode}
            onChange={(e) => setMode(e.target.value as typeof mode)}
            options={[
              { value: 'REDUCE_TENURE', label: 'Keep EMI, reduce tenure' },
              { value: 'REDUCE_EMI', label: 'Keep tenure, reduce EMI' },
            ]}
          />
        </div>
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- pre-closure
function PreclosureDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const quote = usePreclosureQuote(loan.id!, true);
  const m = usePrecloseLoan(loan.id!);
  const toast = useToast();
  const q = quote.data;
  const rows: Array<[string, string | undefined, boolean?]> = q
    ? [
        ['Principal outstanding', q.principal],
        ['Overdue instalments', q.overdueDues],
        ['Interest accrued to date', q.accruedInterest],
        ['Unpaid charges', q.charges],
        ['Foreclosure fee (incl. GST)', q.foreclosureFee],
        ['Advance adjusted', q.advanceAdjusted && !isZero(q.advanceAdjusted) ? `-${q.advanceAdjusted}` : q.advanceAdjusted],
      ]
    : [];
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Pre-close loan ${loan.loanNo}`}
      footer={
        <Footer
          onClose={onClose}
          label={q?.total ? `Pre-close for ${formatINR(q.total)}` : 'Pre-close'}
          variant="danger"
          disabled={!q?.total}
          loading={m.isPending}
          onSubmit={() => q?.total && m.mutate(q.total, { onSuccess: () => (toast({ tone: 'success', message: `Loan ${loan.loanNo} pre-closed.` }), onClose()) })}
        />
      }
    >
      <div className="stack">
        {quote.isLoading && <Spinner label="Getting the quote" />}
        <ErrorBanner error={quote.error} />
        {q && (
          <>
            <p className="muted" style={{ margin: 0 }}>Quote as of {formatDate(q.asOf)}. The posting uses exactly these figures.</p>
            <table className="table" aria-label="Pre-closure quote">
              <tbody>
                {rows.map(([label, value]) => (
                  <tr key={label}>
                    <th scope="row">{label}</th>
                    <td className="num"><MoneyText value={value} /></td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">Total to collect</th>
                  <td className="num" data-testid="preclosure-total"><MoneyText value={q.total} /></td>
                </tr>
              </tfoot>
            </table>
          </>
        )}
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- cancellation (cooling-off)
function CancellationDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const quote = useCancellationQuote(loan.id!, true);
  const m = useCancelLoan(loan.id!);
  const toast = useToast();
  const total = quote.data?.total;
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Cancel loan ${loan.loanNo} (cooling-off)`}
      footer={
        <Footer
          onClose={onClose}
          label={total ? `Cancel for ${formatINR(total)}` : 'Cancel loan'}
          variant="danger"
          disabled={!total}
          loading={m.isPending}
          onSubmit={() => total && m.mutate(total, { onSuccess: () => (toast({ tone: 'success', message: `Loan ${loan.loanNo} cancelled.` }), onClose()) })}
        />
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Within the cooling-off period the borrower exits by repaying the principal and interest for the days used. Disclosed fees are retained.
        </p>
        {quote.isLoading && <Spinner label="Getting the quote" />}
        <ErrorBanner error={quote.error} />
        {total && (
          <dl className="kv">
            <dt>As of</dt>
            <dd>{formatDate(quote.data?.asOf)}</dd>
            <dt>Amount to collect</dt>
            <dd data-testid="cancellation-total"><MoneyText value={total} /></dd>
          </dl>
        )}
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- fee charge
function ChargeDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const product = useLoanProduct(loan.productCode);
  const fees = (product.data?.fees ?? []).filter((f) => f.event !== 'DISBURSEMENT' && f.event !== 'PRECLOSURE');
  const [feeCode, setFeeCode] = useState('');
  const [base, setBase] = useState('');
  const [touched, setTouched] = useState(false);
  const m = useChargeLoanFee(loan.id!);
  const toast = useToast();
  const errs = { feeCode: !feeCode ? 'Select a fee' : null, base: base && !moneyInput(base) ? 'Enter an amount' : null };
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Charge a fee on ${loan.loanNo}`}
      footer={
        <Footer
          onClose={onClose}
          label="Charge fee"
          loading={m.isPending}
          onSubmit={() => {
            setTouched(true);
            if (errs.feeCode || errs.base) return;
            m.mutate({ feeCode, ...(base ? { base: moneyInput(base)! } : {}) }, { onSuccess: () => (toast({ tone: 'success', message: 'Fee charged.' }), onClose()) });
          }}
        />
      }
    >
      <div className="stack">
        {product.isLoading ? (
          <Spinner />
        ) : fees.length === 0 ? (
          <Banner tone="info">The product has no fees that can be charged manually.</Banner>
        ) : (
          <div className="form-grid">
            <Select label="Fee" required value={feeCode} placeholder="Select…" onChange={(e) => setFeeCode(e.target.value)} options={fees.map((f) => ({ value: f.code, label: `${f.name} (${humanize(f.event)})` }))} error={touched ? errs.feeCode : null} />
            <Input label="Base amount" numeric value={base} onChange={(e) => setBase(e.target.value)} hint="Optional; defaults to the EMI (bounce) or principal outstanding" error={touched ? errs.base : null} />
          </div>
        )}
        <p className="muted" style={{ margin: 0, fontSize: 12 }}>GST is added per the fee rule (CGST + SGST within the state, IGST otherwise).</p>
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- waiver (maker-checker)
function WaiveDialog({ loan, charge, onClose }: { loan: Loan; charge: LoanCharge; onClose: () => void }) {
  const [amount, setAmount] = useState(charge.unpaid ?? '');
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const m = useWaiveLoanCharge(loan.id!);
  const toast = useProposalToast();
  const errs = {
    amount: amountError(amount) ?? (charge.unpaid && moneyInput(amount) && Number(moneyInput(amount)) > Number(charge.unpaid) ? `At most ${formatINR(charge.unpaid)}` : null),
    reason: !reason.trim() ? 'A reason is required' : null,
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Waive ${charge.name}`}
      footer={
        <Footer
          onClose={onClose}
          label="Submit for approval"
          loading={m.isPending}
          onSubmit={() => {
            setTouched(true);
            if (errs.amount || errs.reason) return;
            m.mutate({ chargeId: charge.id!, amount: moneyInput(amount)!, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Waiver'), onClose()) });
          }}
        />
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Unpaid: <MoneyText value={charge.unpaid} /> of <MoneyText value={charge.amount} /> charged on {formatDate(charge.date)}.
        </p>
        <Input label="Amount to waive" required numeric value={amount} onChange={(e) => setAmount(e.target.value)} error={touched ? errs.amount : null} />
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched ? errs.reason : null} />
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- reversal (maker-checker)
function ReverseDialog({ loan, txn, onClose }: { loan: Loan; txn: LoanTxn; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const m = useReverseLoanTxn(loan.id!);
  const toast = useProposalToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Reverse transaction #${txn.seq}`}
      footer={
        <Footer
          onClose={onClose}
          label="Propose reversal"
          variant="danger"
          loading={m.isPending}
          onSubmit={() => {
            setTouched(true);
            if (!reason.trim()) return;
            m.mutate({ txnId: txn.id!, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Reversal'), onClose()) });
          }}
        />
      }
    >
      <div className="stack">
        <p style={{ margin: 0 }}>
          {humanize(txn.type ?? '')} of <MoneyText value={txn.amount} /> on {formatDate(txn.valueDate)}: {txn.summary}
        </p>
        <Banner tone="warn">Every later transaction is reversed too, and the days since are replayed. A checker must approve.</Banner>
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched && !reason.trim() ? 'A reason is required' : null} />
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- freeze / unfreeze
function FreezeDialog({ loan, freeze, onClose }: { loan: Loan; freeze: boolean; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const m = useFreezeLoan(loan.id!);
  const toast = useToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`${freeze ? 'Freeze' : 'Unfreeze'} loan ${loan.loanNo}`}
      footer={
        <Footer
          onClose={onClose}
          label={freeze ? 'Freeze' : 'Unfreeze'}
          variant={freeze ? 'danger' : 'primary'}
          loading={m.isPending}
          onSubmit={() => {
            setTouched(true);
            if (!reason.trim()) return;
            m.mutate({ freeze, reason: reason.trim() }, { onSuccess: () => (toast({ tone: 'success', message: `Loan ${freeze ? 'frozen' : 'unfrozen'}.` }), onClose()) });
          }}
        />
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>{freeze ? 'Blocks repayments and other transactions; interest accrual and day-end continue.' : 'Allows transactions again.'}</p>
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched && !reason.trim() ? 'A reason is required' : null} />
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- KFS acceptance
function KfsAcceptanceDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const [channel, setChannel] = useState('OTP');
  const [evidenceRef, setEvidenceRef] = useState('');
  const m = useAcceptKfs(loan.id!);
  const toast = useToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title="Record KFS acceptance"
      footer={
        <Footer
          onClose={onClose}
          label="Record acceptance"
          loading={m.isPending}
          onSubmit={() => m.mutate({ channel, ...(evidenceRef.trim() ? { evidenceRef: evidenceRef.trim() } : {}) }, { onSuccess: () => (toast({ tone: 'success', message: 'KFS acceptance recorded.' }), onClose()) })}
        />
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>The borrower must accept the Key Fact Statement before the loan can be disbursed.</p>
        <div className="form-grid">
          <Select label="Channel" value={channel} onChange={(e) => setChannel(e.target.value)} options={[{ value: 'OTP', label: 'OTP' }, { value: 'ESIGN', label: 'e-Sign' }, { value: 'BRANCH', label: 'Signed at branch' }]} />
          <Input label="Evidence reference" value={evidenceRef} onChange={(e) => setEvidenceRef(e.target.value)} hint="OTP transaction id, e-Sign id or document number" />
        </div>
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}
