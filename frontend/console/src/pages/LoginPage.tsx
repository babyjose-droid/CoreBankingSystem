import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { DEMO_TENANT, DEMO_USERS } from '../auth/demoUsers';
import { config } from '../config';
import { Badge, Banner, Button, Card } from '../ui';

export function LoginPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const from = (location.state as { from?: string } | null)?.from ?? '/';

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
              <p className="muted" style={{ margin: 0 }}>
                You will be redirected to your organisation’s sign-in page.
              </p>
              {error && <Banner tone="danger">{error}</Banner>}
              <Button
                variant="primary"
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
                Mock mode — in-memory data for tenant <strong>&nbsp;{DEMO_TENANT}&nbsp;</strong>, reset on reload.
              </Banner>
              <div className="demo-users" role="list" aria-label="Demo users">
                {DEMO_USERS.map((u) => (
                  <div role="listitem" key={u.username}>
                    <button
                      type="button"
                      className="demo-user"
                      onClick={() => {
                        qc.clear();
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
