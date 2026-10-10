-- V26: release of a co-applicant or guarantor from a loan (US-034 follow-up).
-- A party is never deleted: the row stays with who released it, when (the business date) and why, so the history of
-- who stood behind the loan is kept. From the release date the party drops out of customer.exposure (as co-applicant
-- or guarantor) and out of any joint reporting that reads lending.loan_party where released_on IS NULL. The borrower
-- cannot be released. Two checkers are needed when the loan is stressed (SMA or NPA); one otherwise.
ALTER TABLE lending.loan_party
  ADD COLUMN released_on         date,
  ADD COLUMN released_by         text,
  ADD COLUMN release_reason      text,
  ADD COLUMN release_approval_id uuid REFERENCES platform.approval_request(id),
  ADD CONSTRAINT loan_party_release_complete CHECK (
      (released_on IS NULL AND released_by IS NULL AND release_reason IS NULL AND release_approval_id IS NULL)
      OR (released_on IS NOT NULL AND released_by IS NOT NULL AND length(trim(release_reason)) > 0 AND role <> 'BORROWER'));
COMMENT ON COLUMN lending.loan_party.released_on IS
  'Business date from which the party no longer stands behind the loan; NULL while it does. Set once, never cleared.';

-- A row can still never be deleted, and only one change is allowed: setting the release columns on a co-applicant or
-- guarantor who is not yet released. Nothing else about a party changes.
CREATE OR REPLACE FUNCTION lending.guard_loan_party() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE borrower uuid; st text; no text; existing text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'loan parties cannot be removed; release the party instead' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.released_on IS NOT NULL THEN
      RAISE EXCEPTION 'this party was already released on %; a release is not undone', OLD.released_on USING ERRCODE = '42501';
    END IF;
    IF OLD.role = 'BORROWER' THEN
      RAISE EXCEPTION 'the borrower cannot be released from the loan' USING ERRCODE = '23514';
    END IF;
    IF NEW.released_on IS NULL
       OR (NEW.loan_id, NEW.customer_id, NEW.role, NEW.added_by, NEW.added_at) IS DISTINCT FROM (OLD.loan_id, OLD.customer_id, OLD.role, OLD.added_by, OLD.added_at) THEN
      RAISE EXCEPTION 'loan parties cannot be changed, only released' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
  END IF;
  SELECT customer_id INTO borrower FROM lending.loan_account WHERE id = NEW.loan_id;
  IF NEW.role = 'BORROWER' THEN
    IF NEW.customer_id <> borrower THEN
      RAISE EXCEPTION 'the borrower party must be the customer of the loan' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.customer_id = borrower THEN
    RAISE EXCEPTION 'a customer cannot be both borrower and % on the same loan', lower(replace(NEW.role, '_', '-')) USING ERRCODE = '23514';
  END IF;
  SELECT role INTO existing FROM lending.loan_party WHERE loan_id = NEW.loan_id AND customer_id = NEW.customer_id;
  IF existing IS NOT NULL THEN
    RAISE EXCEPTION 'this customer is already % on the loan; a customer holds one role per loan', existing USING ERRCODE = '23514';
  END IF;
  SELECT status, customer_no INTO st, no FROM customer.customer WHERE id = NEW.customer_id;
  IF st IS DISTINCT FROM 'ACTIVE' THEN
    RAISE EXCEPTION 'customer % is %; guarantors and co-applicants must be ACTIVE customers', no, st USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

-- Exposure counts only parties still standing behind the loan.
CREATE OR REPLACE VIEW customer.exposure AS
  SELECT c.id AS customer_id, c.exposure_limit,
         coalesce(sum(lending.loan_exposure(l.status, l.sanctioned_amount, l.principal_outstanding)) FILTER (WHERE p.role = 'BORROWER'), 0)     AS as_borrower,
         coalesce(sum(lending.loan_exposure(l.status, l.sanctioned_amount, l.principal_outstanding)) FILTER (WHERE p.role = 'CO_APPLICANT'), 0) AS as_co_applicant,
         coalesce(sum(lending.loan_exposure(l.status, l.sanctioned_amount, l.principal_outstanding)) FILTER (WHERE p.role = 'GUARANTOR'), 0)    AS as_guarantor,
         count(*) FILTER (WHERE p.role = 'BORROWER' AND l.status IN ('SANCTIONED','ACTIVE','FROZEN'))     AS loans_as_borrower,
         count(*) FILTER (WHERE p.role = 'CO_APPLICANT' AND l.status IN ('SANCTIONED','ACTIVE','FROZEN')) AS loans_as_co_applicant,
         count(*) FILTER (WHERE p.role = 'GUARANTOR' AND l.status IN ('SANCTIONED','ACTIVE','FROZEN'))    AS loans_as_guarantor
    FROM customer.customer c
    LEFT JOIN lending.loan_party p ON p.customer_id = c.id AND p.released_on IS NULL
    LEFT JOIN lending.loan_account l ON l.id = p.loan_id
   GROUP BY c.id, c.exposure_limit;

INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('LOAN_PARTY_RELEASE', 'RELEASE', NULL, 1),
  ('LOAN_PARTY_RELEASE', 'RELEASE_STRESSED', NULL, 2)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------------------
-- Text length limits (SEC-05). The API checks free-text fields by name (kernel TextLimits); these are the cheap database
-- backstops. NOT VALID: they bind new and changed rows without scanning (or failing on) rows written before.
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_party
  ADD CONSTRAINT loan_party_release_reason_length CHECK (release_reason IS NULL OR length(release_reason) <= 500);
ALTER TABLE platform.approval_decision
  ADD CONSTRAINT approval_decision_note_length CHECK (note IS NULL OR length(note) <= 1000) NOT VALID;
ALTER TABLE customer.customer
  ADD CONSTRAINT customer_display_name_length CHECK (length(display_name) <= 300) NOT VALID;
