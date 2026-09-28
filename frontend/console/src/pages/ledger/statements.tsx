import { Fragment } from 'react';
import type { StatementRow } from '../../api/types';
import { fromUnits, toUnits } from '../../lib/money';
import { MoneyText, humanize } from '../../ui';

export interface Section {
  key: string;
  label: string;
  rows: StatementRow[];
  total: bigint;
}

export function sectionize(rows: StatementRow[], order: Array<[string, string]>): Section[] {
  return order.map(([key, label]) => {
    const rs = rows.filter((r) => r.section === key);
    return { key, label, rows: rs, total: rs.reduce((a, r) => a + toUnits(r.amount), 0n) };
  });
}

export function StatementTable({ caption, sections, extra }: { caption: string; sections: Section[]; extra?: React.ReactNode }) {
  return (
    <div className="table-wrap">
      <table className="table">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">GL code</th>
            <th scope="col">Head</th>
            <th scope="col" className="num">
              Amount
            </th>
          </tr>
        </thead>
        <tbody>
          {sections.map((s) => (
            <Fragment key={s.key}>
              <tr className="row-section">
                <td colSpan={3}>{s.label}</td>
              </tr>
              {s.rows.length === 0 && (
                <tr>
                  <td colSpan={3} className="muted">
                    No balances
                  </td>
                </tr>
              )}
              {s.rows.map((r) => (
                <tr key={s.key + r.glCode}>
                  <td className="mono">{r.glCode}</td>
                  <td>{r.glName}</td>
                  <td className="num">
                    <MoneyText value={r.amount} signed />
                  </td>
                </tr>
              ))}
              <tr className="row-subtotal">
                <td />
                <td>Total {s.label.toLowerCase()}</td>
                <td className="num" data-testid={`subtotal-${s.key}`}>
                  <MoneyText value={fromUnits(s.total)} signed />
                </td>
              </tr>
            </Fragment>
          ))}
        </tbody>
        {extra && <tfoot>{extra}</tfoot>}
      </table>
    </div>
  );
}

export function statementCsvRows(sections: Section[], trailer: Array<[string, string]>): Array<Array<string>> {
  const out: string[][] = [];
  for (const s of sections) {
    for (const r of s.rows) out.push([humanize(s.key), r.glCode, r.glName, r.amount]);
    out.push([humanize(s.key), '', `Total ${s.label.toLowerCase()}`, fromUnits(s.total)]);
  }
  for (const [label, amount] of trailer) out.push(['', '', label, amount]);
  return out;
}
