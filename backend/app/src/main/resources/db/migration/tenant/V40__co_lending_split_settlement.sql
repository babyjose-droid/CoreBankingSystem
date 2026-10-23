-- Phase 5 (P5-2): the daily split of a co-lent loan between the lenders and settlement with the partner through escrow.
-- The loan engine services the whole loan at the blended rate; at each day-end, for every loan whose share the partner
-- holds, what the borrower paid since the partner took its share is divided:
--   * principal repaid by share (the partner's principal outstanding is its share of the loan's);
--   * interest received in the ratio share x rate (each lender earns its own rate on its own share);
--   * fees (without GST) and penal charges received, by share or kept by the originator, as the arrangement says;
--   * less the originator's servicing fee, accrued daily on the partner's principal outstanding.
-- The partner's part moves from the contra head and the income heads to "due to the partner" (2303, on the loan's
-- number). A receipt reversed lowers what the partner is owed, and the next split takes it back. A settlement pays what
-- is due through the escrow account (maker-checker), with a statement for the partner.
--   1. lending.co_lent_position: where the partner stands on a loan (cumulative), and what the borrower had paid
--      when the partner took its share (the baseline the split counts from).
--   2. lending.co_lending_split: each day's change, as posted (the statement lines).
--   3. lending.co_lending_settlement: payments to the partner, each settling the splits it lists.
--   4. lending.co_lending_problems(): the contra head and the partner payable agree with the splits.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Position of the partner on each loan it holds a share of
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.co_lent_position (
    loan_id            uuid PRIMARY KEY REFERENCES lending.co_lent_loan(loan_id),
    -- what the borrower had paid when the partner took its share: the split counts from here
    base_interest      numeric(18,2) NOT NULL,
    base_penal         numeric(18,2) NOT NULL,
    base_fees          jsonb NOT NULL DEFAULT '{}'::jsonb,              -- charge id -> amount paid
    as_of              date NOT NULL,                                    -- last day split (servicing fee accrued to it)
    principal          numeric(18,2) NOT NULL DEFAULT 0,                 -- cumulative, given to the partner
    interest           numeric(18,2) NOT NULL DEFAULT 0,
    fees               jsonb NOT NULL DEFAULT '{}'::jsonb,              -- income head -> amount
    penal              numeric(18,2) NOT NULL DEFAULT 0,
    servicing_accrued  numeric(28,10) NOT NULL DEFAULT 0,
    servicing          numeric(18,2) NOT NULL DEFAULT 0,
    final              boolean NOT NULL DEFAULT false,                   -- the loan is closed and fully divided
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CHECK (servicing_accrued >= 0 AND servicing >= 0)
);

CREATE FUNCTION lending.guard_co_lent_position() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c lending.co_lent_loan;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a co-lending position is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'INSERT' THEN
    SELECT * INTO c FROM lending.co_lent_loan WHERE loan_id = NEW.loan_id;
    IF c.status <> 'ACCEPTED' THEN
      RAISE EXCEPTION 'loan %: the partner holds no share (%)', NEW.loan_id, c.status USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.base_interest <> OLD.base_interest OR NEW.base_penal <> OLD.base_penal OR NEW.base_fees <> OLD.base_fees THEN
    RAISE EXCEPTION 'loan %: the baseline of the split never changes', OLD.loan_id USING ERRCODE = '42501';
  END IF;
  IF OLD.final THEN RAISE EXCEPTION 'loan %: the split is final', OLD.loan_id USING ERRCODE = '42501'; END IF;
  IF NEW.as_of < OLD.as_of THEN RAISE EXCEPTION 'loan %: the split never goes back in time', OLD.loan_id USING ERRCODE = '23514'; END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER co_lent_position_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.co_lent_position
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lent_position();

-- Loans accepted before this migration: the split counts from today (state as it stands).
INSERT INTO lending.co_lent_position (loan_id, base_interest, base_penal, base_fees, as_of)
SELECT c.loan_id,
       coalesce((SELECT sum((d->>'interestPaid')::numeric) FROM jsonb_array_elements(l.state->'demands') d), 0),
       coalesce((SELECT sum((x->>'paid')::numeric) FROM jsonb_array_elements(l.state->'charges') x WHERE x->>'kind' = 'PENAL'), 0),
       coalesce((SELECT jsonb_object_agg(x->>'id', (x->>'paid')::numeric) FROM jsonb_array_elements(l.state->'charges') x WHERE x->>'kind' = 'FEE'), '{}'::jsonb),
       (SELECT business_date FROM platform.business_day WHERE id = 1)
  FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id
 WHERE c.status = 'ACCEPTED';

-- ---------------------------------------------------------------------------------------------------------
-- 2. The day's split, as posted
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.co_lending_split (
    id             uuid PRIMARY KEY,
    loan_id        uuid NOT NULL REFERENCES lending.co_lent_position(loan_id),
    business_date  date NOT NULL,
    principal      numeric(18,2) NOT NULL,      -- changes: negative when a receipt was reversed
    interest       numeric(18,2) NOT NULL,
    fees           numeric(18,2) NOT NULL,
    penal          numeric(18,2) NOT NULL,
    servicing      numeric(18,2) NOT NULL,
    net            numeric(18,2) GENERATED ALWAYS AS (principal + interest + fees + penal - servicing) STORED,
    lot_id         uuid NOT NULL UNIQUE REFERENCES ledger.transaction_lot(id),
    settlement_id  uuid,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX co_lending_split_loan ON lending.co_lending_split (loan_id, business_date);
CREATE INDEX co_lending_split_unsettled ON lending.co_lending_split (loan_id) WHERE settlement_id IS NULL;

CREATE FUNCTION lending.guard_co_lending_split() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a co-lending split is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'INSERT' THEN
    IF (SELECT lot_type FROM ledger.transaction_lot WHERE id = NEW.lot_id) IS DISTINCT FROM 'CO_LENDING_SPLIT' THEN
      RAISE EXCEPTION 'a co-lending split is recorded with its CO_LENDING_SPLIT lot' USING ERRCODE = '23514';
    END IF;
    IF NEW.settlement_id IS NOT NULL THEN RAISE EXCEPTION 'a split is settled after it is made' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  IF OLD.settlement_id IS NOT NULL OR NEW.settlement_id IS NULL
     OR (to_jsonb(NEW) - 'settlement_id' - 'net') <> (to_jsonb(OLD) - 'settlement_id' - 'net') THEN   -- net: generated after BEFORE triggers
    RAISE EXCEPTION 'a co-lending split never changes; it is settled once' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER co_lending_split_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.co_lending_split
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lending_split();

-- ---------------------------------------------------------------------------------------------------------
-- 3. Settlement with the partner through escrow
-- ---------------------------------------------------------------------------------------------------------
CREATE SEQUENCE lending.co_lending_settlement_seq;
CREATE TABLE lending.co_lending_settlement (
    id                uuid PRIMARY KEY,
    settlement_no     text NOT NULL UNIQUE,
    arrangement_code  text NOT NULL REFERENCES lending.co_lending_arrangement(code),
    business_date     date NOT NULL,
    loans             int NOT NULL CHECK (loans > 0),
    principal         numeric(18,2) NOT NULL,
    interest          numeric(18,2) NOT NULL,
    fees              numeric(18,2) NOT NULL,
    penal             numeric(18,2) NOT NULL,
    servicing         numeric(18,2) NOT NULL,
    net               numeric(18,2) NOT NULL CHECK (net > 0),
    lot_id            uuid NOT NULL UNIQUE REFERENCES ledger.transaction_lot(id),
    approval_id       uuid REFERENCES platform.approval_request(id),
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (net = principal + interest + fees + penal - servicing)
);
ALTER TABLE lending.co_lending_split ADD FOREIGN KEY (settlement_id) REFERENCES lending.co_lending_settlement(id);

CREATE FUNCTION lending.guard_co_lending_settlement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'a co-lending settlement never changes' USING ERRCODE = '42501'; END IF;
  IF (SELECT lot_type FROM ledger.transaction_lot WHERE id = NEW.lot_id) IS DISTINCT FROM 'CO_LENDING_SETTLEMENT' THEN
    RAISE EXCEPTION 'a co-lending settlement is recorded with its CO_LENDING_SETTLEMENT lot' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER co_lending_settlement_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.co_lending_settlement
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lending_settlement();

-- A settlement pays exactly the splits it settles (checked when the transaction commits).
CREATE FUNCTION lending.check_co_lending_settlement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s lending.co_lending_settlement; t record;
BEGIN
  SELECT * INTO s FROM lending.co_lending_settlement WHERE id = NEW.id;
  SELECT count(DISTINCT x.loan_id) AS loans, coalesce(sum(x.principal), 0) AS principal, coalesce(sum(x.interest), 0) AS interest,
         coalesce(sum(x.fees), 0) AS fees, coalesce(sum(x.penal), 0) AS penal, coalesce(sum(x.servicing), 0) AS servicing
    INTO t FROM lending.co_lending_split x WHERE x.settlement_id = s.id;
  IF t.loans <> s.loans OR t.principal <> s.principal OR t.interest <> s.interest OR t.fees <> s.fees OR t.penal <> s.penal OR t.servicing <> s.servicing THEN
    RAISE EXCEPTION 'settlement %: its figures are not those of the splits it settles', s.settlement_no USING ERRCODE = '23514';
  END IF;
  IF EXISTS (SELECT 1 FROM lending.co_lending_split x JOIN lending.co_lent_loan c ON c.loan_id = x.loan_id
              WHERE x.settlement_id = s.id AND c.arrangement_code <> s.arrangement_code) THEN
    RAISE EXCEPTION 'settlement %: a loan of another arrangement', s.settlement_no USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER co_lending_settlement_figures AFTER INSERT ON lending.co_lending_settlement
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_co_lending_settlement();

-- ---------------------------------------------------------------------------------------------------------
-- 4. The books agree with the splits
-- ---------------------------------------------------------------------------------------------------------
DROP FUNCTION lending.co_lending_problems();
-- Per branch: the contra head holds the partner's principal outstanding (funded less repaid to it), and the partner
-- payable on the co-lent loans holds what was split and not yet settled.
CREATE FUNCTION lending.co_lending_problems() RETURNS TABLE (branch_code text, head text, kept numeric, ledger numeric) LANGUAGE sql STABLE AS $$
  WITH h AS (SELECT coalesce((SELECT value FROM platform.system_property WHERE key = 'lending.co-lending.partner-share-gl'), '1105') AS contra,
                    coalesce((SELECT value FROM platform.system_property WHERE key = 'lending.co-lending.partner-payable-gl'), '2303') AS payable),
       loans AS (SELECT c.loan_id, c.partner_principal, l.branch_code, l.loan_no FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id
                  WHERE c.status = 'ACCEPTED'),
       contra_kept AS (SELECT b.branch_code, sum(b.partner_principal - coalesce((SELECT sum(x.principal) FROM lending.co_lending_split x WHERE x.loan_id = b.loan_id), 0)) AS amount
                         FROM loans b GROUP BY b.branch_code),
       contra_booked AS (SELECT e.branch_code, sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END) AS amount
                           FROM ledger.account_entry e, h WHERE e.gl_code = h.contra GROUP BY e.branch_code),
       payable_kept AS (SELECT b.branch_code, sum(x.net) AS amount FROM loans b JOIN lending.co_lending_split x ON x.loan_id = b.loan_id
                         WHERE x.settlement_id IS NULL GROUP BY b.branch_code),
       payable_booked AS (SELECT e.branch_code, sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END) AS amount
                            FROM ledger.account_entry e, h WHERE e.gl_code = h.payable AND e.account_no IN (SELECT loan_no FROM loans) GROUP BY e.branch_code)
  SELECT coalesce(k.branch_code, b.branch_code), h.contra, coalesce(k.amount, 0), coalesce(b.amount, 0)
    FROM contra_kept k FULL JOIN contra_booked b ON b.branch_code = k.branch_code, h
   WHERE coalesce(k.amount, 0) <> coalesce(b.amount, 0)
  UNION ALL
  SELECT coalesce(k.branch_code, b.branch_code), h.payable, coalesce(k.amount, 0), coalesce(b.amount, 0)
    FROM payable_kept k FULL JOIN payable_booked b ON b.branch_code = k.branch_code, h
   WHERE coalesce(k.amount, 0) <> coalesce(b.amount, 0)
$$;
