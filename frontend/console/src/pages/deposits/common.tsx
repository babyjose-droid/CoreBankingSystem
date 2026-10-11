import { useState } from 'react';
import { isApiError } from '../../api/errors';
import { useCustomers, useMe } from '../../api/hooks';
import type { CustomerSummary, DepositFamily, DepositNomineeInput, DepositProduct, DepositRuleSet } from '../../api/types';
import { Banner, Button, Card, EmptyState, ErrorBanner, Input, Select, StatusBadge, Table } from '../../ui';

export const TYPE_LABEL: Record<string, string> = {
  SAVINGS: 'Savings',
  CURRENT: 'Current',
  FD_CUMULATIVE: 'Fixed deposit, cumulative',
  FD_PAYOUT: 'Fixed deposit, interest paid out',
};
export const PERIOD_LABEL: Record<number, string> = { 0: 'Once, at maturity', 1: 'Monthly', 3: 'Quarterly', 6: 'Half-yearly', 12: 'Yearly' };
export const OPERATING_MODES = [
  { value: 'SINGLE', label: 'Single' },
  { value: 'EITHER_OR_SURVIVOR', label: 'Either or survivor (two holders)' },
  { value: 'FORMER_OR_SURVIVOR', label: 'Former or survivor (two holders)' },
  { value: 'ANYONE_OR_SURVIVOR', label: 'Anyone or survivor' },
  { value: 'JOINTLY', label: 'Jointly (all holders sign)' },
];
export const RELATIONSHIPS = ['SPOUSE', 'CHILD', 'PARENT', 'SIBLING', 'GRANDCHILD', 'OTHER_RELATIVE', 'OTHER'];

export const pct = (v: string | number | null | undefined) => (v === null || v === undefined || v === '' ? '—' : `${v}%`);
export function tenureText(months: number | null | undefined, days: number | null | undefined): string {
  const m = months ?? 0;
  const d = days ?? 0;
  if (m === 0) return `${d} days`;
  return d === 0 ? `${m} months` : `${m} months ${d} days`;
}

/** 'casa' and 'term' in the URL; anything else is not a family. */
export function familyOf(param: string | undefined): DepositFamily | null {
  return param === 'casa' ? 'CASA' : param === 'term' ? 'TERM_DEPOSIT' : null;
}
export const familyPath = (f: DepositFamily) => (f === 'CASA' ? 'casa' : 'term');

/** The deposit modules this tenant has: CASA (banks) and TD (banks and deposit-taking NBFCs). */
export function useDepositModules(): { casa: boolean; td: boolean; any: DepositFamily | null } {
  const modules = useMe().data?.modules ?? [];
  const casa = modules.includes('CASA');
  const td = modules.includes('TD');
  return { casa, td, any: td ? 'TERM_DEPOSIT' : casa ? 'CASA' : null };
}

export function NoDeposits() {
  return (
    <Card>
      <EmptyState title="Deposits are not enabled for this institution">
        Savings and current accounts are for banks; term deposits are for banks and for an NBFC registered by RBI to accept public deposits.
      </EmptyState>
    </Card>
  );
}

/** A refusal with every reason (422 with `problems`) as a list; any other error as usual. */
export function Problems({ error, title }: { error: unknown; title?: string }) {
  if (!error) return null;
  const problems = isApiError(error) ? (error.problem as { problems?: string[] }).problems : undefined;
  if (!problems?.length) return <ErrorBanner error={error} />;
  return (
    <Banner tone="danger">
      <div>
        <strong>{title ?? 'This cannot go ahead:'}</strong>
        <ul style={{ margin: '4px 0 0', paddingLeft: 18 }} aria-label="Problems">
          {problems.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      </div>
    </Banner>
  );
}

/** The value of one rule of the rule set, or null when that rule does not apply to this institution. */
export function ruleValue(rules: DepositRuleSet | undefined, code: string): number | null {
  const r = rules?.rules?.find((x) => x.code === code);
  return r ? Number(r.value) : null;
}

/** NBFC Directions para 31(9): the depositor is told that the deposit is not insured. */
export function NotInsured({ rules }: { rules: DepositRuleSet | undefined }) {
  if (ruleValue(rules, 'insured') !== 0) return null;
  return <Banner tone="warn">Deposits with this institution are not insured. Say so to the depositor; the receipt will carry the same line.</Banner>;
}

export function ProductStatus({ p }: { p: Pick<DepositProduct, 'status'> }) {
  return <StatusBadge status={p.status ?? 'ACTIVE'} />;
}

// ---------- holders ----------

/** Finds customers by number, PAN or mobile and hands the one chosen to `onPick`. */
export function CustomerPicker({ label, exclude, onPick }: { label: string; exclude: string[]; onPick: (c: CustomerSummary) => void }) {
  const [search, setSearch] = useState('');
  const [q, setQ] = useState<string | null>(null);
  const res = useCustomers(q ?? '', 0, 10, q !== null);
  const rows = (res.data ?? []).filter((c) => !exclude.includes(c.id ?? ''));
  return (
    <div className="stack">
      <form
        className="filters"
        role="search"
        style={{ marginBottom: 0 }}
        onSubmit={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setQ(search.trim());
        }}
      >
        <Input label={label} placeholder="Customer no. / PAN / mobile" value={search} onChange={(e) => setSearch(e.target.value)} fieldClassName="grow" />
        <Button type="submit">Find</Button>
      </form>
      <ErrorBanner error={res.error} />
      {q !== null && !res.isLoading && (
        <Table
          caption={`Customers: ${label}`}
          captionHidden
          columns={[
            { key: 'no', header: 'Customer no.', render: (c) => <span className="mono">{c.customerNo}</span> },
            { key: 'name', header: 'Name', render: (c) => c.displayName },
            { key: 'kyc', header: 'KYC', render: (c) => <StatusBadge status={c.kycStatus ?? 'PENDING'} /> },
            {
              key: 'pick',
              header: <span className="sr-only">Select</span>,
              render: (c) => (
                <Button size="sm" aria-label={`Select ${c.displayName}`} onClick={() => (onPick(c), setQ(null), setSearch(''))}>
                  Select
                </Button>
              ),
            },
          ]}
          rows={rows}
          rowKey={(c) => c.id ?? c.customerNo ?? ''}
          empty={<EmptyState title="No customer found" />}
        />
      )}
    </div>
  );
}

/** The first-named holder and the joint holders, in the order they are named. */
export function HoldersCard({ holders, onChange, mode, onMode }: { holders: CustomerSummary[]; onChange: (h: CustomerSummary[]) => void; mode: string; onMode: (m: string) => void }) {
  return (
    <Card title="Holders">
      <div className="stack">
        {holders.length > 0 && (
          <Table
            caption="Holders of the account"
            captionHidden
            columns={[
              { key: 'n', header: 'No.', render: (c) => holders.indexOf(c) + 1 },
              { key: 'no', header: 'Customer no.', render: (c) => <span className="mono">{c.customerNo}</span> },
              { key: 'name', header: 'Name', render: (c) => c.displayName },
              { key: 'kyc', header: 'KYC', render: (c) => <StatusBadge status={c.kycStatus ?? 'PENDING'} /> },
              {
                key: 'x',
                header: <span className="sr-only">Remove</span>,
                render: (c) => (
                  <Button size="sm" aria-label={`Remove ${c.displayName}`} onClick={() => onChange(holders.filter((h) => h !== c))}>
                    Remove
                  </Button>
                ),
              },
            ]}
            rows={holders}
            rowKey={(c) => c.id ?? ''}
          />
        )}
        {holders.length < 4 && (
          <CustomerPicker
            label={holders.length === 0 ? 'Find the customer' : 'Add a joint holder'}
            exclude={holders.map((h) => h.id ?? '')}
            onPick={(c) => {
              const next = [...holders, c];
              onChange(next);
              if (next.length === 1) onMode('SINGLE');
              else if (mode === 'SINGLE') onMode(next.length === 2 ? 'EITHER_OR_SURVIVOR' : 'ANYONE_OR_SURVIVOR');
            }}
          />
        )}
        <div className="form-grid">
          <Select label="Operating mode" value={mode} onChange={(e) => onMode(e.target.value)} options={OPERATING_MODES} />
        </div>
      </div>
    </Card>
  );
}

// ---------- nominees ----------

export interface NomineeRow {
  name: string;
  relationship: string;
  share: string;
  minor: boolean;
  guardian: string;
}
export const emptyNominee = (): NomineeRow => ({ name: '', relationship: 'SPOUSE', share: '', minor: false, guardian: '' });

export function nomineesInput(mode: string, rows: NomineeRow[]): DepositNomineeInput[] {
  if (mode === 'NONE') return [];
  return rows.map((n) => ({
    name: n.name.trim(),
    relationship: n.relationship as DepositNomineeInput['relationship'],
    sharePercent: mode === 'SIMULTANEOUS' && n.share !== '' ? n.share : null,
    minor: n.minor,
    guardianName: n.minor && n.guardian.trim() ? n.guardian.trim() : null,
  }));
}

/** Nominees with their shares (taken together) or in the order named (one after another); the rule set limits how many. */
export function NomineesCard({ mode, onMode, rows, onChange, max }: { mode: string; onMode: (m: string) => void; rows: NomineeRow[]; onChange: (r: NomineeRow[]) => void; max: number | null }) {
  const set = (i: number, patch: Partial<NomineeRow>) => onChange(rows.map((r, k) => (k === i ? { ...r, ...patch } : r)));
  const total = rows.reduce((s, r) => s + (Number(r.share) || 0), 0);
  return (
    <Card title="Nomination">
      <div className="stack">
        <div className="form-grid">
          <Select
            label="Nomination"
            value={mode}
            onChange={(e) => {
              onMode(e.target.value);
              if (e.target.value === 'NONE') onChange([]);
              else if (rows.length === 0) onChange([{ ...emptyNominee(), share: e.target.value === 'SIMULTANEOUS' ? '100' : '' }]);
            }}
            options={[
              { value: 'NONE', label: 'No nominee' },
              { value: 'SIMULTANEOUS', label: 'Nominees take together, each a share' },
              ...(max === null || max > 1 ? [{ value: 'SUCCESSIVE', label: 'Nominees take one after another, in the order named' }] : []),
            ]}
            hint={max !== null ? `At most ${max} nominee${max === 1 ? '' : 's'} for this institution.` : undefined}
          />
        </div>
        {rows.map((n, i) => (
          <fieldset key={i} className="form-grid" style={{ border: 0, padding: 0, margin: 0 }} aria-label={`Nominee ${i + 1}`}>
            <Input label={`Nominee ${i + 1} name`} required value={n.name} onChange={(e) => set(i, { name: e.target.value })} />
            <Select label="Relationship" value={n.relationship} onChange={(e) => set(i, { relationship: e.target.value })} options={RELATIONSHIPS.map((r) => ({ value: r, label: r.replace('_', ' ').toLowerCase() }))} />
            {mode === 'SIMULTANEOUS' && <Input label="Share %" numeric required value={n.share} onChange={(e) => set(i, { share: e.target.value.replace(/[^0-9.]/g, '') })} />}
            <Select label="Minor" value={n.minor ? 'Y' : 'N'} onChange={(e) => set(i, { minor: e.target.value === 'Y' })} options={[{ value: 'N', label: 'No' }, { value: 'Y', label: 'Yes: a guardian receives' }]} />
            {n.minor && <Input label="Guardian's name" required value={n.guardian} onChange={(e) => set(i, { guardian: e.target.value })} />}
            <div style={{ alignSelf: 'end' }}>
              <Button size="sm" aria-label={`Remove nominee ${i + 1}`} onClick={() => onChange(rows.filter((_, k) => k !== i))}>
                Remove
              </Button>
            </div>
          </fieldset>
        ))}
        {mode !== 'NONE' && (
          <div className="row" style={{ gap: 12 }}>
            {(max === null || rows.length < max) && <Button size="sm" onClick={() => onChange([...rows, emptyNominee()])}>Add nominee</Button>}
            {mode === 'SIMULTANEOUS' && rows.length > 0 && (
              <span className={total === 100 ? 'muted' : undefined} data-testid="share-total">
                Shares total {total}%{total === 100 ? '' : ': they must total 100%'}
              </span>
            )}
          </div>
        )}
      </div>
    </Card>
  );
}
