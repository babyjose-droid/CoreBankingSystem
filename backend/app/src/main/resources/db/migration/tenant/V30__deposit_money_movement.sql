-- Phase 4 (P4-2): money movement on deposit accounts.
--   1. Settings: the cash and bank heads deposits settle against, and how long a booking waits for its funds.
--   2. deposits.movement: every amount into or out of a deposit account, one row per account, each tied to its
--      ledger lot; deposits.account_balance: the balance of every account, kept by the movements and checked against
--      the ledger (deposits.balance_problems).
--   3. deposits.account_hold: freezes (debit, credit, total) and liens.
--   4. Funding a term deposit: how it was funded, and the bank account its proceeds go to (open item P4-07).
--   5. Maker-checker rules and amount-limit types for the new requests.
-- The application checks the same rules first, with the deposit engine (AccountHolds), to answer 422 with every
-- reason; these triggers are what holds whatever the application does.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Settings
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('deposits.gl.cash', '1201', 'migration'),          -- cash in hand
  ('deposits.gl.bank-in', '1203', 'migration'),       -- bank account money from other banks arrives in
  ('deposits.gl.bank-out', '1202', 'migration'),      -- bank account money to other banks leaves from
  ('deposits.funding-within-days', '30', 'migration') -- a term deposit booking lapses if not funded within this
ON CONFLICT (key) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- 2. Movements and balances
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.account_balance (
    account_id uuid PRIMARY KEY REFERENCES deposits.account(id),
    balance    platform.money NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE deposits.movement (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seq              bigint GENERATED ALWAYS AS IDENTITY UNIQUE,   -- the order movements were made in
    account_id       uuid NOT NULL REFERENCES deposits.account(id),
    kind             text NOT NULL CHECK (kind IN ('CASH_DEPOSIT','CASH_WITHDRAWAL','BANK_TRANSFER_IN','BANK_TRANSFER_OUT',
                                                   'TRANSFER_IN','TRANSFER_OUT','FUNDING','FUNDING_PAID','REVERSAL')),
    direction        text NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount           platform.money NOT NULL CHECK (amount > 0 AND amount = round(amount, 2)),
    balance_after    platform.money NOT NULL,                      -- set here, not by the caller
    business_date    date NOT NULL,
    value_date       date NOT NULL,
    lot_id           uuid NOT NULL REFERENCES ledger.transaction_lot(id),
    reference        text CHECK (reference IS NULL OR length(reference) <= 60),   -- the bank's UTR, a slip number
    narration        text CHECK (narration IS NULL OR length(narration) <= 200),
    counterparty_id  uuid REFERENCES deposits.account(id),         -- the other account of a transfer or a funding
    party_name_cipher    bytea,                                     -- the other bank's account holder (bank transfers)
    party_account_cipher bytea,
    party_account_last4  text CHECK (party_account_last4 IS NULL OR party_account_last4 ~ '^[A-Za-z0-9]{1,4}$'),
    party_ifsc           text CHECK (party_ifsc IS NULL OR party_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
    reverses         uuid UNIQUE REFERENCES deposits.movement(id),
    reversed_by      uuid UNIQUE REFERENCES deposits.movement(id),
    reversal_reason  text CHECK (reversal_reason IS NULL OR (btrim(reversal_reason) <> '' AND length(reversal_reason) <= 500)),
    approval_id      uuid REFERENCES platform.approval_request(id),
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT movement_reversal CHECK ((kind = 'REVERSAL') = (reverses IS NOT NULL)),
    CONSTRAINT movement_reversal_reason CHECK ((kind = 'REVERSAL') = (reversal_reason IS NOT NULL)),
    CONSTRAINT movement_direction CHECK (kind = 'REVERSAL' OR direction = CASE
        WHEN kind IN ('CASH_DEPOSIT','BANK_TRANSFER_IN','TRANSFER_IN','FUNDING') THEN 'CREDIT' ELSE 'DEBIT' END),
    CONSTRAINT movement_counterparty CHECK ((kind IN ('TRANSFER_IN','TRANSFER_OUT','FUNDING_PAID')) <= (counterparty_id IS NOT NULL)),
    CONSTRAINT movement_party CHECK ((party_account_cipher IS NULL) = (party_ifsc IS NULL)
                                     AND (party_account_cipher IS NULL) = (party_account_last4 IS NULL)
                                     AND (party_account_cipher IS NULL OR kind IN ('BANK_TRANSFER_IN','BANK_TRANSFER_OUT')))
);
CREATE INDEX movement_account ON deposits.movement (account_id, seq);
CREATE INDEX movement_lot ON deposits.movement (lot_id);

-- An account is changed by one movement at a time (the account row is locked), so the balance after each is exact.
CREATE FUNCTION deposits.guard_movement() RETURNS trigger LANGUAGE plpgsql AS $$
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
  ELSIF a.family = 'CASA' AND NEW.kind = 'FUNDING' THEN
    RAISE EXCEPTION 'account % is not a term deposit', a.account_no USING ERRCODE = '23514';
  ELSIF a.family = 'TERM_DEPOSIT' AND NEW.kind <> 'FUNDING' THEN
    RAISE EXCEPTION 'term deposit %: money moves in by its funding only (withdrawal and closure come with maturity processing)',
      a.account_no USING ERRCODE = '23514';
  END IF;
  -- the state of the account
  IF a.family = 'CASA' AND a.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'account % is %', a.account_no, a.status USING ERRCODE = '23514';
  END IF;
  IF a.family = 'TERM_DEPOSIT' AND NEW.kind = 'FUNDING' AND a.status <> 'BOOKED' THEN
    RAISE EXCEPTION 'term deposit % is %; only a booked deposit is funded', a.account_no, a.status USING ERRCODE = '23514';
  END IF;
  IF a.family = 'TERM_DEPOSIT' AND NEW.kind = 'REVERSAL' AND a.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'term deposit % is %', a.account_no, a.status USING ERRCODE = '23514';
  END IF;
  -- freezes stop the customer's movements, not corrections
  IF NEW.kind <> 'REVERSAL' THEN
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
CREATE TRIGGER movement_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.movement
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_movement();
CREATE TRIGGER movement_no_truncate BEFORE TRUNCATE ON deposits.movement FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

CREATE FUNCTION deposits.guard_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a deposit balance is never deleted' USING ERRCODE = '42501'; END IF;
  -- only the movement trigger writes a balance (it runs inside the same statement); anything else is refused
  IF pg_trigger_depth() < 2 THEN
    RAISE EXCEPTION 'a deposit balance changes only by a movement' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER balance_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.account_balance
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_balance();

-- The movements of an account in a lot equal what the lot posts to the account on its product's liability head,
-- checked at COMMIT (the lot and the movements are written in one transaction, in either order).
CREATE FUNCTION deposits.check_movement_lot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; gl text; moved numeric; posted numeric;
BEGIN
  SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
  SELECT gl_liability INTO gl FROM deposits.product WHERE code = a.product_code;
  SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) INTO moved
    FROM deposits.movement WHERE lot_id = NEW.lot_id AND account_id = NEW.account_id;
  SELECT coalesce(sum(CASE side WHEN 'CR' THEN amount ELSE -amount END), 0) INTO posted
    FROM ledger.account_entry WHERE lot_id = NEW.lot_id AND business_date = NEW.business_date AND account_no = a.account_no;
  IF NOT EXISTS (SELECT 1 FROM ledger.account_entry WHERE lot_id = NEW.lot_id AND business_date = NEW.business_date
                    AND account_no = a.account_no AND gl_code = gl)
     OR EXISTS (SELECT 1 FROM ledger.account_entry WHERE lot_id = NEW.lot_id AND business_date = NEW.business_date
                    AND account_no = a.account_no AND gl_code <> gl) THEN
    RAISE EXCEPTION 'account %: the lot does not post to the account on head % of product %', a.account_no, gl, a.product_code
      USING ERRCODE = '23514';
  END IF;
  IF moved <> posted THEN
    RAISE EXCEPTION 'account %: the movements (%) are not what the lot posts to the account (%)', a.account_no,
      deposits.plain(moved), deposits.plain(posted) USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER movement_matches_lot AFTER INSERT ON deposits.movement
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_movement_lot();

-- Every deposit account whose balance is not what the ledger says: the balance kept here against the sum of its
-- entries on its product's liability head. Empty when the books agree (an SQL invariant; the day-end will check it).
CREATE FUNCTION deposits.balance_problems() RETURNS TABLE (account_no text, kept numeric, ledger numeric) LANGUAGE sql STABLE AS $$
  SELECT a.account_no, coalesce(b.balance, 0), coalesce(e.net, 0)
    FROM deposits.account a
    JOIN deposits.product p ON p.code = a.product_code
    LEFT JOIN deposits.account_balance b ON b.account_id = a.id
    LEFT JOIN LATERAL (SELECT sum(CASE side WHEN 'CR' THEN amount ELSE -amount END) AS net
                         FROM ledger.account_entry x WHERE x.account_no = a.account_no AND x.gl_code = p.gl_liability) e ON true
   WHERE coalesce(b.balance, 0) <> coalesce(e.net, 0)
$$;

-- ---------------------------------------------------------------------------------------------------------
-- 3. Freezes and liens
-- A freeze has no amount and stops debits, credits or both; a lien holds an amount of the balance. A term deposit
-- takes liens only, once funded. Released, never deleted.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.account_hold (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          uuid NOT NULL REFERENCES deposits.account(id),
    kind                text NOT NULL CHECK (kind IN ('DEBIT_FREEZE','CREDIT_FREEZE','TOTAL_FREEZE','LIEN')),
    amount              platform.money CHECK (amount IS NULL OR (amount > 0 AND amount = round(amount, 2))),
    reason              text NOT NULL CHECK (btrim(reason) <> '' AND length(reason) <= 500),
    reference           text CHECK (reference IS NULL OR length(reference) <= 100),   -- the order, the loan it secures
    status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED')),
    placed_on           date NOT NULL,
    placed_by           text NOT NULL,
    approval_id         uuid REFERENCES platform.approval_request(id),
    released_on         date,
    released_by         text,
    release_reason      text CHECK (release_reason IS NULL OR (btrim(release_reason) <> '' AND length(release_reason) <= 500)),
    release_approval_id uuid REFERENCES platform.approval_request(id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT hold_amount_for_a_lien CHECK ((kind = 'LIEN') = (amount IS NOT NULL)),
    CONSTRAINT hold_released CHECK ((status = 'RELEASED') = (released_on IS NOT NULL)
                                    AND (status = 'RELEASED') = (released_by IS NOT NULL)
                                    AND (status = 'RELEASED') = (release_reason IS NOT NULL))
);
CREATE INDEX account_hold_account ON deposits.account_hold (account_id) WHERE status = 'ACTIVE';
-- one freeze of a kind at a time: releasing one of two would leave the account frozen
CREATE UNIQUE INDEX account_hold_one_freeze ON deposits.account_hold (account_id, kind) WHERE status = 'ACTIVE' AND kind <> 'LIEN';

CREATE FUNCTION deposits.guard_hold() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; bal numeric; liens numeric;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a freeze or lien is never deleted; release it' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'ACTIVE' AND NEW.status = 'RELEASED'
       AND (to_jsonb(NEW) - 'status' - 'released_on' - 'released_by' - 'release_reason' - 'release_approval_id')
         = (to_jsonb(OLD) - 'status' - 'released_on' - 'released_by' - 'release_reason' - 'release_approval_id') THEN
      SELECT business_date INTO NEW.released_on FROM platform.business_day WHERE id = 1;
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a freeze or lien never changes, except to be released' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'deposit account % not found', NEW.account_id USING ERRCODE = 'P0002'; END IF;
  IF NEW.status <> 'ACTIVE' THEN RAISE EXCEPTION 'a new hold is active' USING ERRCODE = '23514'; END IF;
  IF a.family = 'TERM_DEPOSIT' AND NEW.kind <> 'LIEN' THEN
    RAISE EXCEPTION 'term deposit %: a term deposit takes a lien, not a freeze', a.account_no USING ERRCODE = '23514';
  END IF;
  IF a.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'account % is %; a hold is placed on an active account%', a.account_no, a.status,
      CASE WHEN a.status = 'BOOKED' THEN ' (fund the deposit first)' ELSE '' END USING ERRCODE = '23514';
  END IF;
  SELECT business_date INTO NEW.placed_on FROM platform.business_day WHERE id = 1;
  IF NEW.kind = 'LIEN' THEN
    SELECT coalesce(max(balance), 0) INTO bal FROM deposits.account_balance WHERE account_id = a.id;
    SELECT coalesce(sum(amount), 0) INTO liens FROM deposits.account_hold WHERE account_id = a.id AND status = 'ACTIVE' AND kind = 'LIEN';
    IF NEW.amount > bal - liens THEN
      RAISE EXCEPTION 'account %: a lien of % is more than the available balance of %', a.account_no, deposits.plain(NEW.amount),
        deposits.plain(greatest(bal - liens, 0)) USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER hold_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.account_hold
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_hold();
CREATE TRIGGER hold_no_truncate BEFORE TRUNCATE ON deposits.account_hold FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- ---------------------------------------------------------------------------------------------------------
-- 4. Funding a term deposit
-- A booked deposit is funded with its principal on the business date, from a savings or current account of the
-- depositor, in cash or by a bank transfer; interest starts that day. The reversal of the funding (same day or
-- later, while nothing else has happened to the deposit) takes it back to BOOKED.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.term_deposit
  ADD COLUMN funding_source             text CHECK (funding_source IN ('ACCOUNT','CASH','BANK_TRANSFER')),
  ADD COLUMN funding_account_id         uuid REFERENCES deposits.account(id),
  ADD COLUMN funding_movement_id        uuid REFERENCES deposits.movement(id),
  ADD COLUMN funding_reference          text CHECK (funding_reference IS NULL OR length(funding_reference) <= 60),
  ADD COLUMN payout_bank_name_cipher    bytea,
  ADD COLUMN payout_bank_account_cipher bytea,
  ADD COLUMN payout_bank_account_last4  text CHECK (payout_bank_account_last4 IS NULL OR payout_bank_account_last4 ~ '^[A-Za-z0-9]{1,4}$'),
  ADD COLUMN payout_bank_ifsc           text CHECK (payout_bank_ifsc IS NULL OR payout_bank_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
  ADD CONSTRAINT term_deposit_funding CHECK ((start_date IS NULL) = (funding_source IS NULL)
                                             AND (start_date IS NULL) = (funding_movement_id IS NULL)
                                             AND (funding_source IS NOT DISTINCT FROM 'ACCOUNT') = (funding_account_id IS NOT NULL)),
  ADD CONSTRAINT term_deposit_payout_bank CHECK ((payout_bank_account_cipher IS NULL) = (payout_bank_ifsc IS NULL)
                                                 AND (payout_bank_account_cipher IS NULL) = (payout_bank_name_cipher IS NULL)
                                                 AND (payout_bank_account_cipher IS NULL) = (payout_bank_account_last4 IS NULL)),
  -- the bank account is recorded when the deposit is funded (it was not captured at booking in P4-1)
  ADD CONSTRAINT term_deposit_payout_bank_when_funded CHECK (payout_to <> 'BANK_ACCOUNT' OR start_date IS NULL OR payout_bank_ifsc IS NOT NULL),
  ADD CONSTRAINT term_deposit_payout_bank_only_for_bank CHECK (payout_to = 'BANK_ACCOUNT' OR payout_bank_ifsc IS NULL);

CREATE OR REPLACE FUNCTION deposits.guard_term_deposit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; p deposits.product%ROWTYPE; pay deposits.account%ROWTYPE; card record; v numeric; maturity date; extra numeric; today date;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a deposit account is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    -- the terms frozen at booking never change; the funding, the payout and the instructions do (P4-2, P4-4)
    IF (to_jsonb(NEW) - 'start_date' - 'maturity_date' - 'payout_to' - 'payout_account_id' - 'maturity_instruction'
            - 'funding_source' - 'funding_account_id' - 'funding_movement_id' - 'funding_reference'
            - 'payout_bank_name_cipher' - 'payout_bank_account_cipher' - 'payout_bank_account_last4' - 'payout_bank_ifsc')
       <> (to_jsonb(OLD) - 'start_date' - 'maturity_date' - 'payout_to' - 'payout_account_id' - 'maturity_instruction'
            - 'funding_source' - 'funding_account_id' - 'funding_movement_id' - 'funding_reference'
            - 'payout_bank_name_cipher' - 'payout_bank_account_cipher' - 'payout_bank_account_last4' - 'payout_bank_ifsc') THEN
      RAISE EXCEPTION 'the terms of a term deposit are fixed at booking' USING ERRCODE = '42501';
    END IF;
    IF NEW.start_date IS NOT NULL AND NEW.maturity_date <> (NEW.start_date + make_interval(months => NEW.tenure_months))::date + NEW.tenure_days THEN
      RAISE EXCEPTION 'the maturity date is the start date plus the tenure' USING ERRCODE = '23514';
    END IF;
    IF OLD.start_date IS NOT NULL AND NEW.start_date IS NOT NULL AND NEW.start_date <> OLD.start_date THEN
      RAISE EXCEPTION 'the start date of a funded deposit never changes' USING ERRCODE = '42501';
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
    IF NEW.interest_period_months <> p.interest_period_months OR NEW.cumulative <> (p.product_type = 'FD_CUMULATIVE')
       OR NEW.day_count <> p.day_count OR NEW.rounding <> p.rounding OR NEW.tds_applicable <> p.tds_applicable
       OR NEW.premature_allowed <> p.premature_allowed
       OR NEW.premature_penalty_percent IS DISTINCT FROM p.premature_penalty_percent
       OR NEW.premature_min_days IS DISTINCT FROM p.premature_min_days THEN
      RAISE EXCEPTION 'deposit %: the terms recorded are not those of product % version %', a.account_no, p.code, p.version
        USING ERRCODE = '23514';
    END IF;
    IF NEW.maturity_instruction <> 'PAY_OUT' AND NOT p.auto_renewal_allowed THEN
      RAISE EXCEPTION 'deposit %: product % does not allow renewal at maturity', a.account_no, p.code USING ERRCODE = '23514';
    END IF;
  END IF;
  IF NEW.payout_account_id IS NOT NULL THEN
    SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
    SELECT * INTO pay FROM deposits.account WHERE id = NEW.payout_account_id;
    IF pay.family <> 'CASA' OR pay.status <> 'ACTIVE' THEN
      RAISE EXCEPTION 'deposit %: interest and maturity proceeds are paid to an active savings or current account', a.account_no
        USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM deposits.account_holder h WHERE h.account_id = pay.id AND h.customer_id = a.customer_id) THEN
      RAISE EXCEPTION 'deposit %: account % is not held by the depositor', a.account_no, pay.account_no USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

-- A term deposit is ACTIVE exactly while it is funded, and then holds its principal; a booked deposit holds nothing.
-- The funding account is the depositor's own. Checked at COMMIT: the funding writes the movement, the deposit and
-- the account in one transaction.
CREATE FUNCTION deposits.check_funding(p_account uuid) RETURNS void LANGUAGE plpgsql AS $$
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
  IF bal <> t.principal THEN
    RAISE EXCEPTION 'term deposit %: a funded deposit holds its principal', a.account_no USING ERRCODE = '23514';
  END IF;
  IF t.funding_account_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM deposits.account_holder h WHERE h.account_id = t.funding_account_id AND h.customer_id = a.customer_id) THEN
    RAISE EXCEPTION 'term deposit %: it is funded from an account the depositor holds', a.account_no USING ERRCODE = '23514';
  END IF;
END $$;

CREATE FUNCTION deposits.check_funding_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME = 'account' THEN
    PERFORM deposits.check_funding(NEW.id);
  ELSE
    PERFORM deposits.check_funding(NEW.account_id);
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER account_funded AFTER UPDATE ON deposits.account
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_funding_trigger();
CREATE CONSTRAINT TRIGGER term_deposit_funded AFTER UPDATE ON deposits.term_deposit
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_funding_trigger();
CREATE CONSTRAINT TRIGGER movement_funded AFTER INSERT ON deposits.movement
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_funding_trigger();

-- ---------------------------------------------------------------------------------------------------------
-- 5. Maker-checker and amount limits
-- Money in and out posts at once below the threshold and needs a checker from it upward; a reversal, a freeze, a
-- lien and the reversal of a funding always need one. The threshold (2,00,000) is a starting value for the tenant
-- to change, not a regulatory figure. The role amount limits of the two new types apply on top.
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('DEPOSIT_TXN', '*', NULL, 0),
  ('DEPOSIT_TXN', '*', 200000, 1),
  ('DEPOSIT_TXN', 'REVERSE', NULL, 1),
  ('DEPOSIT_HOLD', '*', NULL, 1),
  ('TERM_DEPOSIT', 'FUND', NULL, 0),
  ('TERM_DEPOSIT', 'FUND', 200000, 1),
  ('TERM_DEPOSIT', 'REVERSE_FUNDING', NULL, 1)
ON CONFLICT DO NOTHING;

DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'platform.amount_limit'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%LOAN_PRECLOSURE%' LOOP
    EXECUTE format('ALTER TABLE platform.amount_limit DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE platform.amount_limit ADD CONSTRAINT amount_limit_txn_type_check
  CHECK (txn_type IN ('LOAN_DISBURSEMENT','LOAN_REPAYMENT','LOAN_WAIVER','VOUCHER','LOAN_PRECLOSURE','FEE_WAIVER',
                      'DEPOSIT_CREDIT','DEPOSIT_DEBIT'));
