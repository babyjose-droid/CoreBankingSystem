/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_MOCK?: string;
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  /** "1" = show the LOCAL-ONLY development sign-in (dev server only, ignored in mock mode and production builds). */
  readonly VITE_DEV_LOGIN?: string;
  /** Read by vite.config.ts only: where the dev server proxies /realms (default http://localhost:8081). */
  readonly VITE_KEYCLOAK_PROXY_TARGET?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
