/**
 * LOCAL DEVELOPMENT ONLY — direct username/password sign-in against the local Keycloak (ADR-007 amendment).
 *
 * Production signs in through SSO only: passwords never touch the console. This module exists so the local stack
 * can be used where the redirect to Keycloak is impractical (embedded test browsers). Guard rails:
 *   - it runs only when `config.devLogin` is true (VITE_DEV_LOGIN=1 on the Vite dev server, never in a production
 *     build, never in mock mode) — every network function checks the flag itself;
 *   - it uses the `console-dev` Keycloak client, which exists only in the local demo realm
 *     (infra/keycloak/new-tenant-realm.py strips it from every tenant realm and CI asserts that);
 *   - requests go to the same-origin `/realms/...` path, which only the Vite dev server proxies to Keycloak.
 *
 * The password is passed in, sent once and not kept anywhere; nothing in this module logs.
 */
import { config } from '../config';
import { decodeJwt } from './oidc';

export const DEV_LOGIN_CLIENT_ID = 'console-dev';
const STORAGE_KEY = 'corebanking.devSession';

export interface DevTokens {
  accessToken: string;
  refreshToken: string;
  /** Epoch milliseconds at which the access token expires. */
  expiresAt: number;
  /** The tenant realm signed in to, when it is not the realm of the configured authority (VITE_DEV_TENANTS). */
  realm?: string;
}

export class DevLoginError extends Error {}

function assertEnabled(): void {
  if (!config.devLogin) throw new DevLoginError('Development sign-in is not enabled in this build.');
}

function trimSlash(s: string): string {
  return s.replace(/\/+$/, '');
}

/** The realm of the configured authority URL: the tenant signed in to unless another is chosen. */
export function configuredRealm(): string | undefined {
  try {
    return /\/realms\/([^/]+)/.exec(new URL(config.oidcAuthority).pathname)?.[1];
  } catch {
    return undefined;
  }
}

/** A tenant the sign-in form may choose: the configured realm, or one listed in VITE_DEV_TENANTS. */
function checkedRealm(realm: string | undefined): string {
  const own = configuredRealm();
  const chosen = realm ?? own;
  if (!chosen) throw new DevLoginError(`Cannot read the realm from the identity provider URL ${config.oidcAuthority}.`);
  if (chosen !== own && !config.devTenants.includes(chosen)) throw new DevLoginError(`Tenant ${chosen} is not offered by this development sign-in.`);
  return chosen;
}

/** Same-origin path of a realm's OIDC endpoints; the realm of the configured authority URL unless one is given. */
export function devOidcPath(endpoint: 'token' | 'logout', realm?: string): string {
  return `/realms/${checkedRealm(realm)}/protocol/openid-connect/${endpoint}`;
}

/** The issuer a token of this realm must carry: the configured authority, with its realm replaced. */
function expectedIssuer(realm: string | undefined): string {
  const authority = trimSlash(config.oidcAuthority);
  return realm ? authority.replace(/\/realms\/[^/]+$/, `/realms/${realm}`) : authority;
}

async function tokenRequest(form: Record<string, string>, realm?: string): Promise<DevTokens> {
  assertEnabled();
  const path = devOidcPath('token', realm); // outside the try: a tenant that is not offered is not a network failure
  let response: Response;
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body: new URLSearchParams({ client_id: DEV_LOGIN_CLIENT_ID, ...form }).toString(),
      credentials: 'omit',
      cache: 'no-store',
    });
  } catch {
    throw new DevLoginError('Cannot reach the sign-in service. Is the local stack running?');
  }
  let body: Record<string, unknown> | null = null;
  try {
    const parsed: unknown = await response.json();
    if (parsed && typeof parsed === 'object') body = parsed as Record<string, unknown>;
  } catch {
    body = null;
  }
  if (!response.ok) {
    const description = body && typeof body.error_description === 'string' ? body.error_description : null;
    const code = body && typeof body.error === 'string' ? body.error : null;
    throw new DevLoginError(description ?? code ?? `Sign-in failed (HTTP ${response.status}). Is Keycloak running behind the /realms proxy?`);
  }
  const accessToken = body?.access_token;
  const refreshToken = body?.refresh_token;
  if (typeof accessToken !== 'string' || typeof refreshToken !== 'string') {
    throw new DevLoginError('The sign-in service did not return a token. Check the /realms proxy (VITE_KEYCLOAK_PROXY_TARGET).');
  }
  // The backend accepts only tokens whose issuer is the public realm URL. Refuse anything else here, so a proxy
  // or Keycloak hostname misconfiguration is obvious instead of producing 401s on every API call.
  const iss = decodeJwt(accessToken).iss;
  const expected = expectedIssuer(realm);
  if (typeof iss !== 'string' || trimSlash(iss) !== expected) {
    throw new DevLoginError(
      `Token issuer mismatch: Keycloak issued "${typeof iss === 'string' ? iss : 'none'}" but the console is configured for ` +
        `"${expected}". Check KC_HOSTNAME and the /realms proxy (VITE_KEYCLOAK_PROXY_TARGET).`,
    );
  }
  const expiresIn = typeof body?.expires_in === 'number' && body.expires_in > 0 ? body.expires_in : 300;
  return { accessToken, refreshToken, expiresAt: Date.now() + expiresIn * 1000, ...(realm ? { realm } : {}) };
}

/** Resource-owner password grant. The caller must not keep the password. `realm`: the tenant chosen on the form. */
export function devSignIn(username: string, password: string, realm?: string): Promise<DevTokens> {
  return tokenRequest({ grant_type: 'password', scope: 'openid', username, password }, realm === configuredRealm() ? undefined : realm);
}

// Refresh tokens rotate and are single-use (revokeRefreshToken), so concurrent callers (React StrictMode mounts
// effects twice) must share one request.
const inflight = new Map<string, Promise<DevTokens>>();

export function devRefresh(refreshToken: string, realm?: string): Promise<DevTokens> {
  let p = inflight.get(refreshToken);
  if (!p) {
    p = tokenRequest({ grant_type: 'refresh_token', refresh_token: refreshToken }, realm).finally(() => inflight.delete(refreshToken));
    inflight.set(refreshToken, p);
  }
  return p;
}

/** Ends the Keycloak session (back-channel style POST; no redirect). Best effort. */
export async function devSignOut(refreshToken: string, realm?: string): Promise<void> {
  assertEnabled();
  await fetch(devOidcPath('logout', realm), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: DEV_LOGIN_CLIENT_ID, refresh_token: refreshToken }).toString(),
    credentials: 'omit',
    cache: 'no-store',
  });
}

/** Tokens live in sessionStorage (like the SSO session): they do not outlive the tab. */
export function saveDevTokens(tokens: DevTokens): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(tokens));
  } catch {
    /* storage unavailable: session lives in memory only */
  }
}

export function loadDevTokens(): DevTokens | null {
  if (!config.devLogin) return null;
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const t = JSON.parse(raw) as Partial<DevTokens>;
    if (typeof t.accessToken !== 'string' || typeof t.refreshToken !== 'string' || typeof t.expiresAt !== 'number') return null;
    return { accessToken: t.accessToken, refreshToken: t.refreshToken, expiresAt: t.expiresAt, ...(typeof t.realm === 'string' ? { realm: t.realm } : {}) };
  } catch {
    return null;
  }
}

export function clearDevTokens(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
}

/** Renew this long before the access token expires. */
export const DEV_REFRESH_SKEW_MS = 60_000;

export function devRefreshDelay(tokens: DevTokens, now = Date.now()): number {
  return Math.max(tokens.expiresAt - now - DEV_REFRESH_SKEW_MS, 5_000);
}
