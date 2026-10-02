import { useState } from 'react';
import { useBranches, useMe } from '../../api/hooks';
import { reportFile, useFileDownload, useReportRuns, useReports, useRunReport } from '../../api/extraHooks';
import { useLoanProducts } from '../../api/lendingHooks';
import type { ReportDefinition, ReportRun } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, Checkbox, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, StatusBadge, Table, humanize, useToast } from '../../ui';

const PAGE_SIZE = 20;

interface ParamDef {
  type?: string;
  format?: string;
  title?: string;
  description?: string;
  enum?: string[];
  default?: string;
}
interface ParamSchema {
  properties?: Record<string, ParamDef>;
  required?: string[];
}

/** The parameter JSON Schema of a report as form fields, in declaration order. */
export function paramFields(def: ReportDefinition): Array<{ key: string; def: ParamDef; required: boolean; label: string }> {
  const schema = (def.parameters ?? {}) as ParamSchema;
  return Object.entries(schema.properties ?? {}).map(([key, d]) => ({ key, def: d, required: (schema.required ?? []).includes(key), label: d.title ?? humanize(key.replace(/([a-z])([A-Z])/g, '$1_$2')) }));
}

export function ReportsPage() {
  const me = useMe().data!;
  const reports = useReports();
  const [page, setPage] = useState(0);
  const runs = useReportRuns(page, PAGE_SIZE);
  const [running, setRunning] = useState<ReportDefinition | null>(null);
  const [allUsers, setAllUsers] = useState(false);
  const isAdmin = hasPermission(me.permissions, P.reportAdmin);
  const download = useFileDownload();
  const toast = useToast();
  const names = new Map((reports.data ?? []).map((r) => [r.code, r.name]));
  // The API returns every user's runs to report:admin; the toggle narrows the list to the admin's own by default.
  const rows = (runs.data ?? []).filter((r) => !isAdmin || allUsers || r.requestedBy === me.userId);
  const save = (r: ReportRun, part?: 'rejections') => download.mutate(reportFile(r.id, part), { onSuccess: (name) => toast({ tone: 'success', message: `Saved ${name}.` }) });

  return (
    <div className="stack">
      <PageHeader title="Reports" subtitle="Run a report now, then download its file. Rows are limited to the branches you can see; every run and download is audited." />
      <Card title="Catalogue" flush>
        <ErrorBanner error={reports.error} />
        {reports.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Report catalogue"
            captionHidden
            columns={[
              { key: 'name', header: 'Report', render: (r) => (<span><strong>{r.name}</strong> <span className="mono muted">{r.code}</span></span>) },
              { key: 'desc', header: 'What it shows', render: (r) => r.description ?? '' },
              { key: 'format', header: 'Format', render: (r) => (<span className="row" style={{ gap: 4 }}><Badge>{r.outputFormat}</Badge>{r.containsPersonalData && <Badge tone="danger">Personal data</Badge>}{r.allBranchesOnly && <Badge tone="info">All branches</Badge>}</span>) },
              { key: 'run', header: <span className="sr-only">Run</span>, render: (r) => <Button size="sm" variant="primary" aria-label={`Run ${r.name}`} onClick={() => setRunning(r)}>Run…</Button> },
            ]}
            rows={reports.data ?? []}
            rowKey={(r) => r.code}
            empty={<EmptyState title="No reports available to you" />}
          />
        )}
      </Card>
      <Card
        title="Runs"
        flush
        actions={isAdmin && <Checkbox label="Show all users' runs" checked={allUsers} onChange={(e) => setAllUsers(e.target.checked)} />}
      >
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={runs.error ?? download.error} />
        </div>
        {runs.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Report runs"
            captionHidden
            columns={[
              { key: 'report', header: 'Report', render: (r) => names.get(r.reportCode) ?? r.reportCode },
              { key: 'params', header: 'Parameters', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{Object.entries(r.parameters ?? {}).map(([k, v]) => `${k}=${v}`).join(' ') || '—'}</span> },
              { key: 'by', header: 'Run by', render: (r) => (<span><span className="mono">{r.requestedBy}</span><br /><span className="muted" style={{ fontSize: 12 }}><DateTimeText value={r.requestedAt} /></span></span>) },
              { key: 'status', header: 'Status', render: (r) => (<span><StatusBadge status={r.status} />{r.error && <span className="field__error"> {r.error}</span>}</span>) },
              { key: 'rows', header: 'Rows', numeric: true, render: (r) => r.rowCount ?? '—' },
              {
                key: 'dl',
                header: <span className="sr-only">Download</span>,
                render: (r) =>
                  r.status === 'COMPLETED' ? (
                    <span className="row" style={{ gap: 4, justifyContent: 'flex-end' }}>
                      <Button size="sm" aria-label={`Download ${r.fileName ?? r.reportCode}`} loading={download.isPending && download.variables?.fallbackName === 'report.csv'} onClick={() => save(r)}>
                        Download
                      </Button>
                      {(r.rejectedCount ?? 0) > 0 && (
                        <Button size="sm" variant="ghost" aria-label={`Download rejections of ${r.fileName ?? r.reportCode}`} onClick={() => save(r, 'rejections')}>
                          Rejections ({r.rejectedCount})
                        </Button>
                      )}
                    </span>
                  ) : null,
              },
            ]}
            rows={rows}
            rowKey={(r) => r.id}
            empty={<EmptyState title="No runs yet">Run a report from the catalogue above.</EmptyState>}
          />
        )}
        <div className="row" style={{ padding: 12, justifyContent: 'flex-end' }}>
          <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <span className="muted">Page {page + 1}</span>
          <Button size="sm" disabled={(runs.data ?? []).length < PAGE_SIZE} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      </Card>
      {running && <RunDialog def={running} onClose={() => setRunning(null)} onDone={() => setPage(0)} />}
    </div>
  );
}

function RunDialog({ def, onClose, onDone }: { def: ReportDefinition; onClose: () => void; onDone: () => void }) {
  const me = useMe().data!;
  const fields = paramFields(def);
  const branches = useBranches();
  const products = useLoanProducts(fields.some((f) => /^product/i.test(f.key)));
  const run = useRunReport();
  const toast = useToast();
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(fields.map((f) => [f.key, f.def.default ?? (f.def.format === 'date' && f.required ? (/^from$/i.test(f.key) ? `${me.businessDate.slice(0, 8)}01` : me.businessDate) : '')])));
  const [touched, setTouched] = useState(false);
  const [acknowledged, setAcknowledged] = useState(!def.containsPersonalData);
  const errs = Object.fromEntries(fields.map((f) => [f.key, f.required && !values[f.key] ? `${f.label} is required` : f.def.format === 'date' && values[f.key] > me.businessDate ? 'Not after the business date' : null]));
  if (values.from && values.to && values.from > values.to) errs.from = 'From must be on or before To';
  const valid = Object.values(errs).every((e) => !e);
  const set = (k: string, v: string) => setValues((x) => ({ ...x, [k]: v }));
  const blocked = def.allBranchesOnly && !me.allBranches;
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Run ${def.name}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={run.isPending}
            disabled={!acknowledged || blocked}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              const parameters = Object.fromEntries(Object.entries(values).filter(([, v]) => v !== ''));
              run.mutate(
                { code: def.code, parameters },
                {
                  onSuccess: (r) => {
                    if (r.status === 'FAILED') toast({ tone: 'error', message: `${def.name} failed: ${r.error ?? 'unknown error'}` });
                    else toast({ tone: 'success', message: `${def.name} completed: ${r.rowCount ?? 0} row(s). Download it from the runs list.` });
                    onDone();
                    onClose();
                  },
                },
              );
            }}
          >
            Run report
          </Button>
        </>
      }
    >
      <div className="stack">
        {def.description && <p className="muted" style={{ margin: 0 }}>{def.description}</p>}
        {def.containsPersonalData && (
          <Banner tone="warn">
            <span>
              <strong>This file contains unmasked personal data</strong> (names, dates of birth, PAN, mobile numbers). Handle it as restricted data and share it only with the bureau.{' '}
              <strong>Its layout has not been certified by any bureau:</strong> validate it against each bureau’s format specification before the first submission.
            </span>
          </Banner>
        )}
        {blocked && <Banner tone="danger">This report covers every branch, so it needs all-branch access. Your scope is limited to {(me.branches ?? []).join(', ') || me.homeBranch}.</Banner>}
        {fields.length === 0 && <p style={{ margin: 0 }}>This report has no parameters.</p>}
        <div className="form-grid">
          {fields.map((f) => {
            const common = { label: f.label, required: f.required, hint: f.def.description, error: touched ? errs[f.key] : null };
            if (f.def.enum) return <Select key={f.key} {...common} value={values[f.key]} placeholder={f.required ? 'Select…' : 'All'} onChange={(e) => set(f.key, e.target.value)} options={f.def.enum.map((v) => ({ value: v, label: humanize(v) }))} />;
            if (f.key === 'branch') {
              return (
                <Select key={f.key} {...common} value={values[f.key]} placeholder="All my branches" onChange={(e) => set(f.key, e.target.value)}
                  options={(branches.data ?? []).filter((b) => me.allBranches || (me.branches ?? []).includes(b.code)).map((b) => ({ value: b.code, label: `${b.code} — ${b.name}` }))} />
              );
            }
            if (/^product/i.test(f.key)) {
              return <Select key={f.key} {...common} value={values[f.key]} placeholder="All products" onChange={(e) => set(f.key, e.target.value)} options={(products.data ?? []).map((p) => ({ value: p.code, label: `${p.code} — ${p.name}` }))} />;
            }
            if (f.def.format === 'date') return <Input key={f.key} {...common} type="date" max={me.businessDate} value={values[f.key]} onChange={(e) => set(f.key, e.target.value)} />;
            return <Input key={f.key} {...common} numeric={f.def.type === 'number' || f.def.type === 'integer'} value={values[f.key]} onChange={(e) => set(f.key, e.target.value)} />;
          })}
        </div>
        {def.containsPersonalData && <Checkbox label="I understand this file holds personal data and its layout must be validated with each bureau before first submission" checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)} />}
        <ErrorBanner error={run.error} />
      </div>
    </Dialog>
  );
}
