import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useMe } from '../../api/hooks';
import {
  coLendingOffersFile,
  parseDecisionsCsv,
  useCoLendingArrangement,
  useCoLendingArrangements,
  useCoLendingOffers,
  useProposeCoLendingArrangement,
  useProposeCoLendingDecisions,
  useProposeCoLendingStatus,
  useSimulateCoLendingPartner,
} from '../../api/coLendingHooks';
import { useFileDownload } from '../../api/extraHooks';
import { useLoanProducts } from '../../api/lendingHooks';
import type { CoLendingArrangement, CoLendingArrangementInput, CoLendingOffer, CoLendingSharing, Loan } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, Checkbox, CsvUpload, DateText, Dialog, EmptyState, ErrorBanner, Input, MoneyText, PageHeader, Select, Spinner, StatusBadge, Table, Textarea, useToast } from '../../ui';
import { useProposalToast } from '../proposal';
import { Stat, pct } from './common';

/** What the RBI (Co-Lending Arrangements) Directions, 2025 require, in one line for the pages. */
export const CO_LENDING_HELP =
  'Under the RBI Co-Lending Arrangements Directions, 2025 each lender keeps at least 10% of every loan, the borrower pays the share-weighted blended rate, ' +
  'the partner must take its share into its books within 15 calendar days of disbursement (otherwise the loan stays with the originator), and a default loss guarantee is at most 5%.';

const SHARING_LABEL: Record<CoLendingSharing, string> = { ORIGINATOR: 'Kept by the originator', BY_SHARE: 'Shared in the ratio of the shares' };
const PARTNER_KIND = [
  { value: 'BANK', label: 'Bank' },
  { value: 'AIFI', label: 'All-India financial institution' },
  { value: 'NBFC', label: 'NBFC (incl. HFC)' },
];
const num = (v: string) => (v.trim() === '' ? NaN : Number(v));
const blended = (share: number, a: number, b: number) => Math.round(((share * a + (100 - share) * b) / 100) * 100) / 100;

/** List of arrangements, and a new one. */
export function CoLendingPage() {
  const me = useMe().data!;
  const list = useCoLendingArrangements();
  const [creating, setCreating] = useState(false);
  return (
    <div className="stack">
      <PageHeader
        title="Co-lending"
        subtitle={<>Arrangements in which this lender originates and services the loans. {CO_LENDING_HELP}</>}
        actions={hasPermission(me.permissions, P.productPropose) ? <Button variant="primary" onClick={() => setCreating(true)}>New arrangement…</Button> : undefined}
      />
      <Card flush>
        <ErrorBanner error={list.error} />
        {list.isLoading ? (
          <div style={{ padding: 16 }}><Spinner /></div>
        ) : (
          <Table
            caption="Co-lending arrangements"
            captionHidden
            columns={[
              { key: 'code', header: 'Code', render: (a) => <Link to={`/co-lending/${a.code}`} className="mono">{a.code}</Link> },
              { key: 'partner', header: 'Partner', render: (a) => (<span>{a.partnerName} <span className="muted">({a.partnerKind})</span></span>) },
              { key: 'split', header: 'Shares', render: (a) => `${pct(a.originatorShare)} : ${pct(a.partnerShare)}` },
              { key: 'rate', header: 'Blended rate', numeric: true, render: (a) => pct(a.blendedRate) },
              { key: 'products', header: 'Products', render: (a) => <span className="mono">{(a.productCodes ?? []).join(', ')}</span> },
              { key: 'offered', header: 'Offered', numeric: true, render: (a) => a.loans?.offered ?? 0 },
              { key: 'accepted', header: 'Accepted', numeric: true, render: (a) => a.loans?.accepted ?? 0 },
              { key: 'booked', header: 'Partner principal booked', numeric: true, render: (a) => <MoneyText value={a.partnerPrincipalBooked} /> },
              { key: 'status', header: 'Status', render: (a) => <StatusBadge status={a.status} /> },
            ]}
            rows={list.data ?? []}
            rowKey={(a) => a.code ?? ''}
            empty={<EmptyState title="No co-lending arrangement yet" />}
          />
        )}
      </Card>
      {creating && <ArrangementDialog onClose={() => setCreating(false)} />}
    </div>
  );
}

function ArrangementDialog({ current, onClose }: { current?: CoLendingArrangement; onClose: () => void }) {
  const products = useLoanProducts();
  const save = useProposeCoLendingArrangement();
  const toast = useProposalToast();
  const [f, setF] = useState({
    code: current?.code ?? '',
    name: current?.name ?? '',
    partnerName: current?.partnerName ?? '',
    partnerKind: current?.partnerKind ?? 'BANK',
    partnerReference: current?.partnerReference ?? '',
    agreementReference: current?.agreementReference ?? '',
    originatorShare: current?.originatorShare ?? '20',
    originatorRate: current?.originatorRate ?? '',
    partnerRate: current?.partnerRate ?? '',
    servicingFee: current?.servicingFee ?? '0',
    feeSharing: (current?.feeSharing ?? 'ORIGINATOR') as CoLendingSharing,
    penalSharing: (current?.penalSharing ?? 'BY_SHARE') as CoLendingSharing,
    dlgPercent: current?.dlgPercent ?? '0',
    contact: current?.contact ?? '',
  });
  const [chosen, setChosen] = useState<string[]>(current?.productCodes ?? []);
  const [touched, setTouched] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  const share = num(f.originatorShare);
  const or = num(f.originatorRate);
  const pr = num(f.partnerRate);
  const fee = num(f.servicingFee || '0');
  const dlg = num(f.dlgPercent || '0');
  const err = {
    code: !/^[A-Z][A-Z0-9_]{1,19}$/.test(f.code.trim().toUpperCase()) ? '2 to 20 capital letters, digits or _, starting with a letter' : null,
    name: !f.name.trim() ? 'Required' : null,
    partnerName: !f.partnerName.trim() ? 'Required' : null,
    agreementReference: !f.agreementReference.trim() ? 'Required' : null,
    contact: !f.contact.trim() ? 'Required' : null,
    originatorShare: !(share >= 10 && share <= 90) ? 'Each lender keeps at least 10%: 10 to 90' : null,
    originatorRate: !(or > 0 && or < 100) ? 'Above 0 and below 100' : null,
    partnerRate: !(pr > 0 && pr < 100) ? 'Above 0 and below 100' : null,
    servicingFee: !(fee >= 0) || (pr > 0 && fee >= pr) ? 'From 0, below the partner’s rate' : null,
    dlgPercent: !(dlg >= 0 && dlg <= 5) ? 'At most 5% of the outstanding' : null,
    products: chosen.length === 0 ? 'Choose at least one product' : null,
  };
  const valid = Object.values(err).every((x) => !x);
  const show = (k: keyof typeof err) => (touched ? err[k] : null);
  const eligible = (products.data ?? []).filter((p) => p.status === 'ACTIVE' && !p.multipleDisbursements && !p.benchmarkCode);

  const submit = () => {
    setTouched(true);
    if (!valid) return;
    const body: CoLendingArrangementInput = {
      code: f.code.trim().toUpperCase(),
      name: f.name.trim(),
      partnerName: f.partnerName.trim(),
      partnerKind: f.partnerKind as CoLendingArrangementInput['partnerKind'],
      partnerReference: f.partnerReference.trim() || null,
      agreementReference: f.agreementReference.trim(),
      originatorShare: f.originatorShare,
      originatorRate: f.originatorRate,
      partnerRate: f.partnerRate,
      servicingFee: f.servicingFee || '0',
      feeSharing: f.feeSharing,
      penalSharing: f.penalSharing,
      dlgPercent: f.dlgPercent || '0',
      productCodes: chosen,
      contact: f.contact.trim(),
    };
    save.mutate(body, { onSuccess: (a) => (toast(a, current ? 'Change to the arrangement' : 'New arrangement'), onClose()) });
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={current ? `Change arrangement ${current.code}` : 'New co-lending arrangement'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={save.isPending} onClick={submit}>Submit for approval</Button>
        </>
      }
    >
      <div className="stack">
        {current && <Banner tone="info"><span>A change applies to loans sanctioned after it is approved; loans already sanctioned keep their terms. The partner never changes.</span></Banner>}
        <div className="form-grid">
          <Input label="Code" required value={f.code} disabled={!!current} onChange={set('code')} error={show('code')} />
          <Input label="Name" required value={f.name} onChange={set('name')} error={show('name')} />
          <Input label="Partner lender" required value={f.partnerName} disabled={!!current} onChange={set('partnerName')} error={show('partnerName')} />
          <Select label="Partner is" value={f.partnerKind} disabled={!!current} onChange={set('partnerKind')} options={PARTNER_KIND} />
          <Input label="Partner’s reference" value={f.partnerReference} onChange={set('partnerReference')} hint="Optional" />
          <Input label="Agreement reference" required value={f.agreementReference} onChange={set('agreementReference')} error={show('agreementReference')} />
          <Input label="Originator’s share %" required numeric value={f.originatorShare} onChange={set('originatorShare')} error={show('originatorShare')} hint={share >= 10 && share <= 90 ? `Partner ${100 - share}%` : undefined} />
          <Input label="Originator’s rate %" required numeric value={f.originatorRate} onChange={set('originatorRate')} error={show('originatorRate')} />
          <Input label="Partner’s rate %" required numeric value={f.partnerRate} onChange={set('partnerRate')} error={show('partnerRate')} />
          <Input label="Servicing fee % a year" numeric value={f.servicingFee} onChange={set('servicingFee')} error={show('servicingFee')} hint="On the partner’s share, kept by the originator" />
          <Select label="Fees" value={f.feeSharing} onChange={set('feeSharing')} options={Object.entries(SHARING_LABEL).map(([value, label]) => ({ value, label }))} />
          <Select label="Penal charges" value={f.penalSharing} onChange={set('penalSharing')} options={Object.entries(SHARING_LABEL).map(([value, label]) => ({ value, label }))} />
          <Input label="Default loss guarantee %" numeric value={f.dlgPercent} onChange={set('dlgPercent')} error={show('dlgPercent')} hint="By the originator, at most 5%" />
          <Input label="Borrower’s single point of contact" required value={f.contact} onChange={set('contact')} error={show('contact')} hint="Shown in the KFS" />
        </div>
        <p style={{ margin: 0 }} data-testid="blended-rate">
          Blended rate the borrower pays:{' '}
          <strong>{share >= 10 && share <= 90 && or > 0 && pr > 0 ? `${blended(share, or, pr)}%` : '—'}</strong>
        </p>
        <fieldset className="stack" style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className="field__label">Products covered</legend>
          {products.isLoading ? <Spinner /> : eligible.length === 0 ? <span className="muted">No fixed-rate product disbursed at once</span> : eligible.map((p) => (
            <Checkbox key={p.code} label={`${p.code} · ${p.name}`} checked={chosen.includes(p.code!)} onChange={(e) => setChosen(e.target.checked ? [...chosen, p.code!] : chosen.filter((c) => c !== p.code))} />
          ))}
          <span className="muted" style={{ fontSize: 12 }}>Products disbursed in tranches or at a floating rate are not co-lent yet.</span>
          {show('products') && <span className="field__error">{err.products}</span>}
        </fieldset>
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}

function StatusDialog({ a, to, onClose }: { a: CoLendingArrangement; to: 'ACTIVE' | 'SUSPENDED' | 'CLOSED'; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const save = useProposeCoLendingStatus(a.code!);
  const toast = useProposalToast();
  const verb = { ACTIVE: 'Reactivate', SUSPENDED: 'Suspend', CLOSED: 'Close' }[to];
  return (
    <Dialog
      open
      onClose={onClose}
      title={`${verb} arrangement ${a.code}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant={to === 'CLOSED' ? 'danger' : 'primary'} loading={save.isPending} onClick={() => {
            setTouched(true);
            if (!reason.trim()) return;
            save.mutate({ status: to, reason: reason.trim() }, { onSuccess: (x) => (toast(x, `${verb} ${a.code}`), onClose()) });
          }}>Submit for approval</Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          {to === 'SUSPENDED' && 'No new loan is sanctioned under a suspended arrangement; loans already sanctioned or offered go on.'}
          {to === 'CLOSED' && 'A closed arrangement is final. It closes only when no loan under it is sanctioned or awaiting the partner.'}
          {to === 'ACTIVE' && 'New loans may again be sanctioned under the arrangement.'}
        </p>
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched && !reason.trim() ? 'A reason is required' : null} />
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}

function DecisionDialog({ code, offer, onClose }: { code: string; offer: CoLendingOffer; onClose: () => void }) {
  const [decision, setDecision] = useState<'ACCEPT' | 'DECLINE'>('ACCEPT');
  const [ref, setRef] = useState('');
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const save = useProposeCoLendingDecisions(code);
  const toast = useProposalToast();
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Partner’s decision on loan ${offer.loanNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={save.isPending} onClick={() => {
            setTouched(true);
            if (decision === 'DECLINE' && !reason.trim()) return;
            save.mutate([{ loanNo: offer.loanNo!, decision, partnerLoanRef: ref.trim() || null, reason: reason.trim() || null }], { onSuccess: (a) => (toast(a, 'Partner’s decision'), onClose()) });
          }}>Submit for approval</Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          On approval, an acceptance made by <DateText value={offer.deadline} /> books the partner’s {pct(offer.partnerShare)} of the principal then outstanding (now <MoneyText value={offer.partnerPrincipal} />).
        </p>
        <Select label="Decision" value={decision} onChange={(e) => setDecision(e.target.value as 'ACCEPT' | 'DECLINE')} options={[{ value: 'ACCEPT', label: 'Accepted by the partner' }, { value: 'DECLINE', label: 'Declined by the partner' }]} />
        <Input label="Partner’s reference" value={ref} maxLength={60} onChange={(e) => setRef(e.target.value)} hint="Optional" />
        <Textarea label="Partner’s reason" required={decision === 'DECLINE'} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched && decision === 'DECLINE' && !reason.trim() ? 'Required to decline' : null} />
        <ErrorBanner error={save.error} />
      </div>
    </Dialog>
  );
}

/** One arrangement: its terms, the loans by status, the offers open to the partner and its decisions. */
export function CoLendingDetailPage() {
  const { code } = useParams();
  const me = useMe().data!;
  const a = useCoLendingArrangement(code);
  const offers = useCoLendingOffers(code);
  const download = useFileDownload();
  const simulate = useSimulateCoLendingPartner(code ?? '');
  const decisions = useProposeCoLendingDecisions(code ?? '');
  const proposal = useProposalToast();
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [status, setStatus] = useState<'ACTIVE' | 'SUSPENDED' | 'CLOSED' | null>(null);
  const [deciding, setDeciding] = useState<CoLendingOffer | null>(null);
  if (a.isLoading) return <Spinner />;
  if (a.error || !a.data) return <ErrorBanner error={a.error} />;
  const x = a.data;
  const canPropose = hasPermission(me.permissions, P.productPropose) && x.status !== 'CLOSED';
  const canDecide = hasPermission(me.permissions, P.loanDisburse);
  const upload = {
    mutateAsync: async (text: string) => {
      const parsed = parseDecisionsCsv(text);
      if (parsed.problems.length) throw new Error(parsed.problems.slice(0, 5).join('; '));
      return decisions.mutateAsync(parsed.decisions);
    },
    isPending: decisions.isPending,
    error: decisions.error,
    reset: decisions.reset,
  };
  return (
    <div className="stack">
      <PageHeader
        title={<>Co-lending <span className="mono">{x.code}</span> <StatusBadge status={x.status} /></>}
        subtitle={<>{x.name} · with {x.partnerName} · agreement {x.agreementReference} · version {x.version} · <Link to="/co-lending">All arrangements</Link></>}
        actions={canPropose ? (
          <>
            <Button onClick={() => setEditing(true)}>Change terms…</Button>
            {x.status === 'ACTIVE' && <Button onClick={() => setStatus('SUSPENDED')}>Suspend…</Button>}
            {x.status === 'SUSPENDED' && <Button onClick={() => setStatus('ACTIVE')}>Reactivate…</Button>}
            <Button variant="danger" onClick={() => setStatus('CLOSED')}>Close…</Button>
          </>
        ) : undefined}
      />
      <Card title="Terms">
        <div className="stat-grid" data-testid="co-lending-terms">
          <Stat label="Shares (originator : partner)">{pct(x.originatorShare)} : {pct(x.partnerShare)}</Stat>
          <Stat label="Rates">{pct(x.originatorRate)} / {pct(x.partnerRate)}</Stat>
          <Stat label="Blended rate" testId="arrangement-blended">{pct(x.blendedRate)}</Stat>
          <Stat label="Servicing fee">{pct(x.servicingFee)} a year</Stat>
          <Stat label="Default loss guarantee">{pct(x.dlgPercent)}</Stat>
          <Stat label="Partner principal booked" testId="partner-booked"><MoneyText value={x.partnerPrincipalBooked} /></Stat>
        </div>
        <dl className="kv" style={{ marginTop: 16 }}>
          <dt>Products</dt>
          <dd className="mono">{(x.productCodes ?? []).join(', ')}</dd>
          <dt>Fees</dt>
          <dd>{SHARING_LABEL[x.feeSharing as CoLendingSharing] ?? x.feeSharing}</dd>
          <dt>Penal charges</dt>
          <dd>{SHARING_LABEL[x.penalSharing as CoLendingSharing] ?? x.penalSharing}</dd>
          <dt>Single point of contact</dt>
          <dd>{x.contact}</dd>
          <dt>Loans</dt>
          <dd data-testid="loan-counts">
            {Object.entries(x.loans ?? {}).length === 0 ? <span className="muted">None yet</span> : Object.entries(x.loans ?? {}).map(([k, n]) => (
              <span key={k} style={{ marginRight: 12 }}>{n} {k}</span>
            ))}
          </dd>
        </dl>
      </Card>
      <Card
        title="Offered to the partner"
        flush
        actions={
          <>
            <Button size="sm" loading={download.isPending} onClick={() => download.mutate(coLendingOffersFile(x.code!), { onSuccess: (name) => toast({ tone: 'success', message: `Saved ${name}.` }) })}>
              Download offer file
            </Button>
            {canDecide && (
              <Button size="sm" loading={simulate.isPending} disabled={!(offers.data ?? []).length} onClick={() => simulate.mutate(undefined, { onSuccess: (r) => proposal(r, 'Simulated partner acceptance') })}>
                Simulate partner
              </Button>
            )}
          </>
        }
      >
        <p className="muted" style={{ margin: 12 }}>
          A loan is offered when it is disbursed. The partner has 15 calendar days; an offer it has not accepted by then lapses at that day-end and the loan stays with this lender.
        </p>
        <ErrorBanner error={offers.error ?? simulate.error ?? download.error} />
        {offers.isLoading ? <div style={{ padding: 16 }}><Spinner /></div> : (
          <Table
            caption="Offers open to the partner"
            captionHidden
            columns={[
              { key: 'loan', header: 'Loan', render: (o) => <Link to={`/loans/${o.loanId}`} className="mono">{o.loanNo}</Link> },
              { key: 'cust', header: 'Customer', render: (o) => <span className="mono">{o.customerNo}</span> },
              { key: 'disb', header: 'Disbursed', render: (o) => <DateText value={o.disbursedOn} /> },
              { key: 'dl', header: 'Last day', render: (o) => <DateText value={o.deadline} /> },
              { key: 'po', header: 'Principal', numeric: true, render: (o) => <MoneyText value={o.principalOutstanding} /> },
              { key: 'pp', header: 'Partner’s share', numeric: true, render: (o) => <MoneyText value={o.partnerPrincipal} /> },
              { key: 'cls', header: 'Class', render: (o) => (o.assetClass && o.assetClass !== 'STANDARD' ? <Badge tone="warn">{o.assetClass}</Badge> : <span className="muted">Standard</span>) },
              { key: 'act', header: <span className="sr-only">Actions</span>, render: (o) => (canDecide ? <Button size="sm" variant="ghost" onClick={() => setDeciding(o)}>Record decision…</Button> : null) },
            ]}
            rows={offers.data ?? []}
            rowKey={(o) => o.loanNo ?? ''}
            empty={<EmptyState title="No loan is waiting for the partner" />}
          />
        )}
      </Card>
      {canDecide && (
        <Card title="Partner’s decisions from its file">
          <CsvUpload
            label="Decision file"
            columns={['loan_no', 'decision', 'partner_loan_ref', 'reason']}
            exampleRows={[['10010000000017', 'ACCEPT', 'PB-778812', ''], ['10010000000025', 'DECLINE', '', 'Outside the partner’s policy']]}
            templateName={`co-lending-decisions-${x.code}.csv`}
            maxRows={1000}
            hint="decision is ACCEPT or DECLINE; a decline needs a reason. The decisions go to a checker."
            mutation={upload}
            onUploaded={(r) => proposal(r, 'Partner’s decisions')}
          />
        </Card>
      )}
      {editing && <ArrangementDialog current={x} onClose={() => setEditing(false)} />}
      {status && <StatusDialog a={x} to={status} onClose={() => setStatus(null)} />}
      {deciding && <DecisionDialog code={x.code!} offer={deciding} onClose={() => setDeciding(null)} />}
    </div>
  );
}

const CO_LENT_STATUS: Record<string, string> = {
  SANCTIONED: 'Sanctioned under the arrangement; offered to the partner when disbursed',
  OFFERED: 'Offered to the partner',
  ACCEPTED: 'The partner holds its share',
  DECLINED: 'Declined by the partner: the loan stays with this lender',
  LAPSED: 'Not accepted within 15 days: the loan stays with this lender',
  WITHDRAWN: 'Withdrawn',
};

/** The co-lending card on the loan page. */
export function CoLendingCard({ loan }: { loan: Loan }) {
  const c = loan.coLending;
  if (!c) return null;
  return (
    <Card title="Co-lending">
      <div className="stat-grid" data-testid="loan-co-lending">
        <Stat label="Arrangement"><Link to={`/co-lending/${c.arrangementCode}`} className="mono">{c.arrangementCode}</Link> <span className="muted">v{c.arrangementVersion}</span></Stat>
        <Stat label="Partner">{c.partnerName}</Stat>
        <Stat label="Shares">{pct(c.originatorShare)} : {pct(c.partnerShare)}</Stat>
        <Stat label="Rates">{pct(c.originatorRate)} / {pct(c.partnerRate)} → {pct(c.blendedRate)}</Stat>
        <Stat label="Status" testId="co-lent-status"><StatusBadge status={c.status} /></Stat>
        {c.status === 'OFFERED' && <Stat label="Partner decides by"><DateText value={c.deadline} /></Stat>}
        {c.status === 'ACCEPTED' && (
          <>
            <Stat label="Partner’s principal"><MoneyText value={c.partnerPrincipal} /></Stat>
            <Stat label="This lender’s principal"><MoneyText value={c.originatorPrincipal} /></Stat>
          </>
        )}
      </div>
      <p className="muted" style={{ margin: '12px 0 0' }}>
        {CO_LENT_STATUS[c.status ?? ''] ?? c.status}
        {c.decidedOn ? <> on <DateText value={c.decidedOn} /></> : null}
        {c.decisionReason ? ` (${c.decisionReason})` : ''}
        {c.partnerLoanRef ? ` · partner’s reference ${c.partnerLoanRef}` : ''}
      </p>
    </Card>
  );
}
