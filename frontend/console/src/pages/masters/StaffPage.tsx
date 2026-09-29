import { useState } from 'react';
import { useBranches, useMe } from '../../api/hooks';
import { useBranchSets, useProposeStaff, useStaff } from '../../api/masterHooks';
import type { Staff } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, Checkbox, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, StatusBadge, Table } from '../../ui';
import { useProposalToast } from '../proposal';
import { Checklist } from './Checklist';

const USERNAME = /^[A-Za-z0-9._@-]{2,80}$/;

export function StaffPage() {
  const me = useMe().data!;
  const q = useStaff();
  const canPropose = hasPermission(me.permissions, P.staffPropose);
  const [editing, setEditing] = useState<Staff | 'new' | null>(null);
  return (
    <div className="stack">
      <PageHeader
        title="Staff"
        subtitle="Staff profiles and the branches whose data each person can see. Sign-in accounts live in the identity provider."
        actions={canPropose && <Button variant="primary" onClick={() => setEditing('new')}>New staff profile</Button>}
      />
      <Card flush>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Staff"
            captionHidden
            columns={[
              { key: 'user', header: 'Username', render: (s) => <span className="mono">{s.username}</span> },
              { key: 'name', header: 'Name', render: (s) => s.displayName },
              { key: 'home', header: 'Home branch', render: (s) => s.homeBranch },
              { key: 'scope', header: 'Branch scope', render: (s) => (s.allBranches ? <Badge tone="accent">All branches</Badge> : <ScopeText s={s} />) },
              { key: 'status', header: 'Status', render: (s) => <StatusBadge status={s.status ?? 'ACTIVE'} /> },
              ...(canPropose
                ? [{ key: 'act', header: <span className="sr-only">Actions</span>, render: (s: Staff) => <Button size="sm" onClick={() => setEditing(s)} aria-label={`Edit staff ${s.username}`}>Edit</Button> }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(s) => s.username}
            empty={<EmptyState title="No staff profiles" />}
          />
        )}
      </Card>
      {editing && <StaffDialog initial={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ScopeText({ s }: { s: Staff }) {
  const parts = [
    (s.branches ?? []).length > 0 && `+ ${(s.branches ?? []).join(', ')}`,
    (s.branchSets ?? []).length > 0 && `sets ${(s.branchSets ?? []).join(', ')}`,
  ].filter(Boolean);
  return <span>{parts.length ? parts.join(' · ') : <span className="muted">Home branch only</span>}</span>;
}

function StaffDialog({ initial, onClose }: { initial: Staff | null; onClose: () => void }) {
  const me = useMe().data!;
  const branches = useBranches();
  const sets = useBranchSets();
  const propose = useProposeStaff();
  const toast = useProposalToast();
  const [s, setS] = useState<Staff>(
    initial ?? { username: '', displayName: '', homeBranch: me.homeBranch ?? '', allBranches: false, branches: [], branchSets: [], status: 'ACTIVE' },
  );
  const [touched, setTouched] = useState(false);
  const visible = me.allBranches ? null : new Set(me.branches ?? []);
  const errors = {
    username: !USERNAME.test(s.username) ? '2-80 letters, digits or . _ @ -' : null,
    displayName: !s.displayName.trim() ? 'Display name is required' : null,
    homeBranch: !s.homeBranch ? 'Select a home branch' : null,
  };
  const valid = Object.values(errors).every((e) => !e);
  const branchOptions = (branches.data ?? []).map((b) => ({ value: b.code, label: `${b.code} — ${b.name}`, disabled: !!visible && !visible.has(b.code) }));
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={initial ? `Edit staff ${initial.username}` : 'New staff profile'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              const { userId: _u, ...body } = s;
              propose.mutate(
                { ...body, displayName: s.displayName.trim(), branches: s.allBranches ? [] : (s.branches ?? []).filter((b) => b !== s.homeBranch), branchSets: s.allBranches ? [] : s.branchSets },
                { onSuccess: (a) => (toast(a, initial ? 'Staff change' : 'New staff profile'), onClose()) },
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
          <Input label="Username" required disabled={!!initial} className="mono" value={s.username} onChange={(e) => setS({ ...s, username: e.target.value.trim() })} error={touched ? errors.username : null} hint="Identity-provider (Keycloak) username" />
          <Input label="Display name" required value={s.displayName} onChange={(e) => setS({ ...s, displayName: e.target.value })} error={touched ? errors.displayName : null} />
          <Select label="Home branch" required value={s.homeBranch} placeholder="Select…" onChange={(e) => setS({ ...s, homeBranch: e.target.value })} options={branchOptions} error={touched ? errors.homeBranch : null} />
          <Select
            label="Status"
            value={s.status ?? 'ACTIVE'}
            onChange={(e) => setS({ ...s, status: e.target.value as Staff['status'] })}
            options={[
              { value: 'ACTIVE', label: 'Active' },
              { value: 'SUSPENDED', label: 'Suspended' },
              { value: 'EXITED', label: 'Exited' },
            ]}
          />
        </div>
        <Checkbox
          label="All branches (sees every branch, including new ones)"
          checked={!!s.allBranches}
          disabled={!me.allBranches}
          onChange={(e) => setS({ ...s, allBranches: e.target.checked })}
        />
        {!me.allBranches && <p className="muted" style={{ margin: 0, fontSize: 12 }}>You can grant only branches you see yourself ({(me.branches ?? []).join(', ') || me.homeBranch}).</p>}
        {!s.allBranches && (
          <>
            <Checklist legend="Additional branches" options={branchOptions.filter((o) => o.value !== s.homeBranch)} selected={s.branches ?? []} onChange={(v) => setS({ ...s, branches: v })} />
            <Checklist
              legend="Branch sets"
              options={(sets.data ?? []).map((x) => ({ value: x.code, label: `${x.code} — ${x.name} (${x.branches.join(', ')})`, disabled: !!visible && x.branches.some((b) => !visible.has(b)) }))}
              selected={s.branchSets ?? []}
              onChange={(v) => setS({ ...s, branchSets: v })}
            />
          </>
        )}
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
