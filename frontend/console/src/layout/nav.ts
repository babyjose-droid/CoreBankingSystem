import { P } from '../auth/permissions';

export interface NavItem {
  to: string;
  label: string;
  perm: string | null;
  badge?: 'approvals';
  end?: boolean;
}
export interface NavGroup {
  label: string | null;
  items: NavItem[];
}

export const NAV: NavGroup[] = [
  { label: null, items: [{ to: '/', label: 'Home', perm: null, end: true }] },
  { label: null, items: [{ to: '/approvals', label: 'Approvals', perm: P.approvalView, badge: 'approvals' }] },
  { label: null, items: [{ to: '/customers', label: 'Customers', perm: P.customerView }] },
  {
    label: 'Lending',
    items: [
      { to: '/loans', label: 'Loans', perm: P.loanView },
      { to: '/loan-products', label: 'Products', perm: P.productView },
    ],
  },
  {
    label: 'Ledger',
    items: [
      { to: '/ledger/accounts', label: 'Chart of accounts', perm: P.glView },
      { to: '/ledger/vouchers', label: 'Vouchers', perm: P.glView },
      { to: '/ledger/trial-balance', label: 'Trial balance', perm: P.glView },
      { to: '/ledger/profit-and-loss', label: 'P&L', perm: P.glView },
      { to: '/ledger/balance-sheet', label: 'Balance sheet', perm: P.glView },
    ],
  },
  {
    label: 'Day-end',
    items: [
      { to: '/eod/runs', label: 'Runs', perm: P.eodView },
      { to: '/eod/schedule', label: 'Schedule', perm: P.eodView },
    ],
  },
  {
    label: 'Masters',
    items: [
      { to: '/masters/branches', label: 'Branches', perm: P.branchView },
      { to: '/masters/holidays', label: 'Holidays', perm: P.holidayView },
      { to: '/masters/tax-rates', label: 'Tax rates', perm: P.taxRateView },
      { to: '/masters/staff', label: 'Staff', perm: P.staffView },
      { to: '/masters/branch-sets', label: 'Branch sets', perm: P.branchView },
      { to: '/masters/territory', label: 'Territory', perm: P.masterView },
      { to: '/masters/system-properties', label: 'System properties', perm: P.masterView },
      { to: '/masters/enumerations', label: 'Enumerations', perm: P.masterView },
    ],
  },
  { label: null, items: [{ to: '/audit', label: 'Audit', perm: P.auditView }] },
];

export function visibleNav(can: (p: string) => boolean): NavGroup[] {
  return NAV.map((g) => ({ ...g, items: g.items.filter((i) => !i.perm || can(i.perm)) })).filter((g) => g.items.length > 0);
}
