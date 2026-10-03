import { useState } from 'react';
import { useBenchmarks, useMe, useProposeBenchmark, useProposeBenchmarkRate } from '../../api/hooks';
import type { Benchmark } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, Checkbox, DateText, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Spinner, Table } from '../../ui';
import { useProposalToast } from '../proposal';

/** Rates are percentages with up to four decimals; trailing zeros are noise on screen. */
const pct = (v: string | null | undefined) => (v == null ? '—' : `${Number(v).toString()}%`);

export function BenchmarksPage() {
  const me = useMe().data!;
  const q = useBenchmarks();
  const canPropose = hasPermission(me.permissions, P.benchmarkPropose);
  const [adding, setAdding] = useState(false);
  const [rateFor, setRateFor] = useState<Benchmark | null>(null);
  const rows = q.data ?? [];
  return (
    <div className="stack">
      <PageHeader
        title="Benchmark rates"
        subtitle="Benchmarks of floating-rate products. A product's rate is the benchmark rate plus its spread."
        actions={canPropose && <Button variant="primary" onClick={() => setAdding(true)}>Propose benchmark</Button>}
      />
      <ErrorBanner error={q.error} />
      {q.isLoading ? (
        <Spinner />
      ) : rows.length === 0 ? (
        <Card><EmptyState title="No benchmarks" /></Card>
      ) : (
        rows.map((b) => (
          <Card
            key={b.code}
            flush
            title={`${b.code} — ${b.name}`}
            headingLevel={2}
            actions={canPropose && <Button size="sm" aria-label={`Propose rate for ${b.code}`} onClick={() => setRateFor(b)}>Propose rate</Button>}
          >
            <div className="row" style={{ padding: '8px 16px', gap: 16, flexWrap: 'wrap' }} data-testid={`benchmark-${b.code}`}>
              <span>Source: {b.source}</span>
              <Badge tone={b.external ? 'info' : 'neutral'}>{b.external ? 'External benchmark' : 'Internal benchmark'}</Badge>
              <span>
                Current rate: <strong>{pct(b.currentRate)}</strong>
                {b.currentFrom && <span className="muted"> from <DateText value={b.currentFrom} /></span>}
              </span>
              {!b.currentRate && <Badge tone="warn">No rate in force: loans on this benchmark cannot be opened without a rate</Badge>}
            </div>
            <Table
              caption={`Rate history of ${b.code}`}
              captionHidden
              columns={[
                { key: 'from', header: 'Effective from', render: (r) => <DateText value={r.effectiveFrom} /> },
                { key: 'rate', header: 'Rate', numeric: true, render: (r) => <span className="num">{pct(r.rate)}</span> },
                {
                  key: 'state',
                  header: 'State',
                  render: (r) =>
                    r.effectiveFrom > me.businessDate ? <Badge tone="info">Future</Badge> : r.effectiveFrom === b.currentFrom ? <Badge tone="ok">Current</Badge> : <Badge>Superseded</Badge>,
                },
                { key: 'by', header: 'Recorded by', render: (r) => <span className="mono">{r.recordedBy}</span> },
                { key: 'at', header: 'Recorded at', render: (r) => <DateTimeText value={r.recordedAt} /> },
              ]}
              rows={b.rates}
              rowKey={(r) => r.effectiveFrom}
              empty={<EmptyState title="No rate recorded yet" />}
            />
          </Card>
        ))
      )}
      {adding && <ProposeBenchmarkDialog existing={rows.map((b) => b.code)} onClose={() => setAdding(false)} />}
      {rateFor && <ProposeRateDialog benchmark={rateFor} businessDate={me.businessDate} onClose={() => setRateFor(null)} />}
    </div>
  );
}

function ProposeBenchmarkDialog({ existing, onClose }: { existing: string[]; onClose: () => void }) {
  const [b, setB] = useState({ code: '', name: '', source: '', external: true });
  const [touched, setTouched] = useState(false);
  const propose = useProposeBenchmark();
  const toast = useProposalToast();
  const errors = {
    code: !/^[A-Z0-9_]{2,20}$/.test(b.code) ? '2-20 upper-case letters, digits or underscores' : existing.includes(b.code) ? 'This benchmark already exists' : null,
    name: !b.name.trim() ? 'Required' : null,
    source: !b.source.trim() ? 'Required: who publishes the benchmark' : null,
  };
  const valid = Object.values(errors).every((e) => !e);
  return (
    <Dialog
      open
      onClose={onClose}
      title="Propose benchmark"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              propose.mutate({ ...b, name: b.name.trim(), source: b.source.trim() }, { onSuccess: (a) => (toast(a, 'Benchmark'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <Input label="Code" required className="mono" value={b.code} onChange={(e) => setB({ ...b, code: e.target.value.toUpperCase() })} error={touched ? errors.code : null} hint="e.g. MCLR1Y" />
        <Input label="Name" required value={b.name} onChange={(e) => setB({ ...b, name: e.target.value })} error={touched ? errors.name : null} />
        <Input label="Source" required value={b.source} onChange={(e) => setB({ ...b, source: e.target.value })} error={touched ? errors.source : null} hint="Who publishes it: RBI, FBIL, the lender's ALCO" />
        <Checkbox label="External benchmark (published outside the lender)" checked={b.external} onChange={(e) => setB({ ...b, external: e.target.checked })} />
      </div>
      <div style={{ marginTop: 12 }}>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}

function ProposeRateDialog({ benchmark, businessDate, onClose }: { benchmark: Benchmark; businessDate: string; onClose: () => void }) {
  const last = benchmark.rates[0]?.effectiveFrom ?? null;
  const [r, setR] = useState({ rate: '', effectiveFrom: last && last >= businessDate ? '' : businessDate });
  const [touched, setTouched] = useState(false);
  const propose = useProposeBenchmarkRate();
  const toast = useProposalToast();
  const errors = {
    rate: !/^\d{1,3}(\.\d{1,4})?$/.test(r.rate) || Number(r.rate) > 100 ? 'Enter a rate between 0 and 100 (up to four decimals)' : null,
    effectiveFrom: !r.effectiveFrom ? 'Required' : last && r.effectiveFrom <= last ? `Must be after the last recorded rate (${last})` : null,
  };
  const valid = Object.values(errors).every((e) => !e);
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Propose rate for ${benchmark.code}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              propose.mutate({ code: benchmark.code, rate: r.rate, effectiveFrom: r.effectiveFrom }, { onSuccess: (a) => (toast(a, 'Benchmark rate'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>
        The history is never rewritten: a new rate takes effect on a later date than the last one. It applies to new loans from that date.
        Running loans do not change by themselves — each becomes due for a rate reset on its next reset date.
      </p>
      <div className="form-grid">
        <Input label="Rate % p.a." required numeric value={r.rate} onChange={(e) => setR({ ...r, rate: e.target.value.replace(/[^0-9.]/g, '') })} error={touched ? errors.rate : null} hint={benchmark.currentRate ? `Now ${pct(benchmark.currentRate)}` : undefined} />
        <Input label="Effective from" type="date" required value={r.effectiveFrom} onChange={(e) => setR({ ...r, effectiveFrom: e.target.value })} error={touched ? errors.effectiveFrom : null} />
      </div>
      <div style={{ marginTop: 12 }}>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
