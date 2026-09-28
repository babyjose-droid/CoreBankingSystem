import { useEffect, useState } from 'react';
import { useApprovals, useEodSchedule, useMe, useProposeEodSchedule } from '../../api/hooks';
import type { EodSchedule } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { EMAIL_PATTERN } from '../../lib/mask';
import { Banner, Button, Card, ErrorBanner, Input, PageHeader, Spinner, Textarea } from '../../ui';
import { useProposalToast } from '../proposal';

export function parseEmails(text: string): string[] {
  return text
    .split(/[\s,;]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

export function EodSchedulePage() {
  const me = useMe().data!;
  const q = useEodSchedule();
  const canPropose = hasPermission(me.permissions, P.eodSchedule);
  const pending = useApprovals({ status: 'PENDING', entityType: 'EOD_SCHEDULE' }, hasPermission(me.permissions, P.approvalView));
  const propose = useProposeEodSchedule();
  const toast = useProposalToast();
  const [mode, setMode] = useState<EodSchedule['mode']>('MANUAL');
  const [cron, setCron] = useState('0 30 23 * * *');
  const [emails, setEmails] = useState('');
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    if (!q.data) return;
    setMode(q.data.mode);
    if (q.data.cron) setCron(q.data.cron);
    setEmails((q.data.alertEmails ?? []).join('\n'));
  }, [q.data]);

  const list = parseEmails(emails);
  const errors = {
    cron: mode === 'SCHEDULED' && cron.trim().split(/\s+/).length !== 6 ? 'Use 6 fields: second minute hour day month weekday' : null,
    emails: list.find((e) => !EMAIL_PATTERN.test(e)) ? `Invalid email: ${list.find((e) => !EMAIL_PATTERN.test(e))}` : null,
  };

  if (q.isLoading) return <Spinner />;
  return (
    <div className="stack" style={{ maxWidth: 720 }}>
      <PageHeader title="EOD schedule" subtitle="How end-of-day is triggered and who is alerted. Changes need approval." />
      {(pending.data ?? []).length > 0 && <Banner tone="warn">A schedule change is awaiting approval.</Banner>}
      <ErrorBanner error={q.error} />
      <Card>
        <form
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            setTouched(true);
            if (errors.cron || errors.emails) return;
            propose.mutate({ mode, cron: mode === 'SCHEDULED' ? cron.trim() : null, alertEmails: list }, { onSuccess: (a) => toast(a, 'Schedule change') });
          }}
        >
          <fieldset style={{ border: 0, padding: 0, margin: 0 }} disabled={!canPropose}>
            <legend className="field__label" style={{ marginBottom: 6 }}>
              Mode
            </legend>
            <div className="row" style={{ marginBottom: 16 }}>
              {(['MANUAL', 'SCHEDULED'] as const).map((m) => (
                <label key={m} className="checkbox">
                  <input type="radio" name="mode" value={m} checked={mode === m} onChange={() => setMode(m)} />
                  {m === 'MANUAL' ? 'Manual (operator starts EOD)' : 'Scheduled (cron)'}
                </label>
              ))}
            </div>
            <div className="stack">
              {mode === 'SCHEDULED' && (
                <Input
                  label="Cron expression"
                  required
                  className="mono"
                  value={cron}
                  onChange={(e) => setCron(e.target.value)}
                  hint="Spring format, tenant time zone. Example: 0 30 23 * * * = 23:30 daily"
                  error={touched ? errors.cron : null}
                />
              )}
              <Textarea
                label="Alert emails"
                value={emails}
                onChange={(e) => setEmails(e.target.value)}
                hint="One per line or comma-separated. Alerted on failure or exceptions."
                error={touched ? errors.emails : null}
              />
            </div>
          </fieldset>
          <ErrorBanner error={propose.error} />
          {canPropose ? (
            <div className="form-actions">
              <Button type="submit" variant="primary" loading={propose.isPending}>
                Propose change
              </Button>
            </div>
          ) : (
            <p className="muted">You can view the schedule; changing it requires the eod:schedule permission.</p>
          )}
        </form>
      </Card>
    </div>
  );
}
