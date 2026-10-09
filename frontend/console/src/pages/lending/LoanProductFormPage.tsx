import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useLoanProduct, useLoanProductTemplates, usePreviewLoanProduct, useProposeLoanProduct } from '../../api/lendingHooks';
import { LOAN_PRODUCT_DEFAULTS } from '../../api/types';
import type { FeeRule, LoanProduct } from '../../api/types';
import { Banner, Button, Card, Checkbox, ErrorBanner, Input, PageHeader, Select, Spinner } from '../../ui';
import { KfsView } from './KfsView';
import { useProposalToast } from '../proposal';
import { BPI_LABEL, FREQUENCY_LABEL, INTEREST_BASIS_LABEL, REPAYMENT_METHOD_LABEL } from './common';
import { RESET_HELP, RESET_OPTION_LABEL } from './rateResets';

type Seq = NonNullable<LoanProduct['appropriationSequence']>[number];
const SEQ: Seq[] = ['INTEREST', 'PRINCIPAL', 'PENAL', 'FEE'];
const EVENTS: FeeRule['event'][] = ['DISBURSEMENT', 'PRECLOSURE', 'PART_PREPAYMENT', 'BOUNCE', 'LATE_PAYMENT', 'CANCELLATION', 'ADHOC'];

interface FeeDraft {
  key: number;
  code: string;
  name: string;
  event: FeeRule['event'];
  calcType: FeeRule['calcType'];
  amount: string;
  percent: string;
  slabs: string;
  minAmount: string;
  maxAmount: string;
  gstRate: string;
  taxTreatment: 'EXCLUSIVE' | 'INCLUSIVE';
  deductFromDisbursal: boolean;
}

interface Draft {
  code: string;
  name: string;
  repaymentMethod: LoanProduct['repaymentMethod'];
  minAmount: string;
  maxAmount: string;
  minTenorMonths: string;
  maxTenorMonths: string;
  minRate: string;
  maxRate: string;
  interestTableCode: string;
  rateType: NonNullable<LoanProduct['rateType']>;
  dayCount: NonNullable<LoanProduct['dayCount']>;
  frequency: NonNullable<LoanProduct['frequency']>;
  interestBasis: NonNullable<LoanProduct['interestBasis']>;
  bpiMode: NonNullable<LoanProduct['bpiMode']>;
  stepPercent: string;
  stepEvery: string;
  principalEvery: string;
  multipleDisbursements: boolean;
  preEmi: boolean;
  topUpAllowed: boolean;
  benchmarkCode: string;
  spread: string;
  resetFrequencyMonths: string;
  resetOption: NonNullable<LoanProduct['resetOption']>;
  rounding: NonNullable<LoanProduct['rounding']>;
  penalChargeRate: string;
  maxMoratoriumMonths: string;
  coolingOffDays: string;
  secured: boolean;
  appropriationSequence: Seq[];
  appropriationMode: NonNullable<LoanProduct['appropriationMode']>;
  prepaymentMode: NonNullable<LoanProduct['prepaymentMode']>;
  status: NonNullable<LoanProduct['status']>;
  fees: FeeDraft[];
}

const s = (v: unknown) => (v === null || v === undefined ? '' : String(v));

function slabsToText(f: FeeRule): string {
  return (f.slabs ?? []).map((x) => `${s(x.from)}-${s(x.to)}:${s(x.fee)}`).join('; ');
}

/** "0-10000:300; 10000.01-99999999:500" -> slabs; null when the text does not parse. */
export function parseSlabs(text: string): Array<{ from: string; to: string; fee: string }> | null {
  const parts = text.split(';').map((p) => p.trim()).filter(Boolean);
  const out: Array<{ from: string; to: string; fee: string }> = [];
  for (const p of parts) {
    const m = p.match(/^(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*:\s*(\d+(?:\.\d+)?)$/);
    if (!m) return null;
    out.push({ from: m[1], to: m[2], fee: m[3] });
  }
  return out.length ? out : null;
}

function toDraft(p: LoanProduct | null): Draft {
  let key = 0;
  return {
    code: p?.code ?? '',
    name: p?.name ?? '',
    repaymentMethod: p?.repaymentMethod ?? 'EQUATED',
    minAmount: s(p?.minAmount),
    maxAmount: s(p?.maxAmount),
    minTenorMonths: s(p?.minTenorMonths),
    maxTenorMonths: s(p?.maxTenorMonths),
    minRate: s(p?.minRate),
    maxRate: s(p?.maxRate),
    interestTableCode: s(p?.interestTableCode),
    rateType: p?.rateType ?? 'FIXED',
    dayCount: p?.dayCount ?? 'ACTUAL_365',
    frequency: p?.frequency ?? 'MONTHLY',
    interestBasis: p?.interestBasis ?? 'DAILY_REDUCING',
    bpiMode: p?.bpiMode ?? 'NONE',
    stepPercent: s(p?.stepPercent),
    stepEvery: s(p?.stepEvery),
    principalEvery: s(p?.principalEvery ?? 1),
    multipleDisbursements: !!p?.multipleDisbursements,
    preEmi: !!p?.preEmi,
    topUpAllowed: !!p?.topUpAllowed,
    benchmarkCode: s(p?.benchmarkCode),
    spread: s(p?.spread),
    resetFrequencyMonths: s(p?.resetFrequencyMonths),
    resetOption: p?.resetOption ?? 'KEEP_TENURE_CHANGE_EMI',
    rounding: p?.rounding ?? 'RUPEE_HALF_UP',
    penalChargeRate: s(p?.penalChargeRate),
    maxMoratoriumMonths: s(p?.maxMoratoriumMonths ?? 0),
    coolingOffDays: s(p?.coolingOffDays ?? 3),
    secured: !!p?.secured,
    appropriationSequence: p?.appropriationSequence?.length ? [...p.appropriationSequence] : [...SEQ],
    appropriationMode: p?.appropriationMode ?? 'BY_DEMAND',
    prepaymentMode: p?.prepaymentMode ?? 'REDUCE_TENURE',
    status: p?.status ?? 'ACTIVE',
    fees: (p?.fees ?? []).map((f) => ({
      key: ++key,
      code: f.code,
      name: f.name,
      event: f.event,
      calcType: f.calcType,
      amount: s(f.amount),
      percent: s(f.percent),
      slabs: slabsToText(f),
      minAmount: s(f.minAmount),
      maxAmount: s(f.maxAmount),
      gstRate: s(f.gstRate ?? 18),
      taxTreatment: f.taxTreatment ?? 'EXCLUSIVE',
      deductFromDisbursal: !!f.deductFromDisbursal,
    })),
  };
}

const intOk = (v: string) => /^\d+$/.test(v.trim());
const numOk = (v: string) => /^\d+(\.\d+)?$/.test(v.trim());

function validate(d: Draft) {
  const e: Record<string, string | null> = {
    code: !/^[A-Z0-9]{2,12}$/.test(d.code) ? '2-12 upper-case letters or digits' : null,
    name: !d.name.trim() ? 'Name is required' : null,
    minAmount: !numOk(d.minAmount) ? 'Enter an amount' : null,
    maxAmount: !numOk(d.maxAmount) ? 'Enter an amount' : numOk(d.minAmount) && Number(d.maxAmount) < Number(d.minAmount) ? 'Must be at least the minimum' : null,
    minTenorMonths: !intOk(d.minTenorMonths) || Number(d.minTenorMonths) < 1 ? 'Whole months, at least 1' : null,
    maxTenorMonths: !intOk(d.maxTenorMonths) ? 'Whole months' : Number(d.maxTenorMonths) < Number(d.minTenorMonths) ? 'Must be at least the minimum' : null,
    minRate: !numOk(d.minRate) || Number(d.minRate) > 100 ? '0 to 100' : null,
    maxRate: !numOk(d.maxRate) || Number(d.maxRate) > 100 ? '0 to 100' : numOk(d.minRate) && Number(d.maxRate) < Number(d.minRate) ? 'Must be at least the minimum' : null,
    penalChargeRate: d.penalChargeRate && (!numOk(d.penalChargeRate) || Number(d.penalChargeRate) > 100) ? '0 to 100' : null,
    maxMoratoriumMonths: !intOk(d.maxMoratoriumMonths) ? 'Whole months' : null,
    coolingOffDays: !intOk(d.coolingOffDays) ? 'Whole days' : null,
    appropriationSequence: new Set(d.appropriationSequence).size !== SEQ.length ? 'Each component exactly once' : null,
    stepPercent: d.repaymentMethod === 'STEP_EQUATED' && (!/^-?\d+(\.\d+)?$/.test(d.stepPercent.trim()) || Number(d.stepPercent) <= -50 || Number(d.stepPercent) > 100 || Number(d.stepPercent) === 0) ? 'Above -50, at most 100, not 0' : null,
    stepEvery: d.repaymentMethod === 'STEP_EQUATED' && (!intOk(d.stepEvery) || Number(d.stepEvery) < 1) ? 'Instalments between steps' : null,
    principalEvery: d.repaymentMethod === 'FIXED_PRINCIPAL' && (!intOk(d.principalEvery) || Number(d.principalEvery) < 1) ? '1 or more' : null,
    interestBasis: d.interestBasis === 'FLAT' && d.repaymentMethod !== 'EQUATED' ? 'Flat rate is for EMI (equated) products only' : null,
    spread: d.rateType === 'FLOATING' && d.benchmarkCode && !/^-?\d+(\.\d+)?$/.test(d.spread.trim()) ? 'Enter the spread' : null,
    resetFrequencyMonths: d.rateType === 'FLOATING' && d.benchmarkCode && (!intOk(d.resetFrequencyMonths) || Number(d.resetFrequencyMonths) < 1 || Number(d.resetFrequencyMonths) > 60) ? '1 to 60 months' : null,
  };
  const fees = d.fees.map((f) => ({
    code: !/^[A-Z0-9_]{2,20}$/.test(f.code) ? '2-20 upper-case letters, digits or _' : d.fees.filter((x) => x.code === f.code).length > 1 ? 'Duplicate code' : null,
    name: !f.name.trim() ? 'Required' : null,
    amount: f.calcType === 'FIXED' && !numOk(f.amount) ? 'Enter an amount' : null,
    percent: f.calcType === 'PERCENT' && (!numOk(f.percent) || Number(f.percent) <= 0 || Number(f.percent) > 100) ? 'Above 0, at most 100' : null,
    slabs: f.calcType === 'SLAB' && !parseSlabs(f.slabs) ? 'Use from-to:fee; …' : null,
    minAmount: f.minAmount && !numOk(f.minAmount) ? 'Invalid' : null,
    maxAmount: f.maxAmount && !numOk(f.maxAmount) ? 'Invalid' : null,
    gstRate: !numOk(f.gstRate) ? 'Required' : null,
  }));
  const valid = Object.values(e).every((x) => !x) && fees.every((f) => Object.values(f).every((x) => !x));
  return { e, fees, valid };
}

function toProduct(d: Draft): LoanProduct {
  const opt = (v: string) => (v.trim() ? v.trim() : null);
  return {
    ...LOAN_PRODUCT_DEFAULTS,
    code: d.code,
    name: d.name.trim(),
    repaymentMethod: d.repaymentMethod,
    minAmount: d.minAmount.trim(),
    maxAmount: d.maxAmount.trim(),
    minTenorMonths: Number(d.minTenorMonths),
    maxTenorMonths: Number(d.maxTenorMonths),
    minRate: d.minRate.trim(),
    maxRate: d.maxRate.trim(),
    interestTableCode: opt(d.interestTableCode),
    rateType: d.rateType,
    dayCount: d.dayCount,
    frequency: d.frequency,
    interestBasis: d.interestBasis,
    bpiMode: d.bpiMode,
    stepPercent: d.repaymentMethod === 'STEP_EQUATED' ? opt(d.stepPercent) : null,
    stepEvery: d.repaymentMethod === 'STEP_EQUATED' && d.stepEvery ? Number(d.stepEvery) : null,
    principalEvery: d.repaymentMethod === 'FIXED_PRINCIPAL' && d.principalEvery ? Number(d.principalEvery) : 1,
    multipleDisbursements: d.multipleDisbursements,
    preEmi: d.multipleDisbursements && d.preEmi,
    topUpAllowed: d.topUpAllowed,
    benchmarkCode: d.rateType === 'FLOATING' ? opt(d.benchmarkCode) : null,
    spread: d.rateType === 'FLOATING' && d.benchmarkCode ? opt(d.spread) : null,
    resetFrequencyMonths: d.rateType === 'FLOATING' && d.benchmarkCode && d.resetFrequencyMonths ? Number(d.resetFrequencyMonths) : null,
    resetOption: d.resetOption,
    rounding: d.rounding,
    penalChargeRate: opt(d.penalChargeRate),
    maxMoratoriumMonths: Number(d.maxMoratoriumMonths),
    coolingOffDays: Number(d.coolingOffDays),
    secured: d.secured,
    appropriationSequence: d.appropriationSequence,
    appropriationMode: d.appropriationMode,
    prepaymentMode: d.prepaymentMode,
    status: d.status,
    fees: d.fees.map((f) => ({
      code: f.code,
      name: f.name.trim(),
      event: f.event,
      calcType: f.calcType,
      amount: f.calcType === 'FIXED' ? f.amount.trim() : null,
      percent: f.calcType === 'PERCENT' ? f.percent.trim() : null,
      ...(f.calcType === 'SLAB' ? { slabs: parseSlabs(f.slabs) ?? [] } : {}),
      minAmount: opt(f.minAmount),
      maxAmount: opt(f.maxAmount),
      gstRate: f.gstRate.trim(),
      taxTreatment: f.taxTreatment,
      deductFromDisbursal: f.deductFromDisbursal,
    })),
  };
}

const opts = <T extends string>(values: readonly T[], label: (v: T) => string = (v) => v) => values.map((v) => ({ value: v, label: label(v) }));

export function LoanProductFormPage() {
  const { code } = useParams();
  const existing = useLoanProduct(code);
  if (code && existing.isLoading) return <Spinner />;
  if (code && (existing.error || !existing.data)) return <ErrorBanner error={existing.error ?? new Error('Product not found')} />;
  return <ProductForm key={code ?? 'new'} initial={code ? existing.data! : null} />;
}

function ProductForm({ initial }: { initial: LoanProduct | null }) {
  const [d, setD] = useState<Draft>(() => toDraft(initial));
  const [touched, setTouched] = useState(false);
  const propose = useProposeLoanProduct();
  const templates = useLoanProductTemplates(!initial);
  const preview = usePreviewLoanProduct();
  const [template, setTemplate] = useState('');
  const applyTemplate = (code: string) => {
    setTemplate(code);
    const t = (templates.data ?? []).find((x) => x.code === code);
    if (!t) return;
    // Keep the code the user typed; a template has none (nothing in it is a live product).
    setD((x) => ({ ...toDraft({ ...(t.product as unknown as LoanProduct), code: x.code }), status: 'ACTIVE' }));
    preview.reset();
  };
  const runPreview = () => {
    setTouched(true);
    const { code: _c, status: _s, ...product } = toProduct(d);
    if (!Object.entries(v.e).every(([k, x]) => k === 'code' || !x) || !v.fees.every((f) => Object.values(f).every((x) => !x))) return;
    preview.mutate({ product });
  };
  const toast = useProposalToast();
  const navigate = useNavigate();
  const v = validate(d);
  const set = (patch: Partial<Draft>) => setD((x) => ({ ...x, ...patch }));
  const setFee = (key: number, patch: Partial<FeeDraft>) => setD((x) => ({ ...x, fees: x.fees.map((f) => (f.key === key ? { ...f, ...patch } : f)) }));
  const err = (k: string) => (touched ? v.e[k] : null);
  const back = initial ? `/loan-products/${encodeURIComponent(initial.code)}` : '/loan-products';
  const submit = () => {
    setTouched(true);
    if (!v.valid) return;
    propose.mutate(toProduct(d), {
      onSuccess: (a) => {
        toast(a, initial ? 'Loan product change' : 'New loan product');
        navigate(back);
      },
    });
  };
  return (
    <div className="stack">
      <PageHeader
        title={initial ? `Change product ${initial.code}` : 'New loan product'}
        subtitle={initial ? `Currently version ${initial.version ?? 1}. Approval creates version ${(initial.version ?? 1) + 1}; existing loans keep the version they were booked on.` : 'Sent to a checker for approval.'}
        actions={<Link to={back}>Cancel</Link>}
      />
      <form
        className="stack"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        {!initial && (
          <Card title="Start from a template">
            <Select
              label="Template"
              value={template}
              placeholder="Blank product"
              onChange={(e) => applyTemplate(e.target.value)}
              options={(templates.data ?? []).map((t) => ({ value: t.code ?? '', label: `${t.name} (${(t.category ?? '').toLowerCase()})` }))}
              hint={(templates.data ?? []).find((t) => t.code === template)?.description ?? 'Illustrative starting points; change anything before you submit'}
            />
          </Card>
        )}
        <Card title="Product">
          <div className="form-grid">
            <Input label="Code" required disabled={!!initial} className="mono" value={d.code} onChange={(e) => set({ code: e.target.value.toUpperCase() })} error={err('code')} />
            <Input label="Name" required value={d.name} onChange={(e) => set({ name: e.target.value })} error={err('name')} />
            <Select label="Repayment method" value={d.repaymentMethod} onChange={(e) => set({ repaymentMethod: e.target.value as Draft['repaymentMethod'] })} options={opts(Object.keys(REPAYMENT_METHOD_LABEL) as Draft['repaymentMethod'][], (m) => REPAYMENT_METHOD_LABEL[m])} />
            <Select label="Frequency" value={d.frequency} onChange={(e) => set({ frequency: e.target.value as Draft['frequency'] })} options={opts(Object.keys(FREQUENCY_LABEL) as Draft['frequency'][], (f) => FREQUENCY_LABEL[f])} hint="The tenor counts periods of this frequency" />
            {d.repaymentMethod === 'STEP_EQUATED' && (
              <>
                <Input label="Step %" required numeric value={d.stepPercent} onChange={(e) => set({ stepPercent: e.target.value })} hint="10 = step-up, -10 = step-down" error={err('stepPercent')} />
                <Input label="Step every (instalments)" required numeric value={d.stepEvery} onChange={(e) => set({ stepEvery: e.target.value })} error={err('stepEvery')} />
              </>
            )}
            {d.repaymentMethod === 'FIXED_PRINCIPAL' && <Input label="Principal every (instalments)" numeric value={d.principalEvery} onChange={(e) => set({ principalEvery: e.target.value })} hint="Interest falls due every instalment" error={err('principalEvery')} />}
            <Select label="Status" value={d.status} onChange={(e) => set({ status: e.target.value as Draft['status'] })} options={opts(['DRAFT', 'ACTIVE', 'WITHDRAWN'] as const)} />
            <Input label="Minimum amount" required numeric value={d.minAmount} onChange={(e) => set({ minAmount: e.target.value })} error={err('minAmount')} />
            <Input label="Maximum amount" required numeric value={d.maxAmount} onChange={(e) => set({ maxAmount: e.target.value })} error={err('maxAmount')} />
            <Input label="Minimum tenor (months)" required numeric value={d.minTenorMonths} onChange={(e) => set({ minTenorMonths: e.target.value })} error={err('minTenorMonths')} />
            <Input label="Maximum tenor (months)" required numeric value={d.maxTenorMonths} onChange={(e) => set({ maxTenorMonths: e.target.value })} error={err('maxTenorMonths')} />
            <Input label="Max moratorium (months)" numeric value={d.maxMoratoriumMonths} onChange={(e) => set({ maxMoratoriumMonths: e.target.value })} error={err('maxMoratoriumMonths')} />
            <Input label="Cooling-off days" numeric value={d.coolingOffDays} onChange={(e) => set({ coolingOffDays: e.target.value })} error={err('coolingOffDays')} />
          </div>
          <div style={{ marginTop: 12 }}>
            <div className="row">
              <Checkbox label="Secured" checked={d.secured} onChange={(e) => set({ secured: e.target.checked })} />
              <Checkbox label="Multiple disbursements (tranches)" checked={d.multipleDisbursements} onChange={(e) => set({ multipleDisbursements: e.target.checked })} />
              <Checkbox label="Pre-EMI interest until fully drawn" checked={d.multipleDisbursements && d.preEmi} disabled={!d.multipleDisbursements || d.repaymentMethod !== 'EQUATED'} onChange={(e) => set({ preEmi: e.target.checked })} />
              <Checkbox label="Top-up allowed in the same account" checked={d.topUpAllowed} onChange={(e) => set({ topUpAllowed: e.target.checked })} />
            </div>
          </div>
        </Card>
        <Card title="Interest">
          <div className="form-grid">
            <Input label="Minimum rate % p.a." required numeric value={d.minRate} onChange={(e) => set({ minRate: e.target.value })} error={err('minRate')} />
            <Input label="Maximum rate % p.a." required numeric value={d.maxRate} onChange={(e) => set({ maxRate: e.target.value })} error={err('maxRate')} />
            <Input label="Interest table code" hint="Leave empty to enter the rate on each loan" className="mono" value={d.interestTableCode} onChange={(e) => set({ interestTableCode: e.target.value.toUpperCase() })} />
            <Select label="Rate type" value={d.rateType} onChange={(e) => set({ rateType: e.target.value as Draft['rateType'] })} options={opts(['FIXED', 'FLOATING'] as const)} />
            <Select label="Day count" value={d.dayCount} onChange={(e) => set({ dayCount: e.target.value as Draft['dayCount'] })} options={opts(['ACTUAL_365', 'ACTUAL_360', 'ACTUAL_ACTUAL', 'THIRTY_360', 'THIRTY_E_360', 'ACTUAL_366', 'ACTUAL_364', 'ACTUAL_336', 'ACTUAL_372'] as const)} />
            <Select label="Interest basis" value={d.interestBasis} onChange={(e) => set({ interestBasis: e.target.value as Draft['interestBasis'] })} options={opts(Object.keys(INTEREST_BASIS_LABEL) as Draft['interestBasis'][], (b) => INTEREST_BASIS_LABEL[b])} error={err('interestBasis')} hint={d.interestBasis === 'FLAT' ? 'The KFS shows the equivalent reducing rate and the APR' : undefined} />
            <Select label="Broken-period interest" value={d.bpiMode} onChange={(e) => set({ bpiMode: e.target.value as Draft['bpiMode'] })} options={opts(Object.keys(BPI_LABEL) as Draft['bpiMode'][], (b) => BPI_LABEL[b])} />
            {d.rateType === 'FLOATING' && (
              <>
                <Select label="Benchmark" value={d.benchmarkCode} placeholder="None" onChange={(e) => set({ benchmarkCode: e.target.value })} options={opts(['REPO', 'MCLR1Y', 'TBILL91'] as const)} hint="Rate = benchmark + spread when the loan gives no rate" />
                {d.benchmarkCode && (
                  <>
                    <Input label="Spread % over the benchmark" required numeric value={d.spread} onChange={(e) => set({ spread: e.target.value })} error={err('spread')} />
                    <Input label="Rate reset every (months)" required numeric value={d.resetFrequencyMonths} onChange={(e) => set({ resetFrequencyMonths: e.target.value })} error={err('resetFrequencyMonths')} hint="From disbursal; the day-end resets the rate to the benchmark on that date + the spread" />
                    <Select
                      label="At a reset (default)"
                      value={d.resetOption}
                      onChange={(e) => set({ resetOption: e.target.value as Draft['resetOption'] })}
                      options={[
                        { value: 'KEEP_EMI_CHANGE_TENURE', label: RESET_OPTION_LABEL.KEEP_EMI_CHANGE_TENURE },
                        { value: 'KEEP_TENURE_CHANGE_EMI', label: RESET_OPTION_LABEL.KEEP_TENURE_CHANGE_EMI },
                      ]}
                      hint={`Board policy; the borrower may choose otherwise on the loan. ${RESET_HELP}`}
                    />
                  </>
                )}
              </>
            )}
            <Select label="Rounding" value={d.rounding} onChange={(e) => set({ rounding: e.target.value as Draft['rounding'] })} options={opts(['RUPEE_HALF_UP', 'RUPEE_DOWN', 'RUPEE_UP', 'PAISE_HALF_UP', 'PAISE_HALF_EVEN'] as const)} />
            <Input label="Penal charge rate % p.a." numeric value={d.penalChargeRate} onChange={(e) => set({ penalChargeRate: e.target.value })} error={err('penalChargeRate')} />
          </div>
        </Card>
        <Card title="Servicing">
          <div className="form-grid">
            {d.appropriationSequence.map((c, i) => (
              <Select
                key={i}
                label={`Appropriation ${i + 1}`}
                value={c}
                onChange={(e) => set({ appropriationSequence: d.appropriationSequence.map((x, j) => (j === i ? (e.target.value as Seq) : x)) })}
                options={opts(SEQ)}
                error={i === 0 ? err('appropriationSequence') : null}
              />
            ))}
            <Select label="Appropriation mode" value={d.appropriationMode} onChange={(e) => set({ appropriationMode: e.target.value as Draft['appropriationMode'] })} options={opts(['BY_DEMAND', 'BY_COMPONENT'] as const)} />
            <Select label="Default part-prepayment" value={d.prepaymentMode} onChange={(e) => set({ prepaymentMode: e.target.value as Draft['prepaymentMode'] })} options={opts(['REDUCE_TENURE', 'REDUCE_EMI'] as const)} />
          </div>
        </Card>
        <Card
          title="Fees"
          actions={
            <Button size="sm" onClick={() => set({ fees: [...d.fees, { key: Math.max(0, ...d.fees.map((f) => f.key)) + 1, code: '', name: '', event: 'DISBURSEMENT', calcType: 'PERCENT', amount: '', percent: '', slabs: '', minAmount: '', maxAmount: '', gstRate: '18', taxTreatment: 'EXCLUSIVE', deductFromDisbursal: false }] })}>
              Add fee
            </Button>
          }
        >
          <div className="stack">
            {d.fees.length === 0 && <p className="muted" style={{ margin: 0 }}>No fees.</p>}
            {d.fees.map((f, i) => {
              const fe = touched ? v.fees[i] : null;
              const n = i + 1;
              return (
                <fieldset key={f.key} className="fee-row" aria-label={`Fee ${n}`}>
                  <legend>Fee {n}</legend>
                  <Input label={`Fee ${n} code`} className="mono" value={f.code} onChange={(e) => setFee(f.key, { code: e.target.value.toUpperCase() })} error={fe?.code} />
                  <Input label={`Fee ${n} name`} value={f.name} onChange={(e) => setFee(f.key, { name: e.target.value })} error={fe?.name} />
                  <Select label={`Fee ${n} charged on`} value={f.event} onChange={(e) => setFee(f.key, { event: e.target.value as FeeRule['event'] })} options={opts(EVENTS)} />
                  <Select label={`Fee ${n} calculation`} value={f.calcType} onChange={(e) => setFee(f.key, { calcType: e.target.value as FeeRule['calcType'] })} options={opts(['FIXED', 'PERCENT', 'SLAB'] as const)} />
                  {f.calcType === 'FIXED' && <Input label={`Fee ${n} amount`} numeric value={f.amount} onChange={(e) => setFee(f.key, { amount: e.target.value })} error={fe?.amount} />}
                  {f.calcType === 'PERCENT' && <Input label={`Fee ${n} percent`} numeric value={f.percent} onChange={(e) => setFee(f.key, { percent: e.target.value })} error={fe?.percent} />}
                  {f.calcType === 'SLAB' && <Input label={`Fee ${n} slabs`} placeholder="0-10000:300; 10000.01-99999999:500" value={f.slabs} onChange={(e) => setFee(f.key, { slabs: e.target.value })} error={fe?.slabs} />}
                  <Input label={`Fee ${n} minimum`} numeric value={f.minAmount} onChange={(e) => setFee(f.key, { minAmount: e.target.value })} error={fe?.minAmount} />
                  <Input label={`Fee ${n} maximum`} numeric value={f.maxAmount} onChange={(e) => setFee(f.key, { maxAmount: e.target.value })} error={fe?.maxAmount} />
                  <Input label={`Fee ${n} GST %`} numeric value={f.gstRate} onChange={(e) => setFee(f.key, { gstRate: e.target.value })} error={fe?.gstRate} />
                  <Select label={`Fee ${n} GST`} value={f.taxTreatment} onChange={(e) => setFee(f.key, { taxTreatment: e.target.value as FeeDraft['taxTreatment'] })} options={[{ value: 'EXCLUSIVE', label: 'Extra (exclusive)' }, { value: 'INCLUSIVE', label: 'Included (inclusive)' }]} />
                  <Checkbox label="Deduct from disbursal" checked={f.deductFromDisbursal} onChange={(e) => setFee(f.key, { deductFromDisbursal: e.target.checked })} />
                  <Button size="sm" variant="ghost" aria-label={`Remove fee ${n}`} onClick={() => set({ fees: d.fees.filter((x) => x.key !== f.key) })}>
                    Remove
                  </Button>
                </fieldset>
              );
            })}
          </div>
        </Card>
        <ErrorBanner error={propose.error ?? preview.error} />
        {touched && !v.valid && <p className="field__error" role="alert">Fix the highlighted fields.</p>}
        <div className="form-actions">
          <Button loading={preview.isPending} onClick={runPreview}>
            Preview sample loan
          </Button>
          <Button type="submit" variant="primary" loading={propose.isPending}>
            Submit for approval
          </Button>
        </div>
      </form>
      {preview.data && (
        <>
          <Banner tone="info">
            Sample loan on this draft (nothing is stored): the product’s smallest amount, shortest tenor and lowest rate, GST as an intra-state supply.
            {preview.data.sampleSchedule ? ' Structured product: equal principal on each period date is shown as an example.' : ''}
          </Banner>
          <KfsView kfs={preview.data} title="Preview of the draft product" />
        </>
      )}
    </div>
  );
}
