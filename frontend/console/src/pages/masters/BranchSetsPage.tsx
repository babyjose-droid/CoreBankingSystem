import { useState } from 'react';
import { useBranches, useMe } from '../../api/hooks';
import { useBranchSets, useProposeBranchSet } from '../../api/masterHooks';
import type { BranchSet } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Spinner, Table } from '../../ui';
import { useProposalToast } from '../proposal';
import { Checklist } from './Checklist';

export function BranchSetsPage() {
  const me = useMe().data!;
  const q = useBranchSets();
  const canPropose = hasPermission(me.permissions, P.branchPropose);
  const [editing, setEditing] = useState<BranchSet | 'new' | null>(null);
  return (
    <div className="stack">
      <PageHeader
        title="Branch sets"
        subtitle="Named groups of branches, used for reporting and to grant staff a whole zone at once."
        actions={canPropose && <Button variant="primary" onClick={() => setEditing('new')}>New branch set</Button>}
      />
      <Card flush>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Branch sets"
            captionHidden
            columns={[
              { key: 'code', header: 'Code', render: (s) => <span className="mono">{s.code}</span> },
              { key: 'name', header: 'Name', render: (s) => s.name },
              { key: 'branches', header: 'Branches', render: (s) => s.branches.join(', ') },
              ...(canPropose
                ? [{ key: 'act', header: <span className="sr-only">Actions</span>, render: (s: BranchSet) => <Button size="sm" onClick={() => setEditing(s)} aria-label={`Edit branch set ${s.code}`}>Edit</Button> }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(s) => s.code}
            empty={<EmptyState title="No branch sets" />}
          />
        )}
      </Card>
      {editing && <BranchSetDialog initial={editing === 'new' ? null : editing} existing={q.data ?? []} onClose={() => setEditing(null)} />}
    </div>
  );
}

function BranchSetDialog({ initial, existing, onClose }: { initial: BranchSet | null; existing: BranchSet[]; onClose: () => void }) {
  const branches = useBranches();
  const propose = useProposeBranchSet();
  const toast = useProposalToast();
  const [s, setS] = useState<BranchSet>(initial ?? { code: '', name: '', branches: [] });
  const [touched, setTouched] = useState(false);
  const errors = {
    code: !/^[A-Z0-9_]{2,20}$/.test(s.code) ? '2-20 upper-case letters, digits or _' : !initial && existing.some((x) => x.code === s.code) ? 'Code already exists' : null,
    name: !s.name.trim() ? 'Name is required' : null,
    branches: s.branches.length === 0 ? 'Select at least one branch' : null,
  };
  const valid = Object.values(errors).every((e) => !e);
  return (
    <Dialog
      open
      onClose={onClose}
      title={initial ? `Edit branch set ${initial.code}` : 'New branch set'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              propose.mutate({ ...s, name: s.name.trim() }, { onSuccess: (a) => (toast(a, initial ? 'Branch set change' : 'New branch set'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <div className="form-grid">
          <Input label="Code" required disabled={!!initial} className="mono" value={s.code} onChange={(e) => setS({ ...s, code: e.target.value.toUpperCase() })} error={touched ? errors.code : null} />
          <Input label="Name" required value={s.name} onChange={(e) => setS({ ...s, name: e.target.value })} error={touched ? errors.name : null} />
        </div>
        <Checklist
          legend="Member branches"
          options={(branches.data ?? []).map((b) => ({ value: b.code, label: `${b.code} — ${b.name}` }))}
          selected={s.branches}
          onChange={(v) => setS({ ...s, branches: v })}
          error={touched ? errors.branches : null}
        />
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
