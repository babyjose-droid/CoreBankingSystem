import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useCheckDepositProduct, useDepositProduct, useDepositRules, useProposeDepositProduct } from '../../api/depositHooks';
import type { DepositFamily, DepositProduct, DepositProductInput } from '../../api/types';
import { Banner, Button, Card, Checkbox, ErrorBanner, Input, PageHeader, Select, Spinner } from '../../ui';
import { NotFoundPage } from '../ForbiddenPage';
import { useProposalToast } from '../proposal';
import { PERIOD_LABEL, Problems, familyOf, familyPath, ruleValue } from './common';

interface Step {
  id: number;
  value: string;
  days: string;
}
interface Form {
  code: string;
  name: string;
  productType: string;
  dayCount: string;
  rounding: string;
  tds: boolean;
  // savings and current
  minBalance: string;
  cheque: boolean;
  bands: Array<{ from: string; rate: string }>;
  bandMode: string;
  posting: string;
  /** The schedule of charges (P4-4); empty strings for no charge. */
  gst: string;
  minPercent: string;
  minMax: string;
  closure: string;
  closureFree: string;
  closureUntil: string;
  service: Array<{ code: string; name: string; amount: string }>;
  // term deposits
  minAmount: string;
  maxAmount: string;
  minMonths: string;
  minDays: string;
  maxMonths: string;
  maxDays: string;
  period: string;
  /** Amount steps ("from amount") and tenure steps ("from months and days") of the rate card, and the rate of each cell. */
  amounts: Step[];
  tenures: Step[];
  rates: Record<string, string>;
  addOns: Array<{ code: string; percent: string }>;
  premature: boolean;
  penalty: string;
  prematureMinDays: string;
  renewal: boolean;
  glLiability: string;
  glInterestExpense: string;
}

let nextId = 1;
const step = (value = '', days = '0'): Step => ({ id: nextId++, value, days });
const digits = (v: string) => v.replace(/[^0-9]/g, '');
const decimal = (v: string) => v.replace(/[^0-9.]/g, '');
const signed = (v: string) => v.replace(/[^0-9.-]/g, '');

function blank(family: DepositFamily): Form {
  const a = step('0');
  const t = step('12');
  return {
    code: '', name: '', productType: family === 'CASA' ? 'SAVINGS' : 'FD_CUMULATIVE', dayCount: 'ACTUAL_365', rounding: 'PAISE_HALF_UP', tds: family !== 'CASA',
    minBalance: '0', cheque: false, bands: [{ from: '0', rate: '' }], bandMode: 'WHOLE_BALANCE', posting: '3',
    gst: '18', minPercent: '', minMax: '', closure: '', closureFree: '14', closureUntil: '', service: [],
    minAmount: '', maxAmount: '', minMonths: '12', minDays: '0', maxMonths: '60', maxDays: '0', period: '3',
    amounts: [a], tenures: [t], rates: {}, addOns: [], premature: true, penalty: '', prematureMinDays: '', renewal: false,
    glLiability: '', glInterestExpense: '',
  };
}

function fromProduct(p: DepositProduct): Form {
  const cells = p.rateCard ?? [];
  const amounts = [...new Set(cells.map((c) => c.fromAmount ?? '0'))].sort((x, y) => Number(x) - Number(y)).map((v) => step(v));
  const tenures = [...new Map(cells.map((c) => [`${c.fromMonths}|${c.fromDays}`, c])).values()]
    .sort((x, y) => (x.fromMonths ?? 0) * 31 + (x.fromDays ?? 0) - ((y.fromMonths ?? 0) * 31 + (y.fromDays ?? 0)))
    .map((c) => step(String(c.fromMonths ?? 0), String(c.fromDays ?? 0)));
  const rates: Record<string, string> = {};
  for (const a of amounts) {
    for (const t of tenures) {
      const cell = cells.find((c) => c.fromAmount === a.value && String(c.fromMonths) === t.value && String(c.fromDays) === t.days);
      if (cell) rates[`${a.id}:${t.id}`] = cell.ratePercent ?? '';
    }
  }
  const base = blank(p.family as DepositFamily);
  return {
    ...base,
    code: p.code ?? '', name: p.name ?? '', productType: p.productType ?? base.productType, dayCount: p.dayCount ?? 'ACTUAL_365', rounding: p.rounding ?? 'PAISE_HALF_UP',
    tds: p.tdsApplicable === true, minBalance: p.minBalance ?? '0', cheque: p.chequeFacility === true,
    bands: (p.interestBands ?? []).map((b) => ({ from: b.fromBalance ?? '0', rate: b.ratePercent ?? '' })), bandMode: p.bandMode ?? 'WHOLE_BALANCE',
    posting: String(p.interestPostingMonths ?? 3),
    gst: p.charges?.gstRatePercent ?? '18', minPercent: p.charges?.minBalancePercent ?? '', minMax: p.charges?.minBalanceMax ?? '', closure: p.charges?.closureAmount ?? '',
    closureFree: p.charges?.closureFreeWithinDays == null ? '14' : String(p.charges.closureFreeWithinDays),
    closureUntil: p.charges?.closureWithinDays == null ? '' : String(p.charges.closureWithinDays),
    service: (p.charges?.service ?? []).map((x) => ({ code: x.code ?? '', name: x.name ?? '', amount: x.amount ?? '' })),
    minAmount: p.minAmount ?? '', maxAmount: p.maxAmount ?? '',
    minMonths: String(p.minTenureMonths ?? 0), minDays: String(p.minTenureDays ?? 0), maxMonths: String(p.maxTenureMonths ?? 0), maxDays: String(p.maxTenureDays ?? 0),
    period: String(p.interestPeriodMonths ?? 3), amounts: amounts.length ? amounts : base.amounts, tenures: tenures.length ? tenures : base.tenures, rates,
    addOns: (p.addOns ?? []).map((a) => ({ code: a.code ?? '', percent: a.percent ?? '' })), premature: p.prematureAllowed !== false,
    penalty: p.prematurePenaltyPercent ?? '', prematureMinDays: p.prematureMinDays === null || p.prematureMinDays === undefined ? '' : String(p.prematureMinDays),
    renewal: p.autoRenewalAllowed === true, glLiability: p.glLiability ?? '', glInterestExpense: p.glInterestExpense ?? '',
  };
}

/** The form as the API takes it. Settings of the other family are left out; a blank rate leaves its cell out. */
function toInput(family: DepositFamily, f: Form, prematureFixed: boolean): DepositProductInput {
  const common = {
    code: f.code, name: f.name.trim(), productType: f.productType as DepositProductInput['productType'], rounding: f.rounding as DepositProductInput['rounding'],
    ...(f.glLiability.trim() ? { glLiability: f.glLiability.trim() } : {}),
  };
  if (family === 'CASA') {
    const bands = f.bands.filter((b) => b.from !== '' || b.rate !== '');
    return {
      ...common, minBalance: f.minBalance || '0', chequeFacility: f.cheque, charges: chargesInput(f),
      ...(bands.length
        ? { interestBands: bands.map((b) => ({ fromBalance: b.from, ratePercent: b.rate })), bandMode: f.bandMode as DepositProductInput['bandMode'],
            interestPostingMonths: Number(f.posting) as DepositProductInput['interestPostingMonths'], dayCount: f.dayCount as DepositProductInput['dayCount'], tdsApplicable: f.tds,
            ...(f.glInterestExpense.trim() ? { glInterestExpense: f.glInterestExpense.trim() } : {}) }
        : {}),
    };
  }
  const rateCard: NonNullable<DepositProductInput['rateCard']> = [];
  for (const a of f.amounts) {
    for (const t of f.tenures) {
      const r = f.rates[`${a.id}:${t.id}`];
      if (r !== undefined && r !== '' && a.value !== '') rateCard.push({ fromAmount: a.value, fromMonths: Number(t.value || 0), fromDays: Number(t.days || 0), ratePercent: r });
    }
  }
  return {
    ...common, dayCount: f.dayCount as DepositProductInput['dayCount'], tdsApplicable: f.tds, minAmount: f.minAmount, ...(f.maxAmount ? { maxAmount: f.maxAmount } : {}),
    minTenureMonths: Number(f.minMonths || 0), minTenureDays: Number(f.minDays || 0), maxTenureMonths: Number(f.maxMonths || 0), maxTenureDays: Number(f.maxDays || 0),
    interestPeriodMonths: Number(f.period) as DepositProductInput['interestPeriodMonths'], rateCard,
    addOns: f.addOns.filter((a) => a.code || a.percent).map((a) => ({ code: a.code, percent: a.percent })), prematureAllowed: f.premature,
    ...(!prematureFixed && f.premature ? { ...(f.penalty !== '' ? { prematurePenaltyPercent: f.penalty } : {}), ...(f.prematureMinDays !== '' ? { prematureMinDays: Number(f.prematureMinDays) } : {}) } : {}),
    autoRenewalAllowed: f.renewal, ...(f.glInterestExpense.trim() ? { glInterestExpense: f.glInterestExpense.trim() } : {}),
  };
}

/** The schedule of charges as the API takes it; null when the product charges nothing. */
function chargesInput(f: Form): DepositProductInput['charges'] {
  const service = f.service.filter((x) => x.code || x.name || x.amount).map((x) => ({ code: x.code.trim().toUpperCase(), name: x.name.trim(), amount: x.amount }));
  if (!f.minPercent && !f.minMax && !f.closure && service.length === 0) return null;
  return {
    gstRatePercent: f.gst || '18',
    minBalancePercent: f.minPercent || null, minBalanceMax: f.minMax || null,
    closureAmount: f.closure || null, closureFreeWithinDays: f.closure ? Number(f.closureFree || 0) : null, closureWithinDays: f.closure && f.closureUntil ? Number(f.closureUntil) : null,
    service,
  };
}

export function DepositProductFormPage() {
  const params = useParams();
  const family = familyOf(params.family);
  const editing = !!params.code;
  const navigate = useNavigate();
  const existing = useDepositProduct(family ?? 'CASA', family && editing ? params.code : undefined);
  const rules = useDepositRules(family ?? 'CASA', !!family);
  const check = useCheckDepositProduct(family ?? 'CASA');
  const propose = useProposeDepositProduct(family ?? 'CASA');
  const toast = useProposalToast();
  const [f, setF] = useState<Form | null>(null);
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    if (!family || f) return;
    if (!editing) setF(blank(family));
    else if (existing.data) setF(fromProduct(existing.data));
  }, [family, editing, existing.data, f]);

  if (!family) return <NotFoundPage />;
  if (existing.error) return <ErrorBanner error={existing.error} />;
  if (!f) return <Spinner />;
  const casa = family === 'CASA';
  const set = (patch: Partial<Form>) => (setF({ ...f, ...patch }), check.reset(), propose.reset());
  const prematureFixed = ruleValue(rules.data, 'td.premature.rate-cut-percent') !== null;
  const ceiling = ruleValue(rules.data, 'td.max-rate-percent');
  const minTenure = ruleValue(rules.data, 'td.min-tenure-months');
  const maxTenure = ruleValue(rules.data, 'td.max-tenure-months');
  const uniform = ruleValue(rules.data, 'savings.uniform-rate-up-to');
  const currentInterest = ruleValue(rules.data, 'current.interest-allowed') !== 0;
  const showBands = f.productType === 'SAVINGS' || currentInterest;
  const errors = {
    code: !/^[A-Z0-9]{2,12}$/.test(f.code) ? '2 to 12 capital letters or digits' : null,
    name: !f.name.trim() ? 'Required' : null,
    minAmount: !casa && !/^\d+(\.\d{1,2})?$/.test(f.minAmount) ? 'Enter the smallest deposit' : null,
  };
  const valid = Object.values(errors).every((e) => !e);
  const input = () => toInput(family, casa && !showBands ? { ...f, bands: [] } : f, prematureFixed);
  const back = `/deposits/products${editing ? `/${familyPath(family)}/${params.code}` : ''}`;
  const checked = check.data?.problems;

  return (
    <div className="stack">
      <PageHeader
        title={editing ? `Propose change of ${f.code}` : casa ? 'New savings or current product' : 'New term deposit product'}
        subtitle={editing ? 'The product as approved replaces the stored one and becomes its next version. Accounts already opened keep the terms frozen on them.' : 'The product goes to a checker; it can be used once it is approved.'}
        actions={<Link to={back}>Cancel</Link>}
      />
      <Card title="Product">
        <div className="form-grid">
          <Input label="Code" required className="mono" disabled={editing} value={f.code} onChange={(e) => set({ code: e.target.value.toUpperCase() })} error={touched ? errors.code : null} />
          <Input label="Name" required value={f.name} onChange={(e) => set({ name: e.target.value })} error={touched ? errors.name : null} />
          <Select
            label="Type"
            disabled={editing}
            value={f.productType}
            onChange={(e) => set({ productType: e.target.value, ...(e.target.value === 'FD_CUMULATIVE' && f.period === '0' ? { period: '3' } : {}) })}
            options={casa
              ? [{ value: 'SAVINGS', label: 'Savings account' }, { value: 'CURRENT', label: 'Current account' }]
              : [{ value: 'FD_CUMULATIVE', label: 'Fixed deposit, cumulative (interest added to the deposit)' }, { value: 'FD_PAYOUT', label: 'Fixed deposit, interest paid out' }]}
          />
          {casa ? (
            <>
              <Input label="Minimum balance" numeric value={f.minBalance} onChange={(e) => set({ minBalance: decimal(e.target.value) })} />
              <Checkbox label="Cheque facility" checked={f.cheque} onChange={(e) => set({ cheque: e.target.checked })} />
            </>
          ) : (
            <>
              <Input label="Smallest deposit" required numeric value={f.minAmount} onChange={(e) => set({ minAmount: decimal(e.target.value) })} error={touched ? errors.minAmount : null} />
              <Input label="Largest deposit" numeric hint="Empty for no limit" value={f.maxAmount} onChange={(e) => set({ maxAmount: decimal(e.target.value) })} />
            </>
          )}
        </div>
      </Card>

      {casa && (
        <Card title="Charges">
          <div className="stack">
            <p className="muted" style={{ margin: 0 }}>Amounts are before GST; GST is added to every charge and invoiced. Leave a charge empty for none.</p>
            <div className="form-grid">
              <Input label="GST on charges (%)" numeric value={f.gst} onChange={(e) => set({ gst: decimal(e.target.value) })} />
              <Input label="Minimum balance: % of the month's shortfall" numeric value={f.minPercent} onChange={(e) => set({ minPercent: decimal(e.target.value) })}
                hint="Charged only after a month short and notified" />
              <Input label="Minimum balance: most for a month" numeric value={f.minMax} onChange={(e) => set({ minMax: decimal(e.target.value) })} />
              <Input label="Closure charge" numeric value={f.closure} onChange={(e) => set({ closure: decimal(e.target.value) })} />
              {f.closure && (
                <>
                  <Input label="No closure charge within (days of opening)" numeric value={f.closureFree} onChange={(e) => set({ closureFree: digits(e.target.value) })} />
                  <Input label="No closure charge after (days)" numeric hint="Empty for no limit" value={f.closureUntil} onChange={(e) => set({ closureUntil: digits(e.target.value) })} />
                </>
              )}
            </div>
            {f.service.map((x, i) => (
              <div className="form-grid" key={i}>
                <Input label={`Service charge ${i + 1}: code`} value={x.code} maxLength={30} onChange={(e) => set({ service: f.service.map((y, k) => (k === i ? { ...y, code: e.target.value.toUpperCase() } : y)) })} />
                <Input label={`Service charge ${i + 1}: name`} value={x.name} maxLength={100} onChange={(e) => set({ service: f.service.map((y, k) => (k === i ? { ...y, name: e.target.value } : y)) })} />
                <Input label={`Service charge ${i + 1}: amount`} numeric value={x.amount} onChange={(e) => set({ service: f.service.map((y, k) => (k === i ? { ...y, amount: decimal(e.target.value) } : y)) })} />
                <div style={{ alignSelf: 'end' }}>
                  <Button size="sm" aria-label={`Remove service charge ${i + 1}`} onClick={() => set({ service: f.service.filter((_, k) => k !== i) })}>Remove</Button>
                </div>
              </div>
            ))}
            <div><Button size="sm" onClick={() => set({ service: [...f.service, { code: '', name: '', amount: '' }] })}>Add service charge</Button></div>
          </div>
        </Card>
      )}

      {casa ? (
        <Card title="Interest">
          {!showBands ? (
            <p className="muted" style={{ margin: 0 }}>A current account bears no interest under this institution’s rule set.</p>
          ) : (
            <div className="stack">
              {uniform !== null && <p className="muted" style={{ margin: 0 }}>One rate applies to balances up to {uniform.toLocaleString('en-IN')}; a second band may start above that.</p>}
              {f.bands.map((b, i) => (
                <div className="form-grid" key={i}>
                  <Input label={`Band ${i + 1}: from balance`} numeric value={b.from} disabled={i === 0} onChange={(e) => set({ bands: f.bands.map((x, k) => (k === i ? { ...x, from: decimal(e.target.value) } : x)) })} />
                  <Input label={`Band ${i + 1}: rate % a year`} numeric value={b.rate} onChange={(e) => set({ bands: f.bands.map((x, k) => (k === i ? { ...x, rate: decimal(e.target.value) } : x)) })} />
                  {i > 0 && (
                    <div style={{ alignSelf: 'end' }}>
                      <Button size="sm" aria-label={`Remove band ${i + 1}`} onClick={() => set({ bands: f.bands.filter((_, k) => k !== i) })}>Remove</Button>
                    </div>
                  )}
                </div>
              ))}
              <div><Button size="sm" onClick={() => set({ bands: [...f.bands, { from: f.bands.length ? '' : '0', rate: '' }] })}>Add band</Button></div>
              <div className="form-grid">
                <Select label="A balance across bands earns" value={f.bandMode} onChange={(e) => set({ bandMode: e.target.value })} options={[{ value: 'WHOLE_BALANCE', label: 'The rate of its band, on the whole balance' }, { value: 'INCREMENTAL', label: 'Each part the rate of its own band' }]} />
                <Select label="Interest is credited" value={f.posting} onChange={(e) => set({ posting: e.target.value })} options={[1, 3, 6, 12].map((m) => ({ value: String(m), label: PERIOD_LABEL[m] }))} />
                <Select label="Day count" value={f.dayCount} onChange={(e) => set({ dayCount: e.target.value })} options={[{ value: 'ACTUAL_365', label: 'Actual/365' }, { value: 'ACTUAL_360', label: 'Actual/360' }]} />
                <Checkbox label="Deduct tax at source from interest" checked={f.tds} onChange={(e) => set({ tds: e.target.checked })} />
              </div>
            </div>
          )}
        </Card>
      ) : (
        <>
          <Card title="Tenure and interest">
            <div className="form-grid">
              <Input label="Shortest tenure: months" numeric value={f.minMonths} onChange={(e) => set({ minMonths: digits(e.target.value) })} hint={minTenure !== null ? `Rule set: at least ${minTenure} months` : undefined} />
              <Input label="Shortest tenure: days" numeric value={f.minDays} onChange={(e) => set({ minDays: digits(e.target.value) })} />
              <Input label="Longest tenure: months" numeric value={f.maxMonths} onChange={(e) => set({ maxMonths: digits(e.target.value) })} hint={maxTenure !== null ? `Rule set: at most ${maxTenure} months` : undefined} />
              <Input label="Longest tenure: days" numeric value={f.maxDays} onChange={(e) => set({ maxDays: digits(e.target.value) })} />
              <Select
                label={f.productType === 'FD_CUMULATIVE' ? 'Interest is compounded' : 'Interest is paid out'}
                value={f.period}
                onChange={(e) => set({ period: e.target.value })}
                options={[...(f.productType === 'FD_PAYOUT' ? [0] : []), 1, 3, 6, 12].map((m) => ({ value: String(m), label: PERIOD_LABEL[m] }))}
              />
              <Select label="Day count" value={f.dayCount} onChange={(e) => set({ dayCount: e.target.value })} options={[{ value: 'ACTUAL_365', label: 'Actual/365' }, { value: 'ACTUAL_360', label: 'Actual/360' }, { value: 'ACTUAL_ACTUAL', label: 'Actual/actual' }]} />
              <Select label="Rounding of each interest credit" value={f.rounding} onChange={(e) => set({ rounding: e.target.value })} options={['PAISE_HALF_UP', 'PAISE_HALF_EVEN', 'RUPEE_HALF_UP', 'RUPEE_DOWN', 'RUPEE_UP'].map((r) => ({ value: r, label: r.replace(/_/g, ' ').toLowerCase() }))} />
              <Checkbox label="Deduct tax at source from interest" checked={f.tds} onChange={(e) => set({ tds: e.target.checked })} />
              <Checkbox label="Renewal at maturity may be chosen" checked={f.renewal} onChange={(e) => set({ renewal: e.target.checked })} />
            </div>
          </Card>

          <Card title="Rate card">
            <p className="muted" style={{ marginTop: 0 }}>
              Each cell is the full annual rate for deposits from that amount and from that tenure, up to the next step. Every cell needs a rate, and the first steps
              must cover the smallest deposit and the shortest tenure{ceiling !== null ? `. No rate, with every add-on, may be above ${ceiling}%` : ''}.
            </p>
            <div className="table-wrap">
              <table className="table" aria-label="Rate card">
                <thead>
                  <tr>
                    <th scope="col">Tenure from (months, days)</th>
                    {f.amounts.map((a, ai) => (
                      <th scope="col" key={a.id}>
                        <div className="row" style={{ gap: 4 }}>
                          <Input label={`Amount step ${ai + 1}: from`} labelHidden numeric placeholder="From amount" value={a.value} onChange={(e) => set({ amounts: f.amounts.map((x) => (x.id === a.id ? { ...x, value: decimal(e.target.value) } : x)) })} />
                          {f.amounts.length > 1 && <Button size="sm" aria-label={`Remove amount step ${ai + 1}`} onClick={() => set({ amounts: f.amounts.filter((x) => x.id !== a.id) })}>×</Button>}
                        </div>
                      </th>
                    ))}
                    <th scope="col"><Button size="sm" onClick={() => set({ amounts: [...f.amounts, step('')] })}>Add amount step</Button></th>
                  </tr>
                </thead>
                <tbody>
                  {f.tenures.map((t, ti) => (
                    <tr key={t.id}>
                      <th scope="row">
                        <div className="row" style={{ gap: 4 }}>
                          <Input label={`Tenure step ${ti + 1}: months`} labelHidden numeric placeholder="Months" style={{ width: 80 }} value={t.value} onChange={(e) => set({ tenures: f.tenures.map((x) => (x.id === t.id ? { ...x, value: digits(e.target.value) } : x)) })} />
                          <Input label={`Tenure step ${ti + 1}: days`} labelHidden numeric placeholder="Days" style={{ width: 70 }} value={t.days} onChange={(e) => set({ tenures: f.tenures.map((x) => (x.id === t.id ? { ...x, days: digits(e.target.value) } : x)) })} />
                          {f.tenures.length > 1 && <Button size="sm" aria-label={`Remove tenure step ${ti + 1}`} onClick={() => set({ tenures: f.tenures.filter((x) => x.id !== t.id) })}>×</Button>}
                        </div>
                      </th>
                      {f.amounts.map((a, ai) => (
                        <td key={a.id}>
                          <Input label={`Rate for amount step ${ai + 1}, tenure step ${ti + 1}`} labelHidden numeric placeholder="% a year" style={{ width: 100 }} value={f.rates[`${a.id}:${t.id}`] ?? ''} onChange={(e) => set({ rates: { ...f.rates, [`${a.id}:${t.id}`]: decimal(e.target.value) } })} />
                        </td>
                      ))}
                      <td />
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div style={{ marginTop: 8 }}><Button size="sm" onClick={() => set({ tenures: [...f.tenures, step('', '0')] })}>Add tenure step</Button></div>
          </Card>

          <Card title="Add-ons and premature withdrawal">
            <div className="stack">
              {f.addOns.map((a, i) => (
                <div className="form-grid" key={i}>
                  <Input label={`Add-on ${i + 1}: code`} className="mono" value={a.code} hint={i === 0 ? 'SENIOR_CITIZEN is given only to an individual of senior-citizen age' : undefined} onChange={(e) => set({ addOns: f.addOns.map((x, k) => (k === i ? { ...x, code: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '') } : x)) })} />
                  <Input label={`Add-on ${i + 1}: points`} numeric value={a.percent} onChange={(e) => set({ addOns: f.addOns.map((x, k) => (k === i ? { ...x, percent: signed(e.target.value) } : x)) })} />
                  <div style={{ alignSelf: 'end' }}><Button size="sm" aria-label={`Remove add-on ${i + 1}`} onClick={() => set({ addOns: f.addOns.filter((_, k) => k !== i) })}>Remove</Button></div>
                </div>
              ))}
              <div><Button size="sm" onClick={() => set({ addOns: [...f.addOns, { code: f.addOns.length ? '' : 'SENIOR_CITIZEN', percent: '' }] })}>Add an add-on</Button></div>
              <div className="form-grid">
                <Checkbox label="Premature withdrawal allowed" checked={f.premature} onChange={(e) => set({ premature: e.target.checked })} />
                {prematureFixed ? (
                  <p className="muted" style={{ margin: 0 }} data-testid="premature-fixed">The interest paid on premature repayment is fixed by the rule set for this institution; the product sets no penalty of its own.</p>
                ) : (
                  f.premature && (
                    <>
                      <Input label="Penalty: points off the rate for the period run" numeric value={f.penalty} onChange={(e) => set({ penalty: decimal(e.target.value) })} hint="0 for none" />
                      <Input label="No interest when closed within (days)" numeric value={f.prematureMinDays} onChange={(e) => set({ prematureMinDays: digits(e.target.value) })} hint="0 for none" />
                    </>
                  )
                )}
              </div>
            </div>
          </Card>
        </>
      )}

      <Card title="General ledger">
        <div className="form-grid">
          <Input label="Deposits (liability head)" className="mono" value={f.glLiability} onChange={(e) => set({ glLiability: e.target.value.trim() })} placeholder={casa ? (f.productType === 'SAVINGS' ? '2401' : '2402') : '2403'} hint="Empty for the standard head" />
          {(!casa || showBands) && <Input label="Interest (expense head)" className="mono" value={f.glInterestExpense} onChange={(e) => set({ glInterestExpense: e.target.value.trim() })} placeholder={casa ? '5108' : '5107'} hint="Empty for the standard head" />}
        </div>
      </Card>

      <ErrorBanner error={rules.error} />
      <Problems error={check.error ?? propose.error} title="This product cannot be approved:" />
      {checked && checked.length > 0 && (
        <Banner tone="danger">
          <div>
            <strong>This product cannot be approved:</strong>
            <ul style={{ margin: '4px 0 0', paddingLeft: 18 }} aria-label="Problems">
              {checked.map((p) => <li key={p}>{p}</li>)}
            </ul>
          </div>
        </Banner>
      )}
      {checked && checked.length === 0 && <Banner tone="ok">Nothing in the rule set stops this product.</Banner>}
      <div className="row" style={{ justifyContent: 'flex-end', gap: 8 }}>
        <Button loading={check.isPending} onClick={() => (setTouched(true), valid && check.mutate(input()))}>Check product</Button>
        <Button
          variant="primary"
          loading={propose.isPending}
          onClick={() => {
            setTouched(true);
            if (!valid) return;
            propose.mutate(input(), { onSuccess: (a) => (toast(a, 'Deposit product'), navigate('/deposits/products')) });
          }}
        >
          Submit for approval
        </Button>
      </div>
    </div>
  );
}
