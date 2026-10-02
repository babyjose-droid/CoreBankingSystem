import { useState } from 'react';
import { Link } from 'react-router';
import { useCustomerExposure, useCustomerRelationships, useProposeExposureLimit, useProposeRelationships } from '../../api/extraHooks';
import { useCustomers, useMe } from '../../api/hooks';
import type { CustomerSummary, RelationType } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { isMoney } from '../../lib/money';
import { Badge, Button, Card, Dialog, EmptyState, ErrorBanner, Input, MoneyText, Select, Spinner, StatusBadge, Table, Textarea } from '../../ui';
import { useProposalToast } from '../proposal';

export const RELATION_LABEL: Record<RelationType, string> = {
  CO_APPLICANT: 'Co-applicant',
  GUARANTOR: 'Guarantor',
  NOMINEE: 'Nominee',
  AUTHORISED_SIGNATORY: 'Authorised signatory',
};

export function RelationshipsTab({ customer }: { customer: CustomerSummary }) {
  const me = useMe().data!;
  const can = (p: string) => hasPermission(me.permissions, p);
  const exposure = useCustomerExposure(customer.id);
  const rels = useCustomerRelationships(customer.id);
  const [settingLimit, setSettingLimit] = useState(false);
  const [adding, setAdding] = useState(false);
  const e = exposure.data;
  return (
    <div className="stack">
      <Card title="Exposure" actions={can(P.limitPropose) && e && <Button onClick={() => setSettingLimit(true)}>Set exposure limit</Button>}>
        <ErrorBanner error={exposure.error} />
        {exposure.isLoading ? (
          <Spinner />
        ) : (
          e && (
            <>
              <div className="stat-grid" data-testid="exposure">
                <div className="stat">
                  <div className="stat__label">As borrower</div>
                  <div className="stat__value"><MoneyText value={e.asBorrower} /></div>
                  <div className="stat__sub">{e.loansAsBorrower ?? 0} loan(s)</div>
                </div>
                <div className="stat">
                  <div className="stat__label">As co-applicant</div>
                  <div className="stat__value"><MoneyText value={e.asCoApplicant} /></div>
                  <div className="stat__sub">{e.loansAsCoApplicant ?? 0} loan(s)</div>
                </div>
                <div className="stat">
                  <div className="stat__label">As guarantor</div>
                  <div className="stat__value"><MoneyText value={e.asGuarantor} /></div>
                  <div className="stat__sub">{e.loansAsGuarantor ?? 0} loan(s)</div>
                </div>
                <div className="stat">
                  <div className="stat__label">Exposure limit</div>
                  <div className="stat__value">{e.exposureLimit ? <MoneyText value={e.exposureLimit} /> : <span className="muted">No limit</span>}</div>
                </div>
                <div className="stat">
                  <div className="stat__label">Headroom</div>
                  <div className="stat__value">{e.available !== null && e.available !== undefined ? <MoneyText value={e.available} /> : <span className="muted">—</span>}</div>
                </div>
              </div>
              <p className="muted" style={{ margin: '8px 0 0', fontSize: 12 }}>
                The limit applies to the customer’s own loans (sanctioned amount until disbursed, principal outstanding afterwards). Exposure as co-applicant or guarantor is shown for information.
              </p>
            </>
          )
        )}
      </Card>
      <Card title="Relationships" flush actions={can(P.customerCreate) && <Button variant="primary" onClick={() => setAdding(true)}>Add relationships</Button>}>
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={rels.error} />
        </div>
        {rels.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Relationships"
            captionHidden
            columns={[
              { key: 'type', header: 'Relationship', render: (r) => RELATION_LABEL[r.relationType] },
              { key: 'dir', header: 'Direction', render: (r) => (r.direction === 'OUTGOING' ? <Badge>Of this customer</Badge> : <Badge tone="info">This customer is theirs</Badge>) },
              { key: 'other', header: 'Customer', render: (r) => (<span><Link to={`/customers/${r.relatedCustomerId}`}>{r.relatedCustomerName ?? 'Outside your branches'}</Link> <span className="mono muted">{r.relatedCustomerNo}</span></span>) },
              { key: 'share', header: 'Share %', numeric: true, render: (r) => r.sharePercent ?? '' },
              { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status === 'ENDED' ? 'CLOSED' : r.status} /> },
            ]}
            rows={rels.data ?? []}
            rowKey={(r) => `${r.id}-${r.direction}`}
            empty={<EmptyState title="No relationships" />}
          />
        )}
      </Card>
      {settingLimit && e && <ExposureLimitDialog customer={customer} current={e.exposureLimit ?? null} onClose={() => setSettingLimit(false)} />}
      {adding && <AddRelationshipsDialog customer={customer} onClose={() => setAdding(false)} />}
    </div>
  );
}

function ExposureLimitDialog({ customer, current, onClose }: { customer: CustomerSummary; current: string | null; onClose: () => void }) {
  const propose = useProposeExposureLimit(customer.id);
  const toast = useProposalToast();
  const [limit, setLimit] = useState(current ?? '');
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const clean = limit.replace(/[,\s₹]/g, '');
  const errs = {
    limit: clean && (!isMoney(clean) || Number(clean) <= 0) ? 'Enter a positive amount, or leave empty to remove the limit' : !clean && !current ? 'Enter the limit' : null,
    reason: !reason.trim() ? 'A reason is required' : null,
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Exposure limit for ${customer.displayName}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (errs.limit || errs.reason) return;
              propose.mutate({ exposureLimit: clean || null, reason: reason.trim() }, { onSuccess: (a) => (toast(a, clean ? 'Exposure limit' : 'Removal of the exposure limit'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>Checked when a loan is booked and again at disbursement. Loans already booked are not affected.</p>
        <Input label="Exposure limit" numeric value={limit} onChange={(e) => setLimit(e.target.value)} hint={current ? 'Leave empty to remove the limit' : undefined} error={touched ? errs.limit : null} />
        <Textarea label="Reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} error={touched ? errs.reason : null} />
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}

interface Draft {
  customer: CustomerSummary;
  relationType: RelationType;
  sharePercent: string;
}

function AddRelationshipsDialog({ customer, onClose }: { customer: CustomerSummary; onClose: () => void }) {
  const propose = useProposeRelationships(customer.id);
  const toast = useProposalToast();
  const [search, setSearch] = useState('');
  const [q, setQ] = useState<string | null>(null);
  const res = useCustomers(q ?? '', 0, 10, q !== null);
  const [type, setType] = useState<RelationType>('GUARANTOR');
  const [list, setList] = useState<Draft[]>([]);
  const [touched, setTouched] = useState(false);
  const nominees = list.filter((x) => x.relationType === 'NOMINEE');
  const total = nominees.reduce((s, x) => s + Number(x.sharePercent || 0), 0);
  const error =
    list.length === 0
      ? 'Add at least one relationship'
      : nominees.some((x) => !(Number(x.sharePercent) > 0))
        ? 'Every nominee needs a share above 0'
        : nominees.length && Math.abs(total - 100) > 0.001
          ? `Nominee shares must total 100 (they total ${total})`
          : null;
  const types = (Object.keys(RELATION_LABEL) as RelationType[]).filter((t) => t !== 'AUTHORISED_SIGNATORY' || customer.customerType === 'NON_INDIVIDUAL');
  return (
    <Dialog
      open
      wide
      onClose={onClose}
      title={`Add relationships of ${customer.displayName}`}
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
                list.map((x) => ({ relatedCustomerId: x.customer.id, relationType: x.relationType, ...(x.relationType === 'NOMINEE' ? { sharePercent: x.sharePercent } : {}) })),
                { onSuccess: (a) => (toast(a, `${list.length} relationship(s)`), onClose()) },
              );
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>The related party must be an active customer. Nominees are sent as the complete set: their shares must total 100 and replace the current nominees on approval.</p>
        <form
          role="search"
          className="filters"
          style={{ marginBottom: 0 }}
          onSubmit={(e) => {
            e.preventDefault();
            setQ(search.trim());
          }}
        >
          <Select label="Relationship" value={type} onChange={(e) => setType(e.target.value as RelationType)} options={types.map((t) => ({ value: t, label: RELATION_LABEL[t] }))} />
          <Input label="Find the related customer" placeholder="Customer no. / PAN / mobile" value={search} onChange={(e) => setSearch(e.target.value)} fieldClassName="grow" />
          <Button type="submit">Search</Button>
        </form>
        {q !== null &&
          (res.isLoading ? (
            <Spinner />
          ) : (
            <Table
              caption="Customers found"
              captionHidden
              columns={[
                { key: 'no', header: 'Customer no.', render: (c) => <span className="mono">{c.customerNo}</span> },
                { key: 'name', header: 'Name', render: (c) => c.displayName },
                {
                  key: 'add',
                  header: <span className="sr-only">Add</span>,
                  render: (c) =>
                    c.id === customer.id ? (
                      <span className="muted">This customer</span>
                    ) : list.some((x) => x.customer.id === c.id && x.relationType === type) ? (
                      <span className="muted">Added</span>
                    ) : (
                      <Button size="sm" aria-label={`Add ${c.displayName} as ${RELATION_LABEL[type].toLowerCase()}`} onClick={() => setList([...list, { customer: c, relationType: type, sharePercent: type === 'NOMINEE' && nominees.length === 0 ? '100' : '' }])}>
                        Add as {RELATION_LABEL[type].toLowerCase()}
                      </Button>
                    ),
                },
              ]}
              rows={res.data ?? []}
              rowKey={(c) => c.id}
              empty={<EmptyState title="No customer found" />}
            />
          ))}
        {list.length > 0 && (
          <ul className="doc-list" aria-label="Relationships to propose">
            {list.map((x, i) => (
              <li key={`${x.customer.id}-${x.relationType}`}>
                <span>
                  <strong>{RELATION_LABEL[x.relationType]}</strong>: {x.customer.displayName} <span className="mono">{x.customer.customerNo}</span>
                </span>
                <span className="row" style={{ alignItems: 'flex-end' }}>
                  {x.relationType === 'NOMINEE' && (
                    <Input label={`Share % of ${x.customer.displayName}`} numeric style={{ width: 90 }} value={x.sharePercent} onChange={(e) => setList(list.map((y, j) => (j === i ? { ...y, sharePercent: e.target.value } : y)))} />
                  )}
                  <Button size="sm" variant="ghost" aria-label={`Remove ${x.customer.displayName} (${RELATION_LABEL[x.relationType]})`} onClick={() => setList(list.filter((_, j) => j !== i))}>
                    Remove
                  </Button>
                </span>
              </li>
            ))}
          </ul>
        )}
        {touched && error && (
          <span className="field__error" role="alert">
            {error}
          </span>
        )}
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
