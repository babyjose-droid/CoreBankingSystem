-- End of day (US-108, US-109, US-110): step state, checkpoints for idempotent restart, schedule.
ALTER TABLE platform.eod_run ADD COLUMN started_by text, ADD COLUMN next_business_date date;
-- Only one run per tenant can be RUNNING (single instance).
CREATE UNIQUE INDEX eod_single_running ON platform.eod_run ((true)) WHERE status = 'RUNNING';
-- A business date is closed once: at most one successful run per date.
CREATE UNIQUE INDEX eod_one_success_per_date ON platform.eod_run (business_date)
  WHERE status IN ('COMPLETED','COMPLETED_WITH_EXCEPTIONS');

CREATE TABLE platform.eod_step_run (
    run_id      bigint NOT NULL REFERENCES platform.eod_run(id),
    step_no     int NOT NULL,
    name        text NOT NULL,
    status      text NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','COMPLETED_WITH_EXCEPTIONS','FAILED','SKIPPED')),
    processed   int NOT NULL DEFAULT 0,
    failed      int NOT NULL DEFAULT 0,
    started_at  timestamptz,
    finished_at timestamptz,
    PRIMARY KEY (run_id, step_no)
);

CREATE TABLE platform.eod_checkpoint (
    run_id  bigint NOT NULL REFERENCES platform.eod_run(id),
    step_no int NOT NULL,
    item    text NOT NULL,
    PRIMARY KEY (run_id, step_no, item)
);

-- The exception table from V2 keyed by step name; add the step number and when it happened.
ALTER TABLE platform.eod_exception ADD COLUMN step_no int, ADD COLUMN at timestamptz NOT NULL DEFAULT now();

CREATE TABLE platform.eod_schedule (
    id           smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    mode         text NOT NULL DEFAULT 'MANUAL' CHECK (mode IN ('MANUAL','SCHEDULED')),
    cron         text,
    alert_emails text[] NOT NULL DEFAULT '{}',
    CHECK (mode = 'MANUAL' OR cron IS NOT NULL)
);
INSERT INTO platform.eod_schedule (id) VALUES (1);

-- Trial balance gate: every branch and the whole book must net to zero before the date can move.
CREATE FUNCTION ledger.trial_balance_gate(d date) RETURNS void LANGUAGE plpgsql AS $$
DECLARE bad record;
BEGIN
  SELECT branch_code, sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) AS net INTO bad
    FROM ledger.account_entry WHERE business_date <= d
   GROUP BY branch_code HAVING sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) <> 0 LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION 'trial balance for branch % is out by %', bad.branch_code, bad.net USING ERRCODE = '23514';
  END IF;
END $$;
