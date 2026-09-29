import type { LoanKfs } from '../../api/types';
import { addMoney, isMoney } from '../../lib/money';
import { Card, DateText, EmptyState, MoneyText, Table, humanize } from '../../ui';
import { REPAYMENT_METHOD_LABEL, Stat, pct } from './common';

const sum = (xs: Array<string | undefined>) => addMoney('0', ...xs.filter((x): x is string => !!x && isMoney(x)));

/** Key Fact Statement figures: summary, fees with the GST split, and the repayment schedule. */
export function KfsView({ kfs, title = 'Key Fact Statement' }: { kfs: LoanKfs; title?: string }) {
  const fees = kfs.fees ?? [];
  const intra = fees.some((f) => f.cgst && Number(f.cgst) > 0);
  return (
    <div className="stack" data-testid="kfs">
      <Card title={title}>
        <div className="stat-grid">
          <Stat label="Loan amount">
            <MoneyText value={kfs.amount} />
          </Stat>
          <Stat label="Interest rate">{pct(kfs.interestRate)} p.a.</Stat>
          <Stat label={kfs.emi ? 'EMI' : 'Instalment'} testId="kfs-emi">
            {kfs.emi ? <MoneyText value={kfs.emi} /> : <span className="muted">Varies</span>}
          </Stat>
          <Stat label="Instalments">
            {kfs.instalments} over {kfs.tenorMonths} months
          </Stat>
          <Stat label="APR" testId="kfs-apr">
            {pct(kfs.apr)}
          </Stat>
          <Stat label="Total interest">
            <MoneyText value={kfs.totalInterest} />
          </Stat>
          <Stat label="Total repayable">
            <MoneyText value={kfs.totalRepayable} />
          </Stat>
          <Stat label="Net disbursal" testId="kfs-net">
            <MoneyText value={kfs.netDisbursal} />
          </Stat>
        </div>
        <dl className="kv" style={{ marginTop: 16 }}>
          <dt>Product</dt>
          <dd>
            {kfs.productName} <span className="mono muted">{kfs.productCode} v{kfs.productVersion}</span>
          </dd>
          <dt>Repayment</dt>
          <dd>{kfs.repaymentMethod ? REPAYMENT_METHOD_LABEL[kfs.repaymentMethod as keyof typeof REPAYMENT_METHOD_LABEL] ?? kfs.repaymentMethod : '—'}</dd>
          <dt>Rate</dt>
          <dd>
            {humanize(kfs.rateType ?? 'FIXED')} · {kfs.rateExplanation}
          </dd>
          <dt>Penal charges</dt>
          <dd>
            {kfs.penalChargeRate ? `${pct(kfs.penalChargeRate)} p.a. on overdue. ` : ''}
            <span className="muted">{kfs.penalChargeNote}</span>
          </dd>
          <dt>Cooling-off</dt>
          <dd>{kfs.coolingOffDays ?? 0} day(s): the borrower may exit by repaying principal and interest for the days used.</dd>
          <dt>Place of supply</dt>
          <dd>
            GST state <span className="mono">{kfs.placeOfSupply}</span> ({intra ? 'intra-state: CGST + SGST' : fees.length ? 'inter-state: IGST' : 'no fees'})
          </dd>
        </dl>
      </Card>
      <Card title="Fees and GST" flush>
        <Table
          caption="Fees and GST"
          captionHidden
          columns={[
            { key: 'name', header: 'Fee', render: (f) => (<span>{f.name} <span className="mono muted">{f.code}</span></span>) },
            { key: 'fee', header: 'Fee', numeric: true, render: (f) => <MoneyText value={f.fee} /> },
            { key: 'cgst', header: 'CGST', numeric: true, render: (f) => <MoneyText value={f.cgst} /> },
            { key: 'sgst', header: 'SGST', numeric: true, render: (f) => <MoneyText value={f.sgst} /> },
            { key: 'igst', header: 'IGST', numeric: true, render: (f) => <MoneyText value={f.igst} /> },
            { key: 'total', header: 'Total', numeric: true, render: (f) => <MoneyText value={f.total} /> },
          ]}
          rows={fees}
          rowKey={(f) => f.code ?? ''}
          empty={<EmptyState title="No upfront fees" />}
          footer={
            fees.length > 0 && (
              <tr>
                <td>Total</td>
                <td className="num"><MoneyText value={sum(fees.map((f) => f.fee))} /></td>
                <td className="num"><MoneyText value={sum(fees.map((f) => f.cgst))} /></td>
                <td className="num"><MoneyText value={sum(fees.map((f) => f.sgst))} /></td>
                <td className="num"><MoneyText value={sum(fees.map((f) => f.igst))} /></td>
                <td className="num" data-testid="fees-total"><MoneyText value={sum(fees.map((f) => f.total))} /></td>
              </tr>
            )
          }
        />
      </Card>
      <Card title="Repayment schedule" flush>
        <ScheduleTable rows={kfs.schedule ?? []} caption="Repayment schedule" />
      </Card>
    </div>
  );
}

export function ScheduleTable({ rows, caption }: { rows: NonNullable<LoanKfs['schedule']>; caption: string }) {
  return (
    <Table
      caption={caption}
      captionHidden
      columns={[
        { key: 'no', header: '#', numeric: true, render: (r) => r.instalmentNo },
        { key: 'due', header: 'Due date', render: (r) => <DateText value={r.dueDate} /> },
        { key: 'days', header: 'Days', numeric: true, render: (r) => r.days },
        { key: 'open', header: 'Opening', numeric: true, render: (r) => <MoneyText value={r.openingBalance} /> },
        { key: 'int', header: 'Interest', numeric: true, render: (r) => <MoneyText value={r.interest} /> },
        { key: 'prin', header: 'Principal', numeric: true, render: (r) => <MoneyText value={r.principal} /> },
        { key: 'inst', header: 'Instalment', numeric: true, render: (r) => <MoneyText value={r.instalment} /> },
        { key: 'close', header: 'Closing', numeric: true, render: (r) => <MoneyText value={r.closingBalance} /> },
      ]}
      rows={rows}
      rowKey={(r) => `${r.instalmentNo}-${r.dueDate}`}
      empty={<EmptyState title="No instalments" />}
    />
  );
}
