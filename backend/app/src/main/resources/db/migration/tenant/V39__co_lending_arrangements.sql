-- Phase 5 (P5-1): co-lending arrangements and the allocation of a loan's share to the partner, under the RBI
-- (Co-Lending Arrangements) Directions, 2025 (in force from 1-Jan-2026). This tenant is the originating lender: it
-- sources and services the loan and keeps at least 10%; the partner (a bank, an AIFI or another NBFC) funds the rest.
--   1. Heads: the partner's share of co-lent loans (contra-asset 1105), the co-lending escrow account (1208), the
--      partner payable (2303, already in the starter kit) and servicing fee income (4110); and the settings naming them.
--   2. lending.co_lending_arrangement: the partner and the terms (shares, rates, servicing fee, how fees and penal
--      charges are shared, the default loss guarantee), maker-checker. A change binds loans sanctioned after it only.
--   3. lending.co_lent_loan: a loan sanctioned under an arrangement, its terms frozen, and its allocation: offered to the
--      partner at disbursement, accepted (the partner's share booked) within 15 calendar days, or declined or lapsed
--      (the loan stays wholly with the originator).
-- The daily division of interest and collections, settlement through escrow, classification sync, DLG and the bureau
-- come in P5-2 and P5-3.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Heads and settings
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION ledger.load_co_lending_heads() RETURNS int LANGUAGE plpgsql AS $$
DECLARE before int; after int;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '1100') THEN RETURN 0; END IF;    -- no loan book
  SELECT count(*) INTO before FROM ledger.gl_head;
  INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES
    ('1105', 'Co-lent loans - partner''s share (contra)', 'ASSET', '1100', true)
  ON CONFLICT (code) DO NOTHING;
  IF EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '1200') THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES ('1208', 'Co-lending escrow account', 'ASSET', '1200', true)
    ON CONFLICT (code) DO NOTHING;
  END IF;
  IF EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2300') THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES ('2303', 'Co-lending partner payable', 'LIABILITY', '2300', true)
    ON CONFLICT (code) DO NOTHING;
  END IF;
  IF EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '4000') THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES ('4110', 'Co-lending servicing fee income', 'INCOME', '4000', true)
    ON CONFLICT (code) DO NOTHING;
  END IF;
  SELECT count(*) INTO after FROM ledger.gl_head;
  RETURN after - before;
END $$;
SELECT ledger.load_co_lending_heads();

INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('lending.co-lending.partner-share-gl', '1105', 'migration'),
  ('lending.co-lending.escrow-gl', '1208', 'migration'),
  ('lending.co-lending.partner-payable-gl', '2303', 'migration'),
  ('lending.co-lending.servicing-fee-gl', '4110', 'migration')
ON CONFLICT (key) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- 2. Arrangements
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.co_lending_arrangement (
    code               text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    name               text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 120),
    role               text NOT NULL DEFAULT 'ORIGINATOR' CHECK (role IN ('ORIGINATOR')),     -- the partner side comes in P5-4
    partner_name       text NOT NULL CHECK (btrim(partner_name) <> '' AND length(partner_name) <= 200),
    partner_kind       text NOT NULL CHECK (partner_kind IN ('BANK','AIFI','NBFC')),
    partner_reference  text CHECK (partner_reference IS NULL OR length(partner_reference) <= 60),   -- its RBI registration, IFSC prefix …
    agreement_reference text NOT NULL CHECK (btrim(agreement_reference) <> '' AND length(agreement_reference) <= 100),
    originator_share   numeric(5,2) NOT NULL CHECK (originator_share BETWEEN 10 AND 90),            -- para 10: each keeps 10%
    originator_rate    numeric(7,4) NOT NULL CHECK (originator_rate > 0 AND originator_rate < 100),
    partner_rate       numeric(7,4) NOT NULL CHECK (partner_rate > 0 AND partner_rate < 100),
    servicing_fee      numeric(7,4) NOT NULL DEFAULT 0 CHECK (servicing_fee >= 0),                  -- % a year on the partner's outstanding
    fee_sharing        text NOT NULL DEFAULT 'ORIGINATOR' CHECK (fee_sharing IN ('ORIGINATOR','BY_SHARE')),
    penal_sharing      text NOT NULL DEFAULT 'BY_SHARE' CHECK (penal_sharing IN ('ORIGINATOR','BY_SHARE')),
    dlg_percent        numeric(5,2) NOT NULL DEFAULT 0 CHECK (dlg_percent BETWEEN 0 AND 5),          -- para 32: at most 5%
    product_codes      text[] NOT NULL CHECK (cardinality(product_codes) BETWEEN 1 AND 50),
    contact            text NOT NULL CHECK (btrim(contact) <> '' AND length(contact) <= 200),       -- the borrower's single point of contact
    status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    effective_from     date NOT NULL,
    version            int NOT NULL DEFAULT 1,
    created_by         text NOT NULL,
    approval_id        uuid REFERENCES platform.approval_request(id),
    updated_by         text,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT co_lending_fee_below_rate CHECK (servicing_fee < partner_rate)
);

CREATE FUNCTION lending.guard_co_lending_arrangement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p text;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a co-lending arrangement is never deleted; close it' USING ERRCODE = '42501'; END IF;
  FOREACH p IN ARRAY NEW.product_codes LOOP
    IF NOT EXISTS (SELECT 1 FROM lending.loan_product WHERE code = p) THEN
      RAISE EXCEPTION 'co-lending arrangement %: no loan product %', NEW.code, p USING ERRCODE = '23514';
    END IF;
  END LOOP;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'CLOSED' THEN RAISE EXCEPTION 'co-lending arrangement % is closed', OLD.code USING ERRCODE = '42501'; END IF;
    IF NEW.code <> OLD.code OR NEW.role <> OLD.role OR NEW.partner_name <> OLD.partner_name OR NEW.partner_kind <> OLD.partner_kind
       OR NEW.created_by <> OLD.created_by THEN
      RAISE EXCEPTION 'co-lending arrangement %: the partner and its role never change; close it and agree a new one', OLD.code USING ERRCODE = '42501';
    END IF;
    NEW.version := OLD.version + 1;
    NEW.updated_at := now();
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER co_lending_arrangement_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.co_lending_arrangement
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lending_arrangement();

-- ---------------------------------------------------------------------------------------------------------
-- 3. Co-lent loans
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.co_lent_loan (
    loan_id            uuid PRIMARY KEY REFERENCES lending.loan_account(id),
    arrangement_code   text NOT NULL REFERENCES lending.co_lending_arrangement(code),
    arrangement_version int NOT NULL,
    -- the terms frozen at sanction
    originator_share   numeric(5,2) NOT NULL CHECK (originator_share BETWEEN 10 AND 90),
    originator_rate    numeric(7,4) NOT NULL,
    partner_rate       numeric(7,4) NOT NULL,
    blended_rate       numeric(7,4) NOT NULL,
    servicing_fee      numeric(7,4) NOT NULL,
    fee_sharing        text NOT NULL,
    penal_sharing      text NOT NULL,
    dlg_percent        numeric(5,2) NOT NULL,
    -- the allocation
    status             text NOT NULL DEFAULT 'SANCTIONED'
                       CHECK (status IN ('SANCTIONED','OFFERED','ACCEPTED','DECLINED','LAPSED','WITHDRAWN')),
    offered_on         date,
    deadline           date,
    decided_on         date,
    decision_reason    text CHECK (decision_reason IS NULL OR length(decision_reason) <= 500),
    partner_loan_ref   text CHECK (partner_loan_ref IS NULL OR length(partner_loan_ref) <= 60),
    partner_principal  platform.money,
    transfer_lot_id    uuid REFERENCES ledger.transaction_lot(id),
    decision_approval_id uuid REFERENCES platform.approval_request(id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT co_lent_offer CHECK ((status = 'SANCTIONED') = (offered_on IS NULL) AND (offered_on IS NULL) = (deadline IS NULL)),
    CONSTRAINT co_lent_window CHECK (deadline IS NULL OR deadline = offered_on + 15),                  -- paras 22, 24
    CONSTRAINT co_lent_decided CHECK ((status IN ('ACCEPTED','DECLINED','LAPSED','WITHDRAWN')) = (decided_on IS NOT NULL)),
    CONSTRAINT co_lent_accepted CHECK ((status = 'ACCEPTED') = (transfer_lot_id IS NOT NULL)
                                       AND (status = 'ACCEPTED') = (partner_principal IS NOT NULL)),
    CONSTRAINT co_lent_in_time CHECK (status <> 'ACCEPTED' OR decided_on <= deadline)
);
CREATE INDEX co_lent_loan_arrangement ON lending.co_lent_loan (arrangement_code, status);
CREATE INDEX co_lent_loan_offered ON lending.co_lent_loan (deadline) WHERE status = 'OFFERED';

CREATE FUNCTION lending.guard_co_lent_loan() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE l lending.loan_account%ROWTYPE; a lending.co_lending_arrangement%ROWTYPE; today date; s numeric; lot ledger.transaction_lot%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a co-lent loan is never deleted' USING ERRCODE = '42501'; END IF;
  SELECT business_date INTO today FROM platform.business_day WHERE id = 1;
  SELECT * INTO l FROM lending.loan_account WHERE id = NEW.loan_id;
  IF TG_OP = 'INSERT' THEN
    SELECT * INTO a FROM lending.co_lending_arrangement WHERE code = NEW.arrangement_code;
    IF a.status <> 'ACTIVE' THEN
      RAISE EXCEPTION 'co-lending arrangement % is %: it takes no new loan', a.code, a.status USING ERRCODE = '23514';
    END IF;
    IF NOT (l.product_code = ANY (a.product_codes)) THEN
      RAISE EXCEPTION 'loan %: product % is not under co-lending arrangement %', l.loan_no, l.product_code, a.code USING ERRCODE = '23514';
    END IF;
    IF l.status <> 'SANCTIONED' THEN RAISE EXCEPTION 'loan % is %: a loan is co-lent from its sanction', l.loan_no, l.status USING ERRCODE = '23514'; END IF;
    IF coalesce(l.multiple_disbursements, false) THEN
      RAISE EXCEPTION 'loan %: a loan disbursed in tranches is not co-lent (open item P5-01)', l.loan_no USING ERRCODE = '23514';
    END IF;
    IF NEW.arrangement_version <> a.version OR NEW.originator_share <> a.originator_share OR NEW.originator_rate <> a.originator_rate
       OR NEW.partner_rate <> a.partner_rate OR NEW.servicing_fee <> a.servicing_fee OR NEW.fee_sharing <> a.fee_sharing
       OR NEW.penal_sharing <> a.penal_sharing OR NEW.dlg_percent <> a.dlg_percent THEN
      RAISE EXCEPTION 'loan %: the terms are those of arrangement % as it stands', l.loan_no, a.code USING ERRCODE = '23514';
    END IF;
    s := round((a.originator_share * a.originator_rate + (100 - a.originator_share) * a.partner_rate) / 100, 2);
    IF NEW.blended_rate <> s OR l.rate <> s THEN
      RAISE EXCEPTION 'loan %: the borrower pays the blended rate % (para 17)', l.loan_no, s USING ERRCODE = '23514';
    END IF;
    IF NEW.status <> 'SANCTIONED' THEN RAISE EXCEPTION 'a co-lent loan starts at sanction' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  -- UPDATE: only the allocation moves, one way
  IF (to_jsonb(NEW) - 'status' - 'offered_on' - 'deadline' - 'decided_on' - 'decision_reason' - 'partner_loan_ref' - 'partner_principal'
      - 'transfer_lot_id' - 'decision_approval_id')
     <> (to_jsonb(OLD) - 'status' - 'offered_on' - 'deadline' - 'decided_on' - 'decision_reason' - 'partner_loan_ref' - 'partner_principal'
      - 'transfer_lot_id' - 'decision_approval_id') THEN
    RAISE EXCEPTION 'loan %: the co-lending terms are frozen at sanction', l.loan_no USING ERRCODE = '42501';
  END IF;
  IF OLD.status IN ('ACCEPTED','DECLINED','LAPSED','WITHDRAWN') THEN
    RAISE EXCEPTION 'loan %: the co-lending allocation is settled (%)', l.loan_no, OLD.status USING ERRCODE = '42501';
  END IF;
  IF OLD.status = 'SANCTIONED' AND NEW.status = 'OFFERED' THEN
    IF l.status <> 'ACTIVE' OR l.disbursed_on IS NULL THEN
      RAISE EXCEPTION 'loan %: the partner is offered its share once the loan is disbursed', l.loan_no USING ERRCODE = '23514';
    END IF;
    NEW.offered_on := l.disbursed_on;
    NEW.deadline := l.disbursed_on + 15;
    RETURN NEW;
  END IF;
  IF OLD.status IN ('SANCTIONED','OFFERED') AND NEW.status = 'WITHDRAWN' THEN NEW.decided_on := today; RETURN NEW; END IF;
  IF OLD.status = 'OFFERED' AND NEW.status = 'SANCTIONED' THEN          -- the disbursement was reversed: offered again when disbursed
    IF l.status <> 'SANCTIONED' THEN RAISE EXCEPTION 'loan %: the offer stands while the loan is disbursed', l.loan_no USING ERRCODE = '23514'; END IF;
    NEW.offered_on := NULL;
    NEW.deadline := NULL;
    RETURN NEW;
  END IF;
  IF OLD.status = 'OFFERED' AND NEW.status = 'LAPSED' THEN
    -- the day-end of the last day lapses it: no acceptance can follow once that day is closed
    IF today < OLD.deadline THEN RAISE EXCEPTION 'loan %: the offer is open until %', l.loan_no, OLD.deadline USING ERRCODE = '23514'; END IF;
    NEW.decided_on := today;
    RETURN NEW;
  END IF;
  IF OLD.status = 'OFFERED' AND NEW.status = 'DECLINED' THEN NEW.decided_on := today; RETURN NEW; END IF;
  IF OLD.status = 'OFFERED' AND NEW.status = 'ACCEPTED' THEN
    IF today > OLD.deadline THEN
      RAISE EXCEPTION 'loan %: the partner''s share is booked within 15 calendar days of disbursement (by %); the loan stays with the originator',
        l.loan_no, OLD.deadline USING ERRCODE = '23514';
    END IF;
    SELECT * INTO lot FROM ledger.transaction_lot WHERE id = NEW.transfer_lot_id;
    IF lot.lot_type IS DISTINCT FROM 'CO_LENDING_TRANSFER' THEN
      RAISE EXCEPTION 'loan %: the acceptance is recorded with its co-lending transfer', l.loan_no USING ERRCODE = '23514';
    END IF;
    NEW.decided_on := today;
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'loan %: co-lending % cannot become %', l.loan_no, OLD.status, NEW.status USING ERRCODE = '23514';
END $$;
CREATE TRIGGER co_lent_loan_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.co_lent_loan
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lent_loan();

-- The partner's share held on the contra head agrees with the loans accepted (a check for the invariants).
CREATE FUNCTION lending.co_lending_problems() RETURNS TABLE (branch_code text, kept numeric, ledger numeric) LANGUAGE sql STABLE AS $$
  WITH gl AS (SELECT coalesce((SELECT value FROM platform.system_property WHERE key = 'lending.co-lending.partner-share-gl'), '1105') AS code),
       kept AS (SELECT l.branch_code, sum(c.partner_principal) AS amount FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id
                 WHERE c.status = 'ACCEPTED' GROUP BY l.branch_code),
       booked AS (SELECT e.branch_code, sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END) AS amount
                    FROM ledger.account_entry e, gl WHERE e.gl_code = gl.code GROUP BY e.branch_code)
  SELECT coalesce(k.branch_code, b.branch_code), coalesce(k.amount, 0), coalesce(b.amount, 0)
    FROM kept k FULL JOIN booked b ON b.branch_code = k.branch_code
   WHERE coalesce(k.amount, 0) <> coalesce(b.amount, 0)
$$;
