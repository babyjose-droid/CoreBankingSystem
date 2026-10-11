import { readMockTenant, type DemoTenantCode } from '../auth/demoUsers';
import { createMockServer, type MockServer } from './server';

let instance: MockServer | null = null;

/** Singleton used by the running app in mock mode (state lives until page reload), as the demo tenant chosen at sign-in. */
export function getMockServer(): MockServer {
  if (!instance) instance = createMockServer({ latencyMs: import.meta.env.MODE === 'test' ? 0 : 120, tenant: readMockTenant() });
  return instance;
}

export function resetMockServer(): MockServer {
  instance?.dispose();
  instance = createMockServer({ latencyMs: 0 });
  return instance;
}

/** Mock mode: become another demo tenant. Its data starts fresh, as on a reload. */
export function switchMockTenant(tenant: DemoTenantCode): void {
  if (instance && instance.db.tenant === tenant) return;
  instance?.dispose();
  instance = createMockServer({ latencyMs: import.meta.env.MODE === 'test' ? 0 : 120, tenant });
}

export { createMockServer };
export type { MockServer };
