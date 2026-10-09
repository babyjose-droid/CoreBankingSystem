import { useState } from 'react';
import { Link } from 'react-router';
import { useMe } from '../../api/hooks';
import { useProposeResetPreference, useRateResetsApplied, useRateResetsUpcoming } from '../../api/lendingHooks';
import type { Loan, RateResetOption } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, Checkbox, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Select, Spinner, Table, Textarea } from '../../ui';
import { useProposalToast } from '../proposal';
import { Stat, pct } from './common';

export const RESET_OPTION_LABEL: Record<RateResetOption, string> = {
  KEEP_EMI_CHANGE_TENURE: 'Keep the EMI, change the tenure',
  KEEP_TENURE_CHANGE_EMI: 'Keep the tenure, change the EMI',
};

/** What a reset does, in the words the borrower and the product form use (RBI 18-Aug-2023). */
export const RESET_HELP =
  'At each reset the rate becomes the benchmark rate on the reset date plus the spread, also outside the product band (that is flagged). ' +
  'Keeping the EMI lengthens the tenure; when that would pass the product’s maximum tenure, amortise negatively, or extend the loan of a borrower in arrears, the EMI is raised instead. ' +
  'A combination of both, or a switch to a fixed rate, is an amendment.';

const optionLabel = (o: string | null | undefined) => (o ? (RESET_OPTION_LABEL[o as RateResetOption] ?? o) : '—');

/** Benchmark, spread, next reset and what a reset changes, on the loan detail page. */
export function FloatingRateCard({ loan }: { loan: Loan }) {
  const me = useMe().data!;
  const [editing, setEditing] = useState(false);
  if (!loan.benchmarkCode) return null;
  const fixed = !!loan.rateFixedSince;
  const canChange = hasPermission(me.permissions, P.loanAmend) && !fixed && ['SANCTIONED', 'ACTIVE', 'FROZEN'].includes(loan.status ?? '');
  return (
    <Card
      title="Floating rate"
      actions={
        canChange ? (
          <Button size="sm" onClick={() => setEditing(true)}>
            Change reset choice…
          </Button>
        ) : undefined
      }
    >
      <div className="stack">
        {fixed && (
          <Banner tone="info">
            <span>
              Switched to a fixed rate on <DateText value={loan.rateFixedSince} />: the rate is no longer reset.
            </span>
          </Banner>
        )}
        {loan.rateOutsideBand && (
          <Banner tone="warn">
            <span data-testid="outside-band">The last reset set a rate outside the product’s rate band. It was applied (benchmark-linked resets are not limited by the band, decision D-14).</span>
          </Banner>
        )}
        <div className="stat-grid" data-testid="floating-rate">
          <Stat label="Benchmark">
            <span className="mono">{loan.benchmarkCode}</span> {loan.benchmarkRate ? pct(loan.benchmarkRate) : '—'}
          </Stat>
          <Stat label="Spread">{pct(loan.spread)}</Stat>
          <Stat label="Rate now">{pct(loan.currentRate ?? loan.rate)}</Stat>
          <Stat label="Reset every">{loan.resetFrequencyMonths ? `${loan.resetFrequencyMonths} months` : '—'}</Stat>
          <Stat label="Next reset" testId="next-reset">
            {fixed ? <span className="muted">None (fixed)</span> : <DateText value={loan.nextRateReset} />}
          </Stat>
          <Stat label="At a reset" testId="reset-choice">
            {optionLabel(loan.resetPreference ?? loan.resetDefault)}
            <div className="muted" style={{ fontSize: 12, fontWeight: 400 }}>{loan.resetPreference ? 'borrower’s choice' : 'product default'}</div>
          </Stat>
        </div>
      </div>
      {editing && <ResetPreferenceDialog loan={loan} onClose={() => setEditing(false)} />}
    </Card>
  );
}

function ResetPreferenceDialog({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const [option, setOption] = useState<string>(loan.resetPreference ?? '');
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const save = useProposeResetPreference(loan.id!);
  const toast = useProposalToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Reset choice for loan ${loan.loanNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={save.isPending}
            onClick={() => {
              setTouched(true);
              if (!reason.trim()) return;
              save.mutate({ option: (option || null) as RateResetOption | null, reason: reason.trim() }, {
                onSuccess: (a) => {
                  toast(a, 'Reset choice');
                  onClose();
                },
              });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>{RESET_HELP}</p>
        <Select
          label="At the next resets"
          value={option}
          onChange={(e) => setOption(e.target.value)}
          options={[
            { value: '', label: `Product default (${optionLabel(loan.resetDefault)})` },
            { value: 'KEEP_EMI_CHANGE_TENURE', label: RESET_OPTION_LABEL.KEEP_EMI_CHANGE_TENURE },
            { value: 'KEEP_TENURE_CHANGE_EMI', label: RESET_OPTION_LABEL.KEEP_TENURE_CHANGE_EMI },
          ]}
          hint="The borrower’s instruction; it applies once a checker approves it, from the next reset"
        />
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} hint="How the borrower gave the instruction" error={touched && !reason.trim() ? 'A reason is required' : null} />
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}

/** Operations page: floating-rate loans due for a reset, and resets applied by the day-end. */
export function RateResetsPage() {
  const [days, setDays] = useState('30');
  const n = /^\d+$/.test(days) && Number(days) <= 366 ? Number(days) : 30;
  const due = useRateResetsUpcoming(n);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [outside, setOutside] = useState(false);
  const applied = useRateResetsApplied({ ...(from ? { from } : {}), ...(to ? { to } : {}), outsideBandOnly: outside });
  return (
    <div className="stack">
      <PageHeader
        title="Rate resets"
        subtitle="Floating-rate loans are reset at day-end on their reset date: the benchmark rate on that date plus the loan’s spread. Also as reports RATE_RESETS_DUE and RATE_RESETS_APPLIED."
      />
      <Card title="Due for a reset" flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Input label="Days ahead" numeric value={days} onChange={(e) => setDays(e.target.value)} hint="0 to 366" />
        </div>
        <ErrorBanner error={due.error} />
        {due.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Loans due for a rate reset"
            captionHidden
            columns={[
              { key: 'date', header: 'Reset date', render: (r) => <DateText value={r.nextRateReset} /> },
              { key: 'loan', header: 'Loan', render: (r) => <Link to={`/loans/${r.loanId}`} className="mono">{r.loanNo}</Link> },
              { key: 'cust', header: 'Customer', render: (r) => r.customerName },
              { key: 'bm', header: 'Benchmark', render: (r) => (<span><span className="mono">{r.benchmarkCode}</span> {r.benchmarkRate ? pct(r.benchmarkRate) : '—'}</span>) },
              { key: 'spread', header: 'Spread', numeric: true, render: (r) => pct(r.spread) },
              { key: 'now', header: 'Rate now', numeric: true, render: (r) => pct(r.currentRate) },
              { key: 'proj', header: 'Rate at reset', numeric: true, render: (r) => (<span>{r.projectedRate ? pct(r.projectedRate) : '—'}{r.projectedOutsideBand && <> <Badge tone="warn">Outside band</Badge></>}</span>) },
              { key: 'opt', header: 'At the reset', render: (r) => (<span>{optionLabel(r.resetOption)}{r.held && <> <Badge tone="warn">Held: account frozen</Badge></>}</span>) },
              {
                key: 'est',
                header: 'Estimated EMI',
                numeric: true,
                render: (r) => (r.estimatedEmi ? (<span title={r.estimateNote ?? undefined}><MoneyText value={r.estimatedEmi} />{r.estimatedInstalmentsLeft != null && <div className="muted" style={{ fontSize: 12 }}>{r.estimatedInstalmentsLeft} left</div>}</span>) : <span className="muted" title={r.estimateNote ?? undefined}>—</span>),
              },
              { key: 'po', header: 'Principal', numeric: true, render: (r) => <MoneyText value={r.principalOutstanding} /> },
            ]}
            rows={due.data ?? []}
            rowKey={(r) => r.loanId ?? r.loanNo ?? ''}
            empty={<EmptyState title={`No resets in the next ${n} days`} />}
          />
        )}
      </Card>
      <Card title="Applied" flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Input label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} hint="Blank: start of the month" />
          <Input label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} hint="Blank: the business date" />
          <Checkbox label="Outside the band only" checked={outside} onChange={(e) => setOutside(e.target.checked)} />
        </div>
        <ErrorBanner error={applied.error} />
        {applied.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Rate resets applied"
            captionHidden
            columns={[
              { key: 'date', header: 'Effective', render: (r) => <DateText value={r.effectiveDate} /> },
              { key: 'loan', header: 'Loan', render: (r) => <Link to={`/loans/${r.loanId}`} className="mono">{r.loanNo}</Link> },
              { key: 'kind', header: 'Change', render: (r) => (r.kind === 'RATE_STEP' ? 'Elapsed-tenure step' : `${r.benchmarkCode ?? ''} ${r.benchmarkRate ? pct(r.benchmarkRate) : ''} + ${pct(r.spread)}`) },
              { key: 'rate', header: 'Rate', render: (r) => `${pct(r.rateBefore)} → ${pct(r.rateAfter)}` },
              { key: 'emi', header: 'EMI', render: (r) => (<span><MoneyText value={r.emiBefore} /> → <MoneyText value={r.emiAfter} /></span>) },
              { key: 'ten', header: 'Instalments left', render: (r) => `${r.tenureBefore} → ${r.tenureAfter}` },
              {
                key: 'opt',
                header: 'Applied',
                render: (r) => (
                  <span>
                    {optionLabel(r.appliedOption)}
                    {r.fallbackReason && r.appliedOption !== r.requestedOption && (
                      <>
                        <br />
                        <span className="muted" style={{ fontSize: 12 }}>EMI raised instead: {r.fallbackReason}</span>
                      </>
                    )}
                  </span>
                ),
              },
              { key: 'flag', header: <span className="sr-only">Flags</span>, render: (r) => (r.outsideBand ? <Badge tone="warn">Outside band</Badge> : null) },
            ]}
            rows={applied.data ?? []}
            rowKey={(r) => `${r.loanId}-${r.effectiveDate}-${r.kind}`}
            empty={<EmptyState title="No resets applied in the period" />}
          />
        )}
      </Card>
    </div>
  );
}
