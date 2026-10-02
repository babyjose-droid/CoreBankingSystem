import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useCustomers, useMe } from '../../api/hooks';
import { useCreateLoan, useLoanProducts, usePreviewLoan } from '../../api/lendingHooks';
import type { CustomerSummary, LoanApplication, LoanPartyInput, LoanProduct } from '../../api/types';
import { formatINR } from '../../lib/money';
import { Banner, Button, Card, EmptyState, ErrorBanner, Input, Masked, PageHeader, Select, Spinner, StatusBadge, Table, useToast } from '../../ui';
import { KfsView } from './KfsView';
import { moneyInput } from './common';

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
}

function problems(f: Form, product: LoanProduct | undefined, customer: CustomerSummary | null) {
  const amount = moneyInput(f.amount);
  const tenor = /^\d+$/.test(f.tenorMonths) ? Number(f.tenorMonths) : NaN;
  const e: Record<string, string | null> = {
    customer: !customer ? 'Select a customer' : null,
    productCode: !product ? 'Select a product' : null,
    amount: !amount || Number(amount) <= 0 ? 'Enter an amount' : null,
    tenorMonths: Number.isNaN(tenor) || tenor < 1 ? 'Whole months' : null,
    rate: null,
    moratoriumMonths: f.moratoriumMonths && !/^\d+$/.test(f.moratoriumMonths) ? 'Whole months' : null,
    balloon: f.balloon && !moneyInput(f.balloon) ? 'Enter an amount' : null,
  };
  if (product) {
    if (amount && (Number(amount) < Number(product.minAmount) || Number(amount) > Number(product.maxAmount))) {
      e.amount = `Between ${formatINR(String(product.minAmount))} and ${formatINR(String(product.maxAmount))}`;
    }
    if (!Number.isNaN(tenor) && (tenor < product.minTenorMonths || tenor > product.maxTenorMonths)) e.tenorMonths = `${product.minTenorMonths} to ${product.maxTenorMonths} months`;
    if (!product.interestTableCode) {
      const r = Number(f.rate);
      if (!/^\d+(\.\d+)?$/.test(f.rate.trim())) e.rate = 'Enter the rate';
      else if (r < Number(product.minRate) || r > Number(product.maxRate)) e.rate = `${product.minRate}% to ${product.maxRate}%`;
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
  const [f, setF] = useState<Form>({ productCode: '', amount: '', tenorMonths: '', rate: '', disbursalDate: me.businessDate, firstDueDate: '', moratoriumMonths: '', balloon: '', externalRef: '' });
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
      tenorMonths: Number(f.tenorMonths),
      ...(product.interestTableCode ? {} : { rate: f.rate.trim() }),
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
            onChange={(e) => set({ productCode: e.target.value, rate: '', moratoriumMonths: '', balloon: '' })}
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
          <Input label="Tenor (months)" required numeric value={f.tenorMonths} onChange={(e) => set({ tenorMonths: e.target.value })} hint={product ? `${product.minTenorMonths}–${product.maxTenorMonths}` : undefined} error={err('tenorMonths')} />
          {product && !product.interestTableCode && (
            <Input label="Rate % p.a." required numeric value={f.rate} onChange={(e) => set({ rate: e.target.value })} hint={`${product.minRate}%–${product.maxRate}%`} error={err('rate')} />
          )}
          {product?.interestTableCode && (
            <div className="field">
              <span className="field__label">Rate</span>
              <span className="muted">From interest table <span className="mono">{product.interestTableCode}</span></span>
            </div>
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
