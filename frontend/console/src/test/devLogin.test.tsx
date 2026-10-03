import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The development sign-in is off in mock mode (and tests always run in mock mode), so these tests swap the
// config for one that looks like the local stack: real OIDC mode, optional VITE_DEV_LOGIN.
const cfg = vi.hoisted(() => ({
  mock: false,
  devLogin: true,
  apiBaseUrl: '',
  oidcAuthority: 'http://localhost:8081/realms/demo-nbfc',
  oidcClientId: 'console',
  isTest: true,
}));
vi.mock('../config', () => ({ config: cfg, resolvedApiBaseUrl: () => 'http://localhost' }));

import { AppProviders, AppRoutes, createQueryClient } from '../App';
import { mockToken } from '../auth/demoUsers';
import { devSignIn } from '../auth/devLogin';
import { createMockServer } from '../mock/server';

const ISSUER = 'http://localhost:8081/realms/demo-nbfc';
const TOKEN_PATH = '/realms/demo-nbfc/protocol/openid-connect/token';
const LOGOUT_PATH = '/realms/demo-nbfc/protocol/openid-connect/logout';
const STORE = 'corebanking.devSession';

function jwt(claims: Record<string, unknown>): string {
  const enc = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${enc({ alg: 'none' })}.${enc(claims)}.sig`;
}

function tokenResponse(n: number, iss = ISSUER): Response {
  return new Response(
    JSON.stringify({
      access_token: jwt({ iss, preferred_username: 'maker', name: 'CLAUDE-TEST Dev Maker', tenant: 'demo-nbfc', permissions: ['customer:view'], n }),
      refresh_token: `refresh-${n}`,
      expires_in: 300,
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  );
}

interface KcCall {
  path: string;
  form: URLSearchParams;
}

/** Fake Keycloak behind the same-origin /realms proxy. */
function stubKeycloak(handler: (call: KcCall, index: number) => Response) {
  const calls: KcCall[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { path: String(input), form: new URLSearchParams(String(init?.body ?? '')) };
    calls.push(call);
    return handler(call, calls.length);
  });
  vi.stubGlobal('fetch', fn);
  return calls;
}

function renderConsole() {
  const server = createMockServer({ eodStepMs: 400 });
  // The in-memory API knows "mock.<user>" tokens; map the development token's user onto it.
  const api = (request: Request) => {
    const headers = new Headers(request.headers);
    if (headers.get('Authorization')?.startsWith('Bearer ey')) headers.set('Authorization', `Bearer ${mockToken('maker')}`);
    return server.fetch(new Request(request, { headers }));
  };
  const qc = createQueryClient();
  qc.setDefaultOptions({ queries: { ...qc.getDefaultOptions().queries, retry: false, staleTime: 0 } });
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <AppProviders queryClient={qc} fetchImpl={api}>
        <AppRoutes />
      </AppProviders>
    </MemoryRouter>,
  );
}

async function signIn(password = 'LocalDev#2026') {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText(/Username/), 'dev-maker');
  await user.type(screen.getByLabelText(/Password/), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  return user;
}

beforeEach(() => {
  cfg.devLogin = true;
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('development sign-in (local only)', () => {
  it('is not rendered and cannot run when the flag is off', async () => {
    cfg.devLogin = false;
    const calls = stubKeycloak(() => tokenResponse(1));
    renderConsole();
    expect(await screen.findByRole('button', { name: 'Sign in with SSO' })).toBeInTheDocument();
    expect(screen.queryByText(/Development sign-in/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Password/)).not.toBeInTheDocument();
    await expect(devSignIn('dev-maker', 'x')).rejects.toThrow(/not enabled/);
    expect(calls).toHaveLength(0);
  });

  it('signs in with the password grant, stores the session and lands on home with permissions', async () => {
    const calls = stubKeycloak(() => tokenResponse(1));
    renderConsole();
    expect(await screen.findByText(/exists only on a local development stack/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in with SSO' })).toBeInTheDocument();
    await signIn();

    expect(await screen.findByRole('heading', { name: /Welcome/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New customer' })).toBeInTheDocument(); // permission-driven action

    expect(calls).toHaveLength(1);
    expect(calls[0]!.path).toBe(TOKEN_PATH); // same origin: proxied by the dev server
    expect(Object.fromEntries(calls[0]!.form)).toEqual({
      client_id: 'console-dev',
      grant_type: 'password',
      scope: 'openid',
      username: 'dev-maker',
      password: 'LocalDev#2026',
    });
    const stored = sessionStorage.getItem(STORE)!;
    expect(JSON.parse(stored).refreshToken).toBe('refresh-1');
    expect(stored).not.toContain('LocalDev#2026'); // the password is never stored
  });

  it('shows Keycloak’s message for a wrong password and clears the field', async () => {
    stubKeycloak(
      () => new Response(JSON.stringify({ error: 'invalid_grant', error_description: 'Invalid user credentials' }), { status: 401 }),
    );
    renderConsole();
    await signIn('wrong-password');
    expect(await screen.findByText('Invalid user credentials')).toBeInTheDocument();
    expect(screen.getByLabelText(/Password/)).toHaveValue('');
    expect(screen.getByLabelText(/Username/)).toHaveValue('dev-maker');
    expect(sessionStorage.getItem(STORE)).toBeNull();
  });

  it('refuses a token whose issuer is not the configured authority', async () => {
    stubKeycloak(() => tokenResponse(1, 'http://keycloak:8081/realms/demo-nbfc'));
    renderConsole();
    await signIn();
    expect(await screen.findByText(/Token issuer mismatch.*http:\/\/keycloak:8081\/realms\/demo-nbfc/)).toBeInTheDocument();
    expect(sessionStorage.getItem(STORE)).toBeNull();
    expect(screen.queryByRole('heading', { name: /Welcome/ })).not.toBeInTheDocument();
  });

  it('schedules a refresh before expiry and uses the rotated refresh token', async () => {
    const timeouts = vi.spyOn(globalThis, 'setTimeout');
    const calls = stubKeycloak((_c, i) => tokenResponse(i));
    renderConsole();
    await signIn();
    await screen.findByRole('heading', { name: /Welcome/ });

    // 300 s token, renewed 60 s early.
    const scheduled = timeouts.mock.calls.filter(([, ms]) => typeof ms === 'number' && ms > 230_000 && ms <= 240_000);
    expect(scheduled).toHaveLength(1);
    await act(async () => {
      (scheduled[0]![0] as () => void)();
    });
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]!.path).toBe(TOKEN_PATH);
    expect(Object.fromEntries(calls[1]!.form)).toEqual({ client_id: 'console-dev', grant_type: 'refresh_token', refresh_token: 'refresh-1' });
    await waitFor(() => expect(JSON.parse(sessionStorage.getItem(STORE)!).refreshToken).toBe('refresh-2'));
    expect(screen.getByRole('heading', { name: /Welcome/ })).toBeInTheDocument();

    // The next renewal is scheduled with the new refresh token.
    const again = timeouts.mock.calls.filter(([, ms]) => typeof ms === 'number' && ms > 230_000 && ms <= 240_000);
    expect(again).toHaveLength(2);
    await act(async () => {
      (again[1]![0] as () => void)();
    });
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[2]!.form.get('refresh_token')).toBe('refresh-2');
  });

  it('signs out cleanly when the refresh fails', async () => {
    const timeouts = vi.spyOn(globalThis, 'setTimeout');
    stubKeycloak((_c, i) =>
      i === 1 ? tokenResponse(1) : new Response(JSON.stringify({ error: 'invalid_grant', error_description: 'Token is not active' }), { status: 400 }),
    );
    renderConsole();
    await signIn();
    await screen.findByRole('heading', { name: /Welcome/ });
    const scheduled = timeouts.mock.calls.filter(([, ms]) => typeof ms === 'number' && ms > 230_000 && ms <= 240_000);
    await act(async () => {
      (scheduled[0]![0] as () => void)();
    });
    expect(await screen.findByRole('button', { name: 'Sign in with SSO' })).toBeInTheDocument();
    expect(sessionStorage.getItem(STORE)).toBeNull();
  });

  it('restores the session from sessionStorage on reload', async () => {
    const calls = stubKeycloak(() => tokenResponse(9));
    const body = (await tokenResponse(1).json()) as { access_token: string };
    sessionStorage.setItem(STORE, JSON.stringify({ accessToken: body.access_token, refreshToken: 'refresh-1', expiresAt: Date.now() + 200_000 }));
    renderConsole();
    expect(await screen.findByRole('heading', { name: /Welcome/ })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('sign-out clears the session and ends the Keycloak session without a redirect', async () => {
    const calls = stubKeycloak((c) => (c.path === LOGOUT_PATH ? new Response(null, { status: 204 }) : tokenResponse(1)));
    renderConsole();
    const user = await signIn();
    await screen.findByRole('heading', { name: /Welcome/ });

    await user.click(screen.getByRole('button', { name: /maker/i }));
    await user.click(await screen.findByRole('menuitem', { name: 'Sign out' }));

    expect(await screen.findByRole('button', { name: 'Sign in with SSO' })).toBeInTheDocument();
    expect(sessionStorage.getItem(STORE)).toBeNull();
    await waitFor(() => expect(calls.some((c) => c.path === LOGOUT_PATH)).toBe(true));
    const out = calls.find((c) => c.path === LOGOUT_PATH)!;
    expect(Object.fromEntries(out.form)).toEqual({ client_id: 'console-dev', refresh_token: 'refresh-1' });
  });
});
