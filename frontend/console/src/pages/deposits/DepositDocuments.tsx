import { useState } from 'react';
import { depositDocument, useFileDownload, type FileRequest } from '../../api/extraHooks';
import type { DepositAccount, DepositFamily } from '../../api/types';
import { Button, Dialog, ErrorBanner, Input, useToast } from '../../ui';

/**
 * Depositor documents (P4-7): the statement of an account for a period, a deposit's receipt and the interest
 * certificate. Each PDF is fetched with the bearer token in a header and saved from a blob, never opened by URL.
 */

function useSave() {
  const download = useFileDownload();
  const toast = useToast();
  const save = (req: FileRequest, onDone?: () => void) =>
    download.mutate(req, { onSuccess: (name) => (toast({ tone: 'success', message: `Saved ${name}.` }), onDone?.()) });
  return { save, download };
}

export function StatementDialog({ family, account, businessDate, onClose }: { family: DepositFamily; account: DepositAccount; businessDate: string; onClose: () => void }) {
  const first = businessDate.slice(0, 8) + '01';
  const [from, setFrom] = useState(account.openedOn && account.openedOn > first ? account.openedOn : first);
  const [to, setTo] = useState(businessDate);
  const { save, download } = useSave();
  const range = from && to && from > to ? 'From must be on or before To' : to > businessDate ? 'Not after the business date' : null;
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Statement of ${account.accountNo}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!!range} loading={download.isPending}
            onClick={() => save(depositDocument.statement(family === 'CASA' ? 'CASA' : 'TERM_DEPOSIT', account.id!, from, to), onClose)}>
            Download PDF
          </Button>
        </>
      }
    >
      <p style={{ marginTop: 0 }} className="muted">Every movement in the period with the running balance, at most two years at a time.</p>
      <div className="form-grid">
        <Input label="Statement from" type="date" max={businessDate} value={from} onChange={(e) => setFrom(e.target.value)} error={range?.startsWith('From') ? range : null} />
        <Input label="Statement to" type="date" max={businessDate} value={to} onChange={(e) => setTo(e.target.value)} error={range && !range.startsWith('From') ? range : null} />
      </div>
      <ErrorBanner error={download.error} />
    </Dialog>
  );
}

export function ReceiptButton({ account }: { account: DepositAccount }) {
  const { save, download } = useSave();
  return (
    <>
      <Button loading={download.isPending} onClick={() => save(depositDocument.receipt(account.id!))}>Receipt</Button>
      {download.error ? <ErrorBanner error={download.error} /> : null}
    </>
  );
}

export function InterestCertificateButton({ customerId, taxYear }: { customerId: string; taxYear: number }) {
  const { save, download } = useSave();
  return (
    <>
      <Button loading={download.isPending} onClick={() => save(depositDocument.interestCertificate(customerId, taxYear))}>Interest certificate (PDF)</Button>
      {download.error ? <ErrorBanner error={download.error} /> : null}
    </>
  );
}
