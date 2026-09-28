import { useState } from 'react';
import { useMe, useProposeTaxRate, useTaxRates } from '../../api/hooks';
import type { TaxRate } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, DateText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, Table } from '../../ui';
import { useProposalToast } from '../proposal';

export function rateState(t: TaxRate, asOf: string): 'Current' | 'Future' | 'Expired' {
  if (t.effectiveFrom > asOf) return 'Future';
  if (t.effectiveTo && t.effectiveTo < asOf) return 'Expired';
  return 'Current';
}

export function TaxRatesPage() {
  const me = useMe().data!;
  const [asOf, setAsOf] = useState('');
  const q = useTaxRates(asOf || undefined);
  const [proposing, setProposing] = useState(false);
  return (
    <div className="stack">
      <PageHeader title="Tax rates" subtitle="GST and TDS rates with effective dates." actions={hasPermission(me.permissions, P.taxRatePropose) && <Button variant="primary" onClick={() => setProposing(true)}>Propose rate</Button>} />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Input label="Effective as of" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} hint="Leave empty for full history" />
        </div>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Tax rates"
            captionHidden
            columns={[
              { key: 'code', header: 'Code', render: (t) => <span className="mono">{t.code}</span> },
              { key: 'type', header: 'Type', render: (t) => t.taxType },
              { key: 'rate', header: 'Rate %', numeric: true, render: (t) => <span className="num">{t.ratePercent}</span> },
              { key: 'from', header: 'Effective from', render: (t) => <DateText value={t.effectiveFrom} /> },
              { key: 'to', header: 'Effective to', render: (t) => <DateText value={t.effectiveTo} /> },
              {
                key: 'state',
                header: 'State',
                render: (t) => {
                  const s = rateState(t, me.businessDate);
                  return <Badge tone={s === 'Current' ? 'ok' : s === 'Future' ? 'info' : 'neutral'}>{s}</Badge>;
                },
              },
            ]}
            rows={q.data ?? []}
            rowKey={(t) => `${t.code}-${t.effectiveFrom}`}
            empty={<EmptyState title="No tax rates" />}
          />
        )}
      </Card>
      {proposing && <ProposeRateDialog onClose={() => setProposing(false)} />}
    </div>
  );
}

function ProposeRateDialog({ onClose }: { onClose: () => void }) {
  const me = useMe().data!;
  const [t, setT] = useState<TaxRate>({ code: '', taxType: 'GST', ratePercent: '', effectiveFrom: me.businessDate, effectiveTo: null });
  const [touched, setTouched] = useState(false);
  const propose = useProposeTaxRate();
  const toast = useProposalToast();
  const errors = {
    code: !/^[A-Z0-9]{2,20}$/.test(t.code) ? '2-20 upper-case letters or digits' : null,
    ratePercent: !/^\d{1,3}(\.\d{1,4})?$/.test(t.ratePercent) || Number(t.ratePercent) > 100 ? 'Enter a rate between 0 and 100' : null,
    effectiveFrom: !t.effectiveFrom ? 'Required' : null,
    effectiveTo: t.effectiveTo && t.effectiveTo < t.effectiveFrom ? 'Must be on or after effective from' : null,
  };
  const valid = Object.values(errors).every((e) => !e);
  return (
    <Dialog
      open
      onClose={onClose}
      title="Propose tax rate"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              propose.mutate({ ...t, effectiveTo: t.effectiveTo || null }, { onSuccess: (a) => (toast(a, 'Tax rate'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>
        Proposing an existing code closes its current rate the day before the new one takes effect.
      </p>
      <div className="form-grid">
        <Input label="Code" required className="mono" value={t.code} onChange={(e) => setT({ ...t, code: e.target.value.toUpperCase() })} error={touched ? errors.code : null} hint="e.g. GST18, TDS194A" />
        <Select
          label="Tax type"
          required
          value={t.taxType}
          onChange={(e) => setT({ ...t, taxType: e.target.value as TaxRate['taxType'] })}
          options={[
            { value: 'GST', label: 'GST' },
            { value: 'TDS', label: 'TDS' },
          ]}
        />
        <Input label="Rate %" required numeric value={t.ratePercent} onChange={(e) => setT({ ...t, ratePercent: e.target.value.replace(/[^0-9.]/g, '') })} error={touched ? errors.ratePercent : null} />
        <Input label="Effective from" type="date" required value={t.effectiveFrom} onChange={(e) => setT({ ...t, effectiveFrom: e.target.value })} error={touched ? errors.effectiveFrom : null} />
        <Input label="Effective to" type="date" value={t.effectiveTo ?? ''} onChange={(e) => setT({ ...t, effectiveTo: e.target.value || null })} error={touched ? errors.effectiveTo : null} />
      </div>
      <div style={{ marginTop: 12 }}>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
