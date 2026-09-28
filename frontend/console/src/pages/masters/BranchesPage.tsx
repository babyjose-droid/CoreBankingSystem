import { useState } from 'react';
import { useBranches, useEnumeration, useMe, useProposeBranch } from '../../api/hooks';
import type { Branch } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { BRANCH_CODE_PATTERN, IFSC_PATTERN } from '../../lib/mask';
import { Badge, Button, Card, Checkbox, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, StatusBadge, Table } from '../../ui';
import { useProposalToast } from '../proposal';

export function BranchesPage() {
  const me = useMe().data!;
  const q = useBranches();
  const canPropose = hasPermission(me.permissions, P.branchPropose);
  const [editing, setEditing] = useState<Branch | 'new' | null>(null);
  return (
    <div className="stack">
      <PageHeader title="Branches" actions={canPropose && <Button variant="primary" onClick={() => setEditing('new')}>New branch</Button>} />
      <Card flush>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Branches"
            captionHidden
            columns={[
              { key: 'code', header: 'Code', render: (b) => <span className="mono">{b.code}</span> },
              { key: 'name', header: 'Name', render: (b) => (<span>{b.name} {b.headOffice && <Badge tone="accent">HO</Badge>}</span>) },
              { key: 'ifsc', header: 'IFSC', render: (b) => <span className="mono">{b.ifsc ?? '—'}</span> },
              { key: 'state', header: 'GST state', render: (b) => <span className="mono">{b.stateCode}</span> },
              { key: 'parent', header: 'Parent', render: (b) => b.parentCode ?? '—' },
              { key: 'status', header: 'Status', render: (b) => <StatusBadge status={b.status ?? 'ACTIVE'} /> },
              ...(canPropose
                ? [{ key: 'act', header: <span className="sr-only">Actions</span>, render: (b: Branch) => <Button size="sm" onClick={() => setEditing(b)} aria-label={`Edit branch ${b.code}`}>Edit</Button> }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(b) => b.code}
            empty={<EmptyState title="No branches" />}
          />
        )}
      </Card>
      {editing && <BranchDialog initial={editing === 'new' ? null : editing} branches={q.data ?? []} onClose={() => setEditing(null)} />}
    </div>
  );
}

function BranchDialog({ initial, branches, onClose }: { initial: Branch | null; branches: Branch[]; onClose: () => void }) {
  const [b, setB] = useState<Branch>(initial ?? { code: '', name: '', stateCode: '', ifsc: '', parentCode: 'HO', headOffice: false, status: 'ACTIVE' });
  const [touched, setTouched] = useState(false);
  const states = useEnumeration('gst-state');
  const propose = useProposeBranch();
  const toast = useProposalToast();
  const errors = {
    code: !BRANCH_CODE_PATTERN.test(b.code) ? '2-10 upper-case letters or digits' : !initial && branches.some((x) => x.code === b.code) ? 'Code already exists' : null,
    name: !b.name.trim() ? 'Name is required' : null,
    stateCode: !b.stateCode ? 'Select a state' : null,
    ifsc: b.ifsc && !IFSC_PATTERN.test(b.ifsc) ? 'IFSC looks like HDFC0001234' : null,
  };
  const valid = Object.values(errors).every((e) => !e);
  return (
    <Dialog
      open
      onClose={onClose}
      title={initial ? `Edit branch ${initial.code}` : 'New branch'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              propose.mutate(
                { ...b, name: b.name.trim(), ifsc: b.ifsc || null, parentCode: b.parentCode || null },
                { onSuccess: (a) => (toast(a, initial ? 'Branch change' : 'New branch'), onClose()) },
              );
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <Input label="Code" required disabled={!!initial} value={b.code} className="mono" onChange={(e) => setB({ ...b, code: e.target.value.toUpperCase() })} error={touched ? errors.code : null} />
        <Input label="Name" required value={b.name} onChange={(e) => setB({ ...b, name: e.target.value })} error={touched ? errors.name : null} />
        <Input label="IFSC" value={b.ifsc ?? ''} className="mono" onChange={(e) => setB({ ...b, ifsc: e.target.value.toUpperCase() })} error={touched ? errors.ifsc : null} />
        <Select
          label="GST state"
          required
          value={b.stateCode}
          placeholder="Select…"
          onChange={(e) => setB({ ...b, stateCode: e.target.value })}
          options={(states.data ?? []).map((s) => ({ value: s.code, label: `${s.code} — ${s.label}` }))}
          error={touched ? errors.stateCode : null}
        />
        <Select
          label="Parent branch"
          value={b.parentCode ?? ''}
          placeholder="None"
          onChange={(e) => setB({ ...b, parentCode: e.target.value })}
          options={branches.filter((x) => x.code !== b.code).map((x) => ({ value: x.code, label: x.code }))}
        />
        <Select
          label="Status"
          value={b.status ?? 'ACTIVE'}
          onChange={(e) => setB({ ...b, status: e.target.value as Branch['status'] })}
          options={[
            { value: 'ACTIVE', label: 'Active' },
            { value: 'CLOSED', label: 'Closed' },
          ]}
        />
      </div>
      <div style={{ marginTop: 12 }}>
        <Checkbox label="Head office" checked={!!b.headOffice} onChange={(e) => setB({ ...b, headOffice: e.target.checked })} />
      </div>
      <div style={{ marginTop: 12 }}>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
