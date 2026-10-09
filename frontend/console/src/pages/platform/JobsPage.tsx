import { useState } from 'react';
import { useMe } from '../../api/hooks';
import { useJobRuns, useJobs, useProposeJobSchedule, useRunJob } from '../../api/platformHooks';
import type { Job, JobRun } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { describeCron, looksLikeCron } from '../../lib/cron';
import { Badge, Button, Card, Checkbox, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Select, Spinner, StatusBadge, Table, humanize, useToast } from '../../ui';
import { useProposalToast } from '../proposal';

const PERIODS = ['BUSINESS_DATE', 'PREVIOUS_DAY', 'MONTH_TO_DATE', 'PREVIOUS_MONTH'];
const PAGE = 20;

function CronText({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">Not scheduled</span>;
  const words = describeCron(value);
  return (
    <span>
      <span className="mono">{value}</span>
      <br />
      <span className="muted" style={{ fontSize: 12 }}>{words ?? 'Cron, IST'}</span>
    </span>
  );
}

/** How a scheduled report's file was delivered (ReportJobs on the backend); never names a recipient. */
export function deliveryNote(a: Record<string, unknown> | null | undefined): string {
  const left = Number(a?.recipientsLeftOut ?? 0);
  const out = left > 0 ? `; ${left} recipient(s) outside the internal domains left out` : '';
  switch (a?.delivery) {
    case 'SENT': return `Report e-mailed to ${String(a.recipientsSent ?? 0)} internal recipient(s)${out}`;
    case 'NOT_CONFIGURED': return 'Report produced; e-mail is not configured in this deployment';
    case 'NO_INTERNAL_RECIPIENT': return `Report produced; not e-mailed: no recipient on an internal domain${out}`;
    case 'NOT_EMAILED': return 'Report produced; a credit-bureau file is never e-mailed';
    case 'FAILED': return `Report produced; e-mail failed: ${String(a.deliveryError ?? '')}`;
    case 'PENDING_PROVIDER': return 'Report produced; e-mail delivery is waiting for the e-mail provider';
    default: return '';
  }
}

export function JobsPage() {
  const me = useMe().data!;
  const canRun = hasPermission(me.permissions, P.jobRun);
  const canSchedule = hasPermission(me.permissions, P.jobSchedule);
  const jobs = useJobs();
  const run = useRunJob();
  const toast = useToast();
  const [editing, setEditing] = useState<Job | null>(null);
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(0);
  const runs = useJobRuns(filter, page, PAGE);
  const nameOf = (code?: string) => jobs.data?.find((j) => j.code === code)?.name ?? code ?? '';
  return (
    <div className="stack">
      <PageHeader title="Scheduled jobs" subtitle="Background work that runs on a timetable. Schedules are six-field cron expressions read in Indian Standard Time; a schedule change needs approval." />
      <ErrorBanner error={jobs.error ?? run.error} />
      <Card flush>
        {jobs.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Scheduled jobs"
            captionHidden
            columns={[
              { key: 'name', header: 'Job', render: (j) => (<span><strong>{j.name}</strong><br /><span className="muted mono" style={{ fontSize: 12 }}>{j.code}</span></span>) },
              { key: 'kind', header: 'Kind', render: (j) => humanize(j.kind ?? '') },
              { key: 'cron', header: 'Schedule (IST)', render: (j) => <CronText value={j.schedule} /> },
              { key: 'on', header: 'State', render: (j) => (j.enabled ? <Badge tone="ok">Enabled</Badge> : <Badge>Disabled</Badge>) },
              { key: 'next', header: 'Next run', render: (j) => (j.nextRunAt ? <DateTimeText value={j.nextRunAt} /> : <span className="muted">—</span>) },
              { key: 'last', header: 'Last run', render: (j) => (j.lastRun ? (<span><StatusBadge status={j.lastRun.status} /> <DateTimeText value={j.lastRun.startedAt} /></span>) : <span className="muted">Never</span>) },
              ...(canRun || canSchedule
                ? [{
                    key: 'act',
                    header: <span className="sr-only">Actions</span>,
                    render: (j: Job) => (
                      <span className="row" style={{ gap: 6 }}>
                        {canRun && j.runnable !== false && (
                          <Button
                            size="sm"
                            aria-label={`Run ${j.name} now`}
                            loading={run.isPending && run.variables === j.code}
                            onClick={() => run.mutate(j.code!, { onSuccess: (r) => toast({ tone: r.status === 'FAILED' ? 'error' : 'success', message: `${j.name}: ${humanize(r.status ?? '').toLowerCase()}, ${r.processed ?? 0} processed${r.failed ? `, ${r.failed} failed` : ''}.` }) })}
                          >
                            Run now
                          </Button>
                        )}
                        {canSchedule && (
                          <Button size="sm" aria-label={`Change schedule of ${j.name}`} onClick={() => setEditing(j)}>
                            Schedule
                          </Button>
                        )}
                      </span>
                    ),
                  }]
                : []),
            ]}
            rows={jobs.data ?? []}
            rowKey={(j) => j.code ?? ''}
            empty={<EmptyState title="No jobs" />}
          />
        )}
      </Card>
      <Card title="Runs" flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Select
            label="Job"
            value={filter}
            onChange={(e) => {
              setFilter(e.target.value);
              setPage(0);
            }}
            options={[{ value: '', label: 'All jobs' }, ...(jobs.data ?? []).map((j) => ({ value: j.code ?? '', label: j.name ?? j.code ?? '' }))]}
          />
        </div>
        <ErrorBanner error={runs.error} />
        {runs.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Job runs"
            captionHidden
            columns={[
              { key: 'job', header: 'Job', render: (r: JobRun) => nameOf(r.jobCode) },
              { key: 'started', header: 'Started', render: (r) => <DateTimeText value={r.startedAt} /> },
              { key: 'origin', header: 'Started by', render: (r) => (r.origin === 'MANUAL' ? <span>Manual · <span className="mono">{r.requestedBy}</span></span> : 'Schedule') },
              { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
              { key: 'processed', header: 'Processed', numeric: true, render: (r) => r.processed ?? 0 },
              { key: 'failed', header: 'Failed', numeric: true, render: (r) => r.failed ?? 0 },
              { key: 'note', header: 'Result', render: (r) => r.error ?? deliveryNote(r.artifact as Record<string, unknown> | null | undefined) },
            ]}
            rows={runs.data ?? []}
            rowKey={(r) => r.id ?? ''}
            empty={<EmptyState title="No runs yet" />}
            footer={
              <div className="row" style={{ gap: 8, padding: 8 }}>
                <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
                <span className="muted">Page {page + 1}</span>
                <Button size="sm" disabled={(runs.data?.length ?? 0) < PAGE} onClick={() => setPage(page + 1)}>Next</Button>
              </div>
            }
          />
        )}
      </Card>
      {editing && <ScheduleDialog job={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ScheduleDialog({ job, onClose }: { job: Job; onClose: () => void }) {
  const propose = useProposeJobSchedule();
  const toast = useProposalToast();
  const params = (job.parameters ?? {}) as Record<string, unknown>;
  const [schedule, setSchedule] = useState(job.schedule ?? '');
  const [enabled, setEnabled] = useState(!!job.enabled);
  const [period, setPeriod] = useState(String(params.period ?? 'BUSINESS_DATE'));
  const [emailTo, setEmailTo] = useState(String(params.emailTo ?? ''));
  const [touched, setTouched] = useState(false);
  const isReport = job.kind === 'REPORT';
  const cron = schedule.trim();
  const error = cron && !looksLikeCron(cron) ? 'Six fields: second minute hour day-of-month month day-of-week' : enabled && !cron ? 'An enabled job needs a schedule' : null;
  const words = describeCron(cron);
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Schedule of ${job.name}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (error) return;
              propose.mutate(
                { code: job.code!, input: { schedule: cron || null, enabled, ...(isReport ? { parameters: { ...params, period, emailTo: emailTo.trim() } } : {}) } },
                { onSuccess: (a) => (toast(a, 'Job schedule change'), onClose()) },
              );
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <Input
          label="Schedule (cron, IST)"
          className="mono"
          value={schedule}
          onChange={(e) => setSchedule(e.target.value)}
          hint={words ?? 'second minute hour day-of-month month day-of-week, e.g. 0 0 6 1 * * = 06:00 on the 1st'}
          error={touched ? error : null}
        />
        <Checkbox label="Enabled" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        {isReport && (
          <div className="form-grid">
            <Select label="Report period" value={period} onChange={(e) => setPeriod(e.target.value)} options={PERIODS.map((p) => ({ value: p, label: humanize(p) }))} />
            <Input label="E-mail to" type="email" value={emailTo} onChange={(e) => setEmailTo(e.target.value)} hint="Delivery waits for the e-mail provider" />
          </div>
        )}
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
