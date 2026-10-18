-- Phase 4 (P4-5): recurring deposits.
--   1. Product type RD (family TERM_DEPOSIT): a monthly instalment (the amount limits are the instalment's) for a whole
--      number of months, interest on the monthly balance added at each rest (interest_period_months), a late charge per
--      Rs 100 of instalment per month or part of a month of delay (rd_late_charge_per_100; decision D-19), paid out at
--      maturity and never renewed.
--   2. A recurring deposit's terms: principal is the monthly instalment; the first instalment funds it (P4-2).
--   3. Movements RD_INSTALMENT (an instalment into the deposit) and RD_LATE_CHARGE (the late charge out of the paying
--      savings or current account).
--   4. deposits.rd_instalment: every instalment paid, in order, with its due date and late charge; never changed except
--      to record that the funding which paid instalment 1 was reversed. deposits.rd_problems() checks the instalments
--      against the deposit's movements.
--   5. Maker-checker: an instalment posts at once below 2,00,000, like a funding.
-- The day-end (DepositEod, "Recurring deposit interest (monthly)") works the interest out with the engine (RecurringDeposit).

-- ---------------------------------------------------------------------------------------------------------
-- 1. Product type RD
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.product DROP CONSTRAINT product_product_type_check, DROP CONSTRAINT product_cumulative_compounds;
ALTER TABLE deposits.product
  ADD COLUMN rd_late_charge_per_100 numeric(8,2) CHECK (rd_late_charge_per_100 >= 0 AND rd_late_charge_per_100 <= 100),
  ADD CONSTRAINT product_product_type_check CHECK (product_type IN ('SAVINGS','CURRENT','FD_CUMULATIVE','FD_PAYOUT','RD')),
  ADD CONSTRAINT product_cumulative_compounds CHECK (product_type NOT IN ('FD_CUMULATIVE','RD') OR interest_period_months > 0),
  ADD CONSTRAINT product_rd_settings CHECK ((product_type = 'RD') = (rd_late_charge_per_100 IS NOT NULL)
                                            AND (product_type <> 'RD' OR (min_tenure_days = 0 AND max_tenure_days = 0 AND NOT auto_renewal_allowed)));

-- A recurring deposit's rate table has its tenure steps in whole months (the application says so first, with the engine).
CREATE FUNCTION deposits.guard_rd_product() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.product_type = 'RD' AND NEW.status = 'ACTIVE'
     AND EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.rate_card) c WHERE coalesce((c->>'fromDays')::numeric, 0) <> 0) THEN
    RAISE EXCEPTION 'deposit product %: a recurring deposit''s rate table has tenure steps in whole months', NEW.code USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER product_rd_guard BEFORE INSERT OR UPDATE ON deposits.product FOR EACH ROW EXECUTE FUNCTION deposits.guard_rd_product();

-- ---------------------------------------------------------------------------------------------------------
-- 2. Terms of a recurring deposit
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION deposits.guard_term_deposit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; p deposits.product%ROWTYPE; pay deposits.account%ROWTYPE; card record; v numeric; maturity date; extra numeric; today date;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a deposit account is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    -- the terms frozen at booking never change; the funding, the payout and the instructions do (P4-2, P4-4)
    IF (to_jsonb(NEW) - 'start_date' - 'maturity_date' - 'payout_to' - 'payout_account_id' - 'maturity_instruction'
            - 'funding_source' - 'funding_account_id' - 'funding_movement_id' - 'funding_reference' - 'renewed_from'
            - 'payout_bank_name_cipher' - 'payout_bank_account_cipher' - 'payout_bank_account_last4' - 'payout_bank_ifsc')
       <> (to_jsonb(OLD) - 'start_date' - 'maturity_date' - 'payout_to' - 'payout_account_id' - 'maturity_instruction'
            - 'funding_source' - 'funding_account_id' - 'funding_movement_id' - 'funding_reference' - 'renewed_from'
            - 'payout_bank_name_cipher' - 'payout_bank_account_cipher' - 'payout_bank_account_last4' - 'payout_bank_ifsc') THEN
      RAISE EXCEPTION 'the terms of a term deposit are fixed at booking' USING ERRCODE = '42501';
    END IF;
    IF NEW.start_date IS NOT NULL AND NEW.maturity_date <> (NEW.start_date + make_interval(months => NEW.tenure_months))::date + NEW.tenure_days THEN
      RAISE EXCEPTION 'the maturity date is the start date plus the tenure' USING ERRCODE = '23514';
    END IF;
    IF OLD.start_date IS NOT NULL AND NEW.start_date IS NOT NULL AND NEW.start_date <> OLD.start_date THEN
      RAISE EXCEPTION 'the start date of a funded deposit never changes' USING ERRCODE = '42501';
    END IF;
    IF OLD.funding_source = 'RENEWAL' AND NEW.funding_source IS DISTINCT FROM 'RENEWAL' THEN
      RAISE EXCEPTION 'a deposit funded by a renewal keeps its funding' USING ERRCODE = '42501';
    END IF;
    IF OLD.start_date IS NULL AND NEW.start_date IS NOT NULL THEN
      -- funding (P4-2): interest starts on the business date, and a booking does not hold its rate for ever
      SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
      IF NEW.start_date <> today THEN
        RAISE EXCEPTION 'a deposit is funded on the business date (%), and its interest starts that day', today USING ERRCODE = '23514';
      END IF;
      SELECT value::int INTO v FROM platform.system_property WHERE key = 'deposits.funding-within-days';
      IF v IS NOT NULL AND today > NEW.booked_on + v::int THEN
        SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
        RAISE EXCEPTION 'deposit %: the booking of % has lapsed (a deposit is funded within % days of booking); book it again',
          a.account_no, NEW.booked_on, v::int USING ERRCODE = '23514';
      END IF;
    END IF;
    -- a deposit-taking NBFC renews only with the depositor's consent, recorded for this deposit (P4-4)
    IF NEW.maturity_instruction <> 'PAY_OUT' AND NEW.maturity_instruction IS DISTINCT FROM OLD.maturity_instruction
       AND coalesce(deposits.rule_value('td.renewal-needs-consent'), 0) = 1
       AND NOT EXISTS (SELECT 1 FROM deposits.renewal_consent c WHERE c.account_id = NEW.account_id AND c.status = 'ACTIVE'
                          AND c.instruction = NEW.maturity_instruction) THEN
      SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
      RAISE EXCEPTION 'deposit %: a renewal needs the depositor''s consent, recorded for this deposit', a.account_no USING ERRCODE = '23514';
    END IF;
    -- a recurring deposit is paid out at maturity, never renewed (P4-5)
    IF NEW.maturity_instruction <> 'PAY_OUT' AND NEW.maturity_instruction IS DISTINCT FROM OLD.maturity_instruction
       AND (SELECT pr.product_type FROM deposits.account x JOIN deposits.product pr ON pr.code = x.product_code WHERE x.id = NEW.account_id) = 'RD' THEN
      RAISE EXCEPTION 'a recurring deposit is paid out at maturity; it is not renewed' USING ERRCODE = '23514';
    END IF;
  ELSE
    SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
    IF a.family <> 'TERM_DEPOSIT' THEN
      RAISE EXCEPTION 'account % is not a term deposit', a.account_no USING ERRCODE = '23514';
    END IF;
    SELECT * INTO p FROM deposits.product WHERE code = a.product_code;
    NEW.booked_on := a.opened_on;
    IF NEW.start_date IS NOT NULL THEN
      RAISE EXCEPTION 'deposit %: interest starts when the deposit is funded, not at booking', a.account_no USING ERRCODE = '23514';
    END IF;
    maturity := (NEW.booked_on + make_interval(months => NEW.tenure_months))::date + NEW.tenure_days;
    -- the product's limits
    IF NEW.principal < p.min_amount OR (p.max_amount IS NOT NULL AND NEW.principal > p.max_amount) THEN
      RAISE EXCEPTION 'deposit %: the amount % is outside the limits of product %', a.account_no, deposits.plain(NEW.principal), p.code
        USING ERRCODE = '23514';
    END IF;
    IF maturity < (NEW.booked_on + make_interval(months => p.min_tenure_months))::date + p.min_tenure_days
       OR maturity > (NEW.booked_on + make_interval(months => p.max_tenure_months))::date + p.max_tenure_days THEN
      RAISE EXCEPTION 'deposit %: the tenure % is outside the limits of product %', a.account_no,
        deposits.tenure_text(NEW.tenure_months, NEW.tenure_days), p.code USING ERRCODE = '23514';
    END IF;
    -- the rule set, on the real dates
    v := deposits.rule_value('td.min-tenure-months', NEW.booked_on);
    IF v IS NOT NULL AND maturity < (NEW.booked_on + make_interval(months => v::int))::date THEN
      RAISE EXCEPTION 'deposit %: the tenure % is shorter than % months', a.account_no,
        deposits.tenure_text(NEW.tenure_months, NEW.tenure_days), v::int USING ERRCODE = '23514';
    END IF;
    v := deposits.rule_value('td.max-tenure-months', NEW.booked_on);
    IF v IS NOT NULL AND maturity > (NEW.booked_on + make_interval(months => v::int))::date THEN
      RAISE EXCEPTION 'deposit %: the tenure % is longer than % months', a.account_no,
        deposits.tenure_text(NEW.tenure_months, NEW.tenure_days), v::int USING ERRCODE = '23514';
    END IF;
    v := deposits.rule_value('td.max-rate-percent', NEW.booked_on);
    IF v IS NOT NULL AND NEW.rate_percent > v THEN
      RAISE EXCEPTION 'deposit %: the rate % is above the ceiling of %', a.account_no, deposits.plain(NEW.rate_percent) || '%',
        deposits.plain(v) || '%' USING ERRCODE = '23514';
    END IF;
    -- the rate is the product's card rate for this amount and tenure, plus add-ons the product has
    SELECT * INTO card FROM deposits.card_rate(p.rate_card, NEW.principal, NEW.booked_on, maturity);
    IF NOT FOUND OR card.rate_percent <> NEW.card_rate_percent OR card.from_amount <> NEW.card_from_amount
       OR card.from_months <> NEW.card_from_months OR card.from_days <> NEW.card_from_days THEN
      RAISE EXCEPTION 'deposit %: the card rate recorded is not the rate of product % for this amount and tenure', a.account_no, p.code
        USING ERRCODE = '23514';
    END IF;
    IF jsonb_typeof(NEW.add_ons) <> 'array'
       OR EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.add_ons) x
                   WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(p.add_ons) y
                                      WHERE y->>'code' = x->>'code' AND (y->>'percent')::numeric = (x->>'percent')::numeric))
       OR (SELECT count(*) <> count(DISTINCT x->>'code') FROM jsonb_array_elements(NEW.add_ons) x) THEN
      RAISE EXCEPTION 'deposit %: an add-on recorded is not an add-on of product %', a.account_no, p.code USING ERRCODE = '23514';
    END IF;
    SELECT coalesce(sum((x->>'percent')::numeric), 0) INTO extra FROM jsonb_array_elements(NEW.add_ons) x;
    IF NEW.rate_percent <> NEW.card_rate_percent + extra THEN
      RAISE EXCEPTION 'deposit %: the rate is the card rate plus the add-ons given', a.account_no USING ERRCODE = '23514';
    END IF;
    -- the product as it is today is frozen into the deposit
    IF NEW.interest_period_months <> p.interest_period_months OR NEW.cumulative <> (p.product_type IN ('FD_CUMULATIVE','RD'))
       OR NEW.day_count <> p.day_count OR NEW.rounding <> p.rounding OR NEW.tds_applicable <> p.tds_applicable
       OR NEW.premature_allowed <> p.premature_allowed
       OR NEW.premature_penalty_percent IS DISTINCT FROM p.premature_penalty_percent
       OR NEW.premature_min_days IS DISTINCT FROM p.premature_min_days THEN
      RAISE EXCEPTION 'deposit %: the terms recorded are not those of product % version %', a.account_no, p.code, p.version
        USING ERRCODE = '23514';
    END IF;
    IF p.product_type = 'RD' AND (NEW.tenure_days <> 0 OR NEW.maturity_instruction <> 'PAY_OUT') THEN
      RAISE EXCEPTION 'deposit %: a recurring deposit runs for whole months and is paid out at maturity', a.account_no USING ERRCODE = '23514';
    END IF;
    IF NEW.maturity_instruction <> 'PAY_OUT' AND NOT p.auto_renewal_allowed THEN
      RAISE EXCEPTION 'deposit %: product % does not allow renewal at maturity', a.account_no, p.code USING ERRCODE = '23514';
    END IF;
    -- NBFC Directions para 17: an instruction given at booking is not the depositor's consent to renew (P4-4)
    IF NEW.maturity_instruction <> 'PAY_OUT' AND coalesce(deposits.rule_value('td.renewal-needs-consent', NEW.booked_on), 0) = 1 THEN
      RAISE EXCEPTION 'deposit %: a renewal needs the depositor''s consent, recorded after booking; book the deposit to be paid out', a.account_no
        USING ERRCODE = '23514';
    END IF;
  END IF;
  IF NEW.payout_account_id IS NOT NULL THEN
    SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
    SELECT * INTO pay FROM deposits.account WHERE id = NEW.payout_account_id;
    IF pay.family <> 'CASA' OR (pay.status <> 'ACTIVE' AND (TG_OP = 'INSERT' OR NEW.payout_account_id IS DISTINCT FROM OLD.payout_account_id)) THEN
      RAISE EXCEPTION 'deposit %: interest and maturity proceeds are paid to an active savings or current account', a.account_no
        USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM deposits.account_holder h WHERE h.account_id = pay.id AND h.customer_id = a.customer_id) THEN
      RAISE EXCEPTION 'deposit %: account % is not held by the depositor', a.account_no, pay.account_no USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- 3. Movements
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.movement DROP CONSTRAINT movement_kind, DROP CONSTRAINT movement_direction;
ALTER TABLE deposits.movement
  ADD CONSTRAINT movement_kind CHECK (kind IN ('CASH_DEPOSIT','CASH_WITHDRAWAL','BANK_TRANSFER_IN','BANK_TRANSFER_OUT','TRANSFER_IN',
                                               'TRANSFER_OUT','FUNDING','FUNDING_PAID','REVERSAL','INTEREST_CREDIT','INTEREST_PAYOUT',
                                               'TAX_DEDUCTED','CHARGE','CLOSURE','INTEREST_RECOVERY','RENEWAL','RD_INSTALMENT','RD_LATE_CHARGE')),
  ADD CONSTRAINT movement_direction CHECK (kind = 'REVERSAL' OR direction = CASE
        WHEN kind IN ('CASH_DEPOSIT','BANK_TRANSFER_IN','TRANSFER_IN','FUNDING','INTEREST_CREDIT','RD_INSTALMENT') THEN 'CREDIT' ELSE 'DEBIT' END);

CREATE OR REPLACE FUNCTION deposits.guard_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; o deposits.movement%ROWTYPE; bal numeric; liens numeric; frz text; today date; ptype text; t deposits.term_deposit%ROWTYPE;
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
  ELSIF a.family = 'CASA' AND NEW.kind IN ('FUNDING','INTEREST_PAYOUT','INTEREST_RECOVERY','RENEWAL','RD_INSTALMENT') THEN
    RAISE EXCEPTION 'account % is not a term deposit', a.account_no USING ERRCODE = '23514';
  ELSIF a.family = 'TERM_DEPOSIT' AND NEW.kind NOT IN ('FUNDING','INTEREST_CREDIT','INTEREST_PAYOUT','BANK_TRANSFER_OUT','TAX_DEDUCTED',
                                                       'CLOSURE','INTEREST_RECOVERY','RENEWAL','RD_INSTALMENT') THEN
    RAISE EXCEPTION 'term deposit %: money moves by its funding, its instalments, its interest and the tax on it, and its closure or renewal only',
      a.account_no USING ERRCODE = '23514';
  END IF;
  -- a recurring deposit's instalments (P4-5): only to an active recurring deposit, before its maturity date
  IF NEW.kind = 'RD_INSTALMENT' THEN
    SELECT product_type INTO ptype FROM deposits.product WHERE code = a.product_code;
    SELECT * INTO t FROM deposits.term_deposit WHERE account_id = a.id;
    IF ptype <> 'RD' THEN
      RAISE EXCEPTION 'term deposit % is not a recurring deposit; it takes no instalments', a.account_no USING ERRCODE = '23514';
    END IF;
    IF a.status <> 'ACTIVE' OR t.maturity_date IS NULL OR today >= t.maturity_date THEN
      RAISE EXCEPTION 'recurring deposit %: instalments are paid while it is active and before its maturity date', a.account_no USING ERRCODE = '23514';
    END IF;
  END IF;
  -- the state of the account
  IF a.family = 'CASA' AND a.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'account % is %', a.account_no, a.status USING ERRCODE = '23514';
  END IF;
  IF a.family = 'TERM_DEPOSIT' AND NEW.kind = 'FUNDING' AND a.status <> 'BOOKED' THEN
    RAISE EXCEPTION 'term deposit % is %; only a booked deposit is funded', a.account_no, a.status USING ERRCODE = '23514';
  END IF;
  IF a.family = 'TERM_DEPOSIT' AND NEW.kind <> 'FUNDING' AND a.status NOT IN ('ACTIVE','MATURED') THEN
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
  -- a closure or a renewal takes the whole balance (P4-4)
  IF NEW.kind IN ('CLOSURE','RENEWAL') AND bal <> 0 THEN
    RAISE EXCEPTION 'account %: a % takes the whole balance; % would be left', a.account_no, lower(NEW.kind), deposits.plain(bal)
      USING ERRCODE = '23514';
  END IF;
  UPDATE deposits.account_balance SET balance = bal, updated_at = now() WHERE account_id = a.id;
  NEW.balance_after := bal;
  RETURN NEW;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- 4. Instalments
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.rd_instalment (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     uuid NOT NULL REFERENCES deposits.account(id),
    instalment_no  int NOT NULL CHECK (instalment_no >= 1),
    due_date       date NOT NULL,
    amount         platform.money NOT NULL CHECK (amount > 0),
    late_charge    platform.money NOT NULL DEFAULT 0 CHECK (late_charge >= 0),
    paid_on        date NOT NULL,
    source         text NOT NULL CHECK (source IN ('ACCOUNT','CASH','BANK_TRANSFER','GATEWAY','STANDING_INSTRUCTION')),
    movement_id    uuid NOT NULL UNIQUE REFERENCES deposits.movement(id),   -- the credit to the deposit (FUNDING for the first)
    lot_id         uuid NOT NULL REFERENCES ledger.transaction_lot(id),
    reversed       boolean NOT NULL DEFAULT false,
    approval_id    uuid REFERENCES platform.approval_request(id),
    created_by     text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX rd_instalment_once ON deposits.rd_instalment (account_id, instalment_no) WHERE NOT reversed;
CREATE INDEX rd_instalment_account ON deposits.rd_instalment (account_id, instalment_no);

CREATE FUNCTION deposits.guard_rd_instalment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; t deposits.term_deposit%ROWTYPE; m deposits.movement%ROWTYPE; ptype text; paid int; today date;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'an instalment is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    -- the funding that paid instalment 1 was reversed (P4-2): the instalment no longer counts
    IF NOT OLD.reversed AND NEW.reversed AND (to_jsonb(NEW) - 'reversed') = (to_jsonb(OLD) - 'reversed')
       AND EXISTS (SELECT 1 FROM deposits.movement x WHERE x.id = NEW.movement_id AND x.reversed_by IS NOT NULL) THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an instalment never changes, except to record that its movement was reversed' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id FOR UPDATE;
  SELECT product_type INTO ptype FROM deposits.product WHERE code = a.product_code;
  SELECT * INTO t FROM deposits.term_deposit WHERE account_id = NEW.account_id;
  IF ptype IS DISTINCT FROM 'RD' THEN
    RAISE EXCEPTION 'account % is not a recurring deposit', a.account_no USING ERRCODE = '23514';
  END IF;
  SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
  IF NEW.paid_on <> today THEN RAISE EXCEPTION 'an instalment is paid on the business date (%)', today USING ERRCODE = '23514'; END IF;
  SELECT count(*) INTO paid FROM deposits.rd_instalment WHERE account_id = NEW.account_id AND NOT reversed;
  IF NEW.instalment_no <> paid + 1 THEN
    RAISE EXCEPTION 'recurring deposit %: instalments are paid in order; the next is %', a.account_no, paid + 1 USING ERRCODE = '23514';
  END IF;
  IF NEW.instalment_no > t.tenure_months THEN
    RAISE EXCEPTION 'recurring deposit %: all % instalments are paid', a.account_no, t.tenure_months USING ERRCODE = '23514';
  END IF;
  IF NEW.amount <> t.principal THEN
    RAISE EXCEPTION 'recurring deposit %: the instalment is %', a.account_no, deposits.plain(t.principal) USING ERRCODE = '23514';
  END IF;
  IF t.start_date IS NULL OR NEW.due_date <> (t.start_date + make_interval(months => NEW.instalment_no - 1))::date THEN
    RAISE EXCEPTION 'recurring deposit %: instalment % falls due % months after the start date', a.account_no, NEW.instalment_no, NEW.instalment_no - 1
      USING ERRCODE = '23514';
  END IF;
  SELECT * INTO m FROM deposits.movement WHERE id = NEW.movement_id;
  IF m.account_id <> NEW.account_id OR m.amount <> NEW.amount OR m.lot_id <> NEW.lot_id
     OR m.kind <> (CASE WHEN NEW.instalment_no = 1 THEN 'FUNDING' ELSE 'RD_INSTALMENT' END) THEN
    RAISE EXCEPTION 'recurring deposit %: an instalment is recorded with the movement that paid it in', a.account_no USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER rd_instalment_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.rd_instalment
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_rd_instalment();
CREATE TRIGGER rd_instalment_no_truncate BEFORE TRUNCATE ON deposits.rd_instalment FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- Every instalment movement, and the funding of a recurring deposit, is recorded as an instalment by COMMIT.
CREATE FUNCTION deposits.check_rd_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE;
BEGIN
  IF NEW.kind NOT IN ('RD_INSTALMENT','FUNDING') THEN RETURN NULL; END IF;
  SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
  IF (SELECT product_type FROM deposits.product WHERE code = a.product_code) <> 'RD' THEN RETURN NULL; END IF;
  IF NOT EXISTS (SELECT 1 FROM deposits.rd_instalment WHERE movement_id = NEW.id) THEN
    RAISE EXCEPTION 'recurring deposit %: the % of % is not recorded as an instalment', a.account_no, lower(NEW.kind), deposits.plain(NEW.amount)
      USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER movement_rd_instalment AFTER INSERT ON deposits.movement
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_rd_movement();

-- Recurring deposits whose instalments do not add up to what their instalment movements brought in (an SQL invariant).
CREATE FUNCTION deposits.rd_problems() RETURNS TABLE (account_no text, instalments numeric, moved numeric) LANGUAGE sql STABLE AS $$
  SELECT a.account_no, coalesce(i.total, 0), coalesce(m.total, 0)
    FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code AND p.product_type = 'RD'
    LEFT JOIN LATERAL (SELECT sum(x.amount) AS total FROM deposits.rd_instalment x WHERE x.account_id = a.id AND NOT x.reversed) i ON true
    LEFT JOIN LATERAL (SELECT sum(x.amount) AS total FROM deposits.movement x
                        WHERE x.account_id = a.id AND x.kind IN ('FUNDING','RD_INSTALMENT') AND x.reversed_by IS NULL) m ON true
   WHERE coalesce(i.total, 0) <> coalesce(m.total, 0)
$$;

-- ---------------------------------------------------------------------------------------------------------
-- 5. Maker-checker
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('TERM_DEPOSIT', 'INSTALMENT', NULL, 0),
  ('TERM_DEPOSIT', 'INSTALMENT', 200000, 1)
ON CONFLICT DO NOTHING;
