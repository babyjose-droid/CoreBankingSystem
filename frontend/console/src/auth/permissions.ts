/**
 * Permission codes used by the console. The backend is the source of truth (GET /api/v1/me and the access-token
 * `permissions` claim); the console only hides what the user cannot do. Every call is still authorised server-side.
 */
export const P = {
  approvalView: 'approval:view',
  approvalApprove: 'approval:approve',
  customerView: 'customer:view',
  customerCreate: 'customer:create',
  glView: 'gl:view',
  glPropose: 'gl:head-propose',
  voucherCreate: 'voucher:create',
  voucherReverse: 'voucher:reverse',
  eodView: 'eod:view',
  eodRun: 'eod:run',
  eodSchedule: 'eod:schedule',
  branchView: 'branch:view',
  branchPropose: 'branch:propose',
  holidayView: 'holiday:view',
  holidayPropose: 'holiday:propose',
  taxRateView: 'tax:view',
  taxRatePropose: 'tax:propose',
  auditView: 'audit:view',
  glReports: 'gl:reports',
  productView: 'product:view',
  productPropose: 'product:propose',
  loanView: 'loan:view',
  loanCreate: 'loan:create',
  loanDisburse: 'loan:disburse',
  loanRepay: 'loan:repay',
  loanWaive: 'loan:waive',
  loanReverse: 'loan:reverse',
  loanAdmin: 'loan:admin',
  loanAmend: 'loan:amend',
  loanRestructure: 'loan:restructure',
  staffView: 'staff:view',
  staffPropose: 'staff:propose',
  masterView: 'master:view',
  masterPropose: 'master:propose',
} as const;

export type Permission = (typeof P)[keyof typeof P];

/** "All view" as used for the demo roles (audit:view is granted separately). */
export const VIEW_PERMISSIONS: Permission[] = [
  P.approvalView,
  P.customerView,
  P.glView,
  P.eodView,
  P.branchView,
  P.holidayView,
  P.taxRateView,
  P.glReports,
  P.productView,
  P.loanView,
  P.staffView,
  P.masterView,
];

export const PROPOSE_PERMISSIONS: Permission[] = [
  P.customerCreate,
  P.glPropose,
  P.voucherCreate,
  P.voucherReverse,
  P.eodSchedule,
  P.branchPropose,
  P.holidayPropose,
  P.taxRatePropose,
  P.productPropose,
  P.loanCreate,
  P.loanDisburse,
  P.loanRepay,
  P.loanWaive,
  P.loanReverse,
  P.loanAmend,
  P.loanRestructure,
  P.staffPropose,
  P.masterPropose,
];

export const ALL_PERMISSIONS: Permission[] = Object.values(P);

export function hasPermission(perms: readonly string[] | undefined, p: string): boolean {
  return !!perms && perms.includes(p);
}

export function hasAny(perms: readonly string[] | undefined, ps: readonly string[]): boolean {
  return ps.some((p) => hasPermission(perms, p));
}
