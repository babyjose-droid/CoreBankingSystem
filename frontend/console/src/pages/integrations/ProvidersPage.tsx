import { useState } from 'react';
import { useMe } from '../../api/hooks';
import { useProposeProviderConfig, useProposeProviderDeactivation, useProviderCatalogue, useProviderConfigs } from '../../api/integrationHooks';
import type { ProviderConfig, ProviderKind } from '../../api/integrationTypes';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, Checkbox, DateTimeText, Dialog, ErrorBanner, Input, PageHeader, Select, Spinner, Table, humanize } from '../../ui';
import { useProposalToast } from '../proposal';

const KINDS: ProviderKind[] = ['PAYOUT', 'COLLECTION', 'MANDATE', 'SMS', 'EMAIL'];
const KIND_LABEL: Record<ProviderKind, string> = { PAYOUT: 'Payouts', COLLECTION: 'Collections', MANDATE: 'Mandates', SMS: 'SMS', EMAIL: 'E-mail' };

interface Row {
  kind: ProviderKind;
  config: ProviderConfig | null;
}

export function ProvidersPage() {
  const me = useMe().data!;
  const canAdmin = hasPermission(me.permissions, P.integrationAdmin);
  const [history, setHistory] = useState(false);
  const configs = useProviderConfigs(history);
  const deactivate = useProposeProviderDeactivation();
  const toast = useProposalToast();
  const [editing, setEditing] = useState<Row | null>(null);
  const all = configs.data ?? [];
  const rows: Row[] = history ? all.map((c) => ({ kind: c.kind!, config: c })) : KINDS.map((kind) => ({ kind, config: all.find((c) => c.kind === kind && c.status === 'ACTIVE') ?? null }));
  return (
    <div className="stack">
      <PageHeader title="Integration providers" subtitle="Which provider serves each kind of integration. A change needs approval. Secrets are never shown again: only that one is set and its last four characters." />
      <ErrorBanner error={configs.error ?? deactivate.error} />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Checkbox label="Show earlier versions" checked={history} onChange={(e) => setHistory(e.target.checked)} />
        </div>
        {configs.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Integration providers"
            captionHidden
            columns={[
              { key: 'kind', header: 'Integration', render: (r) => KIND_LABEL[r.kind] ?? r.kind },
              {
                key: 'provider',
                header: 'Provider',
                render: (r) =>
                  r.config ? (
                    <span>
                      <span className="mono">{r.config.provider}</span> {r.config.provider === 'SIMULATOR' && <Badge tone="warn">Test only</Badge>} {r.config.verified === false && <Badge tone="warn">Not verified</Badge>}
                    </span>
                  ) : (
                    <span className="muted">Not configured</span>
                  ),
              },
              { key: 'settings', header: 'Settings', render: (r) => Object.entries(r.config?.settings ?? {}).map(([k, v]) => <div key={k}><span className="muted">{k}:</span> {v}</div>) },
              {
                key: 'secrets',
                header: 'Secrets',
                render: (r) =>
                  Object.entries(r.config?.secrets ?? {}).map(([k, v]) => (
                    <div key={k} data-testid={`secret-${r.kind}-${k}`}>
                      <span className="muted">{k}:</span> set <span className="mono masked" aria-label={`ending ${v.last4}`}>••••{v.last4}</span>
                    </div>
                  )),
              },
              { key: 'status', header: 'Status', render: (r) => (r.config ? <span>{r.config.status === 'ACTIVE' ? <Badge tone="ok">Active</Badge> : <Badge>{humanize(r.config.status ?? '')}</Badge>} v{r.config.version}</span> : null) },
              { key: 'upd', header: 'Changed', render: (r) => (r.config ? (<span><DateTimeText value={r.config.updatedAt} /> <span className="muted mono">{r.config.updatedBy}</span></span>) : null) },
              ...(canAdmin && !history
                ? [{
                    key: 'act',
                    header: <span className="sr-only">Actions</span>,
                    render: (r: Row) => (
                      <span className="row" style={{ gap: 6 }}>
                        <Button size="sm" aria-label={`${r.config ? 'Change' : 'Configure'} ${KIND_LABEL[r.kind]} provider`} onClick={() => setEditing(r)}>{r.config ? 'Change' : 'Configure'}</Button>
                        {r.config && <Button size="sm" variant="danger" aria-label={`Deactivate ${KIND_LABEL[r.kind]} provider`} onClick={() => deactivate.mutate(r.kind, { onSuccess: (a) => toast(a, 'Provider deactivation') })}>Deactivate</Button>}
                      </span>
                    ),
                  }]
                : []),
            ]}
            rows={rows}
            rowKey={(r) => r.config?.id ?? r.kind}
          />
        )}
      </Card>
      {editing && <ProviderDialog row={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ProviderDialog({ row, onClose }: { row: Row; onClose: () => void }) {
  const catalogue = useProviderCatalogue();
  const propose = useProposeProviderConfig();
  const toast = useProposalToast();
  const specs = (catalogue.data ?? []).filter((s) => s.kind === row.kind);
  const [provider, setProvider] = useState(row.config?.provider ?? '');
  const [settings, setSettings] = useState<Record<string, string>>(row.config?.settings ?? {});
  // Typed secrets live here only until Submit: they are sent once and wiped whatever the outcome.
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState(false);
  const spec = specs.find((s) => s.provider === provider);
  const same = !!row.config && row.config.provider === provider;
  const has = (name: string) => same && !!row.config?.secrets?.[name]?.set;
  const secretError = (name: string) => {
    const v = secrets[name] ?? '';
    if (!v) return spec?.requiredSecrets?.includes(name) && !has(name) ? 'Required' : null;
    return v.length < 8 ? 'At least 8 characters' : null;
  };
  const valid = !!spec && (spec.secrets ?? []).every((n) => !secretError(n));
  const submit = () => {
    setTouched(true);
    if (!valid || !spec) return;
    const given = Object.fromEntries(Object.entries(secrets).filter(([k, v]) => v && spec.secrets?.includes(k)));
    const set = Object.fromEntries(Object.entries(settings).filter(([k, v]) => v.trim() && spec.settings?.includes(k)).map(([k, v]) => [k, v.trim()]));
    setSecrets({});
    setTouched(false);
    propose.mutate({ kind: row.kind, provider: spec.provider!, settings: set, ...(Object.keys(given).length ? { secrets: given } : {}) }, { onSuccess: (a) => (toast(a, 'Provider configuration'), onClose()) });
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={`${KIND_LABEL[row.kind]} provider`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={propose.isPending} onClick={submit}>
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <Select
          label="Provider"
          required
          value={provider}
          placeholder="Select…"
          onChange={(e) => {
            setProvider(e.target.value);
            setSecrets({});
          }}
          options={specs.map((s) => ({ value: s.provider ?? '', label: `${s.provider}${s.enabledInDeployment ? '' : ' (not enabled in this deployment)'}`, disabled: !s.enabledInDeployment }))}
          error={touched && !spec ? 'Required' : null}
        />
        {spec?.provider === 'SIMULATOR' && <Banner tone="warn">Test only: the simulator reports success without moving money or sending anything.</Banner>}
        {spec && spec.verified === false && <Banner tone="warn">Not verified against the provider. {spec.note}</Banner>}
        {spec && (spec.settings?.length ?? 0) > 0 && (
          <div className="form-grid">
            {spec.settings!.map((name) => (
              <Input key={name} label={name} value={settings[name] ?? ''} onChange={(e) => setSettings({ ...settings, [name]: e.target.value })} />
            ))}
          </div>
        )}
        {spec && (spec.secrets?.length ?? 0) > 0 && (
          <fieldset className="fieldset">
            <legend>Secrets</legend>
            <p className="muted" style={{ marginTop: 0 }}>Sent once with this request and never shown again, not even to the checker. Leave a secret empty to keep its current value.</p>
            <div className="form-grid">
              {spec.secrets!.map((name) => (
                <Input
                  key={name}
                  type="password"
                  autoComplete="new-password"
                  label={name}
                  required={spec.requiredSecrets?.includes(name) && !has(name)}
                  value={secrets[name] ?? ''}
                  onChange={(e) => setSecrets({ ...secrets, [name]: e.target.value })}
                  hint={has(name) ? `Set, ending ${row.config?.secrets?.[name]?.last4}. Empty keeps it.` : 'Not set'}
                  error={touched ? secretError(name) : null}
                />
              ))}
            </div>
          </fieldset>
        )}
        {propose.isError && <p className="muted" style={{ margin: 0 }}>The secrets were cleared from this form; type them again to retry.</p>}
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
