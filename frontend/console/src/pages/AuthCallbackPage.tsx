import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { Banner, Spinner } from '../ui';

export function AuthCallbackPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const done = useRef(false);
  useEffect(() => {
    if (done.current) return; // StrictMode double-invoke: the code can be exchanged only once
    done.current = true;
    auth
      .completeLogin()
      .then((to) => navigate(to, { replace: true }))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Sign-in failed'));
  }, [auth, navigate]);
  return (
    <div className="login">
      {error ? (
        <div className="stack">
          <Banner tone="danger">{error}</Banner>
          <Link to="/login">Back to sign in</Link>
        </div>
      ) : (
        <Spinner label="Completing sign-in" />
      )}
    </div>
  );
}

export function SilentRenewPage() {
  const auth = useAuth();
  useEffect(() => {
    void auth.completeSilentRenew().catch(() => undefined);
  }, [auth]);
  return null;
}
