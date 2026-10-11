import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { DEMO_TENANTS, DEMO_USERS, readMockTenant, saveMockTenant, type DemoTenantCode } from '../auth/demoUsers';
import { configuredRealm } from '../auth/devLogin';
import { config } from '../config';
import { Badge, Banner, Button, Card, Input, Select } from '../ui';

export function LoginPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  // Development sign-in (local only). The password is never put in React state: it is read from the input at
  // submit, handed to the token request once, and the input is cleared whatever the outcome.
  const [devUser, setDevUser] = useState('');
  const [devError, setDevError] = useState<string | null>(null);
  const [devBusy, setDevBusy] = useState(false);
  // Local stack: the tenants of VITE_DEV_TENANTS, each its own Keycloak realm. Mock mode: the demo tenants.
  const devTenants = config.devTenants.length ? config.devTenants : [];
  const [devTenant, setDevTenant] = useState(() => (devTenants.includes(configuredRealm() ?? '') ? configuredRealm()! : devTenants[0] ?? ''));
  const [mockTenant, setMockTenant] = useState<DemoTenantCode>(() => readMockTenant());

  const submitDev = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!auth.devLogin || devBusy) return;
    const field = e.currentTarget.elements.namedItem('password');
    const input = field instanceof HTMLInputElement ? field : null;
    const password = input?.value ?? '';
    if (!devUser.trim() || !password) {
      setDevError('Enter a username and password.');
      return;
    }
    setDevBusy(true);
    setDevError(null);
    try {
      qc.clear();
      await auth.loginDev(devUser.trim(), password, devTenant || undefined);
    } catch (err) {
      setDevError(err instanceof Error ? err.message : 'Sign-in failed');
    } finally {
      if (input) input.value = '';
      setDevBusy(false);
    }
  };

  if (auth.session) return <Navigate to={from} replace />;

  return (
    <div className="login">
      <div className="login__card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
          <span className="header__logo" aria-hidden="true" />
          <h1 style={{ fontSize: 22 }}>CoreBanking</h1>
          <span className="muted">Staff console</span>
        </div>
        <Card title="Sign in">
          {auth.mode === 'oidc' ? (
            <div className="stack">
              {auth.devLogin && (
                <form className="stack" onSubmit={submitDev} aria-label="Development sign-in (local only)" autoComplete="off">
                  <h3 style={{ margin: 0, fontSize: 15 }}>Development sign-in (local only)</h3>
                  <Banner tone="warn">
                    This form exists only on a local development stack. Shared and production environments sign in
                    through SSO; passwords never pass through the console there.
                  </Banner>
                  {devError && <Banner tone="danger">{devError}</Banner>}
                  {devTenants.length > 1 && (
                    <Select
                      label="Tenant"
                      value={devTenant}
                      onChange={(e) => setDevTenant(e.target.value)}
                      options={devTenants.map((t) => ({ value: t, label: t }))}
                      hint="Each demo tenant is its own institution with its own users and data."
                    />
                  )}
                  <Input
                    label="Username"
                    name="username"
                    value={devUser}
                    onChange={(e) => setDevUser(e.target.value)}
                    autoComplete="off"
                    autoCapitalize="none"
                    spellCheck={false}
                    required
                  />
                  <Input label="Password" name="password" type="password" autoComplete="off" required />
                  <Button variant="primary" type="submit" loading={devBusy}>
                    Sign in
                  </Button>
                  <hr style={{ width: '100%', border: 0, borderTop: '1px solid var(--border, #ddd)', margin: 0 }} />
                </form>
              )}
              <p className="muted" style={{ margin: 0 }}>
                You will be redirected to your organisation’s sign-in page.
              </p>
              {error && <Banner tone="danger">{error}</Banner>}
              <Button
                variant={auth.devLogin ? 'secondary' : 'primary'}
                loading={busy}
                onClick={async () => {
                  setBusy(true);
                  setError(null);
                  try {
                    await auth.login(from);
                  } catch (e) {
                    setError(e instanceof Error ? e.message : 'Sign-in failed');
                    setBusy(false);
                  }
                }}
              >
                Sign in with SSO
              </Button>
              <p className="muted" style={{ fontSize: 12, margin: 0 }}>
                Identity provider: <code>{config.oidcAuthority}</code>
              </p>
            </div>
          ) : (
            <div className="stack">
              <Banner tone="info">
                Mock mode — in-memory data for tenant <strong>&nbsp;{mockTenant}&nbsp;</strong>, reset on reload.
              </Banner>
              <Select
                label="Demo tenant"
                value={mockTenant}
                onChange={(e) => setMockTenant(e.target.value as DemoTenantCode)}
                options={DEMO_TENANTS.map((t) => ({ value: t.code, label: `${t.name} — ${t.description}` }))}
              />
              <div className="demo-users" role="list" aria-label="Demo users">
                {DEMO_USERS.map((u) => (
                  <div role="listitem" key={u.username}>
                    <button
                      type="button"
                      className="demo-user"
                      onClick={async () => {
                        qc.clear();
                        saveMockTenant(mockTenant);
                        (await import('../mock')).switchMockTenant(mockTenant);
                        auth.loginDemo(u.username);
                        navigate(from, { replace: true });
                      }}
                    >
                      <span>
                        <strong>{u.name}</strong> <span className="mono muted">({u.username})</span>
                        <br />
                        <span className="muted" style={{ fontSize: 12 }}>
                          {u.description}
                        </span>
                      </span>
                      <Badge tone="accent">{u.role}</Badge>
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
