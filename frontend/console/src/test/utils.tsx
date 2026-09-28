import { render, type RenderResult } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AppProviders, AppRoutes, createQueryClient } from '../App';
import { createMockServer, type MockServer } from '../mock/server';
import { mockToken } from '../auth/demoUsers';

export interface RenderAppResult extends RenderResult {
  server: MockServer;
}

/** Renders the whole app (routes, shell, providers) against a fresh in-memory mock API. */
export function renderApp(opts: { user?: string; route?: string; server?: MockServer } = {}): RenderAppResult {
  const server = opts.server ?? createMockServer({ eodStepMs: 400 });
  const qc = createQueryClient();
  qc.setDefaultOptions({ queries: { ...qc.getDefaultOptions().queries, retry: false, staleTime: 0 } });
  const result = render(
    <MemoryRouter initialEntries={[opts.route ?? '/']}>
      <AppProviders queryClient={qc} initialMockUser={opts.user} fetchImpl={server.fetch}>
        <AppRoutes />
      </AppProviders>
    </MemoryRouter>,
  );
  return { ...result, server };
}

/** Direct calls to the mock API (for API-level tests and test setup). */
export function mockCall(server: MockServer, user: string, method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
  return server
    .fetch(
      new Request(`http://localhost${path}`, {
        method,
        headers: { Authorization: `Bearer ${mockToken(user)}`, 'Content-Type': 'application/json', ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    )
    .then(async (r) => ({ status: r.status, contentType: r.headers.get('Content-Type'), body: await r.json() }));
}
