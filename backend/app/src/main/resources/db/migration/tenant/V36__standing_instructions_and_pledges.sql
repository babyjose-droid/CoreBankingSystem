-- Phase 4 (P4-5b): standing instructions on savings and current accounts, and loans against term deposits.
--   1. Settings: the days a standing instruction that could not be carried out is tried again; a bank's lending policy
--      for loans against deposits (the most lent as a percentage of the deposits pledged, the points above the deposit
--      rate) where the rule set has none (a deposit-taking NBFC has its own: 75%, 2 points, after three months).
--   2. Movement LOAN_REPAYMENT: a savings or current account pays a loan instalment.
--   3. deposits.standing_instruction: a savings or current account pays, on its own, a fixed amount to another account
--      every 1, 3, 6 or 12 months (TRANSFER), each instalment of a loan on its due date (LOAN_INSTALMENT), or each
--      instalment of a recurring deposit on its due date (RD_INSTALMENT). deposits.standing_instruction_run: what was
--      paid for each date, and every attempt that could not be.
--   4. The job STANDING_INSTRUCTIONS (every 15 minutes; also right after end of day).
--   5. A pledge: a lien on a term deposit that secures a loan (deposits.account_hold.loan_id), placed while the loan is
--      sanctioned or active and released only once it is closed or cancelled.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Settings
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('deposits.si.retry-days', '3', 'migration'),            -- a payment not made on its date is tried for this many days more
  ('deposits.loan.max-percent', '90', 'migration'),        -- a bank's policy: the most lent against deposits, % of their value
  ('deposits.loan.spread-percent', '1', 'migration')       -- a bank's policy: the loan rate at least this many points above the deposit's
ON CONFLICT (key) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- 2. Movements
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.movement DROP CONSTRAINT movement_kind;
ALTER TABLE deposits.movement
  ADD CONSTRAINT movement_kind CHECK (kind IN ('CASH_DEPOSIT','CASH_WITHDRAWAL','BANK_TRANSFER_IN','BANK_TRANSFER_OUT','TRANSFER_IN',
                                               'TRANSFER_OUT','FUNDING','FUNDING_PAID','REVERSAL','INTEREST_CREDIT','INTEREST_PAYOUT',
                                               'TAX_DEDUCTED','CHARGE','CLOSURE','INTEREST_RECOVERY','RENEWAL','RD_INSTALMENT','RD_LATE_CHARGE',
                                               'LOAN_REPAYMENT'));
-- movement_direction (V35) already makes every kind not listed as a credit a debit; guard_movement (V35) already keeps
-- a term deposit to its own kinds, so LOAN_REPAYMENT is taken by a savings or current account only.

-- ---------------------------------------------------------------------------------------------------------
-- 3. Standing instructions
-- ---------------------------------------------------------------------------------------------------------
CREATE SEQUENCE deposits.standing_instruction_no;

CREATE TABLE deposits.standing_instruction (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    instruction_no   text NOT NULL UNIQUE DEFAULT 'SI' || lpad(nextval('deposits.standing_instruction_no')::text, 8, '0'),
    from_account_id  uuid NOT NULL REFERENCES deposits.account(id),
    kind             text NOT NULL CHECK (kind IN ('TRANSFER','LOAN_INSTALMENT','RD_INSTALMENT')),
    to_account_id    uuid REFERENCES deposits.account(id),        -- TRANSFER: a savings or current account; RD_INSTALMENT: the deposit
    loan_id          uuid REFERENCES lending.loan_account(id),    -- LOAN_INSTALMENT
    amount           platform.money CHECK (amount IS NULL OR (amount > 0 AND amount = round(amount, 2))),
    every_months     smallint CHECK (every_months IN (1, 3, 6, 12)),
    start_on         date NOT NULL,
    end_on           date,
    narration        text CHECK (narration IS NULL OR length(narration) <= 200),
    status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED','ENDED')),
    created_by       text NOT NULL,
    approval_id      uuid REFERENCES platform.approval_request(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    closed_on        date,
    closed_by        text,
    close_reason     text CHECK (close_reason IS NULL OR length(close_reason) <= 500),
    close_approval_id uuid REFERENCES platform.approval_request(id),
    CONSTRAINT si_shape CHECK (CASE kind
        WHEN 'TRANSFER' THEN to_account_id IS NOT NULL AND loan_id IS NULL AND amount IS NOT NULL AND every_months IS NOT NULL
        WHEN 'LOAN_INSTALMENT' THEN to_account_id IS NULL AND loan_id IS NOT NULL AND amount IS NULL AND every_months IS NULL
        ELSE to_account_id IS NOT NULL AND loan_id IS NULL AND amount IS NULL AND every_months IS NULL END),
    CONSTRAINT si_not_to_itself CHECK (to_account_id IS NULL OR to_account_id <> from_account_id),
    CONSTRAINT si_dates CHECK (end_on IS NULL OR end_on >= start_on),
    CONSTRAINT si_closed CHECK ((status = 'ACTIVE') = (closed_on IS NULL) AND (closed_on IS NULL OR close_reason IS NOT NULL))
);
CREATE INDEX standing_instruction_from ON deposits.standing_instruction (from_account_id);
CREATE INDEX standing_instruction_active ON deposits.standing_instruction (status) WHERE status = 'ACTIVE';
-- one standing instruction pays a loan or a recurring deposit
CREATE UNIQUE INDEX standing_instruction_one_per_loan ON deposits.standing_instruction (loan_id) WHERE status = 'ACTIVE' AND loan_id IS NOT NULL;
CREATE UNIQUE INDEX standing_instruction_one_per_rd ON deposits.standing_instruction (to_account_id)
  WHERE status = 'ACTIVE' AND kind = 'RD_INSTALMENT';

CREATE FUNCTION deposits.guard_standing_instruction() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f deposits.account%ROWTYPE; t deposits.account%ROWTYPE; ptype text; today date; lstatus text; lcust uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a standing instruction is never deleted; cancel it' USING ERRCODE = '42501'; END IF;
  SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'ACTIVE' AND NEW.status IN ('CANCELLED','ENDED')
       AND (to_jsonb(NEW) - 'status' - 'closed_on' - 'closed_by' - 'close_reason' - 'close_approval_id')
         = (to_jsonb(OLD) - 'status' - 'closed_on' - 'closed_by' - 'close_reason' - 'close_approval_id') THEN
      NEW.closed_on := today;
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'standing instruction %: an instruction never changes; cancel it and give a new one', OLD.instruction_no
      USING ERRCODE = '42501';
  END IF;
  IF NEW.status <> 'ACTIVE' THEN RAISE EXCEPTION 'a new standing instruction is active' USING ERRCODE = '23514'; END IF;
  IF NEW.start_on < today THEN
    RAISE EXCEPTION 'a standing instruction starts on or after the business date (%)', today USING ERRCODE = '23514';
  END IF;
  SELECT * INTO f FROM deposits.account WHERE id = NEW.from_account_id;
  IF f.family <> 'CASA' OR f.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'account % is %: a standing instruction is given on an active savings or current account', f.account_no,
      CASE WHEN f.family <> 'CASA' THEN 'a term deposit' ELSE f.status END USING ERRCODE = '23514';
  END IF;
  IF NEW.kind = 'TRANSFER' THEN
    SELECT * INTO t FROM deposits.account WHERE id = NEW.to_account_id;
    IF t.family <> 'CASA' OR t.status <> 'ACTIVE' THEN
      RAISE EXCEPTION 'account %: a standing transfer goes to an active savings or current account', t.account_no USING ERRCODE = '23514';
    END IF;
  ELSIF NEW.kind = 'RD_INSTALMENT' THEN
    SELECT * INTO t FROM deposits.account WHERE id = NEW.to_account_id;
    SELECT product_type INTO ptype FROM deposits.product WHERE code = t.product_code;
    IF t.family <> 'TERM_DEPOSIT' OR ptype <> 'RD' OR t.status <> 'ACTIVE' THEN
      RAISE EXCEPTION 'account % is not an active recurring deposit', t.account_no USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM deposits.account_holder x JOIN deposits.account_holder y ON y.customer_id = x.customer_id
                    WHERE x.account_id = f.id AND y.account_id = t.id) THEN
      RAISE EXCEPTION 'account % and recurring deposit % have no holder in common', f.account_no, t.account_no USING ERRCODE = '23514';
    END IF;
  ELSE
    SELECT status, customer_id INTO lstatus, lcust FROM lending.loan_account WHERE id = NEW.loan_id;
    IF lstatus NOT IN ('SANCTIONED','ACTIVE') THEN
      RAISE EXCEPTION 'the loan is %: a standing instruction pays a sanctioned or active loan', lstatus USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM deposits.account_holder WHERE account_id = f.id AND customer_id = lcust) THEN
      RAISE EXCEPTION 'account % is not held by the borrower', f.account_no USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER standing_instruction_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.standing_instruction
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_standing_instruction();
CREATE TRIGGER standing_instruction_no_truncate BEFORE TRUNCATE ON deposits.standing_instruction
  FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- One row per date paid (DONE), and one per date and business day an attempt failed (FAILED, with how many times).
CREATE TABLE deposits.standing_instruction_run (
    id              bigserial PRIMARY KEY,
    instruction_id  uuid NOT NULL REFERENCES deposits.standing_instruction(id),
    for_date        date NOT NULL,                     -- the date the payment was due under the instruction
    business_date   date NOT NULL,
    status          text NOT NULL CHECK (status IN ('DONE','FAILED')),
    amount          platform.money CHECK (amount IS NULL OR amount > 0),
    lot_id          uuid REFERENCES ledger.transaction_lot(id),
    movement_id     uuid REFERENCES deposits.movement(id),          -- the debit to the paying account
    loan_txn_id     uuid,                                           -- the loan's repayment (LOAN_INSTALMENT)
    reason          text CHECK (reason IS NULL OR length(reason) <= 1000),
    attempts        int NOT NULL DEFAULT 1 CHECK (attempts >= 1),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT si_run_shape CHECK (CASE status WHEN 'DONE' THEN lot_id IS NOT NULL AND movement_id IS NOT NULL AND amount IS NOT NULL AND reason IS NULL
                                                ELSE lot_id IS NULL AND movement_id IS NULL AND reason IS NOT NULL END)
);
CREATE UNIQUE INDEX si_run_done_once ON deposits.standing_instruction_run (instruction_id, for_date) WHERE status = 'DONE';
CREATE UNIQUE INDEX si_run_failed_per_day ON deposits.standing_instruction_run (instruction_id, for_date, business_date) WHERE status = 'FAILED';
CREATE INDEX si_run_instruction ON deposits.standing_instruction_run (instruction_id, for_date DESC);

CREATE FUNCTION deposits.guard_standing_instruction_run() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m deposits.movement%ROWTYPE; si deposits.standing_instruction%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a standing instruction run is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'FAILED' AND NEW.status = 'FAILED'
       AND (to_jsonb(NEW) - 'reason' - 'attempts' - 'updated_at') = (to_jsonb(OLD) - 'reason' - 'attempts' - 'updated_at') THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a standing instruction run never changes, except to count another failed attempt' USING ERRCODE = '42501';
  END IF;
  SELECT business_date INTO NEW.business_date FROM platform.business_day WHERE id = 1;
  IF NEW.status = 'DONE' THEN
    SELECT * INTO si FROM deposits.standing_instruction WHERE id = NEW.instruction_id;
    SELECT * INTO m FROM deposits.movement WHERE id = NEW.movement_id;
    IF m.account_id <> si.from_account_id OR m.direction <> 'DEBIT' OR m.lot_id <> NEW.lot_id
       OR m.kind <> (CASE si.kind WHEN 'TRANSFER' THEN 'TRANSFER_OUT' WHEN 'LOAN_INSTALMENT' THEN 'LOAN_REPAYMENT' ELSE 'FUNDING_PAID' END) THEN
      RAISE EXCEPTION 'standing instruction %: the run is the debit to the paying account of its own kind', si.instruction_no USING ERRCODE = '23514';
    END IF;
    IF si.kind <> 'RD_INSTALMENT' AND m.amount <> NEW.amount THEN
      RAISE EXCEPTION 'standing instruction %: the run is for the amount debited', si.instruction_no USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER standing_instruction_run_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.standing_instruction_run
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_standing_instruction_run();
CREATE TRIGGER standing_instruction_run_no_truncate BEFORE TRUNCATE ON deposits.standing_instruction_run
  FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- ---------------------------------------------------------------------------------------------------------
-- 4. The job
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE platform.job_definition DROP CONSTRAINT job_definition_kind_check;
ALTER TABLE platform.job_definition ADD CONSTRAINT job_definition_kind_check
  CHECK (kind IN ('REPORT','DASHBOARD_REFRESH','KYC_EXPIRY','CONSENT_EXPIRY','EXPORT_CLEANUP','USAGE_SNAPSHOT','DEFERRED_RECEIPTS',
                  'STANDING_INSTRUCTIONS'));
INSERT INTO platform.job_definition (code, name, kind, schedule, enabled, parameters, built_in) VALUES
  ('STANDING_INSTRUCTIONS', 'Carry out standing instructions due', 'STANDING_INSTRUCTIONS', '0 */15 * * * *', true, '{}', true)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- 5. Pledges
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.account_hold ADD COLUMN loan_id uuid REFERENCES lending.loan_account(id),
  ADD CONSTRAINT hold_pledge_is_a_lien CHECK (loan_id IS NULL OR (kind = 'LIEN' AND amount IS NOT NULL));
CREATE UNIQUE INDEX account_hold_one_pledge ON deposits.account_hold (account_id) WHERE status = 'ACTIVE' AND loan_id IS NOT NULL;
CREATE INDEX account_hold_loan ON deposits.account_hold (loan_id) WHERE loan_id IS NOT NULL;

CREATE FUNCTION deposits.guard_pledge() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; lstatus text; lcust uuid; lno text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.loan_id IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
    SELECT status, customer_id, loan_no INTO lstatus, lcust, lno FROM lending.loan_account WHERE id = NEW.loan_id;
    IF a.family <> 'TERM_DEPOSIT' THEN
      RAISE EXCEPTION 'account %: a loan is secured by a term deposit', a.account_no USING ERRCODE = '23514';
    END IF;
    IF lstatus NOT IN ('SANCTIONED','ACTIVE') THEN
      RAISE EXCEPTION 'loan % is %: a deposit is pledged for a sanctioned or active loan', lno, lstatus USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM deposits.account_holder WHERE account_id = a.id AND customer_id = lcust) THEN
      RAISE EXCEPTION 'deposit % is not held by the borrower of loan %', a.account_no, lno USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  -- a pledge is released once its loan is closed or cancelled
  IF OLD.loan_id IS NOT NULL AND OLD.status = 'ACTIVE' AND NEW.status = 'RELEASED' THEN
    SELECT status, loan_no INTO lstatus, lno FROM lending.loan_account WHERE id = OLD.loan_id;
    IF lstatus NOT IN ('CLOSED','CANCELLED') THEN
      RAISE EXCEPTION 'the deposit secures loan %, which is %: the pledge is released when the loan is closed', lno, lstatus
        USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pledge_guard BEFORE INSERT OR UPDATE ON deposits.account_hold FOR EACH ROW EXECUTE FUNCTION deposits.guard_pledge();
