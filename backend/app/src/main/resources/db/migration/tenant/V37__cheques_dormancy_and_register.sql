-- Phase 4 (P4-6): cheques, inoperative and unclaimed accounts, and the deposit-taking NBFC's net owned fund.
--   1. Settings: the clearing heads (2409 inward, 1207 outward, loaded with a bank's deposit heads in V28) and the
--      unclaimed deposits head (2407); how long a cheque stays good (3 months).
--   2. Cheques on a savings or current account with the cheque facility: deposits.cheque_book and deposits.cheque_leaf
--      (issued, stopped, paid); inward clearing (deposits.cheque_presentment: a cheque of ours presented by another
--      bank, paid or returned with the reason); outward clearing (deposits.cheque_deposit: another bank's cheque paid
--      in by our customer, credited once it clears). Movements CHEQUE_PAID and CHEQUE_DEPOSIT.
--   3. Inoperative and unclaimed: deposits.casa_account.last_customer_txn_on (kept by the movement guard),
--      inoperative_since; an inoperative account takes credits but no debit made by or for the customer, and no
--      charge. deposits.unclaimed: a balance transferred to the unclaimed deposits head (for the DEA Fund) and its
--      refund to a claimant. Movements UNCLAIMED_TRANSFER and UNCLAIMED_REFUND.
--   4. deposits.net_owned_fund: a deposit-taking NBFC's net owned fund as of an audited balance sheet, recorded
--      through a checker; the application holds public deposits to 1.5 times it (rule td.deposits-to-nof-multiple).
--   5. Maker-checker rules: a stop payment takes effect at once; its revocation, a cheque book, a reactivation and a
--      net owned fund wait for a checker (the default rule).

-- ---------------------------------------------------------------------------------------------------------
-- 1. Settings
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('deposits.gl.inward-clearing', '2409', 'migration'),
  ('deposits.gl.outward-clearing', '1207', 'migration'),
  ('deposits.gl.unclaimed', '2407', 'migration'),
  ('deposits.cheque-valid-months', '3', 'migration')        -- a cheque older than this is stale (RBI, 1-Apr-2012)
ON CONFLICT (key) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- 2. Movements
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.movement DROP CONSTRAINT movement_kind, DROP CONSTRAINT movement_direction;
ALTER TABLE deposits.movement
  ADD CONSTRAINT movement_kind CHECK (kind IN ('CASH_DEPOSIT','CASH_WITHDRAWAL','BANK_TRANSFER_IN','BANK_TRANSFER_OUT','TRANSFER_IN',
                                               'TRANSFER_OUT','FUNDING','FUNDING_PAID','REVERSAL','INTEREST_CREDIT','INTEREST_PAYOUT',
                                               'TAX_DEDUCTED','CHARGE','CLOSURE','INTEREST_RECOVERY','RENEWAL','RD_INSTALMENT','RD_LATE_CHARGE',
                                               'LOAN_REPAYMENT','CHEQUE_PAID','CHEQUE_DEPOSIT','UNCLAIMED_TRANSFER','UNCLAIMED_REFUND')),
  ADD CONSTRAINT movement_direction CHECK (kind = 'REVERSAL' OR direction = CASE
        WHEN kind IN ('CASH_DEPOSIT','BANK_TRANSFER_IN','TRANSFER_IN','FUNDING','INTEREST_CREDIT','RD_INSTALMENT','CHEQUE_DEPOSIT','UNCLAIMED_REFUND')
        THEN 'CREDIT' ELSE 'DEBIT' END);

ALTER TABLE deposits.casa_account
  ADD COLUMN last_customer_txn_on date,      -- the last movement made by or for the customer (not interest, tax or charges)
  ADD COLUMN inoperative_since    date;
UPDATE deposits.casa_account c SET last_customer_txn_on = (
  SELECT max(m.business_date) FROM deposits.movement m
   WHERE m.account_id = c.account_id AND m.kind NOT IN ('INTEREST_CREDIT','TAX_DEDUCTED','CHARGE','REVERSAL','RD_LATE_CHARGE','CLOSURE'));

-- A movement made by or for the customer: what keeps an account operative (RBI, inoperative accounts, 1-Jan-2024).
CREATE FUNCTION deposits.customer_induced(p_kind text) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
  SELECT p_kind NOT IN ('INTEREST_CREDIT','INTEREST_PAYOUT','TAX_DEDUCTED','CHARGE','REVERSAL','RD_LATE_CHARGE','CLOSURE','RENEWAL',
                        'INTEREST_RECOVERY','UNCLAIMED_TRANSFER','UNCLAIMED_REFUND') $$;

CREATE TABLE deposits.unclaimed (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id         uuid NOT NULL REFERENCES deposits.account(id),
    transferred_on     date NOT NULL,
    amount             platform.money NOT NULL CHECK (amount > 0),
    movement_id        uuid NOT NULL UNIQUE REFERENCES deposits.movement(id),
    last_operated_on   date NOT NULL,                  -- the last customer movement (or the maturity date of a deposit)
    refunded_on        date,
    refund_movement_id uuid UNIQUE REFERENCES deposits.movement(id),
    refund_reference   text CHECK (refund_reference IS NULL OR length(refund_reference) <= 100),
    refund_approval_id uuid REFERENCES platform.approval_request(id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT unclaimed_refund CHECK ((refunded_on IS NULL) = (refund_movement_id IS NULL))
);
CREATE UNIQUE INDEX unclaimed_one_open ON deposits.unclaimed (account_id) WHERE refunded_on IS NULL;

CREATE FUNCTION deposits.guard_unclaimed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m deposits.movement%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'an unclaimed transfer is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.refunded_on IS NULL AND NEW.refunded_on IS NOT NULL
       AND (to_jsonb(NEW) - 'refunded_on' - 'refund_movement_id' - 'refund_reference' - 'refund_approval_id')
         = (to_jsonb(OLD) - 'refunded_on' - 'refund_movement_id' - 'refund_reference' - 'refund_approval_id') THEN
      SELECT * INTO m FROM deposits.movement WHERE id = NEW.refund_movement_id;
      IF m.account_id <> NEW.account_id OR m.kind <> 'UNCLAIMED_REFUND' OR m.amount <> NEW.amount THEN
        RAISE EXCEPTION 'an unclaimed balance is refunded whole, to the account it came from' USING ERRCODE = '23514';
      END IF;
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an unclaimed transfer never changes, except to record its refund' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO m FROM deposits.movement WHERE id = NEW.movement_id;
  IF m.account_id <> NEW.account_id OR m.kind <> 'UNCLAIMED_TRANSFER' OR m.amount <> NEW.amount THEN
    RAISE EXCEPTION 'an unclaimed transfer is the UNCLAIMED_TRANSFER of its account, for its amount' USING ERRCODE = '23514';
  END IF;
  IF NEW.refunded_on IS NOT NULL THEN RAISE EXCEPTION 'a new unclaimed transfer is not yet refunded' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER unclaimed_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.unclaimed FOR EACH ROW EXECUTE FUNCTION deposits.guard_unclaimed();
CREATE TRIGGER unclaimed_no_truncate BEFORE TRUNCATE ON deposits.unclaimed FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- The movement guard of V35 with P4-6: cheques, inoperative accounts and unclaimed balances.
CREATE OR REPLACE FUNCTION deposits.guard_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; o deposits.movement%ROWTYPE; bal numeric; liens numeric; frz text; today date; ptype text; t deposits.term_deposit%ROWTYPE;
        c deposits.casa_account%ROWTYPE; open_unclaimed boolean;
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
                                                       'CLOSURE','INTEREST_RECOVERY','RENEWAL','RD_INSTALMENT','UNCLAIMED_TRANSFER','UNCLAIMED_REFUND') THEN
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
  -- P4-6: a balance transferred as unclaimed: nothing moves until it is refunded to a claimant
  open_unclaimed := EXISTS (SELECT 1 FROM deposits.unclaimed WHERE account_id = a.id AND refunded_on IS NULL);
  IF open_unclaimed AND NEW.kind NOT IN ('UNCLAIMED_REFUND','REVERSAL') THEN
    RAISE EXCEPTION 'account %: its balance was transferred as unclaimed; it is refunded to the claimant first', a.account_no USING ERRCODE = '23514';
  END IF;
  IF NOT open_unclaimed AND NEW.kind = 'UNCLAIMED_REFUND' THEN
    RAISE EXCEPTION 'account %: nothing was transferred as unclaimed', a.account_no USING ERRCODE = '23514';
  END IF;
  -- P4-6: an inoperative account takes credits, but no debit by or for the customer and no charge
  IF a.family = 'CASA' THEN
    SELECT * INTO c FROM deposits.casa_account WHERE account_id = a.id FOR UPDATE;
    IF c.inoperative_since IS NOT NULL AND NEW.direction = 'DEBIT' AND NEW.kind NOT IN ('REVERSAL','TAX_DEDUCTED','UNCLAIMED_TRANSFER') THEN
      RAISE EXCEPTION 'account % is inoperative since %: it is reactivated (KYC updated) before money is taken out of it',
        a.account_no, c.inoperative_since USING ERRCODE = '23514';
    END IF;
    IF deposits.customer_induced(NEW.kind) THEN
      UPDATE deposits.casa_account SET last_customer_txn_on = today WHERE account_id = a.id;
    END IF;
  END IF;
  -- freezes stop the customer's movements, not corrections, the interest the bank credits (P4-3) or the tax on it (P4-3b)
  IF NEW.kind NOT IN ('REVERSAL','INTEREST_CREDIT','TAX_DEDUCTED','UNCLAIMED_TRANSFER','UNCLAIMED_REFUND') THEN
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
  -- a closure, a renewal or an unclaimed transfer takes the whole balance
  IF NEW.kind IN ('CLOSURE','RENEWAL','UNCLAIMED_TRANSFER') AND bal <> 0 THEN
    RAISE EXCEPTION 'account %: a % takes the whole balance; % would be left', a.account_no, lower(replace(NEW.kind, '_', ' ')), deposits.plain(bal)
      USING ERRCODE = '23514';
  END IF;
  UPDATE deposits.account_balance SET balance = bal, updated_at = now() WHERE account_id = a.id;
  NEW.balance_after := bal;
  RETURN NEW;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- 3. Cheques
-- ---------------------------------------------------------------------------------------------------------
CREATE SEQUENCE deposits.cheque_leaf_no START 100001;     -- cheque numbers are six digits, never reused
CREATE SEQUENCE deposits.cheque_book_no;

CREATE TABLE deposits.cheque_book (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    book_no      text NOT NULL UNIQUE DEFAULT 'CB' || lpad(nextval('deposits.cheque_book_no')::text, 8, '0'),
    account_id   uuid NOT NULL REFERENCES deposits.account(id),
    first_leaf   int NOT NULL CHECK (first_leaf BETWEEN 100001 AND 999999),
    leaves       int NOT NULL CHECK (leaves IN (10, 25, 50, 100)),
    issued_on    date NOT NULL,
    issued_by    text NOT NULL,
    approval_id  uuid REFERENCES platform.approval_request(id),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cheque_book_account ON deposits.cheque_book (account_id);

CREATE TABLE deposits.cheque_leaf (
    account_id    uuid NOT NULL REFERENCES deposits.account(id),
    leaf_no       int NOT NULL,
    book_id       uuid NOT NULL REFERENCES deposits.cheque_book(id),
    status        text NOT NULL DEFAULT 'UNUSED' CHECK (status IN ('UNUSED','STOPPED','PAID')),
    stopped_on    date,
    stop_reason   text CHECK (stop_reason IS NULL OR length(stop_reason) <= 500),
    stopped_by    text,
    stop_approval_id uuid REFERENCES platform.approval_request(id),
    paid_on       date,
    presentment_id uuid,
    PRIMARY KEY (account_id, leaf_no),
    CONSTRAINT leaf_stopped CHECK ((status = 'STOPPED') = (stopped_on IS NOT NULL AND stop_reason IS NOT NULL)),
    CONSTRAINT leaf_paid CHECK ((status = 'PAID') = (paid_on IS NOT NULL AND presentment_id IS NOT NULL))
);
CREATE UNIQUE INDEX cheque_leaf_no_once ON deposits.cheque_leaf (leaf_no);

CREATE FUNCTION deposits.guard_cheque_book() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; p deposits.product%ROWTYPE; c deposits.casa_account%ROWTYPE; today date; n int;
BEGIN
  IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'a cheque book never changes; stop its leaves' USING ERRCODE = '42501'; END IF;
  SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
  NEW.issued_on := today;
  SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
  SELECT * INTO p FROM deposits.product WHERE code = a.product_code;
  SELECT * INTO c FROM deposits.casa_account WHERE account_id = a.id;
  IF a.family <> 'CASA' OR NOT coalesce(p.cheque_facility, false) THEN
    RAISE EXCEPTION 'account %: its product has no cheque facility', a.account_no USING ERRCODE = '23514';
  END IF;
  IF a.status <> 'ACTIVE' OR c.inoperative_since IS NOT NULL THEN
    RAISE EXCEPTION 'account % is %: a cheque book is issued on an active, operative account', a.account_no,
      CASE WHEN a.status <> 'ACTIVE' THEN a.status ELSE 'inoperative' END USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cheque_book_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.cheque_book FOR EACH ROW EXECUTE FUNCTION deposits.guard_cheque_book();

CREATE FUNCTION deposits.guard_cheque_leaf() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b deposits.cheque_book%ROWTYPE; today date;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a cheque leaf is never deleted' USING ERRCODE = '42501'; END IF;
  SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
  IF TG_OP = 'INSERT' THEN
    SELECT * INTO b FROM deposits.cheque_book WHERE id = NEW.book_id;
    IF b.account_id <> NEW.account_id OR NEW.leaf_no < b.first_leaf OR NEW.leaf_no >= b.first_leaf + b.leaves THEN
      RAISE EXCEPTION 'cheque %: a leaf of its own book', NEW.leaf_no USING ERRCODE = '23514';
    END IF;
    IF NEW.status <> 'UNUSED' THEN RAISE EXCEPTION 'a new cheque leaf is unused' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  IF (to_jsonb(NEW) - 'status' - 'stopped_on' - 'stop_reason' - 'stopped_by' - 'stop_approval_id' - 'paid_on' - 'presentment_id')
     <> (to_jsonb(OLD) - 'status' - 'stopped_on' - 'stop_reason' - 'stopped_by' - 'stop_approval_id' - 'paid_on' - 'presentment_id') THEN
    RAISE EXCEPTION 'cheque %: only its status changes', OLD.leaf_no USING ERRCODE = '42501';
  END IF;
  IF OLD.status = 'PAID' THEN RAISE EXCEPTION 'cheque % is paid', OLD.leaf_no USING ERRCODE = '23514'; END IF;
  IF NEW.status = 'STOPPED' AND OLD.status = 'UNUSED' THEN NEW.stopped_on := today; RETURN NEW; END IF;
  IF NEW.status = 'UNUSED' AND OLD.status = 'STOPPED' THEN
    NEW.stopped_on := NULL; NEW.stop_reason := NULL; NEW.stopped_by := NULL; NEW.stop_approval_id := NULL; RETURN NEW;
  END IF;
  IF NEW.status = 'PAID' AND OLD.status = 'UNUSED' THEN NEW.paid_on := today; RETURN NEW; END IF;
  RAISE EXCEPTION 'cheque %: % cannot become %', OLD.leaf_no, OLD.status, NEW.status USING ERRCODE = '23514';
END $$;
CREATE TRIGGER cheque_leaf_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.cheque_leaf FOR EACH ROW EXECUTE FUNCTION deposits.guard_cheque_leaf();

-- Inward clearing: a cheque drawn on one of our accounts, presented by another bank.
CREATE TABLE deposits.cheque_presentment (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       uuid REFERENCES deposits.account(id),     -- null when the account number is not ours
    account_no       text NOT NULL,
    leaf_no          int NOT NULL CHECK (leaf_no BETWEEN 1 AND 999999),
    amount           platform.money NOT NULL CHECK (amount > 0),
    cheque_date      date NOT NULL,
    payee            text NOT NULL CHECK (btrim(payee) <> '' AND length(payee) <= 200),
    presenting_bank  text NOT NULL CHECK (btrim(presenting_bank) <> '' AND length(presenting_bank) <= 100),
    presented_on     date NOT NULL,
    status           text NOT NULL CHECK (status IN ('PAID','RETURNED')),
    return_reason    text CHECK (return_reason IN ('INSUFFICIENT_FUNDS','PAYMENT_STOPPED','NOT_ISSUED','ALREADY_PAID','STALE','POST_DATED',
                                                   'ACCOUNT_CLOSED','ACCOUNT_FROZEN','ACCOUNT_INOPERATIVE','NO_SUCH_ACCOUNT','AMOUNT_HELD')),
    movement_id      uuid UNIQUE REFERENCES deposits.movement(id),
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT presentment_outcome CHECK (CASE status WHEN 'PAID' THEN movement_id IS NOT NULL AND return_reason IS NULL
                                                      ELSE movement_id IS NULL AND return_reason IS NOT NULL END)
);
CREATE INDEX cheque_presentment_account ON deposits.cheque_presentment (account_id, presented_on DESC);

-- Outward clearing: another bank's cheque paid in by one of our customers, credited once it clears.
CREATE TABLE deposits.cheque_deposit (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       uuid NOT NULL REFERENCES deposits.account(id),
    cheque_no        text NOT NULL CHECK (cheque_no ~ '^[0-9]{6}$'),
    drawee_ifsc      text NOT NULL CHECK (drawee_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
    drawer_name      text NOT NULL CHECK (btrim(drawer_name) <> '' AND length(drawer_name) <= 200),
    amount           platform.money NOT NULL CHECK (amount > 0),
    cheque_date      date NOT NULL,
    lodged_on        date NOT NULL,
    status           text NOT NULL DEFAULT 'IN_CLEARING' CHECK (status IN ('IN_CLEARING','CLEARED','RETURNED')),
    settled_on       date,
    return_reason    text CHECK (return_reason IS NULL OR length(return_reason) <= 200),
    movement_id      uuid UNIQUE REFERENCES deposits.movement(id),
    created_by       text NOT NULL,
    settled_by       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT deposit_outcome CHECK (CASE status WHEN 'IN_CLEARING' THEN settled_on IS NULL AND movement_id IS NULL AND return_reason IS NULL
                                                  WHEN 'CLEARED' THEN settled_on IS NOT NULL AND movement_id IS NOT NULL AND return_reason IS NULL
                                                  ELSE settled_on IS NOT NULL AND movement_id IS NULL AND return_reason IS NOT NULL END)
);
CREATE INDEX cheque_deposit_account ON deposits.cheque_deposit (account_id, lodged_on DESC);
CREATE INDEX cheque_deposit_in_clearing ON deposits.cheque_deposit (status) WHERE status = 'IN_CLEARING';

CREATE FUNCTION deposits.guard_cheque_clearing() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m deposits.movement%ROWTYPE; today date;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a clearing record is never deleted' USING ERRCODE = '42501'; END IF;
  SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
  IF TG_TABLE_NAME = 'cheque_presentment' THEN
    IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'a presentment never changes' USING ERRCODE = '42501'; END IF;
    NEW.presented_on := today;
    IF NEW.status = 'PAID' THEN
      SELECT * INTO m FROM deposits.movement WHERE id = NEW.movement_id;
      IF m.account_id IS DISTINCT FROM NEW.account_id OR m.kind <> 'CHEQUE_PAID' OR m.amount <> NEW.amount THEN
        RAISE EXCEPTION 'cheque %: a cheque paid is the CHEQUE_PAID of its account, for its amount', NEW.leaf_no USING ERRCODE = '23514';
      END IF;
    END IF;
    RETURN NEW;
  END IF;
  -- cheque_deposit
  IF TG_OP = 'INSERT' THEN
    NEW.lodged_on := today;
    IF NEW.status <> 'IN_CLEARING' THEN RAISE EXCEPTION 'a cheque paid in is first in clearing' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  IF OLD.status <> 'IN_CLEARING' OR (to_jsonb(NEW) - 'status' - 'settled_on' - 'return_reason' - 'movement_id' - 'settled_by')
                                    <> (to_jsonb(OLD) - 'status' - 'settled_on' - 'return_reason' - 'movement_id' - 'settled_by') THEN
    RAISE EXCEPTION 'a cheque paid in is settled once, as cleared or returned' USING ERRCODE = '42501';
  END IF;
  NEW.settled_on := today;
  IF NEW.status = 'CLEARED' THEN
    SELECT * INTO m FROM deposits.movement WHERE id = NEW.movement_id;
    IF m.account_id <> NEW.account_id OR m.kind <> 'CHEQUE_DEPOSIT' OR m.amount <> NEW.amount THEN
      RAISE EXCEPTION 'a cheque cleared is the CHEQUE_DEPOSIT of its account, for its amount' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cheque_presentment_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.cheque_presentment
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_cheque_clearing();
CREATE TRIGGER cheque_deposit_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.cheque_deposit
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_cheque_clearing();
ALTER TABLE deposits.cheque_leaf ADD CONSTRAINT leaf_presentment FOREIGN KEY (presentment_id) REFERENCES deposits.cheque_presentment(id);

-- ---------------------------------------------------------------------------------------------------------
-- 4. Net owned fund
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.net_owned_fund (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    as_of        date NOT NULL UNIQUE,                       -- the date of the audited balance sheet
    amount       platform.money NOT NULL CHECK (amount > 0),
    reference    text NOT NULL CHECK (btrim(reference) <> '' AND length(reference) <= 200),
    recorded_by  text NOT NULL,
    approval_id  uuid REFERENCES platform.approval_request(id),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER net_owned_fund_no_change BEFORE UPDATE OR DELETE ON deposits.net_owned_fund FOR EACH ROW EXECUTE FUNCTION deposits.forbid_change();
CREATE TRIGGER net_owned_fund_no_truncate BEFORE TRUNCATE ON deposits.net_owned_fund FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- Public deposits outstanding: every term deposit funded and not closed, at its balance.
CREATE FUNCTION deposits.public_deposits_outstanding() RETURNS numeric LANGUAGE sql STABLE AS $$
  SELECT coalesce(sum(b.balance), 0) FROM deposits.account a JOIN deposits.account_balance b ON b.account_id = a.id
   WHERE a.family = 'TERM_DEPOSIT' AND a.status IN ('ACTIVE','MATURED') $$;

-- ---------------------------------------------------------------------------------------------------------
-- 5. Maker-checker
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('CHEQUE', 'STOP', NULL, 0)                 -- a stop payment protects the customer: it takes effect at once
ON CONFLICT DO NOTHING;
