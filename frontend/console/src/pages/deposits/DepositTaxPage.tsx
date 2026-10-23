import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useProposeTaxDeclaration, useRevokeTaxDeclaration, useTaxDeclarations, useTaxSummary } from '../../api/depositHooks';
import { useMe } from '../../api/hooks';
import type { TaxDeclaration } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, Textarea } from '../../ui';
import { CustomerPicker, Problems } from './common';
import { useMoneyToast } from './DepositMoney';
import { InterestCertificateButton } from './DepositDocuments';

/** Tax deducted at source on deposit interest (P4-3b): a depositor's tax year and their Form 121 declarations. */

const BASIS: Record<string, string> = {
  EXEMPT: 'Exempt',
  DECLARATION: 'Form 121 on file',
  BELOW_THRESHOLD: 'Within the threshold',
  RATE: 'At the rate',
  NO_PAN_RATE: 'Higher rate: no PAN',
};
const QUARTER = ['April to June', 'July to September', 'October to December', 'January to March'];
const yearLabel = (y: number) => `${y}-${String(y + 1).slice(2)}`;

export function DepositTaxPage() {
  const [params, setParams] = useSearchParams();
  const customerId = params.get('customer') ?? undefined;
  const [year, setYear] = useState<number | null>(null);
  const summary = useTaxSummary(customerId, year);
  const s = summary.data;
  const current = s?.taxYear ?? null;
  return (
    <div className="stack">
      <PageHeader title="Tax at source" subtitle="Tax deducted from a depositor's interest across all their deposits, and their Form 121 declarations." />
      <Card title="Depositor">
        <CustomerPicker label="Depositor" exclude={[]} onPick={(c) => (setYear(null), setParams({ customer: c.id! }))} />
      </Card>
      {customerId && <ErrorBanner error={summary.error} />}
      {customerId && summary.isLoading && <Spinner />}
      {s && (
        <>
          <Card title={`${s.name} · tax year ${s.taxYearLabel}`}>
            <div className="row" style={{ gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
              <Select
                label="Tax year"
                value={String(s.taxYear)}
                onChange={(e) => setYear(Number(e.target.value))}
                options={[(current ?? 0) + 1, current ?? 0, (current ?? 0) - 1].map((y) => ({ value: String(y), label: yearLabel(y) }))}
              />
            </div>
            <div className="row" style={{ gap: 8, marginBottom: 12 }}>
              <InterestCertificateButton customerId={customerId!} taxYear={s.taxYear!} />
            </div>
            <dl className="kv">
              <dt>Interest credited</dt>
              <dd data-testid="tax-interest"><MoneyText value={s.interest} /></dd>
              <dt>Tax deducted</dt>
              <dd data-testid="tax-deducted"><MoneyText value={s.tax} /></dd>
              <dt>Threshold</dt>
              <dd><MoneyText value={s.threshold} />{s.senior && <span className="muted"> (senior citizen)</span>}</dd>
              <dt>Rate</dt>
              <dd>{s.ratePercent}%{!s.panOnFile && <span className="muted"> (no PAN on file)</span>}</dd>
              <dt>Form 121</dt>
              <dd data-testid="tax-declaration">{s.declarationId ? <Badge tone="ok">On file</Badge> : 'None'}</dd>
            </dl>
            {!s.panOnFile && <Banner tone="warn">No PAN is on file: tax is deducted at the higher rate, and Form 121 cannot be recorded.</Banner>}
          </Card>
          <Card title="By quarter" flush>
            <Table
              caption="Tax by quarter"
              captionHidden
              columns={[
                { key: 'q', header: 'Quarter', render: (q) => QUARTER[(q.quarter ?? 1) - 1] },
                { key: 'i', header: 'Interest', numeric: true, render: (q) => <MoneyText value={q.interest} /> },
                { key: 't', header: 'Tax', numeric: true, render: (q) => <MoneyText value={q.tax} /> },
              ]}
              rows={s.quarters ?? []}
              rowKey={(q) => String(q.quarter)}
            />
          </Card>
          <Card title="Credits" flush>
            {(s.credits ?? []).length === 0 ? (
              <EmptyState title="No interest credited in this tax year yet" />
            ) : (
              <Table
                caption="Credits and tax"
                captionHidden
                columns={[
                  { key: 'd', header: 'Business date', render: (e) => <DateText value={e.businessDate} /> },
                  { key: 'a', header: 'Account', render: (e) => <span className="mono">{e.accountNo}</span> },
                  { key: 'i', header: 'Interest', numeric: true, render: (e) => <MoneyText value={e.interest} /> },
                  { key: 't', header: 'Tax', numeric: true, render: (e) => <MoneyText value={e.tax} /> },
                  { key: 'b', header: 'Why', render: (e) => `${BASIS[e.basis ?? ''] ?? e.basis}${e.ratePercent ? ` (${e.ratePercent}%)` : ''}` },
                ]}
                rows={s.credits ?? []}
                rowKey={(e) => String(e.id)}
              />
            )}
          </Card>
          <Declarations customerId={customerId!} currentYear={s.taxYear!} panOnFile={!!s.panOnFile} />
        </>
      )}
    </div>
  );
}

function Declarations({ customerId, currentYear, panOnFile }: { customerId: string; currentYear: number; panOnFile: boolean }) {
  const me = useMe().data!;
  const canPropose = hasPermission(me.permissions, P.depositTax);
  const q = useTaxDeclarations(customerId);
  const [recording, setRecording] = useState(false);
  const [revoking, setRevoking] = useState<TaxDeclaration | null>(null);
  return (
    <Card title="Form 121 declarations" flush actions={canPropose && panOnFile ? <Button onClick={() => setRecording(true)}>Record Form 121</Button> : undefined}>
      {q.isLoading ? (
        <Spinner />
      ) : (q.data ?? []).length === 0 ? (
        <EmptyState title="No declaration recorded" />
      ) : (
        <Table
          caption="Form 121 declarations"
          captionHidden
          columns={[
            { key: 'y', header: 'Tax year', render: (d) => yearLabel(d.taxYear ?? 0) },
            { key: 'r', header: 'Received', render: (d) => <span><DateText value={d.receivedOn} />{d.reference && <span className="muted"> · {d.reference}</span>}</span> },
            { key: 's', header: 'Status', render: (d) => (d.status === 'ACTIVE' ? <StatusBadge status="ACTIVE" /> : <span>Revoked <DateText value={d.revokedOn} /><span className="muted"> · {d.revokeReason}</span></span>) },
            {
              key: 'act',
              header: <span className="sr-only">Actions</span>,
              render: (d) =>
                canPropose && d.status === 'ACTIVE' ? (
                  <Button size="sm" aria-label={`Revoke the declaration for ${yearLabel(d.taxYear ?? 0)}`} onClick={() => setRevoking(d)}>
                    Revoke
                  </Button>
                ) : null,
            },
          ]}
          rows={q.data ?? []}
          rowKey={(d) => d.id!}
        />
      )}
      {recording && <RecordDialog customerId={customerId} currentYear={currentYear} onClose={() => setRecording(false)} />}
      {revoking && <RevokeDialog declaration={revoking} onClose={() => setRevoking(null)} />}
    </Card>
  );
}

function RecordDialog({ customerId, currentYear, onClose }: { customerId: string; currentYear: number; onClose: () => void }) {
  const [year, setYear] = useState(String(currentYear));
  const [receivedOn, setReceivedOn] = useState('');
  const [reference, setReference] = useState('');
  const m = useProposeTaxDeclaration();
  const toast = useMoneyToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title="Record Form 121"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={m.isPending}
            onClick={() => m.mutate({ customerId, taxYear: Number(year), receivedOn: receivedOn || null, reference: reference.trim() || null }, { onSuccess: (a) => (toast(a, 'Form 121'), onClose()) })}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>Form 121 replaced Forms 15G and 15H from 1 April 2026. Once a checker approves it, no tax is deducted from the depositor's interest for the year.</p>
      <Select label="Tax year" value={year} onChange={(e) => setYear(e.target.value)} options={[currentYear, currentYear + 1].map((y) => ({ value: String(y), label: yearLabel(y) }))} />
      <Input label="Received on" type="date" hint="The business date if left empty" value={receivedOn} onChange={(e) => setReceivedOn(e.target.value)} />
      <Input label="Acknowledgement or receipt number" maxLength={60} value={reference} onChange={(e) => setReference(e.target.value)} />
      <Problems error={m.error} title="The declaration cannot be recorded:" />
    </Dialog>
  );
}

function RevokeDialog({ declaration, onClose }: { declaration: TaxDeclaration; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const m = useRevokeTaxDeclaration();
  const toast = useMoneyToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Revoke Form 121 for ${yearLabel(declaration.taxYear ?? 0)}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="danger" loading={m.isPending} disabled={!reason.trim()} onClick={() => m.mutate({ id: declaration.id!, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Revocation'), onClose()) })}>
            Submit for approval
          </Button>
        </>
      }
    >
      <Banner tone="warn">From the next interest credit, tax is deducted for the whole year so far.</Banner>
      <Textarea label="Reason" required rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <Problems error={m.error} title="The declaration cannot be revoked:" />
    </Dialog>
  );
}
