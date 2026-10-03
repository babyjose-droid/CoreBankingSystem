import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useBranches, useCreateCustomer, useDedupeCheck, useEnumeration, useMe } from '../../api/hooks';
import { errorMessage, isApiError } from '../../api/errors';
import type { CustomerInput, DedupeMatch } from '../../api/types';
import {
  Badge,
  Banner,
  Button,
  Card,
  Checkbox,
  EmptyState,
  ErrorBanner,
  Input,
  PageHeader,
  Select,
  Table,
  Tabs,
  Textarea,
  humanize,
  useToast,
} from '../../ui';
import { useProposalToast } from '../proposal';
import { validateBasics, validateDetails, type Basics, type Details, type Errors } from './validation';

const EMPTY_BASICS: Basics = { customerType: 'INDIVIDUAL', homeBranch: '', firstName: '', lastName: '', dateOfBirth: '', mobile: '', pan: '' };
const EMPTY_DETAILS: Details = { middleName: '', gender: '', email: '', line1: '', line2: '', city: '', stateCode: '', pincode: '' };

const STRENGTH_TONE = { EXACT: 'danger', STRONG: 'warn', POSSIBLE: 'info' } as const;

import { CustomFieldInputs, customDraftErrors, customFromDraft, useCustomDefs, type CustomDraft } from '../custom/CustomFields';

export function NewCustomerPage() {
  const me = useMe().data!;
  const branches = useBranches();
  const states = useEnumeration('gst-state');
  const dedupe = useDedupeCheck();
  const create = useCreateCustomer();
  const toast = useToast();
  const proposalToast = useProposalToast();
  const navigate = useNavigate();

  const [step, setStep] = useState<1 | 2>(1);
  const [basics, setBasics] = useState<Basics>({ ...EMPTY_BASICS, homeBranch: me.homeBranch ?? '' });
  const [details, setDetails] = useState<Details>(EMPTY_DETAILS);
  const [errors, setErrors] = useState<Errors<Basics>>({});
  const [detailErrors, setDetailErrors] = useState<Errors<Details>>({});
  const [checkedKey, setCheckedKey] = useState<string | null>(null);
  const [matches, setMatches] = useState<DedupeMatch[]>([]);
  const [override, setOverride] = useState(false);
  const [overrideReason, setOverrideReason] = useState('');
  const customDefs = useCustomDefs('CUSTOMER');
  const [custom, setCustom] = useState<CustomDraft>({});
  const [customErrors, setCustomErrors] = useState<Record<string, string>>({});
  const [tab, setTab] = useState('personal');

  const basicsKey = JSON.stringify(basics);
  const checkedCurrent = checkedKey === basicsKey;
  const exact = matches.find((m) => m.strength === 'EXACT');
  const nonExact = matches.filter((m) => m.strength !== 'EXACT');
  const needsOverride = checkedCurrent && !exact && nonExact.length > 0;
  const canContinue = checkedCurrent && (!needsOverride || (override && overrideReason.trim().length > 0));

  const set = <K extends keyof Basics>(k: K, v: Basics[K]) => {
    setBasics((b) => ({ ...b, [k]: v }));
    if (errors[k]) setErrors((e) => ({ ...e, [k]: undefined }));
  };
  const setD = <K extends keyof Details>(k: K, v: Details[K]) => {
    setDetails((d) => ({ ...d, [k]: v }));
    if (detailErrors[k]) setDetailErrors((e) => ({ ...e, [k]: undefined }));
  };

  const toInput = (): CustomerInput => {
    const addr = { line1: details.line1, line2: details.line2, city: details.city, stateCode: details.stateCode, pincode: details.pincode };
    const hasAddr = Object.values(addr).some(Boolean);
    return {
      customerType: basics.customerType,
      homeBranch: basics.homeBranch,
      firstName: basics.firstName.trim(),
      lastName: basics.lastName.trim() || undefined,
      middleName: details.middleName.trim() || undefined,
      dateOfBirth: basics.dateOfBirth,
      mobile: basics.mobile,
      pan: basics.pan || undefined,
      gender: details.gender || undefined,
      email: details.email.trim() || undefined,
      address: hasAddr ? Object.fromEntries(Object.entries(addr).filter(([, v]) => v)) : undefined,
      overrideDedupe: needsOverride ? true : undefined,
      overrideReason: needsOverride ? overrideReason.trim() : undefined,
      ...(customDefs.length ? { custom: customFromDraft(customDefs, custom) } : {}),
    };
  };

  const checkDuplicates = () => {
    const e = validateBasics(basics, me.businessDate);
    setErrors(e);
    if (Object.keys(e).length) return;
    dedupe.mutate(toInput(), {
      onSuccess: (m) => {
        setMatches(m);
        setCheckedKey(basicsKey);
        setOverride(false);
        setOverrideReason('');
      },
    });
  };

  const submit = () => {
    const e = validateDetails(details);
    setDetailErrors(e);
    if (Object.keys(e).length) {
      setTab(e.email ? 'personal' : 'contact');
      return;
    }
    const ce = customDraftErrors(customDefs, custom);
    setCustomErrors(ce);
    if (Object.keys(ce).length) {
      setTab('additional');
      return;
    }
    create.mutate(toInput(), {
      onSuccess: (r) => {
        if (r.kind === 'existing') {
          toast({ tone: 'info', message: `Customer with this PAN already exists (${r.customer.customerNo}).`, link: { to: `/customers/${r.customer.id}`, label: 'Open customer' } });
          navigate(`/customers/${r.customer.id}`);
        } else {
          proposalToast(r.approval, 'New customer');
          navigate('/customers');
        }
      },
      onError: (err) => {
        if (!isApiError(err)) toast({ tone: 'error', message: errorMessage(err) });
      },
    });
  };

  const branchOptions = useMemo(
    () => (branches.data ?? []).filter((b) => b.status !== 'CLOSED').map((b) => ({ value: b.code, label: `${b.code} — ${b.name}` })),
    [branches.data],
  );
  const indiv = basics.customerType === 'INDIVIDUAL';

  return (
    <div className="stack" style={{ maxWidth: 960 }}>
      <PageHeader title="New customer" subtitle={`Step ${step} of 2 — ${step === 1 ? 'basics and duplicate check' : 'details'}`} actions={<Link to="/customers">Cancel</Link>} />
      {step === 1 && (
        <Card title="1. Basics">
          <form
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              checkDuplicates();
            }}
          >
            <div className="form-grid">
              <Select
                label="Customer type"
                required
                value={basics.customerType}
                onChange={(e) => set('customerType', e.target.value as Basics['customerType'])}
                options={[
                  { value: 'INDIVIDUAL', label: 'Individual' },
                  { value: 'NON_INDIVIDUAL', label: 'Non-individual' },
                ]}
              />
              <Select label="Home branch" required value={basics.homeBranch} onChange={(e) => set('homeBranch', e.target.value)} options={branchOptions} placeholder="Select…" error={errors.homeBranch} />
              <Input label={indiv ? 'First name' : 'Entity name'} required value={basics.firstName} onChange={(e) => set('firstName', e.target.value)} error={errors.firstName} autoComplete="off" />
              {indiv && <Input label="Last name" value={basics.lastName} onChange={(e) => set('lastName', e.target.value)} autoComplete="off" />}
              <Input
                label={indiv ? 'Date of birth' : 'Date of incorporation'}
                type="date"
                required
                value={basics.dateOfBirth}
                max={me.businessDate}
                onChange={(e) => set('dateOfBirth', e.target.value)}
                error={errors.dateOfBirth}
                hint={indiv ? 'Must be 18 or older on the business date' : undefined}
              />
              <Input
                label="Mobile"
                required
                inputMode="numeric"
                maxLength={10}
                value={basics.mobile}
                onChange={(e) => set('mobile', e.target.value.replace(/\D/g, ''))}
                error={errors.mobile}
                autoComplete="off"
              />
              <Input
                label="PAN"
                value={basics.pan}
                maxLength={10}
                onChange={(e) => set('pan', e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))}
                error={errors.pan}
                hint="Format ABCDE1234F"
                autoComplete="off"
                className="mono"
              />
            </div>
            <div className="form-actions">
              <Button type="submit" loading={dedupe.isPending}>
                Check duplicates
              </Button>
              <Button variant="primary" disabled={!canContinue} onClick={() => setStep(2)}>
                Continue
              </Button>
            </div>
          </form>
          {!checkedCurrent && checkedKey && <Banner tone="info">Details changed — run the duplicate check again.</Banner>}
          <ErrorBanner error={dedupe.error} />
          {checkedCurrent && (
            <div className="stack" style={{ marginTop: 16 }}>
              <h3>Duplicate check</h3>
              {matches.length === 0 ? (
                <Banner tone="ok">No duplicates found.</Banner>
              ) : (
                <>
                  <Table
                    caption="Possible duplicate customers"
                    columns={[
                      { key: 'no', header: 'Customer no.', render: (m) => <span className="mono">{m.customerNo}</span> },
                      { key: 'name', header: 'Name', render: (m) => m.displayName },
                      { key: 'rule', header: 'Rule', render: (m) => (m.rule === 'NAME_DOB' ? 'Name + DOB' : humanize(m.rule)) },
                      { key: 'strength', header: 'Strength', render: (m) => <Badge tone={STRENGTH_TONE[m.strength ?? 'POSSIBLE']}>{humanize(m.strength ?? 'POSSIBLE')}</Badge> },
                    ]}
                    rows={matches}
                    rowKey={(m) => m.customerNo + m.rule}
                    empty={<EmptyState title="None" />}
                  />
                  {exact && (
                    <Banner tone="warn">
                      A customer with this PAN already exists ({exact.customerNo}). Submitting will return the existing customer instead of creating a new one.
                    </Banner>
                  )}
                  {needsOverride && (
                    <div className="stack">
                      <Checkbox label="These are different people — proceed anyway (the checker will see the matches)" checked={override} onChange={(e) => setOverride(e.target.checked)} />
                      {override && <Textarea label="Override reason" required value={overrideReason} onChange={(e) => setOverrideReason(e.target.value)} maxLength={500} />}
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </Card>
      )}

      {step === 2 && (
        <Card title="2. Details" actions={<Button size="sm" onClick={() => setStep(1)}>Back to basics</Button>}>
          <Tabs
            label="Customer details"
            active={tab}
            onChange={setTab}
            tabs={[
              {
                id: 'personal',
                label: 'Personal',
                content: (
                  <div className="form-grid">
                    {indiv && <Input label="Middle name" value={details.middleName} onChange={(e) => setD('middleName', e.target.value)} />}
                    {indiv && (
                      <Select
                        label="Gender"
                        value={details.gender}
                        onChange={(e) => setD('gender', e.target.value as Details['gender'])}
                        placeholder="Not specified"
                        options={[
                          { value: 'FEMALE', label: 'Female' },
                          { value: 'MALE', label: 'Male' },
                          { value: 'OTHER', label: 'Other' },
                        ]}
                      />
                    )}
                    <Input label="Email" type="email" value={details.email} onChange={(e) => setD('email', e.target.value)} error={detailErrors.email} />
                  </div>
                ),
              },
              {
                id: 'identification',
                label: 'Identification',
                content: (
                  <dl className="kv">
                    <dt>PAN</dt>
                    <dd className="mono">{basics.pan ? `XXXXX${basics.pan.slice(5, 9)}X` : '—'}</dd>
                    <dt>Mobile</dt>
                    <dd className="mono">{`XXXXXX${basics.mobile.slice(-4)}`}</dd>
                    <dt>Duplicate check</dt>
                    <dd>{matches.length === 0 ? 'No matches' : `${matches.length} match(es)${needsOverride ? ' — override requested' : ''}`}</dd>
                  </dl>
                ),
              },
              {
                id: 'contact',
                label: 'Contact / address',
                content: (
                  <div className="form-grid">
                    <Input label="Address line 1" value={details.line1} onChange={(e) => setD('line1', e.target.value)} />
                    <Input label="Address line 2" value={details.line2} onChange={(e) => setD('line2', e.target.value)} />
                    <Input label="City" value={details.city} onChange={(e) => setD('city', e.target.value)} />
                    <Select
                      label="State"
                      value={details.stateCode}
                      onChange={(e) => setD('stateCode', e.target.value)}
                      placeholder="Select…"
                      options={(states.data ?? []).map((s) => ({ value: s.code, label: `${s.code} — ${s.label}` }))}
                    />
                    <Input label="PIN code" inputMode="numeric" maxLength={6} value={details.pincode} onChange={(e) => setD('pincode', e.target.value.replace(/\D/g, ''))} error={detailErrors.pincode} />
                  </div>
                ),
              },
              ...(customDefs.length
                ? [{ id: 'additional', label: 'Additional details', content: <CustomFieldInputs defs={customDefs} draft={custom} onChange={setCustom} errors={customErrors} /> }]
                : []),
            ]}
          />
          <ErrorBanner error={create.error} />
          <div className="form-actions">
            <Button onClick={() => setStep(1)}>Back</Button>
            <Button variant="primary" loading={create.isPending} onClick={submit}>
              Submit for approval
            </Button>
          </div>
        </Card>
      )}
    </div>
  );
}
