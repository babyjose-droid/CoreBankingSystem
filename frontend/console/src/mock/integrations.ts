/**
 * Integrations (P2-2) in the mock API: provider configuration, payouts, collections, mandates and NACH files,
 * outbound webhooks, API clients and customer messages, plus the test-only simulator.
 *
 * No secret and no full account number is ever kept: a provider secret leaves only its last four characters, a
 * signing or client secret is generated when it is collected and returned once, and accounts keep their last four.
 */
import type {
  ApiClientInput, ApiClientRecord, Beneficiary, BeneficiaryInput, CollectionOrder, CollectionOrderInput, Mandate, MandateInput, MandateStatusUpdate,
  MessageLogRow, MessageTemplate, MessageTemplateInput, NachFile, NachPresentation, Payout, PaymentResolution, ProviderConfig, ProviderConfigInput,
  ProviderSpec, ReconRow, SimulatedCallback, StatusEvent, WebhookDelivery, WebhookEndpoint, WebhookEndpointInput,
} from '../api/integrationTypes';
import type { DemoUser } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { ISO_DATE, addDays } from '../lib/dates';
import { appendAudit, uuid, type ApprovalPayload, type MockDb, type StoredLoan } from './db';
import { addEvent, refreshLoan } from './lending';
import * as C from './lendingCalc';
import { bad, conflict, HttpProblem, notFound, PROBLEM_BASE, type FieldProblem } from './problems';

type Result = { status: number; body: unknown; raw?: { contentType: string; data: string; fileName: string } };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
}
export interface IntegrationRouter {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (user: DemoUser, entityType: string, action: string, payload: ApprovalPayload, proposed: Record<string, unknown>, current?: Record<string, unknown> | null, entityId?: string | null, amount?: string | null) => Result;
  nowIso: () => string;
}
const ok = (body: unknown): Result => ({ status: 200, body });
const forbidden = (detail: string) => new HttpProblem(403, 'Forbidden', detail, {}, PROBLEM_BASE + 'forbidden');
const SYSTEM = { username: 'system' } as DemoUser;

export const WEBHOOK_EVENT_FIELDS: Record<string, string[]> = {
  'loan.disbursed': ['amount', 'branch', 'businessDate', 'customerId', 'externalRef', 'loanId', 'loanNo', 'netDisbursed', 'trancheNo', 'transactionId'],
  'payment.received': ['amount', 'branch', 'businessDate', 'channel', 'customerId', 'externalRef', 'kind', 'loanId', 'loanNo', 'loanStatus', 'transactionId', 'utr', 'valueDate'],
  'payment.bounced': ['amount', 'attempt', 'bounceCharge', 'businessDate', 'customerId', 'dueDate', 'externalRef', 'loanId', 'loanNo', 'mandateRef', 'representOn', 'returnCode', 'returnReason'],
  'loan.closed': ['branch', 'businessDate', 'closure', 'customerId', 'externalRef', 'loanId', 'loanNo'],
  'loan.npa': ['assetClass', 'branch', 'businessDate', 'customerId', 'dpd', 'externalRef', 'loanId', 'loanNo', 'npaSince'],
  'mandate.status': ['customerId', 'debitAccountMasked', 'loanId', 'loanNo', 'mandateRef', 'previousStatus', 'rejectCode', 'rejectReason', 'status', 'umrn'],
  'payout.status': ['action', 'amount', 'beneficiaryAccountMasked', 'customerId', 'externalRef', 'failureCode', 'failureReason', 'loanId', 'loanNo', 'payoutRef', 'previousStatus', 'status', 'utr'],
};
export const MESSAGE_VARIABLES: Record<string, string[]> = {
  DUE_REMINDER: ['amount', 'due_date', 'loan_no', 'name'],
  LOAN_DISBURSED: ['amount', 'date', 'loan_no', 'name', 'net_amount'],
  NOC_ISSUED: ['date', 'loan_no', 'name'],
  PAYMENT_BOUNCED: ['amount', 'due_date', 'loan_no', 'name', 'reason'],
  PAYMENT_RECEIVED: ['amount', 'date', 'loan_no', 'name'],
  RATE_RESET: ['loan_no', 'name', 'new_emi', 'new_rate', 'new_tenure', 'old_emi', 'old_rate', 'old_tenure'],
};
export const GRANTABLE_SCOPES = [
  'customer:view', 'customer:create', 'consent:view', 'consent:record', 'kyc:upload', 'product:view', 'loan:view', 'loan:create', 'loan:stp', 'loan:repay',
  'payout:view', 'payout:beneficiary', 'collection:view', 'collection:create', 'mandate:view', 'mandate:register', 'report:run',
];
export const SENSITIVE_SCOPES = ['loan:repay', 'loan:stp', 'payout:beneficiary'];
const SIM_NOTE = 'Simulator: reports success without moving money. For tests and demos only.';
const UNVERIFIED = "UNVERIFIED-AGAINST-PROVIDER: written from public documentation as recalled, never run against the provider; confirm in the provider's sandbox before use.";
const CATALOGUE: ProviderSpec[] = [
  { kind: 'PAYOUT', provider: 'SIMULATOR', settings: [], secrets: ['webhookSecret'], requiredSecrets: [], verified: true, note: SIM_NOTE, enabledInDeployment: true },
  { kind: 'COLLECTION', provider: 'SIMULATOR', settings: [], secrets: ['webhookSecret'], requiredSecrets: [], verified: true, note: SIM_NOTE, enabledInDeployment: true },
  { kind: 'MANDATE', provider: 'SIMULATOR', settings: [], secrets: ['webhookSecret'], requiredSecrets: [], verified: true, note: SIM_NOTE, enabledInDeployment: true },
  { kind: 'SMS', provider: 'SIMULATOR', settings: [], secrets: [], requiredSecrets: [], verified: true, note: SIM_NOTE, enabledInDeployment: true },
  { kind: 'EMAIL', provider: 'SIMULATOR', settings: ['fromAddress'], secrets: [], requiredSecrets: [], verified: true, note: SIM_NOTE, enabledInDeployment: true },
  { kind: 'PAYOUT', provider: 'EASEBUZZ', settings: ['mode', 'wireBaseUrl'], secrets: ['key', 'salt'], requiredSecrets: ['key', 'salt'], verified: false, note: UNVERIFIED, enabledInDeployment: false },
  { kind: 'COLLECTION', provider: 'EASEBUZZ', settings: ['environment'], secrets: ['key', 'salt'], requiredSecrets: ['key', 'salt'], verified: false, note: UNVERIFIED, enabledInDeployment: false },
  { kind: 'SMS', provider: 'GENERIC_HTTP', settings: ['authHeader', 'idField', 'url'], secrets: ['apiKey'], requiredSecrets: ['apiKey'], verified: false, note: UNVERIFIED, enabledInDeployment: true },
];
export const NACH_RETURN_REASONS = [
  { code: '01', description: 'Account closed', category: 'ACCOUNT', representable: false, verified: false },
  { code: '04', description: 'Balance insufficient', category: 'FUNDS', representable: true, verified: false },
  { code: '05', description: 'Not arranged for', category: 'FUNDS', representable: true, verified: false },
  { code: '06', description: 'Payment stopped by drawer', category: 'CUSTOMER', representable: false, verified: false },
  { code: '08', description: 'No such account', category: 'ACCOUNT', representable: false, verified: false },
  { code: '59', description: 'Network failure', category: 'TECHNICAL', representable: true, verified: false },
];
const NACH_HEADER = 'record_type,file_ref,utility_code,sponsor_bank_code,settlement_date,direction,seq,item_ref,umrn,account_number,ifsc,account_type,holder_name,amount,user_ref,status,return_code,bank_ref,count,total_amount,success_count,success_amount';

interface StoredMessage extends MessageLogRow {
  eventKey?: string;
}
interface StoredPayment {
  id: string;
  provider: string;
  providerPaymentId: string;
  orderId: string | null;
  loanId: string | null;
  amount: string;
  status: 'POSTED' | 'UNMATCHED' | 'REFUND_DUE';
  paidAt: string;
  valueDate: string | null;
  loanTxnId: string | null;
  lastError: string | null;
  resolutionNote: string | null;
}
interface StoredSettlement {
  providerPaymentId: string;
  amount: string;
  fee: string | null;
  settledOn: string;
  utr: string | null;
  fileRef: string;
}
export interface IntegrationDb {
  providers: ProviderConfig[];
  beneficiaries: Array<Beneficiary & { loanId: string; active: boolean }>;
  payouts: Array<Payout & { disbursementTxn: string | null }>;
  /** Disbursements already turned into a payout instruction (or that predate the gateway). */
  payoutSeen: Set<string>;
  orders: CollectionOrder[];
  payments: StoredPayment[];
  settlements: StoredSettlement[];
  mandates: Mandate[];
  nachFiles: Array<NachFile & { content: string; controlKey?: string }>;
  presentations: Array<NachPresentation & { fileId: string; mandateId: string }>;
  endpoints: Array<WebhookEndpoint & { pendingFor: string | null; kid: number; down?: boolean }>;
  deliveries: WebhookDelivery[];
  clients: Array<ApiClientRecord & { pendingFor: string | null }>;
  templates: MessageTemplate[];
  messages: StoredMessage[];
  optOuts: Array<{ customerId: string; channel: string }>;
  inboundEvents: Set<string>;
}

type IntegrationPayload = Extract<ApprovalPayload, { kind: 'INTEGRATION' }>;
const payload = (op: string, data: Record<string, unknown>, checkers = 1, maker = ''): IntegrationPayload => ({ kind: 'INTEGRATION', op, data, checkers, maker });

const last4 = (v: string) => v.slice(-4);
const masked = (l4: string | undefined) => `XXXXXXXX${l4 ?? ''}`;
const money = (paise: number) => C.fromPaise(paise);
const isMoney2 = (v: unknown): v is string => typeof v === 'string' && /^[0-9]+(\.[0-9]{1,2})?$/.test(v) && Number(v) > 0;
const loanOf = (db: MockDb, id: string | null | undefined): StoredLoan => {
  const l = db.loans.find((x) => x.id === id);
  if (!l) throw notFound('Loan');
  return l;
};
const customerName = (db: MockDb, loan: StoredLoan) => db.customers.find((c) => c.id === loan.customerId)?.input.firstName ?? 'Customer';
const activeProvider = (db: MockDb, kind: string) => db.integration.providers.find((p) => p.kind === kind && p.status === 'ACTIVE');
const randomSecret = (prefix: string) => prefix + Array.from({ length: 4 }, () => Math.random().toString(36).slice(2, 12)).join('');
const event = (list: StatusEvent[] | undefined, at: string, from: string | null, to: string, source: string, actor: string | null): StatusEvent[] => [...(list ?? []), { at, from, to, source, actor }];

// ------------------------------------------------------------------ outbound: webhooks and messages
function emit(db: MockDb, at: string, type: string, aggregateId: string | null) {
  for (const e of db.integration.endpoints) {
    if (e.status !== 'ACTIVE' || !e.eventTypes?.includes(type)) continue;
    db.integration.deliveries.push(deliver(e, { id: uuid(), endpointId: e.id, eventId: uuid(), eventType: type, aggregateId, replayOf: null, requestedBy: null, createdAt: at }, at));
  }
}
function deliver(e: { down?: boolean }, d: WebhookDelivery, at: string): WebhookDelivery {
  return e.down
    ? { ...d, status: 'RETRY', attempts: 1, nextAttemptAt: new Date(Date.parse(at) + 60_000).toISOString(), lastStatus: 503, lastError: 'HTTP 503', deliveredAt: null, attemptLog: [{ attemptNo: 1, at, statusCode: 503, durationMs: 812, error: 'HTTP 503' }] }
    : { ...d, status: 'DELIVERED', attempts: 1, nextAttemptAt: null, lastStatus: 200, lastError: null, deliveredAt: at, attemptLog: [{ attemptNo: 1, at, statusCode: 200, durationMs: 143, error: null }] };
}
function notify(db: MockDb, at: string, code: string, loan: StoredLoan, eventKey: string) {
  for (const t of db.integration.templates.filter((x) => x.code === code && x.status === 'ACTIVE')) {
    const channel = t.channel ?? 'SMS';
    if (db.integration.messages.some((m) => m.templateCode === code && m.channel === channel && m.eventKey === eventKey)) continue;
    const customer = db.customers.find((c) => c.id === loan.customerId);
    const optedOut = db.integration.optOuts.some((o) => o.customerId === loan.customerId && o.channel === channel);
    const provider = activeProvider(db, channel);
    const mobile = customer?.input.mobile ?? '';
    db.integration.messages.push({
      id: uuid(), templateCode: code, channel, category: t.category ?? 'TRANSACTIONAL', customerId: loan.customerId, loanId: loan.id, eventKey,
      recipientMasked: channel === 'SMS' ? `XXXXXX${mobile.slice(-4)}` : 'x***@***', status: optedOut ? 'SUPPRESSED' : provider ? 'SENT' : 'FAILED',
      suppressReason: optedOut ? 'OPTED_OUT' : null, provider: provider?.provider ?? null, providerRef: provider && !optedOut ? `SIM-${uuid().slice(0, 8)}` : null,
      attempts: optedOut ? 0 : 1, lastError: provider || optedOut ? null : `no ${channel} provider is active`, createdAt: at, sentAt: provider && !optedOut ? at : null,
    });
  }
}

// ------------------------------------------------------------------ payouts
/** The relay: every disbursement not yet seen becomes a payout instruction (ON_HOLD without a usable beneficiary). */
function syncPayouts(db: MockDb, at: string) {
  const I = db.integration;
  for (const loan of db.loans) {
    for (const e of loan.events) {
      if (e.type !== 'DISBURSEMENT' || I.payoutSeen.has(e.id)) continue;
      I.payoutSeen.add(e.id);
      createPayout(db, loan, C.fromPaise(loan.netDisbursed ?? e.amount ?? 0), e.id, at, 'RELAY', 'created from the disbursement');
      emit(db, at, 'loan.disbursed', loan.id);
      notify(db, at, 'LOAN_DISBURSED', loan, e.id);
    }
  }
}
function createPayout(db: MockDb, loan: StoredLoan, amount: string, txn: string | null, at: string, source: string, note: string) {
  const I = db.integration;
  const b = I.beneficiaries.find((x) => x.loanId === loan.id && x.active && x.validation !== 'INVALID');
  const attemptNo = I.payouts.filter((p) => p.loanId === loan.id).length + 1;
  const provider = activeProvider(db, 'PAYOUT');
  const sent = !!b && !!provider;
  const status = sent ? 'SENT' : b ? 'INITIATED' : 'ON_HOLD';
  const p: IntegrationDb['payouts'][number] = {
    id: uuid(), reference: `PO-${loan.loanNo}-${String(attemptNo).padStart(2, '0')}`, loanId: loan.id, loanNo: loan.loanNo, branch: loan.branch, attemptNo, amount,
    mode: 'IMPS', provider: provider?.provider ?? null, status, providerRef: sent ? `SIMP${uuid().slice(0, 10).toUpperCase()}` : null, utr: null, failureCode: null, failureReason: null,
    stp: false, needsAction: !sent, actionNote: b ? (provider ? null : 'no payout provider is active') : 'no beneficiary account: record one, then retry', failureAction: null, reversalApprovalId: null,
    attempts: sent ? 1 : 0, lastError: null, createdAt: at, sentAt: sent ? at : null, completedAt: null,
    beneficiaryAccountMasked: b?.accountMasked ?? null, beneficiaryIfsc: b?.ifsc ?? null, disbursementTxn: txn,
    events: event(sent ? event(undefined, at, null, 'INITIATED', source, null) : undefined, at, sent ? 'INITIATED' : null, status, sent ? 'WORKER' : source, null),
  };
  p.events![0] = { ...p.events![0], actor: note };
  I.payouts.push(p);
  return p;
}
const payoutView = ({ disbursementTxn: _t, ...p }: IntegrationDb['payouts'][number], withEvents = false): Payout => (withEvents ? p : { ...p, events: undefined });

// ------------------------------------------------------------------ seed
export function seedIntegrations(db: MockDb, seedAt: string, now: number) {
  const ago = (h: number) => new Date(now - h * 3600_000).toISOString();
  const cfg = (kind: ProviderConfig['kind'], provider: string, secrets: ProviderConfig['secrets'] = {}): ProviderConfig => ({
    id: uuid(), kind, provider, settings: {}, secrets, status: 'ACTIVE', version: 1, updatedBy: 'maker', updatedAt: seedAt, verified: true, enabledInDeployment: true,
  });
  const sim = { webhookSecret: { set: true, last4: 'k3y9' } };
  const I: IntegrationDb = {
    providers: [cfg('PAYOUT', 'SIMULATOR', sim), cfg('COLLECTION', 'SIMULATOR', sim), cfg('MANDATE', 'SIMULATOR', sim), cfg('SMS', 'SIMULATOR')],
    beneficiaries: [], payouts: [], payoutSeen: new Set(), orders: [], payments: [], settlements: [], mandates: [], nachFiles: [], presentations: [],
    endpoints: [], deliveries: [], clients: [], templates: [], messages: [], optOuts: [], inboundEvents: new Set(),
  };
  db.integration = I;
  for (const l of db.loans) for (const e of l.events) if (e.type === 'DISBURSEMENT') I.payoutSeen.add(e.id);
  const [l0, l1, l2, l3] = db.loans;
  const ben = (loan: StoredLoan, l4: string, ifsc: string) => {
    const b = { id: uuid(), loanId: loan.id, active: true, accountMasked: masked(l4), ifsc, validation: 'VALID', validationNote: null, createdBy: 'maker', createdAt: seedAt };
    I.beneficiaries.push(b);
    return b;
  };
  const seedPayout = (loan: StoredLoan, at: string, over: Partial<Payout>) => {
    const p = createPayout(db, loan, money(loan.netDisbursed ?? loan.amount), loan.events.find((e) => e.type === 'DISBURSEMENT')?.id ?? null, at, 'RELAY', 'created from the disbursement');
    Object.assign(p, over);
    return p;
  };
  if (l0 && l1 && l2 && l3) {
    ben(l0, '4521', 'HDFC0001234');
    ben(l1, '7788', 'SBIN0004567');
    ben(l2, '9012', 'ICIC0000789');
    const done = seedPayout(l0, `${l0.disbursedOn}T12:01:00.000Z`, { status: 'SUCCESS', utr: 'HDFCN26015000123', completedAt: `${l0.disbursedOn}T12:03:00.000Z`, needsAction: false });
    done.events = event(done.events, done.completedAt!, 'SENT', 'SUCCESS', 'CALLBACK', null);
    seedPayout(l1, `${l1.disbursedOn}T12:01:00.000Z`, {});
    const failed = seedPayout(l2, `${l2.disbursedOn}T12:01:00.000Z`, {
      status: 'FAILED', failureCode: 'ACCOUNT_CLOSED', failureReason: 'Beneficiary account is closed', needsAction: true, failureAction: 'PARKED',
      actionNote: 'parked by tenant setting: retry the payout or propose the reversal', completedAt: `${l2.disbursedOn}T12:05:00.000Z`,
    });
    failed.events = event(event(failed.events, failed.completedAt!, 'SENT', 'FAILED', 'CALLBACK', null), failed.completedAt!, 'FAILED', 'FAILED', 'SYSTEM', 'PARKED');
    seedPayout(l3, `${l3.disbursedOn}T12:01:00.000Z`, {});
    Object.assign(I.payouts[3], { status: 'ON_HOLD', needsAction: true, actionNote: 'no beneficiary account: record one, then retry', sentAt: null, providerRef: null, attempts: 0, beneficiaryAccountMasked: null, beneficiaryIfsc: null, events: [{ at: `${l3.disbursedOn}T12:01:00.000Z`, from: null, to: 'ON_HOLD', source: 'RELAY', actor: null }] });

    // collections: one open link, one paid and posted, and three reconciliation breaks
    const order = (loan: StoredLoan, n: number, amount: string, status: string, at: string): CollectionOrder => ({
      id: uuid(), reference: `CO-${loan.loanNo}-${String(n).padStart(2, '0')}`, loanId: loan.id, loanNo: loan.loanNo, amount, methods: ['UPI'], provider: 'SIMULATOR',
      paymentUrl: `https://pay.simulator.example/l/CO-${loan.loanNo}-${String(n).padStart(2, '0')}`, status, expiresAt: new Date(Date.parse(at) + 72 * 3600_000).toISOString(), createdBy: 'maker', createdAt: at,
    });
    const paid = order(l0, 1, '9792.00', 'PAID', ago(60));
    I.orders.push(paid, order(l2, 1, '10954.00', 'CREATED', ago(3)));
    I.payments.push(
      { id: uuid(), provider: 'SIMULATOR', providerPaymentId: 'SIMPAY-CLAUDE-TEST-001', orderId: paid.id!, loanId: l0.id, amount: '9792.00', status: 'POSTED', paidAt: ago(58), valueDate: db.businessDate, loanTxnId: null, lastError: null, resolutionNote: null },
      { id: uuid(), provider: 'SIMULATOR', providerPaymentId: 'SIMPAY-CLAUDE-TEST-002', orderId: null, loanId: null, amount: '2500.00', status: 'UNMATCHED', paidAt: ago(30), valueDate: null, loanTxnId: null, lastError: 'no order with this reference', resolutionNote: null },
      { id: uuid(), provider: 'SIMULATOR', providerPaymentId: 'SIMPAY-CLAUDE-TEST-003', orderId: null, loanId: l2.id, amount: '5000.00', status: 'POSTED', paidAt: ago(80), valueDate: addDays(db.businessDate, -3), loanTxnId: null, lastError: null, resolutionNote: null },
    );
    I.settlements.push(
      { providerPaymentId: 'SIMPAY-CLAUDE-TEST-001', amount: '9792.00', fee: '23.60', settledOn: addDays(db.businessDate, -1), utr: 'SETTL26180001', fileRef: 'settlement-seed' },
      { providerPaymentId: 'SIMPAY-CLAUDE-TEST-003', amount: '4950.00', fee: '11.80', settledOn: addDays(db.businessDate, -2), utr: 'SETTL26179004', fileRef: 'settlement-seed' },
      { providerPaymentId: 'SIMPAY-CLAUDE-TEST-009', amount: '1200.00', fee: '2.36', settledOn: addDays(db.businessDate, -2), utr: 'SETTL26179004', fileRef: 'settlement-seed' },
    );

    // mandates: one in force, one waiting for the bank, one rejected
    const mandate = (loan: StoredLoan, n: number, l4: string, status: string, at: string, over: Partial<Mandate> = {}): Mandate => ({
      id: uuid(), mandateRef: `MD-${loan.loanNo}-${String(n).padStart(2, '0')}`, loanId: loan.id, loanNo: loan.loanNo, umrn: null, status, maxAmount: money(Math.max(loan.state.emi ?? 0, 100_000) * 2),
      frequency: 'MONTHLY', startDate: loan.openDate, endDate: null, debitAccountMasked: masked(l4), ifsc: 'HDFC0001234', accountType: 'SB', sponsorBankCode: 'HDFC', utilityCode: 'NACH00000000001234',
      provider: 'SIMULATOR', authenticationUrl: null, rejectCode: null, rejectReason: null, lastError: null, createdBy: 'maker', createdAt: at, updatedAt: at,
      events: [{ at, from: null, to: 'DRAFT', source: 'OPERATOR', actor: 'maker' }, { at, from: 'DRAFT', to: 'SUBMITTED', source: 'WORKER', actor: null }], ...over,
    });
    const m0 = mandate(l0, 1, '4521', 'ACTIVE', `${l0.openDate}T10:30:00.000Z`, { umrn: 'HDFC0000000012345678' });
    m0.events = event(m0.events, `${l0.disbursedOn}T09:00:00.000Z`, 'SUBMITTED', 'ACTIVE', 'CALLBACK', null);
    const m2 = mandate(l2, 1, '9012', 'ACTIVE', `${l2.openDate}T10:30:00.000Z`, { umrn: 'ICIC0000000087654321', ifsc: 'ICIC0000789' });
    m2.events = event(m2.events, `${l2.disbursedOn}T09:00:00.000Z`, 'SUBMITTED', 'ACTIVE', 'CALLBACK', null);
    const m3 = mandate(l3, 1, '3344', 'REJECTED', `${l3.openDate}T10:30:00.000Z`, { rejectCode: 'M012', rejectReason: 'Signature mismatch' });
    m3.events = event(m3.events, `${l3.disbursedOn}T09:00:00.000Z`, 'SUBMITTED', 'REJECTED', 'CALLBACK', null);
    I.mandates.push(m0, mandate(l1, 1, '7788', 'SUBMITTED', `${l1.openDate}T10:30:00.000Z`, { authenticationUrl: `https://mandate.simulator.example/a/MD-${l1.loanNo}-01`, ifsc: 'SBIN0004567' }), m2, m3);

    I.messages.push(
      { id: uuid(), templateCode: 'LOAN_DISBURSED', channel: 'SMS', category: 'TRANSACTIONAL', customerId: l1.customerId, loanId: l1.id, recipientMasked: 'XXXXXX0002', status: 'SENT', suppressReason: null, provider: 'SIMULATOR', providerRef: 'SIM-2f91c0aa', attempts: 1, lastError: null, createdAt: ago(48), sentAt: ago(48) },
      { id: uuid(), templateCode: 'DUE_REMINDER', channel: 'SMS', category: 'SERVICE', customerId: l2.customerId, loanId: l2.id, recipientMasked: 'XXXXXX0004', status: 'SUPPRESSED', suppressReason: 'OPTED_OUT', provider: null, providerRef: null, attempts: 0, lastError: null, createdAt: ago(20), sentAt: null },
      { id: uuid(), templateCode: 'PAYMENT_RECEIVED', channel: 'EMAIL', category: 'TRANSACTIONAL', customerId: l0.customerId, loanId: l0.id, recipientMasked: 'a***@e***.example', status: 'FAILED', suppressReason: null, provider: null, providerRef: null, attempts: 3, lastError: 'no EMAIL provider is active', createdAt: ago(58), sentAt: null },
    );
    I.optOuts.push({ customerId: l2.customerId, channel: 'SMS' });
  }

  const endpoint = (name: string, url: string, eventTypes: string[], status: string, down = false): IntegrationDb['endpoints'][number] => ({
    id: uuid(), name, url, eventTypes, status, version: 1, secretPending: false, createdBy: 'maker', createdAt: seedAt, keysInForce: ['k1'], pendingFor: null, kid: 1, down,
  });
  const los = endpoint('LOS CLAUDE-TEST', 'https://los.partner.example/hooks/corebanking', ['loan.disbursed', 'payment.received', 'payout.status'], 'ACTIVE');
  const crm = endpoint('Collections CRM CLAUDE-TEST', 'https://crm.partner.example/webhooks/cbs', ['mandate.status', 'payment.bounced', 'payout.status'], 'ACTIVE', true);
  I.endpoints.push(los, crm);
  const d = (e: WebhookEndpoint, type: string, at: string): WebhookDelivery => deliver(los, { id: uuid(), endpointId: e.id, eventId: uuid(), eventType: type, aggregateId: db.loans[0]?.id ?? null, replayOf: null, requestedBy: null, createdAt: at }, at);
  const dead = d(crm, 'payment.bounced', ago(40));
  Object.assign(dead, {
    status: 'DEAD', attempts: 6, lastStatus: 503, lastError: 'HTTP 503', deliveredAt: null,
    attemptLog: [0, 1, 2, 3, 4, 5].map((i) => ({ attemptNo: i + 1, at: ago(40 - i * 4), statusCode: i === 2 ? null : 503, durationMs: i === 2 ? 10_000 : 800 + i * 13, error: i === 2 ? 'timed out' : 'HTTP 503' })),
  });
  I.deliveries.push(d(los, 'loan.disbursed', ago(48)), d(los, 'payment.received', ago(58)), dead);

  I.clients.push(
    { clientId: 'ext-los-claude-test', name: 'LOS CLAUDE-TEST', status: 'ACTIVE', scopes: ['customer:create', 'customer:view', 'loan:create', 'loan:stp', 'loan:view'], homeBranch: 'HO', allBranches: true, serviceUsername: 'service-account-ext-los-claude-test', secretPending: false, secretIssuedAt: seedAt, createdBy: 'admin', createdAt: seedAt, pendingFor: null },
    { clientId: 'ext-reports-claude-test', name: 'Reporting CLAUDE-TEST', status: 'DISABLED', scopes: ['loan:view', 'report:run'], homeBranch: 'MUM', allBranches: false, serviceUsername: 'service-account-ext-reports-claude-test', secretPending: false, secretIssuedAt: seedAt, createdBy: 'admin', createdAt: seedAt, pendingFor: null },
  );
  const tpl = (code: MessageTemplate['code'], channel: 'SMS' | 'EMAIL', body: string, over: Partial<MessageTemplate> = {}): MessageTemplate => ({
    code, channel, language: 'en', category: 'TRANSACTIONAL', subject: null as never, body, dltEntityId: channel === 'SMS' ? '1101234567890123456' : undefined, dltTemplateId: channel === 'SMS' ? '1107160000000000001' : undefined,
    dltHeader: channel === 'SMS' ? 'DEMONB' : undefined, status: 'ACTIVE', version: 1, updatedBy: 'checker', updatedAt: seedAt, dltForm: channel === 'SMS' ? dltForm(body) : null, ...over,
  });
  I.templates.push(
    tpl('LOAN_DISBURSED', 'SMS', 'Dear {{name}}, Rs {{net_amount}} has been disbursed on loan {{loan_no}} on {{date}}. - Demo NBFC'),
    tpl('PAYMENT_RECEIVED', 'SMS', 'Dear {{name}}, we received Rs {{amount}} on loan {{loan_no}} on {{date}}. Thank you. - Demo NBFC'),
    tpl('PAYMENT_BOUNCED', 'SMS', 'Dear {{name}}, the debit of Rs {{amount}} due {{due_date}} on loan {{loan_no}} was returned: {{reason}}. - Demo NBFC'),
    tpl('PAYMENT_RECEIVED', 'EMAIL', 'Dear {{name}},\n\nWe received Rs {{amount}} on loan {{loan_no}} on {{date}}.\n\nDemo NBFC', { subject: 'Payment received on loan {{loan_no}}' }),
  );
}

// ------------------------------------------------------------------ helpers used by routes and approvals
const PLACEHOLDER = /\{\{\s*([^{}]*?)\s*\}\}/g;
export function placeholders(body: string): string[] {
  return [...new Set([...body.matchAll(PLACEHOLDER)].map((m) => m[1]))];
}
export function dltForm(body: string): string {
  return body.replace(PLACEHOLDER, '{#var#}');
}

function reconciliation(db: MockDb): ReconRow[] {
  const I = db.integration;
  const rows: ReconRow[] = [];
  const ids = new Set([...I.payments.map((p) => p.providerPaymentId), ...I.settlements.map((s) => s.providerPaymentId)]);
  for (const id of ids) {
    const p = I.payments.find((x) => x.providerPaymentId === id);
    const s = I.settlements.find((x) => x.providerPaymentId === id);
    const category: ReconRow['category'] = !p ? 'SETTLED_NOT_RECEIVED' : p.status === 'REFUND_DUE' ? 'REFUND_DUE' : p.status === 'UNMATCHED' ? 'PAYMENT_NOT_POSTED' : !s ? 'POSTED_NOT_SETTLED' : Number(s.amount) !== Number(p.amount) ? 'AMOUNT_MISMATCH' : 'MATCHED';
    rows.push({
      category, provider: p?.provider ?? 'SIMULATOR', providerPaymentId: id, paymentId: p?.id ?? null, loanId: p?.loanId ?? null, loanNo: db.loans.find((l) => l.id === p?.loanId)?.loanNo ?? null,
      paymentStatus: p?.status ?? null, paymentAmount: p?.amount ?? null, paidAt: p?.paidAt ?? null, valueDate: p?.valueDate ?? null, loanTxnId: p?.loanTxnId ?? null, review: p?.resolutionNote ?? null,
      lastError: p?.lastError ?? null, settledAmount: s?.amount ?? null, settlementFee: s?.fee ?? null, settledOn: s?.settledOn ?? null, settlementUtr: s?.utr ?? null,
    });
  }
  return rows.sort((a, b) => (a.providerPaymentId ?? '').localeCompare(b.providerPaymentId ?? ''));
}

function postReceipt(db: MockDb, loan: StoredLoan, amount: string, at: string, mode: string) {
  const e = addEvent(db, loan, 'system', at, { type: 'REPAYMENT', valueDate: db.businessDate, amount: C.toPaise(amount), summary: `Receipt via ${mode}`, data: { mode } });
  refreshLoan(db, loan);
  emit(db, at, 'payment.received', loan.id);
  notify(db, at, 'PAYMENT_RECEIVED', loan, e.id);
  return e;
}

function moveMandate(db: MockDb, m: Mandate, to: string, at: string, source: string, actor: string | null, u: { umrn?: string; rejectCode?: string; rejectReason?: string } = {}) {
  const from = m.status ?? 'DRAFT';
  const allowed: Record<string, string[]> = { DRAFT: ['SUBMITTED', 'CANCELLED'], SUBMITTED: ['ACTIVE', 'REJECTED', 'CANCELLED'], ACTIVE: ['SUSPENDED', 'CANCELLED', 'EXPIRED'], SUSPENDED: ['ACTIVE', 'CANCELLED', 'EXPIRED'] };
  if (!allowed[from]?.includes(to)) throw conflict('Mandate status cannot change', `the mandate cannot become ${to} from its current status`);
  const umrn = u.umrn ?? m.umrn ?? null;
  if (to === 'ACTIVE' && !/^[A-Z0-9]{20}$/.test(umrn ?? '')) throw bad('an active mandate needs its UMRN (20 letters or digits)', [{ field: 'umrn', message: '20 letters or digits' }]);
  Object.assign(m, { status: to, umrn, rejectCode: to === 'REJECTED' ? (u.rejectCode ?? null) : m.rejectCode, rejectReason: to === 'REJECTED' ? (u.rejectReason ?? null) : m.rejectReason, updatedAt: at, authenticationUrl: to === 'SUBMITTED' ? m.authenticationUrl : null });
  m.events = event(m.events, at, from, to, source, actor);
  emit(db, at, 'mandate.status', m.loanId ?? null);
}

type ProposeFn = IntegrationRouter['propose'];
function payoutNews(db: MockDb, propose: ProposeFn, p: IntegrationDb['payouts'][number], to: string, at: string, reasonCode?: string, reason?: string) {
  const from = p.status ?? '';
  if (from === to) return;
  if (from !== 'SENT' && !(from === 'SUCCESS' && to === 'RETURNED')) {
    p.events = event(p.events, at, from, from, 'CALLBACK', `conflict: ${to}`);
    Object.assign(p, { needsAction: true, actionNote: `the provider reported ${to} for a payout that is ${from}` });
    return;
  }
  p.events = event(p.events, at, from, to, 'CALLBACK', null);
  if (to === 'SUCCESS') Object.assign(p, { status: to, utr: `SIMN${Date.parse(at).toString().slice(-12)}`, completedAt: at, needsAction: false, actionNote: null });
  else {
    Object.assign(p, { status: to, failureCode: reasonCode ?? 'UNKNOWN', failureReason: reason ?? 'The bank did not credit the account', completedAt: at });
    // A staff-approved disbursement is not undone silently: its reversal is proposed for a checker.
    const loan = loanOf(db, p.loanId);
    const note = `payout ${p.reference} ${to}: ${p.failureReason}`;
    const a = (propose(SYSTEM, 'LOAN_DISBURSEMENT_REVERSAL', 'REVERSE', payload('PAYOUT_REVERSAL', { payoutId: p.id, loanId: loan.id }, 1, 'system'), { loanNo: loan.loanNo, payout: p.reference, reason: note, amount: p.amount }, null, loan.loanNo, p.amount ?? null).body as { id: string }).id;
    Object.assign(p, { failureAction: 'PROPOSED', reversalApprovalId: a, needsAction: true, actionNote: 'reversal of the disbursement proposed: approve it, or reject it and retry the payout' });
    p.events = event(p.events, at, to, to, 'SYSTEM', 'PROPOSED');
  }
  emit(db, at, 'payout.status', p.loanId ?? null);
}

// ------------------------------------------------------------------ approvals
/** Applies an approved integration proposal; false when the payload is not one. */
export function applyIntegrationApproval(db: MockDb, p: ApprovalPayload, approval: { entityId?: string | null; appliedRef?: string | null; maker?: string }, checker: string, at: string): boolean {
  if (p.kind !== 'INTEGRATION') return false;
  const I = db.integration;
  const d = p.data as Record<string, never>;
  switch (p.op) {
    case 'PROVIDER_CONFIG': {
      const prev = I.providers.filter((x) => x.kind === d.kind);
      prev.forEach((x) => x.status === 'ACTIVE' && Object.assign(x, { status: 'INACTIVE', updatedBy: checker, updatedAt: at }));
      const version = Math.max(0, ...prev.map((x) => x.version ?? 0)) + 1;
      const hints = d.secretHints as Record<string, string>;
      I.providers.push({ id: uuid(), kind: d.kind, provider: d.provider, settings: d.settings, secrets: Object.fromEntries(Object.entries(hints).map(([k, v]) => [k, { set: true, last4: v }])), status: 'ACTIVE', version, updatedBy: approval.maker ?? checker, updatedAt: at, verified: d.verified, enabledInDeployment: true });
      approval.appliedRef = `${d.kind} v${version}`;
      return true;
    }
    case 'PROVIDER_DEACTIVATE':
      I.providers.forEach((x) => x.kind === d.kind && x.status === 'ACTIVE' && Object.assign(x, { status: 'INACTIVE', updatedBy: checker, updatedAt: at }));
      return true;
    case 'WEBHOOK_CREATE':
      I.endpoints.push({ id: d.id, name: d.name, url: d.url, eventTypes: d.eventTypes, status: 'ACTIVE', version: 1, secretPending: true, createdBy: p.maker, createdAt: at, keysInForce: [], pendingFor: p.maker, kid: 0 });
      approval.entityId = d.id;
      approval.appliedRef = d.name;
      return true;
    case 'WEBHOOK_UPDATE':
    case 'WEBHOOK_ACTION': {
      const e = I.endpoints.find((x) => x.id === d.id);
      if (!e) throw notFound('Webhook endpoint');
      if (p.op === 'WEBHOOK_UPDATE') Object.assign(e, { name: d.name, url: d.url, eventTypes: d.eventTypes, version: (e.version ?? 1) + 1 });
      else if (d.action === 'ROTATE_SECRET') Object.assign(e, { secretPending: true, pendingFor: p.maker });
      else e.status = d.action === 'DISABLE' ? 'DISABLED' : 'ACTIVE';
      approval.appliedRef = e.name ?? null;
      return true;
    }
    case 'CLIENT_CREATE':
      I.clients.push({ clientId: d.clientId, name: d.name, status: 'ACTIVE', scopes: d.scopes, homeBranch: d.homeBranch, allBranches: d.allBranches, serviceUsername: `service-account-${d.clientId}`, secretPending: true, secretIssuedAt: null, createdBy: p.maker, createdAt: at, pendingFor: p.maker });
      approval.appliedRef = d.clientId;
      return true;
    case 'CLIENT_SCOPES':
    case 'CLIENT_ACTION': {
      const c = I.clients.find((x) => x.clientId === d.clientId);
      if (!c) throw notFound('API client');
      if (p.op === 'CLIENT_SCOPES') c.scopes = d.scopes;
      else if (d.action === 'ROTATE_SECRET') Object.assign(c, { secretPending: true, pendingFor: p.maker });
      else c.status = d.action === 'DISABLE' ? 'DISABLED' : 'ACTIVE';
      approval.appliedRef = c.clientId ?? null;
      return true;
    }
    case 'MESSAGE_TEMPLATE': {
      const t = d as unknown as MessageTemplate;
      const i = I.templates.findIndex((x) => x.code === t.code && x.channel === t.channel && x.language === t.language);
      const rec: MessageTemplate = { ...t, version: (i >= 0 ? (I.templates[i].version ?? 1) : 0) + 1, updatedBy: checker, updatedAt: at, dltForm: t.channel === 'SMS' ? dltForm(t.body ?? '') : null };
      if (i >= 0) I.templates[i] = rec;
      else I.templates.push(rec);
      approval.appliedRef = `${t.code}/${t.channel}/${t.language} v${rec.version}`;
      return true;
    }
    case 'PAYOUT_REVERSAL': {
      // Mock simplification: the payout is settled as reversed; the loan's ledger is not replayed back to SANCTIONED.
      const po = I.payouts.find((x) => x.id === d.payoutId);
      if (!po) throw notFound('Payout');
      Object.assign(po, { failureAction: 'REVERSED', needsAction: false, actionNote: 'disbursement reversed; the loan is SANCTIONED again' });
      po.events = event(po.events, at, po.status ?? null, po.status ?? '', 'SYSTEM', 'REVERSED');
      approval.appliedRef = po.loanNo ?? null;
      return true;
    }
    default:
      throw new HttpProblem(500, 'Internal error', `No handler for integration approval ${p.op}`);
  }
}

/** A rejected reversal leaves the failed payout parked, to be retried. */
export function rejectIntegrationApproval(db: MockDb, p: ApprovalPayload, at: string) {
  if (p.kind !== 'INTEGRATION' || p.op !== 'PAYOUT_REVERSAL') return;
  const po = db.integration.payouts.find((x) => x.id === p.data.payoutId);
  if (po) {
    Object.assign(po, { failureAction: 'PARKED', actionNote: 'the reversal was rejected: retry the payout' });
    po.events = event(po.events, at, po.status ?? null, po.status ?? '', 'SYSTEM', 'PARKED');
  }
}

// ------------------------------------------------------------------ routes
export function registerIntegrationRoutes(db: MockDb, r: IntegrationRouter) {
  const { on, require, propose, nowIso } = r;
  const I = () => db.integration;
  const pending = (op: string, pred: (d: Record<string, unknown>) => boolean) => db.approvals.some((s) => s.approval.status === 'PENDING' && s.payload.kind === 'INTEGRATION' && s.payload.op === op && pred(s.payload.data));
  const alreadyPending = (what: string) => conflict('Change already pending', `${what} already has a change awaiting approval`);

  // ---- providers
  on('GET', '/api/v1/integrations/providers/catalogue', ({ user }) => (require(user, P.integrationView), ok(CATALOGUE)));
  on('GET', '/api/v1/integrations/providers', ({ user, url }) => {
    require(user, P.integrationView);
    const history = url.searchParams.get('history') === 'true';
    return ok(I().providers.filter((p) => history || p.status === 'ACTIVE').sort((a, b) => (a.kind ?? '').localeCompare(b.kind ?? '') || (b.version ?? 0) - (a.version ?? 0)));
  });
  on('POST', '/api/v1/integrations/providers', ({ user, body }) => {
    require(user, P.integrationAdmin);
    const b = (body ?? {}) as ProviderConfigInput;
    const spec = CATALOGUE.find((s) => s.kind === b.kind && s.provider === b.provider?.toUpperCase());
    if (!spec) throw bad(`there is no provider ${b.provider} for ${b.kind}`, [{ field: 'provider', message: 'Unknown provider' }]);
    if (!spec.enabledInDeployment) throw conflict('Provider not enabled', `provider ${spec.provider} is not enabled in this deployment (corebanking.integration.providers-enabled)`);
    const settings = b.settings ?? {};
    const given = b.secrets ?? {};
    const errors: FieldProblem[] = [];
    for (const [k, v] of Object.entries(settings)) {
      if (!spec.settings!.includes(k)) errors.push({ field: `settings.${k}`, message: `unknown setting '${k}' for ${spec.provider}` });
      else if (!v || v.length > 500) errors.push({ field: `settings.${k}`, message: `setting '${k}' is empty or too long` });
    }
    for (const [k, v] of Object.entries(given)) {
      if (!spec.secrets!.includes(k)) errors.push({ field: `secrets.${k}`, message: `unknown secret '${k}' for ${spec.provider}` });
      else if (!v || v.length < 8 || v.length > 500) errors.push({ field: `secrets.${k}`, message: `secret '${k}' must be 8 to 500 characters` });
    }
    if (settings.url && !/^https:\/\/[a-z0-9.-]+\.[a-z]{2,}(:(443|8443))?(\/|$)/i.test(settings.url)) errors.push({ field: 'settings.url', message: 'url: must be https with a public DNS name' });
    const active = activeProvider(db, spec.kind!);
    const current = active?.provider === spec.provider ? Object.fromEntries(Object.entries(active?.secrets ?? {}).map(([k, v]) => [k, v.last4 ?? ''])) : {};
    for (const req of spec.requiredSecrets ?? []) if (!(req in given) && !(req in current)) errors.push({ field: `secrets.${req}`, message: `secret '${req}' is required` });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (pending('PROVIDER_CONFIG', (d) => d.kind === spec.kind) || pending('PROVIDER_DEACTIVATE', (d) => d.kind === spec.kind)) throw alreadyPending(`The ${spec.kind} provider`);
    // Only the names of the secrets that change and their last four characters go any further; the values are dropped here.
    const secretHints = { ...current, ...Object.fromEntries(Object.entries(given).map(([k, v]) => [k, last4(v)])) };
    const shown = { kind: spec.kind, provider: spec.provider, settings, secretsChanged: Object.keys(given).sort(), secretHints, verified: spec.verified, note: spec.note };
    return propose(user, 'PROVIDER_CONFIG', active ? 'UPDATE' : 'CREATE', payload('PROVIDER_CONFIG', shown, 1, user.username), shown, active ? { ...active } : null, spec.kind);
  });
  on('POST', '/api/v1/integrations/providers/{kind}/deactivate', ({ user, params }) => {
    require(user, P.integrationAdmin);
    if (!activeProvider(db, params.kind)) throw notFound(`active provider for ${params.kind}`);
    if (pending('PROVIDER_CONFIG', (d) => d.kind === params.kind) || pending('PROVIDER_DEACTIVATE', (d) => d.kind === params.kind)) throw alreadyPending(`The ${params.kind} provider`);
    return propose(user, 'PROVIDER_CONFIG', 'DEACTIVATE', payload('PROVIDER_DEACTIVATE', { kind: params.kind }, 1, user.username), { kind: params.kind }, null, params.kind);
  });

  // ---- simulator
  on('POST', '/api/v1/integrations/simulator/callbacks', ({ user, body }) => {
    require(user, P.integrationSimulate);
    const b = (body ?? {}) as SimulatedCallback;
    if (!b.kind || !b.reference?.trim() || !b.status) throw bad('kind, reference and status are required');
    if (!['payout', 'collection', 'mandate'].includes(b.kind)) throw bad('kind must be payout, collection or mandate', [{ field: 'kind', message: 'Unknown kind' }]);
    if (activeProvider(db, b.kind.toUpperCase())?.provider !== 'SIMULATOR') throw conflict('Simulator not active', `SIMULATOR is not the active ${b.kind} provider of this tenant`);
    const eventId = b.eventId?.trim() || `sim-${uuid()}`;
    if (I().inboundEvents.has(eventId)) return ok({ eventId, receipt: 'DUPLICATE' });
    const at = nowIso();
    const ref = b.reference.trim();
    syncPayouts(db, at);
    if (b.kind === 'payout') {
      const p = I().payouts.find((x) => x.reference === ref);
      if (!p) throw notFound(`Payout ${ref}`);
      if (!['SUCCESS', 'FAILED', 'RETURNED'].includes(b.status)) throw bad('a payout status is SUCCESS, FAILED or RETURNED', [{ field: 'status', message: 'Unknown status' }]);
      payoutNews(db, propose, p, b.status, at, b.reasonCode, b.reason);
    } else if (b.kind === 'collection') {
      const o = I().orders.find((x) => x.reference === ref);
      if (!['PAID', 'FAILED'].includes(b.status)) throw bad('a collection status is PAID or FAILED', [{ field: 'status', message: 'Unknown status' }]);
      if (b.status === 'PAID') {
        const amount = b.amount ?? o?.amount;
        if (!isMoney2(amount)) throw bad('amount must be positive with at most two decimals', [{ field: 'amount', message: 'Invalid amount' }]);
        const pay: StoredPayment = { id: uuid(), provider: 'SIMULATOR', providerPaymentId: `SIMPAY-${eventId.slice(-8).toUpperCase()}`, orderId: o?.id ?? null, loanId: null, amount: C.fromPaise(C.toPaise(amount)), status: 'UNMATCHED', paidAt: at, valueDate: null, loanTxnId: null, lastError: null, resolutionNote: null };
        if (!o) pay.lastError = 'no order with this reference';
        else if (o.status === 'PAID') pay.lastError = 'the order was already paid';
        else {
          const e = postReceipt(db, loanOf(db, o.loanId), pay.amount, at, 'GATEWAY');
          Object.assign(pay, { status: 'POSTED', loanId: o.loanId, valueDate: db.businessDate, loanTxnId: e.id });
          o.status = 'PAID';
        }
        I().payments.push(pay);
      } else if (o && o.status === 'CREATED') o.status = 'FAILED';
    } else {
      const m = I().mandates.find((x) => x.mandateRef === ref);
      if (!m) throw notFound(`Mandate ${ref}`);
      if (!['ACTIVE', 'REJECTED', 'CANCELLED'].includes(b.status)) throw bad('a mandate status is ACTIVE, REJECTED or CANCELLED', [{ field: 'status', message: 'Unknown status' }]);
      moveMandate(db, m, b.status, at, 'CALLBACK', null, { umrn: b.status === 'ACTIVE' ? `SIMU${String(Date.parse(at)).padStart(16, '0').slice(-16)}` : undefined, rejectCode: b.reasonCode, rejectReason: b.reason });
    }
    I().inboundEvents.add(eventId);
    appendAudit(db, at, user.username, 'SIMULATED_CALLBACK', 'INTEGRATION', ref, { kind: b.kind, status: b.status });
    return ok({ eventId, receipt: 'ACCEPTED' });
  });

  on('POST', '/api/v1/integrations/simulator/payouts/{id}/outcome', ({ user, params, body }) => {
    require(user, P.integrationSimulate);
    const b = (body ?? {}) as { status?: string; reason?: string };
    const wanted = (b.status ?? '').toUpperCase();
    if (wanted !== 'FAILED' && wanted !== 'RETURNED') throw bad('status must be FAILED or RETURNED', [{ field: 'status', message: 'FAILED or RETURNED' }]);
    if (activeProvider(db, 'PAYOUT')?.provider !== 'SIMULATOR') throw conflict('Simulator not active', 'SIMULATOR is not the active payout provider of this tenant');
    const at = nowIso();
    syncPayouts(db, at);
    const p = I().payouts.find((x) => x.id === params.id);
    if (!p) throw notFound('Payout');
    const ok2 = wanted === 'FAILED' ? ['INITIATED', 'SENT'].includes(p.status ?? '') : ['SENT', 'SUCCESS'].includes(p.status ?? '');
    if (!ok2) throw conflict('Not possible now', `a payout that is ${p.status} cannot be reported ${wanted} (FAILED: from INITIATED or SENT; RETURNED: from SENT or SUCCESS)`);
    payoutNews(db, propose, p, wanted, at, wanted === 'FAILED' ? 'SIM_FAILED' : 'SIM_RETURNED', b.reason?.trim() || (wanted === 'FAILED' ? 'account closed (simulated)' : 'returned by the beneficiary bank (simulated)'));
    appendAudit(db, at, user.username, 'SIMULATED_CALLBACK', 'INTEGRATION', p.reference ?? null, { kind: 'payout', status: wanted });
    return ok({ eventId: `sim-${uuid()}`, receipt: 'ACCEPTED', payoutStatus: wanted });
  });

  // ---- payouts
  on('PUT', '/api/v1/loans/{id}/payout-beneficiary', ({ user, params, body }) => {
    require(user, P.payoutBeneficiary);
    const loan = loanOf(db, params.id);
    const b = (body ?? {}) as BeneficiaryInput;
    const errors: FieldProblem[] = [];
    if (!b.holderName?.trim() || b.holderName.length > 100) errors.push({ field: 'holderName', message: 'holderName is required (at most 100 characters)' });
    if (!/^[A-Za-z0-9]{6,35}$/.test(b.accountNumber ?? '')) errors.push({ field: 'accountNumber', message: 'accountNumber must be 6 to 35 letters or digits' });
    if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(b.ifsc ?? '')) errors.push({ field: 'ifsc', message: 'ifsc is not a valid IFSC' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (I().payouts.some((p) => p.loanId === loan.id && ['SENT', 'SUCCESS'].includes(p.status ?? ''))) throw conflict('Beneficiary cannot change', 'a payout for this loan is already sent or paid; the beneficiary can no longer change');
    if (['CLOSED', 'CANCELLED', 'WRITTEN_OFF'].includes(loan.state.status)) throw conflict('Loan is not open', `loan is ${loan.state.status}`);
    // The simulator's bank refuses accounts ending 0000, so the 422 path can be shown.
    if (b.accountNumber.endsWith('0000')) throw bad('the bank did not confirm this account: no such account', [{ field: 'accountNumber', message: 'Not confirmed by the bank' }]);
    I().beneficiaries.forEach((x) => x.loanId === loan.id && (x.active = false));
    const rec = { id: uuid(), loanId: loan.id, active: true, accountMasked: masked(last4(b.accountNumber)), ifsc: b.ifsc, validation: 'VALID', validationNote: null, createdBy: user.username, createdAt: nowIso() };
    I().beneficiaries.push(rec);
    appendAudit(db, rec.createdAt, user.username, 'PAYOUT_BENEFICIARY', 'LOAN', loan.id, { accountLast4: last4(b.accountNumber), ifsc: b.ifsc, validation: 'VALID' });
    const { loanId: _l, active: _a, ...view } = rec;
    return ok(view);
  });
  on('GET', '/api/v1/loans/{id}/payout-beneficiary', ({ user, params }) => {
    require(user, P.payoutView);
    const rec = I().beneficiaries.find((x) => x.loanId === loanOf(db, params.id).id && x.active);
    if (!rec) throw notFound('Payout beneficiary');
    const { loanId: _l, active: _a, ...view } = rec;
    return ok(view);
  });
  on('GET', '/api/v1/payouts', ({ user, url }) => {
    require(user, P.payoutView);
    syncPayouts(db, nowIso());
    const q = url.searchParams;
    const needs = q.get('needsAction');
    return ok(
      I().payouts
        .filter((p) => (!q.get('status') || p.status === q.get('status')) && (!needs || String(!!p.needsAction) === needs) && (!q.get('loanId') || p.loanId === q.get('loanId')))
        .filter((p) => (!q.get('from') || (p.createdAt ?? '') >= q.get('from')!) && (!q.get('to') || (p.createdAt ?? '').slice(0, 10) <= q.get('to')!))
        .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
        .map((p) => payoutView(p)),
    );
  });
  const payout = (id: string) => {
    const p = I().payouts.find((x) => x.id === id);
    if (!p) throw notFound('Payout');
    return p;
  };
  on('GET', '/api/v1/payouts/{id}', ({ user, params }) => (require(user, P.payoutView), ok(payoutView(payout(params.id), true))));
  on('POST', '/api/v1/payouts/{id}/retry', ({ user, params }) => {
    require(user, P.payoutAdmin);
    const p = payout(params.id);
    const at = nowIso();
    const loan = loanOf(db, p.loanId);
    if (p.status === 'ON_HOLD' || p.status === 'INITIATED') {
      const b = I().beneficiaries.find((x) => x.loanId === p.loanId && x.active && x.validation !== 'INVALID');
      if (!b) throw conflict('No beneficiary', 'record a beneficiary account for the loan first');
      if (!activeProvider(db, 'PAYOUT')) throw conflict('No payout provider', 'no payout provider is active');
      p.events = event(event(p.events, at, p.status, 'INITIATED', 'OPERATOR', user.username), at, 'INITIATED', 'SENT', 'WORKER', null);
      Object.assign(p, { status: 'SENT', sentAt: at, attempts: 1, needsAction: false, actionNote: null, providerRef: `SIMP${uuid().slice(0, 10).toUpperCase()}`, provider: 'SIMULATOR', beneficiaryAccountMasked: b.accountMasked, beneficiaryIfsc: b.ifsc });
      appendAudit(db, at, user.username, 'PAYOUT_RETRY', 'PAYOUT', p.reference ?? null, { from: 'ON_HOLD' });
      return ok(payoutView(p, true));
    }
    if (['FAILED', 'RETURNED'].includes(p.status ?? '') && p.failureAction === 'PARKED') {
      if (loan.state.status !== 'ACTIVE') throw conflict('Loan is not active', `loan is ${loan.state.status}: disburse it again instead`);
      Object.assign(p, { needsAction: false, actionNote: 'a new attempt was made' });
      const next = createPayout(db, loan, p.amount ?? '0.00', p.disbursementTxn, at, 'OPERATOR', `new attempt after ${p.reference}`);
      appendAudit(db, at, user.username, 'PAYOUT_RETRY', 'PAYOUT', p.reference ?? null, { newPayoutId: next.id });
      return ok(payoutView(next, true));
    }
    throw conflict('Payout cannot be retried', `a payout that is ${p.status}${p.failureAction ? ` (${p.failureAction})` : ''} cannot be retried`);
  });
  on('POST', '/api/v1/payouts/{id}/refresh', ({ user, params }) => {
    require(user, P.payoutAdmin);
    const p = payout(params.id);
    if (!['SENT', 'SUCCESS'].includes(p.status ?? '')) throw conflict('Payout cannot be refreshed', 'only a sent or paid payout can be refreshed');
    if (!activeProvider(db, 'PAYOUT')) throw conflict('No payout provider', 'no payout provider is active');
    p.events = event(p.events, nowIso(), p.status ?? null, p.status ?? '', 'POLL', user.username);
    return ok(payoutView(p, true));
  });

  // ---- collections
  on('POST', '/api/v1/loans/{id}/collection-orders', ({ user, params, body }) => {
    require(user, P.collectionCreate);
    const loan = loanOf(db, params.id);
    const b = (body ?? {}) as CollectionOrderInput;
    if (!isMoney2(b.amount)) throw bad('amount must be positive with at most two decimals', [{ field: 'amount', message: 'Invalid amount' }]);
    if (loan.state.status !== 'ACTIVE') throw conflict('Loan cannot take a payment', `loan is ${loan.state.status}: it cannot take a payment`);
    if ((b.methods ?? []).some((m) => !['UPI', 'CARD', 'NETBANKING'].includes(m))) throw bad('methods may contain UPI, CARD and NETBANKING', [{ field: 'methods', message: 'Unknown method' }]);
    if (b.returnUrl && !/^https:\/\//i.test(b.returnUrl)) throw bad('returnUrl: must be https', [{ field: 'returnUrl', message: 'Must be https' }]);
    const provider = activeProvider(db, 'COLLECTION');
    if (!provider) throw conflict('No collection provider', 'no collection provider is active for this tenant');
    const at = nowIso();
    const reference = `CO-${loan.loanNo}-${String(I().orders.filter((o) => o.loanId === loan.id).length + 1).padStart(2, '0')}`;
    const o: CollectionOrder = { id: uuid(), reference, loanId: loan.id, loanNo: loan.loanNo, amount: C.fromPaise(C.toPaise(b.amount)), methods: b.methods ?? [], provider: provider.provider, paymentUrl: `https://pay.simulator.example/l/${reference}`, status: 'CREATED', expiresAt: new Date(Date.parse(at) + 72 * 3600_000).toISOString(), createdBy: user.username, createdAt: at };
    I().orders.push(o);
    appendAudit(db, at, user.username, 'COLLECTION_ORDER', 'LOAN', loan.id, { reference, amount: o.amount });
    return { status: 201, body: o };
  });
  on('GET', '/api/v1/loans/{id}/collection-orders', ({ user, params }) => {
    require(user, P.collectionView);
    return ok(I().orders.filter((o) => o.loanId === loanOf(db, params.id).id).sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')));
  });
  on('GET', '/api/v1/collection-orders/{id}', ({ user, params }) => {
    require(user, P.collectionView);
    const o = I().orders.find((x) => x.id === params.id);
    if (!o) throw notFound('Payment order');
    return ok(o);
  });
  on('GET', '/api/v1/gateway-payments/reconciliation', ({ user, url }) => {
    require(user, P.collectionView);
    const category = url.searchParams.get('category');
    return ok(reconciliation(db).filter((x) => (category ? x.category === category : x.category !== 'MATCHED')));
  });
  on('POST', '/api/v1/gateway-payments/{id}/resolve', ({ user, params, body }) => {
    require(user, P.collectionAdmin);
    const b = (body ?? {}) as PaymentResolution;
    if (!b.note?.trim()) throw bad('a note is required', [{ field: 'note', message: 'Required' }]);
    const p = I().payments.find((x) => x.id === params.id);
    if (!p) throw notFound('Gateway payment');
    if (p.status !== 'UNMATCHED') throw conflict('Payment is not unmatched', `the payment is ${p.status}`);
    const at = nowIso();
    if (b.refund) Object.assign(p, { status: 'REFUND_DUE', resolutionNote: b.note.trim(), lastError: null });
    else {
      if (!b.loanId) throw bad('give the loanId to post to, or refund = true', [{ field: 'loanId', message: 'Required unless refunding' }]);
      const loan = loanOf(db, b.loanId);
      if (loan.state.status !== 'ACTIVE') throw conflict('Loan is not active', `loan is ${loan.state.status}`);
      const e = postReceipt(db, loan, p.amount, at, 'GATEWAY');
      Object.assign(p, { status: 'POSTED', loanId: loan.id, valueDate: db.businessDate, loanTxnId: e.id, resolutionNote: b.note.trim(), lastError: null });
    }
    appendAudit(db, at, user.username, 'GATEWAY_PAYMENT_RESOLVED', 'GATEWAY_PAYMENT', p.id, { refund: String(!!b.refund), loanId: String(b.loanId ?? null) });
    return ok({ id: p.id, status: p.status, loanId: p.loanId, resolutionNote: p.resolutionNote });
  });
  on('POST', '/api/v1/gateway-settlements/upload', ({ user, url, body }) => {
    require(user, P.collectionAdmin);
    const fileRef = url.searchParams.get('fileRef') ?? '';
    if (!/^[A-Za-z0-9._-]{1,60}$/.test(fileRef)) throw bad("fileRef: 1 to 60 letters, digits, '.', '_' or '-'", [{ field: 'fileRef', message: 'Invalid reference' }]);
    const lines = String(body ?? '').split(/\r?\n/).filter((l) => l.trim());
    const head = (lines.shift() ?? '').split(',').map((h) => h.trim());
    for (const col of ['provider_payment_id', 'amount', 'settled_on']) if (!head.includes(col)) throw bad(`the file needs the column ${col}`);
    const rows = lines.map((l, i) => {
      const cells = l.split(',').map((c) => c.trim());
      const get = (c: string) => cells[head.indexOf(c)] ?? '';
      if (!get('provider_payment_id') || !isMoney2(get('amount')) || !ISO_DATE.test(get('settled_on')) || (get('fee') && !/^[0-9]+(\.[0-9]{1,2})?$/.test(get('fee')))) throw bad(`line ${i + 2}: amount, fee or settled_on (YYYY-MM-DD) is not valid`);
      return { providerPaymentId: get('provider_payment_id'), amount: C.fromPaise(C.toPaise(get('amount'))), fee: get('fee') || null, settledOn: get('settled_on'), utr: get('utr') || null, fileRef };
    });
    const fresh = rows.filter((x) => !I().settlements.some((s) => s.providerPaymentId === x.providerPaymentId));
    I().settlements.push(...fresh);
    appendAudit(db, nowIso(), user.username, 'SETTLEMENT_FILE', 'GATEWAY_SETTLEMENT', fileRef, { rows: rows.length, added: fresh.length });
    return ok({ fileRef, rows: rows.length, added: fresh.length, alreadyLoaded: rows.length - fresh.length });
  });

  // ---- mandates
  const mandateList = ({ events: _e, ...m }: Mandate): Mandate => m;
  on('POST', '/api/v1/loans/{id}/mandates', ({ user, params, body }) => {
    require(user, P.mandateRegister);
    const loan = loanOf(db, params.id);
    const b = (body ?? {}) as MandateInput;
    const errors: FieldProblem[] = [];
    if (!b.holderName?.trim() || b.holderName.length > 100) errors.push({ field: 'holderName', message: 'holderName is required' });
    if (!/^[A-Za-z0-9]{6,35}$/.test(b.accountNumber ?? '')) errors.push({ field: 'accountNumber', message: 'accountNumber must be 6 to 35 letters or digits' });
    if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(b.ifsc ?? '')) errors.push({ field: 'ifsc', message: 'ifsc is not a valid IFSC' });
    if (!['SB', 'CA', 'CC', 'OT'].includes(b.accountType ?? 'SB')) errors.push({ field: 'accountType', message: 'accountType must be SB, CA, CC or OT' });
    if (!isMoney2(b.maxAmount)) errors.push({ field: 'maxAmount', message: 'maxAmount must be positive' });
    if (!['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'YEARLY', 'AS_PRESENTED'].includes(b.frequency ?? 'MONTHLY')) errors.push({ field: 'frequency', message: 'frequency must be MONTHLY, QUARTERLY, HALF_YEARLY, YEARLY or AS_PRESENTED' });
    if (!ISO_DATE.test(b.startDate ?? '')) errors.push({ field: 'startDate', message: 'startDate is required' });
    else if (b.endDate && b.endDate <= b.startDate) errors.push({ field: 'endDate', message: 'endDate must be after startDate' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (['CLOSED', 'CANCELLED', 'WRITTEN_OFF'].includes(loan.state.status)) throw conflict('Loan is not open', `loan is ${loan.state.status}`);
    if (I().mandates.some((m) => m.loanId === loan.id && ['DRAFT', 'SUBMITTED', 'ACTIVE', 'SUSPENDED'].includes(m.status ?? ''))) throw conflict('Mandate already exists', 'the loan already has a mandate in registration or in force; cancel it first');
    const at = nowIso();
    const provider = activeProvider(db, 'MANDATE');
    const mandateRef = `MD-${loan.loanNo}-${String(I().mandates.filter((m) => m.loanId === loan.id).length + 1).padStart(2, '0')}`;
    const m: Mandate = {
      id: uuid(), mandateRef, loanId: loan.id, loanNo: loan.loanNo, umrn: null, status: provider ? 'SUBMITTED' : 'DRAFT', maxAmount: C.fromPaise(C.toPaise(b.maxAmount)), frequency: b.frequency ?? 'MONTHLY', startDate: b.startDate, endDate: b.endDate ?? null,
      debitAccountMasked: masked(last4(b.accountNumber)), ifsc: b.ifsc, accountType: b.accountType ?? 'SB', sponsorBankCode: b.sponsorBankCode?.trim() || 'HDFC', utilityCode: b.utilityCode?.trim() || 'NACH00000000001234', provider: provider?.provider ?? null,
      authenticationUrl: provider ? `https://mandate.simulator.example/a/${mandateRef}` : null, rejectCode: null, rejectReason: null, lastError: provider ? null : 'no mandate provider is active', createdBy: user.username, createdAt: at, updatedAt: at,
      events: provider ? [{ at, from: null, to: 'DRAFT', source: 'OPERATOR', actor: user.username }, { at, from: 'DRAFT', to: 'SUBMITTED', source: 'WORKER', actor: null }] : [{ at, from: null, to: 'DRAFT', source: 'OPERATOR', actor: user.username }],
    };
    I().mandates.push(m);
    appendAudit(db, at, user.username, 'MANDATE_REGISTERED', 'LOAN', loan.id, { mandateRef, accountLast4: last4(b.accountNumber) });
    return { status: 201, body: m };
  });
  on('GET', '/api/v1/loans/{id}/mandates', ({ user, params }) => {
    require(user, P.mandateView);
    const live = (m: Mandate) => (['ACTIVE', 'SUSPENDED'].includes(m.status ?? '') ? 0 : 1);
    return ok(I().mandates.filter((m) => m.loanId === params.id).sort((a, b) => live(a) - live(b) || (b.createdAt ?? '').localeCompare(a.createdAt ?? '')).map(mandateList));
  });
  const presView = ({ fileId: _f, mandateId: _m, ...p }: IntegrationDb['presentations'][number]): NachPresentation => p;
  on('GET', '/api/v1/loans/{id}/nach-presentations', ({ user, params }) => {
    require(user, P.mandateView);
    return ok(I().presentations.filter((p) => p.loanId === params.id).map(presView).reverse());
  });
  on('GET', '/api/v1/mandates', ({ user, url }) => {
    require(user, P.mandateView);
    const status = url.searchParams.get('status');
    return ok(I().mandates.filter((m) => !status || m.status === status).sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')).map(mandateList));
  });
  const mandateOf = (id: string) => {
    const m = I().mandates.find((x) => x.id === id);
    if (!m) throw notFound('Mandate');
    return m;
  };
  on('GET', '/api/v1/mandates/{id}', ({ user, params }) => (require(user, P.mandateView), ok(mandateOf(params.id))));
  on('POST', '/api/v1/mandates/{id}/status', ({ user, params, body }) => {
    require(user, P.mandateAdmin);
    const b = (body ?? {}) as MandateStatusUpdate;
    if (!b.status) throw bad('status is required', [{ field: 'status', message: 'Required' }]);
    if (!['SUBMITTED', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'CANCELLED', 'EXPIRED'].includes(b.status)) throw bad(`unknown mandate status ${b.status}`, [{ field: 'status', message: 'Unknown status' }]);
    const m = mandateOf(params.id);
    moveMandate(db, m, b.status, nowIso(), 'OPERATOR', user.username, b);
    appendAudit(db, nowIso(), user.username, 'MANDATE_STATUS', 'MANDATE', m.mandateRef ?? null, { status: b.status });
    return ok(m);
  });

  // ---- NACH
  const fileView = ({ content: _c, controlKey: _k, ...f }: IntegrationDb['nachFiles'][number]): NachFile => f;
  on('GET', '/api/v1/nach/return-reasons', ({ user }) => (require(user, P.mandateView), ok(NACH_RETURN_REASONS)));
  on('GET', '/api/v1/nach/files', ({ user, url }) => {
    require(user, P.nachAdmin);
    const direction = url.searchParams.get('direction');
    return ok(I().nachFiles.filter((f) => !direction || f.direction === direction).map(fileView).reverse());
  });
  const nachFile = (id: string) => {
    const f = I().nachFiles.find((x) => x.id === id);
    if (!f) throw notFound('NACH file');
    return f;
  };
  on('GET', '/api/v1/nach/files/{id}/content', ({ user, params }) => {
    require(user, P.nachFile);
    const f = nachFile(params.id);
    appendAudit(db, nowIso(), user.username, 'NACH_FILE_DOWNLOAD', 'NACH_FILE', f.fileRef ?? null, {});
    return { status: 200, body: null, raw: { contentType: 'text/plain', data: f.content, fileName: `${f.fileRef}.csv` } };
  });
  on('POST', '/api/v1/nach/presentations/generate', ({ user }) => {
    require(user, P.nachAdmin);
    if (db.dayStatus !== 'OPEN') throw conflict('Business day is not open', 'presentation files are generated for the open business date');
    const at = nowIso();
    const settlement = addDays(db.businessDate, 1);
    const skipped: string[] = [];
    const items: Array<{ m: Mandate; loan: StoredLoan; amount: number; dueDate: string; attempt: number }> = [];
    for (const m of I().mandates.filter((x) => x.status === 'ACTIVE')) {
      const loan = loanOf(db, m.loanId);
      // The earliest unpaid demand, else the next instalment when it falls due within 15 days (a mock shortcut: the
      // backend presents what is due on the settlement date).
      const taken = (dueDate: string) => I().presentations.some((p) => p.loanId === loan.id && p.dueDate === dueDate && ['GENERATED', 'SUCCESS'].includes(p.status ?? ''));
      const open = loan.state.status !== 'ACTIVE' ? [] : loan.state.demands.map((dm) => ({ dueDate: dm.dueDate, instalment: dm.principalDue + dm.interestDue - dm.principalPaid - dm.interestPaid - dm.principalRescheduled - dm.interestCapitalised })).filter((x) => x.instalment > 0);
      const next = loan.state.status === 'ACTIVE' ? loan.state.rows[loan.state.raised] : undefined;
      const due = open.find((x) => !taken(x.dueDate)) ?? (!open.length && next && next.dueDate <= addDays(settlement, 15) && !taken(next.dueDate) ? { dueDate: next.dueDate, instalment: next.instalment } : undefined);
      const amount = due?.instalment ?? 0;
      // one debit in flight per loan: the next demand waits for the bank's answer to this one
      if (!due || amount <= 0 || I().presentations.some((p) => p.loanId === loan.id && p.status === 'GENERATED')) continue;
      if (amount > C.toPaise(m.maxAmount ?? '0')) {
        skipped.push(`${loan.loanNo}: ${C.fromPaise(amount)} is above the mandate limit`);
        continue;
      }
      items.push({ m, loan, amount, dueDate: due.dueDate, attempt: I().presentations.filter((p) => p.loanId === loan.id && p.dueDate === due.dueDate).length + 1 });
    }
    if (!items.length) return ok([]);
    const compact = settlement.replace(/-/g, '');
    const fileRef = `NP${compact}-${String(I().nachFiles.filter((f) => f.direction === 'PRESENTATION' && f.settlementDate === settlement).length + 1).padStart(2, '0')}`;
    const id = uuid();
    const total = items.reduce((s, x) => s + x.amount, 0);
    const lines = [NACH_HEADER, `H,${fileRef},NACH00000000001234,HDFC,${settlement},P,,,,,,,,,,,,,,,,`];
    items.forEach((x, i) => {
      const itemRef = `${fileRef}-${String(i + 1).padStart(6, '0')}`;
      // The mock keeps no account numbers: the file carries the masked form (a real file carries the full number).
      lines.push(`D,${fileRef},,,,,${i + 1},${itemRef},${x.m.umrn},${x.m.debitAccountMasked},${x.m.ifsc},${x.m.accountType},${customerName(db, x.loan)},${C.fromPaise(x.amount)},${x.loan.loanNo},,,,,,,`);
      I().presentations.push({ id: uuid(), fileId: id, mandateId: x.m.id!, itemRef, loanId: x.loan.id, loanNo: x.loan.loanNo, dueDate: x.dueDate, settlementDate: settlement, amount: C.fromPaise(x.amount), attemptNo: x.attempt, status: 'GENERATED', returnCode: null, returnReason: null, posting: null, postingNote: null, loanTxnId: null, bounceCharge: null, bounceChargeNote: null, representOn: null, representNote: null });
    });
    lines.push(`T,${fileRef},,,,,,,,,,,,,,,,,${items.length},${C.fromPaise(total)},,`);
    const f: IntegrationDb['nachFiles'][number] = { id, direction: 'PRESENTATION', fileRef, format: 'GENERIC', encoding: 'CSV', settlementDate: settlement, recordCount: items.length, totalAmount: C.fromPaise(total), successCount: null, successAmount: null, sha256: `mock-${id}`, status: 'GENERATED', summary: { skipped }, error: null, createdBy: user.username, createdAt: at, processedAt: null, content: lines.join('\r\n') + '\r\n' };
    I().nachFiles.push(f);
    appendAudit(db, at, user.username, 'NACH_PRESENTATION_FILE', 'NACH_FILE', fileRef, { records: items.length, total: f.totalAmount });
    return ok([fileView(f)]);
  });
  on('GET', '/api/v1/nach/presentations/pending', ({ user }) => (require(user, P.nachAdmin), ok(I().presentations.filter((p) => p.posting === 'PENDING' || p.bounceCharge === 'PENDING').map(presView))));

  function receive(text: string, by: string): NachFile {
    if (!text.trim()) throw bad('the file is empty');
    const at = nowIso();
    const sha = `mock-${text.length}-${[...text].reduce((h, ch) => (h * 31 + ch.charCodeAt(0)) >>> 0, 7).toString(16)}`;
    const known = I().nachFiles.find((f) => f.direction === 'RESPONSE' && f.sha256 === sha);
    if (known) throw conflict('File already received', 'this response file was already received', { fileId: known.id });
    const f: IntegrationDb['nachFiles'][number] = { id: uuid(), direction: 'RESPONSE', fileRef: `PENDING-${sha.slice(5, 21)}`, format: 'GENERIC', encoding: 'CSV', settlementDate: null, recordCount: null, totalAmount: null, successCount: null, successAmount: null, sha256: sha, status: 'RECEIVED', summary: null, error: null, createdBy: by, createdAt: at, processedAt: null, content: text };
    I().nachFiles.push(f);
    const reject = (status: string, error: string) => Object.assign(f, { status, error, processedAt: at });
    const rows = text.split(/\r?\n/).filter((l) => l.trim()).map((l) => l.split(','));
    const head = rows.shift() ?? [];
    const col = (row: string[], name: string) => (row[head.indexOf(name)] ?? '').trim();
    const h = rows.find((x) => x[0] === 'H');
    const t = rows.find((x) => x[0] === 'T');
    const details = rows.filter((x) => x[0] === 'D');
    if (head.join(',') !== NACH_HEADER || !h || !t) return fileView(reject('REJECTED', 'not a GENERIC CSV file: the header, H or T record is missing'));
    if (col(h, 'direction') !== 'R') return fileView(reject('REJECTED', 'this is not a response file (direction R)'));
    const total = details.reduce((s, d) => s + C.toPaise(col(d, 'amount') || '0'), 0);
    const okRows = details.filter((d) => col(d, 'status') === '1');
    const okTotal = okRows.reduce((s, d) => s + C.toPaise(col(d, 'amount') || '0'), 0);
    if (Number(col(t, 'count')) !== details.length || C.toPaise(col(t, 'total_amount') || '0') !== total || Number(col(t, 'success_count')) !== okRows.length || C.toPaise(col(t, 'success_amount') || '0') !== okTotal) {
      return fileView(reject('REJECTED', 'the control totals in the trailer do not match the detail records; nothing was processed'));
    }
    const fileRef = col(h, 'file_ref');
    const pres = I().nachFiles.find((x) => x.direction === 'PRESENTATION' && x.fileRef === fileRef);
    if (!pres) return fileView(reject('REJECTED', `the response names presentation file ${fileRef}, which does not exist`));
    const controlKey = [fileRef, details.length, total, okRows.length, okTotal].join('|');
    if (I().nachFiles.some((x) => x.direction === 'RESPONSE' && x.status === 'PROCESSED' && x.controlKey === controlKey)) return fileView(reject('DUPLICATE', `a response with the same control totals was already processed for ${fileRef}`));
    let success = 0;
    let bounced = 0;
    let alreadyRecorded = 0;
    const errors: string[] = [];
    const outcomes: NachPresentation[] = [];
    for (const d of details) {
      const itemRef = col(d, 'item_ref');
      const p = I().presentations.find((x) => x.itemRef === itemRef && x.fileId === pres.id);
      if (!p) {
        errors.push(`${itemRef}: no such presentation in ${fileRef}`);
        continue;
      }
      if (p.status !== 'GENERATED') {
        alreadyRecorded++;
        outcomes.push(presView(p));
        continue;
      }
      if (C.toPaise(col(d, 'amount') || '0') !== C.toPaise(p.amount ?? '0')) {
        errors.push(`${itemRef}: amount ${col(d, 'amount')} differs from the amount presented`);
        continue;
      }
      const loan = loanOf(db, p.loanId);
      if (col(d, 'status') === '1') {
        const e = postReceipt(db, loan, p.amount ?? '0', at, 'NACH');
        Object.assign(p, { status: 'SUCCESS', posting: 'POSTED', loanTxnId: e.id });
        success++;
      } else {
        const code = col(d, 'return_code');
        const reason = NACH_RETURN_REASONS.find((x) => x.code === code);
        const again = !!reason?.representable && (p.attemptNo ?? 1) < 3;
        Object.assign(p, { status: 'BOUNCED', returnCode: code, returnReason: reason?.description ?? 'Unknown return reason', bounceCharge: 'NOT_CHARGED', bounceChargeNote: 'the mock does not post bounce charges', representOn: again ? addDays(db.businessDate, 3) : null, representNote: again ? null : reason?.representable ? 'the presentation limit is reached' : 'this return reason is not presented again' });
        bounced++;
        emit(db, at, 'payment.bounced', loan.id);
        notify(db, at, 'PAYMENT_BOUNCED', loan, p.id!);
      }
      outcomes.push(presView(p));
    }
    Object.assign(f, { status: 'PROCESSED', controlKey, fileRef: `NR-${fileRef}-${I().nachFiles.filter((x) => x.direction === 'RESPONSE' && x.status === 'PROCESSED').length + 1}`, settlementDate: col(h, 'settlement_date') || pres.settlementDate, recordCount: details.length, totalAmount: C.fromPaise(total), successCount: okRows.length, successAmount: C.fromPaise(okTotal), summary: { success, bounced, alreadyRecorded, errors, rows: outcomes }, processedAt: at });
    appendAudit(db, at, by, 'NACH_RESPONSE_RECEIVED', 'NACH_FILE', f.fileRef ?? null, { success, bounced });
    return fileView(f);
  }
  on('POST', '/api/v1/nach/responses', ({ user, body }) => (require(user, P.nachAdmin), ok(receive(typeof body === 'string' ? body : '', user.username))));
  on('POST', '/api/v1/nach/files/{id}/simulate-response', ({ user, params }) => {
    require(user, P.integrationSimulate);
    const f = nachFile(params.id);
    if (f.direction !== 'PRESENTATION') throw notFound('Presentation file');
    const pres = I().presentations.filter((p) => p.fileId === f.id);
    // The simulated bank returns the debit of every loan that is already overdue (04, balance insufficient).
    const lines = [NACH_HEADER, `H,${f.fileRef},NACH00000000001234,HDFC,${f.settlementDate},R,,,,,,,,,,,,,,,,`];
    let okCount = 0;
    let okTotal = 0;
    pres.forEach((p, i) => {
      const bounce = (loanOf(db, p.loanId).state.dpd ?? 0) > 0;
      if (!bounce) {
        okCount++;
        okTotal += C.toPaise(p.amount ?? '0');
      }
      lines.push(`D,${f.fileRef},,,,,${i + 1},${p.itemRef},,,,,,${p.amount},,${bounce ? 0 : 1},${bounce ? '04' : ''},SIMBANK${String(i + 1).padStart(6, '0')},,,,`);
    });
    lines.push(`T,${f.fileRef},,,,,,,,,,,,,,,,,${pres.length},${f.totalAmount},${okCount},${C.fromPaise(okTotal)}`);
    return ok(receive(lines.join('\r\n') + '\r\n', user.username));
  });

  // ---- webhooks
  const endpointView = ({ pendingFor: _p, kid: _k, down: _d, ...e }: IntegrationDb['endpoints'][number]): WebhookEndpoint => e;
  const endpointOf = (id: string) => {
    const e = I().endpoints.find((x) => x.id === id);
    if (!e) throw notFound('Webhook endpoint');
    return e;
  };
  const checkedEndpoint = (b: WebhookEndpointInput) => {
    const errors: FieldProblem[] = [];
    if (!b.name?.trim() || b.name.length > 80) errors.push({ field: 'name', message: 'name is required (at most 80 characters)' });
    if (!/^https:\/\/[a-z0-9-]+(\.[a-z0-9-]+)+(:(443|8443))?(\/[^\s]*)?$/i.test(b.url ?? '') || /^https:\/\/(localhost|[0-9.]+)([:/]|$)/i.test(b.url ?? '')) errors.push({ field: 'url', message: 'url: must be https with a public DNS name, port 443 or 8443' });
    if (!b.eventTypes?.length) errors.push({ field: 'eventTypes', message: `eventTypes: choose at least one of ${Object.keys(WEBHOOK_EVENT_FIELDS).join(', ')}` });
    for (const t of b.eventTypes ?? []) if (!WEBHOOK_EVENT_FIELDS[t]) errors.push({ field: 'eventTypes', message: `unknown event type ${t}` });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    return { name: b.name.trim(), url: b.url.trim(), eventTypes: [...new Set(b.eventTypes)].sort() };
  };
  on('GET', '/api/v1/webhooks/event-types', ({ user }) => (require(user, P.webhookView), ok({ eventTypes: WEBHOOK_EVENT_FIELDS, signatureHeader: 'X-CoreBanking-Signature', eventIdHeader: 'X-CoreBanking-Event-Id', recommendedToleranceSeconds: 300, retryWaitsSeconds: [60, 300, 1800, 7200, 43200] })));
  on('GET', '/api/v1/webhooks/endpoints', ({ user }) => (require(user, P.webhookView), ok(I().endpoints.map(endpointView))));
  on('POST', '/api/v1/webhooks/endpoints', ({ user, body }) => {
    require(user, P.webhookAdmin);
    const p = checkedEndpoint((body ?? {}) as WebhookEndpointInput);
    if (I().endpoints.some((e) => e.name?.toLowerCase() === p.name.toLowerCase()) || pending('WEBHOOK_CREATE', (d) => String(d.name).toLowerCase() === p.name.toLowerCase())) throw conflict('Endpoint already exists', `an endpoint named ${p.name} already exists`);
    const id = uuid();
    return propose(user, 'WEBHOOK_ENDPOINT', 'CREATE', payload('WEBHOOK_CREATE', { id, ...p }, 1, user.username), { id, ...p }, null, id);
  });
  on('PUT', '/api/v1/webhooks/endpoints/{id}', ({ user, params, body }) => {
    require(user, P.webhookAdmin);
    const e = endpointOf(params.id);
    const p = checkedEndpoint((body ?? {}) as WebhookEndpointInput);
    if (pending('WEBHOOK_UPDATE', (d) => d.id === e.id)) throw alreadyPending(`Endpoint ${e.name}`);
    return propose(user, 'WEBHOOK_ENDPOINT', 'UPDATE', payload('WEBHOOK_UPDATE', { id: e.id, ...p }, 1, user.username), { id: e.id, ...p }, { name: e.name, url: e.url, eventTypes: e.eventTypes }, e.id);
  });
  on('POST', '/api/v1/webhooks/endpoints/{id}/actions', ({ user, params, body }) => {
    require(user, P.webhookAdmin);
    const e = endpointOf(params.id);
    const action = (body as { action?: string } | null)?.action ?? '';
    if (!['DISABLE', 'ENABLE', 'ROTATE_SECRET'].includes(action)) throw bad(`unknown action ${action}`, [{ field: 'action', message: 'DISABLE, ENABLE or ROTATE_SECRET' }]);
    if ((action === 'DISABLE' && e.status !== 'ACTIVE') || (action === 'ENABLE' && e.status === 'ACTIVE')) throw conflict('Nothing to change', `the endpoint is ${e.status}`);
    if (pending('WEBHOOK_ACTION', (d) => d.id === e.id)) throw alreadyPending(`Endpoint ${e.name}`);
    return propose(user, 'WEBHOOK_ENDPOINT', action, payload('WEBHOOK_ACTION', { id: e.id, name: e.name, action }, 1, user.username), { id: e.id, name: e.name }, { name: e.name, status: e.status }, e.id);
  });
  on('POST', '/api/v1/webhooks/endpoints/{id}/secret', ({ user, params }) => {
    require(user, P.webhookAdmin);
    const e = endpointOf(params.id);
    if (!e.pendingFor) throw conflict('No secret to collect', 'there is no secret to collect: propose a rotation first');
    if (e.pendingFor !== user.username) throw forbidden('the secret can be collected only by the user who proposed it');
    e.kid += 1;
    Object.assign(e, { pendingFor: null, secretPending: false, keysInForce: [`k${e.kid}`, ...(e.kid > 1 ? [`k${e.kid - 1}`] : [])] });
    appendAudit(db, nowIso(), user.username, 'WEBHOOK_SECRET_COLLECTED', 'WEBHOOK_ENDPOINT', e.id ?? null, { keyId: `k${e.kid}` });
    // Generated now and returned once; the mock keeps nothing of it.
    return ok({ keyId: `k${e.kid}`, secret: randomSecret('whsec_'), previousSecretValidHours: e.kid > 1 ? 24 : 0 });
  });
  const replay = (d: WebhookDelivery, by: string): WebhookDelivery => {
    const e = endpointOf(d.endpointId ?? '');
    if (e.status !== 'ACTIVE') throw conflict('Endpoint is disabled', `endpoint ${e.name} is ${e.status}`);
    const at = nowIso();
    const next = deliver(e, { id: uuid(), endpointId: e.id, eventId: d.eventId, eventType: d.eventType, aggregateId: d.aggregateId, replayOf: d.id, requestedBy: by, createdAt: at }, at);
    I().deliveries.push(next);
    return next;
  };
  on('POST', '/api/v1/webhooks/endpoints/{id}/replay-dead', ({ user, params }) => {
    require(user, P.webhookAdmin);
    const e = endpointOf(params.id);
    const dead = I().deliveries.filter((d) => d.endpointId === e.id && d.status === 'DEAD' && !I().deliveries.some((x) => x.replayOf === d.id));
    const created = dead.map((d) => replay(d, user.username).id);
    return ok({ replayed: created.length, deliveryIds: created });
  });
  const deliveryList = ({ attemptLog: _a, ...d }: WebhookDelivery): WebhookDelivery => d;
  on('GET', '/api/v1/webhooks/deliveries', ({ user, url }) => {
    require(user, P.webhookView);
    const [endpointId, status] = [url.searchParams.get('endpointId'), url.searchParams.get('status')];
    return ok(I().deliveries.filter((d) => (!endpointId || d.endpointId === endpointId) && (!status || d.status === status)).sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')).map(deliveryList));
  });
  const deliveryOf = (id: string) => {
    const d = I().deliveries.find((x) => x.id === id);
    if (!d) throw notFound('Delivery');
    return d;
  };
  on('GET', '/api/v1/webhooks/deliveries/{id}', ({ user, params }) => (require(user, P.webhookView), ok(deliveryOf(params.id))));
  on('POST', '/api/v1/webhooks/deliveries/{id}/replay', ({ user, params }) => {
    require(user, P.webhookAdmin);
    const next = replay(deliveryOf(params.id), user.username);
    appendAudit(db, nowIso(), user.username, 'WEBHOOK_REPLAY', 'WEBHOOK_DELIVERY', params.id, { newDeliveryId: next.id });
    return ok(next);
  });

  // ---- API clients
  const clientView = ({ pendingFor: _p, ...c }: IntegrationDb['clients'][number]): ApiClientRecord => c;
  const clientOf = (id: string) => {
    const c = I().clients.find((x) => x.clientId === id);
    if (!c) throw notFound('API client');
    return c;
  };
  const checkedScopes = (scopes: unknown): string[] => {
    const s = [...new Set(Array.isArray(scopes) ? (scopes as string[]) : [])].sort();
    if (!s.length) throw bad('at least one scope is required', [{ field: 'scopes', message: 'Choose at least one' }]);
    const refused = s.filter((x) => !GRANTABLE_SCOPES.includes(x));
    if (refused.length) throw bad(`these scopes cannot be granted to an API client: [${refused.join(', ')}]`, [{ field: 'scopes', message: 'Not grantable' }]);
    return s;
  };
  const sensitive = (s: string[]) => s.filter((x) => SENSITIVE_SCOPES.includes(x));
  on('GET', '/api/v1/api-clients', ({ user }) => (require(user, P.apiclientView), ok(I().clients.map(clientView))));
  on('GET', '/api/v1/api-clients/scopes', ({ user }) => (require(user, P.apiclientView), ok({ grantable: GRANTABLE_SCOPES, sensitive: SENSITIVE_SCOPES })));
  on('GET', '/api/v1/api-clients/{clientId}', ({ user, params }) => (require(user, P.apiclientView), ok(clientView(clientOf(params.clientId)))));
  on('POST', '/api/v1/api-clients', ({ user, body }) => {
    require(user, P.apiclientAdmin);
    const b = (body ?? {}) as ApiClientInput;
    const raw = (b.clientId ?? '').trim().replace(/^ext-/, '');
    if (!/^[a-z0-9][a-z0-9-]{1,49}$/.test(raw)) throw bad('clientId: lower-case letters, digits and hyphens (2 to 50 characters)', [{ field: 'clientId', message: 'Lower-case letters, digits and hyphens' }]);
    if (!b.name?.trim() || b.name.length > 80) throw bad('name is required (at most 80 characters)', [{ field: 'name', message: 'Required' }]);
    const s = checkedScopes(b.scopes);
    if (!db.branches.some((x) => x.code === b.homeBranch)) throw bad('homeBranch is required', [{ field: 'homeBranch', message: 'Unknown branch' }]);
    const clientId = `ext-${raw}`;
    if (I().clients.some((c) => c.clientId === clientId) || pending('CLIENT_CREATE', (d) => d.clientId === clientId)) throw conflict('API client already exists', `API client ${clientId} already exists`);
    const shown = { clientId, name: b.name.trim(), scopes: s, sensitiveScopes: sensitive(s), homeBranch: b.homeBranch, allBranches: !!b.allBranches };
    return propose(user, 'API_CLIENT', sensitive(s).length ? 'CREATE_SENSITIVE' : 'CREATE', payload('CLIENT_CREATE', shown, sensitive(s).length ? 2 : 1, user.username), shown, null, clientId);
  });
  on('PUT', '/api/v1/api-clients/{clientId}/scopes', ({ user, params, body }) => {
    require(user, P.apiclientAdmin);
    const c = clientOf(params.clientId);
    const s = checkedScopes((body as { scopes?: unknown } | null)?.scopes);
    if (s.join() === [...(c.scopes ?? [])].sort().join()) throw bad('Nothing to change');
    if (pending('CLIENT_SCOPES', (d) => d.clientId === c.clientId)) throw alreadyPending(`API client ${c.clientId}`);
    const added = sensitive(s).filter((x) => !c.scopes?.includes(x));
    const shown = { clientId: c.clientId, scopes: s, sensitiveScopes: sensitive(s) };
    return propose(user, 'API_CLIENT', added.length ? 'SCOPES_SENSITIVE' : 'SCOPES', payload('CLIENT_SCOPES', shown, added.length ? 2 : 1, user.username), shown, { clientId: c.clientId, scopes: c.scopes }, c.clientId);
  });
  on('POST', '/api/v1/api-clients/{clientId}/actions', ({ user, params, body }) => {
    require(user, P.apiclientAdmin);
    const c = clientOf(params.clientId);
    const action = (body as { action?: string } | null)?.action ?? '';
    if (!['ROTATE_SECRET', 'DISABLE', 'ENABLE'].includes(action)) throw bad(`unknown action ${action}`, [{ field: 'action', message: 'ROTATE_SECRET, DISABLE or ENABLE' }]);
    if ((action === 'DISABLE' && c.status !== 'ACTIVE') || (action === 'ENABLE' && c.status === 'ACTIVE')) throw conflict('Nothing to change', `the client is ${c.status}`);
    if (pending('CLIENT_ACTION', (d) => d.clientId === c.clientId)) throw alreadyPending(`API client ${c.clientId}`);
    const two = action === 'ENABLE' && sensitive(c.scopes ?? []).length > 0;
    return propose(user, 'API_CLIENT', two ? `${action}_SENSITIVE` : action, payload('CLIENT_ACTION', { clientId: c.clientId, action }, two ? 2 : 1, user.username), { clientId: c.clientId }, { clientId: c.clientId, status: c.status, scopes: c.scopes }, c.clientId);
  });
  on('POST', '/api/v1/api-clients/{clientId}/secret', ({ user, params }) => {
    require(user, P.apiclientAdmin);
    const c = clientOf(params.clientId);
    if (!c.pendingFor) throw conflict('No secret to collect', 'there is no secret to collect: propose a rotation first');
    if (c.pendingFor !== user.username) throw forbidden('the secret can be collected only by the user who proposed it');
    if (c.status !== 'ACTIVE') throw conflict('Client is disabled', 'the client is disabled');
    Object.assign(c, { pendingFor: null, secretPending: false, secretIssuedAt: nowIso() });
    appendAudit(db, nowIso(), user.username, 'API_CLIENT_SECRET_COLLECTED', 'API_CLIENT', c.clientId ?? null, {});
    return ok({ clientId: c.clientId, clientSecret: randomSecret(''), grantType: 'client_credentials' });
  });

  // ---- messages
  on('GET', '/api/v1/message-templates', ({ user }) => (require(user, P.messageView), ok(I().templates)));
  on('GET', '/api/v1/message-templates/variables', ({ user }) => (require(user, P.messageView), ok(MESSAGE_VARIABLES)));
  on('POST', '/api/v1/message-templates', ({ user, body }) => {
    require(user, P.messageAdmin);
    const b = (body ?? {}) as MessageTemplateInput;
    const allowed = MESSAGE_VARIABLES[b.code];
    if (!allowed) throw bad(`code must be one of [${Object.keys(MESSAGE_VARIABLES).join(', ')}]`, [{ field: 'code', message: 'Unknown code' }]);
    if (!['SMS', 'EMAIL'].includes(b.channel)) throw bad('channel must be SMS or EMAIL', [{ field: 'channel', message: 'SMS or EMAIL' }]);
    const language = b.language ?? 'en';
    if (!/^[a-z]{2}$/.test(language)) throw bad('language is a two-letter code', [{ field: 'language', message: 'Two letters' }]);
    if (!b.body?.trim()) throw bad('body is required', [{ field: 'body', message: 'Required' }]);
    const unknown = placeholders(b.body).filter((p) => !allowed.includes(p));
    if (unknown.length) throw bad(`placeholder '${unknown[0]}' is not available for ${b.code}; use [${allowed.join(', ')}]`, [{ field: 'body', message: `Unknown placeholder ${unknown[0]}` }]);
    if (b.channel === 'EMAIL') {
      if (!b.subject?.trim() || b.subject.length > 200) throw bad('subject is required for e-mail (at most 200 characters)', [{ field: 'subject', message: 'Required' }]);
      const bad2 = placeholders(b.subject).filter((p) => !allowed.includes(p));
      if (bad2.length) throw bad(`placeholder '${bad2[0]}' is not available for ${b.code}`, [{ field: 'subject', message: `Unknown placeholder ${bad2[0]}` }]);
    } else if (!/^[0-9]+$/.test(b.dltEntityId ?? '') || !/^[0-9]+$/.test(b.dltTemplateId ?? '') || !b.dltHeader?.trim()) {
      throw bad('an SMS template needs dltEntityId and dltTemplateId (digits) and dltHeader (the registered sender id)', [{ field: 'dltTemplateId', message: 'DLT registration is required for SMS' }]);
    }
    const key = `${b.code}/${b.channel}/${language}`;
    if (pending('MESSAGE_TEMPLATE', (d) => `${d.code}/${d.channel}/${d.language}` === key)) throw alreadyPending(`Template ${key}`);
    const sms = b.channel === 'SMS';
    const shown = { code: b.code, channel: b.channel, language, category: b.category ?? 'TRANSACTIONAL', subject: sms ? null : b.subject!.trim(), body: b.body, dltEntityId: sms ? b.dltEntityId : null, dltTemplateId: sms ? b.dltTemplateId : null, dltHeader: sms ? b.dltHeader : null, status: b.status ?? 'ACTIVE' };
    const current = I().templates.find((t) => t.code === b.code && t.channel === b.channel && t.language === language);
    return propose(user, 'MESSAGE_TEMPLATE', current ? 'UPDATE' : 'CREATE', payload('MESSAGE_TEMPLATE', shown, 1, user.username), shown, current ? { ...current } : null, key);
  });
  on('GET', '/api/v1/messages', ({ user, url }) => {
    require(user, P.messageView);
    const q = url.searchParams;
    return ok(
      I().messages
        .filter((m) => (!q.get('customerId') || m.customerId === q.get('customerId')) && (!q.get('loanId') || m.loanId === q.get('loanId')) && (!q.get('status') || m.status === q.get('status')))
        .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
        .map(({ eventKey: _e, ...m }) => m),
    );
  });
  on('POST', '/api/v1/customers/{id}/message-opt-outs', ({ user, params, body }) => {
    require(user, P.messageAdmin);
    const b = (body ?? {}) as { channel?: string; optOut?: boolean };
    if (!['SMS', 'EMAIL'].includes(b.channel ?? '')) throw bad('channel must be SMS or EMAIL', [{ field: 'channel', message: 'SMS or EMAIL' }]);
    if (!db.customers.some((c) => c.id === params.id)) throw notFound('Customer');
    db.integration.optOuts = I().optOuts.filter((o) => !(o.customerId === params.id && o.channel === b.channel));
    if (b.optOut ?? true) I().optOuts.push({ customerId: params.id, channel: b.channel! });
    return ok({ customerId: params.id, optedOut: I().optOuts.filter((o) => o.customerId === params.id).map((o) => o.channel).sort() });
  });
}
