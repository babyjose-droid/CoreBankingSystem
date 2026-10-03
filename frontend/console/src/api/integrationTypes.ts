/**
 * Shapes of the integration module's responses. The contract types every integration response as the open
 * `IntegrationRecord` (additionalProperties), so these are written from the backend's views and are all optional
 * on purpose: the console must not break when a field is absent.
 */
import type { Money, S } from './types';

export type ProviderKind = S['ProviderConfigInput']['kind'];
export type ProviderConfigInput = S['ProviderConfigInput'];
export interface ProviderSpec {
  kind?: ProviderKind;
  provider?: string;
  settings?: string[];
  secrets?: string[];
  requiredSecrets?: string[];
  verified?: boolean;
  note?: string | null;
  enabledInDeployment?: boolean;
}
export interface ProviderConfig {
  id?: string;
  kind?: ProviderKind;
  provider?: string;
  settings?: Record<string, string>;
  /** Never a value: only that it is set and its last four characters. */
  secrets?: Record<string, { set?: boolean; last4?: string }>;
  status?: string;
  version?: number;
  updatedBy?: string;
  updatedAt?: string;
  verified?: boolean;
  enabledInDeployment?: boolean;
}

export interface StatusEvent {
  at?: string;
  from?: string | null;
  to?: string | null;
  source?: string | null;
  actor?: string | null;
}
export type BeneficiaryInput = S['BeneficiaryInput'];
export interface Beneficiary {
  id?: string;
  accountMasked?: string;
  ifsc?: string;
  validation?: string;
  validationNote?: string | null;
  createdBy?: string;
  createdAt?: string;
}
export interface Payout {
  id?: string;
  reference?: string;
  loanId?: string;
  loanNo?: string;
  branch?: string;
  attemptNo?: number;
  amount?: Money;
  mode?: string | null;
  provider?: string | null;
  status?: string;
  providerRef?: string | null;
  utr?: string | null;
  failureCode?: string | null;
  failureReason?: string | null;
  stp?: boolean;
  needsAction?: boolean;
  actionNote?: string | null;
  /** REVERSED, PROPOSED or PARKED once a failed payout's disbursement has been dealt with. */
  failureAction?: string | null;
  reversalApprovalId?: string | null;
  attempts?: number;
  lastError?: string | null;
  createdAt?: string;
  sentAt?: string | null;
  completedAt?: string | null;
  beneficiaryAccountMasked?: string | null;
  beneficiaryIfsc?: string | null;
  events?: StatusEvent[];
}

export type CollectionOrderInput = S['CollectionOrderInput'];
export interface CollectionOrder {
  id?: string;
  reference?: string;
  loanId?: string;
  loanNo?: string;
  amount?: Money;
  methods?: string[];
  provider?: string;
  paymentUrl?: string | null;
  status?: string;
  expiresAt?: string | null;
  createdBy?: string;
  createdAt?: string;
}
export type ReconCategory = 'MATCHED' | 'PAYMENT_NOT_POSTED' | 'POSTED_NOT_SETTLED' | 'SETTLED_NOT_RECEIVED' | 'AMOUNT_MISMATCH' | 'REFUND_DUE';
export interface ReconRow {
  category?: ReconCategory;
  provider?: string;
  providerPaymentId?: string;
  paymentId?: string | null;
  loanId?: string | null;
  loanNo?: string | null;
  paymentStatus?: string | null;
  paymentAmount?: Money | null;
  paidAt?: string | null;
  valueDate?: string | null;
  loanTxnId?: string | null;
  review?: string | null;
  lastError?: string | null;
  settledAmount?: Money | null;
  settlementFee?: Money | null;
  settledOn?: string | null;
  settlementUtr?: string | null;
}
export type PaymentResolution = S['PaymentResolution'];
export interface SettlementUploadResult {
  fileRef?: string;
  rows?: number;
  added?: number;
  alreadyLoaded?: number;
}

export type MandateInput = S['MandateInput'];
export type MandateStatusUpdate = S['MandateStatusUpdate'];
export interface Mandate {
  id?: string;
  mandateRef?: string;
  loanId?: string;
  loanNo?: string;
  umrn?: string | null;
  status?: string;
  maxAmount?: Money;
  frequency?: string;
  startDate?: string;
  endDate?: string | null;
  debitAccountMasked?: string;
  ifsc?: string;
  accountType?: string;
  sponsorBankCode?: string | null;
  utilityCode?: string | null;
  provider?: string | null;
  authenticationUrl?: string | null;
  rejectCode?: string | null;
  rejectReason?: string | null;
  lastError?: string | null;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
  events?: StatusEvent[];
}
export interface NachPresentation {
  id?: string;
  itemRef?: string;
  loanId?: string;
  loanNo?: string;
  dueDate?: string;
  settlementDate?: string;
  amount?: Money;
  attemptNo?: number;
  status?: string;
  returnCode?: string | null;
  returnReason?: string | null;
  posting?: string | null;
  postingNote?: string | null;
  loanTxnId?: string | null;
  bounceCharge?: string | null;
  bounceChargeNote?: string | null;
  representOn?: string | null;
  representNote?: string | null;
}
export interface NachFile {
  id?: string;
  direction?: 'PRESENTATION' | 'RESPONSE';
  fileRef?: string;
  format?: string;
  encoding?: string;
  settlementDate?: string | null;
  recordCount?: number | null;
  totalAmount?: Money | null;
  successCount?: number | null;
  successAmount?: Money | null;
  sha256?: string;
  status?: string;
  summary?: { skipped?: string[]; success?: number; bounced?: number; alreadyRecorded?: number; errors?: string[]; rows?: NachPresentation[] } | null;
  error?: string | null;
  createdBy?: string;
  createdAt?: string;
  processedAt?: string | null;
}

export type WebhookEndpointInput = S['WebhookEndpointInput'];
export type WebhookEventType = WebhookEndpointInput['eventTypes'][number];
export interface WebhookEndpoint {
  id?: string;
  name?: string;
  url?: string;
  eventTypes?: string[];
  status?: string;
  version?: number;
  secretPending?: boolean;
  createdBy?: string;
  createdAt?: string;
  keysInForce?: string[];
}
export interface WebhookEventTypes {
  eventTypes?: Record<string, string[]>;
  signatureHeader?: string;
  eventIdHeader?: string;
  recommendedToleranceSeconds?: number;
  retryWaitsSeconds?: number[];
}
export interface WebhookSecret {
  keyId?: string;
  secret?: string;
  previousSecretValidHours?: number;
}
export interface WebhookDelivery {
  id?: string;
  endpointId?: string;
  eventId?: string;
  eventType?: string;
  aggregateId?: string | null;
  status?: string;
  attempts?: number;
  nextAttemptAt?: string | null;
  lastStatus?: number | null;
  lastError?: string | null;
  replayOf?: string | null;
  requestedBy?: string | null;
  createdAt?: string;
  deliveredAt?: string | null;
  attemptLog?: Array<{ attemptNo?: number; at?: string; statusCode?: number | null; durationMs?: number | null; error?: string | null }>;
}

export type ApiClientInput = S['ApiClientInput'];
export interface ApiClientRecord {
  clientId?: string;
  name?: string;
  status?: string;
  scopes?: string[];
  homeBranch?: string;
  allBranches?: boolean;
  serviceUsername?: string | null;
  secretPending?: boolean;
  secretIssuedAt?: string | null;
  createdBy?: string;
  createdAt?: string;
}
export interface ApiClientScopeList {
  grantable?: string[];
  sensitive?: string[];
}
export interface ApiClientSecret {
  clientId?: string;
  clientSecret?: string;
  grantType?: string;
}

export type MessageTemplateInput = S['MessageTemplateInput'];
export interface MessageTemplate extends Partial<MessageTemplateInput> {
  version?: number;
  updatedBy?: string;
  updatedAt?: string;
  dltForm?: string | null;
}
export type MessageVariables = Record<string, string[]>;
export interface MessageLogRow {
  id?: string;
  templateCode?: string;
  channel?: string;
  category?: string;
  customerId?: string | null;
  loanId?: string | null;
  recipientMasked?: string;
  status?: string;
  suppressReason?: string | null;
  provider?: string | null;
  providerRef?: string | null;
  attempts?: number;
  lastError?: string | null;
  createdAt?: string;
  sentAt?: string | null;
}
export type SimulatedCallback = S['SimulatedCallback'];
export interface SimulatedCallbackResult {
  eventId?: string;
  receipt?: string;
}
