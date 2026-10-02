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

export const DEMO_USERS: DemoUser[] = [
  {
    username: 'maker',
    name: 'Meera Maker',
    role: 'Maker',
    description: 'Views everything, proposes and creates changes',
    homeBranch: 'HO',
    permissions: [...VIEW_PERMISSIONS, ...PROPOSE_PERMISSIONS, P.limitPropose],
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
    description: 'Runs end-of-day; views the ledger',
    homeBranch: 'HO',
    permissions: [P.eodView, P.eodRun, P.glView],
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
