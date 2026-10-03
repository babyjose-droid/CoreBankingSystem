import { useState } from 'react';
import { useMe } from '../../api/hooks';
import { useEndSession, useSessions } from '../../api/platformHooks';
import type { LoginSession } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, DateTimeText, Dialog, EmptyState, ErrorBanner, Input, PageHeader, Spinner, Table, useToast } from '../../ui';

export function SessionsPage() {
  const me = useMe().data!;
  const isAdmin = hasPermission(me.permissions, P.sessionAdmin);
  const [draft, setDraft] = useState('');
  const [other, setOther] = useState<string | null>(null);
  const q = useSessions(other);
  const end = useEndSession();
  const toast = useToast();
  const [ending, setEnding] = useState<LoginSession | null>(null);
  const whose = other ? `Sessions of ${other}` : 'My sessions';
  return (
    <div className="stack">
      <PageHeader title="Sessions" subtitle="Where this account is signed in. Ending a session signs that browser or device out; work already saved is not affected." />
      {isAdmin && (
        <Card title="Look up another user">
          <form
            className="filters"
            style={{ marginBottom: 0 }}
            onSubmit={(e) => {
              e.preventDefault();
              setOther(draft.trim() && draft.trim() !== me.userId ? draft.trim() : null);
            }}
          >
            <Input label="Username" className="mono" value={draft} onChange={(e) => setDraft(e.target.value)} hint="Leave empty for your own sessions" />
            <Button type="submit">Show sessions</Button>
          </form>
        </Card>
      )}
      <Card title={whose} flush>
        <ErrorBanner error={q.error} />
        {q.isLoading ? (
          <div style={{ padding: 16 }}>
            <Spinner />
          </div>
        ) : (
          <Table
            caption={whose}
            captionHidden
            columns={[
              { key: 'started', header: 'Signed in', render: (s) => <DateTimeText value={s.startedAt} /> },
              { key: 'last', header: 'Last active', render: (s) => <DateTimeText value={s.lastAccessAt} /> },
              { key: 'ip', header: 'IP address', render: (s) => <span className="mono">{s.ipAddress ?? '—'}</span> },
              { key: 'clients', header: 'Applications', render: (s) => (s.clients ?? []).join(', ') || '—' },
              { key: 'cur', header: 'Current', render: (s) => (s.current ? <Badge tone="ok">This session</Badge> : null) },
              {
                key: 'act',
                header: <span className="sr-only">Actions</span>,
                render: (s) => (
                  <Button size="sm" variant={s.current ? 'danger' : undefined} aria-label={`End session from ${s.ipAddress ?? 'unknown address'}${s.current ? ' (this session)' : ''}`} onClick={() => setEnding(s)}>
                    End session
                  </Button>
                ),
              },
            ]}
            rows={q.data ?? []}
            rowKey={(s) => s.id ?? ''}
            empty={<EmptyState title="No active sessions" />}
          />
        )}
      </Card>
      {ending && (
        <Dialog
          open
          onClose={() => setEnding(null)}
          title="End session"
          footer={
            <>
              <Button onClick={() => setEnding(null)}>Cancel</Button>
              <Button
                variant="danger"
                loading={end.isPending}
                onClick={() =>
                  end.mutate(
                    { id: ending.id!, user: other },
                    {
                      onSuccess: () => {
                        toast({ tone: 'success', message: 'Session ended.' });
                        setEnding(null);
                      },
                    },
                  )
                }
              >
                End session
              </Button>
            </>
          }
        >
          <div className="stack">
            <p style={{ margin: 0 }}>
              {ending.current
                ? 'This is the session you are using now. Ending it signs you out here on your next action.'
                : `The session from ${ending.ipAddress ?? 'an unknown address'} will be signed out${other ? ` for ${other}` : ''}.`}
            </p>
            <ErrorBanner error={end.error} />
          </div>
        </Dialog>
      )}
    </div>
  );
}
