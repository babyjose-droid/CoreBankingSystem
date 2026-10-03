import { useState } from 'react';
import { useMe } from '../../api/hooks';
import { useMessageLog, useMessageTemplates, useMessageVariables, useProposeMessageTemplate } from '../../api/integrationHooks';
import type { MessageTemplate, MessageTemplateInput } from '../../api/integrationTypes';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, StatusBadge, Table, Tabs, Textarea, humanize } from '../../ui';
import { useProposalToast } from '../proposal';

const PLACEHOLDER = /\{\{\s*([^{}]*?)\s*\}\}/g;
/** Sample values for the live preview, in the formats the messages use. */
export const SAMPLE: Record<string, string> = {
  name: 'Anu', loan_no: '100100000012', amount: '9792.00', net_amount: '197640.00', date: '30-Jun-2026', due_date: '15-Jul-2026', reason: 'Balance insufficient',
  old_rate: '16.00', new_rate: '15.50', old_emi: '9792.00', new_emi: '9745.00', old_tenure: '19', new_tenure: '19',
};

export interface Preview {
  text: string;
  unknown: string[];
  /** `{{` without its `}}`, or the other way round. */
  malformed: boolean;
}
/** Fills the placeholders with sample values; a placeholder the event does not offer makes the preview fail. */
export function previewTemplate(body: string, allowed: string[]): Preview {
  const names = [...new Set([...body.matchAll(PLACEHOLDER)].map((m) => m[1]))];
  const unknown = names.filter((n) => !allowed.includes(n));
  const text = body.replace(PLACEHOLDER, (whole, n: string) => (allowed.includes(n) ? (SAMPLE[n] ?? `<${n}>`) : whole));
  const rest = body.replace(PLACEHOLDER, '');
  return { text, unknown, malformed: rest.includes('{{') || rest.includes('}}') };
}

export function MessagesPage() {
  const [tab, setTab] = useState('templates');
  return (
    <div className="stack">
      <PageHeader title="Messages" subtitle="SMS and e-mail sent to customers when something happens on their loan." />
      <Tabs label="Messages" active={tab} onChange={setTab} tabs={[{ id: 'templates', label: 'Templates', content: <Templates /> }, { id: 'log', label: 'Delivery log', content: <DeliveryLog /> }]} />
    </div>
  );
}

function Templates() {
  const me = useMe().data!;
  const canAdmin = hasPermission(me.permissions, P.messageAdmin);
  const q = useMessageTemplates();
  const [editing, setEditing] = useState<MessageTemplate | 'new' | null>(null);
  return (
    <div className="stack">
      {canAdmin && (
        <div>
          <Button variant="primary" onClick={() => setEditing('new')}>New template</Button>
        </div>
      )}
      <ErrorBanner error={q.error} />
      <Card flush>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Message templates"
            captionHidden
            columns={[
              { key: 'code', header: 'Event', render: (t) => (<span>{humanize(t.code ?? '')}<br /><span className="muted" style={{ fontSize: 12 }}>{t.channel === 'EMAIL' ? 'E-mail' : 'SMS'} · {t.language} · {humanize(t.category ?? '')}</span></span>) },
              { key: 'body', header: 'Text', render: (t) => (<span>{t.subject && (<><strong>{t.subject}</strong><br /></>)}<span style={{ whiteSpace: 'pre-wrap' }}>{t.body}</span></span>) },
              { key: 'dlt', header: 'DLT registration', render: (t) => (t.channel === 'SMS' ? (<span className="mono" style={{ fontSize: 12 }}>Entity {t.dltEntityId}<br />Template {t.dltTemplateId}<br />Header {t.dltHeader}</span>) : <span className="muted">Not needed for e-mail</span>) },
              { key: 'status', header: 'Status', render: (t) => (<span>{t.status === 'ACTIVE' ? <Badge tone="ok">Active</Badge> : <Badge>Retired</Badge>} v{t.version}</span>) },
              ...(canAdmin ? [{ key: 'act', header: <span className="sr-only">Actions</span>, render: (t: MessageTemplate) => <Button size="sm" aria-label={`Change ${humanize(t.code ?? '')} ${t.channel} template`} onClick={() => setEditing(t)}>Change</Button> }] : []),
            ]}
            rows={q.data ?? []}
            rowKey={(t) => `${t.code}-${t.channel}-${t.language}`}
            empty={<EmptyState title="No message templates" />}
          />
        )}
      </Card>
      {editing && <TemplateDialog initial={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function TemplateDialog({ initial, onClose }: { initial: MessageTemplate | null; onClose: () => void }) {
  const variables = useMessageVariables();
  const propose = useProposeMessageTemplate();
  const toast = useProposalToast();
  const [f, setF] = useState({
    code: (initial?.code ?? 'PAYMENT_RECEIVED') as MessageTemplateInput['code'], channel: (initial?.channel ?? 'SMS') as MessageTemplateInput['channel'], language: initial?.language ?? 'en',
    category: (initial?.category ?? 'TRANSACTIONAL') as NonNullable<MessageTemplateInput['category']>, subject: initial?.subject ?? '', body: initial?.body ?? '', dltEntityId: initial?.dltEntityId ?? '',
    dltTemplateId: initial?.dltTemplateId ?? '', dltHeader: initial?.dltHeader ?? '', status: (initial?.status ?? 'ACTIVE') as NonNullable<MessageTemplateInput['status']>,
  });
  const [touched, setTouched] = useState(false);
  const allowed = variables.data?.[f.code] ?? [];
  const sms = f.channel === 'SMS';
  const body = previewTemplate(f.body, allowed);
  const subject = previewTemplate(f.subject, allowed);
  const placeholderError = (p: { unknown: string[]; malformed: boolean }) => (p.unknown.length ? `Not available for this event: ${p.unknown.map((u) => `{{${u}}}`).join(', ')}` : p.malformed ? 'A placeholder is not closed: write {{name}}' : null);
  const errs = {
    body: !f.body.trim() ? 'Required' : placeholderError(body),
    subject: sms ? null : !f.subject.trim() ? 'Required for e-mail' : placeholderError(subject),
    language: !/^[a-z]{2}$/.test(f.language) ? 'Two letters, e.g. en' : null,
    dltEntityId: sms && !/^[0-9]+$/.test(f.dltEntityId) ? 'Digits, as registered on the DLT platform' : null,
    dltTemplateId: sms && !/^[0-9]+$/.test(f.dltTemplateId) ? 'Digits, as registered on the DLT platform' : null,
    dltHeader: sms && !f.dltHeader.trim() ? 'The registered sender id' : null,
  };
  const err = (k: keyof typeof errs) => (touched ? errs[k] : null);
  // Placeholder problems show while typing; they are what the preview is for.
  const live = (k: 'body' | 'subject') => (f[k].trim() ? placeholderError(k === 'body' ? body : subject) : null) ?? err(k);
  const previewFails = !!placeholderError(body) || (!sms && !!placeholderError(subject));
  return (
    <Dialog
      open
      onClose={onClose}
      wide
      title={initial ? `Change ${humanize(initial.code ?? '')} template` : 'New message template'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (Object.values(errs).some(Boolean)) return;
              propose.mutate(
                { code: f.code, channel: f.channel, language: f.language, category: f.category, body: f.body, status: f.status, ...(sms ? { dltEntityId: f.dltEntityId, dltTemplateId: f.dltTemplateId, dltHeader: f.dltHeader.trim() } : { subject: f.subject.trim() }) },
                { onSuccess: (a) => (toast(a, 'Message template'), onClose()) },
              );
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <div className="form-grid">
          <Select label="Event" disabled={!!initial} value={f.code} onChange={(e) => setF({ ...f, code: e.target.value as typeof f.code })} options={Object.keys(variables.data ?? { [f.code]: [] }).map((c) => ({ value: c, label: humanize(c) }))} />
          <Select label="Channel" disabled={!!initial} value={f.channel} onChange={(e) => setF({ ...f, channel: e.target.value as typeof f.channel })} options={[{ value: 'SMS', label: 'SMS' }, { value: 'EMAIL', label: 'E-mail' }]} />
          <Input label="Language" required disabled={!!initial} className="mono" value={f.language} onChange={(e) => setF({ ...f, language: e.target.value.trim().toLowerCase() })} error={err('language')} />
          <Select label="Category" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value as typeof f.category })} options={['TRANSACTIONAL', 'SERVICE', 'PROMOTIONAL'].map((c) => ({ value: c, label: humanize(c) }))} />
          <Select label="Status" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value as typeof f.status })} options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'RETIRED', label: 'Retired (not sent)' }]} />
        </div>
        <p className="muted" style={{ margin: 0 }} data-testid="placeholders">
          Placeholders for this event: {allowed.length ? allowed.map((a) => <span key={a} className="mono">{`{{${a}}} `}</span>) : 'loading…'}
        </p>
        {!sms && <Input label="Subject" required maxLength={200} value={f.subject} onChange={(e) => setF({ ...f, subject: e.target.value })} error={live('subject')} />}
        <Textarea label="Text" required rows={sms ? 3 : 6} value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} error={live('body')} />
        {sms && (
          <div className="form-grid">
            <Input label="DLT entity id" required className="mono" value={f.dltEntityId} onChange={(e) => setF({ ...f, dltEntityId: e.target.value.trim() })} hint="Principal entity id" error={err('dltEntityId')} />
            <Input label="DLT template id" required className="mono" value={f.dltTemplateId} onChange={(e) => setF({ ...f, dltTemplateId: e.target.value.trim() })} hint="Registered content template" error={err('dltTemplateId')} />
            <Input label="DLT header" required className="mono" value={f.dltHeader} onChange={(e) => setF({ ...f, dltHeader: e.target.value })} hint="Registered sender id" error={err('dltHeader')} />
          </div>
        )}
        <div>
          <h3 style={{ margin: '0 0 4px', fontSize: 14 }}>Preview with sample values</h3>
          {previewFails ? (
            <Banner tone="danger">The preview cannot be made: fix the placeholders first.</Banner>
          ) : (
            <div className="preview-box" data-testid="template-preview" aria-live="polite">
              {!sms && subject.text && <strong>{subject.text}{'\n'}</strong>}
              {body.text || <span className="muted">Type the text to see it here.</span>}
            </div>
          )}
          {sms && !previewFails && f.body && (
            <p className="muted" style={{ margin: '6px 0 0', fontSize: 12 }}>
              {body.text.length} characters with these sample values. As registered on DLT: <span className="mono">{f.body.replace(PLACEHOLDER, '{#var#}')}</span>
            </p>
          )}
        </div>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}

function DeliveryLog() {
  const [status, setStatus] = useState('');
  const q = useMessageLog(status);
  return (
    <Card flush>
      <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
        <Select label="Status" value={status} onChange={(e) => setStatus(e.target.value)} options={[{ value: '', label: 'All' }, ...['QUEUED', 'SENT', 'FAILED', 'SUPPRESSED'].map((s) => ({ value: s, label: humanize(s) }))]} />
        <span className="muted" style={{ paddingBottom: 6 }}>Recipients are masked and the text of a message is never shown here.</span>
      </div>
      <ErrorBanner error={q.error} />
      {q.isLoading ? (
        <div style={{ padding: 16 }}>
          <Spinner />
        </div>
      ) : (
        <Table
          caption="Message delivery log"
          captionHidden
          columns={[
            { key: 'at', header: 'Created', render: (m) => <DateTimeText value={m.createdAt} /> },
            { key: 'tpl', header: 'Message', render: (m) => (<span>{humanize(m.templateCode ?? '')}<br /><span className="muted" style={{ fontSize: 12 }}>{m.channel === 'EMAIL' ? 'E-mail' : 'SMS'} · {humanize(m.category ?? '')}</span></span>) },
            { key: 'to', header: 'Recipient', render: (m) => <span className="mono masked" title="Masked for privacy">{m.recipientMasked}</span> },
            { key: 'status', header: 'Status', render: (m) => (<span><StatusBadge status={m.status} />{(m.suppressReason || m.lastError) && (<><br /><span className="muted" style={{ fontSize: 12 }}>{m.suppressReason ? humanize(m.suppressReason) : m.lastError}</span></>)}</span>) },
            { key: 'prov', header: 'Provider', render: (m) => (m.provider ? (<span>{m.provider} <span className="mono muted" style={{ fontSize: 12 }}>{m.providerRef}</span></span>) : <span className="muted">—</span>) },
            { key: 'n', header: 'Attempts', numeric: true, render: (m) => m.attempts ?? 0 },
            { key: 'sent', header: 'Sent', render: (m) => (m.sentAt ? <DateTimeText value={m.sentAt} /> : <span className="muted">—</span>) },
          ]}
          rows={q.data ?? []}
          rowKey={(m) => m.id ?? ''}
          empty={<EmptyState title="No messages match" />}
        />
      )}
    </Card>
  );
}
