import type {
  Approval,
  AuditEvent,
  Branch,
  BusinessDay,
  CustomerInput,
  EodRun,
  EodSchedule,
  GlHead,
  Holiday,
  LedgerEntry,
  TaxRate,
  Voucher,
  VoucherInput,
} from '../api/types';

export interface StoredCustomer {
  id: string;
  customerNo: string;
  input: CustomerInput;
  kycStatus: 'PENDING' | 'VERIFIED';
  status: 'ACTIVE' | 'INACTIVE';
  createdAt: string;
}

export type StoredEntry = Required<Omit<LedgerEntry, 'narration'>> & { narration: string; voucherId: string | null };

/** Internal payload the mock keeps next to an approval so it can apply it later (never returned to clients). */
export type ApprovalPayload =
  | { kind: 'BRANCH'; branch: Branch }
  | { kind: 'HOLIDAY'; holidays: Holiday[] }
  | { kind: 'TAX_RATE'; rate: TaxRate }
  | { kind: 'GL_HEAD'; head: GlHead }
  | { kind: 'CUSTOMER'; input: CustomerInput }
  | { kind: 'VOUCHER'; input: VoucherInput }
  | { kind: 'VOUCHER_REVERSAL'; voucherId: string; reason: string }
  | { kind: 'EOD_SCHEDULE'; schedule: EodSchedule };

export interface StoredApproval {
  approval: Approval;
  payload: ApprovalPayload;
}

export interface AuditRecord extends Required<Omit<AuditEvent, 'entityId' | 'detail'>> {
  entityId: string | null;
  detail: Record<string, unknown> | null;
  prevHash: string;
  hash: string;
}

export interface MockDb {
  tenant: string;
  tenantName: string;
  businessDate: string;
  dayStatus: BusinessDay['status'];
  branches: Branch[];
  holidays: Holiday[];
  taxRates: TaxRate[];
  glHeads: GlHead[];
  customers: StoredCustomer[];
  customerSeq: number;
  vouchers: Voucher[];
  voucherSeq: number;
  entries: StoredEntry[];
  approvals: StoredApproval[];
  eodRuns: EodRun[];
  eodRunSeq: number;
  eodSchedule: EodSchedule;
  audit: AuditRecord[];
  idempotency: Map<string, { status: number; body: unknown }>;
  /** Fault injection for demos/tests: the named EOD step fails once. */
  eodFailAtStep: string | null;
}

let uuidCounter = 0;
/** crypto.randomUUID when available; deterministic-looking fallback otherwise. */
export function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  uuidCounter += 1;
  const hex = uuidCounter.toString(16).padStart(12, '0');
  return `00000000-0000-4000-8000-${hex}`;
}

/** FNV-1a 32-bit — good enough to demonstrate a tamper-evident hash chain in the mock. */
export function fnv1a(input: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, '0');
}

export function auditHash(prevHash: string, e: Omit<AuditRecord, 'hash' | 'prevHash'>): string {
  return fnv1a(prevHash + '|' + JSON.stringify([e.id, e.at, e.actor, e.action, e.entityType, e.entityId, e.detail]));
}

export function appendAudit(
  db: MockDb,
  at: string,
  actor: string,
  action: string,
  entityType: string,
  entityId: string | null,
  detail: Record<string, unknown> | null = null,
): AuditRecord {
  const prev = db.audit[db.audit.length - 1];
  const prevHash = prev ? prev.hash : '00000000';
  const base = { id: (prev?.id ?? 0) + 1, at, actor, action, entityType, entityId, detail };
  const rec: AuditRecord = { ...base, prevHash, hash: auditHash(prevHash, base) };
  db.audit.push(rec);
  return rec;
}

export function verifyAudit(db: MockDb): { intact: boolean; firstBrokenId: number | null } {
  let prevHash = '00000000';
  for (const rec of db.audit) {
    const { hash, prevHash: storedPrev, ...rest } = rec;
    if (storedPrev !== prevHash || auditHash(prevHash, rest) !== hash) return { intact: false, firstBrokenId: rec.id };
    prevHash = hash;
  }
  return { intact: true, firstBrokenId: null };
}
