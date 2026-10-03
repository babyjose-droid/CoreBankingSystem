import { useState } from 'react';
import { useMe } from '../../api/hooks';
import { useEnumerationTypes } from '../../api/masterHooks';
import { useCustomFields, useProposeCustomField, type CustomEntity } from '../../api/platformHooks';
import type { CustomField, CustomFieldInput } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, Checkbox, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, Table, humanize } from '../../ui';
import { useProposalToast } from '../proposal';

const ENTITIES: Array<{ value: CustomEntity; label: string }> = [
  { value: 'CUSTOMER', label: 'Customer' },
  { value: 'LOAN_ACCOUNT', label: 'Loan account' },
  { value: 'LOAN_PRODUCT', label: 'Loan product' },
];
const TYPES: CustomFieldInput['dataType'][] = ['TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'ENUM'];

export function CustomFieldsPage() {
  const me = useMe().data!;
  const canPropose = hasPermission(me.permissions, P.customFieldPropose);
  const [entity, setEntity] = useState<CustomEntity | ''>('');
  const q = useCustomFields(entity || undefined);
  const [editing, setEditing] = useState<CustomField | 'new' | null>(null);
  return (
    <div className="stack">
      <PageHeader
        title="Custom fields"
        subtitle="Extra fields on customers, loan accounts and loan products. A field is deactivated, never deleted; its type and personal-data flag cannot change."
        actions={canPropose && <Button variant="primary" onClick={() => setEditing('new')}>New custom field</Button>}
      />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select label="Entity" value={entity} onChange={(e) => setEntity(e.target.value as CustomEntity | '')} options={[{ value: '', label: 'All' }, ...ENTITIES]} />
        </div>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Custom fields"
            captionHidden
            columns={[
              { key: 'entity', header: 'Entity', render: (f) => humanize(f.entity ?? '') },
              { key: 'key', header: 'Key', render: (f) => <span className="mono">{f.key}</span> },
              { key: 'label', header: 'Label', render: (f) => f.label },
              { key: 'type', header: 'Type', render: (f) => (<span>{humanize(f.dataType ?? '')}{f.enumType ? <span className="mono muted"> {f.enumType}</span> : null}</span>) },
              { key: 'rules', header: 'Rules', render: (f) => [f.required && 'required', f.regex && `pattern ${f.regex}`, f.min && `min ${f.min}`, f.max && `max ${f.max}`].filter(Boolean).join(' · ') },
              { key: 'flags', header: 'Flags', render: (f) => (<span className="row" style={{ gap: 4 }}>{f.pii && <Badge tone="info">Personal data</Badge>}{f.active ? <Badge tone="ok">Active</Badge> : <Badge>Inactive</Badge>}</span>) },
              ...(canPropose ? [{ key: 'act', header: <span className="sr-only">Actions</span>, render: (f: CustomField) => <Button size="sm" aria-label={`Edit ${f.entity} ${f.key}`} onClick={() => setEditing(f)}>Edit</Button> }] : []),
            ]}
            rows={q.data ?? []}
            rowKey={(f) => `${f.entity}.${f.key}`}
            empty={<EmptyState title="No custom fields" />}
          />
        )}
      </Card>
      {editing && <FieldDialog initial={editing === 'new' ? null : editing} defaultEntity={entity || 'CUSTOMER'} onClose={() => setEditing(null)} />}
    </div>
  );
}

function FieldDialog({ initial, defaultEntity, onClose }: { initial: CustomField | null; defaultEntity: CustomEntity; onClose: () => void }) {
  const propose = useProposeCustomField();
  const enums = useEnumerationTypes();
  const toast = useProposalToast();
  const [f, setF] = useState({
    entity: (initial?.entity as CustomEntity) ?? defaultEntity,
    key: initial?.key ?? '',
    label: initial?.label ?? '',
    dataType: (initial?.dataType as CustomFieldInput['dataType']) ?? 'TEXT',
    enumType: initial?.enumType ?? '',
    required: !!initial?.required,
    regex: initial?.regex ?? '',
    min: initial?.min ?? '',
    max: initial?.max ?? '',
    pii: !!initial?.pii,
    active: initial?.active ?? true,
    sortOrder: String(initial?.sortOrder ?? 0),
  });
  const [touched, setTouched] = useState(false);
  const num = (v: string) => v.trim() === '' || /^-?\d+(\.\d+)?$/.test(v.trim());
  let regexOk = true;
  try {
    if (f.regex) new RegExp(f.regex);
  } catch {
    regexOk = false;
  }
  const errs = {
    key: !/^[a-z][A-Za-z0-9]{1,39}$/.test(f.key) ? 'Starts with a lower-case letter; 2-40 letters or digits' : null,
    label: !f.label.trim() ? 'Label is required' : null,
    enumType: f.dataType === 'ENUM' && !f.enumType ? 'Choose the enumeration that lists the choices' : null,
    regex: !regexOk ? 'Not a valid regular expression' : null,
    min: !num(f.min) ? 'A number' : null,
    max: !num(f.max) ? 'A number' : f.min.trim() && f.max.trim() && Number(f.max) < Number(f.min) ? 'At least the minimum' : null,
    sortOrder: !/^-?\d+$/.test(f.sortOrder) ? 'Whole number' : null,
  };
  const valid = Object.values(errs).every((e) => !e);
  const err = (k: keyof typeof errs) => (touched ? errs[k] : null);
  const ranged = f.dataType === 'TEXT' || f.dataType === 'NUMBER';
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={initial ? `Edit custom field ${initial.key}` : 'New custom field'}
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
                {
                  entity: f.entity, key: f.key, label: f.label.trim(), dataType: f.dataType, enumType: f.dataType === 'ENUM' ? f.enumType : null, required: f.required,
                  regex: f.dataType === 'TEXT' && f.regex ? f.regex : null, min: ranged && f.min.trim() ? Number(f.min) : null, max: ranged && f.max.trim() ? Number(f.max) : null,
                  pii: f.dataType === 'TEXT' && f.pii, active: f.active, sortOrder: Number(f.sortOrder),
                },
                { onSuccess: (a) => (toast(a, initial ? 'Custom field change' : 'New custom field'), onClose()) },
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
          <Select label="Entity" disabled={!!initial} value={f.entity} onChange={(e) => setF({ ...f, entity: e.target.value as CustomEntity })} options={ENTITIES} />
          <Input label="Key" required disabled={!!initial} className="mono" value={f.key} onChange={(e) => setF({ ...f, key: e.target.value.trim() })} hint="As used in the API, e.g. dsaCode" error={err('key')} />
          <Input label="Label" required maxLength={80} value={f.label} onChange={(e) => setF({ ...f, label: e.target.value })} error={err('label')} />
          <Select label="Data type" disabled={!!initial} value={f.dataType} onChange={(e) => setF({ ...f, dataType: e.target.value as typeof f.dataType })} options={TYPES.map((t) => ({ value: t, label: humanize(t) }))} hint={initial ? 'Cannot change' : undefined} />
          {f.dataType === 'ENUM' && (
            <Select label="Enumeration" required value={f.enumType} placeholder="Select…" onChange={(e) => setF({ ...f, enumType: e.target.value })} options={(enums.data ?? []).map((t) => ({ value: t.type ?? '', label: t.type ?? '' }))} error={err('enumType')} />
          )}
          {f.dataType === 'TEXT' && <Input label="Pattern (regular expression)" className="mono" value={f.regex} onChange={(e) => setF({ ...f, regex: e.target.value })} hint="The whole value must match" error={err('regex')} />}
          {ranged && <Input label={f.dataType === 'TEXT' ? 'Shortest length' : 'Lowest value'} numeric value={f.min} onChange={(e) => setF({ ...f, min: e.target.value })} error={err('min')} />}
          {ranged && <Input label={f.dataType === 'TEXT' ? 'Longest length' : 'Highest value'} numeric value={f.max} onChange={(e) => setF({ ...f, max: e.target.value })} error={err('max')} />}
          <Input label="Sort order" numeric value={f.sortOrder} onChange={(e) => setF({ ...f, sortOrder: e.target.value })} error={err('sortOrder')} />
        </div>
        <div className="row">
          <Checkbox label="Required" checked={f.required} onChange={(e) => setF({ ...f, required: e.target.checked })} />
          <Checkbox label="Personal data (stored encrypted, shown masked)" checked={f.dataType === 'TEXT' && f.pii} disabled={!!initial || f.dataType !== 'TEXT'} onChange={(e) => setF({ ...f, pii: e.target.checked })} />
          <Checkbox label="Active" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />
        </div>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
