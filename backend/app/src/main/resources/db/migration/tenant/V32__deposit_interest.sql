-- Phase 4 (P4-3): interest on deposits at day-end.
--   1. Setting: the head interest payable on deposits is booked to (2404).
--   2. Movements: interest credited to an account (INTEREST_CREDIT) and a term deposit's interest paid out to a
--      savings or current account (INTEREST_PAYOUT) or to a bank account through the gateway (BANK_TRANSFER_OUT).
--      A freeze does not stop interest being credited. A funded deposit holds at least its principal (a cumulative
--      deposit holds its principal and the interest added to it).
--   3. deposits.interest_state: where each account's interest stands (accrued through which day, accrued and not yet
--      credited, credited so far); deposits.interest_event: every accrual, credit and payout the day-end made.
--      deposits.interest_problems() lists the branches whose interest-payable head does not equal what the accounts
--      have accrued and not been credited.
-- The day-end steps (DepositEod) work the amounts out with the deposit engine (InterestDayEnd).

-- ---------------------------------------------------------------------------------------------------------
-- 1. Setting
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('deposits.gl.interest-payable', '2404', 'migration')   -- interest accrued on deposits and not yet credited
ON CONFLICT (key) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- 2. Movements
-- ---------------------------------------------------------------------------------------------------------
DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'deposits.movement'::regclass AND contype = 'c'
              AND (pg_get_constraintdef(oid) LIKE '%CASH_WITHDRAWAL%' OR conname IN ('movement_direction','movement_counterparty')) LOOP
    EXECUTE format('ALTER TABLE deposits.movement DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE deposits.movement
  ADD CONSTRAINT movement_kind CHECK (kind IN ('CASH_DEPOSIT','CASH_WITHDRAWAL','BANK_TRANSFER_IN','BANK_TRANSFER_OUT','TRANSFER_IN',
                                               'TRANSFER_OUT','FUNDING','FUNDING_PAID','REVERSAL','INTEREST_CREDIT','INTEREST_PAYOUT')),
  ADD CONSTRAINT movement_direction CHECK (kind = 'REVERSAL' OR direction = CASE
        WHEN kind IN ('CASH_DEPOSIT','BANK_TRANSFER_IN','TRANSFER_IN','FUNDING','INTEREST_CREDIT') THEN 'CREDIT' ELSE 'DEBIT' END),
  ADD CONSTRAINT movement_counterparty CHECK ((kind IN ('TRANSFER_IN','TRANSFER_OUT','FUNDING_PAID','INTEREST_PAYOUT')) <= (counterparty_id IS NOT NULL));

CREATE OR REPLACE FUNCTION deposits.guard_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; o deposits.movement%ROWTYPE; bal numeric; liens numeric; frz text; today date;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a deposit movement is never deleted; reverse it' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.reversed_by IS NULL AND NEW.reversed_by IS NOT NULL
       AND (to_jsonb(NEW) - 'reversed_by') = (to_jsonb(OLD) - 'reversed_by') THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a deposit movement never changes, except to record the movement that reverses it' USING ERRCODE = '42501';
  END IF;
  IF NEW.reversed_by IS NOT NULL THEN RAISE EXCEPTION 'a new movement is not yet reversed' USING ERRCODE = '23514'; END IF;
  SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'deposit account % not found', NEW.account_id USING ERRCODE = 'P0002'; END IF;
  SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
  NEW.business_date := today;
  NEW.value_date := coalesce(NEW.value_date, today);
  IF NEW.value_date <> today THEN
    RAISE EXCEPTION 'account %: a movement takes value on the business date (%)', a.account_no, today USING ERRCODE = '23514';
  END IF;
  -- which account takes which movement
  IF NEW.kind = 'REVERSAL' THEN
    SELECT * INTO o FROM deposits.movement WHERE id = NEW.reverses FOR UPDATE;
    IF NOT FOUND OR o.account_id <> NEW.account_id THEN
      RAISE EXCEPTION 'account %: a reversal reverses a movement of the same account', a.account_no USING ERRCODE = '23514';
    END IF;
    IF o.kind = 'REVERSAL' THEN RAISE EXCEPTION 'account %: a reversal is not reversed', a.account_no USING ERRCODE = '23514'; END IF;
    IF o.reversed_by IS NOT NULL THEN
      RAISE EXCEPTION 'account %: the movement is already reversed', a.account_no USING ERRCODE = '23514';
    END IF;
    IF NEW.amount <> o.amount OR NEW.direction = o.direction THEN
      RAISE EXCEPTION 'account %: a reversal is the same amount in the other direction', a.account_no USING ERRCODE = '23514';
    END IF;
  ELSIF a.family = 'CASA' AND NEW.kind IN ('FUNDING','INTEREST_PAYOUT') THEN
    RAISE EXCEPTION 'account % is not a term deposit', a.account_no USING ERRCODE = '23514';
  ELSIF a.family = 'TERM_DEPOSIT' AND NEW.kind NOT IN ('FUNDING','INTEREST_CREDIT','INTEREST_PAYOUT','BANK_TRANSFER_OUT') THEN
    RAISE EXCEPTION 'term deposit %: money moves by its funding and its interest only (withdrawal and closure come with maturity processing)',
      a.account_no USING ERRCODE = '23514';
  END IF;
  -- the state of the account
  IF a.family = 'CASA' AND a.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'account % is %', a.account_no, a.status USING ERRCODE = '23514';
  END IF;
  IF a.family = 'TERM_DEPOSIT' AND NEW.kind = 'FUNDING' AND a.status <> 'BOOKED' THEN
    RAISE EXCEPTION 'term deposit % is %; only a booked deposit is funded', a.account_no, a.status USING ERRCODE = '23514';
  END IF;
  IF a.family = 'TERM_DEPOSIT' AND NEW.kind <> 'FUNDING' AND a.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'term deposit % is %', a.account_no, a.status USING ERRCODE = '23514';
  END IF;
  -- freezes stop the customer's movements, not corrections and not the interest the bank credits (P4-3)
  IF NEW.kind NOT IN ('REVERSAL','INTEREST_CREDIT') THEN
    SELECT string_agg(lower(replace(kind, '_', ' ')), ', ' ORDER BY kind) INTO frz FROM deposits.account_hold
     WHERE account_id = a.id AND status = 'ACTIVE'
       AND (kind = 'TOTAL_FREEZE' OR kind = CASE NEW.direction WHEN 'DEBIT' THEN 'DEBIT_FREEZE' ELSE 'CREDIT_FREEZE' END);
    IF frz IS NOT NULL THEN
      RAISE EXCEPTION 'account % is under a %', a.account_no, frz USING ERRCODE = '23514';
    END IF;
  END IF;
  -- the balance: never below zero, a debit never below the liens
  INSERT INTO deposits.account_balance (account_id) VALUES (a.id) ON CONFLICT (account_id) DO NOTHING;
  SELECT balance INTO bal FROM deposits.account_balance WHERE account_id = a.id FOR UPDATE;
  bal := bal + CASE NEW.direction WHEN 'CREDIT' THEN NEW.amount ELSE -NEW.amount END;
  IF bal < 0 THEN
    RAISE EXCEPTION 'account %: the balance would fall below zero', a.account_no USING ERRCODE = '23514';
  END IF;
  IF NEW.direction = 'DEBIT' THEN
    SELECT coalesce(sum(amount), 0) INTO liens FROM deposits.account_hold WHERE account_id = a.id AND status = 'ACTIVE' AND kind = 'LIEN';
    IF bal < liens THEN
      RAISE EXCEPTION 'account %: % is held under lien; the balance would fall below it', a.account_no, deposits.plain(liens)
        USING ERRCODE = '23514';
    END IF;
  END IF;
  UPDATE deposits.account_balance SET balance = bal, updated_at = now() WHERE account_id = a.id;
  NEW.balance_after := bal;
  RETURN NEW;
END $$;

-- A funded deposit holds at least its principal: interest credited to it (cumulative) stays on it until maturity, and
-- interest paid out leaves it only after it was credited.
CREATE OR REPLACE FUNCTION deposits.check_funding(p_account uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; t deposits.term_deposit%ROWTYPE; m deposits.movement%ROWTYPE; bal numeric;
BEGIN
  SELECT * INTO a FROM deposits.account WHERE id = p_account;
  IF a.family <> 'TERM_DEPOSIT' OR a.status NOT IN ('BOOKED','ACTIVE') THEN RETURN; END IF;
  SELECT * INTO t FROM deposits.term_deposit WHERE account_id = p_account;
  IF NOT FOUND THEN RETURN; END IF;   -- the booking itself writes the row later in the same transaction
  SELECT coalesce(max(balance), 0) INTO bal FROM deposits.account_balance WHERE account_id = p_account;
  IF a.status = 'BOOKED' THEN
    IF t.start_date IS NOT NULL OR bal <> 0 THEN
      RAISE EXCEPTION 'term deposit %: a booked deposit is not funded and holds nothing', a.account_no USING ERRCODE = '23514';
    END IF;
    RETURN;
  END IF;
  IF t.start_date IS NULL THEN
    RAISE EXCEPTION 'term deposit %: an active deposit is funded', a.account_no USING ERRCODE = '23514';
  END IF;
  SELECT * INTO m FROM deposits.movement WHERE id = t.funding_movement_id;
  IF m.account_id IS DISTINCT FROM p_account OR m.kind <> 'FUNDING' OR m.reversed_by IS NOT NULL OR m.amount <> t.principal THEN
    RAISE EXCEPTION 'term deposit %: the funding recorded is not a funding of the principal', a.account_no USING ERRCODE = '23514';
  END IF;
  IF bal < t.principal THEN
    RAISE EXCEPTION 'term deposit %: a funded deposit holds at least its principal', a.account_no USING ERRCODE = '23514';
  END IF;
  IF t.funding_account_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM deposits.account_holder h WHERE h.account_id = t.funding_account_id AND h.customer_id = a.customer_id) THEN
    RAISE EXCEPTION 'term deposit %: it is funded from an account the depositor holds', a.account_no USING ERRCODE = '23514';
  END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- 3. Interest state and events
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.interest_state (
    account_id       uuid PRIMARY KEY REFERENCES deposits.account(id),
    accrued_through  date NOT NULL,                               -- the last day accrued
    period_from      date,                                        -- savings: first day of the open interest period
    exact            numeric(30,8) NOT NULL DEFAULT 0,            -- savings: the open period's interest, unrounded
    accrued          numeric(20,4) NOT NULL DEFAULT 0 CHECK (accrued >= 0),   -- booked to payable, not yet credited
    booked           numeric(20,4) NOT NULL DEFAULT 0 CHECK (booked >= 0),    -- term deposit: booked since the start
    credited         numeric(20,4) NOT NULL DEFAULT 0 CHECK (credited >= 0),  -- credited to the account so far
    periods_credited int NOT NULL DEFAULT 0 CHECK (periods_credited >= 0),     -- term deposit
    last_credit_on   date,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE deposits.interest_event (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    uuid NOT NULL REFERENCES deposits.account(id),
    kind          text NOT NULL CHECK (kind IN ('ACCRUAL','CREDIT','PAYOUT','PAYOUT_HELD','REVERSAL')),
    period_from   date,
    period_to     date,
    period_no     int,
    amount        numeric(20,4) NOT NULL,
    lot_id        uuid REFERENCES ledger.transaction_lot(id),
    movement_id   uuid REFERENCES deposits.movement(id),
    business_date date NOT NULL,
    note          text,
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT interest_event_amount CHECK (kind IN ('ACCRUAL','REVERSAL') OR amount > 0),
    CONSTRAINT interest_event_lot CHECK ((kind = 'PAYOUT_HELD') = (lot_id IS NULL))
);
CREATE INDEX interest_event_account ON deposits.interest_event (account_id, id);
CREATE TRIGGER interest_event_immutable BEFORE UPDATE OR DELETE ON deposits.interest_event
  FOR EACH ROW EXECUTE FUNCTION deposits.forbid_change();
CREATE TRIGGER interest_event_no_truncate BEFORE TRUNCATE ON deposits.interest_event
  FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- Branches whose interest-payable head does not equal what their accounts have accrued and not been credited.
CREATE FUNCTION deposits.interest_problems() RETURNS TABLE (branch_code text, kept numeric, ledger numeric) LANGUAGE sql STABLE AS $$
  WITH payable AS (SELECT coalesce((SELECT value FROM platform.system_property WHERE key = 'deposits.gl.interest-payable'), '2404') AS gl),
       kept AS (SELECT a.branch_code, sum(s.accrued) AS amount FROM deposits.interest_state s JOIN deposits.account a ON a.id = s.account_id
                 GROUP BY a.branch_code),
       booked AS (SELECT e.branch_code, sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END) AS amount
                    FROM ledger.account_entry e, payable WHERE e.gl_code = payable.gl GROUP BY e.branch_code)
  SELECT coalesce(k.branch_code, b.branch_code), coalesce(k.amount, 0), coalesce(b.amount, 0)
    FROM kept k FULL JOIN booked b ON b.branch_code = k.branch_code
   WHERE coalesce(k.amount, 0) <> coalesce(b.amount, 0)
$$;
