import { useState } from 'react';
import { useMe } from '../../api/hooks';
import { usePincodeLookup, useStates, useUploadTerritory } from '../../api/masterHooks';
import { P, hasPermission } from '../../auth/permissions';
import { PINCODE_PATTERN } from '../../lib/mask';
import { Badge, Button, Card, CsvUpload, EmptyState, ErrorBanner, Input, PageHeader, Spinner, Table } from '../../ui';
import { useProposalToast } from '../proposal';

export const TERRITORY_COLUMNS = ['state', 'district', 'city', 'pincode'];

export function TerritoryPage() {
  const me = useMe().data!;
  const canPropose = hasPermission(me.permissions, P.masterPropose);
  const states = useStates();
  const upload = useUploadTerritory();
  const toast = useProposalToast();
  return (
    <div className="stack">
      <PageHeader title="Territory" subtitle="States with GST state codes, and the cities and districts each pincode serves." />
      <PincodeLookup />
      {canPropose && (
        <Card title="Load a territory file">
          <CsvUpload
            label="Territory file"
            columns={TERRITORY_COLUMNS}
            exampleRows={[['KL', 'Ernakulam', 'Aluva', '683101']]}
            templateName="territory-template.csv"
            maxRows={20_000}
            hint="state is the code (KL), name or GST code; the whole file loads or nothing does"
            mutation={upload}
            onUploaded={(a, rows) => toast(a, `Territory file (${rows} rows)`)}
          />
        </Card>
      )}
      <Card title="States and union territories" flush>
        <ErrorBanner error={states.error} />
        {states.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption="States and union territories"
            captionHidden
            columns={[
              { key: 'code', header: 'Code', render: (s) => <span className="mono">{s.code}</span> },
              { key: 'name', header: 'Name', render: (s) => (<span>{s.name} {s.unionTerritory && <Badge tone="info">UT</Badge>}</span>) },
              { key: 'gst', header: 'GST state code', render: (s) => <span className="mono">{s.gstStateCode}</span> },
            ]}
            rows={states.data ?? []}
            rowKey={(s) => s.code ?? ''}
            empty={<EmptyState title="No states loaded" />}
          />
        )}
      </Card>
    </div>
  );
}

function PincodeLookup() {
  const [draft, setDraft] = useState('');
  const [pincode, setPincode] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const q = usePincodeLookup(pincode);
  return (
    <Card title="Pincode lookup">
      <form
        role="search"
        className="filters"
        style={{ marginBottom: 0 }}
        onSubmit={(e) => {
          e.preventDefault();
          const v = draft.trim();
          if (!PINCODE_PATTERN.test(v)) {
            setErr('6 digits, not starting with 0');
            return;
          }
          setErr(null);
          setPincode(v);
        }}
      >
        <Input label="Pincode" inputMode="numeric" maxLength={6} className="mono" value={draft} onChange={(e) => setDraft(e.target.value.replace(/\D/g, ''))} error={err} />
        <Button type="submit" variant="primary">
          Look up
        </Button>
      </form>
      {pincode && (
        <div style={{ marginTop: 12 }}>
          {q.isLoading ? (
            <Spinner />
          ) : q.error ? (
            <ErrorBanner error={q.error} />
          ) : (
            <Table
              caption={`Places served by ${pincode}`}
              columns={[
                { key: 'city', header: 'City', render: (p) => p.city },
                { key: 'district', header: 'District', render: (p) => p.district },
                { key: 'state', header: 'State', render: (p) => `${p.stateName} (${p.stateCode})` },
                { key: 'gst', header: 'GST state code', render: (p) => <span className="mono">{p.gstStateCode}</span> },
              ]}
              rows={q.data ?? []}
              rowKey={(p) => `${p.pincode}-${p.city}`}
            />
          )}
        </div>
      )}
    </Card>
  );
}
