import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useDepositProducts, useDepositRules, useOpenCasaAccount } from '../../api/depositHooks';
import { useBranches, useMe } from '../../api/hooks';
import type { CasaAccountInput, CustomerSummary } from '../../api/types';
import { Button, Card, ErrorBanner, PageHeader, Select } from '../../ui';
import { useProposalToast } from '../proposal';
import { HoldersCard, NomineesCard, Problems, TYPE_LABEL, nomineesInput, ruleValue, type NomineeRow } from './common';

export function CasaAccountFormPage() {
  const me = useMe().data!;
  const navigate = useNavigate();
  const products = useDepositProducts('CASA');
  const rules = useDepositRules('CASA');
  const branches = useBranches();
  const open = useOpenCasaAccount();
  const toast = useProposalToast();
  const [productCode, setProductCode] = useState('');
  const [branch, setBranch] = useState(me.homeBranch ?? '');
  const [holders, setHolders] = useState<CustomerSummary[]>([]);
  const [mode, setMode] = useState('SINGLE');
  const [nomination, setNomination] = useState('NONE');
  const [nominees, setNominees] = useState<NomineeRow[]>([]);
  const active = (products.data ?? []).filter((p) => p.status === 'ACTIVE');
  const ready = !!productCode && !!branch && holders.length > 0;
  return (
    <div className="stack">
      <PageHeader title="Open a savings or current account" subtitle="The account opens with no balance once a checker approves the request." actions={<Link to="/deposits/casa">Cancel</Link>} />
      <ErrorBanner error={products.error ?? rules.error ?? branches.error} />
      <Card title="Account">
        <div className="form-grid">
          <Select label="Product" required placeholder="Choose a product" value={productCode} onChange={(e) => (setProductCode(e.target.value), open.reset())}
            options={active.map((p) => ({ value: p.code ?? '', label: `${p.code} — ${p.name} (${TYPE_LABEL[p.productType ?? '']})` }))} />
          <Select label="Branch" required value={branch} onChange={(e) => setBranch(e.target.value)}
            options={(branches.data ?? []).filter((b) => (me.branches ?? []).includes(b.code)).map((b) => ({ value: b.code, label: `${b.code} — ${b.name}` }))} />
        </div>
      </Card>
      <HoldersCard holders={holders} onChange={(h) => (setHolders(h), open.reset())} mode={mode} onMode={setMode} />
      <NomineesCard mode={nomination} onMode={setNomination} rows={nominees} onChange={setNominees} max={ruleValue(rules.data, 'nominees.max')} />
      <Problems error={open.error} title="The account cannot be opened:" />
      <div className="row" style={{ justifyContent: 'flex-end' }}>
        <Button
          variant="primary"
          disabled={!ready}
          loading={open.isPending}
          onClick={() => {
            const body: CasaAccountInput = {
              productCode, customerId: holders[0].id!, jointHolders: holders.slice(1).map((h) => h.id!), branchCode: branch,
              operatingMode: mode as CasaAccountInput['operatingMode'], nominationMode: nomination as CasaAccountInput['nominationMode'], nominees: nomineesInput(nomination, nominees),
            };
            open.mutate(body, { onSuccess: (a) => (toast(a, 'Account opening'), navigate('/deposits/casa')) });
          }}
        >
          Submit for approval
        </Button>
      </div>
    </div>
  );
}
