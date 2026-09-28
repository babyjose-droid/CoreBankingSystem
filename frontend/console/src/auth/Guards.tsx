import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { useMe } from '../api/hooks';
import { ForbiddenPage } from '../pages/ForbiddenPage';
import { Spinner } from '../ui';
import { useAuth } from './AuthProvider';
import { hasPermission } from './permissions';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { ready, session } = useAuth();
  const location = useLocation();
  if (!ready) {
    return (
      <div className="login">
        <Spinner label="Signing in" />
      </div>
    );
  }
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <>{children}</>;
}

/** Route guard: renders a clear 403 page when the user lacks the permission (e.g. direct URL access). */
export function RequirePermission({ perm, children }: { perm: string; children: ReactNode }) {
  const me = useMe();
  if (me.isPending) return <Spinner />;
  if (!hasPermission(me.data?.permissions, perm)) return <ForbiddenPage permission={perm} />;
  return <>{children}</>;
}
