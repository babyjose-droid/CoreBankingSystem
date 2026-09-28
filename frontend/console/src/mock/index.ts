import { createMockServer, type MockServer } from './server';

let instance: MockServer | null = null;

/** Singleton used by the running app in mock mode (state lives until page reload). */
export function getMockServer(): MockServer {
  if (!instance) instance = createMockServer({ latencyMs: import.meta.env.MODE === 'test' ? 0 : 120 });
  return instance;
}

export function resetMockServer(): MockServer {
  instance?.dispose();
  instance = createMockServer({ latencyMs: 0 });
  return instance;
}

export { createMockServer };
export type { MockServer };
