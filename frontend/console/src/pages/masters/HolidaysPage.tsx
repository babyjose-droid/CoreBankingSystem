import { useState } from 'react';
import { useBranches, useHolidays, useMe, useProposeHolidays } from '../../api/hooks';
import type { Holiday } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Button, Card, DateText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, Table } from '../../ui';
import { useProposalToast } from '../proposal';

export function HolidaysPage() {
  const me = useMe().data!;
  const thisYear = Number(me.businessDate.slice(0, 4));
  const [year, setYear] = useState(thisYear);
  const [branch, setBranch] = useState('');
  const branches = useBranches();
  const q = useHolidays(year, branch);
  const [adding, setAdding] = useState(false);
  const canPropose = hasPermission(me.permissions, P.holidayPropose);
  return (
    <div className="stack">
      <PageHeader title="Holidays" subtitle="Tenant-wide holidays (all branches) skip the business date in end-of-day." actions={canPropose && <Button variant="primary" onClick={() => setAdding(true)}>Add holidays</Button>} />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select
            label="Year"
            value={String(year)}
            onChange={(e) => setYear(Number(e.target.value))}
            options={[thisYear - 1, thisYear, thisYear + 1, thisYear + 2].map((y) => ({ value: String(y), label: String(y) }))}
          />
          <Select
            label="Branch"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            options={[{ value: '', label: 'All' }, ...(branches.data ?? []).map((b) => ({ value: b.code, label: b.code }))]}
          />
        </div>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption={`Holidays ${year}`}
            captionHidden
            columns={[
              { key: 'day', header: 'Date', render: (h) => <DateText value={h.day} weekday /> },
              { key: 'reason', header: 'Reason', render: (h) => h.reason },
              { key: 'branch', header: 'Applies to', render: (h) => h.branchCode ?? 'All branches' },
            ]}
            rows={q.data ?? []}
            rowKey={(h) => `${h.day}-${h.branchCode ?? 'ALL'}`}
            empty={<EmptyState title={`No holidays in ${year}`} />}
          />
        )}
      </Card>
      {adding && <AddHolidaysDialog onClose={() => setAdding(false)} />}
    </div>
  );
}

interface Row extends Holiday {
  key: number;
}

function AddHolidaysDialog({ onClose }: { onClose: () => void }) {
  const me = useMe().data!;
  const branches = useBranches();
  const propose = useProposeHolidays();
  const toast = useProposalToast();
  const [rows, setRows] = useState<Row[]>([{ key: 1, day: '', reason: '', branchCode: '' }]);
  const [touched, setTouched] = useState(false);
  const errs = rows.map((r) => ({
    day: !r.day ? 'Required' : r.day <= me.businessDate ? 'Must be after the business date' : rows.filter((x) => x.day === r.day && x.branchCode === r.branchCode).length > 1 ? 'Duplicate' : null,
    reason: !r.reason.trim() ? 'Required' : null,
  }));
  const valid = errs.every((e) => !e.day && !e.reason);
  const update = (key: number, patch: Partial<Row>) => setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title="Add holidays"
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
                rows.map((r) => ({ day: r.day, reason: r.reason.trim(), branchCode: r.branchCode || null })),
                { onSuccess: (a) => (toast(a, `${rows.length} holiday(s)`), onClose()) },
              );
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        {rows.map((r, i) => (
          <fieldset key={r.key} className="row" style={{ border: 0, padding: 0, margin: 0 }} aria-label={`Holiday ${i + 1}`}>
            <Input label={`Date ${i + 1}`} type="date" min={me.businessDate} value={r.day} onChange={(e) => update(r.key, { day: e.target.value })} error={touched ? errs[i].day : null} />
            <Input label={`Reason ${i + 1}`} value={r.reason} onChange={(e) => update(r.key, { reason: e.target.value })} error={touched ? errs[i].reason : null} />
            <Select
              label={`Branch ${i + 1}`}
              value={r.branchCode ?? ''}
              onChange={(e) => update(r.key, { branchCode: e.target.value })}
              options={[{ value: '', label: 'All branches' }, ...(branches.data ?? []).map((b) => ({ value: b.code, label: b.code }))]}
            />
            <Button size="sm" variant="ghost" aria-label={`Remove holiday ${i + 1}`} disabled={rows.length === 1} onClick={() => setRows((rs) => rs.filter((x) => x.key !== r.key))}>
              ✕
            </Button>
          </fieldset>
        ))}
        <div>
          <Button size="sm" onClick={() => setRows((rs) => [...rs, { key: Math.max(...rs.map((x) => x.key)) + 1, day: '', reason: '', branchCode: '' }])}>
            Add another
          </Button>
        </div>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
