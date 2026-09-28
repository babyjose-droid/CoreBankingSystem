import { useState } from 'react';
import { useMe, useProfitAndLoss } from '../../api/hooks';
import { downloadCsv } from '../../lib/csv';
import { financialYearStart } from '../../lib/dates';
import { fromUnits, toUnits } from '../../lib/money';
import { Button, Card, ErrorBanner, Input, MoneyText, PageHeader, Spinner } from '../../ui';
import { StatementTable, sectionize, statementCsvRows } from './statements';

export function ProfitLossPage() {
  const me = useMe().data!;
  const [from, setFrom] = useState(financialYearStart(me.businessDate));
  const [to, setTo] = useState(me.businessDate);
  const q = useProfitAndLoss(from, to);
  const rows = q.data ?? [];
  const sections = sectionize(rows, [
    ['INCOME', 'Income'],
    ['EXPENSE', 'Expenses'],
  ]);
  const netRow = rows.find((r) => r.section === 'NET_PROFIT');
  const net = netRow ? netRow.amount : fromUnits(sections[0].total - sections[1].total);
  const netLabel = toUnits(net) >= 0n ? 'Net profit' : 'Net loss';
  return (
    <div className="stack">
      <PageHeader
        title="Profit and loss"
        actions={
          <Button disabled={rows.length === 0} onClick={() => downloadCsv(`profit-and-loss_${from}_${to}.csv`, ['Section', 'GL code', 'Head', 'Amount'], statementCsvRows(sections, [[netLabel, net]]))}>
            Download CSV
          </Button>
        }
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
          <StatementTable
            caption={`Profit and loss ${from} to ${to}`}
            sections={sections}
            extra={
              <tr>
                <td />
                <td>{netLabel}</td>
                <td className="num" data-testid="net-profit">
                  <MoneyText value={net} signed />
                </td>
              </tr>
            }
          />
        )}
      </Card>
    </div>
  );
}
