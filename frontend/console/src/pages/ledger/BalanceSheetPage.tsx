import { useState } from 'react';
import { useBalanceSheet, useMe } from '../../api/hooks';
import { downloadCsv } from '../../lib/csv';
import { formatINR, fromUnits, toUnits } from '../../lib/money';
import { Banner, Button, Card, ErrorBanner, Input, MoneyText, PageHeader, Spinner } from '../../ui';
import { StatementTable, sectionize, statementCsvRows } from './statements';

export function BalanceSheetPage() {
  const me = useMe().data!;
  const [asOf, setAsOf] = useState(me.businessDate);
  const q = useBalanceSheet(asOf);
  const rows = q.data ?? [];
  const sections = sectionize(rows, [
    ['ASSET', 'Assets'],
    ['LIABILITY', 'Liabilities'],
    ['EQUITY', 'Equity'],
  ]);
  const np = toUnits(rows.find((r) => r.section === 'NET_PROFIT')?.amount ?? '0');
  const [assets, liabilities, equity] = sections.map((s) => s.total);
  const liabPlusEquity = liabilities + equity + np;
  const diff = assets - liabPlusEquity;
  return (
    <div className="stack">
      <PageHeader
        title="Balance sheet"
        actions={
          <Button
            disabled={rows.length === 0}
            onClick={() =>
              downloadCsv(
                `balance-sheet_${asOf}.csv`,
                ['Section', 'GL code', 'Head', 'Amount'],
                statementCsvRows(sections, [
                  ['Profit and loss account (current period)', fromUnits(np)],
                  ['Total liabilities and equity', fromUnits(liabPlusEquity)],
                ]),
              )
            }
          >
            Download CSV
          </Button>
        }
      />
      <Card flush>
        <div className="filters" style={{ padding: 12, marginBottom: 0 }}>
          <Input label="As of" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          {!q.isLoading && rows.length > 0 &&
            (diff === 0n ? (
              <Banner tone="ok">✓ Assets = Liabilities + Equity</Banner>
            ) : (
              <Banner tone="danger">✕ Difference {formatINR(fromUnits(diff))}</Banner>
            ))}
        </div>
        <div style={{ padding: '0 12px' }}>
          <ErrorBanner error={q.error} />
        </div>
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <StatementTable
            caption={`Balance sheet as of ${asOf}`}
            sections={sections}
            extra={
              <>
                <tr>
                  <td />
                  <td>Profit and loss account (current period)</td>
                  <td className="num">
                    <MoneyText value={fromUnits(np)} signed />
                  </td>
                </tr>
                <tr>
                  <td />
                  <td>Total liabilities and equity</td>
                  <td className="num">
                    <MoneyText value={fromUnits(liabPlusEquity)} />
                  </td>
                </tr>
              </>
            }
          />
        )}
      </Card>
    </div>
  );
}
