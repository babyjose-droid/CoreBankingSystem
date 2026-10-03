import { useState } from 'react';
import { useBranches, useMe } from '../../api/hooks';
import { useApiClientScopes, useApiClients, useCollectApiClientSecret, useProposeApiClient, useProposeApiClientAction, useProposeApiClientScopes } from '../../api/integrationHooks';
import type { ApiClientRecord } from '../../api/integrationTypes';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, Checkbox, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, Table, humanize } from '../../ui';
import { useProposalToast } from '../proposal';
import { OneTimeSecretDialog } from './common';

export function ApiClientsPage() {
  const me = useMe().data!;
  const canAdmin = hasPermission(me.permissions, P.apiclientAdmin);
  const q = useApiClients();
  const scopes = useApiClientScopes();
  const action = useProposeApiClientAction();
  const collect = useCollectApiClientSecret();
  const toast = useProposalToast();
  const [creating, setCreating] = useState(false);
  const [scoping, setScoping] = useState<ApiClientRecord | null>(null);
  const sensitive = scopes.data?.sensitive ?? [];
  const act = (c: ApiClientRecord, a: string, what: string) => action.mutate({ clientId: c.clientId!, action: a }, { onSuccess: (ap) => toast(ap, what) });
  return (
    <div className="stack">
      <PageHeader
        title="API clients"
        subtitle="Systems that call the API with their own credentials (OAuth2 client credentials), such as a loan origination system. Every change needs approval."
        actions={canAdmin && <Button variant="primary" onClick={() => setCreating(true)}>New API client</Button>}
      />
      <Banner tone="info">A client that can move money ({sensitive.join(', ') || 'loan:stp, loan:repay, payout:beneficiary'}) needs two different checkers.</Banner>
      <ErrorBanner error={q.error ?? action.error ?? collect.error} />
      <Card flush>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="API clients"
            captionHidden
            columns={[
              { key: 'id', header: 'Client', render: (c) => (<span><strong>{c.name}</strong><br /><span className="mono" style={{ fontSize: 12 }}>{c.clientId}</span></span>) },
              { key: 'scopes', header: 'Scopes', render: (c) => (<span className="row" style={{ gap: 4, flexWrap: 'wrap' }}>{(c.scopes ?? []).map((s) => <Badge key={s} tone={sensitive.includes(s) ? 'warn' : 'neutral'} title={sensitive.includes(s) ? 'Moves money' : undefined}>{s}</Badge>)}</span>) },
              { key: 'branch', header: 'Branches', render: (c) => (c.allBranches ? 'All branches' : <span className="mono">{c.homeBranch}</span>) },
              { key: 'status', header: 'Status', render: (c) => (c.status === 'ACTIVE' ? <Badge tone="ok">Active</Badge> : <Badge>{humanize(c.status ?? '')}</Badge>) },
              { key: 'secret', header: 'Secret', render: (c) => (c.secretPending ? <Badge tone="warn">Waiting to be collected</Badge> : c.secretIssuedAt ? (<span>Issued <DateTimeText value={c.secretIssuedAt} /></span>) : <span className="muted">None</span>) },
              ...(canAdmin
                ? [{
                    key: 'act',
                    header: <span className="sr-only">Actions</span>,
                    render: (c: ApiClientRecord) => (
                      <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                        {c.secretPending && <Button size="sm" variant="primary" aria-label={`Collect secret of ${c.clientId}`} loading={collect.isPending && collect.variables === c.clientId} onClick={() => collect.mutate(c.clientId!)}>Collect secret</Button>}
                        <Button size="sm" aria-label={`Change scopes of ${c.clientId}`} onClick={() => setScoping(c)}>Scopes</Button>
                        <Button size="sm" aria-label={`Rotate secret of ${c.clientId}`} disabled={c.secretPending || c.status !== 'ACTIVE'} onClick={() => act(c, 'ROTATE_SECRET', 'Secret rotation')}>Rotate secret</Button>
                        {c.status === 'ACTIVE' ? (
                          <Button size="sm" variant="danger" aria-label={`Disable ${c.clientId}`} onClick={() => act(c, 'DISABLE', 'Client disabling')}>Disable</Button>
                        ) : (
                          <Button size="sm" aria-label={`Enable ${c.clientId}`} onClick={() => act(c, 'ENABLE', 'Client enabling')}>Enable</Button>
                        )}
                      </span>
                    ),
                  }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(c) => c.clientId ?? ''}
            empty={<EmptyState title="No API clients" />}
          />
        )}
      </Card>
      {creating && <ClientDialog grantable={scopes.data?.grantable ?? []} sensitive={sensitive} onClose={() => setCreating(false)} />}
      {scoping && <ClientDialog client={scoping} grantable={scopes.data?.grantable ?? []} sensitive={sensitive} onClose={() => setScoping(null)} />}
      {collect.data && (
        <OneTimeSecretDialog
          title="Client secret"
          warning="This is the only time the client secret is shown. It is created at the identity provider now and CoreBanking does not keep it; if it is lost, propose a rotation."
          rows={[{ label: 'Client id', value: collect.data.clientId ?? '', copy: true }, { label: 'Client secret', value: collect.data.clientSecret ?? '', copy: true }, { label: 'Grant type', value: collect.data.grantType ?? '' }]}
          onClose={() => collect.reset()}
        />
      )}
    </div>
  );
}

function ClientDialog({ client, grantable, sensitive, onClose }: { client?: ApiClientRecord; grantable: string[]; sensitive: string[]; onClose: () => void }) {
  const me = useMe().data!;
  const branches = useBranches();
  const create = useProposeApiClient();
  const change = useProposeApiClientScopes();
  const toast = useProposalToast();
  const [f, setF] = useState({ clientId: '', name: '', homeBranch: me.homeBranch ?? '', allBranches: false });
  const [scopes, setScopes] = useState<string[]>(client?.scopes ?? []);
  const [touched, setTouched] = useState(false);
  const errs = {
    clientId: client || /^[a-z0-9][a-z0-9-]{1,49}$/.test(f.clientId) ? null : 'Lower-case letters, digits and hyphens',
    name: client || f.name.trim() ? null : 'Required',
    homeBranch: client || f.homeBranch ? null : 'Required',
    scopes: scopes.length === 0 ? 'Choose at least one scope' : null,
  };
  const err = (k: keyof typeof errs) => (touched ? errs[k] : null);
  const added = scopes.filter((s) => sensitive.includes(s) && !(client?.scopes ?? []).includes(s));
  const pending = create.isPending || change.isPending;
  return (
    <Dialog
      open
      onClose={onClose}
      wide
      title={client ? `Scopes of ${client.clientId}` : 'New API client'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={pending}
            onClick={() => {
              setTouched(true);
              if (Object.values(errs).some(Boolean)) return;
              if (client) change.mutate({ clientId: client.clientId!, scopes }, { onSuccess: (a) => (toast(a, 'Scope change'), onClose()) });
              else create.mutate({ clientId: f.clientId, name: f.name.trim(), scopes, homeBranch: f.homeBranch, allBranches: f.allBranches }, { onSuccess: (a) => (toast(a, 'New API client'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        {!client && (
          <div className="form-grid">
            <Input label="Client id" required className="mono" value={f.clientId} onChange={(e) => setF({ ...f, clientId: e.target.value.trim().toLowerCase() })} hint={`Stored as ext-${f.clientId || '…'}`} error={err('clientId')} />
            <Input label="Name" required maxLength={80} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} error={err('name')} />
            <Select label="Home branch" required value={f.homeBranch} onChange={(e) => setF({ ...f, homeBranch: e.target.value })} options={(branches.data ?? []).map((b) => ({ value: b.code, label: `${b.code} — ${b.name}` }))} error={err('homeBranch')} />
            <Checkbox label="May act for all branches" checked={f.allBranches} onChange={(e) => setF({ ...f, allBranches: e.target.checked })} />
          </div>
        )}
        <fieldset className="fieldset" aria-invalid={!!err('scopes') || undefined}>
          <legend>Scopes</legend>
          <div className="form-grid">
            {grantable.map((s) => (
              <Checkbox key={s} label={<span><span className="mono">{s}</span>{sensitive.includes(s) && <> <Badge tone="warn">Moves money</Badge></>}</span>} checked={scopes.includes(s)} onChange={(e) => setScopes(e.target.checked ? [...scopes, s] : scopes.filter((x) => x !== s))} />
            ))}
          </div>
          {err('scopes') && <p className="field__error" role="alert">{err('scopes')}</p>}
        </fieldset>
        {added.length > 0 && <Banner tone="warn">Two different checkers must approve this: it grants {added.join(', ')}, which can move money.</Banner>}
        {!client && <Banner tone="info">After approval, you (the proposer) collect the client secret here, once. Nobody else can collect it.</Banner>}
        <ErrorBanner error={create.error ?? change.error} />
      </div>
    </Dialog>
  );
}
