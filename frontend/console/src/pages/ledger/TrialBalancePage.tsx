import { useState } from 'react';
import { useBranches, useGlEntries, useMe, useTrialBalance } from '../../api/hooks';
import type { TrialBalanceRow } from '../../api/types';
import { downloadCsv } from '../../lib/csv';
import { financialYearStart } from '../../lib/dates';
import { formatINR, fromUnits, toUnits } from '../../lib/money';
import { Banner, Button, Card, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Select, Spinner, Table } from '../../ui';
import { CategoryBadge } from './common';

export function tbTotals(rows: TrialBalanceRow[]) {
  let dr = 0n;
  let cr = 0n;
  let net = 0n;
  for (const r of rows) {
    dr += toUnits(r.debit);
    cr += toUnits(r.credit);
    net += toUnits(r.net);
  }
  return { dr, cr, net, balanced: net === 0n && dr === cr };
}

export function TrialBalancePage() {
  const me = useMe().data!;
  const [asOf, setAsOf] = useState(me.businessDate);
  const [branch, setBranch] = useState('');
  const [drill, setDrill] = useState<TrialBalanceRow | null>(null);
  const branches = useBranches();
  const q = useTrialBalance(asOf, branch);
  const rows = q.data ?? [];
  const t = tbTotals(rows);

  return (
    <div className="stack">
      <PageHeader
        title="Trial balance"
        subtitle="Per posting head, from ledger entries up to the as-of date. Select a row to drill down."
        actions={
          <Button
            disabled={rows.length === 0}
            onClick={() =>
              downloadCsv(
                `trial-balance_${asOf}${branch ? '_' + branch : ''}.csv`,
                ['GL code', 'GL name', 'Category', 'Debit', 'Credit', 'Net'],
                [...rows.map((r) => [r.glCode, r.glName, r.category, r.debit, r.credit, r.net]), ['', 'TOTAL', '', fromUnits(t.dr), fromUnits(t.cr), fromUnits(t.net)]],
              )
            }
          >
            Download CSV
          </Button>
        }
      />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Input label="As of" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          <Select
            label="Branch"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            options={[{ value: '', label: 'All branches' }, ...(branches.data ?? []).map((b) => ({ value: b.code, label: `${b.code} — ${b.name}` }))]}
          />
          <div style={{ paddingBottom: 2 }}>
            {!q.isLoading && !q.error && rows.length > 0 &&
              (t.balanced ? (
                <Banner tone="ok">✓ Balanced — Σ net = {formatINR('0')}</Banner>
              ) : (
                <Banner tone="danger">✕ Not balanced — Σ net = {formatINR(fromUnits(t.net))}</Banner>
              ))}
          </div>
        </div>
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={q.error} />
        </div>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption={`Trial balance as of ${asOf}`}
            captionHidden
            columns={[
              { key: 'code', header: 'GL code', render: (r) => <span className="mono">{r.glCode}</span> },
              { key: 'name', header: 'GL name', render: (r) => r.glName },
              { key: 'cat', header: 'Category', render: (r) => <CategoryBadge category={r.category} /> },
              { key: 'dr', header: 'Debit', numeric: true, render: (r) => <MoneyText value={r.debit} /> },
              { key: 'cr', header: 'Credit', numeric: true, render: (r) => <MoneyText value={r.credit} /> },
              { key: 'net', header: 'Net (DR − CR)', numeric: true, render: (r) => <MoneyText value={r.net} signed /> },
            ]}
            rows={rows}
            rowKey={(r) => r.glCode}
            onRowClick={setDrill}
            rowLabel={(r) => `Show entries for ${r.glCode} ${r.glName}`}
            empty={<EmptyState title="No entries up to this date" />}
            footer={
              rows.length > 0 && (
                <tr>
                  <td colSpan={3}>Total</td>
                  <td className="num">
                    <MoneyText value={fromUnits(t.dr)} />
                  </td>
                  <td className="num">
                    <MoneyText value={fromUnits(t.cr)} />
                  </td>
                  <td className="num" data-testid="tb-net-total">
                    <MoneyText value={fromUnits(t.net)} signed />
                  </td>
                </tr>
              )
            }
          />
        )}
      </Card>
      <EntriesDrawer row={drill} asOf={asOf} branch={branch} onClose={() => setDrill(null)} />
    </div>
  );
}

function EntriesDrawer({ row, asOf, branch, onClose }: { row: TrialBalanceRow | null; asOf: string; branch: string; onClose: () => void }) {
  const [from, setFrom] = useState(financialYearStart(asOf));
  const q = useGlEntries(row ? { glCode: row.glCode, from, to: asOf, branch } : null);
  return (
    <Dialog open={!!row} onClose={onClose} variant="drawer" title={row ? `${row.glCode} ${row.glName}` : ''}>
      {row && (
        <div className="stack">
          <div className="filters">
            <Input label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            <span className="muted" style={{ paddingBottom: 6 }}>
              to <DateText value={asOf} /> {branch && `· branch ${branch}`}
            </span>
          </div>
          <ErrorBanner error={q.error} />
          {q.isLoading ? (
            <Spinner />
          ) : (
            <Table
              caption="Ledger entries"
              captionHidden
              columns={[
                { key: 'date', header: 'Date', render: (e) => <DateText value={e.businessDate} /> },
                { key: 'branch', header: 'Branch', render: (e) => e.branch },
                { key: 'narr', header: 'Narration', render: (e) => e.narration },
                { key: 'type', header: 'Lot type', render: (e) => <span className="mono" style={{ fontSize: 12 }}>{e.lotType}</span> },
                { key: 'dr', header: 'Debit', numeric: true, render: (e) => (e.side === 'DR' ? <MoneyText value={e.amount} /> : '') },
                { key: 'cr', header: 'Credit', numeric: true, render: (e) => (e.side === 'CR' ? <MoneyText value={e.amount} /> : '') },
              ]}
              rows={q.data ?? []}
              rowKey={(e) => `${e.lotId}-${e.branch}-${e.side}-${e.amount}-${e.narration}`}
              empty={<EmptyState title="No entries in this period" />}
            />
          )}
        </div>
      )}
    </Dialog>
  );
}
