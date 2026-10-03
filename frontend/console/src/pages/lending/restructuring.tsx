import { useState, type ReactNode } from 'react';
import { usePreviewAmendment, useProposeAmendment, useProposeRestructure, useSimulateRestructure } from '../../api/lendingHooks';
import type { AmendmentKind, AmendmentRequest, Loan, RestructureOption, RestructureSimulation, RestructureTerms } from '../../api/types';
import { Badge, Banner, Button, Card, DateText, Dialog, ErrorBanner, Input, MoneyText, Select, Textarea } from '../../ui';
import { useProposalToast } from '../proposal';
import { AssetClassBadge, moneyInput, pct } from './common';
import { ScheduleTable } from './KfsView';

type RateOption = NonNullable<AmendmentRequest['rateOption']>;

const KIND_LABEL: Record<AmendmentKind, string> = {
  RATE_CHANGE: 'Rate change',
  TENURE_CHANGE: 'Tenure change',
  EMI_CHANGE: 'EMI change',
  DUE_DAY_CHANGE: 'Due-day change',
  MATURITY_CHANGE: 'Maturity date change',
};
const OTHER_KIND_LABEL: Record<string, string> = { RESTRUCTURE: 'Restructure', SANCTION_CHANGE: 'Sanction change', NPA_OVERRIDE: 'NPA override' };
export const amendmentKindLabel = (k: string | undefined) => OTHER_KIND_LABEL[k ?? ''] ?? KIND_LABEL[k as AmendmentKind] ?? k ?? '';

const RATE_OPTIONS: Array<{ value: RateOption; label: string }> = [
  { value: 'KEEP_EMI_CHANGE_TENURE', label: 'Keep EMI, change tenure' },
  { value: 'KEEP_TENURE_CHANGE_EMI', label: 'Keep tenure, change EMI' },
  { value: 'CHANGE_BOTH', label: 'Change both' },
];

const isInt = (v: string, min: number, max: number) => /^\d+$/.test(v) && Number(v) >= min && Number(v) <= max;
const isRate = (v: string) => /^\d+(\.\d+)?$/.test(v.trim()) && Number(v) <= 100;

/** Two-column before/after comparison; `changed` rows are emphasised. */
function Compare({ caption, rows }: { caption: string; rows: Array<[label: string, before: ReactNode, after: ReactNode, changed: boolean]> }) {
  return (
    <table className="table" aria-label={caption}>
      <thead>
        <tr>
          <th scope="col">Figure</th>
          <th scope="col" className="num">Before</th>
          <th scope="col" className="num">After</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(([label, before, after, changed]) => (
          <tr key={label} data-changed={changed || undefined}>
            <th scope="row">{label}</th>
            <td className="num">{before}</td>
            <td className="num" style={changed ? { fontWeight: 600 } : undefined}>
              {after}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ---------------------------------------------------------------- amendment
export function AmendDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const [kind, setKind] = useState<AmendmentKind>('RATE_CHANGE');
  const [f, setF] = useState({ rate: loan.currentRate ?? loan.rate ?? '', rateOption: 'KEEP_EMI_CHANGE_TENURE' as RateOption, instalments: '', emi: '', dueDay: '', maturity: '', reason: '' });
  const [touched, setTouched] = useState(false);
  const preview = usePreviewAmendment(loan.id!);
  const propose = useProposeAmendment(loan.id!);
  const toast = useProposalToast();

  const both = kind === 'RATE_CHANGE' && f.rateOption === 'CHANGE_BOTH';
  const errs: Record<string, string | null> = {
    rate: kind === 'RATE_CHANGE' && !isRate(f.rate) ? 'Enter the new rate' : null,
    instalments: (kind === 'TENURE_CHANGE' || (both && !f.emi)) && !isInt(f.instalments, 1, 480) ? (both ? 'Instalments (1-480) or a new EMI' : '1 to 480') : null,
    emi: (kind === 'EMI_CHANGE' || (both && !f.instalments)) && !moneyInput(f.emi) ? (both ? 'A new EMI or the instalments' : 'Enter the new EMI') : null,
    dueDay: kind === 'DUE_DAY_CHANGE' && !isInt(f.dueDay, 1, 31) ? '1 to 31 (31 = month end)' : null,
    maturity: kind === 'MATURITY_CHANGE' && !f.maturity ? 'Choose the new maturity date' : null,
  };
  const valid = Object.values(errs).every((e) => !e);
  const request: AmendmentRequest = {
    kind,
    ...(kind === 'RATE_CHANGE' ? { newRatePercent: f.rate.trim(), rateOption: f.rateOption } : {}),
    ...((kind === 'TENURE_CHANGE' || (both && f.instalments)) ? { remainingInstalments: Number(f.instalments) } : {}),
    ...((kind === 'EMI_CHANGE' || (both && !f.instalments && f.emi)) ? { newEmi: moneyInput(f.emi) ?? f.emi } : {}),
    ...(kind === 'DUE_DAY_CHANGE' ? { newDueDay: Number(f.dueDay) } : {}),
    ...(kind === 'MATURITY_CHANGE' ? { newMaturityDate: f.maturity } : {}),
  };
  const key = JSON.stringify(request);
  const [previewedKey, setPreviewedKey] = useState<string | null>(null);
  const fresh = !!preview.data && previewedKey === key;
  const set = (patch: Partial<typeof f>) => setF((x) => ({ ...x, ...patch }));
  const err = (k: string) => (touched ? errs[k] : null);
  const p = preview.data;

  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={`Amend loan ${loan.loanNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            loading={preview.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              propose.reset();
              preview.mutate(request, { onSuccess: () => setPreviewedKey(key) });
            }}
          >
            Preview
          </Button>
          <Button
            variant="primary"
            disabled={!fresh}
            title={fresh ? undefined : 'Preview the current terms first'}
            loading={propose.isPending}
            onClick={() => {
              if (!f.reason.trim()) {
                setTouched(true);
                return;
              }
              propose.mutate({ ...request, reason: f.reason.trim() }, { onSuccess: (a) => (toast(a, 'Amendment'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <div className="form-grid">
          <Select label="Change" value={kind} onChange={(e) => setKind(e.target.value as AmendmentKind)} options={(Object.keys(KIND_LABEL) as AmendmentKind[]).map((k) => ({ value: k, label: KIND_LABEL[k] }))} />
          {kind === 'RATE_CHANGE' && (
            <>
              <Input label="New rate % p.a." required numeric value={f.rate} onChange={(e) => set({ rate: e.target.value })} hint={`Now ${pct(loan.currentRate ?? loan.rate)}`} error={err('rate')} />
              <Select label="Borrower's choice" value={f.rateOption} onChange={(e) => set({ rateOption: e.target.value as RateOption })} options={RATE_OPTIONS} hint="RBI 18-Aug-2023: the borrower chooses" />
            </>
          )}
          {(kind === 'TENURE_CHANGE' || both) && (
            <Input label="Remaining instalments" required={!both} numeric value={f.instalments} onChange={(e) => set({ instalments: e.target.value })} error={err('instalments')} />
          )}
          {(kind === 'EMI_CHANGE' || both) && (
            <Input label="New EMI" required={!both} numeric value={f.emi} onChange={(e) => set({ emi: e.target.value })} hint={both ? 'Or give the instalments instead' : `Now ${loan.emi ?? '—'}`} error={err('emi')} />
          )}
          {kind === 'MATURITY_CHANGE' && (
            <Input label="New maturity date" required type="date" value={f.maturity} onChange={(e) => set({ maturity: e.target.value })} hint="The last instalment falls due in this month, on the loan's due day; the EMI is recomputed" error={err('maturity')} />
          )}
          {kind === 'DUE_DAY_CHANGE' && <Input label="New due day" required numeric value={f.dueDay} onChange={(e) => set({ dueDay: e.target.value })} hint="Day of the month; 31 = month end" error={err('dueDay')} />}
        </div>
        <Textarea label="Reason" required maxLength={500} value={f.reason} onChange={(e) => set({ reason: e.target.value })} error={touched && fresh && !f.reason.trim() ? 'A reason is required to propose' : null} />
        <ErrorBanner error={preview.error ?? propose.error} />
        {p && !fresh && <Banner tone="info">The change was edited since the last preview. Preview again before proposing.</Banner>}
        {p && fresh && (
          <div className="stack" data-testid="amendment-preview">
            <Compare
              caption="Before and after"
              rows={[
                ['Rate', `${pct(p.rateBefore)}`, `${pct(p.rateAfter)}`, p.rateBefore !== p.rateAfter],
                ['EMI', <MoneyText value={p.emiBefore} />, <MoneyText value={p.emiAfter} />, p.emiBefore !== p.emiAfter],
                ['Remaining instalments', p.remainingBefore, p.remainingAfter, p.remainingBefore !== p.remainingAfter],
                ['Next due', <DateText value={p.nextDueBefore} />, <DateText value={p.nextDueAfter} />, p.nextDueBefore !== p.nextDueAfter],
                ['Maturity', <DateText value={p.maturityBefore} />, <DateText value={p.maturityAfter} />, p.maturityBefore !== p.maturityAfter],
                ['Total interest to come', <MoneyText value={p.interestBefore} />, <MoneyText value={p.interestAfter} />, p.interestBefore !== p.interestAfter],
              ]}
            />
            <p className="muted" style={{ margin: 0, fontSize: 13 }}>
              Principal <MoneyText value={p.principal} /> · interest accrued since the last due date <MoneyText value={p.accruedInterest} /> (carried into the next instalment)
              {p.brokenPeriodInterest && Number(p.brokenPeriodInterest) > 0 ? (
                <>
                  {' '}
                  · broken-period interest <MoneyText value={p.brokenPeriodInterest} />
                </>
              ) : null}
              . A checker applies it; if the loan changes before then, the figures at approval apply.
            </p>
            <Card title="New schedule" flush headingLevel={3}>
              <ScheduleTable rows={p.schedule ?? []} caption="New schedule" />
            </Card>
          </div>
        )}
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- restructure
interface OptionDraft {
  key: number;
  instalments: string;
  rate: string;
  moratorium: string;
  overdueInterest: 'CAPITALISE' | 'KEEP_AS_ARREARS';
}

const toTerms = (o: OptionDraft): RestructureTerms => ({
  remainingInstalments: Number(o.instalments),
  overdueInterest: o.overdueInterest,
  principalMoratoriumMonths: o.moratorium ? Number(o.moratorium) : 0,
  ...(o.rate.trim() ? { newRatePercent: o.rate.trim() } : {}),
});

export function RestructureDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const [opts, setOpts] = useState<OptionDraft[]>([{ key: 1, instalments: '', rate: '', moratorium: '0', overdueInterest: 'CAPITALISE' }]);
  const [touched, setTouched] = useState(false);
  const [chosen, setChosen] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [reasonTouched, setReasonTouched] = useState(false);
  const sim = useSimulateRestructure(loan.id!);
  const propose = useProposeRestructure(loan.id!);
  const toast = useProposalToast();
  const errs = opts.map((o) => ({
    instalments: !isInt(o.instalments, 1, 480) ? '1 to 480' : null,
    rate: o.rate.trim() && !isRate(o.rate) ? 'Invalid rate' : null,
    moratorium: o.moratorium && (!/^\d+$/.test(o.moratorium) || (isInt(o.instalments, 1, 480) && Number(o.moratorium) >= Number(o.instalments))) ? 'Shorter than the tenure' : null,
  }));
  const valid = errs.every((e) => !e.instalments && !e.rate && !e.moratorium);
  const terms = opts.map(toTerms);
  const key = JSON.stringify(terms);
  const [simKey, setSimKey] = useState<string | null>(null);
  const fresh = !!sim.data && simKey === key;
  const update = (k: number, patch: Partial<OptionDraft>) => setOpts((os) => os.map((o) => (o.key === k ? { ...o, ...patch } : o)));
  const npa = ['SUBSTANDARD', 'DOUBTFUL1', 'DOUBTFUL2', 'DOUBTFUL3', 'LOSS'].includes(loan.assetClass ?? '');

  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={`Restructure loan ${loan.loanNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            loading={sim.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              setChosen(null);
              propose.reset();
              sim.mutate(terms, { onSuccess: () => setSimKey(key) });
            }}
          >
            Simulate
          </Button>
          <Button
            variant="danger"
            disabled={!fresh || chosen === null}
            title={!fresh ? 'Simulate the current options first' : chosen === null ? 'Choose an option' : undefined}
            loading={propose.isPending}
            onClick={() => {
              setReasonTouched(true);
              if (chosen === null || !reason.trim()) return;
              propose.mutate({ ...terms[chosen], reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Restructure'), onClose()) });
            }}
          >
            {chosen === null ? 'Propose restructure' : `Propose option ${chosen + 1}`}
          </Button>
        </>
      }
    >
      <div className="stack">
        <Banner tone="warn">
          <span>
            <strong>Restructuring {npa ? 'keeps this NPA in its class' : 'downgrades this standard account to sub-standard (NPA)'}</strong> under the RBI Prudential Framework (7-Jun-2019). It can be upgraded only after the
            specified period (at least a year from the first payment under the new schedule, with 10% of principal repaid and no default). It needs <strong>two different checkers</strong> and
            cannot be reversed.
          </span>
        </Banner>
        <p className="muted" style={{ margin: 0, fontSize: 13 }}>Overdue principal is always rescheduled into the new schedule. Compare up to three options.</p>
        {opts.map((o, i) => (
          <fieldset key={o.key} className="fee-row" aria-label={`Option ${i + 1}`}>
            <legend>Option {i + 1}</legend>
            <Input label={`Option ${i + 1} instalments`} required numeric value={o.instalments} onChange={(e) => update(o.key, { instalments: e.target.value })} error={touched ? errs[i].instalments : null} />
            <Input label={`Option ${i + 1} new rate %`} numeric value={o.rate} placeholder={loan.currentRate ?? loan.rate} onChange={(e) => update(o.key, { rate: e.target.value })} hint="Empty keeps the rate" error={touched ? errs[i].rate : null} />
            <Input label={`Option ${i + 1} principal moratorium (months)`} numeric value={o.moratorium} onChange={(e) => update(o.key, { moratorium: e.target.value })} error={touched ? errs[i].moratorium : null} />
            <Select
              label={`Option ${i + 1} overdue interest`}
              value={o.overdueInterest}
              onChange={(e) => update(o.key, { overdueInterest: e.target.value as OptionDraft['overdueInterest'] })}
              options={[
                { value: 'CAPITALISE', label: 'Capitalise' },
                { value: 'KEEP_AS_ARREARS', label: 'Keep as arrears' },
              ]}
            />
            <Button size="sm" variant="ghost" aria-label={`Remove option ${i + 1}`} disabled={opts.length === 1} onClick={() => setOpts((os) => os.filter((x) => x.key !== o.key))}>
              Remove
            </Button>
          </fieldset>
        ))}
        <div>
          <Button size="sm" disabled={opts.length >= 3} onClick={() => setOpts((os) => [...os, { key: Math.max(...os.map((x) => x.key)) + 1, instalments: '', rate: '', moratorium: '0', overdueInterest: 'CAPITALISE' }])}>
            Add option
          </Button>
        </div>
        <ErrorBanner error={sim.error ?? propose.error} />
        {sim.data && !fresh && <Banner tone="info">Options changed since the last simulation. Simulate again before proposing.</Banner>}
        {sim.data && fresh && (
          <>
            <SimulationTable current={sim.data.current} options={sim.data.options ?? []} chosen={chosen} onChoose={setChosen} />
            <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={reasonTouched && !reason.trim() ? 'A reason is required to propose' : null} />
          </>
        )}
      </div>
    </Dialog>
  );
}

function SimulationTable({
  current,
  options,
  chosen,
  onChoose,
}: {
  current: RestructureSimulation['current'];
  options: RestructureOption[];
  chosen: number | null;
  onChoose: (i: number) => void;
}) {
  const m = (v: string | undefined) => <MoneyText value={v} />;
  const rows: Array<[string, ReactNode, (o: RestructureOption) => ReactNode]> = [
    ['Asset class', <AssetClassBadge value={current?.assetClass} />, (o) => <AssetClassBadge value={o.classAfter} />],
    ['Rate', pct(current?.rate), (o) => pct(o.rateAfter)],
    ['EMI', m(current?.emi), (o) => m(o.emiAfter)],
    ['Remaining instalments', current?.remainingInstalments, (o) => o.remainingAfter],
    ['Maturity', <DateText value={options[0]?.maturityBefore} />, (o) => <DateText value={o.maturityAfter} />],
    ['Principal', m(current?.principalOutstanding), (o) => m(o.principalAfter)],
    ['Overdue principal rescheduled', '', (o) => m(o.overduePrincipalRescheduled)],
    ['Overdue interest capitalised', '', (o) => m(o.interestCapitalised)],
    ['Overdue interest kept as arrears', '', (o) => m(o.arrearsKept)],
    ['Total interest to come', m(options[0]?.interestBefore), (o) => m(o.interestAfter)],
    ['NPV at contract rate', m(options[0]?.npvBefore), (o) => m(o.npvAfter)],
    ['NPV difference (lender’s sacrifice)', '', (o) => <span data-testid="npv-loss">{m(o.npvLoss)}</span>],
    ['Upgrade not before', '', (o) => <DateText value={o.upgradeNotBefore} />],
  ];
  return (
    <div className="table-wrap">
      <table className="table" aria-label="Restructure options">
        <thead>
          <tr>
            <th scope="col">Figure</th>
            <th scope="col" className="num">
              Current {current?.dpd ? <Badge tone="warn">{current.dpd} DPD</Badge> : null}
            </th>
            {options.map((_, i) => (
              <th key={i} scope="col" className="num">
                Option {i + 1}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, cur, render]) => (
            <tr key={label}>
              <th scope="row">{label}</th>
              <td className="num">{cur}</td>
              {options.map((o, i) => (
                <td key={i} className="num">
                  {render(o)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td />
            <td />
            {options.map((_, i) => (
              <td key={i} className="num">
                <label className="checkbox" style={{ justifyContent: 'flex-end' }}>
                  <input type="radio" name="restructure-option" checked={chosen === i} onChange={() => onChoose(i)} aria-label={`Choose option ${i + 1}`} />
                  <span>Choose</span>
                </label>
              </td>
            ))}
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
