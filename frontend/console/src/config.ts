const env = import.meta.env;

export const config = {
  /** In-memory mock API + demo-user login. Always on under test. */
  mock: env.VITE_MOCK === '1' || env.VITE_MOCK === 'true' || env.MODE === 'test',
  apiBaseUrl: (env.VITE_API_BASE_URL as string | undefined) ?? '',
  oidcAuthority: (env.VITE_OIDC_AUTHORITY as string | undefined) ?? 'http://localhost:8081/realms/demo-nbfc',
  oidcClientId: (env.VITE_OIDC_CLIENT_ID as string | undefined) ?? 'console',
  isTest: env.MODE === 'test',
} as const;

/** openapi-fetch builds a Request, which needs an absolute URL outside the browser; fall back to the page origin. */
export function resolvedApiBaseUrl(): string {
  if (config.apiBaseUrl) return config.apiBaseUrl.replace(/\/$/, '');
  return typeof window !== 'undefined' ? window.location.origin : 'http://localhost';
}
