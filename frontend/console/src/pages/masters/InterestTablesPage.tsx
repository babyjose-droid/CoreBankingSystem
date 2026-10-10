import { useState } from 'react';
import { useInterestTables, useMe, useProposeInterestTable } from '../../api/hooks';
import type { InterestTable, InterestTableInput } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, Table } from '../../ui';
import { useProposalToast } from '../proposal';

const MODE_LABEL: Record<string, string> = { ABSOLUTE: 'Fixed (slab rate is the loan rate)', ADDITIVE: 'Base rate + slab', SPREAD: 'Spread over the product benchmark' };
const pct = (v: string) => `${Number(v).toString()}%`;

/** Rows are edited as text, one band per line: "minAmount maxAmount minTenor maxTenor rate". */
const ROW_HELP = 'One row per line: minimum amount, maximum amount, minimum months, maximum months, rate %. Bands include both ends and may not overlap.';

function parseRows(text: string): { rows: InterestTableInput['rows']; error: string | null } {
  const rows: InterestTableInput['rows'] = [];
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
  for (const [i, line] of lines.entries()) {
    const f = line.split(/[\s,;]+/);
    if (f.length !== 5 || !f.every((x, k) => (k >= 2 && k <= 3 ? /^\d{1,3}$/ : /^\d+(\.\d{1,4})?$/).test(x))) return { rows, error: `Line ${i + 1}: expected five numbers: min amount, max amount, min months, max months, rate` };
    rows.push({ minAmount: f[0], maxAmount: f[1], minTenorMonths: Number(f[2]), maxTenorMonths: Number(f[3]), rate: f[4] });
  }
  return { rows, error: rows.length ? null : 'At least one row is required' };
}

export function InterestTablesPage() {
  const me = useMe().data!;
  const q = useInterestTables();
  const canPropose = hasPermission(me.permissions, P.productPropose);
  const [editing, setEditing] = useState<InterestTable | 'new' | null>(null);
  const tables = q.data ?? [];
  return (
    <div className="stack">
      <PageHeader
        title="Interest tables"
        subtitle="Rate slabs by loan amount and tenor, attached to a product. A SPREAD table holds spreads over the product's benchmark."
        actions={canPropose && <Button variant="primary" onClick={() => setEditing('new')}>Propose table</Button>}
      />
      <ErrorBanner error={q.error} />
      {q.isLoading ? (
        <Spinner />
      ) : tables.length === 0 ? (
        <Card><EmptyState title="No interest tables" /></Card>
      ) : (
        tables.map((t) => (
          <Card
            key={t.code}
            flush
            title={`${t.code} — ${t.name}`}
            headingLevel={2}
            actions={canPropose && <Button size="sm" aria-label={`Propose replacement of ${t.code}`} onClick={() => setEditing(t)}>Propose change</Button>}
          >
            <div className="row" style={{ padding: '8px 16px', gap: 16, flexWrap: 'wrap' }} data-testid={`table-${t.code}`}>
              <Badge tone={t.mode === 'SPREAD' ? 'info' : 'neutral'}>{t.mode === 'SPREAD' ? 'SPREAD' : t.mode === 'ADDITIVE' ? 'ADDITIVE' : 'FIXED'}</Badge>
              <span>{MODE_LABEL[t.mode]}</span>
              {t.mode === 'ADDITIVE' && <span>Base rate {pct(t.baseRate)}</span>}
              <span className="muted">{t.usedByProducts.length ? `Used by ${t.usedByProducts.join(', ')}` : 'Not used by any product'}</span>
            </div>
            <Table
              caption={`Rows of ${t.code}`}
              captionHidden
              columns={[
                { key: 'amt', header: 'Amount', render: (r) => <span className="num">{r.minAmount} to {r.maxAmount}</span> },
                { key: 'ten', header: 'Tenor (months)', render: (r) => <span className="num">{r.minTenorMonths} to {r.maxTenorMonths}</span> },
                { key: 'rate', header: t.mode === 'SPREAD' ? 'Spread' : 'Rate', numeric: true, render: (r) => <span className="num">{pct(r.rate)}</span> },
              ]}
              rows={t.rows}
              rowKey={(r) => `${r.minAmount}-${r.minTenorMonths}`}
              empty={<EmptyState title="No rows" />}
            />
          </Card>
        ))
      )}
      {editing && <ProposeTableDialog table={editing === 'new' ? null : editing} existing={tables.map((t) => t.code)} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ProposeTableDialog({ table, existing, onClose }: { table: InterestTable | null; existing: string[]; onClose: () => void }) {
  const [t, setT] = useState({
    code: table?.code ?? '',
    name: table?.name ?? '',
    mode: table ? (table.mode === 'ABSOLUTE' ? 'FIXED' : table.mode) : 'FIXED',
    baseRate: table?.baseRate && Number(table.baseRate) ? table.baseRate : '',
    rows: (table?.rows ?? []).map((r) => `${r.minAmount} ${r.maxAmount} ${r.minTenorMonths} ${r.maxTenorMonths} ${r.rate}`).join('\n'),
  });
  const [touched, setTouched] = useState(false);
  const propose = useProposeInterestTable();
  const toast = useProposalToast();
  const parsed = parseRows(t.rows);
  const errors = {
    code: !/^[A-Z0-9_]{2,20}$/.test(t.code) ? '2-20 upper-case letters, digits or underscores' : !table && existing.includes(t.code) ? 'This table already exists: use Propose change' : null,
    name: !t.name.trim() ? 'Required' : null,
    baseRate: t.mode === 'ADDITIVE' && !/^\d{1,2}(\.\d{1,4})?$/.test(t.baseRate) ? 'Enter the base rate' : null,
    rows: parsed.error,
  };
  const valid = Object.values(errors).every((e) => !e);
  return (
    <Dialog
      open
      onClose={onClose}
      title={table ? `Propose change of ${table.code}` : 'Propose interest table'}
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
                { code: t.code, name: t.name.trim(), mode: t.mode as InterestTableInput['mode'], ...(t.mode === 'ADDITIVE' ? { baseRate: t.baseRate } : {}), rows: parsed.rows },
                { onSuccess: (a) => (toast(a, 'Interest table'), onClose()) },
              );
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      {table && <p className="muted" style={{ marginTop: 0 }}>The rows below replace all rows of the table once approved. Loans already booked keep their rate; a spread table applies to a loan from its next rate reset.</p>}
      <div className="form-grid">
        <Input label="Code" required className="mono" disabled={!!table} value={t.code} onChange={(e) => setT({ ...t, code: e.target.value.toUpperCase() })} error={touched ? errors.code : null} />
        <Input label="Name" required value={t.name} onChange={(e) => setT({ ...t, name: e.target.value })} error={touched ? errors.name : null} />
        <Select
          label="Mode"
          value={t.mode}
          onChange={(e) => setT({ ...t, mode: e.target.value })}
          options={[
            { value: 'FIXED', label: 'Fixed: the slab rate is the loan rate' },
            { value: 'ADDITIVE', label: 'Base rate + slab' },
            { value: 'SPREAD', label: 'Spread over the product benchmark' },
          ]}
        />
        {t.mode === 'ADDITIVE' && <Input label="Base rate % p.a." numeric value={t.baseRate} onChange={(e) => setT({ ...t, baseRate: e.target.value.replace(/[^0-9.]/g, '') })} error={touched ? errors.baseRate : null} />}
      </div>
      <label className="field" style={{ display: 'block', marginTop: 12 }}>
        <span>Rows</span>
        <textarea className="mono" rows={8} style={{ width: '100%' }} value={t.rows} onChange={(e) => setT({ ...t, rows: e.target.value })} aria-label="Rows" placeholder={'0 500000 12 60 14.5\n500000.01 2500000 12 60 13.5'} />
        <span className="muted">{touched && errors.rows ? errors.rows : ROW_HELP}</span>
      </label>
      <div style={{ marginTop: 12 }}>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
