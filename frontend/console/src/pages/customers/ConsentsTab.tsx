import { useState } from 'react';
import { useConsents, useRecordConsent, useWithdrawConsent } from '../../api/extraHooks';
import { useEnumeration, useMe } from '../../api/hooks';
import type { Consent, ConsentInput, CustomerSummary } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, Select, Spinner, Table, Textarea, humanize, useToast } from '../../ui';

const CHANNELS: ConsentInput['channel'][] = ['BRANCH', 'WEB', 'MOBILE_APP', 'API', 'PAPER', 'CALL_CENTRE'];

/** Status as the customer would understand it; a withdrawn consent whose data must still be kept says so. */
export function ConsentStatus({ c }: { c: Consent }) {
  if (c.status === 'ACTIVE') return <Badge tone="ok">In force</Badge>;
  if (c.status === 'EXPIRED') return <Badge>Expired</Badge>;
  return (
    <span className="row" style={{ gap: 4 }}>
      <Badge tone="warn">Withdrawn</Badge>
      {c.retainedForLegalObligation && <Badge tone="info" title="The consent is gone; the data is kept and used only as far as law requires while a loan exists">Retained for legal obligation</Badge>}
    </span>
  );
}

export function ConsentsTab({ customer }: { customer: CustomerSummary }) {
  const me = useMe().data!;
  const canRecord = hasPermission(me.permissions, P.consentRecord);
  const q = useConsents(customer.id, true);
  const [recording, setRecording] = useState(false);
  const [withdrawing, setWithdrawing] = useState<Consent | null>(null);
  return (
    <div className="stack">
      <Card title="Consent and purpose records" flush actions={canRecord && <Button variant="primary" onClick={() => setRecording(true)}>Record consent</Button>}>
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={q.error} />
        </div>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Consent records"
            captionHidden
            columns={[
              { key: 'purpose', header: 'Purpose', render: (c) => humanize(c.purpose) },
              { key: 'basis', header: 'Lawful basis', render: (c) => (c.lawfulBasis === 'CONSENT' ? 'Consent (s.6)' : 'Legitimate use (s.7)') },
              { key: 'notice', header: 'Notice', render: (c) => <span className="mono">{c.noticeVersion}</span> },
              { key: 'channel', header: 'Channel', render: (c) => humanize(c.channel) },
              { key: 'granted', header: 'Granted', render: (c) => <DateTimeText value={c.grantedAt} /> },
              { key: 'expires', header: 'Expires', render: (c) => <DateTimeText value={c.expiresAt} /> },
              {
                key: 'status',
                header: 'Status',
                render: (c) => (
                  <span>
                    <ConsentStatus c={c} />
                    {c.status === 'WITHDRAWN' && (
                      <span className="muted" style={{ display: 'block', fontSize: 12 }}>
                        <DateTimeText value={c.withdrawnAt} /> by {c.withdrawnBy}: {c.withdrawalReason}
                      </span>
                    )}
                  </span>
                ),
              },
              ...(canRecord
                ? [{
                    key: 'act',
                    header: <span className="sr-only">Actions</span>,
                    render: (c: Consent) =>
                      c.status === 'ACTIVE' && c.lawfulBasis === 'CONSENT' ? (
                        <Button size="sm" aria-label={`Withdraw consent for ${humanize(c.purpose)}`} onClick={() => setWithdrawing(c)}>
                          Withdraw…
                        </Button>
                      ) : null,
                  }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(c) => c.id}
            empty={<EmptyState title="No consent records" />}
          />
        )}
      </Card>
      <p className="muted" style={{ margin: 0, fontSize: 12 }}>Digital Personal Data Protection Act 2023. Withdrawn and expired records stay in the list; a legitimate-use record cannot be withdrawn.</p>
      {recording && <RecordDialog customer={customer} onClose={() => setRecording(false)} />}
      {withdrawing && <WithdrawDialog customer={customer} consent={withdrawing} onClose={() => setWithdrawing(null)} />}
    </div>
  );
}

function RecordDialog({ customer, onClose }: { customer: CustomerSummary; onClose: () => void }) {
  const purposes = useEnumeration('consent-purpose');
  const record = useRecordConsent(customer.id);
  const toast = useToast();
  const [c, setC] = useState({ purpose: '', lawfulBasis: 'CONSENT' as ConsentInput['lawfulBasis'], noticeVersion: '', channel: 'BRANCH' as ConsentInput['channel'], evidenceRef: '', expires: '' });
  const [touched, setTouched] = useState(false);
  const today = new Date().toISOString().slice(0, 10);
  const errs = {
    purpose: !c.purpose ? 'Select a purpose' : null,
    noticeVersion: !c.noticeVersion.trim() ? 'Notice version is required' : null,
    evidenceRef: c.lawfulBasis === 'CONSENT' && !c.evidenceRef.trim() ? 'Evidence is required for a consent' : null,
    expires: c.expires && c.expires <= today ? 'Must be in the future' : null,
  };
  const valid = Object.values(errs).every((e) => !e);
  return (
    <Dialog
      open
      onClose={onClose}
      title="Record consent"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={record.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              record.mutate(
                {
                  purpose: c.purpose,
                  lawfulBasis: c.lawfulBasis,
                  noticeVersion: c.noticeVersion.trim(),
                  channel: c.channel,
                  ...(c.evidenceRef.trim() ? { evidenceRef: c.evidenceRef.trim() } : {}),
                  ...(c.expires ? { expiresAt: `${c.expires}T23:59:59.000Z` } : {}),
                },
                { onSuccess: (r) => (toast({ tone: 'success', message: `${humanize(r.purpose)} recorded.` }), onClose()) },
              );
            }}
          >
            Record
          </Button>
        </>
      }
    >
      <div className="stack">
        <div className="form-grid">
          <Select label="Purpose" required value={c.purpose} placeholder="Select…" onChange={(e) => setC({ ...c, purpose: e.target.value })} options={(purposes.data ?? []).filter((p) => p.active !== false).map((p) => ({ value: p.code, label: p.label }))} error={touched ? errs.purpose : null} />
          <Select
            label="Lawful basis"
            value={c.lawfulBasis}
            onChange={(e) => setC({ ...c, lawfulBasis: e.target.value as ConsentInput['lawfulBasis'] })}
            options={[
              { value: 'CONSENT', label: 'Consent (s.6)' },
              { value: 'LEGITIMATE_USE', label: 'Legitimate use (s.7)' },
            ]}
          />
          <Input label="Notice version" required className="mono" value={c.noticeVersion} onChange={(e) => setC({ ...c, noticeVersion: e.target.value })} hint="Privacy notice shown to the customer" error={touched ? errs.noticeVersion : null} />
          <Select label="Channel" value={c.channel} onChange={(e) => setC({ ...c, channel: e.target.value as ConsentInput['channel'] })} options={CHANNELS.map((x) => ({ value: x, label: humanize(x) }))} />
          <Input label="Evidence reference" required={c.lawfulBasis === 'CONSENT'} value={c.evidenceRef} onChange={(e) => setC({ ...c, evidenceRef: e.target.value })} hint="OTP or e-sign transaction, or form scan id" error={touched ? errs.evidenceRef : null} />
          <Input label="Expires on" type="date" value={c.expires} onChange={(e) => setC({ ...c, expires: e.target.value })} hint="Optional" error={touched ? errs.expires : null} />
        </div>
        <ErrorBanner error={record.error} />
      </div>
    </Dialog>
  );
}

function WithdrawDialog({ customer, consent, onClose }: { customer: CustomerSummary; consent: Consent; onClose: () => void }) {
  const withdraw = useWithdrawConsent(customer.id);
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Withdraw consent: ${humanize(consent.purpose)}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="danger"
            loading={withdraw.isPending}
            onClick={() => {
              setTouched(true);
              if (!reason.trim()) return;
              withdraw.mutate(
                { consentId: consent.id, reason: reason.trim() },
                {
                  onSuccess: (r) => {
                    toast({ tone: 'success', message: r.retainedForLegalObligation ? 'Withdrawal recorded. The data is retained for legal obligation while a loan exists.' : 'Withdrawal recorded.' });
                    onClose();
                  },
                },
              );
            }}
          >
            Record withdrawal
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Takes effect at once and the grant is kept. If the purpose is needed to service a loan of this customer, the data is retained and used only as far as law requires.
        </p>
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched && !reason.trim() ? 'A reason is required' : null} />
        <ErrorBanner error={withdraw.error} />
      </div>
    </Dialog>
  );
}
