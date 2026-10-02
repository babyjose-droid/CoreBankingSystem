import { useRef, useState } from 'react';
import { KYC_CONTENT_TYPES, KYC_MAX_BYTES, kycFile, useFileDownload, useKycDocuments, useRejectKycDocument, useUploadKycDocument, useVerifyKycDocument, type KycContentType } from '../../api/extraHooks';
import { useEnumeration, useMe } from '../../api/hooks';
import type { CustomerSummary, KycDocument } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, Checkbox, DateText, DateTimeText, Dialog, EmptyState, ErrorBanner, Field, Input, Masked, Select, Spinner, StatusBadge, Table, Textarea, humanize, useToast } from '../../ui';

const FULL_AADHAAR = /^\d{4}\s?\d{4}\s?\d{4}$/;

/** Client-side checks before a KYC file is sent: PDF, JPEG or PNG, at most 5 MB. Returns an error message or null. */
export function kycFileProblem(file: Pick<File, 'type' | 'size'> | null | undefined): string | null {
  if (!file) return 'Choose a file';
  if (!KYC_CONTENT_TYPES.includes(file.type as KycContentType)) return 'The file must be a PDF, JPEG or PNG';
  if (file.size > KYC_MAX_BYTES) return `The file is ${(file.size / 1024 / 1024).toFixed(1)} MB; the limit is 5 MB`;
  if (file.size === 0) return 'The file is empty';
  return null;
}

/** Document-number rules that protect the customer: a full Aadhaar number is never sent. */
export function documentNumberProblem(docType: string, number: string): string | null {
  const n = number.trim();
  if (FULL_AADHAAR.test(n)) return 'Never enter a full Aadhaar number; give only its last four digits';
  if (docType === 'AADHAAR_MASKED' && n && !/^\d{4}$/.test(n)) return 'For Aadhaar enter only the last four digits';
  if (n.length > 40) return 'At most 40 characters';
  return null;
}

export function KycTab({ customer }: { customer: CustomerSummary }) {
  const me = useMe().data!;
  const can = (p: string) => hasPermission(me.permissions, p);
  const q = useKycDocuments(customer.id);
  const download = useFileDownload();
  const toast = useToast();
  const [uploading, setUploading] = useState(false);
  const [deciding, setDeciding] = useState<{ doc: KycDocument; verify: boolean } | null>(null);
  return (
    <div className="stack">
      <Card
        title="KYC documents"
        flush
        actions={can(P.kycUpload) && <Button variant="primary" onClick={() => setUploading(true)}>Upload document</Button>}
      >
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={q.error ?? download.error} />
        </div>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="KYC documents"
            captionHidden
            columns={[
              { key: 'type', header: 'Document', render: (d) => humanize(d.docType) },
              { key: 'no', header: 'Number', render: (d) => <Masked value={d.numberMasked} kind="account" /> },
              { key: 'expiry', header: 'Expiry', render: (d) => (<span><DateText value={d.expiryDate} /> {d.expired && <Badge tone="danger">Expired</Badge>}</span>) },
              { key: 'status', header: 'Status', render: (d) => (<span><StatusBadge status={d.status} />{d.statusReason && <span className="muted"> {d.statusReason}</span>}</span>) },
              { key: 'by', header: 'Uploaded', render: (d) => (<span><span className="mono">{d.uploadedBy}</span> · <DateTimeText value={d.uploadedAt} /></span>) },
              { key: 'checked', header: 'Checked by', render: (d) => <span className="mono">{d.verifiedBy ?? '—'}</span> },
              {
                key: 'act',
                header: <span className="sr-only">Actions</span>,
                render: (d) => (
                  <span className="row" style={{ gap: 4 }}>
                    {can(P.kycViewDocument) && (
                      <Button size="sm" variant="ghost" aria-label={`Open ${humanize(d.docType)} file`} onClick={() => download.mutate(kycFile(customer.id, d.id), { onSuccess: (name) => toast({ tone: 'success', message: `Saved ${name}. This download is audited.` }) })}>
                        Open file
                      </Button>
                    )}
                    {can(P.kycVerify) && d.status === 'PENDING' && (
                      <>
                        <Button size="sm" aria-label={`Verify ${humanize(d.docType)}`} onClick={() => setDeciding({ doc: d, verify: true })}>
                          Verify
                        </Button>
                        <Button size="sm" variant="danger" aria-label={`Reject ${humanize(d.docType)}`} onClick={() => setDeciding({ doc: d, verify: false })}>
                          Reject
                        </Button>
                      </>
                    )}
                  </span>
                ),
              },
            ]}
            rows={q.data ?? []}
            rowKey={(d) => d.id}
            empty={<EmptyState title="No KYC documents on file" />}
          />
        )}
      </Card>
      <p className="muted" style={{ margin: 0, fontSize: 12 }}>Document numbers are never stored or shown in full: only the last four characters are kept. Opening a file is written to the audit log.</p>
      {uploading && <UploadDialog customer={customer} onClose={() => setUploading(false)} />}
      {deciding && <DecideDialog customer={customer} doc={deciding.doc} verify={deciding.verify} onClose={() => setDeciding(null)} />}
    </div>
  );
}

function UploadDialog({ customer, onClose }: { customer: CustomerSummary; onClose: () => void }) {
  const me = useMe().data!;
  const types = useEnumeration('kyc-document-type');
  const upload = useUploadKycDocument(customer.id);
  const toast = useToast();
  const [docType, setDocType] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [issueDate, setIssueDate] = useState('');
  const [expiryDate, setExpiryDate] = useState('');
  const [errors, setErrors] = useState<Record<string, string | null>>({});
  // The document number is deliberately NOT React state: it is read from the input at submit and wiped right after,
  // so it never sits in component state, the query cache or dev tools.
  const numberRef = useRef<HTMLInputElement>(null);
  const submit = () => {
    const number = numberRef.current?.value ?? '';
    const e = {
      docType: !docType ? 'Select the document type' : null,
      file: kycFileProblem(file),
      number: documentNumberProblem(docType, number),
      issueDate: issueDate && issueDate > me.businessDate ? 'Cannot be in the future' : null,
      expiryDate: expiryDate && issueDate && expiryDate <= issueDate ? 'Must be after the issue date' : null,
    };
    setErrors(e);
    if (Object.values(e).some(Boolean) || !file) return;
    upload.mutate(
      { file, docType, issueDate, expiryDate, documentNumber: number.trim() || undefined },
      {
        onSuccess: (d) => {
          toast({ tone: 'success', message: `${humanize(d.docType)} uploaded; it is pending verification by another user.` });
          onClose();
        },
        onSettled: () => {
          if (numberRef.current) numberRef.current.value = '';
        },
      },
    );
  };
  const aadhaar = docType === 'AADHAAR_MASKED';
  return (
    <Dialog
      open
      onClose={onClose}
      title="Upload KYC document"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={upload.isPending} onClick={submit}>
            Upload
          </Button>
        </>
      }
    >
      <div className="stack">
        <Select
          label="Document type"
          required
          value={docType}
          placeholder="Select…"
          onChange={(e) => setDocType(e.target.value)}
          options={(types.data ?? []).filter((t) => t.active !== false).map((t) => ({ value: t.code, label: t.label }))}
          error={errors.docType}
        />
        {aadhaar && <Banner tone="warn">Upload only a masked Aadhaar copy (first eight digits hidden) and enter only the last four digits.</Banner>}
        <Input
          label="File"
          required
          type="file"
          accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
          hint="PDF, JPEG or PNG, up to 5 MB"
          onChange={(e) => {
            const f = e.target.files?.[0] ?? null;
            setFile(f);
            setErrors((x) => ({ ...x, file: f ? kycFileProblem(f) : null }));
          }}
          error={errors.file}
        />
        <Field label={aadhaar ? 'Last four digits' : 'Document number'} hint="Sent once with the file; only the last four characters are kept" error={errors.number}>
          {({ id, describedBy, invalid }) => (
            // Uncontrolled on purpose (see numberRef).
            <input id={id} ref={numberRef} className="input mono" autoComplete="off" spellCheck={false} maxLength={40} aria-describedby={describedBy} aria-invalid={invalid || undefined} />
          )}
        </Field>
        <div className="form-grid">
          <Input label="Issue date" type="date" max={me.businessDate} value={issueDate} onChange={(e) => setIssueDate(e.target.value)} error={errors.issueDate} />
          <Input label="Expiry date" type="date" value={expiryDate} onChange={(e) => setExpiryDate(e.target.value)} error={errors.expiryDate} />
        </div>
        <ErrorBanner error={upload.error} />
      </div>
    </Dialog>
  );
}

function DecideDialog({ customer, doc, verify, onClose }: { customer: CustomerSummary; doc: KycDocument; verify: boolean; onClose: () => void }) {
  const verifyM = useVerifyKycDocument(customer.id);
  const rejectM = useRejectKycDocument(customer.id);
  const toast = useToast();
  const [note, setNote] = useState('');
  const [masking, setMasking] = useState(false);
  const [touched, setTouched] = useState(false);
  const aadhaar = doc.docType === 'AADHAAR_MASKED';
  const m = verify ? verifyM : rejectM;
  const done = (d: KycDocument) => {
    toast({ tone: 'success', message: `${humanize(doc.docType)} ${verify ? 'verified' : 'rejected'}.${d.customerKycStatus ? ` Customer KYC is ${d.customerKycStatus.toLowerCase()}.` : ''}` });
    onClose();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={`${verify ? 'Verify' : 'Reject'} ${humanize(doc.docType)}`}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant={verify ? 'primary' : 'danger'}
            loading={m.isPending}
            onClick={() => {
              setTouched(true);
              if (verify) {
                if (aadhaar && !masking) return;
                verifyM.mutate({ docId: doc.id, maskingConfirmed: aadhaar ? true : undefined, note: note.trim() || undefined }, { onSuccess: done });
              } else {
                if (!note.trim()) return;
                rejectM.mutate({ docId: doc.id, reason: note.trim() }, { onSuccess: done });
              }
            }}
          >
            {verify ? 'Verify document' : 'Reject document'}
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted" style={{ margin: 0 }}>
          Uploaded by <span className="mono">{doc.uploadedBy}</span>. A document must be checked by someone other than its uploader.
        </p>
        {verify && aadhaar && (
          <div className="field">
            <Checkbox label="I confirm the copy shows only the last four Aadhaar digits" checked={masking} onChange={(e) => setMasking(e.target.checked)} />
            {touched && !masking && (
              <span className="field__error" role="alert">
                Confirm the masking to verify an Aadhaar copy
              </span>
            )}
          </div>
        )}
        <Textarea label={verify ? 'Note' : 'Reason'} required={!verify} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} error={!verify && touched && !note.trim() ? 'A reason is required' : null} />
        <ErrorBanner error={m.error} />
      </div>
    </Dialog>
  );
}
