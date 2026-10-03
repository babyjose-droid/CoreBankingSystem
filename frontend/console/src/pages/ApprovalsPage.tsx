import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useApproval, useApprovals, useApprove, useBulkApprove, useMe, useReject } from '../api/hooks';
import { errorMessage } from '../api/errors';
import type { Approval, ApprovalStatus } from '../api/types';
import { P, hasPermission } from '../auth/permissions';
import { canActOn } from '../layout/usePendingForMe';
import { formatINR, isMoney } from '../lib/money';
import {
  Badge,
  Banner,
  Button,
  Card,
  DateTimeText,
  Dialog,
  EmptyState,
  ErrorBanner,
  MoneyText,
  PageHeader,
  Select,
  Spinner,
  StatusBadge,
  Table,
  Textarea,
  humanize,
  useToast,
  type Column,
} from '../ui';

const STATUSES: ApprovalStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'];
const ENTITY_TYPES = [
  'CUSTOMER',
  'BRANCH',
  'GL_HEAD',
  'VOUCHER',
  'TAX_RATE',
  'HOLIDAY',
  'EOD_SCHEDULE',
  'LOAN_PRODUCT',
  'LOAN_DISBURSEMENT',
  'LOAN_WAIVER',
  'LOAN_REVERSAL',
  'LOAN_AMENDMENT',
  'LOAN_RESTRUCTURE',
  'LOAN_SANCTION_CHANGE',
  'LOAN_NPA_OVERRIDE',
  'STAFF',
  'BRANCH_SET',
  'SYSTEM_PROPERTY',
  'ENUMERATION',
  'TERRITORY',
  'AMOUNT_LIMIT',
  'CUSTOMER_RELATIONSHIP',
  'EXPOSURE_LIMIT',
  'CUSTOM_FIELD',
  'LOAN_PRODUCT_CUSTOM',
  'JOB',
];

export function slaClass(a: Pick<Approval, 'status' | 'ageHours'>): string | undefined {
  if (a.status !== 'PENDING' || a.ageHours === undefined) return undefined;
  if (a.ageHours > 48) return 'sla-red';
  if (a.ageHours > 24) return 'sla-amber';
  return undefined;
}

/**
 * "1 of 2 approvals" for a pending request that needs several checkers. Uses checkersRequired / approvalsSoFar;
 * falls back to the older note-based message when a backend does not send them.
 */
export function approvalProgress(a: Pick<Approval, 'status' | 'checkersRequired' | 'approvalsSoFar' | 'note'>): string | null {
  if (a.status !== 'PENDING') return null;
  if (a.checkersRequired !== undefined) return a.checkersRequired > 1 ? `${a.approvalsSoFar ?? 0} of ${a.checkersRequired} approvals` : null;
  return a.note && /needs a second checker/i.test(a.note) ? '1 of 2 approvals' : null;
}

function ProgressBadge({ a }: { a: Approval }) {
  const text = approvalProgress(a);
  return text ? (
    <Badge tone="info" title="Each approval must come from a different checker">
      {text}
    </Badge>
  ) : null;
}

export function formatAge(hours: number | undefined): string {
  if (hours === undefined) return '—';
  if (hours < 1) return '<1h';
  const d = Math.floor(hours / 24);
  const h = Math.floor(hours % 24);
  return d > 0 ? `${d}d ${h}h` : `${h}h`;
}

function summaryOf(a: Approval): string {
  const p = (a.proposed ?? {}) as Record<string, unknown>;
  const c = (a.current ?? {}) as Record<string, unknown>;
  if (a.entityType === 'CUSTOMER') return [p.firstName, p.lastName].filter(Boolean).join(' ');
  if (a.entityType === 'VOUCHER') return String(p.description ?? p.voucherNo ?? c.voucherNo ?? '');
  if (a.entityType === 'HOLIDAY') {
    const hs = (p.holidays as Array<{ day: string; reason: string }> | undefined) ?? [];
    return hs.map((h) => `${h.day} ${h.reason}`).join(', ');
  }
  if (a.entityType === 'EOD_SCHEDULE') return `Mode ${String(p.mode ?? '')}`;
  if (a.entityType.startsWith('LOAN_') && p.loanNo) return [p.loanNo, p.customer ?? p.charge ?? p.transaction ?? p.kind ?? p.reason].filter(Boolean).join(' — ');
  if (a.entityType === 'AMOUNT_LIMIT') return `${String(p.roleName ?? '')} · ${humanize(String(p.txnType ?? ''))}`;
  if (a.entityType === 'CUSTOMER_RELATIONSHIP' || a.entityType === 'EXPOSURE_LIMIT') return [p.customerNo, p.name].filter(Boolean).join(' — ');
  if (a.entityType === 'STAFF') return [p.username, p.displayName].filter(Boolean).join(' — ');
  if (a.entityType === 'SYSTEM_PROPERTY') return `${String(p.key ?? '')} = ${String(p.value ?? '')}`;
  if (a.entityType === 'ENUMERATION') return `${String(p.type ?? '')} (${((p.values as unknown[] | undefined) ?? []).length} value(s))`;
  if (a.entityType === 'TERRITORY') return `${String(p.rows ?? '')} row(s) · ${String(p.states ?? '')}`;
  return [p.code, p.name].filter(Boolean).join(' — ');
}

export function ApprovalsPage() {
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') ?? 'PENDING') as ApprovalStatus | '';
  const entityType = params.get('entityType') ?? '';
  const openId = params.get('id');
  const me = useMe().data!;
  const canApprove = hasPermission(me.permissions, P.approvalApprove);
  const q = useApprovals({ status: status || undefined, entityType: entityType || undefined });
  const bulk = useBulkApprove();
  const toast = useToast();
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const setParam = (k: string, v: string | null) => {
    const next = new URLSearchParams(params);
    if (v === null || v === '') next.delete(k);
    else next.set(k, v);
    if (k === 'status' && v === '') next.set('status', '');
    setParams(next);
  };

  const rows = q.data ?? [];
  const columns: Column<Approval>[] = useMemo(
    () => [
      { key: 'type', header: 'Type', render: (a) => humanize(a.entityType) },
      { key: 'action', header: 'Action', render: (a) => humanize(a.action) },
      { key: 'summary', header: 'Summary', render: (a) => <span>{summaryOf(a) || <span className="muted">—</span>}</span> },
      { key: 'amount', header: 'Amount', numeric: true, render: (a) => (a.amount ? <MoneyText value={a.amount} /> : '') },
      { key: 'maker', header: 'Maker', render: (a) => <span className="mono">{a.maker}</span> },
      { key: 'madeAt', header: 'Made at', render: (a) => <DateTimeText value={a.madeAt} /> },
      {
        key: 'age',
        header: 'Age',
        numeric: true,
        render: (a) => (
          <span className={slaClass(a)} title={slaClass(a) === 'sla-red' ? 'Over 48h SLA' : slaClass(a) === 'sla-amber' ? 'Over 24h' : undefined}>
            {formatAge(a.ageHours)}
          </span>
        ),
      },
      { key: 'status', header: 'Status', render: (a) => (<span className="row" style={{ gap: 4 }}><StatusBadge status={a.status} /><ProgressBadge a={a} /></span>) },
    ],
    [],
  );

  const actionableSelected = [...selected].filter((id) => rows.some((r) => r.id === id && canActOn(r, me)));

  return (
    <div className="stack">
      <PageHeader
        title="Approvals"
        subtitle="Maker-checker queue. Changes are applied only when a different user approves them."
        actions={
          canApprove && (
            <Button
              variant="primary"
              disabled={actionableSelected.length === 0}
              loading={bulk.isPending}
              onClick={() =>
                bulk.mutate(
                  { ids: actionableSelected },
                  {
                    onSuccess: (res) => {
                      const okCount = res.filter((r) => r.ok).length;
                      const failed = res.filter((r) => !r.ok);
                      toast({
                        tone: failed.length ? 'error' : 'success',
                        message: `Approved ${okCount} of ${res.length}.` + (failed.length ? ` Failed: ${failed.map((f) => f.error).join('; ')}` : ''),
                      });
                      setSelected(new Set());
                    },
                    onError: (e) => toast({ tone: 'error', message: errorMessage(e) }),
                  },
                )
              }
            >
              Approve selected ({actionableSelected.length})
            </Button>
          )
        }
      />
      <Card flush>
        <div style={{ padding: 12 }}>
          <div className="filters">
            <Select
              label="Status"
              value={status}
              onChange={(e) => setParam('status', e.target.value)}
              options={[{ value: '', label: 'All' }, ...STATUSES.map((s) => ({ value: s, label: humanize(s) }))]}
            />
            <Select
              label="Entity type"
              value={entityType}
              onChange={(e) => setParam('entityType', e.target.value)}
              options={[{ value: '', label: 'All' }, ...ENTITY_TYPES.map((s) => ({ value: s, label: humanize(s) }))]}
            />
            <span className="muted" style={{ paddingBottom: 6 }}>
              Ageing: <span className="sla-amber">&gt;24h</span> · <span className="sla-red">&gt;48h</span>
            </span>
          </div>
          <ErrorBanner error={q.error} />
        </div>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Approval requests"
            captionHidden
            columns={columns}
            rows={rows}
            rowKey={(a) => a.id}
            onRowClick={(a) => setParam('id', a.id)}
            rowLabel={(a) => `Open ${humanize(a.entityType)} ${humanize(a.action)} request by ${a.maker}`}
            empty={<EmptyState title="No approval requests match these filters" />}
            selection={
              canApprove
                ? {
                    selected,
                    onChange: setSelected,
                    idOf: (a) => a.id,
                    isSelectable: (a) => canActOn(a, me),
                    label: (a) => `Select ${humanize(a.entityType)} request by ${a.maker}`,
                  }
                : undefined
            }
          />
        )}
      </Card>
      <ApprovalDrawer id={openId} onClose={() => setParam('id', null)} />
    </div>
  );
}

function renderValue(v: unknown): React.ReactNode {
  if (v === null || v === undefined || v === '') return <span className="muted">—</span>;
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  if (Array.isArray(v)) {
    if (v.length === 0) return <span className="muted">none</span>;
    return (
      <ul style={{ margin: 0, paddingLeft: 16 }}>
        {v.map((x, i) => (
          <li key={i} className="mono" style={{ fontSize: 12 }}>
            {typeof x === 'object' && x !== null
              ? Object.entries(x as Record<string, unknown>)
                  .filter(([, val]) => val !== null && val !== undefined && val !== '')
                  .map(([k, val]) => (k === 'amount' && typeof val === 'string' && isMoney(val) ? formatINR(val) : typeof val === 'object' ? JSON.stringify(val) : String(val)))
                  .join(' · ')
              : String(x)}
          </li>
        ))}
      </ul>
    );
  }
  if (typeof v === 'object') return <code>{JSON.stringify(v)}</code>;
  return String(v);
}

export function diffRows(current: Record<string, unknown> | null | undefined, proposed: Record<string, unknown> | undefined) {
  const keys = Array.from(new Set([...Object.keys(current ?? {}), ...Object.keys(proposed ?? {})]));
  return keys.map((k) => {
    const before = current ? current[k] : undefined;
    const after = proposed ? proposed[k] : undefined;
    return { key: k, before, after, changed: JSON.stringify(before ?? null) !== JSON.stringify(after ?? null) };
  });
}

function ApprovalDrawer({ id, onClose }: { id: string | null; onClose: () => void }) {
  const q = useApproval(id);
  const me = useMe().data!;
  const approve = useApprove();
  const reject = useReject();
  const toast = useToast();
  const [note, setNote] = useState('');
  const [noteError, setNoteError] = useState<string | null>(null);
  const a = q.data;
  const own = !!a && a.maker === me.userId;
  const canApprove = hasPermission(me.permissions, P.approvalApprove);
  const pending = a?.status === 'PENDING';

  const close = () => {
    setNote('');
    setNoteError(null);
    approve.reset();
    reject.reset();
    onClose();
  };

  const decisionDisabledReason = !a
    ? null
    : !pending
      ? `This request is already ${humanize(a.status).toLowerCase()}.`
      : !canApprove
        ? 'You do not have the approval:approve permission.'
        : own
          ? 'You made this request. Maker-checker requires a different user to approve it.'
          : null;

  return (
    <Dialog
      open={!!id}
      onClose={close}
      variant="drawer"
      title={a ? `${humanize(a.entityType)} · ${humanize(a.action)}` : 'Approval'}
      footer={
        a &&
        pending &&
        canApprove && (
          <>
            <Button
              variant="danger"
              disabled={own}
              loading={reject.isPending}
              aria-describedby={own ? 'own-request-reason' : undefined}
              onClick={() => {
                if (!note.trim()) {
                  setNoteError('A note is required to reject');
                  return;
                }
                reject.mutate(
                  { id: a.id, note: note.trim() },
                  {
                    onSuccess: () => {
                      toast({ tone: 'success', message: 'Request rejected.' });
                      close();
                    },
                  },
                );
              }}
            >
              Reject
            </Button>
            <Button
              variant="primary"
              disabled={own}
              loading={approve.isPending}
              aria-describedby={own ? 'own-request-reason' : undefined}
              onClick={() =>
                approve.mutate(
                  { id: a.id, note: note.trim() || undefined },
                  {
                    onSuccess: () => {
                      toast({ tone: 'success', message: 'Approved and applied.' });
                      close();
                    },
                  },
                )
              }
            >
              Approve
            </Button>
          </>
        )
      }
    >
      {q.isLoading && <Spinner />}
      <ErrorBanner error={q.error} />
      {a && (
        <div className="stack">
          <dl className="kv">
            <dt>Status</dt>
            <dd>
              <StatusBadge status={a.status} /> <ProgressBadge a={a} />
            </dd>
            {(a.checkersRequired ?? 1) > 1 && (
              <>
                <dt>Checkers</dt>
                <dd data-testid="approval-checkers">
                  {a.checkersRequired} different checkers required
                  {pending ? `; ${a.approvalsSoFar ?? 0} recorded so far. The change applies with the last approval.` : '.'}
                </dd>
              </>
            )}
            {a.appliedRef && (
              <>
                <dt>Applied as</dt>
                <dd className="mono">{a.appliedRef}</dd>
              </>
            )}
            <dt>Maker</dt>
            <dd className="mono">{a.maker}</dd>
            <dt>Made at</dt>
            <dd>
              <DateTimeText value={a.madeAt} /> <span className={slaClass(a)}>({formatAge(a.ageHours)})</span>
            </dd>
            {a.entityId && (
              <>
                <dt>Entity</dt>
                <dd className="mono">{a.entityId}</dd>
              </>
            )}
            {a.amount && (
              <>
                <dt>Amount</dt>
                <dd>
                  <MoneyText value={a.amount} />
                </dd>
              </>
            )}
            {a.checker && (
              <>
                <dt>Checker</dt>
                <dd className="mono">
                  {a.checker} · <DateTimeText value={a.checkedAt} />
                </dd>
              </>
            )}
            {a.note && (
              <>
                <dt>Note</dt>
                <dd>{a.note}</dd>
              </>
            )}
          </dl>

          <div className="table-wrap">
            <table className="diff">
              <caption className="sr-only">Current versus proposed values; changed fields are highlighted</caption>
              <thead>
                <tr>
                  <th scope="col">Field</th>
                  <th scope="col">Current</th>
                  <th scope="col">Proposed</th>
                </tr>
              </thead>
              <tbody>
                {diffRows(a.current, a.proposed).map((r) => (
                  <tr key={r.key} className={r.changed ? 'is-changed' : undefined} data-changed={r.changed || undefined}>
                    <th scope="row">
                      {humanize(r.key.replace(/([a-z])([A-Z])/g, '$1_$2'))}
                      {r.changed && <span className="sr-only"> (changed)</span>}
                    </th>
                    <td className="diff__old">{a.current ? renderValue(r.before) : <span className="muted">new</span>}</td>
                    <td className="diff__new">{renderValue(r.after)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {decisionDisabledReason && (
            <Banner tone={own ? 'warn' : 'info'}>
              <span id="own-request-reason">{decisionDisabledReason}</span>
            </Banner>
          )}
          {pending && canApprove && !own && (
            <Textarea
              label="Note"
              hint="Optional for approval; required to reject."
              value={note}
              maxLength={500}
              error={noteError}
              onChange={(e) => {
                setNote(e.target.value);
                if (e.target.value.trim()) setNoteError(null);
              }}
            />
          )}
          <ErrorBanner error={approve.error ?? reject.error} />
        </div>
      )}
    </Dialog>
  );
}
