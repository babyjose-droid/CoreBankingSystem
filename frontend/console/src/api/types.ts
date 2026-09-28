import type { components, paths } from './schema';

type S = components['schemas'];
export type { paths };
export type Money = S['Money'];
export type Problem = S['Problem'];
export type Me = S['Me'];
export type BusinessDay = S['BusinessDay'];
export type Branch = S['Branch'];
export type Holiday = S['Holiday'];
export type TaxRate = S['TaxRate'];
export type EnumValue = S['EnumValue'];
export type ApprovalStatus = S['ApprovalStatus'];
/** The generated type has `current`/`proposed` as empty objects; widen them for the diff view. */
export type Approval = Omit<S['Approval'], 'current' | 'proposed'> & {
  current?: Record<string, unknown> | null;
  proposed?: Record<string, unknown>;
};
export type Decision = S['Decision'];
export type CustomerInput = S['CustomerInput'];
export type CustomerSummary = S['CustomerSummary'];
export type DedupeMatch = S['DedupeMatch'];
export type GlHead = S['GlHead'];
export type GlCategory = GlHead['category'];
export type VoucherLine = S['VoucherLine'];
export type VoucherInput = S['VoucherInput'];
export type Voucher = S['Voucher'];
export type TrialBalanceRow = S['TrialBalanceRow'];
export type LedgerEntry = S['LedgerEntry'];
export type StatementRow = S['StatementRow'];
export type EodStep = S['EodStep'];
export type EodException = S['EodException'];
export type EodRun = S['EodRun'];
export type EodSchedule = S['EodSchedule'];
export type AuditEvent = S['AuditEvent'];
export type BulkApproveResult = { id?: string; ok?: boolean; error?: string };
export type VerifyResult = { intact: boolean; firstBrokenId?: number | null };
