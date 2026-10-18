-- Phase 4 (P4-4): the life of a deposit account after it is opened.
--   1. Settings and rules: the heads charges post to, the overdue rate for matured deposits left unpaid, how many days
--      before maturity a depositor is told; rule td.overdue-interest (bank 1, NBFC 0; unverified, open item P4-19).
--   2. A savings or current product's schedule of charges (deposits.product.charges); deposits.charge: every charge
--      levied, with its GST and tax invoice; deposits.min_balance_month: the monthly average balance against the minimum.
--   3. Closing: an account is CLOSED with nothing left on it and nothing held; a term deposit is MATURED while its
--      proceeds wait to be paid or renewed. deposits.closure records how each account was closed and what it paid.
--   4. Movements CHARGE, CLOSURE, INTEREST_RECOVERY and RENEWAL; a deposit funded by renewing another (RENEWAL).
--   5. A deposit-taking NBFC renews only with the depositor's consent, recorded after booking (deposits.renewal_consent;
--      open item P4-06): an instruction given when the deposit is booked is not that consent.
--   6. Maturity notices (deposits.maturity_notice) and the message they send (integration.message.deposit_account_id).
--   7. Maker-checker rules; the GST output register and summary include deposit charges.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Settings and rules
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('deposits.gl.charge-income', '4107', 'migration'),        -- account service charges
  ('deposits.gl.cgst', '2201', 'migration'),
  ('deposits.gl.sgst', '2202', 'migration'),
  ('deposits.gl.igst', '2203', 'migration'),
  ('deposits.overdue-rate-percent', '2.75', 'migration'),    -- interest on a matured deposit left unpaid (the savings rate)
  ('deposits.maturity-notice-days', '14', 'migration')       -- when the rule set has no notice period of its own
ON CONFLICT (key) DO NOTHING;

INSERT INTO deposits.rule (kind, code, value, effective_from, source, verified, note) VALUES
 ('BANK','td.overdue-interest',1,'2026-04-01','RBI Commercial Banks Interest Rate on Deposits Directions 2025 (summary)',false,
  'A matured deposit left unpaid earns the savings rate or the contracted rate, whichever is lower'),
 ('NBFC_DEPOSIT','td.overdue-interest',0,'2026-04-01','RBI NBFC Public Deposits Directions 2025 (no provision found for interest after maturity)',false,
  'No interest after maturity unless the deposit is renewed; confirm');

-- ---------------------------------------------------------------------------------------------------------
-- 2. Charges
-- ---------------------------------------------------------------------------------------------------------
-- {"gstRatePercent": 18, "minBalancePercent": 10, "minBalanceMax": 300, "closureAmount": 500, "closureFreeWithinDays": 14,
--  "closureWithinDays": 365, "service": [{"code": "DUP_STATEMENT", "name": "Duplicate statement", "amount": 100}]}
-- The application checks the figures (DepositCharges.problems); the database holds the shape.
ALTER TABLE deposits.product
  ADD COLUMN charges jsonb,
  ADD CONSTRAINT product_charges_for_casa CHECK (charges IS NULL OR (family = 'CASA' AND jsonb_typeof(charges) = 'object'
      AND (NOT charges ? 'service' OR jsonb_typeof(charges->'service') = 'array')));

CREATE TABLE deposits.charge (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      uuid NOT NULL REFERENCES deposits.account(id),
    charge_code     text NOT NULL CHECK (charge_code ~ '^[A-Z][A-Z0-9_]{2,29}$'),
    description     text NOT NULL CHECK (btrim(description) <> '' AND length(description) <= 100),
    business_date   date NOT NULL,
    taxable_value   platform.money NOT NULL CHECK (taxable_value > 0),
    gst_rate        numeric(7,4) NOT NULL CHECK (gst_rate >= 0 AND gst_rate <= 100),
    cgst            platform.money NOT NULL DEFAULT 0 CHECK (cgst >= 0),
    sgst            platform.money NOT NULL DEFAULT 0 CHECK (sgst >= 0),
    igst            platform.money NOT NULL DEFAULT 0 CHECK (igst >= 0),
    total           numeric(20,4) GENERATED ALWAYS AS (taxable_value + cgst + sgst + igst) STORED,
    sac             text NOT NULL DEFAULT '997119',          -- other financial services (deposit account services)
    branch_code     text NOT NULL REFERENCES platform.branch(code),
    supplier_state  text NOT NULL,                           -- GST state code of the branch
    place_of_supply text NOT NULL,                           -- GST state code of the depositor's address
    invoice_no      text NOT NULL UNIQUE CHECK (length(invoice_no) <= 16),
    movement_id     uuid NOT NULL UNIQUE REFERENCES deposits.movement(id),
    lot_id          uuid NOT NULL REFERENCES ledger.transaction_lot(id),
    reason          text CHECK (reason IS NULL OR length(reason) <= 500),
    approval_id     uuid REFERENCES platform.approval_request(id),
    status          text NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED','CANCELLED')),
    cancelled_on    date,
    credit_note_no  text UNIQUE CHECK (credit_note_no IS NULL OR length(credit_note_no) <= 16),
    cancel_movement_id uuid UNIQUE REFERENCES deposits.movement(id),
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT charge_tax_matches_place CHECK ((igst = 0 OR supplier_state <> place_of_supply)
                                               AND (cgst + sgst = 0 OR supplier_state = place_of_supply)),
    CONSTRAINT charge_cancelled CHECK ((status = 'CANCELLED') = (cancelled_on IS NOT NULL)
                                       AND (status = 'CANCELLED') = (credit_note_no IS NOT NULL)
                                       AND (status = 'CANCELLED') = (cancel_movement_id IS NOT NULL))
);
CREATE INDEX charge_account ON deposits.charge (account_id, business_date);
CREATE INDEX charge_date ON deposits.charge (business_date);

-- A charge is issued once and can only be cancelled (its movement reversed), with a credit note.
CREATE FUNCTION deposits.guard_charge() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m deposits.movement%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a charge is never deleted; reverse it' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'ISSUED' AND NEW.status = 'CANCELLED'
       AND (to_jsonb(NEW) - '{status,cancelled_on,credit_note_no,cancel_movement_id,total}'::text[])
         = (to_jsonb(OLD) - '{status,cancelled_on,credit_note_no,cancel_movement_id,total}'::text[]) THEN
      SELECT * INTO m FROM deposits.movement WHERE id = NEW.cancel_movement_id;
      IF m.reverses IS DISTINCT FROM OLD.movement_id THEN
        RAISE EXCEPTION 'a charge is cancelled by the reversal of its movement' USING ERRCODE = '23514';
      END IF;
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'charge % never changes, except to be cancelled once', OLD.invoice_no USING ERRCODE = '42501';
  END IF;
  SELECT * INTO m FROM deposits.movement WHERE id = NEW.movement_id;
  IF m.kind <> 'CHARGE' OR m.account_id <> NEW.account_id OR m.lot_id <> NEW.lot_id THEN
    RAISE EXCEPTION 'a charge is levied by a CHARGE movement of the same account and lot' USING ERRCODE = '23514';
  END IF;
  IF m.amount <> NEW.taxable_value + NEW.cgst + NEW.sgst + NEW.igst THEN
    RAISE EXCEPTION 'the charge movement (%) is not the charge with its tax (%)', deposits.plain(m.amount),
      deposits.plain(NEW.taxable_value + NEW.cgst + NEW.sgst + NEW.igst) USING ERRCODE = '23514';
  END IF;
  IF NEW.status <> 'ISSUED' THEN RAISE EXCEPTION 'a charge is issued first' USING ERRCODE = '23514'; END IF;
  NEW.business_date := m.business_date;
  RETURN NEW;
END $$;
CREATE TRIGGER charge_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.charge FOR EACH ROW EXECUTE FUNCTION deposits.guard_charge();
CREATE TRIGGER charge_no_truncate BEFORE TRUNCATE ON deposits.charge FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- What the lot of a charge credits: its taxable value to the income head and its tax to the GST heads. Checked at COMMIT.
CREATE FUNCTION deposits.check_charge_lot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE income text; cg text; sg text; ig text; inc numeric; c numeric; s numeric; i numeric;
BEGIN
  SELECT coalesce((SELECT value FROM platform.system_property WHERE key = 'deposits.gl.charge-income'), '4107'),
         coalesce((SELECT value FROM platform.system_property WHERE key = 'deposits.gl.cgst'), '2201'),
         coalesce((SELECT value FROM platform.system_property WHERE key = 'deposits.gl.sgst'), '2202'),
         coalesce((SELECT value FROM platform.system_property WHERE key = 'deposits.gl.igst'), '2203') INTO income, cg, sg, ig;
  SELECT coalesce(sum(amount) FILTER (WHERE gl_code = income), 0), coalesce(sum(amount) FILTER (WHERE gl_code = cg), 0),
         coalesce(sum(amount) FILTER (WHERE gl_code = sg), 0), coalesce(sum(amount) FILTER (WHERE gl_code = ig), 0)
    INTO inc, c, s, i FROM ledger.account_entry WHERE lot_id = NEW.lot_id AND side = 'CR';
  IF inc <> NEW.taxable_value OR c <> NEW.cgst OR s <> NEW.sgst OR i <> NEW.igst THEN
    RAISE EXCEPTION 'charge %: the lot does not credit its value and tax to the charge and GST heads', NEW.invoice_no USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER charge_matches_lot AFTER INSERT ON deposits.charge
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_charge_lot();

-- The month's average balance against the product's minimum, one row per account and month evaluated. A charge is
-- levied only for a short month after a short month that was notified (the depositor had a month to restore it).
ALTER TABLE deposits.casa_account ADD COLUMN min_balance_through date;     -- the last month-end evaluated
CREATE TABLE deposits.min_balance_month (
    account_id  uuid NOT NULL REFERENCES deposits.account(id),
    month       date NOT NULL CHECK (extract(day FROM month) = 1),
    from_date   date NOT NULL,
    to_date     date NOT NULL,
    required    platform.money NOT NULL CHECK (required >= 0),
    average     platform.money NOT NULL,
    shortfall   platform.money NOT NULL CHECK (shortfall >= 0),
    notified    boolean NOT NULL,
    charge_id   uuid REFERENCES deposits.charge(id),
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, month),
    CHECK (from_date <= to_date),
    CHECK (charge_id IS NULL OR shortfall > 0),
    CHECK (notified = (shortfall > 0))
);
CREATE TRIGGER min_balance_month_immutable BEFORE UPDATE OR DELETE ON deposits.min_balance_month
  FOR EACH ROW EXECUTE FUNCTION deposits.forbid_change();

-- ---------------------------------------------------------------------------------------------------------
-- 3. Closing an account
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.account
  ADD COLUMN closed_on         date,
  ADD COLUMN closed_by         text,
  ADD COLUMN close_reason      text CHECK (close_reason IS NULL OR (btrim(close_reason) <> '' AND length(close_reason) <= 500)),
  ADD COLUMN close_approval_id uuid REFERENCES platform.approval_request(id),
  ADD CONSTRAINT account_closed CHECK ((status = 'CLOSED') = (closed_on IS NOT NULL)
                                       AND (status = 'CLOSED') = (closed_by IS NOT NULL));

CREATE OR REPLACE FUNCTION deposits.guard_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p deposits.product%ROWTYPE; b text; ok boolean;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'a deposit account is never deleted' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF NEW.id <> OLD.id OR NEW.account_no <> OLD.account_no OR NEW.family <> OLD.family OR NEW.product_code <> OLD.product_code
       OR NEW.product_version <> OLD.product_version OR NEW.customer_id <> OLD.customer_id OR NEW.opened_on <> OLD.opened_on
       OR NEW.approval_id IS DISTINCT FROM OLD.approval_id OR NEW.opened_by <> OLD.opened_by THEN
      RAISE EXCEPTION 'the number, product, first holder and opening of a deposit account never change' USING ERRCODE = '42501';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
      -- the states an account goes through (P4-4); a deposit paid out through the gateway and returned is MATURED again
      ok := CASE OLD.family
              WHEN 'CASA' THEN OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED'
              ELSE (OLD.status, NEW.status) IN (('BOOKED','ACTIVE'), ('ACTIVE','BOOKED'), ('BOOKED','CANCELLED'), ('ACTIVE','MATURED'),
                                                ('ACTIVE','CLOSED'), ('MATURED','CLOSED'), ('CLOSED','MATURED')) END;
      IF NOT ok THEN
        RAISE EXCEPTION 'account % cannot go from % to %', OLD.account_no, OLD.status, NEW.status USING ERRCODE = '23514';
      END IF;
      IF NEW.status = 'CLOSED' THEN
        SELECT business_date INTO NEW.closed_on FROM platform.business_day WHERE id = 1;
        IF NEW.closed_by IS NULL THEN RAISE EXCEPTION 'account %: who closed it is recorded', OLD.account_no USING ERRCODE = '23514'; END IF;
      ELSIF OLD.status = 'CLOSED' THEN
        NEW.closed_on := NULL; NEW.closed_by := NULL; NEW.close_reason := NULL; NEW.close_approval_id := NULL;
      END IF;
    ELSIF OLD.status = 'CLOSED' AND (NEW.closed_on IS DISTINCT FROM OLD.closed_on OR NEW.closed_by IS DISTINCT FROM OLD.closed_by
                                     OR NEW.close_reason IS DISTINCT FROM OLD.close_reason) THEN
      RAISE EXCEPTION 'the closure of account % never changes', OLD.account_no USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO p FROM deposits.product WHERE code = NEW.product_code;
  IF NOT FOUND THEN RAISE EXCEPTION 'deposit product % not found', NEW.product_code USING ERRCODE = 'P0002'; END IF;
  IF p.family <> NEW.family THEN
    RAISE EXCEPTION 'product % is not a % product', p.code, CASE NEW.family WHEN 'CASA' THEN 'savings or current account' ELSE 'term deposit' END
      USING ERRCODE = '23514';
  END IF;
  IF p.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'product % is %; it takes no new account', p.code, p.status USING ERRCODE = '23514';
  END IF;
  PERFORM deposits.assert_can_offer(NEW.family);
  SELECT status INTO b FROM platform.branch WHERE code = NEW.branch_code;
  IF b IS DISTINCT FROM 'ACTIVE' THEN
    RAISE EXCEPTION 'branch % is not active', NEW.branch_code USING ERRCODE = '23514';
  END IF;
  NEW.product_version := p.version;
  NEW.account_no := platform.next_number(CASE NEW.family WHEN 'CASA' THEN 'CASA' ELSE 'TERM_DEPOSIT' END);
  NEW.status := CASE NEW.family WHEN 'CASA' THEN 'ACTIVE' ELSE 'BOOKED' END;
  NEW.closed_on := NULL; NEW.closed_by := NULL; NEW.close_reason := NULL; NEW.close_approval_id := NULL;
  SELECT business_date INTO NEW.opened_on FROM platform.business_day WHERE id = 1;
  IF NEW.opened_on IS NULL THEN RAISE EXCEPTION 'business date not initialised' USING ERRCODE = 'P0002'; END IF;
  RETURN NEW;
END $$;

-- A closed account holds nothing and has nothing held on it; a closed savings or current account is not where a live
-- deposit pays out, nor has a gateway transfer still on its way. Checked at COMMIT (the payout and the status are
-- written in one transaction).
CREATE FUNCTION deposits.check_closure(p_account uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; bal numeric; d text;
BEGIN
  SELECT * INTO a FROM deposits.account WHERE id = p_account;
  IF a.status <> 'CLOSED' THEN RETURN; END IF;
  SELECT coalesce(max(balance), 0) INTO bal FROM deposits.account_balance WHERE account_id = p_account;
  IF bal <> 0 THEN
    RAISE EXCEPTION 'account %: a closed account holds nothing; % is left on it', a.account_no, deposits.plain(bal) USING ERRCODE = '23514';
  END IF;
  IF EXISTS (SELECT 1 FROM deposits.account_hold WHERE account_id = p_account AND status = 'ACTIVE') THEN
    RAISE EXCEPTION 'account %: release its freezes and liens before it is closed', a.account_no USING ERRCODE = '23514';
  END IF;
  IF a.family = 'CASA' THEN
    SELECT string_agg(x.account_no, ', ' ORDER BY x.account_no) INTO d FROM deposits.term_deposit t JOIN deposits.account x ON x.id = t.account_id
     WHERE t.payout_account_id = p_account AND x.status IN ('BOOKED','ACTIVE','MATURED');
    IF d IS NOT NULL THEN
      RAISE EXCEPTION 'account %: deposit % pays out to it; change the deposit''s payout first', a.account_no, d USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM deposits.gateway_transfer g JOIN deposits.movement m ON m.id = g.movement_id
                WHERE m.account_id = p_account AND g.status = 'SENT') THEN
      RAISE EXCEPTION 'account %: a transfer sent through the payment gateway has not been paid yet', a.account_no USING ERRCODE = '23514';
    END IF;
  END IF;
END $$;
CREATE FUNCTION deposits.check_closure_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM deposits.check_closure(NEW.id);
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER account_closed_empty AFTER UPDATE ON deposits.account
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_closure_trigger();

-- How each account was closed and what it paid. One row per closure (a deposit whose gateway payout came back is
-- closed again later, so the earlier row is marked returned).
CREATE TABLE deposits.closure (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id          uuid NOT NULL REFERENCES deposits.account(id),
    kind                text NOT NULL CHECK (kind IN ('CASA','PREMATURE','MATURITY','RENEWAL')),
    reason              text NOT NULL CHECK (reason IN ('DEPOSITOR_REQUEST','DEATH','EMERGENCY_EXPENSE','CRITICAL_ILLNESS','MATURITY')),
    business_date       date NOT NULL,
    principal           platform.money,
    rate_percent        numeric(8,4),                    -- the rate interest was finally worked out at
    interest_due        numeric(20,4) NOT NULL DEFAULT 0,   -- interest earned on the deposit (at that rate)
    interest_credited   numeric(20,4) NOT NULL DEFAULT 0,   -- credited at closure
    interest_recovered  numeric(20,4) NOT NULL DEFAULT 0 CHECK (interest_recovered >= 0),
    overdue_interest    numeric(20,4) NOT NULL DEFAULT 0 CHECK (overdue_interest >= 0),
    charges             numeric(20,4) NOT NULL DEFAULT 0 CHECK (charges >= 0),
    tax                 numeric(20,4) NOT NULL DEFAULT 0 CHECK (tax >= 0),
    paid_amount         numeric(20,4) NOT NULL CHECK (paid_amount >= 0),
    pay_to              text CHECK (pay_to IN ('ACCOUNT','CASH','BANK_TRANSFER','GATEWAY','RENEWAL')),
    pay_account_id      uuid REFERENCES deposits.account(id),
    pay_reference       text CHECK (pay_reference IS NULL OR length(pay_reference) <= 60),
    pay_movement_id     uuid REFERENCES deposits.movement(id),
    renewed_to          uuid REFERENCES deposits.account(id),
    basis               text CHECK (basis IS NULL OR length(basis) <= 500),
    note                text CHECK (note IS NULL OR length(note) <= 500),
    approval_id         uuid REFERENCES platform.approval_request(id),
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    payout_returned_on  date,
    CONSTRAINT closure_paid CHECK ((paid_amount > 0) = (pay_to IS NOT NULL)),
    CONSTRAINT closure_pay_account CHECK ((pay_to = 'ACCOUNT') = (pay_account_id IS NOT NULL)),
    CONSTRAINT closure_renewal CHECK ((pay_to IS NOT DISTINCT FROM 'RENEWAL') = (renewed_to IS NOT NULL)),
    CONSTRAINT closure_kind_reason CHECK ((kind IN ('MATURITY','RENEWAL')) = (reason = 'MATURITY'))
);
CREATE UNIQUE INDEX closure_one_live ON deposits.closure (account_id) WHERE payout_returned_on IS NULL;
CREATE FUNCTION deposits.guard_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a closure is never deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.payout_returned_on IS NULL AND NEW.payout_returned_on IS NOT NULL
     AND (to_jsonb(NEW) - 'payout_returned_on') = (to_jsonb(OLD) - 'payout_returned_on') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'a closure never changes, except to record that its gateway payout came back' USING ERRCODE = '42501';
END $$;
CREATE TRIGGER closure_guard BEFORE UPDATE OR DELETE ON deposits.closure FOR EACH ROW EXECUTE FUNCTION deposits.guard_closure();
CREATE TRIGGER closure_no_truncate BEFORE TRUNCATE ON deposits.closure FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- ---------------------------------------------------------------------------------------------------------
-- 4. Movements
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE deposits.movement DROP CONSTRAINT movement_kind, DROP CONSTRAINT movement_direction, DROP CONSTRAINT movement_counterparty;
ALTER TABLE deposits.movement
  ADD CONSTRAINT movement_kind CHECK (kind IN ('CASH_DEPOSIT','CASH_WITHDRAWAL','BANK_TRANSFER_IN','BANK_TRANSFER_OUT','TRANSFER_IN',
                                               'TRANSFER_OUT','FUNDING','FUNDING_PAID','REVERSAL','INTEREST_CREDIT','INTEREST_PAYOUT',
                                               'TAX_DEDUCTED','CHARGE','CLOSURE','INTEREST_RECOVERY','RENEWAL')),
  ADD CONSTRAINT movement_direction CHECK (kind = 'REVERSAL' OR direction = CASE
        WHEN kind IN ('CASH_DEPOSIT','BANK_TRANSFER_IN','TRANSFER_IN','FUNDING','INTEREST_CREDIT') THEN 'CREDIT' ELSE 'DEBIT' END),
  ADD CONSTRAINT movement_counterparty CHECK ((kind IN ('TRANSFER_IN','TRANSFER_OUT','FUNDING_PAID','INTEREST_PAYOUT','RENEWAL'))
                                              <= (counterparty_id IS NOT NULL));

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
  ELSIF a.family = 'CASA' AND NEW.kind IN ('FUNDING','INTEREST_PAYOUT','INTEREST_RECOVERY','RENEWAL') THEN
    RAISE EXCEPTION 'account % is not a term deposit', a.account_no USING ERRCODE = '23514';
  ELSIF a.family = 'TERM_DEPOSIT' AND NEW.kind NOT IN ('FUNDING','INTEREST_CREDIT','INTEREST_PAYOUT','BANK_TRANSFER_OUT','TAX_DEDUCTED',
                                                       'CLOSURE','INTEREST_RECOVERY','RENEWAL') THEN
    RAISE EXCEPTION 'term deposit %: money moves by its funding, its interest and the tax on it, and its closure or renewal only',
      a.account_no USING ERRCODE = '23514';
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

-- A deposit funded by renewing another: the renewed deposit is the depositor's own, and the funding is not reversed.
DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'deposits.term_deposit'::regclass AND contype = 'c' AND conname = 'term_deposit_funding_source' LOOP
    EXECUTE format('ALTER TABLE deposits.term_deposit DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE deposits.term_deposit
  ADD COLUMN renewed_from uuid UNIQUE REFERENCES deposits.account(id),
  ADD CONSTRAINT term_deposit_funding_source CHECK (funding_source IN ('ACCOUNT','CASH','BANK_TRANSFER','GATEWAY','RENEWAL')),
  ADD CONSTRAINT term_deposit_renewed_from CHECK ((funding_source IS NOT DISTINCT FROM 'RENEWAL') = (renewed_from IS NOT NULL));

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

-- A funded deposit holds at least its principal while ACTIVE; one funded by a renewal comes from a deposit of the same
-- depositor. MATURED and CLOSED deposits are checked by their own rules.
CREATE OR REPLACE FUNCTION deposits.check_funding(p_account uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; t deposits.term_deposit%ROWTYPE; m deposits.movement%ROWTYPE; bal numeric; old deposits.account%ROWTYPE;
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
  IF t.renewed_from IS NOT NULL THEN
    SELECT * INTO old FROM deposits.account WHERE id = t.renewed_from;
    IF old.family <> 'TERM_DEPOSIT' OR old.customer_id <> a.customer_id OR m.counterparty_id IS DISTINCT FROM t.renewed_from THEN
      RAISE EXCEPTION 'term deposit %: it renews a deposit of the same depositor, and is funded from it', a.account_no USING ERRCODE = '23514';
    END IF;
  END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- 5. Consent to renew (deposit-taking NBFC)
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.renewal_consent (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid NOT NULL REFERENCES deposits.account(id),
    instruction   text NOT NULL CHECK (instruction IN ('RENEW_PRINCIPAL','RENEW_WITH_INTEREST')),
    reference     text NOT NULL CHECK (btrim(reference) <> '' AND length(reference) <= 60),   -- the depositor's signed request
    received_on   date NOT NULL,
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','USED','WITHDRAWN')),
    recorded_by   text NOT NULL,
    approval_id   uuid REFERENCES platform.approval_request(id),
    used_on       date,
    withdrawn_on  date,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT renewal_consent_used CHECK ((status = 'USED') = (used_on IS NOT NULL) AND (status = 'WITHDRAWN') = (withdrawn_on IS NOT NULL))
);
CREATE UNIQUE INDEX renewal_consent_one_active ON deposits.renewal_consent (account_id) WHERE status = 'ACTIVE';
CREATE FUNCTION deposits.guard_renewal_consent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; today date;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a consent is never deleted; withdraw it' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'ACTIVE' AND NEW.status IN ('USED','WITHDRAWN')
       AND (to_jsonb(NEW) - 'status' - 'used_on' - 'withdrawn_on') = (to_jsonb(OLD) - 'status' - 'used_on' - 'withdrawn_on') THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a consent never changes, except to be used or withdrawn once' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
  SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
  IF a.family <> 'TERM_DEPOSIT' OR a.status NOT IN ('ACTIVE','MATURED') THEN
    RAISE EXCEPTION 'account %: consent to renew is recorded for a funded or matured term deposit', a.account_no USING ERRCODE = '23514';
  END IF;
  IF NEW.received_on > today OR NEW.received_on < a.opened_on THEN
    RAISE EXCEPTION 'deposit %: the consent was received between booking and today', a.account_no USING ERRCODE = '23514';
  END IF;
  IF NEW.status <> 'ACTIVE' THEN RAISE EXCEPTION 'a consent is recorded active' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER renewal_consent_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.renewal_consent
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_renewal_consent();
CREATE TRIGGER renewal_consent_no_truncate BEFORE TRUNCATE ON deposits.renewal_consent
  FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- ---------------------------------------------------------------------------------------------------------
-- 6. Maturity notices
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.maturity_notice (
    account_id      uuid NOT NULL REFERENCES deposits.account(id),
    maturity_date   date NOT NULL,
    notice_on       date NOT NULL,
    instruction     text NOT NULL,
    expected_amount numeric(20,4) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, maturity_date),
    CHECK (notice_on < maturity_date)
);
CREATE TRIGGER maturity_notice_immutable BEFORE UPDATE OR DELETE ON deposits.maturity_notice
  FOR EACH ROW EXECUTE FUNCTION deposits.forbid_change();

ALTER TABLE integration.message ADD COLUMN deposit_account_id uuid REFERENCES deposits.account(id);

ALTER TABLE deposits.interest_event DROP CONSTRAINT interest_event_kind;
ALTER TABLE deposits.interest_event
  ADD CONSTRAINT interest_event_kind CHECK (kind IN ('ACCRUAL','CREDIT','PAYOUT','PAYOUT_HELD','REVERSAL','TAX','RECOVERY','OVERDUE'));

-- ---------------------------------------------------------------------------------------------------------
-- 7. Maker-checker; GST output reports
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('DEPOSIT_TXN', 'CHARGE', NULL, 1),
  ('CASA_ACCOUNT', 'CLOSE', NULL, 1),
  ('TERM_DEPOSIT', 'CLOSE', NULL, 1),
  ('TERM_DEPOSIT', 'INSTRUCTION', NULL, 1),
  ('TERM_DEPOSIT', 'RENEW', NULL, 1)
ON CONFLICT DO NOTHING;

-- The register lists loan fee invoices and deposit charges together; for a deposit charge loan_no is the account number.
CREATE OR REPLACE FUNCTION reporting.rpt_gst_output_register(p_user text, p_params jsonb)
RETURNS TABLE (invoice_no text, invoice_date date, status text, loan_no text, customer_no text, customer_name text, branch_code text,
               supplier_state text, place_of_supply text, place_of_supply_name text, supply_type text, sac text, description text,
               taxable_value numeric, gst_rate numeric, cgst numeric, sgst numeric, igst numeric, total numeric, cancelled_on date)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.param_date(p_params, 'from', date_trunc('month', reporting.business_date())::date) AS f,
                      reporting.param_date(p_params, 'to', reporting.business_date()) AS t),
  rows AS (
    SELECT i.invoice_no, i.invoice_date, i.status, l.loan_no, c.customer_no, c.display_name, i.branch_code, i.supplier_state,
           i.place_of_supply, i.sac, i.description, i.taxable_value::numeric AS taxable_value, i.gst_rate, i.cgst::numeric AS cgst,
           i.sgst::numeric AS sgst, i.igst::numeric AS igst, i.total, i.cancelled_on
      FROM lending.fee_invoice i JOIN lending.loan_account l ON l.id = i.loan_id JOIN customer.customer c ON c.id = l.customer_id
    UNION ALL
    SELECT g.invoice_no, g.business_date, g.status, a.account_no, c.customer_no, c.display_name, g.branch_code, g.supplier_state,
           g.place_of_supply, g.sac, g.description, g.taxable_value::numeric, g.gst_rate, g.cgst::numeric, g.sgst::numeric, g.igst::numeric,
           g.total, g.cancelled_on
      FROM deposits.charge g JOIN deposits.account a ON a.id = g.account_id JOIN customer.customer c ON c.id = a.customer_id)
  SELECT r.invoice_no, r.invoice_date, r.status, r.loan_no, r.customer_no, r.display_name, r.branch_code, r.supplier_state,
         r.place_of_supply, platform.state_name(r.place_of_supply),
         CASE WHEN r.supplier_state = r.place_of_supply THEN 'INTRA_STATE' ELSE 'INTER_STATE' END, r.sac, r.description,
         r.taxable_value, r.gst_rate, r.cgst, r.sgst, r.igst, r.total, r.cancelled_on
    FROM rows r, prm
   WHERE r.invoice_date BETWEEN prm.f AND prm.t
     AND r.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   ORDER BY r.invoice_no
$$;

CREATE OR REPLACE FUNCTION reporting.rpt_gst_output_summary(p_user text, p_params jsonb)
RETURNS TABLE (from_date date, to_date date, supplier_state text, place_of_supply text, place_of_supply_name text, supply_type text,
               gst_rate numeric, invoices bigint, taxable_value numeric, cgst numeric, sgst numeric, igst numeric, total_tax numeric)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.param_date(p_params, 'from', date_trunc('month', reporting.business_date())::date) AS f,
                      reporting.param_date(p_params, 'to', reporting.business_date()) AS t),
  live AS (
    SELECT i.invoice_date AS d, i.branch_code, i.supplier_state, i.place_of_supply, i.gst_rate, i.taxable_value::numeric AS taxable_value,
           i.cgst::numeric AS cgst, i.sgst::numeric AS sgst, i.igst::numeric AS igst
      FROM lending.fee_invoice i WHERE i.status = 'ISSUED'
    UNION ALL
    SELECT g.business_date, g.branch_code, g.supplier_state, g.place_of_supply, g.gst_rate, g.taxable_value::numeric, g.cgst::numeric,
           g.sgst::numeric, g.igst::numeric
      FROM deposits.charge g WHERE g.status = 'ISSUED')
  SELECT prm.f, prm.t, x.supplier_state, x.place_of_supply, platform.state_name(x.place_of_supply),
         CASE WHEN x.supplier_state = x.place_of_supply THEN 'INTRA_STATE' ELSE 'INTER_STATE' END, x.gst_rate, count(*),
         sum(x.taxable_value), sum(x.cgst), sum(x.sgst), sum(x.igst), sum(x.cgst + x.sgst + x.igst)
    FROM live x, prm
   WHERE x.d BETWEEN prm.f AND prm.t
     AND x.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   GROUP BY prm.f, prm.t, x.supplier_state, x.place_of_supply, x.gst_rate
   ORDER BY x.supplier_state, x.place_of_supply, x.gst_rate
$$;
