import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useCustomers, useMe } from '../../api/hooks';
import { useCreateLoan, useLoanProducts, usePreviewLoan } from '../../api/lendingHooks';
import type { CustomerSummary, LoanApplication, LoanPartyInput, LoanProduct } from '../../api/types';
import { formatINR } from '../../lib/money';
import { Banner, Button, Card, EmptyState, ErrorBanner, Input, Masked, PageHeader, Select, Spinner, StatusBadge, Table, useToast } from '../../ui';
import { KfsView } from './KfsView';
import { FREQUENCY_LABEL, moneyInput } from './common';

interface Form {
  productCode: string;
  amount: string;
  tenorMonths: string;
  rate: string;
  disbursalDate: string;
  firstDueDate: string;
  moratoriumMonths: string;
  balloon: string;
  externalRef: string;
  instalment: string;
  maturityAmount: string;
  plan: Array<{ dueDate: string; principal: string }>;
}

/** The rate may be left out: interest table, benchmark + spread, or it follows from an instalment / maturity amount. */
const rateFromElsewhere = (p: LoanProduct, f: Form) => !!p.interestTableCode || !!p.benchmarkCode || !!f.instalment.trim() || !!f.maturityAmount.trim();

function problems(f: Form, product: LoanProduct | undefined, customer: CustomerSummary | null) {
  const amount = moneyInput(f.amount);
  const tenor = /^\d+$/.test(f.tenorMonths) ? Number(f.tenorMonths) : NaN;
  const e: Record<string, string | null> = {
    customer: !customer ? 'Select a customer' : null,
    productCode: !product ? 'Select a product' : null,
    amount: !amount || Number(amount) <= 0 ? 'Enter an amount' : null,
    tenorMonths: product?.repaymentMethod === 'STRUCTURED' ? null : Number.isNaN(tenor) || tenor < 1 ? 'Whole number of periods' : null,
    rate: null,
    moratoriumMonths: f.moratoriumMonths && !/^\d+$/.test(f.moratoriumMonths) ? 'Whole months' : null,
    balloon: f.balloon && !moneyInput(f.balloon) ? 'Enter an amount' : null,
  };
  if (product) {
    if (amount && (Number(amount) < Number(product.minAmount) || Number(amount) > Number(product.maxAmount))) {
      e.amount = `Between ${formatINR(String(product.minAmount))} and ${formatINR(String(product.maxAmount))}`;
    }
    if (product.repaymentMethod !== 'STRUCTURED' && !Number.isNaN(tenor) && (tenor < product.minTenorMonths || tenor > product.maxTenorMonths)) e.tenorMonths = `${product.minTenorMonths} to ${product.maxTenorMonths} months`;
    if (f.rate.trim() || !rateFromElsewhere(product, f)) {
      const r = Number(f.rate);
      if (!/^\d+(\.\d+)?$/.test(f.rate.trim())) e.rate = 'Enter the rate';
      else if (f.instalment.trim() || f.maturityAmount.trim()) e.rate = 'Give the rate or the instalment / maturity amount, not both';
      else if (r < Number(product.minRate) || r > Number(product.maxRate)) e.rate = `${product.minRate}% to ${product.maxRate}%`;
    }
    e.instalment = f.instalment && !moneyInput(f.instalment) ? 'Enter an amount' : null;
    e.maturityAmount = f.maturityAmount && !moneyInput(f.maturityAmount) ? 'Enter an amount' : null;
    if (product.repaymentMethod === 'STRUCTURED') {
      const total = f.plan.reduce((x, r) => x + Number(moneyInput(r.principal || '0') ?? NaN), 0);
      e.plan =
        f.plan.length === 0
          ? 'Add the principal plan'
          : f.plan.some((r) => !r.dueDate || moneyInput(r.principal || '0') === null)
            ? 'Every row needs a date and a principal (0 for an interest-only date)'
            : f.plan.some((r, i) => i > 0 && r.dueDate <= f.plan[i - 1].dueDate)
              ? 'The dates must be in ascending order'
              : amount && Math.abs(total - Number(amount)) > 0.001
                ? `The principal must add up to the amount (it adds up to ${total})`
                : null;
    }
    if (f.moratoriumMonths && Number(f.moratoriumMonths) > (product.maxMoratoriumMonths ?? 0)) e.moratoriumMonths = `At most ${product.maxMoratoriumMonths ?? 0}`;
  }
  return e;
}

export function NewLoanPage() {
  const me = useMe().data!;
  const navigate = useNavigate();
  const toast = useToast();
  const products = useLoanProducts();
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const customers = useCustomers(q, 0, 10);
  const [customer, setCustomer] = useState<CustomerSummary | null>(null);
  const [f, setF] = useState<Form>({ productCode: '', amount: '', tenorMonths: '', rate: '', disbursalDate: me.businessDate, firstDueDate: '', moratoriumMonths: '', balloon: '', externalRef: '', instalment: '', maturityAmount: '', plan: [] });
  const [touched, setTouched] = useState(false);
  const [parties, setParties] = useState<Array<{ customer: CustomerSummary; role: LoanPartyInput['role'] }>>([]);
  const preview = usePreviewLoan();
  const create = useCreateLoan();
  const active = (products.data ?? []).filter((p) => (p.status ?? 'ACTIVE') === 'ACTIVE');
  const product = active.find((p) => p.code === f.productCode);
  const errs = problems(f, product, customer);
  const valid = Object.values(errs).every((x) => !x);

  const app: LoanApplication | null = useMemo(() => {
    if (!customer || !product) return null;
    return {
      productCode: product.code,
      customerId: customer.id,
      amount: moneyInput(f.amount) ?? f.amount,
      tenorMonths: product.repaymentMethod === 'STRUCTURED' ? f.plan.length : Number(f.tenorMonths),
      ...(f.rate.trim() ? { rate: f.rate.trim() } : {}),
      ...(f.instalment.trim() ? { instalment: moneyInput(f.instalment) ?? f.instalment } : {}),
      ...(f.maturityAmount.trim() ? { maturityAmount: moneyInput(f.maturityAmount) ?? f.maturityAmount } : {}),
      ...(product.repaymentMethod === 'STRUCTURED' ? { scheduleRows: f.plan.map((r) => ({ dueDate: r.dueDate, principal: moneyInput(r.principal || '0') ?? r.principal })) } : {}),
      disbursalDate: f.disbursalDate || undefined,
      firstDueDate: f.firstDueDate || undefined,
      ...(f.moratoriumMonths ? { moratoriumMonths: Number(f.moratoriumMonths) } : {}),
      ...(f.balloon ? { balloon: moneyInput(f.balloon) ?? f.balloon } : {}),
      ...(f.externalRef.trim() ? { externalRef: f.externalRef.trim() } : {}),
      ...(parties.length ? { parties: parties.map((p) => ({ customerId: p.customer.id, role: p.role })) } : {}),
    };
  }, [customer, product, f, parties]);
  const appKey = JSON.stringify(app);
  const [previewedKey, setPreviewedKey] = useState<string | null>(null);
  const previewFresh = !!preview.data && previewedKey === appKey;

  const set = (patch: Partial<Form>) => setF((x) => ({ ...x, ...patch }));
  const err = (k: string) => (touched ? errs[k] : null);

  return (
    <div className="stack">
      <PageHeader title="New loan" subtitle="Preview the Key Fact Statement, then book the loan as sanctioned. Money moves only at disbursement." actions={<Link to="/loans">Cancel</Link>} />
      <Card title="1. Customer">
        {customer ? (
          <div className="row" style={{ alignItems: 'center' }} data-testid="selected-customer">
            <strong>{customer.displayName}</strong>
            <span className="mono">{customer.customerNo}</span>
            <span>Home branch {customer.homeBranch}</span>
            <StatusBadge status={customer.kycStatus} />
            <Button
              size="sm"
              onClick={() => {
                setCustomer(null);
                setParties([]);
              }}
            >
              Change
            </Button>
          </div>
        ) : (
          <div className="stack">
            <form
              role="search"
              className="filters"
              style={{ marginBottom: 0 }}
              onSubmit={(e) => {
                e.preventDefault();
                setQ(search.trim());
              }}
            >
              <Input label="Find customer" placeholder="Customer no. / PAN / mobile" value={search} onChange={(e) => setSearch(e.target.value)} fieldClassName="grow" />
              <Button type="submit">Search</Button>
            </form>
            {customers.isLoading ? (
              <Spinner />
            ) : (
              <Table
                caption="Customers"
                captionHidden
                columns={[
                  { key: 'no', header: 'Customer no.', render: (c) => <span className="mono">{c.customerNo}</span> },
                  { key: 'name', header: 'Name', render: (c) => c.displayName },
                  { key: 'pan', header: 'PAN', render: (c) => <Masked value={c.panMasked} kind="PAN" /> },
                  { key: 'branch', header: 'Branch', render: (c) => c.homeBranch },
                  { key: 'kyc', header: 'KYC', render: (c) => <StatusBadge status={c.kycStatus} /> },
                  { key: 'pick', header: <span className="sr-only">Select</span>, render: (c) => <Button size="sm" aria-label={`Select ${c.displayName}`} onClick={() => setCustomer(c)}>Select</Button> },
                ]}
                rows={customers.data ?? []}
                rowKey={(c) => c.id}
                empty={<EmptyState title={q ? `No customer matches “${q}”` : 'No customers'} />}
              />
            )}
            {touched && errs.customer && <span className="field__error" role="alert">{errs.customer}</span>}
          </div>
        )}
        {customer && customer.kycStatus !== 'VERIFIED' && <Banner tone="warn">KYC is {customer.kycStatus?.toLowerCase()}: a loan can be previewed but not sanctioned until KYC is verified.</Banner>}
      </Card>

      <Card title="2. Co-applicants and guarantors">
        <PartyPicker borrowerId={customer?.id ?? null} parties={parties} onChange={setParties} />
      </Card>

      <Card title="3. Terms">
        <div className="form-grid">
          <Select
            label="Product"
            required
            value={f.productCode}
            placeholder="Select…"
            onChange={(e) => set({ productCode: e.target.value, rate: '', moratoriumMonths: '', balloon: '', instalment: '', maturityAmount: '', plan: [] })}
            options={active.map((p) => ({ value: p.code, label: `${p.code} — ${p.name}` }))}
            error={err('productCode')}
          />
          <Input
            label="Amount"
            required
            numeric
            value={f.amount}
            onChange={(e) => set({ amount: e.target.value })}
            hint={product ? `${formatINR(String(product.minAmount))} – ${formatINR(String(product.maxAmount))}` : undefined}
            error={err('amount')}
          />
          {product?.repaymentMethod !== 'STRUCTURED' && (
            <Input
              label="Tenor (months)"
              required
              numeric
              value={f.tenorMonths}
              onChange={(e) => set({ tenorMonths: e.target.value })}
              hint={product ? `${product.minTenorMonths}–${product.maxTenorMonths}${(product.frequency ?? 'MONTHLY') !== 'MONTHLY' ? ` ${FREQUENCY_LABEL[product.frequency!].toLowerCase()} instalments (not months)` : ''}` : undefined}
              error={err('tenorMonths')}
            />
          )}
          {product && !product.interestTableCode && (
            <Input
              label={product.interestBasis === 'FLAT' ? 'Flat rate % p.a.' : 'Rate % p.a.'}
              required={!rateFromElsewhere(product, f)}
              numeric
              value={f.rate}
              onChange={(e) => set({ rate: e.target.value })}
              hint={product.benchmarkCode ? `Empty = ${product.benchmarkCode} + ${String(product.spread)}%` : `${product.minRate}%–${product.maxRate}%`}
              error={err('rate')}
            />
          )}
          {product?.interestTableCode && (
            <div className="field">
              <span className="field__label">Rate</span>
              <span className="muted">From interest table <span className="mono">{product.interestTableCode}</span></span>
            </div>
          )}
          {product?.repaymentMethod === 'EQUATED' && (
            <Input label="Agreed instalment" numeric value={f.instalment} onChange={(e) => set({ instalment: e.target.value })} hint="Instead of the rate: the rate then follows from it" error={err('instalment')} />
          )}
          {product?.repaymentMethod === 'BULLET_TOTAL_INTEREST' && (
            <Input label="Maturity amount" numeric value={f.maturityAmount} onChange={(e) => set({ maturityAmount: e.target.value })} hint="Instead of the rate: amount repayable at maturity" error={err('maturityAmount')} />
          )}
          <Input label="Disbursal date" type="date" min={me.businessDate} value={f.disbursalDate} onChange={(e) => set({ disbursalDate: e.target.value })} />
          <Input label="First due date" type="date" value={f.firstDueDate} onChange={(e) => set({ firstDueDate: e.target.value })} hint="Optional; defaults to one month after disbursal" />
          {!!product?.maxMoratoriumMonths && (
            <Input label="Moratorium (months)" numeric value={f.moratoriumMonths} onChange={(e) => set({ moratoriumMonths: e.target.value })} hint={`Interest-only months, up to ${product.maxMoratoriumMonths}`} error={err('moratoriumMonths')} />
          )}
          {product?.repaymentMethod === 'EQUATED' && (
            <Input label="Balloon" numeric value={f.balloon} onChange={(e) => set({ balloon: e.target.value })} hint="Optional amount due with the last EMI" error={err('balloon')} />
          )}
          <Input label="LOS reference" value={f.externalRef} onChange={(e) => set({ externalRef: e.target.value })} hint="Optional; repeating it returns the existing loan" />
        </div>
        {product?.repaymentMethod === 'STEP_EQUATED' && (
          <p className="muted" style={{ margin: '12px 0 0' }}>
            Step plan of the product: the instalment changes by {String(product.stepPercent)}% every {product.stepEvery} instalments.
          </p>
        )}
        {product?.repaymentMethod === 'STRUCTURED' && (
          <fieldset className="fee-row" style={{ marginTop: 12, display: 'block' }} aria-label="Principal plan">
            <legend>Principal plan</legend>
            <p className="muted" style={{ margin: '0 0 8px', fontSize: 12 }}>Principal falling due on each date (0 for an interest-only date). The tenor is the number of rows; the principal must add up to the amount.</p>
            {f.plan.map((r, i) => (
              <div className="row" key={i} style={{ alignItems: 'flex-end', marginBottom: 8 }}>
                <Input label={`Due date ${i + 1}`} type="date" min={f.disbursalDate} value={r.dueDate} onChange={(e) => set({ plan: f.plan.map((x, j) => (j === i ? { ...x, dueDate: e.target.value } : x)) })} />
                <Input label={`Principal ${i + 1}`} numeric value={r.principal} onChange={(e) => set({ plan: f.plan.map((x, j) => (j === i ? { ...x, principal: e.target.value } : x)) })} />
                <Button size="sm" variant="ghost" aria-label={`Remove plan row ${i + 1}`} onClick={() => set({ plan: f.plan.filter((_, j) => j !== i) })}>
                  Remove
                </Button>
              </div>
            ))}
            <Button size="sm" onClick={() => set({ plan: [...f.plan, { dueDate: '', principal: '' }] })}>
              Add plan row
            </Button>
            {touched && errs.plan && (
              <span className="field__error" role="alert" style={{ display: 'block', marginTop: 8 }}>
                {errs.plan}
              </span>
            )}
          </fieldset>
        )}
        <div className="form-actions" style={{ marginTop: 16 }}>
          <Button
            loading={preview.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid || !app) return;
              create.reset();
              preview.mutate(app, { onSuccess: () => setPreviewedKey(appKey) });
            }}
          >
            Preview
          </Button>
          <Button
            variant="primary"
            disabled={!previewFresh}
            loading={create.isPending}
            title={previewFresh ? undefined : 'Preview the current terms first'}
            onClick={() =>
              app &&
              create.mutate(app, {
                onSuccess: (loan) => {
                  toast({ tone: 'success', message: `Loan ${loan.loanNo} booked as sanctioned.` });
                  navigate(`/loans/${loan.id}`);
                },
              })
            }
          >
            Create loan
          </Button>
        </div>
        <div style={{ marginTop: 12 }}>
          <ErrorBanner error={preview.error ?? create.error} />
        </div>
      </Card>
      {preview.data && (previewFresh ? <KfsView kfs={preview.data} title="Preview: Key Fact Statement" /> : <Banner tone="info">Terms changed since the last preview. Preview again to see the figures.</Banner>)}
    </div>
  );
}

type Party = { customer: CustomerSummary; role: LoanPartyInput['role'] };

/** Co-applicants and guarantors picked by customer search: each an existing customer, named once, never the borrower. */
function PartyPicker({ borrowerId, parties, onChange }: { borrowerId: string | null; parties: Party[]; onChange: (p: Party[]) => void }) {
  const [search, setSearch] = useState('');
  const [q, setQ] = useState<string | null>(null);
  const res = useCustomers(q ?? '', 0, 10, q !== null);
  const taken = new Set([borrowerId, ...parties.map((p) => p.customer.id)]);
  const add = (customer: CustomerSummary, role: Party['role']) => onChange([...parties, { customer, role }]);
  return (
    <div className="stack">
      {parties.length === 0 ? (
        <p className="muted" style={{ margin: 0 }}>None. Optional: add up to 10 existing customers.</p>
      ) : (
        <ul className="doc-list" aria-label="Parties added">
          {parties.map((p) => (
            <li key={p.customer.id}>
              <span>
                <strong>{p.customer.displayName}</strong> <span className="mono">{p.customer.customerNo}</span> · {p.role === 'GUARANTOR' ? 'Guarantor' : 'Co-applicant'}
              </span>
              <Button size="sm" variant="ghost" aria-label={`Remove ${p.customer.displayName}`} onClick={() => onChange(parties.filter((x) => x.customer.id !== p.customer.id))}>
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}
      <form
        role="search"
        className="filters"
        style={{ marginBottom: 0 }}
        onSubmit={(e) => {
          e.preventDefault();
          setQ(search.trim());
        }}
      >
        <Input label="Find a co-applicant or guarantor" placeholder="Customer no. / PAN / mobile" value={search} onChange={(e) => setSearch(e.target.value)} fieldClassName="grow" />
        <Button type="submit" disabled={parties.length >= 10}>
          Search parties
        </Button>
      </form>
      {q !== null &&
        (res.isLoading ? (
          <Spinner />
        ) : (
          <Table
            caption="Customers to add as a party"
            captionHidden
            columns={[
              { key: 'no', header: 'Customer no.', render: (c) => <span className="mono">{c.customerNo}</span> },
              { key: 'name', header: 'Name', render: (c) => c.displayName },
              { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
              {
                key: 'add',
                header: <span className="sr-only">Add</span>,
                render: (c) =>
                  taken.has(c.id) ? (
                    <span className="muted">{c.id === borrowerId ? 'Borrower' : 'Added'}</span>
                  ) : (
                    <span className="row" style={{ gap: 4 }}>
                      <Button size="sm" aria-label={`Add ${c.displayName} as co-applicant`} disabled={parties.length >= 10} onClick={() => add(c, 'CO_APPLICANT')}>
                        Co-applicant
                      </Button>
                      <Button size="sm" aria-label={`Add ${c.displayName} as guarantor`} disabled={parties.length >= 10} onClick={() => add(c, 'GUARANTOR')}>
                        Guarantor
                      </Button>
                    </span>
                  ),
              },
            ]}
            rows={res.data ?? []}
            rowKey={(c) => c.id}
            empty={<EmptyState title={q ? `No customer matches “${q}”` : 'No customers'} />}
          />
        ))}
    </div>
  );
}
