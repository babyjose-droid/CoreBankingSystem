import { useState } from 'react';
import { useMe } from '../../api/hooks';
import {
  coLendingClassificationFile,
  parsePartnerClassCsv,
  useCoLendingDlg,
  useProposeDlgCover,
  useProposeDlgInvocation,
  useProposeDlgRecovery,
  useProposePartnerClasses,
  useReleaseDlgCover,
} from '../../api/coLendingHooks';
import { useFileDownload } from '../../api/extraHooks';
import type { CoLendingDlg } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, CsvUpload, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, Select, Spinner, Table, Textarea, useToast } from '../../ui';
import { useProposalToast } from '../proposal';
import { Stat, pct } from './common';

type Cover = NonNullable<CoLendingDlg['covers']>[number];
type Invocation = NonNullable<CoLendingDlg['invocations']>[number];
type InDefault = NonNullable<CoLendingDlg['inDefault']>[number];

const FORM_LABEL: Record<string, string> = { CASH: 'Cash deposit', FD_LIEN: 'Fixed deposit under lien', BANK_GUARANTEE: 'Bank guarantee' };
const money = (v: string) => /^\d+(\.\d{1,2})?$/.test(v.trim());

/** P5-3: the default loss guarantee the originator gives the partner (Directions para 32; Digital Lending Directions). */
export function DlgCard({ code }: { code: string }) {
  const me = useMe().data!;
  const dlg = useCoLendingDlg(code);
  const can = hasPermission(me.permissions, P.loanDisburse);
  const [lodging, setLodging] = useState(false);
  const [releasing, setReleasing] = useState<Cover | null>(null);
  const [invoking, setInvoking] = useState<InDefault | null>(null);
  const [recovering, setRecovering] = useState<Invocation | null>(null);
  const d = dlg.data;
  return (
    <Card title="Default loss guarantee" flush actions={can && d && Number(d.headroom ?? 0) > 0 ? <Button size="sm" onClick={() => setLodging(true)}>Lodge cover…</Button> : undefined}>
      <p className="muted" style={{ margin: 12 }}>
        Given by this lender to the partner, in cash, a fixed deposit under lien or a bank guarantee; all covers together at most the arrangement’s percentage of the
        partner’s share funded, each lasting as long as the longest loan it covers. The partner invokes it for a loan in default, within 120 days overdue; it is not set off
        against the loan.
      </p>
      <ErrorBanner error={dlg.error} />
      {dlg.isLoading || !d ? <div style={{ padding: 16 }}><Spinner /></div> : (
        <div className="stack">
          <div className="stat-grid" style={{ padding: '0 12px' }} data-testid="dlg-figures">
            <Stat label="Cap">
              <MoneyText value={d.cap} />
              <div className="muted" style={{ fontSize: 12, fontWeight: 400 }}>{pct(d.dlgPercent)} of <MoneyText value={d.partnerFunded} /></div>
            </Stat>
            <Stat label="Covered" testId="dlg-covered"><MoneyText value={d.covered} /></Stat>
            <Stat label="Headroom"><MoneyText value={d.headroom} /></Stat>
            <Stat label="Invoked"><MoneyText value={d.invoked} /></Stat>
            <Stat label="Still available"><MoneyText value={d.available} /></Stat>
          </div>
          <Table
            caption="Covers"
            captionHidden
            columns={[
              { key: 'no', header: 'Cover', render: (c) => <span className="mono">{c.coverNo}</span> },
              { key: 'f', header: 'Form', render: (c) => (<span>{FORM_LABEL[c.form ?? ''] ?? c.form}{c.instrumentRef ? <span className="muted"> {c.instrumentRef}</span> : null}</span>) },
              { key: 'a', header: 'Amount', numeric: true, render: (c) => <MoneyText value={c.amount} /> },
              { key: 'i', header: 'Invoked', numeric: true, render: (c) => <MoneyText value={c.invoked} /> },
              { key: 'v', header: 'Valid until', render: (c) => (<span><DateText value={c.validUntil} />{c.lastsLongEnough === false && <> <Badge tone="warn">Shorter than a loan</Badge></>}</span>) },
              { key: 's', header: 'Status', render: (c) => (c.status === 'RELEASED' ? <Badge tone="neutral">Released</Badge> : <Badge tone="ok">Active</Badge>) },
              { key: 'x', header: <span className="sr-only">Actions</span>, render: (c) => (can && c.status === 'ACTIVE' ? <Button size="sm" variant="ghost" onClick={() => setReleasing(c)}>Release…</Button> : null) },
            ]}
            rows={d.covers ?? []}
            rowKey={(c) => c.id ?? ''}
            empty={<EmptyState title="No cover lodged" />}
          />
          <h3 style={{ margin: '0 12px' }}>Loans in default</h3>
          <Table
            caption="Loans in default"
            captionHidden
            columns={[
              { key: 'l', header: 'Loan', render: (r) => <span className="mono">{r.loanNo}</span> },
              { key: 'd', header: 'Days past due', numeric: true, render: (r) => r.dpd },
              { key: 'c', header: 'Class', render: (r) => r.assetClass },
              { key: 'p', header: 'Partner’s principal', numeric: true, render: (r) => <MoneyText value={r.partnerPrincipal} /> },
              { key: 'b', header: 'Invoke by', render: (r) => <DateText value={r.invokeBy} /> },
              { key: 'x', header: <span className="sr-only">Actions</span>, render: (r) => (can && Number(d.available ?? 0) > 0 ? <Button size="sm" variant="ghost" onClick={() => setInvoking(r)}>Record invocation…</Button> : null) },
            ]}
            rows={d.inDefault ?? []}
            rowKey={(r) => r.loanNo ?? ''}
            empty={<EmptyState title="No loan in default" />}
          />
          {(d.invocations ?? []).length > 0 && (
            <Table
              caption="Invocations"
              columns={[
                { key: 'd', header: 'Date', render: (r) => <DateText value={r.businessDate} /> },
                { key: 'l', header: 'Loan', render: (r) => <span className="mono">{r.loanNo}</span> },
                { key: 'c', header: 'Cover', render: (r) => <span className="mono">{r.coverNo}</span> },
                { key: 'a', header: 'Paid', numeric: true, render: (r) => <MoneyText value={r.amount} /> },
                { key: 'r', header: 'Recovered', numeric: true, render: (r) => <MoneyText value={r.recovered} /> },
                { key: 'x', header: <span className="sr-only">Actions</span>, render: (r) => (can && Number(r.recovered) < Number(r.amount) ? <Button size="sm" variant="ghost" onClick={() => setRecovering(r)}>Record recovery…</Button> : null) },
              ]}
              rows={d.invocations ?? []}
              rowKey={(r) => r.id ?? ''}
            />
          )}
        </div>
      )}
      {lodging && d && <CoverDialog code={code} d={d} onClose={() => setLodging(false)} />}
      {releasing && <ReleaseDialog cover={releasing} onClose={() => setReleasing(null)} />}
      {invoking && d && <InvokeDialog code={code} loan={invoking} covers={(d.covers ?? []).filter((c) => c.status === 'ACTIVE' && Number(c.available) > 0)} onClose={() => setInvoking(null)} />}
      {recovering && <RecoverDialog inv={recovering} onClose={() => setRecovering(null)} />}
    </Card>
  );
}

function CoverDialog({ code, d, onClose }: { code: string; d: CoLendingDlg; onClose: () => void }) {
  const save = useProposeDlgCover(code);
  const toast = useProposalToast();
  const [f, setF] = useState({ form: 'CASH', amount: '', instrumentRef: '', validUntil: d.longestLoanDue ?? '' });
  const [touched, setTouched] = useState(false);
  const err = {
    amount: !money(f.amount) || Number(f.amount) <= 0 ? 'An amount above zero' : Number(f.amount) > Number(d.headroom) ? `At most the headroom of ${d.headroom}` : null,
    instrumentRef: f.form !== 'CASH' && !f.instrumentRef.trim() ? 'Its number is required' : null,
    validUntil: !f.validUntil ? 'Required' : d.longestLoanDue && f.validUntil < d.longestLoanDue ? 'At least to the last due date of the loans it covers' : null,
  };
  return (
    <Dialog open onClose={onClose} title={`Lodge a cover for ${code}`} footer={<>
      <Button onClick={onClose}>Cancel</Button>
      <Button variant="primary" loading={save.isPending} onClick={() => {
        setTouched(true);
        if (Object.values(err).some(Boolean)) return;
        save.mutate({ form: f.form as 'CASH', amount: f.amount, instrumentRef: f.instrumentRef.trim() || null, validUntil: f.validUntil }, { onSuccess: (a) => (toast(a, 'Guarantee cover'), onClose()) });
      }}>Submit for approval</Button>
    </>}>
      <div className="stack">
        <Select label="Form" value={f.form} onChange={(e) => setF({ ...f, form: e.target.value })} options={Object.entries(FORM_LABEL).map(([value, label]) => ({ value, label }))}
          hint={f.form === 'CASH' ? 'Paid from the bank to a deposit with the partner' : 'Not on the books until invoked'} />
        <Input label="Amount" numeric required value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} hint={`Headroom ${d.headroom}`} error={touched ? err.amount : null} />
        {f.form !== 'CASH' && <Input label="Fixed deposit or guarantee number" required maxLength={60} value={f.instrumentRef} onChange={(e) => setF({ ...f, instrumentRef: e.target.value })} error={touched ? err.instrumentRef : null} />}
        <Input label="Valid until" type="date" required value={f.validUntil} onChange={(e) => setF({ ...f, validUntil: e.target.value })} error={touched ? err.validUntil : null} />
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}

function ReleaseDialog({ cover, onClose }: { cover: Cover; onClose: () => void }) {
  const save = useReleaseDlgCover();
  const toast = useProposalToast();
  const [reason, setReason] = useState('');
  return (
    <Dialog open onClose={onClose} title={`Release cover ${cover.coverNo}`} footer={<>
      <Button onClick={onClose}>Cancel</Button>
      <Button variant="primary" loading={save.isPending} disabled={!reason.trim()} onClick={() => save.mutate({ id: cover.id!, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Release'), onClose()) })}>Submit for approval</Button>
    </>}>
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>{cover.form === 'CASH' ? <>The <MoneyText value={cover.available} /> not invoked comes back to the bank.</> : 'The instrument is returned; nothing is posted.'}</p>
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}

function InvokeDialog({ code, loan, covers, onClose }: { code: string; loan: InDefault; covers: Cover[]; onClose: () => void }) {
  const save = useProposeDlgInvocation(code);
  const toast = useProposalToast();
  const [coverId, setCoverId] = useState(covers[0]?.id ?? '');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const ok = coverId && money(amount) && Number(amount) > 0 && reason.trim();
  return (
    <Dialog open onClose={onClose} title={`The partner invokes the guarantee for loan ${loan.loanNo}`} footer={<>
      <Button onClick={onClose}>Cancel</Button>
      <Button variant="primary" loading={save.isPending} disabled={!ok} onClick={() => save.mutate({ loanNo: loan.loanNo!, coverId, amount, reason: reason.trim() }, { onSuccess: (a) => (toast(a, 'Invocation'), onClose()) })}>Submit for approval</Button>
    </>}>
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>{loan.dpd} days past due; the partner’s principal is <MoneyText value={loan.partnerPrincipal} />. The amount is this lender’s loss; the loan is not reduced.</p>
        <Select label="Cover" value={coverId} onChange={(e) => setCoverId(e.target.value)} options={covers.map((c) => ({ value: c.id!, label: `${c.coverNo} · ${FORM_LABEL[c.form ?? '']} · ${c.available} left` }))} />
        <Input label="Amount" numeric required value={amount} onChange={(e) => setAmount(e.target.value)} />
        <Textarea label="The partner’s claim" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}

function RecoverDialog({ inv, onClose }: { inv: Invocation; onClose: () => void }) {
  const save = useProposeDlgRecovery();
  const toast = useProposalToast();
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  return (
    <Dialog open onClose={onClose} title={`Recovery on loan ${inv.loanNo}`} footer={<>
      <Button onClick={onClose}>Cancel</Button>
      <Button variant="primary" loading={save.isPending} disabled={!(money(amount) && Number(amount) > 0)} onClick={() => save.mutate({ id: inv.id!, amount, reference: reference.trim() || null }, { onSuccess: (a) => (toast(a, 'Recovery'), onClose()) })}>Submit for approval</Button>
    </>}>
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>What the partner shares back of a recovery on the loan; at most <MoneyText value={String(Number(inv.amount) - Number(inv.recovered))} />.</p>
        <Input label="Amount" numeric required value={amount} onChange={(e) => setAmount(e.target.value)} />
        <Input label="Reference" maxLength={100} value={reference} onChange={(e) => setReference(e.target.value)} />
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}

/** P5-3: classification at borrower level with the partner (Directions para 33). */
export function ClassificationCard({ code }: { code: string }) {
  const me = useMe().data!;
  const download = useFileDownload();
  const record = useProposePartnerClasses(code);
  const proposal = useProposalToast();
  const toast = useToast();
  const upload = {
    mutateAsync: async (text: string) => {
      const parsed = parsePartnerClassCsv(text);
      if (parsed.problems.length) throw new Error(parsed.problems.slice(0, 5).join('; '));
      return record.mutateAsync(parsed.rows);
    },
    isPending: record.isPending,
    error: record.error,
    reset: record.reset,
  };
  return (
    <Card title="Classification with the partner" actions={
      <Button size="sm" loading={download.isPending} onClick={() => download.mutate(coLendingClassificationFile(code), { onSuccess: (name) => toast({ tone: 'success', message: `Saved ${name}.` }) })}>
        Download our classification
      </Button>
    }>
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Classification is at borrower level across both lenders and is shared by the end of the next working day. Send the partner this lender’s file; record the partner’s.
          An NPA at the partner holds the loan at that class here (by an override, for a year) and the borrower’s other loans follow at day-end.
        </p>
        {hasPermission(me.permissions, P.loanClassify) && (
          <CsvUpload
            label="Partner’s classification file"
            columns={['loan_no', 'asset_class', 'npa_since', 'partner_dpd']}
            exampleRows={[['10010000000017', 'SUBSTANDARD', '2026-06-20', '95'], ['10010000000025', 'SMA1', '', '40']]}
            templateName={`co-lending-partner-classification-${code}.csv`}
            maxRows={5000}
            hint="asset_class STANDARD, SMA0–2, SUBSTANDARD, DOUBTFUL1–3 or LOSS; an NPA needs its npa_since. Recorded through a checker."
            mutation={upload}
            onUploaded={(r) => proposal(r, 'Partner’s classification')}
          />
        )}
        <ErrorBanner error={download.error} />
      </div>
    </Card>
  );
}
