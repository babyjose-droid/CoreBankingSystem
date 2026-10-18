import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useBookTermDeposit, useDepositAccounts, useDepositProducts, useDepositRules, usePreviewTermDeposit } from '../../api/depositHooks';
import { useBranches, useMe } from '../../api/hooks';
import type { CustomerSummary, TermDepositInput, TermDepositPreview } from '../../api/types';
import { Banner, Button, Card, Checkbox, DateText, ErrorBanner, Input, MoneyText, PageHeader, Select, Table } from '../../ui';
import { useProposalToast } from '../proposal';
import { HoldersCard, NomineesCard, NotInsured, PERIOD_LABEL, Problems, TYPE_LABEL, nomineesInput, pct, ruleValue, tenureText, useDepositModules, type NomineeRow } from './common';

/** The figures of a deposit as the engine works them out; the booking uses the same calculation. */
export function PreviewCard({ q }: { q: TermDepositPreview }) {
  return (
    <Card title="Preview" flush>
      <div style={{ padding: 16 }}>
        <dl className="kv" data-testid="td-preview">
          <dt>Rate</dt>
          <dd>
            <strong data-testid="preview-rate">{pct(q.ratePercent)}</strong> a year
            <br />
            <span className="muted">
              card rate {pct(q.cardRatePercent)} (amount from {Number(q.cardFromAmount).toLocaleString('en-IN')}, tenure from {tenureText(q.cardFromMonths, q.cardFromDays)})
              {(q.addOns ?? []).map((x) => `, ${x.code} ${Number(x.percent) > 0 ? '+' : ''}${x.percent}`).join('')}
            </span>
          </dd>
          <dt>If funded on</dt>
          <dd><DateText value={q.startDate} /></dd>
          <dt>Matures on</dt>
          <dd data-testid="preview-maturity"><DateText value={q.maturityDate} /></dd>
          {q.recurring && (
            <>
              <dt>Monthly instalment</dt>
              <dd data-testid="preview-instalment"><MoneyText value={q.amount} /> × {q.tenureMonths}, the first on funding</dd>
            </>
          )}
          <dt>Total interest</dt>
          <dd><MoneyText value={q.totalInterest} /></dd>
          <dt>Repaid at maturity</dt>
          <dd data-testid="preview-value"><MoneyText value={q.maturityValue} /></dd>
          {q.cumulative && (
            <>
              <dt>Annualised yield</dt>
              <dd>{pct(q.annualisedYieldPercent)}</dd>
            </>
          )}
          {q.tdsApplicable && (
            <>
              <dt>Tax expected at source</dt>
              <dd data-testid="preview-tax"><MoneyText value={q.totalTax} /></dd>
            </>
          )}
        </dl>
        {q.recurring && <p className="muted" style={{ marginBottom: 0, fontSize: 12 }}>The figures assume every instalment is paid on its due date; an instalment paid late earns from the month after and pays a late charge.</p>}
        {q.taxNote && <p className="muted" style={{ marginBottom: 0, fontSize: 12 }}>{q.taxNote}</p>}
      </div>
      <Table
        caption={q.cumulative ? 'Interest added to the deposit' : 'Interest paid out'}
        columns={[
          { key: 'n', header: 'No.', render: (p) => p.number },
          { key: 'from', header: 'From', render: (p) => <DateText value={p.from} /> },
          { key: 'to', header: q.cumulative ? 'Added on' : 'Paid on', render: (p) => <DateText value={p.to} /> },
          { key: 'bal', header: 'On a balance of', numeric: true, render: (p) => <MoneyText value={p.balance} /> },
          { key: 'int', header: 'Interest', numeric: true, render: (p) => <MoneyText value={p.interest} /> },
          ...(q.tdsApplicable ? [{ key: 'tax', header: 'Tax', numeric: true, render: (p: NonNullable<TermDepositPreview['periods']>[number]) => <MoneyText value={(q.tax ?? []).find((t) => t.period === p.number)?.tax ?? '0'} /> }] : []),
        ]}
        rows={q.periods ?? []}
        rowKey={(p) => String(p.number)}
      />
    </Card>
  );
}

export function TermDepositFormPage() {
  const me = useMe().data!;
  const modules = useDepositModules();
  const navigate = useNavigate();
  const products = useDepositProducts('TERM_DEPOSIT');
  const rules = useDepositRules('TERM_DEPOSIT');
  const branches = useBranches();
  const preview = usePreviewTermDeposit();
  const book = useBookTermDeposit();
  const toast = useProposalToast();
  const [productCode, setProductCode] = useState('');
  const [branch, setBranch] = useState(me.homeBranch ?? '');
  const [amount, setAmount] = useState('');
  const [months, setMonths] = useState('12');
  const [days, setDays] = useState('0');
  const [addOns, setAddOns] = useState<string[]>([]);
  const [holders, setHolders] = useState<CustomerSummary[]>([]);
  const [mode, setMode] = useState('SINGLE');
  const [nomination, setNomination] = useState('NONE');
  const [nominees, setNominees] = useState<NomineeRow[]>([]);
  const [payoutAccount, setPayoutAccount] = useState('');
  const [instruction, setInstruction] = useState('PAY_OUT');
  const depositor = holders[0];
  const own = useDepositAccounts('CASA', { customerId: depositor?.id, status: 'ACTIVE' }, modules.casa && !!depositor?.id);
  const active = (products.data ?? []).filter((p) => p.status === 'ACTIVE');
  const product = active.find((p) => p.code === productCode);
  const rd = product?.productType === 'RD';
  const renewalNeedsConsent = ruleValue(rules.data, 'td.renewal-needs-consent') === 1;
  const changed = () => (preview.reset(), book.reset());

  const input = (): TermDepositInput => ({
    productCode, amount, tenureMonths: Number(months || 0), tenureDays: rd ? 0 : Number(days || 0), addOns,
    customerId: depositor?.id ?? null, jointHolders: holders.slice(1).map((h) => h.id!), branchCode: branch,
    operatingMode: mode as TermDepositInput['operatingMode'], nominationMode: nomination as TermDepositInput['nominationMode'], nominees: nomineesInput(nomination, nominees),
    payoutTo: payoutAccount ? 'CASA' : 'BANK_ACCOUNT', payoutAccountId: payoutAccount || null, maturityInstruction: (rd ? 'PAY_OUT' : instruction) as TermDepositInput['maturityInstruction'],
  });
  const termsEntered = !!productCode && /^\d+(\.\d{1,2})?$/.test(amount) && Number(months || 0) + Number(days || 0) > 0;

  return (
    <div className="stack">
      <PageHeader title="Book a term deposit" subtitle="The deposit is booked once a checker approves the request, and waits for its funds. Interest starts on the funding date." actions={<Link to="/deposits/term">Cancel</Link>} />
      <ErrorBanner error={products.error ?? rules.error ?? branches.error} />
      <NotInsured rules={rules.data} />
      <Card title="Deposit">
        <div className="form-grid">
          <Select label="Product" required placeholder="Choose a product" value={productCode}
            onChange={(e) => (setProductCode(e.target.value), setAddOns([]), setInstruction('PAY_OUT'), changed())}
            options={active.map((p) => ({ value: p.code ?? '', label: `${p.code} — ${p.name}` }))}
            hint={product ? `${TYPE_LABEL[product.productType ?? '']}; ${tenureText(product.minTenureMonths, product.minTenureDays)} to ${tenureText(product.maxTenureMonths, product.maxTenureDays)}; from ${Number(product.minAmount).toLocaleString('en-IN')}` : undefined} />
          <Input label={rd ? 'Monthly instalment' : 'Amount'} required numeric value={amount} onChange={(e) => (setAmount(e.target.value.replace(/[^0-9.]/g, '')), changed())} />
          <Input label="Tenure: months" numeric value={months} onChange={(e) => (setMonths(e.target.value.replace(/[^0-9]/g, '')), changed())} />
          {!rd && <Input label="Tenure: days" numeric value={days} onChange={(e) => (setDays(e.target.value.replace(/[^0-9]/g, '')), changed())} />}
          <Select label="Branch" required value={branch} onChange={(e) => setBranch(e.target.value)}
            options={(branches.data ?? []).filter((b) => (me.branches ?? []).includes(b.code)).map((b) => ({ value: b.code, label: `${b.code} — ${b.name}` }))} />
          {(product?.addOns ?? []).map((a) => (
            <Checkbox key={a.code} label={`${a.code === 'SENIOR_CITIZEN' ? 'Senior citizen' : a.code} ${Number(a.percent) > 0 ? '+' : ''}${a.percent} points`} checked={addOns.includes(a.code ?? '')}
              onChange={(e) => (setAddOns(e.target.checked ? [...addOns, a.code ?? ''] : addOns.filter((x) => x !== a.code)), changed())} />
          ))}
        </div>
        {product && (
          <p className="muted" style={{ marginBottom: 0 }}>
            Interest is {product.productType === 'FD_PAYOUT' ? 'paid out' : 'compounded'} {(PERIOD_LABEL[product.interestPeriodMonths ?? 0] ?? '').toLowerCase()}.
            {rd && ` The first instalment funds the deposit; the rest fall due on the same day each month, with a late charge of Rs ${product.rdLateChargePer100} per 100 a month after it.`}
          </p>
        )}
      </Card>
      <HoldersCard holders={holders} onChange={(h) => (setHolders(h), setPayoutAccount(''), changed())} mode={mode} onMode={setMode} />
      <NomineesCard mode={nomination} onMode={(m) => (setNomination(m), changed())} rows={nominees} onChange={(r) => (setNominees(r), changed())} max={ruleValue(rules.data, 'nominees.max')} />
      <Card title="Proceeds and maturity">
        <div className="form-grid">
          <Select
            label="Interest and maturity proceeds go to"
            value={payoutAccount}
            onChange={(e) => (setPayoutAccount(e.target.value), changed())}
            options={[{ value: '', label: 'The depositor’s bank account' }, ...(own.data ?? []).map((a) => ({ value: a.id ?? '', label: `Account ${a.accountNo} (${a.productCode})` }))]}
            hint={modules.casa ? 'A savings or current account the depositor holds here can be chosen once the depositor is picked.' : 'The bank account is recorded when the deposit is funded.'}
          />
          <Select
            label="At maturity"
            value={instruction}
            onChange={(e) => (setInstruction(e.target.value), changed())}
            options={[
              { value: 'PAY_OUT', label: 'Pay out' },
              ...(product?.autoRenewalAllowed && !renewalNeedsConsent && !rd
                ? [{ value: 'RENEW_PRINCIPAL', label: 'Renew the principal, pay out the interest' }, { value: 'RENEW_WITH_INTEREST', label: 'Renew the principal with the interest' }]
                : []),
            ]}
            hint={renewalNeedsConsent ? 'A renewal needs the depositor’s consent, recorded when the deposit matures.' : product && !product.autoRenewalAllowed ? 'This product does not allow renewal.' : undefined}
          />
        </div>
      </Card>
      <Problems error={preview.error ?? book.error} title="The deposit cannot be booked:" />
      {preview.data && <PreviewCard q={preview.data} />}
      {preview.data && !depositor && <Banner tone="info">Pick the depositor to book the deposit. The senior citizen add-on and the tax figures depend on who the depositor is.</Banner>}
      <div className="row" style={{ justifyContent: 'flex-end', gap: 8 }}>
        <Button disabled={!termsEntered} loading={preview.isPending} onClick={() => preview.mutate(input())}>Preview</Button>
        <Button
          variant="primary"
          disabled={!termsEntered || !depositor || !branch}
          loading={book.isPending}
          onClick={() => book.mutate(input(), { onSuccess: (a) => (toast(a, 'Term deposit'), navigate('/deposits/term')) })}
        >
          Submit for approval
        </Button>
      </div>
    </div>
  );
}
