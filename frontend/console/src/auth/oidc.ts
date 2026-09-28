import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { config } from '../config';

let manager: UserManager | null = null;

export function getUserManager(): UserManager {
  if (!manager) {
    const origin = window.location.origin;
    manager = new UserManager({
      authority: config.oidcAuthority,
      client_id: config.oidcClientId,
      redirect_uri: `${origin}/auth/callback`,
      silent_redirect_uri: `${origin}/auth/silent`,
      post_logout_redirect_uri: `${origin}/login`,
      response_type: 'code', // Authorization Code; oidc-client-ts always uses PKCE (S256) for public clients
      scope: 'openid profile',
      automaticSilentRenew: true,
      // Session storage: tokens do not outlive the tab.
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    });
  }
  return manager;
}

export interface TokenClaims {
  tenant?: string;
  permissions?: string[];
  preferred_username?: string;
  name?: string;
  sub?: string;
}

export function decodeJwt(token: string): Record<string, unknown> {
  const part = token.split('.')[1];
  if (!part) return {};
  try {
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '=');
    const json = decodeURIComponent(
      Array.from(atob(b64))
        .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
        .join(''),
    );
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return {};
  }
}

/** Claims come from the access token (what the API sees), falling back to the ID-token profile. */
export function claimsFromUser(user: User): TokenClaims {
  const at = decodeJwt(user.access_token);
  const profile = user.profile as Record<string, unknown>;
  const pick = <T,>(k: string): T | undefined => (at[k] ?? profile[k]) as T | undefined;
  const perms = pick<unknown>('permissions');
  return {
    tenant: pick<string>('tenant'),
    permissions: Array.isArray(perms) ? perms.map(String) : [],
    preferred_username: pick<string>('preferred_username'),
    name: pick<string>('name'),
    sub: pick<string>('sub'),
  };
}
