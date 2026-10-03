import { useRef } from 'react';
import { useFileDownload } from '../../api/extraHooks';
import { useMe } from '../../api/hooks';
import { nachFileContent, useGenerateNach, useNachFiles, usePendingPresentations, useSimulateNachResponse, useUploadNachResponse } from '../../api/integrationHooks';
import type { NachFile, NachPresentation } from '../../api/integrationTypes';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Banner, Button, Card, DateText, DateTimeText, EmptyState, ErrorBanner, MoneyText, PageHeader, Spinner, StatusBadge, Table, useToast } from '../../ui';

function OutcomeTable({ rows, caption }: { rows: NachPresentation[]; caption: string }) {
  return (
    <Table
      caption={caption}
      captionHidden
      columns={[
        { key: 'item', header: 'Item', render: (p) => <span className="mono">{p.itemRef}</span> },
        { key: 'loan', header: 'Loan', render: (p) => <span className="mono">{p.loanNo}</span> },
        { key: 'due', header: 'Due', render: (p) => <DateText value={p.dueDate} /> },
        { key: 'amt', header: 'Amount', numeric: true, render: (p) => <MoneyText value={p.amount} /> },
        { key: 'st', header: 'Result', render: (p) => <StatusBadge status={p.status} /> },
        { key: 'why', header: 'Bounce reason', render: (p) => (p.status === 'BOUNCED' ? (<span><span className="mono">{p.returnCode}</span> {p.returnReason}</span>) : '') },
        { key: 'next', header: 'Follow-up', render: (p) => (p.status === 'BOUNCED' ? (p.representOn ? (<span>Presented again on <DateText value={p.representOn} /></span>) : (p.representNote ?? 'Not presented again')) : p.posting === 'POSTED' ? 'Repayment posted' : (p.postingNote ?? p.posting ?? '')) },
      ]}
      rows={rows}
      rowKey={(p) => p.id ?? p.itemRef ?? ''}
      empty={<EmptyState title="No rows" />}
    />
  );
}

export function NachPage() {
  const me = useMe().data!;
  const canDownload = hasPermission(me.permissions, P.nachFile);
  const canSimulate = hasPermission(me.permissions, P.integrationSimulate);
  const files = useNachFiles('');
  const pending = usePendingPresentations();
  const generate = useGenerateNach();
  const upload = useUploadNachResponse();
  const simulate = useSimulateNachResponse();
  const download = useFileDownload();
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const result: NachFile | undefined = simulate.data ?? upload.data;
  const layouts = [...new Set((files.data ?? []).map((f) => f.format).filter(Boolean))];
  return (
    <div className="stack">
      <PageHeader
        title="NACH files"
        subtitle="Presentation files for the sponsor bank and the response files it sends back."
        actions={<Button variant="primary" loading={generate.isPending} onClick={() => generate.mutate(undefined, { onSuccess: (r) => toast({ tone: r.length ? 'success' : 'info', message: r.length ? `Presentation file ${r.map((f) => f.fileRef).join(', ')} generated with ${r.reduce((n, f) => n + (f.recordCount ?? 0), 0)} debit(s).` : 'Nothing is due for presentation.' }) })}>Generate presentation file</Button>}
      />
      <Banner tone="warn">
        <strong>These files use the built-in GENERIC layout.</strong> It is the product's own layout for testing end to end; it is not NPCI's or any bank's and no bank will accept it. Files become usable with a bank once the sponsor bank's layout is configured (tenant property <span className="mono">nach.format</span>).{layouts.length > 0 && layouts.some((l) => l !== 'GENERIC') ? ` Layouts in use: ${layouts.join(', ')}.` : ''}
      </Banner>
      <ErrorBanner error={files.error ?? generate.error ?? download.error ?? simulate.error} />
      <Card title="Files" flush>
        {files.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="NACH files"
            captionHidden
            columns={[
              { key: 'ref', header: 'File', render: (f) => (<span><span className="mono">{f.fileRef}</span><br /><span className="muted" style={{ fontSize: 12 }}>{f.direction === 'PRESENTATION' ? 'Presentation (to the bank)' : 'Response (from the bank)'}</span></span>) },
              { key: 'layout', header: 'Layout', render: (f) => (<span>{f.format === 'GENERIC' ? <Badge tone="warn">GENERIC</Badge> : <Badge>{f.format}</Badge>} <span className="muted">{f.encoding}</span></span>) },
              { key: 'settle', header: 'Settlement', render: (f) => <DateText value={f.settlementDate} /> },
              { key: 'n', header: 'Debits', numeric: true, render: (f) => f.recordCount ?? '' },
              { key: 'total', header: 'Total', numeric: true, render: (f) => (f.totalAmount ? <MoneyText value={f.totalAmount} /> : '') },
              { key: 'ok', header: 'Debited', numeric: true, render: (f) => (f.direction === 'RESPONSE' && f.successAmount ? (<span>{f.successCount} · <MoneyText value={f.successAmount} /></span>) : '') },
              { key: 'status', header: 'Status', render: (f) => (<span><StatusBadge status={f.status} />{f.error && (<><br /><span className="muted" style={{ fontSize: 12 }}>{f.error}</span></>)}{(f.summary?.skipped?.length ?? 0) > 0 && (<><br /><span className="muted" style={{ fontSize: 12 }}>Skipped: {f.summary!.skipped!.join('; ')}</span></>)}</span>) },
              { key: 'at', header: 'Created', render: (f) => (<span><DateTimeText value={f.createdAt} /> <span className="muted mono">{f.createdBy}</span></span>) },
              {
                key: 'act',
                header: <span className="sr-only">Actions</span>,
                render: (f) => (
                  <span className="row" style={{ gap: 6 }}>
                    {canDownload && <Button size="sm" aria-label={`Download ${f.fileRef}`} onClick={() => download.mutate(nachFileContent(f.id!, f.fileRef ?? 'nach'))}>Download</Button>}
                    {canSimulate && f.direction === 'PRESENTATION' && (
                      <Button size="sm" aria-label={`Simulate the bank's response to ${f.fileRef} (test only)`} loading={simulate.isPending && simulate.variables === f.id} onClick={() => (upload.reset(), simulate.mutate(f.id!))}>
                        Simulate response (test only)
                      </Button>
                    )}
                  </span>
                ),
              },
            ]}
            rows={files.data ?? []}
            rowKey={(f) => f.id ?? ''}
            empty={<EmptyState title="No NACH files yet">Generate a presentation file for the debits that are due.</EmptyState>}
          />
        )}
        {canDownload && <p className="muted" style={{ padding: '0 12px 12px', margin: 0, fontSize: 12 }}>A presentation file holds account numbers. Every download is recorded in the audit trail.</p>}
      </Card>
      <Card title="Upload the bank's response file">
        <div className="stack">
          <p className="muted" style={{ margin: 0 }}>The file is refused as a whole when its control totals do not match its rows, and when the same file was already received.</p>
          <div className="field">
            <label className="field__label" htmlFor="nach-response-file">Response file</label>
            <input
              id="nach-response-file"
              ref={input}
              type="file"
              accept=".csv,.txt,text/plain,text/csv"
              onChange={async (e) => {
                const file = e.target.files?.[0];
                if (!file) return;
                const content = await file.text();
                simulate.reset();
                upload.mutate(content, { onSettled: () => input.current && (input.current.value = '') });
              }}
            />
          </div>
          {upload.isPending && <Spinner label="Processing the file" />}
          <ErrorBanner error={upload.error} />
          {result && <ResponseResult file={result} />}
        </div>
      </Card>
      <Card title="Outcomes waiting for their posting" flush>
        <ErrorBanner error={pending.error} />
        <OutcomeTable caption="Outcomes waiting for their posting" rows={pending.data ?? []} />
      </Card>
    </div>
  );
}

function ResponseResult({ file }: { file: NachFile }) {
  const s = file.summary;
  if (file.status !== 'PROCESSED') {
    return (
      <Banner tone="danger">
        {file.status === 'DUPLICATE' ? 'Duplicate file' : 'File refused'}: {file.error ?? 'the file could not be processed'}. Nothing was changed.
      </Banner>
    );
  }
  return (
    <div className="stack" data-testid="nach-result">
      <Banner tone={s?.bounced || s?.errors?.length ? 'warn' : 'ok'}>
        {file.fileRef}: {s?.success ?? 0} debited, {s?.bounced ?? 0} bounced, {s?.alreadyRecorded ?? 0} already recorded{s?.errors?.length ? `, ${s.errors.length} row(s) in error` : ''}.
      </Banner>
      {(s?.errors?.length ?? 0) > 0 && (
        <ul aria-label="Rows in error" style={{ margin: 0 }}>
          {s!.errors!.map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      )}
      {s?.rows ? <OutcomeTable caption="Result per row" rows={s.rows} /> : <p className="muted" style={{ margin: 0 }}>Row-level results are on each loan's mandate (Mandates → open the mandate).</p>}
    </div>
  );
}
