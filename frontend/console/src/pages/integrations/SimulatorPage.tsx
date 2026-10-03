import { useState } from 'react';
import { useSimulateCallback } from '../../api/integrationHooks';
import type { SimulatedCallback } from '../../api/integrationTypes';
import { isMoney } from '../../lib/money';
import { Banner, Button, Card, ErrorBanner, Input, PageHeader, Select } from '../../ui';

const STATUSES: Record<SimulatedCallback['kind'], string[]> = { payout: ['SUCCESS', 'FAILED', 'RETURNED'], collection: ['PAID', 'FAILED'], mandate: ['ACTIVE', 'REJECTED', 'CANCELLED'] };
const REF_HINT: Record<SimulatedCallback['kind'], string> = { payout: 'Payout reference, e.g. PO-…-01', collection: 'Payment order reference, e.g. CO-…-01', mandate: 'Mandate reference, e.g. MD-…-01' };

/** Shown only with integration:simulate. Plays what a provider would call back, through the real verification path. */
export function SimulatorPage() {
  const simulate = useSimulateCallback();
  const [f, setF] = useState({ kind: 'payout' as SimulatedCallback['kind'], reference: '', status: 'SUCCESS', amount: '', reasonCode: '', reason: '', eventId: '' });
  const [touched, setTouched] = useState(false);
  const failing = ['FAILED', 'RETURNED', 'REJECTED'].includes(f.status);
  const errs = {
    reference: !f.reference.trim() ? 'Required' : null,
    amount: f.amount && (!isMoney(f.amount) || Number(f.amount) <= 0) ? 'Enter a positive amount' : null,
  };
  return (
    <div className="stack">
      <PageHeader title="Provider simulator" subtitle="Plays a provider callback against this tenant, exactly as the SIMULATOR provider would send it." />
      <div className="test-only" data-testid="test-only">
        <Banner tone="warn">
          <strong>Test only.</strong> Nothing here moves money. It changes payouts, payment orders and mandates of this tenant as if a provider had reported it, and works only while SIMULATOR is the active provider. Do not use it on production data.
        </Banner>
        <Card title="Simulated callback">
          <div className="stack">
            <div className="form-grid">
              <Select label="Kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value as SimulatedCallback['kind'], status: STATUSES[e.target.value as SimulatedCallback['kind']][0] })} options={[{ value: 'payout', label: 'Payout' }, { value: 'collection', label: 'Collection' }, { value: 'mandate', label: 'Mandate' }]} />
              <Input label="Reference" required className="mono" value={f.reference} onChange={(e) => setF({ ...f, reference: e.target.value })} hint={REF_HINT[f.kind]} error={touched ? errs.reference : null} />
              <Select label="Reported status" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })} options={STATUSES[f.kind].map((s) => ({ value: s, label: s }))} />
              {f.kind === 'collection' && <Input label="Amount" numeric value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} hint="Empty = the order amount" error={touched ? errs.amount : null} />}
              {failing && <Input label="Reason code" className="mono" value={f.reasonCode} onChange={(e) => setF({ ...f, reasonCode: e.target.value })} />}
              {failing && <Input label="Reason" value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} />}
              <Input label="Event id" className="mono" value={f.eventId} onChange={(e) => setF({ ...f, eventId: e.target.value })} hint="Repeat an id to test replay protection" />
            </div>
            <div>
              <Button
                variant="primary"
                loading={simulate.isPending}
                onClick={() => {
                  setTouched(true);
                  if (errs.reference || errs.amount) return;
                  simulate.mutate({
                    kind: f.kind, reference: f.reference.trim(), status: f.status,
                    ...(f.kind === 'collection' && f.amount ? { amount: f.amount } : {}),
                    ...(failing && f.reasonCode.trim() ? { reasonCode: f.reasonCode.trim() } : {}),
                    ...(failing && f.reason.trim() ? { reason: f.reason.trim() } : {}),
                    ...(f.eventId.trim() ? { eventId: f.eventId.trim() } : {}),
                  });
                }}
              >
                Send simulated callback
              </Button>
            </div>
            <ErrorBanner error={simulate.error} />
            {simulate.data && (
              <Banner tone={simulate.data.receipt === 'DUPLICATE' ? 'info' : 'ok'}>
                {simulate.data.receipt === 'DUPLICATE' ? 'Duplicate: this event id was already received, so nothing was processed again.' : 'Callback accepted and processed.'} Event id <span className="mono">{simulate.data.eventId}</span>
              </Banner>
            )}
          </div>
        </Card>
      </div>
    </div>
  );
}
