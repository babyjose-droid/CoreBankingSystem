import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMe } from '../../api/hooks';
import { useEnumerationTypes, useEnumerationValues, useProposeEnumeration } from '../../api/masterHooks';
import type { EnumValue, EnumValueInput } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, Checkbox, EmptyState, ErrorBanner, Input, PageHeader, Spinner, Table } from '../../ui';
import { useProposalToast } from '../proposal';

export function EnumerationsPage() {
  const q = useEnumerationTypes();
  const navigate = useNavigate();
  return (
    <div className="stack">
      <PageHeader title="Enumerations" subtitle="Code lists used in forms and validation. Values are deactivated, never deleted." />
      <Card flush>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Enumeration types"
            captionHidden
            columns={[
              { key: 'type', header: 'Type', render: (t) => <Link to={`/masters/enumerations/${encodeURIComponent(t.type ?? '')}`} className="mono" onClick={(e) => e.stopPropagation()}>{t.type}</Link> },
              { key: 'count', header: 'Values', numeric: true, render: (t) => t.valueCount },
              { key: 'active', header: 'Active', numeric: true, render: (t) => t.activeCount },
            ]}
            rows={q.data ?? []}
            rowKey={(t) => t.type ?? ''}
            onRowClick={(t) => navigate(`/masters/enumerations/${encodeURIComponent(t.type ?? '')}`)}
            rowLabel={(t) => `Open enumeration ${t.type}`}
            empty={<EmptyState title="No enumerations" />}
          />
        )}
      </Card>
    </div>
  );
}

interface Row {
  key: number;
  code: string;
  label: string;
  sortOrder: string;
  active: boolean;
  isNew: boolean;
}

/** Accessible name of a row: its code, or "new value N" while the code is being typed. */
const nameOf = (r: Row) => (r.isNew ? `new value ${r.key}` : r.code);

const toRows = (values: EnumValue[]): Row[] => values.map((v, i) => ({ key: i + 1, code: v.code, label: v.label, sortOrder: String((i + 1) * 10), active: v.active ?? true, isNew: false }));

export function EnumerationValuesPage() {
  const { type = '' } = useParams();
  const q = useEnumerationValues(type);
  if (q.isLoading) return <Spinner />;
  if (q.error || !q.data) return <ErrorBanner error={q.error ?? new Error('Enumeration not found')} />;
  return <EnumerationEditor key={type} type={type} values={q.data} />;
}

function EnumerationEditor({ type, values }: { type: string; values: EnumValue[] }) {
  const me = useMe().data!;
  const canPropose = hasPermission(me.permissions, P.masterPropose);
  const original = toRows(values);
  const [rows, setRows] = useState<Row[]>(original);
  const [touched, setTouched] = useState(false);
  const propose = useProposeEnumeration();
  const toast = useProposalToast();
  const update = (key: number, patch: Partial<Row>) => setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  const errs = rows.map((r) => ({
    code: !/^[A-Z0-9_]{1,40}$/.test(r.code) ? 'Upper-case letters, digits or _' : rows.filter((x) => x.code === r.code).length > 1 ? 'Duplicate' : null,
    label: !r.label.trim() ? 'Required' : null,
    sortOrder: !/^-?\d+$/.test(r.sortOrder) ? 'Whole number' : null,
  }));
  const valid = errs.every((e) => !e.code && !e.label && !e.sortOrder);
  const changed = rows.filter((r) => {
    const o = original.find((x) => x.code === r.code && !r.isNew);
    return !o || o.label !== r.label.trim() || o.sortOrder !== r.sortOrder || o.active !== r.active;
  });
  const submit = () => {
    setTouched(true);
    if (!valid || changed.length === 0) return;
    const body: EnumValueInput[] = changed.map((r) => ({ code: r.code, label: r.label.trim(), sortOrder: Number(r.sortOrder), active: r.active }));
    propose.mutate({ type, values: body }, { onSuccess: (a) => toast(a, `${changed.length} value change(s) to ${type}`) });
  };
  return (
    <div className="stack">
      <PageHeader title={`Enumeration ${type}`} subtitle="Change labels, order or active flag, or add values. Existing codes cannot change and values are never deleted." actions={<Link to="/masters/enumerations">All enumerations</Link>} />
      <Card
        flush
        actions={
          canPropose && (
            <Button size="sm" onClick={() => setRows((rs) => [...rs, { key: Math.max(0, ...rs.map((r) => r.key)) + 1, code: '', label: '', sortOrder: String((rs.length + 1) * 10), active: true, isNew: true }])}>
              Add value
            </Button>
          )
        }
        title={`${rows.length} values`}
      >
        <Table
          caption={`Values of ${type}`}
          captionHidden
          columns={[
            {
              key: 'code',
              header: 'Code',
              render: (r) =>
                r.isNew && canPropose ? (
                  <Input label={`Code of new value ${r.key}`} labelHidden className="mono" value={r.code} onChange={(e) => update(r.key, { code: e.target.value.toUpperCase() })} error={touched ? errs[rows.indexOf(r)].code : null} />
                ) : (
                  <span className="mono">{r.code}</span>
                ),
            },
            {
              key: 'label',
              header: 'Label',
              render: (r) =>
                canPropose ? (
                  <Input label={`Label of ${nameOf(r)}`} labelHidden value={r.label} onChange={(e) => update(r.key, { label: e.target.value })} error={touched ? errs[rows.indexOf(r)].label : null} />
                ) : (
                  r.label
                ),
            },
            {
              key: 'sort',
              header: 'Sort order',
              render: (r) =>
                canPropose ? (
                  <Input label={`Sort order of ${nameOf(r)}`} labelHidden numeric style={{ width: 90 }} value={r.sortOrder} onChange={(e) => update(r.key, { sortOrder: e.target.value })} error={touched ? errs[rows.indexOf(r)].sortOrder : null} />
                ) : (
                  r.sortOrder
                ),
            },
            {
              key: 'active',
              header: 'Active',
              render: (r) =>
                canPropose ? (
                  <Checkbox label={<span className="sr-only">{`Active: ${nameOf(r)}`}</span>} checked={r.active} onChange={(e) => update(r.key, { active: e.target.checked })} />
                ) : r.active ? (
                  <Badge tone="ok">Active</Badge>
                ) : (
                  <Badge>Inactive</Badge>
                ),
            },
          ]}
          rows={rows}
          rowKey={(r) => r.key}
        />
      </Card>
      {canPropose && (
        <>
          <ErrorBanner error={propose.error} />
          <div className="form-actions">
            <span className="muted">{changed.length === 0 ? 'No changes' : `${changed.length} value(s) changed or added`}</span>
            <Button variant="primary" disabled={changed.length === 0} loading={propose.isPending} onClick={submit}>
              Submit for approval
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
