import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { config } from '../config';
import { DEMO_TENANT, findDemoUser, mockToken } from './demoUsers';
import {
  clearDevTokens,
  devRefresh,
  devRefreshDelay,
  devSignIn,
  devSignOut,
  DevLoginError,
  loadDevTokens,
  saveDevTokens,
  type DevTokens,
} from './devLogin';
import { claimsFromUser, decodeJwt, getUserManager } from './oidc';

export interface Session {
  token: string;
  username: string;
  name: string;
  tenant: string;
  /** From the token; /api/v1/me is authoritative once loaded. */
  permissions: string[];
}

export interface AuthContextValue {
  mode: 'mock' | 'oidc';
  ready: boolean;
  session: Session | null;
  getToken: () => string | null;
  loginDemo: (username: string) => void;
  login: (returnTo?: string) => Promise<void>;
  /** True only on a local development stack (VITE_DEV_LOGIN=1 on the dev server, not mock mode). */
  devLogin: boolean;
  /** LOCAL DEVELOPMENT ONLY: direct username/password sign-in. Rejects with a displayable message. */
  loginDev: (username: string, password: string) => Promise<void>;
  completeLogin: () => Promise<string>;
  completeSilentRenew: () => Promise<void>;
  logout: () => Promise<void>;
  /** Drop the local session (e.g. after a 401) without an IdP round-trip; the user lands on /login. */
  expire: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const MOCK_KEY = 'corebanking.mockUser';

function readMockSession(): Session | null {
  try {
    const u = sessionStorage.getItem(MOCK_KEY);
    return u ? mockSession(u) : null;
  } catch {
    return null;
  }
}

function mockSession(username: string): Session | null {
  const u = findDemoUser(username);
  if (!u) return null;
  return { token: mockToken(u.username), username: u.username, name: u.name, tenant: DEMO_TENANT, permissions: u.permissions };
}

function devSession(tokens: DevTokens): Session {
  const at = decodeJwt(tokens.accessToken);
  const str = (k: string) => (typeof at[k] === 'string' ? (at[k] as string) : undefined);
  const perms = at.permissions;
  return {
    token: tokens.accessToken,
    username: str('preferred_username') ?? str('sub') ?? 'unknown',
    name: str('name') ?? str('preferred_username') ?? 'User',
    tenant: str('tenant') ?? '',
    permissions: Array.isArray(perms) ? perms.map(String) : [],
  };
}

export function AuthProvider({ children, initialMockUser }: { children: ReactNode; initialMockUser?: string }) {
  const mode = config.mock ? 'mock' : 'oidc';
  const [session, setSession] = useState<Session | null>(() =>
    mode === 'mock' ? (initialMockUser ? mockSession(initialMockUser) : readMockSession()) : null,
  );
  const [ready, setReady] = useState(mode === 'mock');
  const tokenRef = useRef<string | null>(session?.token ?? null);
  tokenRef.current = session?.token ?? null;

  // --- Development sign-in (local only; see devLogin.ts). Inert unless config.devLogin. ---
  const devRef = useRef<DevTokens | null>(null);
  const devTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const dropDev = useCallback(() => {
    if (devTimer.current) clearTimeout(devTimer.current);
    devTimer.current = null;
    devRef.current = null;
    clearDevTokens();
  }, []);

  const applyDev = useCallback(
    (tokens: DevTokens) => {
      if (devTimer.current) clearTimeout(devTimer.current);
      devRef.current = tokens;
      saveDevTokens(tokens);
      setSession(devSession(tokens));
      // Access tokens last 5 minutes: renew with the (rotating) refresh token shortly before expiry.
      devTimer.current = setTimeout(() => {
        devRefresh(tokens.refreshToken).then(
          (next) => {
            if (devRef.current === tokens) applyDev(next);
          },
          () => {
            if (devRef.current !== tokens) return;
            dropDev();
            setSession(null);
          },
        );
      }, devRefreshDelay(tokens));
    },
    [dropDev],
  );

  useEffect(() => {
    if (mode !== 'oidc') return;
    let cancelled = false;
    const stored = config.devLogin ? loadDevTokens() : null;
    if (stored) {
      if (stored.expiresAt - Date.now() > 10_000) {
        applyDev(stored);
        setReady(true);
      } else {
        devRefresh(stored.refreshToken)
          .then(
            (next) => {
              if (!cancelled) applyDev(next);
            },
            () => {
              if (!cancelled) dropDev();
            },
          )
          .finally(() => {
            if (!cancelled) setReady(true);
          });
      }
      return () => {
        cancelled = true;
        if (devTimer.current) clearTimeout(devTimer.current);
        devTimer.current = null;
        devRef.current = null;
      };
    }
    const um = getUserManager();
    const apply = (user: Parameters<typeof claimsFromUser>[0] | null) => {
      if (devRef.current) return; // a development sign-in owns the session
      if (!user || user.expired) {
        setSession(null);
        return;
      }
      const c = claimsFromUser(user);
      setSession({
        token: user.access_token,
        username: c.preferred_username ?? c.sub ?? 'unknown',
        name: c.name ?? c.preferred_username ?? 'User',
        tenant: c.tenant ?? '',
        permissions: c.permissions ?? [],
      });
    };
    um.getUser()
      .then(apply)
      .finally(() => setReady(true));
    const onLoaded = (u: Parameters<typeof apply>[0]) => apply(u);
    const onExpired = () => {
      if (!devRef.current) setSession(null);
    };
    um.events.addUserLoaded(onLoaded);
    um.events.addAccessTokenExpired(onExpired);
    um.events.addUserSignedOut(onExpired);
    return () => {
      um.events.removeUserLoaded(onLoaded);
      um.events.removeAccessTokenExpired(onExpired);
      um.events.removeUserSignedOut(onExpired);
      if (devTimer.current) clearTimeout(devTimer.current);
      devTimer.current = null;
    };
  }, [mode, applyDev, dropDev]);

  const loginDev = useCallback(
    async (username: string, password: string) => {
      if (!config.devLogin) throw new DevLoginError('Development sign-in is not enabled in this build.');
      applyDev(await devSignIn(username, password));
    },
    [applyDev],
  );

  const loginDemo = useCallback((username: string) => {
    const s = mockSession(username);
    if (!s) return;
    try {
      sessionStorage.setItem(MOCK_KEY, username);
    } catch {
      /* storage unavailable: session lives in memory only */
    }
    setSession(s);
  }, []);

  const login = useCallback(async (returnTo = '/') => {
    await getUserManager().signinRedirect({ state: { returnTo } });
  }, []);

  const completeLogin = useCallback(async () => {
    const user = await getUserManager().signinRedirectCallback();
    const state = user.state as { returnTo?: string } | undefined;
    return state?.returnTo ?? '/';
  }, []);

  const completeSilentRenew = useCallback(async () => {
    await getUserManager().signinSilentCallback();
  }, []);

  const logout = useCallback(async () => {
    if (mode === 'mock') {
      try {
        sessionStorage.removeItem(MOCK_KEY);
      } catch {
        /* ignore */
      }
      setSession(null);
      return;
    }
    const dev = devRef.current;
    if (dev) {
      // Development sign-in: clear locally first, then end the Keycloak session through the proxy. No redirect.
      dropDev();
      setSession(null);
      await devSignOut(dev.refreshToken).catch(() => undefined);
      return;
    }
    await getUserManager().signoutRedirect();
  }, [mode, dropDev]);

  const expire = useCallback(() => {
    if (mode === 'oidc') {
      if (devRef.current) dropDev();
      else void getUserManager().removeUser();
    } else {
      try {
        sessionStorage.removeItem(MOCK_KEY);
      } catch {
        /* ignore */
      }
    }
    setSession(null);
  }, [mode, dropDev]);

  const value = useMemo<AuthContextValue>(
    () => ({ mode, ready, session, getToken: () => tokenRef.current, loginDemo, login, devLogin: config.devLogin, loginDev, completeLogin, completeSilentRenew, logout, expire }),
    [mode, ready, session, loginDemo, login, loginDev, completeLogin, completeSilentRenew, logout, expire],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
