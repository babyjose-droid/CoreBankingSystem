import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useBranches, useGlHeads, useMe, useProposeVoucher } from '../../api/hooks';
import type { VoucherInput, VoucherLine } from '../../api/types';
import { formatINR, fromUnits, isMoney, toUnits } from '../../lib/money';
import { Badge, Button, Card, ErrorBanner, Input, PageHeader, Select } from '../../ui';
import { useProposalToast } from '../proposal';

interface LineDraft {
  key: number;
  branch: string;
  glCode: string;
  side: 'DR' | 'CR';
  amount: string;
  narration: string;
}

export function lineTotals(lines: Array<Pick<LineDraft, 'side' | 'amount'>>): { dr: bigint; cr: bigint } {
  let dr = 0n;
  let cr = 0n;
  for (const l of lines) {
    if (!isMoney(l.amount)) continue;
    const u = toUnits(l.amount);
    if (l.side === 'DR') dr += u;
    else cr += u;
  }
  return { dr, cr };
}

const AMOUNT_RE = /^[0-9]+(\.[0-9]{1,2})?$/;

export function VoucherFormPage() {
  const me = useMe().data!;
  const branches = useBranches();
  const heads = useGlHeads();
  const propose = useProposeVoucher();
  const toast = useProposalToast();
  const navigate = useNavigate();
  const seq = useMemo(() => ({ n: 2 }), []);
  const blank = (key: number, side: 'DR' | 'CR'): LineDraft => ({ key, branch: me.homeBranch ?? '', glCode: '', side, amount: '', narration: '' });
  const [voucherType, setVoucherType] = useState<VoucherInput['voucherType']>('JOURNAL');
  const [valueDate, setValueDate] = useState(me.businessDate);
  const [reference, setReference] = useState('');
  const [description, setDescription] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([blank(1, 'DR'), blank(2, 'CR')]);
  const [touched, setTouched] = useState(false);

  const postingHeads = (heads.data ?? []).filter((h) => h.posting && h.status !== 'FROZEN' && h.status !== 'CLOSED');
  const { dr, cr } = lineTotals(lines);
  const diff = dr - cr;
  const balanced = dr > 0n && diff === 0n;

  const lineErrors = lines.map((l) => ({
    branch: !l.branch ? 'Required' : null,
    glCode: !l.glCode ? 'Select a posting head' : null,
    amount: !AMOUNT_RE.test(l.amount) || toUnits(l.amount) <= 0n ? 'Enter an amount > 0 (max 2 decimals)' : null,
  }));
  const headerErrors = {
    description: !description.trim() ? 'Description is required' : null,
    valueDate: !valueDate ? 'Required' : valueDate > me.businessDate ? 'Cannot be after the business date' : null,
  };
  const allValid = !headerErrors.description && !headerErrors.valueDate && lineErrors.every((e) => !e.branch && !e.glCode && !e.amount);
  const canSubmit = balanced && allValid && lines.length >= 2;

  const update = (key: number, patch: Partial<LineDraft>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  const submit = () => {
    setTouched(true);
    if (!canSubmit) return;
    const input: VoucherInput = {
      voucherType,
      valueDate,
      reference: reference.trim() || undefined,
      description: description.trim(),
      lines: lines.map<VoucherLine>((l) => ({ branch: l.branch, glCode: l.glCode, side: l.side, amount: fromUnits(toUnits(l.amount)), narration: l.narration.trim() || undefined })),
    };
    propose.mutate(input, {
      onSuccess: (a) => {
        toast(a, 'Voucher');
        navigate('/ledger/vouchers');
      },
    });
  };

  return (
    <div className="stack" style={{ maxWidth: 1100 }}>
      <PageHeader title="New voucher" subtitle="Debits must equal credits. Lines across branches get automatic inter-branch legs." actions={<Link to="/ledger/vouchers">Cancel</Link>} />
      <Card title="Header">
        <div className="form-grid">
          <Select
            label="Voucher type"
            required
            value={voucherType}
            onChange={(e) => setVoucherType(e.target.value as VoucherInput['voucherType'])}
            options={[
              { value: 'JOURNAL', label: 'Journal' },
              { value: 'RECEIPT', label: 'Receipt' },
              { value: 'PAYMENT', label: 'Payment' },
              { value: 'CONTRA', label: 'Contra' },
            ]}
          />
          <Input label="Value date" type="date" required value={valueDate} max={me.businessDate} onChange={(e) => setValueDate(e.target.value)} error={touched ? headerErrors.valueDate : null} />
          <Input label="Reference" value={reference} onChange={(e) => setReference(e.target.value)} />
          <Input label="Description" required value={description} onChange={(e) => setDescription(e.target.value)} error={touched ? headerErrors.description : null} fieldClassName="span-2" />
        </div>
      </Card>
      <Card title="Lines" actions={<Button size="sm" onClick={() => setLines((ls) => [...ls, blank(++seq.n, diff > 0n ? 'CR' : 'DR')])}>Add line</Button>}>
        <div className="lines">
          {lines.map((l, i) => (
            <fieldset key={l.key} className="line" style={{ margin: 0 }} aria-label={`Line ${i + 1}`}>
              <Select
                label={`Line ${i + 1} branch`}
                value={l.branch}
                onChange={(e) => update(l.key, { branch: e.target.value })}
                placeholder="Branch…"
                options={(branches.data ?? []).filter((b) => b.status !== 'CLOSED').map((b) => ({ value: b.code, label: b.code }))}
                error={touched ? lineErrors[i].branch : null}
              />
              <Select
                label={`Line ${i + 1} GL head`}
                value={l.glCode}
                onChange={(e) => update(l.key, { glCode: e.target.value })}
                placeholder="Select posting head…"
                options={postingHeads.map((h) => ({ value: h.code, label: `${h.code} — ${h.name}` }))}
                error={touched ? lineErrors[i].glCode : null}
              />
              <Select
                label={`Line ${i + 1} side`}
                value={l.side}
                onChange={(e) => update(l.key, { side: e.target.value as 'DR' | 'CR' })}
                options={[
                  { value: 'DR', label: 'DR' },
                  { value: 'CR', label: 'CR' },
                ]}
              />
              <Input
                label={`Line ${i + 1} amount`}
                numeric
                value={l.amount}
                placeholder="0.00"
                onChange={(e) => update(l.key, { amount: e.target.value.replace(/[^0-9.]/g, '') })}
                error={touched || l.amount !== '' ? lineErrors[i].amount : null}
              />
              <Input label={`Line ${i + 1} narration`} value={l.narration} onChange={(e) => update(l.key, { narration: e.target.value })} />
              <Button size="sm" variant="ghost" aria-label={`Remove line ${i + 1}`} disabled={lines.length <= 2} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}>
                ✕
              </Button>
            </fieldset>
          ))}
        </div>
        <div className="totals" style={{ marginTop: 12 }} aria-live="polite">
          <span>
            Total DR <strong className="num" data-testid="total-dr">{formatINR(fromUnits(dr))}</strong>
          </span>
          <span>
            Total CR <strong className="num" data-testid="total-cr">{formatINR(fromUnits(cr))}</strong>
          </span>
          {balanced ? (
            <Badge tone="ok">Balanced</Badge>
          ) : (
            <Badge tone="danger">{dr === 0n && cr === 0n ? 'Enter amounts' : `Out of balance by ${formatINR(fromUnits(diff < 0n ? -diff : diff))}`}</Badge>
          )}
        </div>
      </Card>
      <ErrorBanner error={propose.error} />
      <div className="form-actions">
        <Button onClick={() => navigate('/ledger/vouchers')}>Cancel</Button>
        <Button variant="primary" disabled={!balanced} loading={propose.isPending} onClick={submit}>
          Submit for approval
        </Button>
      </div>
    </div>
  );
}
