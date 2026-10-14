import { ALL_PERMISSIONS, P, PROPOSE_PERMISSIONS, VIEW_PERMISSIONS } from './permissions';

export interface DemoUser {
  username: string;
  name: string;
  role: string;
  description: string;
  homeBranch: string;
  permissions: string[];
  /** Realm roles as they appear in the access token; role amount limits are set per role. */
  roles: string[];
}

export const DEMO_TENANT = 'demo-nbfc';
export const DEMO_TENANT_NAME = 'Demo NBFC Ltd';

/**
 * The demo tenants of mock mode, as on the local stack: an NBFC that takes no deposits, a bank (savings, current
 * and term deposits) and an NBFC registered to accept public deposits (term deposits only).
 */
export const DEMO_TENANTS = [
  { code: DEMO_TENANT, name: DEMO_TENANT_NAME, description: 'NBFC: lending, no deposits' },
  { code: 'demo-bank', name: 'Demo Bank Ltd', description: 'Bank: savings, current and term deposits' },
  { code: 'demo-nbfc-d', name: 'Demo Deposit NBFC Ltd', description: 'Deposit-taking NBFC: term deposits only' },
] as const;
export type DemoTenantCode = (typeof DEMO_TENANTS)[number]['code'];

const MOCK_TENANT_KEY = 'corebanking.mockTenant';

/** The demo tenant chosen on the mock sign-in page; it lasts as long as the tab, like the mock session. */
export function readMockTenant(): DemoTenantCode {
  try {
    const t = sessionStorage.getItem(MOCK_TENANT_KEY);
    return DEMO_TENANTS.find((x) => x.code === t)?.code ?? DEMO_TENANT;
  } catch {
    return DEMO_TENANT;
  }
}

export function saveMockTenant(tenant: DemoTenantCode): void {
  try {
    sessionStorage.setItem(MOCK_TENANT_KEY, tenant);
  } catch {
    /* storage unavailable: the default tenant is used */
  }
}

export const DEMO_USERS: DemoUser[] = [
  {
    username: 'maker',
    name: 'Meera Maker',
    role: 'Maker',
    description: 'Views everything, proposes and creates changes',
    homeBranch: 'HO',
    permissions: [...VIEW_PERMISSIONS, ...PROPOSE_PERMISSIONS, P.limitPropose, P.collectionCreate, P.mandateRegister, P.payoutBeneficiary, P.loanClassify, P.casaOpen, P.depositBook, P.casaTransact, P.casaHold, P.depositFund, P.depositHold],
    roles: ['MAKER'],
  },
  {
    username: 'checker',
    name: 'Charan Checker',
    role: 'Checker',
    description: 'Views everything, approves or rejects requests',
    homeBranch: 'HO',
    permissions: [...VIEW_PERMISSIONS, P.approvalApprove, P.kycVerify],
    roles: ['CHECKER'],
  },
  {
    username: 'ops',
    name: 'Omar Operations',
    role: 'Operations',
    description: 'Runs end-of-day, payouts, collections, mandates and NACH files',
    homeBranch: 'HO',
    permissions: [
      P.eodView, P.eodRun, P.glView, P.loanView, P.casaView, P.depositView,
      P.payoutView, P.payoutBeneficiary, P.payoutAdmin,
      P.collectionView, P.collectionCreate, P.collectionAdmin,
      P.mandateView, P.mandateRegister, P.mandateAdmin, P.nachAdmin, P.nachFile,
      P.jobView, P.jobRun,
    ],
    roles: ['OPERATIONS'],
  },
  {
    username: 'auditor',
    name: 'Anita Auditor',
    role: 'Auditor',
    description: 'Read-only access plus the audit trail',
    homeBranch: 'MUM',
    permissions: [...VIEW_PERMISSIONS, P.auditView],
    roles: ['AUDITOR'],
  },
  {
    username: 'admin',
    name: 'Arjun Admin',
    role: 'Administrator',
    description: 'All permissions (still cannot approve own requests)',
    homeBranch: 'HO',
    permissions: [...ALL_PERMISSIONS],
    roles: ['TENANT_ADMIN'],
  },
];

export function findDemoUser(username: string): DemoUser | undefined {
  return DEMO_USERS.find((u) => u.username === username);
}

/** Mock bearer tokens are "mock.<username>" — they only mean something to the in-memory mock API. */
export function mockToken(username: string): string {
  return `mock.${username}`;
}

export function usernameFromMockToken(token: string | null | undefined): string | null {
  if (!token || !token.startsWith('mock.')) return null;
  return token.slice(5);
}
