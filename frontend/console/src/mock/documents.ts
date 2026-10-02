/**
 * Mock API for loan documents (tiny but valid PDFs), the report catalogue and runs, and the dashboard.
 */
import type { Dashboard, DpdBucket, ReportDefinition, ReportRun } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { toCsv, type CsvCell } from '../lib/csv';
import { formatDate, ISO_DATE } from '../lib/dates';
import { appendAudit, uuid, type MockDb, type StoredLoan } from './db';
import { arrearsOf, customerOf, displayName, principalOutstanding } from './lending';
import * as C from './lendingCalc';
import { branchScope } from './platform';
import { bad, conflict, HttpProblem, notFound, PROBLEM_BASE } from './problems';

type Raw = { contentType: string; data: Uint8Array | string; fileName: string };
type Result = { status: number; body: unknown; raw?: Raw };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
}
export interface DocumentsRouter {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  nowIso: () => string;
}

// ------------------------------------------------------------------ PDF
/** A one-page PDF (Helvetica, ASCII) with a title and lines of text: small, but valid for any PDF reader. */
export function tinyPdf(title: string, lines: string[]): Uint8Array {
  const esc = (s: string) => s.replace(/₹/g, 'Rs ').replace(/[^\x20-\x7e]/g, '?').replace(/([\\()])/g, '\\$1');
  const text = [`BT /F1 16 Tf 50 790 Td (${esc(title)}) Tj ET`, ...lines.slice(0, 58).map((l, i) => `BT /F1 10 Tf 50 ${764 - i * 12} Td (${esc(l)}) Tj ET`)].join('\n');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 5 0 R /Resources << /Font << /F1 4 0 R >> >> >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${text.length} >>\nstream\n${text}\nendstream`,
  ];
  let out = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((o, i) => {
    offsets.push(out.length);
    out += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return new TextEncoder().encode(out);
}

const inr = (p: number) => `Rs ${C.fromPaise(p)}`;

// ------------------------------------------------------------------ reports
interface ReportSpec extends ReportDefinition {
  run: (db: MockDb, loans: StoredLoan[], params: Record<string, string>) => { headers: string[]; rows: CsvCell[][] };
}

const date = (title: string, description?: string) => ({ type: 'string', format: 'date', title, ...(description ? { description } : {}) });
const branchParam = { type: 'string', title: 'Branch', description: 'Leave empty for all your branches' };
const productParam = { type: 'string', title: 'Product', description: 'Leave empty for all products' };

function events(loans: StoredLoan[], types: string[], from: string, to: string) {
  return loans.flatMap((l) => l.events.filter((e) => types.includes(e.type) && !e.reversedBy && e.valueDate >= from && e.valueDate <= to).map((e) => ({ l, e })));
}

function bucketOf(dpd: number): DpdBucket['bucket'] {
  if (dpd <= 0) return '0';
  if (dpd <= 30) return '1-30';
  if (dpd <= 60) return '31-60';
  if (dpd <= 90) return '61-90';
  if (dpd <= 180) return '91-180';
  if (dpd <= 365) return '181-365';
  return '>365';
}
const BUCKETS: DpdBucket['bucket'][] = ['0', '1-30', '31-60', '61-90', '91-180', '181-365', '>365'];
const isLive = (l: StoredLoan) => !!l.disbursedOn && !l.state.closedOn;
const NPA = ['SUBSTANDARD', 'DOUBTFUL1', 'DOUBTFUL2', 'DOUBTFUL3', 'LOSS'];

function reportSpecs(db: MockDb): ReportSpec[] {
  const name = (l: StoredLoan) => {
    const c = customerOf(db, l);
    return c ? displayName(c) : '';
  };
  return [
    {
      code: 'LOAN_BOOK', name: 'Loan book', description: 'Every live loan with outstanding, overdue, DPD and asset class as of the business date.',
      permission: P.reportRun, outputFormat: 'CSV', containsPersonalData: false, allBranchesOnly: false, schedule: null,
      parameters: { type: 'object', properties: { branch: branchParam, productCode: productParam }, required: [] },
      run: (_db, loans, p) => ({
        headers: ['loanNo', 'customerNo', 'customerName', 'productCode', 'branch', 'status', 'amount', 'principalOutstanding', 'overdue', 'dpd', 'assetClass'],
        rows: loans.filter((l) => isLive(l) && (!p.productCode || l.product.code === p.productCode)).map((l) => [
          l.loanNo, customerOf(db, l)?.customerNo, name(l), l.product.code, l.branch, l.state.status, C.fromPaise(l.amount), C.fromPaise(principalOutstanding(l)), C.fromPaise(arrearsOf(l.state)), l.state.dpd, l.state.assetClass,
        ]),
      }),
    },
    {
      code: 'DPD_AGEING', name: 'DPD ageing', description: 'Live loans and principal outstanding by days-past-due bucket.',
      permission: P.reportRun, outputFormat: 'CSV', containsPersonalData: false, allBranchesOnly: false, schedule: '0 0 6 * * *',
      parameters: { type: 'object', properties: { branch: branchParam }, required: [] },
      run: (_db, loans) => ({
        headers: ['bucket', 'loans', 'principalOutstanding'],
        rows: BUCKETS.map((b) => {
          const ls = loans.filter((l) => isLive(l) && bucketOf(l.state.dpd) === b);
          return [b, ls.length, C.fromPaise(ls.reduce((s, l) => s + principalOutstanding(l), 0))];
        }),
      }),
    },
    {
      code: 'COLLECTIONS', name: 'Collections', description: 'Receipts (repayments, part-prepayments, pre-closures) in a period.',
      permission: P.reportRun, outputFormat: 'CSV', containsPersonalData: false, allBranchesOnly: false, schedule: null,
      parameters: { type: 'object', properties: { from: date('From'), to: date('To', 'Not after the business date'), branch: branchParam, mode: { type: 'string', title: 'Mode', enum: ['CASH', 'NACH', 'UPI', 'NEFT'] } }, required: ['from', 'to'] },
      run: (_db, loans, p) => ({
        headers: ['valueDate', 'loanNo', 'customerName', 'branch', 'type', 'mode', 'amount'],
        rows: events(loans, ['REPAYMENT', 'PREPAYMENT', 'PRECLOSURE', 'CANCELLATION'], p.from, p.to)
          .filter(({ e }) => !p.mode || e.data.mode === p.mode)
          .sort((a, b) => a.e.valueDate.localeCompare(b.e.valueDate))
          .map(({ l, e }) => [e.valueDate, l.loanNo, name(l), l.branch, e.type, e.data.mode ?? '', C.fromPaise(e.amount ?? 0)]),
      }),
    },
    {
      code: 'DISBURSEMENTS', name: 'Disbursements', description: 'Loans disbursed in a period, with the net amount paid out.',
      permission: P.reportRun, outputFormat: 'CSV', containsPersonalData: false, allBranchesOnly: false, schedule: null,
      parameters: { type: 'object', properties: { from: date('From'), to: date('To'), branch: branchParam, productCode: productParam }, required: ['from', 'to'] },
      run: (_db, loans, p) => ({
        headers: ['disbursedOn', 'loanNo', 'customerName', 'productCode', 'branch', 'amount', 'netDisbursed'],
        rows: loans.filter((l) => l.disbursedOn && l.disbursedOn >= p.from && l.disbursedOn <= p.to && (!p.productCode || l.product.code === p.productCode))
          .sort((a, b) => a.disbursedOn!.localeCompare(b.disbursedOn!))
          .map((l) => [l.disbursedOn, l.loanNo, name(l), l.product.code, l.branch, C.fromPaise(l.amount), C.fromPaise(l.netDisbursed ?? 0)]),
      }),
    },
    {
      code: 'BUREAU_CONSUMER', name: 'Credit bureau file (consumer)', description: 'UCRF-style monthly consumer file for the credit bureaus. Contains unmasked personal data.',
      permission: P.bureauExport, outputFormat: 'UCRF', containsPersonalData: true, allBranchesOnly: true, schedule: null,
      parameters: { type: 'object', properties: { asOf: date('Reporting date', 'Usually the last day of the month') }, required: ['asOf'] },
      run: () => ({ headers: [], rows: [] }),
    },
  ];
}

function bureauFile(db: MockDb, loans: StoredLoan[], asOf: string) {
  const member = db.systemProperties.find((p) => p.key === 'bureau.member-code')?.value?.trim();
  if (!member) throw conflict('Bureau member code is not configured', 'Set the system property bureau.member-code before producing a bureau file');
  const lines = [`HDR|${member}|${asOf.replace(/-/g, '')}|UCRF-MOCK`];
  const rejected: CsvCell[][] = [];
  let accounts = 0;
  for (const l of loans) {
    const c = customerOf(db, l);
    if (!l.disbursedOn) rejected.push([l.loanNo, 'Not disbursed']);
    else if (!c?.input.pan) rejected.push([l.loanNo, 'Borrower has no PAN']);
    else {
      accounts += 1;
      lines.push(['ACC', displayName(c), c.input.dateOfBirth.replace(/-/g, ''), c.input.pan, c.input.mobile, l.loanNo, l.disbursedOn.replace(/-/g, ''), C.fromPaise(l.amount), C.fromPaise(principalOutstanding(l)), C.fromPaise(arrearsOf(l.state)), l.state.dpd, l.state.assetClass, l.state.status].join('|'));
    }
  }
  lines.push(`TRL|${accounts}`);
  return { content: lines.join('\r\n') + '\r\n', accounts, rejections: toCsv(['loanNo', 'reason'], rejected), rejected: rejected.length };
}

// ------------------------------------------------------------------ dashboard
function dashboard(db: MockDb, loans: StoredLoan[], user: DemoUser, allBranches: boolean): Dashboard {
  const today = db.businessDate;
  const monthStart = `${today.slice(0, 8)}01`;
  const live = loans.filter(isLive);
  const sum = (ls: StoredLoan[], f: (l: StoredLoan) => number) => ls.reduce((s, l) => s + f(l), 0);
  const portfolio = sum(live, principalOutstanding);
  const npa = live.filter((l) => NPA.includes(l.state.assetClass));
  const grossNpa = sum(npa, principalOutstanding);
  const disbursed = (from: string) => loans.filter((l) => l.disbursedOn && l.disbursedOn >= from && l.disbursedOn <= today);
  const collected = (from: string) => events(loans, ['REPAYMENT', 'PREPAYMENT', 'PRECLOSURE', 'CANCELLATION'], from, today).reduce((s, { e }) => s + (e.amount ?? 0), 0);
  const demands = loans.flatMap((l) => (l.disbursedOn ? l.state.demands : [])).filter((d) => d.dueDate >= monthStart && d.dueDate <= today);
  const demandMtd = demands.reduce((s, d) => s + d.principalDue + d.interestDue + d.principalRescheduled + d.interestCapitalised, 0);
  const against = demands.reduce((s, d) => s + d.principalPaid + d.interestPaid, 0);
  const pct = (a: number, b: number) => (b > 0 ? ((a / b) * 100).toFixed(2) : null);
  const lastRun = [...db.eodRuns].sort((a, b) => b.id - a.id)[0];
  return {
    businessDate: today,
    activeLoans: live.length,
    portfolioOutstanding: C.fromPaise(portfolio),
    overdueAmount: C.fromPaise(sum(live, (l) => arrearsOf(l.state))),
    grossNpa: C.fromPaise(grossNpa),
    npaLoans: npa.length,
    npaPercent: pct(grossNpa, portfolio),
    disbursedToday: C.fromPaise(sum(disbursed(today), (l) => l.amount)),
    disbursedMtd: C.fromPaise(sum(disbursed(monthStart), (l) => l.amount)),
    disbursementsToday: disbursed(today).length,
    disbursementsMtd: disbursed(monthStart).length,
    collectedToday: C.fromPaise(collected(today)),
    collectedMtd: C.fromPaise(collected(monthStart)),
    demandMtd: C.fromPaise(demandMtd),
    collectedAgainstDemandMtd: C.fromPaise(against),
    collectionEfficiencyMtd: pct(against, demandMtd),
    dpdBuckets: BUCKETS.map((bucket) => {
      const ls = live.filter((l) => bucketOf(l.state.dpd) === bucket);
      return { bucket, loans: ls.length, amount: C.fromPaise(sum(ls, principalOutstanding)) };
    }),
    pendingApprovals: user.permissions.includes(P.approvalView) ? db.approvals.filter((s) => s.approval.status === 'PENDING').length : 0,
    lastEod: lastRun ? { businessDate: lastRun.businessDate, status: lastRun.status, finishedAt: lastRun.finishedAt ?? null } : null,
    openEodExceptions: allBranches ? db.eodRuns.flatMap((r) => r.exceptions ?? []).filter((x) => !x.resolved).length : 0,
  };
}

// ------------------------------------------------------------------ routes
export function registerDocumentRoutes(db: MockDb, r: DocumentsRouter) {
  const { on, require, nowIso } = r;
  const scoped = (user: DemoUser, branch?: string) => {
    const s = branchScope(db, user.username, user.homeBranch);
    return { scope: s, loans: db.loans.filter((l) => (s.allBranches || s.branches.includes(l.branch)) && (!branch || l.branch === branch)) };
  };
  /** Loan in the caller's branch scope (404 otherwise, as the contract says). */
  const loanFor = (user: DemoUser, id: string) => {
    require(user, P.loanView);
    const loan = scoped(user).loans.find((l) => l.id === id || l.loanNo === id);
    if (!loan) throw notFound('Loan');
    return loan;
  };
  const pdf = (user: DemoUser, loan: StoredLoan, doc: string, title: string, lines: string[], suffix = ''): Result => {
    appendAudit(db, nowIso(), user.username, 'DOCUMENT_GENERATED', 'LOAN', loan.id, { document: doc });
    const c = customerOf(db, loan);
    const head = [`Loan ${loan.loanNo}   ${c ? displayName(c) : ''}   ${c?.customerNo ?? ''}`, `${db.tenantName}   Branch ${loan.branch}   Generated on business date ${formatDate(db.businessDate)}`, ''];
    return { status: 200, body: null, raw: { contentType: 'application/pdf', data: tinyPdf(title, [...head, ...lines]), fileName: `${doc}-${loan.loanNo}${suffix}.pdf` } };
  };

  on('GET', '/api/v1/loans/{id}/documents/kfs.pdf', ({ user, params }) => {
    const loan = loanFor(user, params.id);
    const k = loan.kfs;
    return pdf(user, loan, 'kfs', 'Key Facts Statement', [
      'Part 1 - Interest rate and fees/charges',
      `Loan amount: Rs ${k.amount}    Tenor: ${k.tenorMonths} months    Instalments: ${k.instalments}`,
      `Interest rate: ${k.interestRate}% p.a. (${k.rateType})    EMI: ${k.emi ? `Rs ${k.emi}` : 'n/a'}`,
      `Annual Percentage Rate (APR): ${k.apr}%    Total interest: Rs ${k.totalInterest}    Total repayable: Rs ${k.totalRepayable}`,
      ...(k.fees ?? []).map((f) => `Fee ${f.code} ${f.name}: Rs ${f.fee} + GST Rs ${C.fromPaise(C.toPaise(f.cgst) + C.toPaise(f.sgst) + C.toPaise(f.igst))} = Rs ${f.total}`),
      `Net disbursal: Rs ${k.netDisbursal}`,
      '',
      'Part 2 - Other qualitative information',
      `Penal charges: ${k.penalChargeRate ?? 'n/a'}% p.a. on overdue. ${k.penalChargeNote ?? ''}`,
      `Cooling-off period: ${k.coolingOffDays} day(s)    Grievance officer: [not configured]`,
      '',
      'Repayment schedule',
      ...(k.schedule ?? []).map((s) => `${String(s.instalmentNo).padStart(3)}  ${s.dueDate}  principal ${s.principal}  interest ${s.interest}  instalment ${s.instalment}`),
    ]);
  });
  on('GET', '/api/v1/loans/{id}/documents/statement.pdf', ({ user, params, url }) => {
    const loan = loanFor(user, params.id);
    if (!loan.disbursedOn) throw conflict('Loan is not disbursed', `Loan ${loan.loanNo} has not been disbursed yet, so there is no statement`);
    const from = url.searchParams.get('from') || loan.disbursedOn;
    const to = url.searchParams.get('to') || db.businessDate;
    if (!ISO_DATE.test(from) || !ISO_DATE.test(to)) throw bad('from and to must be dates (YYYY-MM-DD)', [{ field: 'from', message: 'Invalid date' }]);
    if (to > db.businessDate) throw bad('The statement cannot go beyond the business date', [{ field: 'to', message: 'After the business date' }]);
    if (from > to) throw bad('From must be on or before To', [{ field: 'from', message: 'After To' }]);
    const txns = loan.events.filter((e) => e.valueDate >= from && e.valueDate <= to).sort((a, b) => a.seq - b.seq);
    return pdf(user, loan, 'statement', 'Statement of account', [
      `Period ${formatDate(from)} to ${formatDate(to)}`,
      '',
      ...txns.map((e) => `${e.valueDate}  ${e.type.padEnd(13)} ${e.amount === null ? '' : inr(e.amount)}  ${e.summary}${e.reversedBy ? ' [REVERSED]' : ''}`.slice(0, 110)),
      '',
      `Principal outstanding: ${inr(principalOutstanding(loan))}    Overdue: ${inr(arrearsOf(loan.state))}    Days past due: ${loan.state.dpd}`,
    ], `-${from}-${to}`);
  });
  on('GET', '/api/v1/loans/{id}/documents/schedule.pdf', ({ user, params }) => {
    const loan = loanFor(user, params.id);
    const st = loan.state;
    return pdf(user, loan, 'schedule', 'Repayment schedule', [
      'Instalments fallen due',
      ...st.demands.map((d) => `${String(d.no).padStart(3)}  ${d.dueDate}  due ${inr(d.principalDue + d.interestDue)}  paid ${inr(d.principalPaid + d.interestPaid)}`),
      '',
      'Instalments to come',
      ...st.rows.slice(st.raised).map((x) => `${String(x.no).padStart(3)}  ${x.dueDate}  principal ${inr(x.principal)}  interest ${inr(x.interest)}  instalment ${inr(x.instalment)}`),
    ]);
  });
  on('GET', '/api/v1/loans/{id}/documents/noc.pdf', ({ user, params }) => {
    const loan = loanFor(user, params.id);
    if (loan.state.status !== 'CLOSED') throw conflict('Loan is not closed', `A no-objection letter is issued only for a closed loan; loan ${loan.loanNo} is ${loan.state.status}`);
    return pdf(user, loan, 'noc', 'No-objection certificate', [
      `This is to certify that loan ${loan.loanNo} of Rs ${C.fromPaise(loan.amount)} was closed on ${formatDate(loan.state.closedOn)}.`,
      'All dues under the loan have been received and we have no further claim on the borrower.',
    ]);
  });
  on('GET', '/api/v1/loans/{id}/charges/{chargeId}/invoice.pdf', ({ user, params }) => {
    const loan = loanFor(user, params.id);
    const id = params.chargeId;
    if (/^P\d+$/.test(id)) throw conflict('No invoice for a penal charge', 'Penal charges carry no GST, so there is no tax invoice');
    const charge = /^[CD]\d{1,6}$/.test(id) ? loan.state.charges.find((c) => c.id === id) : undefined;
    const reversed = !charge && loan.events.some((e) => e.data.chargeId === id && e.reversedBy);
    if (!charge && !reversed) throw notFound('Charge');
    const intra = loan.supplierState === loan.recipientState;
    const total = charge?.amount ?? 0;
    const taxable = Math.round(total / 1.18);
    return pdf(user, loan, 'invoice', reversed ? 'Tax invoice - CANCELLED' : 'Tax invoice', [
      `Invoice no. GST/${loan.branch}/${loan.loanNo.slice(-6)}/${id}    SAC 9971`,
      `Supplier state ${loan.supplierState}    Recipient state ${loan.recipientState}`,
      `${charge?.name ?? 'Fee'} (${charge?.code ?? id})`,
      `Taxable value: ${inr(taxable)}`,
      intra ? `CGST 9%: ${inr(Math.round((total - taxable) / 2))}    SGST 9%: ${inr(total - taxable - Math.round((total - taxable) / 2))}` : `IGST 18%: ${inr(total - taxable)}`,
      `Invoice total: ${inr(total)}`,
    ], `-${id}`);
  });

  // ---- reports
  const visible = (user: DemoUser) => reportSpecs(db).filter((s) => user.permissions.includes(s.permission));
  on('GET', '/api/v1/reports', ({ user }) => {
    if (!user.permissions.includes(P.bureauExport)) require(user, P.reportRun);
    return { status: 200, body: visible(user).map(({ run: _run, ...def }) => def) };
  });
  on('POST', '/api/v1/reports/{code}/runs', ({ user, params, body }) => {
    const spec = reportSpecs(db).find((s) => s.code === params.code);
    if (!spec) throw notFound(`Report ${params.code}`);
    require(user, spec.permission);
    const raw = ((body as { parameters?: Record<string, unknown> } | null)?.parameters ?? {}) as Record<string, unknown>;
    const schema = spec.parameters as { properties: Record<string, { format?: string; title?: string; enum?: string[] }>; required: string[] };
    const p: Record<string, string> = {};
    const errors: Array<{ field: string; message: string }> = [];
    for (const [k, v] of Object.entries(raw)) {
      if (!(k in schema.properties)) errors.push({ field: k, message: `Unknown parameter ${k}` });
      else if (v !== '' && v !== null && v !== undefined) p[k] = String(v);
    }
    for (const [k, def] of Object.entries(schema.properties)) {
      const label = def.title ?? k;
      if (schema.required.includes(k) && !p[k]) errors.push({ field: k, message: `${label} is required` });
      else if (p[k] && def.format === 'date' && !ISO_DATE.test(p[k])) errors.push({ field: k, message: `${label} must be a date (YYYY-MM-DD)` });
      else if (p[k] && def.format === 'date' && p[k] > db.businessDate) errors.push({ field: k, message: `${label} cannot be after the business date` });
      else if (p[k] && def.enum && !def.enum.includes(p[k])) errors.push({ field: k, message: `${label} must be one of ${def.enum.join(', ')}` });
    }
    if (p.from && p.to && p.from > p.to) errors.push({ field: 'from', message: 'From must be on or before To' });
    if (p.branch && !db.branches.some((b) => b.code === p.branch)) errors.push({ field: 'branch', message: `Unknown branch ${p.branch}` });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    const { scope, loans } = scoped(user, p.branch);
    if (spec.allBranchesOnly && !scope.allBranches) throw new HttpProblem(403, 'All-branch access needed', `${spec.name} covers every branch, so it needs all-branch access`, {}, PROBLEM_BASE + 'forbidden');
    const now = nowIso();
    const run: ReportRun = { id: uuid(), reportCode: spec.code, requestedBy: user.username, requestedAt: now, businessDate: db.businessDate, parameters: p, status: 'COMPLETED', rowCount: 0, rejectedCount: 0, fileName: null, contentType: null, bytes: null, error: null, finishedAt: now };
    let content = '';
    let rejections: string | null = null;
    if (spec.outputFormat === 'UCRF') {
      const f = bureauFile(db, loans, p.asOf);
      content = f.content;
      rejections = f.rejections;
      Object.assign(run, { rowCount: f.accounts, rejectedCount: f.rejected, fileName: `bureau-consumer-${p.asOf}.txt`, contentType: 'text/plain' });
    } else {
      const out = spec.run(db, loans, p);
      content = toCsv(out.headers, out.rows);
      Object.assign(run, { rowCount: out.rows.length, fileName: `${spec.code.toLowerCase().replace(/_/g, '-')}-${db.businessDate}.csv`, contentType: 'text/csv' });
    }
    run.bytes = new TextEncoder().encode(content).length;
    db.reportRuns.push({ run, content, rejections, permission: spec.permission });
    appendAudit(db, now, user.username, spec.outputFormat === 'UCRF' ? 'BUREAU_EXPORT' : 'REPORT_RUN', 'REPORT', run.id, { code: spec.code, rows: run.rowCount });
    return { status: 201, body: run };
  });
  on('GET', '/api/v1/reports/runs', ({ user, url }) => {
    if (!user.permissions.includes(P.bureauExport) && !user.permissions.includes(P.reportAdmin)) require(user, P.reportRun);
    const page = Math.max(0, Number(url.searchParams.get('page') ?? 0));
    const size = Math.min(100, Math.max(1, Number(url.searchParams.get('size') ?? 20)));
    const all = user.permissions.includes(P.reportAdmin);
    const rows = db.reportRuns.filter((x) => all || x.run.requestedBy === user.username).map((x) => x.run).sort((a, b) => b.requestedAt.localeCompare(a.requestedAt));
    return { status: 200, body: rows.slice(page * size, page * size + size) };
  });
  on('GET', '/api/v1/reports/runs/{id}/download', ({ user, params, url }) => {
    const x = db.reportRuns.find((y) => y.run.id === params.id);
    if (!x || (x.run.requestedBy !== user.username && !user.permissions.includes(P.reportAdmin))) throw notFound('Report run');
    require(user, x.permission);
    if (x.run.status !== 'COMPLETED') throw conflict('Run did not complete', `This run is ${x.run.status}${x.run.error ? `: ${x.run.error}` : ''}`);
    const bureau = x.run.contentType === 'text/plain';
    appendAudit(db, nowIso(), user.username, bureau ? 'BUREAU_DOWNLOAD' : 'REPORT_DOWNLOAD', 'REPORT', x.run.id, { part: url.searchParams.get('part') });
    if (url.searchParams.get('part') === 'rejections') {
      if (x.rejections === null) throw notFound('Rejections file');
      return { status: 200, body: null, raw: { contentType: 'text/csv', data: x.rejections, fileName: `${(x.run.fileName ?? 'run').replace(/\.\w+$/, '')}-rejections.csv` } };
    }
    return { status: 200, body: null, raw: { contentType: x.run.contentType ?? 'text/csv', data: x.content, fileName: x.run.fileName ?? 'report.csv' } };
  });

  on('GET', '/api/v1/dashboard', ({ user }) => {
    require(user, P.dashboardView);
    const { scope, loans } = scoped(user);
    return { status: 200, body: dashboard(db, loans, user, scope.allBranches) };
  });
}
