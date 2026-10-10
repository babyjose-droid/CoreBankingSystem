import { useState } from 'react';
import { useMe } from '../../api/hooks';
import {
  useCollectWebhookSecret, useProposeWebhookAction, useProposeWebhookEndpoint, useReplayDead, useReplayDelivery, useWebhookDeliveries, useWebhookDelivery, useWebhookEndpoints, useWebhookEventTypes,
} from '../../api/integrationHooks';
import type { WebhookEndpoint, WebhookEventType } from '../../api/integrationTypes';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, Checkbox, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, StatusBadge, Table, Tabs, humanize, useToast } from '../../ui';
import { useProposalToast } from '../proposal';
import { config } from '../../config';
import { OneTimeSecretDialog } from './common';

const DELIVERY_STATUSES = ['PENDING', 'RETRY', 'DELIVERED', 'DEAD'];

/** https, a public DNS name (no IP address or localhost), port 443 or 8443. The server checks again, including DNS. */
export function webhookUrlError(url: string, localHosts: readonly string[] = config.webhookLocalHosts): string | null {
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return 'Enter a full URL, e.g. https://example.com/hooks';
  }
  if (localHosts.includes(u.hostname.toLowerCase()) && (u.protocol === 'http:' || u.protocol === 'https:')) return u.username || u.password ? 'Must not contain a user name or password' : null;
  if (u.protocol !== 'https:') return 'Must be https';
  if (u.hostname === 'localhost' || /^[0-9.]+$/.test(u.hostname) || u.hostname.includes(':') || !u.hostname.includes('.')) return 'Must be a public DNS name, not an IP address or localhost';
  if (u.port && !['443', '8443'].includes(u.port)) return 'Port must be 443 or 8443';
  if (u.username || u.password) return 'Must not contain a user name or password';
  return null;
}

export function WebhooksPage() {
  const [tab, setTab] = useState('endpoints');
  return (
    <div className="stack">
      <PageHeader title="Webhooks" subtitle="Events sent to your systems over https, each signed with the endpoint's secret." />
      <Tabs label="Webhooks" active={tab} onChange={setTab} tabs={[{ id: 'endpoints', label: 'Endpoints', content: <Endpoints /> }, { id: 'deliveries', label: 'Deliveries', content: <Deliveries /> }]} />
    </div>
  );
}

function Endpoints() {
  const me = useMe().data!;
  const canAdmin = hasPermission(me.permissions, P.webhookAdmin);
  const q = useWebhookEndpoints();
  const types = useWebhookEventTypes();
  const action = useProposeWebhookAction();
  const collect = useCollectWebhookSecret();
  const proposalToast = useProposalToast();
  const [editing, setEditing] = useState<WebhookEndpoint | 'new' | null>(null);
  const act = (e: WebhookEndpoint, a: string, what: string) => action.mutate({ id: e.id!, action: a }, { onSuccess: (ap) => proposalToast(ap, what) });
  return (
    <div className="stack">
      {canAdmin && (
        <div>
          <Button variant="primary" onClick={() => setEditing('new')}>New endpoint</Button>
        </div>
      )}
      <ErrorBanner error={q.error ?? action.error ?? collect.error} />
      <Card flush>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Webhook endpoints"
            captionHidden
            columns={[
              { key: 'name', header: 'Endpoint', render: (e) => (<span><strong>{e.name}</strong><br /><span className="mono" style={{ fontSize: 12, wordBreak: 'break-all' }}>{e.url}</span></span>) },
              { key: 'types', header: 'Events', render: (e) => (e.eventTypes ?? []).map((t) => <div key={t} className="mono" style={{ fontSize: 12 }}>{t}</div>) },
              { key: 'status', header: 'Status', render: (e) => (e.status === 'ACTIVE' ? <Badge tone="ok">Active</Badge> : <Badge>{humanize(e.status ?? '')}</Badge>) },
              { key: 'keys', header: 'Signing keys', render: (e) => (<span>{(e.keysInForce ?? []).join(', ') || <span className="muted">None yet</span>}{e.secretPending && (<><br /><Badge tone="warn">Secret waiting to be collected</Badge></>)}</span>) },
              ...(canAdmin
                ? [{
                    key: 'act',
                    header: <span className="sr-only">Actions</span>,
                    render: (e: WebhookEndpoint) => (
                      <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                        {e.secretPending && <Button size="sm" variant="primary" aria-label={`Collect signing secret of ${e.name}`} loading={collect.isPending && collect.variables === e.id} onClick={() => collect.mutate(e.id!)}>Collect secret</Button>}
                        <Button size="sm" aria-label={`Change ${e.name}`} onClick={() => setEditing(e)}>Change</Button>
                        <Button size="sm" aria-label={`Rotate secret of ${e.name}`} disabled={e.secretPending} onClick={() => act(e, 'ROTATE_SECRET', 'Secret rotation')}>Rotate secret</Button>
                        {e.status === 'ACTIVE' ? (
                          <Button size="sm" variant="danger" aria-label={`Disable ${e.name}`} onClick={() => act(e, 'DISABLE', 'Endpoint disabling')}>Disable</Button>
                        ) : (
                          <Button size="sm" aria-label={`Enable ${e.name}`} onClick={() => act(e, 'ENABLE', 'Endpoint enabling')}>Enable</Button>
                        )}
                      </span>
                    ),
                  }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(e) => e.id ?? ''}
            empty={<EmptyState title="No webhook endpoints" />}
          />
        )}
      </Card>
      {types.data && (
        <Card title="For the receiving system">
          <dl className="kv">
            <dt>Signature header</dt>
            <dd className="mono">{types.data.signatureHeader}</dd>
            <dt>Event id header</dt>
            <dd className="mono">{types.data.eventIdHeader}</dd>
            <dt>Accept timestamps within</dt>
            <dd>{types.data.recommendedToleranceSeconds} seconds</dd>
            <dt>Retries after</dt>
            <dd>{(types.data.retryWaitsSeconds ?? []).map((s) => (s >= 3600 ? `${s / 3600} h` : `${s / 60} min`)).join(', ')}, then the delivery is dead until replayed</dd>
          </dl>
        </Card>
      )}
      {editing && <EndpointDialog initial={editing === 'new' ? null : editing} types={Object.keys(types.data?.eventTypes ?? {})} onClose={() => setEditing(null)} />}
      {collect.data && (
        <OneTimeSecretDialog
          title="Signing secret"
          warning={<>This is the only time the signing secret is shown. CoreBanking keeps it encrypted and cannot show it again; if it is lost, propose a rotation.{collect.data.previousSecretValidHours ? ` The previous secret stays valid for ${collect.data.previousSecretValidHours} hours so the receiver can switch over.` : ''}</>}
          rows={[{ label: 'Key id', value: collect.data.keyId ?? '' }, { label: 'Signing secret', value: collect.data.secret ?? '', copy: true }]}
          onClose={() => collect.reset()}
        />
      )}
    </div>
  );
}

function EndpointDialog({ initial, types, onClose }: { initial: WebhookEndpoint | null; types: string[]; onClose: () => void }) {
  const propose = useProposeWebhookEndpoint();
  const toast = useProposalToast();
  const [f, setF] = useState({ name: initial?.name ?? '', url: initial?.url ?? '', eventTypes: initial?.eventTypes ?? [] });
  const [touched, setTouched] = useState(false);
  const errs = {
    name: !f.name.trim() ? 'Required' : null,
    url: webhookUrlError(f.url.trim()),
    eventTypes: f.eventTypes.length === 0 ? 'Choose at least one event' : null,
  };
  const err = (k: keyof typeof errs) => (touched ? errs[k] : null);
  return (
    <Dialog
      open
      onClose={onClose}
      title={initial ? `Change ${initial.name}` : 'New webhook endpoint'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (Object.values(errs).some(Boolean)) return;
              propose.mutate({ id: initial?.id, input: { name: f.name.trim(), url: f.url.trim(), eventTypes: f.eventTypes as WebhookEventType[] } }, { onSuccess: (a) => (toast(a, initial ? 'Endpoint change' : 'New endpoint'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        {!initial && <Banner tone="info">After a checker approves, you (the proposer) collect the signing secret here, once. Nobody else can collect it.</Banner>}
        <Input label="Name" required maxLength={80} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} error={err('name')} />
        <Input label="URL" required type="url" className="mono" value={f.url} onChange={(e) => setF({ ...f, url: e.target.value })} hint={config.webhookLocalHosts.length ? `https, a public DNS name, port 443 or 8443 (local stack: http://${config.webhookLocalHosts.join(", ")} also works)` : "https, a public DNS name, port 443 or 8443"} error={err('url')} />
        <fieldset className="fieldset" aria-invalid={!!err('eventTypes') || undefined}>
          <legend>Events to send</legend>
          {types.map((t) => (
            <Checkbox key={t} label={<span className="mono">{t}</span>} checked={f.eventTypes.includes(t)} onChange={(e) => setF({ ...f, eventTypes: e.target.checked ? [...f.eventTypes, t] : f.eventTypes.filter((x) => x !== t) })} />
          ))}
          {err('eventTypes') && <p className="field__error" role="alert">{err('eventTypes')}</p>}
        </fieldset>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}

function Deliveries() {
  const me = useMe().data!;
  const canAdmin = hasPermission(me.permissions, P.webhookAdmin);
  const endpoints = useWebhookEndpoints();
  const [endpointId, setEndpointId] = useState('');
  const [status, setStatus] = useState('');
  const q = useWebhookDeliveries({ endpointId, status });
  const replay = useReplayDelivery();
  const replayDead = useReplayDead();
  const toast = useToast();
  const [open, setOpen] = useState<string | null>(null);
  const nameOf = (id?: string) => endpoints.data?.find((e) => e.id === id)?.name ?? id ?? '';
  return (
    <div className="stack">
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select label="Endpoint" value={endpointId} onChange={(e) => setEndpointId(e.target.value)} options={[{ value: '', label: 'All endpoints' }, ...(endpoints.data ?? []).map((e) => ({ value: e.id ?? '', label: e.name ?? '' }))]} />
          <Select label="Status" value={status} onChange={(e) => setStatus(e.target.value)} options={[{ value: '', label: 'All' }, ...DELIVERY_STATUSES.map((s) => ({ value: s, label: s === 'DEAD' ? 'Dead (gave up)' : humanize(s) }))]} />
          {canAdmin && endpointId && (
            <Button loading={replayDead.isPending} onClick={() => replayDead.mutate(endpointId, { onSuccess: (r) => toast({ tone: r.replayed ? 'success' : 'info', message: r.replayed ? `${r.replayed} dead delivery(ies) sent again.` : 'No dead deliveries to replay.' }) })}>
              Replay all dead
            </Button>
          )}
        </div>
        <ErrorBanner error={q.error ?? replay.error ?? replayDead.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Webhook deliveries"
            captionHidden
            columns={[
              { key: 'at', header: 'Created', render: (d) => <DateTimeText value={d.createdAt} /> },
              { key: 'type', header: 'Event', render: (d) => (<span><span className="mono">{d.eventType}</span>{d.replayOf && (<> <Badge>Replay</Badge></>)}</span>) },
              { key: 'ep', header: 'Endpoint', render: (d) => nameOf(d.endpointId) },
              { key: 'status', header: 'Status', render: (d) => (<span><StatusBadge status={d.status} />{d.lastError && (<><br /><span className="muted" style={{ fontSize: 12 }}>{d.lastError}</span></>)}</span>) },
              { key: 'n', header: 'Attempts', numeric: true, render: (d) => d.attempts ?? 0 },
              { key: 'next', header: 'Next attempt', render: (d) => (d.nextAttemptAt ? <DateTimeText value={d.nextAttemptAt} /> : <span className="muted">—</span>) },
              {
                key: 'act',
                header: <span className="sr-only">Actions</span>,
                render: (d) => (
                  <span className="row" style={{ gap: 6 }}>
                    <Button size="sm" aria-label={`Attempts of ${d.eventType} delivery ${d.id}`} onClick={() => setOpen(d.id!)}>Attempts</Button>
                    {canAdmin && <Button size="sm" aria-label={`Replay ${d.eventType} delivery ${d.id}`} loading={replay.isPending && replay.variables === d.id} onClick={() => replay.mutate(d.id!, { onSuccess: (r) => toast({ tone: r.status === 'DELIVERED' ? 'success' : 'info', message: `Sent again as a new delivery: ${humanize(r.status ?? '').toLowerCase()}.` }) })}>Replay</Button>}
                  </span>
                ),
              },
            ]}
            rows={q.data ?? []}
            rowKey={(d) => d.id ?? ''}
            empty={<EmptyState title="No deliveries match" />}
          />
        )}
      </Card>
      {open && <DeliveryDialog id={open} onClose={() => setOpen(null)} />}
    </div>
  );
}

function DeliveryDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const q = useWebhookDelivery(id);
  const d = q.data;
  return (
    <Dialog open onClose={onClose} title={d ? `Delivery of ${d.eventType}` : 'Delivery'} footer={<Button onClick={onClose}>Close</Button>}>
      <div className="stack">
        <ErrorBanner error={q.error} />
        {q.isLoading && <Spinner />}
        {d && (
          <>
            <dl className="kv">
              <dt>Event id</dt>
              <dd className="mono">{d.eventId}</dd>
              <dt>Status</dt>
              <dd><StatusBadge status={d.status} /></dd>
              {d.replayOf && (<><dt>Replay of</dt><dd className="mono">{d.replayOf} <span className="muted">by {d.requestedBy}</span></dd></>)}
            </dl>
            <Table
              caption="Attempts"
              columns={[
                { key: 'n', header: '#', numeric: true, render: (a) => a.attemptNo },
                { key: 'at', header: 'At', render: (a) => <DateTimeText value={a.at} /> },
                { key: 'code', header: 'HTTP status', render: (a) => a.statusCode ?? <span className="muted">No answer</span> },
                { key: 'ms', header: 'Took', numeric: true, render: (a) => (a.durationMs != null ? `${a.durationMs} ms` : '') },
                { key: 'err', header: 'Error', render: (a) => a.error ?? '' },
              ]}
              rows={d.attemptLog ?? []}
              rowKey={(a) => a.attemptNo ?? 0}
              empty={<EmptyState title="Not attempted yet" />}
            />
          </>
        )}
      </div>
    </Dialog>
  );
}
