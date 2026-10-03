import type { components, paths } from './schema';

export type S = components['schemas'];
export type { paths };
export type Money = S['Money'];
export type Problem = S['Problem'];
export type Me = S['Me'];
export type BusinessDay = S['BusinessDay'];
export type Branch = S['Branch'];
export type Holiday = S['Holiday'];
export type TaxRate = S['TaxRate'];
export type Benchmark = S['Benchmark'];
export type BenchmarkRate = S['BenchmarkRate'];
export type BenchmarkInput = S['BenchmarkInput'];
export type BenchmarkRateInput = S['BenchmarkRateInput'];
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
export type EnumValueInput = S['EnumValueInput'];
export type EnumerationType = S['EnumerationType'];
export type SystemProperty = S['SystemProperty'];
export type PropertyChange = S['PropertyChange'];
export type BranchSet = S['BranchSet'];
export type Staff = S['Staff'];
export type State = S['State'];
export type PincodePlace = S['PincodePlace'];
export type VoucherUploadResult = Omit<S['VoucherUploadResult'], 'approvals'> & { approvals?: Approval[] };
export type LoanStatus = S['LoanStatus'];
export type AssetClass = S['AssetClass'];
export type FeeRule = S['FeeRule'];
export type LoanProduct = S['LoanProduct'];
/** Contract defaults of the product options added with the lending completion (P2-6). */
export const LOAN_PRODUCT_DEFAULTS = {
  interestBasis: 'DAILY_REDUCING',
  bpiMode: 'NONE',
  principalEvery: 1,
  multipleDisbursements: false,
  preEmi: false,
  topUpAllowed: false,
} as const satisfies Partial<LoanProduct>;
export type LoanApplication = S['LoanApplication'];
export type ScheduleRow = S['ScheduleRow'];
export type LoanKfs = S['LoanKfs'];
export type KfsFee = NonNullable<LoanKfs['fees']>[number];
export type LoanSummary = S['LoanSummary'];
export type Loan = S['Loan'];
export type LoanSchedule = S['LoanSchedule'];
export type LoanDemand = NonNullable<LoanSchedule['demands']>[number];
export type LoanCharge = NonNullable<LoanSchedule['charges']>[number];
export type LoanTxn = S['LoanTxn'];
export type PreclosureQuote = S['PreclosureQuote'];
export type CustomField = S['CustomField'];
export type CustomFieldInput = S['CustomFieldInput'];
export type CustomValues = Record<string, unknown>;
export type DeferredReceipt = S['DeferredReceipt'];
export type LoginSession = S['LoginSession'];
export type Job = S['Job'];
export type JobRun = S['JobRun'];
export type JobScheduleInput = S['JobScheduleInput'];
export type SupportAccess = S['SupportAccess'];
export type LoanProductTemplate = S['LoanProductTemplate'];
export type PrincipalRow = S['PrincipalRow'];
export type LoanTranches = S['LoanTranches'];
export type DisbursementSimulation = S['DisbursementSimulation'];
export type TransactionSimulation = S['TransactionSimulation'];
export type TransactionSimulationRequest = S['TransactionSimulationRequest'];
export type SanctionChangeRequest = S['SanctionChangeRequest'];
export type SanctionChangePreview = S['SanctionChangePreview'];
export type NpaOverrideRequest = S['NpaOverrideRequest'];
export type RepaymentFrequency = S['RepaymentFrequency'];
export type AmendmentRequest = S['AmendmentRequest'];
export type AmendmentKind = AmendmentRequest['kind'];
export type AmendmentPreview = S['AmendmentPreview'];
export type LoanAmendment = S['LoanAmendment'];
export type RestructureTerms = S['RestructureTerms'];
export type RestructureOption = S['RestructureOption'];
export type RestructureSimulation = S['RestructureSimulation'];
export type CancellationQuote = { total?: Money; asOf?: string };
export type LimitTxnType = S['LimitTxnType'];
export type AmountLimit = S['AmountLimit'];
export type AmountLimitInput = S['AmountLimitInput'];
export type RelationshipInput = S['RelationshipInput'];
export type RelationType = RelationshipInput['relationType'];
export type CustomerRelationship = S['CustomerRelationship'];
export type CustomerExposure = S['CustomerExposure'];
export type LoanParty = S['LoanParty'];
export type LoanPartyInput = NonNullable<LoanApplication['parties']>[number];
export type ConsentInput = S['ConsentInput'];
export type Consent = S['Consent'];
export type KycDocument = S['KycDocument'];
export type ReportDefinition = S['ReportDefinition'];
export type ReportRun = S['ReportRun'];
export type DpdBucket = S['DpdBucket'];
export type Dashboard = S['Dashboard'];
export type BulkApproveResult = { id?: string; ok?: boolean; error?: string };
export type VerifyResult = { intact: boolean; firstBrokenId?: number | null };
