-- P2-3: loan amendments (rate reset, tenure, EMI, due day) and restructuring, with a history of every change.
-- The engine state (LoanAccount.Snapshot JSON) carries the current rate, the restructured flag and the specified
-- period; the columns below copy them for queries and reports.

-- ---------------------------------------------------------------------------------------------------------
-- Amendment and restructure history. One row per applied change, linked to the loan transaction (which holds the
-- state before it, for reversal) and to the maker-checker request.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_amendment (
    id               uuid PRIMARY KEY,
    loan_id          uuid NOT NULL REFERENCES lending.loan_account(id),
    seq              int NOT NULL CHECK (seq >= 1),
    txn_id           uuid NOT NULL UNIQUE REFERENCES lending.loan_txn(id),
    kind             text NOT NULL CHECK (kind IN ('RATE_CHANGE','TENURE_CHANGE','EMI_CHANGE','DUE_DAY_CHANGE','RESTRUCTURE')),
    parameters       jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'object'),   -- the request as proposed
    emi_before       platform.money NOT NULL,
    emi_after        platform.money NOT NULL CHECK (emi_after > 0),
    tenure_before    int NOT NULL CHECK (tenure_before >= 0),     -- instalments still to be demanded
    tenure_after     int NOT NULL CHECK (tenure_after >= 1),
    rate_before      platform.rate NOT NULL,
    rate_after       platform.rate NOT NULL CHECK (rate_after >= 0),
    maturity_before  date,
    maturity_after   date NOT NULL,
    interest_before  platform.money NOT NULL,                    -- interest still to be demanded
    interest_after   platform.money NOT NULL,
    proposed_figures jsonb,                                      -- what the checker saw when it was proposed
    applied_figures  jsonb NOT NULL,                             -- what was applied (engine re-run at approval)
    differs_from_proposal boolean NOT NULL DEFAULT false,        -- the loan changed between proposal and approval
    approval_id      uuid NOT NULL REFERENCES platform.approval_request(id),
    made_by          text NOT NULL,
    checked_by       text NOT NULL,
    business_date    date NOT NULL,
    reason           text,
    reversed_by      uuid REFERENCES lending.loan_txn(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (loan_id, seq),
    CONSTRAINT amendment_four_eyes CHECK (made_by <> checked_by),
    CONSTRAINT restructure_has_no_reversal CHECK (kind <> 'RESTRUCTURE' OR reversed_by IS NULL)
);
CREATE INDEX loan_amendment_loan ON lending.loan_amendment (loan_id, seq DESC);

-- History is append-only; the only change allowed is recording, once, that the amendment was reversed.
CREATE FUNCTION lending.guard_loan_amendment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'loan amendments cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.reversed_by IS NULL AND NEW.reversed_by IS NOT NULL
     AND (to_jsonb(NEW) - 'reversed_by') = (to_jsonb(OLD) - 'reversed_by') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'loan amendment % is immutable', OLD.id USING ERRCODE = '42501';
END $$;
CREATE TRIGGER loan_amendment_guard BEFORE UPDATE OR DELETE ON lending.loan_amendment
  FOR EACH ROW EXECUTE FUNCTION lending.guard_loan_amendment();

-- ---------------------------------------------------------------------------------------------------------
-- Denormalised figures on the account (from the engine state, written by LoanStore.save).
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_account
  ADD COLUMN current_rate          platform.rate,             -- rate now charged (rate column = rate as sanctioned)
  ADD COLUMN restructured_on       date,                      -- latest restructuring
  ADD COLUMN restructure_count     int NOT NULL DEFAULT 0 CHECK (restructure_count >= 0),
  ADD COLUMN upgrade_not_before    date,                      -- earliest end of the specified period; NULL once upgraded
  ADD COLUMN restructure_defaulted boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT restructure_fields_together CHECK ((restructured_on IS NULL) = (restructure_count = 0)),
  ADD CONSTRAINT monitoring_needs_restructure CHECK (upgrade_not_before IS NULL OR restructured_on IS NOT NULL);
UPDATE lending.loan_account SET current_rate = rate WHERE current_rate IS NULL;

-- Restructured accounts (regulatory return on restructured advances; SMA/NPA reports).
CREATE VIEW lending.restructured_loan AS
  SELECT l.id, l.loan_no, l.branch_code, l.customer_id, l.restructured_on, l.restructure_count, l.asset_class,
         l.npa_since, l.upgrade_not_before, l.restructure_defaulted, l.principal_outstanding, l.current_rate,
         l.upgrade_not_before IS NOT NULL AS under_monitoring
    FROM lending.loan_account l
   WHERE l.restructured_on IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------
-- Maker-checker: an amendment needs one checker; a restructure needs two (RBI framework: a resolution plan is a
-- credit decision, taken above the level that sanctions ordinary servicing changes).
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('LOAN_AMENDMENT', 'AMEND', NULL, 1),
  ('LOAN_RESTRUCTURE', 'RESTRUCTURE', NULL, 2)
ON CONFLICT DO NOTHING;
