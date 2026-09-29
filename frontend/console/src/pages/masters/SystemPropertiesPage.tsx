import { useState } from 'react';
import { useGlHeads, useMe } from '../../api/hooks';
import { useProposeSystemProperty, useSystemProperties } from '../../api/masterHooks';
import type { SystemProperty } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, Table, Textarea } from '../../ui';
import { useProposalToast } from '../proposal';

export function SystemPropertiesPage() {
  const me = useMe().data!;
  const q = useSystemProperties();
  const canPropose = hasPermission(me.permissions, P.masterPropose);
  const [editing, setEditing] = useState<SystemProperty | 'new' | null>(null);
  return (
    <div className="stack">
      <PageHeader
        title="System properties"
        subtitle="Tenant-wide settings. Keys ending in -gl name a posting GL head. Changes go through maker-checker."
        actions={canPropose && <Button variant="primary" onClick={() => setEditing('new')}>New property</Button>}
      />
      <Card flush>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="System properties"
            captionHidden
            columns={[
              { key: 'key', header: 'Key', render: (p) => <span className="mono">{p.key}</span> },
              { key: 'value', header: 'Value', render: (p) => <span className="mono">{p.value}</span> },
              { key: 'desc', header: 'Description', render: (p) => p.description ?? '' },
              { key: 'by', header: 'Last changed', render: (p) => (<span><span className="mono">{p.updatedBy}</span> · <DateTimeText value={p.updatedAt} /></span>) },
              ...(canPropose
                ? [{ key: 'act', header: <span className="sr-only">Actions</span>, render: (p: SystemProperty) => <Button size="sm" onClick={() => setEditing(p)} aria-label={`Edit ${p.key}`}>Edit</Button> }]
                : []),
            ]}
            rows={q.data ?? []}
            rowKey={(p) => p.key ?? ''}
            empty={<EmptyState title="No properties" />}
          />
        )}
      </Card>
      {editing && <PropertyDialog initial={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function PropertyDialog({ initial, onClose }: { initial: SystemProperty | null; onClose: () => void }) {
  const [key, setKey] = useState(initial?.key ?? '');
  const [value, setValue] = useState(initial?.value ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [touched, setTouched] = useState(false);
  const isGl = key.endsWith('-gl');
  const heads = useGlHeads(isGl);
  const propose = useProposeSystemProperty();
  const toast = useProposalToast();
  const errors = {
    key: !/^[a-z][a-z0-9_.-]+$/.test(key) ? 'Lower-case letters, digits, dot, dash or underscore' : null,
    value: !value.trim() ? 'Value is required' : initial && value.trim() === initial.value && description.trim() === (initial.description ?? '') ? 'Change the value or description' : null,
  };
  const valid = !errors.key && !errors.value;
  return (
    <Dialog
      open
      onClose={onClose}
      title={initial ? `Edit ${initial.key}` : 'New system property'}
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
                { key, change: { value: value.trim(), ...(description.trim() ? { description: description.trim() } : {}) } },
                { onSuccess: (a) => (toast(a, 'Property change'), onClose()) },
              );
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <Input label="Key" required disabled={!!initial} className="mono" value={key} onChange={(e) => setKey(e.target.value.trim())} error={touched ? errors.key : null} />
        {isGl ? (
          <Select
            label="Value (GL head)"
            required
            value={value}
            placeholder="Select…"
            onChange={(e) => setValue(e.target.value)}
            options={(heads.data ?? []).filter((h) => h.posting && h.status === 'ACTIVE').map((h) => ({ value: h.code, label: `${h.code} — ${h.name}` }))}
            error={touched ? errors.value : null}
          />
        ) : (
          <Input label="Value" required className="mono" value={value} onChange={(e) => setValue(e.target.value)} error={touched ? errors.value : null} />
        )}
        <Textarea label="Description" value={description} onChange={(e) => setDescription(e.target.value)} />
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
