import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { config, resolvedApiBaseUrl } from '../config';
import { createApiClient, type ApiClient } from './client';

const ApiContext = createContext<ApiClient | null>(null);

/** The mock is loaded lazily so that it is a separate chunk that real-backend deployments never download. */
async function mockFetch(request: Request): Promise<Response> {
  const { getMockServer } = await import('../mock');
  return getMockServer().fetch(request);
}

export function ApiProvider({ children, fetchImpl }: { children: ReactNode; fetchImpl?: (r: Request) => Promise<Response> }) {
  const auth = useAuth();
  const { getToken, expire } = auth;
  const client = useMemo(
    () =>
      createApiClient({
        baseUrl: resolvedApiBaseUrl(),
        getToken,
        fetch: fetchImpl ?? (config.mock ? mockFetch : undefined),
        onUnauthorized: expire,
      }),
    [getToken, expire, fetchImpl],
  );
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const c = useContext(ApiContext);
  if (!c) throw new Error('useApi must be used inside <ApiProvider>');
  return c;
}
