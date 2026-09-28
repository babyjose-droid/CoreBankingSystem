import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { config } from '../config';
import { DEMO_TENANT, findDemoUser, mockToken } from './demoUsers';
import { claimsFromUser, getUserManager } from './oidc';

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

export function AuthProvider({ children, initialMockUser }: { children: ReactNode; initialMockUser?: string }) {
  const mode = config.mock ? 'mock' : 'oidc';
  const [session, setSession] = useState<Session | null>(() =>
    mode === 'mock' ? (initialMockUser ? mockSession(initialMockUser) : readMockSession()) : null,
  );
  const [ready, setReady] = useState(mode === 'mock');
  const tokenRef = useRef<string | null>(session?.token ?? null);
  tokenRef.current = session?.token ?? null;

  useEffect(() => {
    if (mode !== 'oidc') return;
    const um = getUserManager();
    const apply = (user: Parameters<typeof claimsFromUser>[0] | null) => {
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
    const onExpired = () => setSession(null);
    um.events.addUserLoaded(onLoaded);
    um.events.addAccessTokenExpired(onExpired);
    um.events.addUserSignedOut(onExpired);
    return () => {
      um.events.removeUserLoaded(onLoaded);
      um.events.removeAccessTokenExpired(onExpired);
      um.events.removeUserSignedOut(onExpired);
    };
  }, [mode]);

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
    await getUserManager().signoutRedirect();
  }, [mode]);

  const expire = useCallback(() => {
    if (mode === 'oidc') void getUserManager().removeUser();
    else {
      try {
        sessionStorage.removeItem(MOCK_KEY);
      } catch {
        /* ignore */
      }
    }
    setSession(null);
  }, [mode]);

  const value = useMemo<AuthContextValue>(
    () => ({ mode, ready, session, getToken: () => tokenRef.current, loginDemo, login, completeLogin, completeSilentRenew, logout, expire }),
    [mode, ready, session, loginDemo, login, completeLogin, completeSilentRenew, logout, expire],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
