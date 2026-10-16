-- Phase 4 (P4-3b): tax deducted at source on deposit interest (section 393 of the Income-tax Act, 2025).
--   1. Settings: the head tax deducted is owed on (2205, TDS payable on deposit interest) and how a deduction is rounded
--      (to the rupee).
--   2. Movement TAX_DEDUCTED: tax taken from interest just credited (a freeze does not stop it).
--   3. deposits.tds_ledger: every interest credit of a product that deducts tax, with the tax deducted (zero below the
--      threshold) and why, per depositor and tax year; deposits.tds_problems() checks the TDS payable head against it.
--   4. deposits.tds_declaration: Form 121 (which replaced Forms 15G and 15H from 1-Apr-2026) recorded per depositor and
--      tax year, through maker-checker; revoked, never deleted.
-- The rate, the thresholds and the senior-citizen age are rules of the rule set (tenant V28; open item P4-02). The day-end
-- works the tax out with the deposit engine (TdsOnInterest) when it credits interest.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Settings
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('deposits.gl.tds-payable', '2205', 'migration'),          -- tax deducted from deposit interest, until paid to the government
  ('deposits.tds.rounding', 'RUPEE_HALF_UP', 'migration')    -- a deduction is rounded to the rupee
ON CONFLICT (key) DO NOTHING;
-- a deposit-taking tenant whose chart had no 'Statutory dues' group when its deposit heads were loaded gets the head now
INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting)
SELECT '2205', 'TDS payable on deposit interest', 'LIABILITY', '2200', true
 WHERE deposits.rule_kind() IS NOT NULL AND EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2200')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- 2. Movement
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.movement DROP CONSTRAINT movement_kind, DROP CONSTRAINT movement_direction;
ALTER TABLE deposits.movement
  ADD CONSTRAINT movement_kind CHECK (kind IN ('CASH_DEPOSIT','CASH_WITHDRAWAL','BANK_TRANSFER_IN','BANK_TRANSFER_OUT','TRANSFER_IN',
                                               'TRANSFER_OUT','FUNDING','FUNDING_PAID','REVERSAL','INTEREST_CREDIT','INTEREST_PAYOUT',
                                               'TAX_DEDUCTED')),
  ADD CONSTRAINT movement_direction CHECK (kind = 'REVERSAL' OR direction = CASE
        WHEN kind IN ('CASH_DEPOSIT','BANK_TRANSFER_IN','TRANSFER_IN','FUNDING','INTEREST_CREDIT') THEN 'CREDIT' ELSE 'DEBIT' END);

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
  ELSIF a.family = 'TERM_DEPOSIT' AND NEW.kind NOT IN ('FUNDING','INTEREST_CREDIT','INTEREST_PAYOUT','BANK_TRANSFER_OUT','TAX_DEDUCTED') THEN
    RAISE EXCEPTION 'term deposit %: money moves by its funding, its interest and the tax on it only (withdrawal and closure come with maturity processing)',
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
  -- freezes stop the customer's movements, not corrections, the interest the bank credits (P4-3) or the tax on it (P4-3b)
  IF NEW.kind NOT IN ('REVERSAL','INTEREST_CREDIT','TAX_DEDUCTED') THEN
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


ALTER TABLE deposits.interest_event DROP CONSTRAINT interest_event_kind_check;
ALTER TABLE deposits.interest_event
  ADD CONSTRAINT interest_event_kind CHECK (kind IN ('ACCRUAL','CREDIT','PAYOUT','PAYOUT_HELD','REVERSAL','TAX'));

-- ---------------------------------------------------------------------------------------------------------
-- 3. The tax ledger
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.tds_ledger (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id   uuid NOT NULL REFERENCES customer.customer(id),        -- the first holder: the payee
    tax_year      int NOT NULL CHECK (tax_year BETWEEN 2000 AND 2200),   -- named by the calendar year it starts in (1 April)
    account_id    uuid NOT NULL REFERENCES deposits.account(id),
    business_date date NOT NULL,
    interest      numeric(20,4) NOT NULL CHECK (interest > 0),
    tax           numeric(20,4) NOT NULL CHECK (tax >= 0 AND tax <= interest),
    rate_percent  numeric(8,4),
    basis         text NOT NULL CHECK (basis IN ('EXEMPT','DECLARATION','BELOW_THRESHOLD','RATE','NO_PAN_RATE')),
    senior        boolean NOT NULL,
    pan_on_file   boolean NOT NULL,
    declaration_id uuid,
    interest_movement_id uuid REFERENCES deposits.movement(id),
    tax_movement_id uuid REFERENCES deposits.movement(id),
    lot_id        uuid REFERENCES ledger.transaction_lot(id),
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tds_ledger_posted CHECK ((tax > 0) = (lot_id IS NOT NULL) AND (tax > 0) = (tax_movement_id IS NOT NULL)),
    CONSTRAINT tds_ledger_rate CHECK ((basis IN ('RATE','NO_PAN_RATE')) = (rate_percent IS NOT NULL))
);
CREATE INDEX tds_ledger_customer_year ON deposits.tds_ledger (customer_id, tax_year);
CREATE INDEX tds_ledger_account ON deposits.tds_ledger (account_id, id);
CREATE TRIGGER tds_ledger_immutable BEFORE UPDATE OR DELETE ON deposits.tds_ledger
  FOR EACH ROW EXECUTE FUNCTION deposits.forbid_change();
CREATE TRIGGER tds_ledger_no_truncate BEFORE TRUNCATE ON deposits.tds_ledger
  FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- Branches whose TDS payable head does not equal the tax the ledger says was deducted there (until paying the tax over
-- to the government is built, the head only ever takes deductions).
CREATE FUNCTION deposits.tds_problems() RETURNS TABLE (branch_code text, kept numeric, ledger numeric) LANGUAGE sql STABLE AS $$
  WITH payable AS (SELECT coalesce((SELECT value FROM platform.system_property WHERE key = 'deposits.gl.tds-payable'), '2205') AS gl),
       kept AS (SELECT a.branch_code, sum(t.tax) AS amount FROM deposits.tds_ledger t JOIN deposits.account a ON a.id = t.account_id
                 GROUP BY a.branch_code),
       booked AS (SELECT e.branch_code, sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END) AS amount
                    FROM ledger.account_entry e, payable WHERE e.gl_code = payable.gl GROUP BY e.branch_code)
  SELECT coalesce(k.branch_code, b.branch_code), coalesce(k.amount, 0), coalesce(b.amount, 0)
    FROM kept k FULL JOIN booked b ON b.branch_code = k.branch_code
   WHERE coalesce(k.amount, 0) <> coalesce(b.amount, 0)
$$;

-- ---------------------------------------------------------------------------------------------------------
-- 4. Form 121
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.tds_declaration (
    id            uuid PRIMARY KEY,
    customer_id   uuid NOT NULL REFERENCES customer.customer(id),
    tax_year      int NOT NULL CHECK (tax_year BETWEEN 2000 AND 2200),
    form          text NOT NULL DEFAULT 'FORM_121' CHECK (form IN ('FORM_121')),
    reference     text CHECK (reference IS NULL OR length(reference) <= 60),   -- the acknowledgement or receipt number
    received_on   date NOT NULL,
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    recorded_by   text NOT NULL,
    approval_id   uuid REFERENCES platform.approval_request(id),
    revoked_on    date,
    revoked_by    text,
    revoke_reason text CHECK (revoke_reason IS NULL OR (btrim(revoke_reason) <> '' AND length(revoke_reason) <= 500)),
    revoke_approval_id uuid REFERENCES platform.approval_request(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tds_declaration_revoked CHECK ((status = 'REVOKED') = (revoked_on IS NOT NULL)
                                              AND (status = 'REVOKED') = (revoke_reason IS NOT NULL))
);
CREATE UNIQUE INDEX tds_declaration_one_active ON deposits.tds_declaration (customer_id, tax_year) WHERE status = 'ACTIVE';

CREATE FUNCTION deposits.guard_tds_declaration() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c customer.customer%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a tax declaration is never deleted; revoke it' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'REVOKED' OR NEW.status <> 'REVOKED'
       OR (to_jsonb(NEW) - 'status' - 'revoked_on' - 'revoked_by' - 'revoke_reason' - 'revoke_approval_id')
          <> (to_jsonb(OLD) - 'status' - 'revoked_on' - 'revoked_by' - 'revoke_reason' - 'revoke_approval_id') THEN
      RAISE EXCEPTION 'a tax declaration never changes, except to be revoked once' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO c FROM customer.customer WHERE id = NEW.customer_id;
  IF c.customer_type <> 'INDIVIDUAL' THEN
    RAISE EXCEPTION 'customer %: Form 121 is a declaration by an individual', c.customer_no USING ERRCODE = '23514';
  END IF;
  IF c.pan_hash IS NULL THEN
    RAISE EXCEPTION 'customer %: a declaration without a PAN is not valid; record the PAN first', c.customer_no USING ERRCODE = '23514';
  END IF;
  IF NEW.status <> 'ACTIVE' THEN RAISE EXCEPTION 'a declaration is recorded active' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tds_declaration_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.tds_declaration
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_tds_declaration();
CREATE TRIGGER tds_declaration_no_truncate BEFORE TRUNCATE ON deposits.tds_declaration
  FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- Recording and revoking a declaration change the tax deducted: a checker approves both.
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('TDS_DECLARATION', '*', NULL, 1)
ON CONFLICT DO NOTHING;
