import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useGlHeads, useMe, useProposeReversal, useVouchers } from '../../api/hooks';
import type { Voucher } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { financialYearStart } from '../../lib/dates';
import {
  Button,
  Card,
  DateText,
  Dialog,
  EmptyState,
  ErrorBanner,
  Input,
  MoneyText,
  PageHeader,
  Spinner,
  StatusBadge,
  Table,
  Textarea,
  humanize,
} from '../../ui';
import { useProposalToast } from '../proposal';

export function VouchersPage() {
  const me = useMe().data!;
  const [from, setFrom] = useState(financialYearStart(me.businessDate));
  const [to, setTo] = useState(me.businessDate);
  const q = useVouchers(from, to);
  const navigate = useNavigate();
  const [open, setOpen] = useState<Voucher | null>(null);
  return (
    <div className="stack">
      <PageHeader
        title="Vouchers"
        subtitle="Posted manual vouchers. New vouchers and reversals go through maker-checker."
        actions={hasPermission(me.permissions, P.voucherCreate) && <Button variant="primary" onClick={() => navigate('/ledger/vouchers/new')}>New voucher</Button>}
      />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Input label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          <Input label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={q.error} />
        </div>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="Vouchers"
            captionHidden
            columns={[
              { key: 'no', header: 'Voucher no.', render: (v) => <span className="mono">{v.voucherNo}</span> },
              { key: 'date', header: 'Business date', render: (v) => <DateText value={v.businessDate} /> },
              { key: 'type', header: 'Type', render: (v) => humanize(v.voucherType) },
              { key: 'desc', header: 'Description', render: (v) => v.description },
              { key: 'ref', header: 'Reference', render: (v) => v.reference ?? '' },
              { key: 'amount', header: 'Amount', numeric: true, render: (v) => <MoneyText value={v.amount} /> },
              { key: 'status', header: 'Status', render: (v) => <StatusBadge status={v.status} /> },
            ]}
            rows={q.data ?? []}
            rowKey={(v) => v.id}
            onRowClick={setOpen}
            rowLabel={(v) => `Open voucher ${v.voucherNo}`}
            empty={<EmptyState title="No vouchers in this period" />}
          />
        )}
      </Card>
      <VoucherDrawer voucher={open} onClose={() => setOpen(null)} />
    </div>
  );
}

function VoucherDrawer({ voucher, onClose }: { voucher: Voucher | null; onClose: () => void }) {
  const me = useMe().data!;
  const heads = useGlHeads();
  const reverse = useProposeReversal();
  const toast = useProposalToast();
  const [reason, setReason] = useState('');
  const [showReverse, setShowReverse] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const close = () => {
    setReason('');
    setShowReverse(false);
    setErr(null);
    reverse.reset();
    onClose();
  };
  const canReverse = voucher?.status === 'POSTED' && hasPermission(me.permissions, P.voucherReverse);
  const nameOf = (code: string) => heads.data?.find((h) => h.code === code)?.name ?? '';
  return (
    <Dialog
      open={!!voucher}
      onClose={close}
      variant="drawer"
      title={voucher ? `Voucher ${voucher.voucherNo}` : ''}
      footer={
        canReverse &&
        (showReverse ? (
          <>
            <Button onClick={() => setShowReverse(false)}>Cancel</Button>
            <Button
              variant="danger"
              loading={reverse.isPending}
              onClick={() => {
                if (!reason.trim()) {
                  setErr('A reason is required');
                  return;
                }
                reverse.mutate({ id: voucher!.id, note: reason.trim() }, { onSuccess: (a) => (toast(a, 'Reversal'), close()) });
              }}
            >
              Propose reversal
            </Button>
          </>
        ) : (
          <Button variant="danger" onClick={() => setShowReverse(true)}>
            Reverse…
          </Button>
        ))
      }
    >
      {voucher && (
        <div className="stack">
          <dl className="kv">
            <dt>Type</dt>
            <dd>{humanize(voucher.voucherType)}</dd>
            <dt>Value date</dt>
            <dd>
              <DateText value={voucher.valueDate} />
            </dd>
            <dt>Business date</dt>
            <dd>
              <DateText value={voucher.businessDate} />
            </dd>
            <dt>Description</dt>
            <dd>{voucher.description}</dd>
            <dt>Status</dt>
            <dd>
              <StatusBadge status={voucher.status} />
            </dd>
            <dt>Amount</dt>
            <dd>
              <MoneyText value={voucher.amount} />
            </dd>
          </dl>
          <Table
            caption="Voucher lines"
            columns={[
              { key: 'branch', header: 'Branch', render: (l) => l.branch },
              { key: 'gl', header: 'GL head', render: (l) => <span><span className="mono">{l.glCode}</span> {nameOf(l.glCode)}</span> },
              { key: 'dr', header: 'Debit', numeric: true, render: (l) => (l.side === 'DR' ? <MoneyText value={l.amount} /> : '') },
              { key: 'cr', header: 'Credit', numeric: true, render: (l) => (l.side === 'CR' ? <MoneyText value={l.amount} /> : '') },
            ]}
            rows={voucher.lines}
            rowKey={(l) => `${l.branch}-${l.glCode}-${l.side}-${l.amount}-${l.narration ?? ''}`}
          />
          {showReverse && (
            <Textarea label="Reason for reversal" required value={reason} error={err} maxLength={500} onChange={(e) => (setReason(e.target.value), setErr(null))} />
          )}
          <ErrorBanner error={reverse.error} />
        </div>
      )}
    </Dialog>
  );
}
