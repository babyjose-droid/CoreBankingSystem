import { Link } from 'react-router';
import { Card } from '../ui';

export function ForbiddenPage({ permission }: { permission?: string }) {
  return (
    <div style={{ maxWidth: 560 }}>
      <Card title="403 — Access denied">
        <p style={{ marginTop: 0 }}>You don’t have access to this page.</p>
        {permission && (
          <p className="muted">
            It requires the <code>{permission}</code> permission. Ask your administrator if you need it.
          </p>
        )}
        <Link to="/">Go to Home</Link>
      </Card>
    </div>
  );
}

export function NotFoundPage() {
  return (
    <div style={{ maxWidth: 560 }}>
      <Card title="Page not found">
        <p style={{ marginTop: 0 }}>There is nothing at this address.</p>
        <Link to="/">Go to Home</Link>
      </Card>
    </div>
  );
}
