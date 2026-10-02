-- P2-5: role amount limits (US-021), associates and customer exposure limits (US-034), consent records under
-- the DPDP Act 2023 (US-036) and KYC documents with metadata (US-032).

-- =========================================================================================================
-- 1. Role amount limits per transaction type (US-021)
-- =========================================================================================================
-- One row = what one role (a Keycloak realm role, e.g. MAKER, BRANCH_MANAGER) may put through in one
-- transaction and, optionally, in one business day. The most permissive of a user's roles applies (kernel
-- AmountLimits). Rows are maintained through maker-checker and are effective-dated; periods for one role and
-- transaction type can never overlap.
CREATE TABLE platform.amount_limit (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    role_name      text NOT NULL CHECK (role_name ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{1,63}$'),
    txn_type       text NOT NULL CHECK (txn_type IN ('LOAN_DISBURSEMENT','LOAN_REPAYMENT','LOAN_WAIVER','VOUCHER',
                                                     'LOAN_PRECLOSURE','FEE_WAIVER')),
    per_txn_max    platform.money NOT NULL CHECK (per_txn_max >= 0),
    per_day_max    platform.money,                       -- NULL = no cumulative limit
    effective_from date NOT NULL,
    effective_to   date,
    approval_id    uuid REFERENCES platform.approval_request(id),
    created_by     text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT amount_limit_day_covers_txn CHECK (per_day_max IS NULL OR per_day_max >= per_txn_max),
    CONSTRAINT amount_limit_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT amount_limit_no_overlap EXCLUDE USING gist
        (role_name WITH =, txn_type WITH =, daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') WITH &&)
);

-- A limit row is history: only its end date may change (a new row supersedes it); it is never deleted.
CREATE FUNCTION platform.guard_amount_limit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'amount limits are never deleted; end the period instead' USING ERRCODE = '42501';
  END IF;
  IF (to_jsonb(NEW) - 'effective_to') <> (to_jsonb(OLD) - 'effective_to') THEN
    RAISE EXCEPTION 'an amount limit cannot be edited; propose a new limit from a new date' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER amount_limit_guard BEFORE UPDATE OR DELETE ON platform.amount_limit
  FOR EACH ROW EXECUTE FUNCTION platform.guard_amount_limit();

-- What each user has put through (stage MAKE) or approved (stage APPROVE) per business day: the basis of the
-- cumulative per-day limit. Append-only.
CREATE TABLE platform.amount_limit_usage (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      text NOT NULL,
    txn_type      text NOT NULL,
    stage         text NOT NULL CHECK (stage IN ('MAKE','APPROVE')),
    business_date date NOT NULL,
    amount        platform.money NOT NULL CHECK (amount >= 0),
    reference     text,
    at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX amount_limit_usage_day ON platform.amount_limit_usage (lower(username), txn_type, stage, business_date);
CREATE TRIGGER amount_limit_usage_immutable BEFORE UPDATE OR DELETE ON platform.amount_limit_usage
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- Total used so far today. Takes a transaction-scoped lock on (user, type, stage, day), so two concurrent
-- transactions of one user cannot both pass the cumulative check: the second waits and sees the first.
CREATE FUNCTION platform.limit_used(p_user text, p_type text, p_stage text, p_date date) RETURNS numeric
LANGUAGE plpgsql AS $$
DECLARE used numeric;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(lower(p_user) || '|' || p_type || '|' || p_stage || '|' || p_date, 0));
  SELECT coalesce(sum(amount), 0) INTO used FROM platform.amount_limit_usage
   WHERE lower(username) = lower(p_user) AND txn_type = p_type AND stage = p_stage AND business_date = p_date;
  RETURN used;
END $$;

-- =========================================================================================================
-- 2. Associates, loan parties and customer exposure (US-034)
-- =========================================================================================================
ALTER TABLE customer.customer
  ADD COLUMN exposure_limit platform.money CHECK (exposure_limit IS NULL OR exposure_limit >= 0);
COMMENT ON COLUMN customer.customer.exposure_limit IS
  'Maximum sanctioned-undisbursed + principal outstanding as borrower; NULL = no limit. Set through maker-checker.';

-- Customer-level relationships. A nominee row carries a share; the shares of the ACTIVE nominees of one
-- account (loan_id) — or of the customer when no account is named — must total 100.
CREATE TABLE customer.relationship (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         uuid NOT NULL REFERENCES customer.customer(id),
    related_customer_id uuid NOT NULL REFERENCES customer.customer(id),
    relation_type       text NOT NULL CHECK (relation_type IN ('CO_APPLICANT','GUARANTOR','NOMINEE','AUTHORISED_SIGNATORY')),
    loan_id             uuid REFERENCES lending.loan_account(id),     -- the account a nomination applies to
    share_percent       numeric(5,2) CHECK (share_percent > 0 AND share_percent <= 100),
    status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ENDED')),
    approval_id         uuid REFERENCES platform.approval_request(id),
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    ended_at            timestamptz,
    ended_by            text,
    CONSTRAINT relationship_not_self CHECK (customer_id <> related_customer_id),
    CONSTRAINT relationship_share_only_for_nominee CHECK ((relation_type = 'NOMINEE') = (share_percent IS NOT NULL)),
    CONSTRAINT relationship_account_only_for_nominee CHECK (loan_id IS NULL OR relation_type = 'NOMINEE'),
    CONSTRAINT relationship_ended CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX relationship_active_unique
  ON customer.relationship (customer_id, related_customer_id, relation_type, loan_id) NULLS NOT DISTINCT
  WHERE status = 'ACTIVE';
CREATE INDEX relationship_related ON customer.relationship (related_customer_id) WHERE status = 'ACTIVE';

CREATE FUNCTION customer.guard_relationship() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c customer.customer%ROWTYPE; r customer.customer%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'relationships are never deleted; end them instead' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'ACTIVE' AND NEW.status = 'ENDED'
       AND (to_jsonb(NEW) - 'status' - 'ended_at' - 'ended_by') = (to_jsonb(OLD) - 'status' - 'ended_at' - 'ended_by') THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a relationship can only be ended, not edited' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO c FROM customer.customer WHERE id = NEW.customer_id;
  SELECT * INTO r FROM customer.customer WHERE id = NEW.related_customer_id;
  IF r.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'the related customer % is %; only ACTIVE customers can be linked', r.customer_no, r.status USING ERRCODE = '23514';
  END IF;
  IF NEW.relation_type = 'AUTHORISED_SIGNATORY' AND (c.customer_type <> 'NON_INDIVIDUAL' OR r.customer_type <> 'INDIVIDUAL') THEN
    RAISE EXCEPTION 'an authorised signatory is an individual acting for a non-individual customer' USING ERRCODE = '23514';
  END IF;
  IF NEW.loan_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM lending.loan_account l WHERE l.id = NEW.loan_id AND l.customer_id = NEW.customer_id) THEN
    RAISE EXCEPTION 'the account named in a nomination must belong to the customer' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER relationship_guard BEFORE INSERT OR UPDATE OR DELETE ON customer.relationship
  FOR EACH ROW EXECUTE FUNCTION customer.guard_relationship();

-- Checked at COMMIT, so a set of nominees can be replaced in one transaction.
CREATE FUNCTION customer.check_nominee_shares() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE total numeric;
BEGIN
  IF NEW.relation_type <> 'NOMINEE' THEN RETURN NULL; END IF;
  SELECT sum(share_percent) INTO total FROM customer.relationship
   WHERE customer_id = NEW.customer_id AND relation_type = 'NOMINEE' AND status = 'ACTIVE'
     AND loan_id IS NOT DISTINCT FROM NEW.loan_id;
  IF total IS NOT NULL AND total <> 100 THEN
    RAISE EXCEPTION 'nominee shares must total 100 percent; they total %', total USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER relationship_nominee_shares AFTER INSERT OR UPDATE ON customer.relationship
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION customer.check_nominee_shares();

-- Parties to a loan. The borrower row is written by the database when the loan is booked; a customer holds
-- one role per loan, so the borrower can never also be its guarantor or co-applicant.
CREATE TABLE lending.loan_party (
    loan_id     uuid NOT NULL REFERENCES lending.loan_account(id),
    customer_id uuid NOT NULL REFERENCES customer.customer(id),
    role        text NOT NULL CHECK (role IN ('BORROWER','CO_APPLICANT','GUARANTOR')),
    added_by    text NOT NULL,
    added_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (loan_id, customer_id)
);
CREATE UNIQUE INDEX loan_party_one_borrower ON lending.loan_party (loan_id) WHERE role = 'BORROWER';
CREATE INDEX loan_party_customer ON lending.loan_party (customer_id, role);

CREATE FUNCTION lending.guard_loan_party() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE borrower uuid; st text; no text; existing text;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'loan parties cannot be changed or removed' USING ERRCODE = '42501';
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
CREATE TRIGGER loan_party_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_party
  FOR EACH ROW EXECUTE FUNCTION lending.guard_loan_party();

CREATE FUNCTION lending.add_borrower_party() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO lending.loan_party (loan_id, customer_id, role, added_by)
  VALUES (NEW.id, NEW.customer_id, 'BORROWER', coalesce(NEW.created_by, 'system'));
  RETURN NULL;
END $$;
CREATE TRIGGER loan_borrower_party AFTER INSERT ON lending.loan_account
  FOR EACH ROW EXECUTE FUNCTION lending.add_borrower_party();
INSERT INTO lending.loan_party (loan_id, customer_id, role, added_by)
  SELECT id, customer_id, 'BORROWER', coalesce(created_by, 'system') FROM lending.loan_account;

-- What one loan adds to exposure: the sanctioned amount until it is disbursed, then the principal outstanding.
-- Closed, cancelled and written-off loans add nothing.
CREATE FUNCTION lending.loan_exposure(p_status text, p_sanctioned numeric, p_outstanding numeric) RETURNS numeric
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN p_status = 'SANCTIONED' THEN p_sanctioned
              WHEN p_status IN ('ACTIVE','FROZEN') THEN p_outstanding
              ELSE 0 END
$$;

-- Exposure by role. Borrower-level NPA (lending.borrower_class) still looks only at the borrower's own
-- accounts: a guarantor's or co-applicant's accounts are not downgraded, their exposure is only shown here.
CREATE VIEW customer.exposure AS
  SELECT c.id AS customer_id, c.exposure_limit,
         coalesce(sum(lending.loan_exposure(l.status, l.sanctioned_amount, l.principal_outstanding)) FILTER (WHERE p.role = 'BORROWER'), 0)     AS as_borrower,
         coalesce(sum(lending.loan_exposure(l.status, l.sanctioned_amount, l.principal_outstanding)) FILTER (WHERE p.role = 'CO_APPLICANT'), 0) AS as_co_applicant,
         coalesce(sum(lending.loan_exposure(l.status, l.sanctioned_amount, l.principal_outstanding)) FILTER (WHERE p.role = 'GUARANTOR'), 0)    AS as_guarantor,
         count(*) FILTER (WHERE p.role = 'BORROWER' AND l.status IN ('SANCTIONED','ACTIVE','FROZEN'))     AS loans_as_borrower,
         count(*) FILTER (WHERE p.role = 'CO_APPLICANT' AND l.status IN ('SANCTIONED','ACTIVE','FROZEN')) AS loans_as_co_applicant,
         count(*) FILTER (WHERE p.role = 'GUARANTOR' AND l.status IN ('SANCTIONED','ACTIVE','FROZEN'))    AS loans_as_guarantor
    FROM customer.customer c
    LEFT JOIN lending.loan_party p ON p.customer_id = c.id
    LEFT JOIN lending.loan_account l ON l.id = p.loan_id
   GROUP BY c.id, c.exposure_limit;

-- The exposure limit is checked when a loan is booked and again when it is disbursed (the limit may have been
-- lowered in between). The customer row is locked, so two loans booked at the same moment are checked one
-- after the other.
CREATE FUNCTION lending.check_exposure_limit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lim numeric; other numeric; mine numeric;
BEGIN
  SELECT exposure_limit INTO lim FROM customer.customer WHERE id = NEW.customer_id FOR NO KEY UPDATE;
  IF lim IS NULL THEN RETURN NULL; END IF;
  mine := CASE WHEN TG_OP = 'INSERT' THEN lending.loan_exposure(NEW.status, NEW.sanctioned_amount, NEW.sanctioned_amount)
               ELSE coalesce(NEW.disbursed_amount, NEW.sanctioned_amount) END;
  SELECT coalesce(sum(lending.loan_exposure(l.status, l.sanctioned_amount, l.principal_outstanding)), 0) INTO other
    FROM lending.loan_account l WHERE l.customer_id = NEW.customer_id AND l.id <> NEW.id;
  IF other + mine > lim THEN
    RAISE EXCEPTION 'customer exposure limit % would be exceeded: existing exposure % plus this loan %',
      trim_scale(lim), trim_scale(other), trim_scale(mine) USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER loan_exposure_on_booking AFTER INSERT ON lending.loan_account
  FOR EACH ROW EXECUTE FUNCTION lending.check_exposure_limit();
CREATE TRIGGER loan_exposure_on_disbursement AFTER UPDATE OF status ON lending.loan_account
  FOR EACH ROW WHEN (OLD.status = 'SANCTIONED' AND NEW.status = 'ACTIVE') EXECUTE FUNCTION lending.check_exposure_limit();

-- =========================================================================================================
-- 3. Consent and purpose records (DPDP Act 2023, US-036)
-- =========================================================================================================
INSERT INTO platform.enumeration (enum_type, code, label, sort_order) VALUES
  ('consent-purpose','LOAN_PROCESSING','Processing and servicing the loan',10),
  ('consent-purpose','KYC_VERIFICATION','KYC verification',20),
  ('consent-purpose','CREDIT_BUREAU_REPORTING','Reporting to credit information companies',30),
  ('consent-purpose','ACCOUNT_AGGREGATOR','Fetching data through an account aggregator',40),
  ('consent-purpose','MARKETING','Marketing and offers',50)
ON CONFLICT DO NOTHING;

-- V4 created customer.consent with a minimal shape; nothing wrote to it. Complete it here.
ALTER TABLE customer.consent RENAME COLUMN given_at TO granted_at;
ALTER TABLE customer.consent ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE customer.consent
  ADD COLUMN lawful_basis   text NOT NULL DEFAULT 'CONSENT' CHECK (lawful_basis IN ('CONSENT','LEGITIMATE_USE')),
  ADD COLUMN evidence_ref   text,                 -- OTP / e-sign transaction id, form scan reference, API request id
  ADD COLUMN expires_at     timestamptz,
  ADD COLUMN withdrawal_reason text,
  ADD COLUMN withdrawn_by   text,
  ADD COLUMN retained_for_legal_obligation boolean NOT NULL DEFAULT false,
  ADD COLUMN recorded_by    text NOT NULL DEFAULT 'system',
  ADD COLUMN recorded_at    timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT consent_channel CHECK (channel IN ('BRANCH','WEB','MOBILE_APP','API','PAPER','CALL_CENTRE')),
  ADD CONSTRAINT consent_expiry_after_grant CHECK (expires_at IS NULL OR expires_at > granted_at),
  ADD CONSTRAINT consent_withdrawal_complete CHECK ((withdrawn_at IS NULL) = (withdrawal_reason IS NULL)
                                                     AND (withdrawn_at IS NULL) = (withdrawn_by IS NULL)),
  ADD CONSTRAINT consent_withdrawal_after_grant CHECK (withdrawn_at IS NULL OR withdrawn_at >= granted_at),
  ADD CONSTRAINT consent_retained_only_when_withdrawn CHECK (NOT retained_for_legal_obligation OR withdrawn_at IS NOT NULL),
  ADD CONSTRAINT consent_notice_version CHECK (length(trim(notice_version)) > 0);
ALTER TABLE customer.consent ALTER COLUMN lawful_basis DROP DEFAULT;
ALTER TABLE customer.consent ALTER COLUMN recorded_by DROP DEFAULT;

-- Append-only history of what happened to each record.
CREATE TABLE customer.consent_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    consent_id  uuid NOT NULL REFERENCES customer.consent(id),
    customer_id uuid NOT NULL REFERENCES customer.customer(id),
    purpose     text NOT NULL,
    event       text NOT NULL CHECK (event IN ('GRANTED','WITHDRAWN')),
    at          timestamptz NOT NULL,
    actor       text NOT NULL,
    reason      text,
    retained_for_legal_obligation boolean NOT NULL DEFAULT false,
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consent_event_customer ON customer.consent_event (customer_id, id);
CREATE TRIGGER consent_event_immutable BEFORE UPDATE OR DELETE ON customer.consent_event
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- Purposes without which an existing loan cannot be serviced (tenant property consent.servicing-purposes).
CREATE FUNCTION customer.servicing_purposes() RETURNS text[] LANGUAGE sql STABLE AS $$
  SELECT array_agg(upper(replace(trim(x), '-', '_')))
    FROM unnest(string_to_array(coalesce(
           (SELECT value FROM platform.system_property WHERE key = 'consent.servicing-purposes'),
           'loan-processing,kyc-verification,credit-bureau-reporting'), ',')) x
   WHERE trim(x) <> ''
$$;

-- True while the lender must keep and use the customer's data to service a loan or to meet record-keeping
-- duties: the customer is a party to a loan that is sanctioned, live or written off, or that closed within the
-- retention period (tenant property consent.retention-years, default 5).
CREATE FUNCTION customer.has_retention_obligation(p_customer uuid, p_at timestamptz DEFAULT now()) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT EXISTS (
    SELECT 1 FROM lending.loan_party p JOIN lending.loan_account l ON l.id = p.loan_id
     WHERE p.customer_id = p_customer
       AND (l.status IN ('SANCTIONED','ACTIVE','FROZEN','WRITTEN_OFF')
            OR (l.status = 'CLOSED' AND coalesce(l.closed_on, p_at::date) > (p_at - make_interval(years =>
                 coalesce((SELECT value::int FROM platform.system_property WHERE key = 'consent.retention-years'), 5)))::date)))
$$;

-- A record is written once. The only later change is its withdrawal, which keeps the grant and adds who, when
-- and why. Withdrawing a servicing purpose while a retention obligation exists is recorded and flagged
-- retained_for_legal_obligation; marketing and other purposes simply end.
CREATE FUNCTION customer.guard_consent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'consent records are never deleted; record a withdrawal instead' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.withdrawn_at IS NOT NULL THEN
      RAISE EXCEPTION 'a consent record starts as granted; withdraw it afterwards' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.enumeration WHERE enum_type = 'consent-purpose' AND code = NEW.purpose AND active) THEN
      RAISE EXCEPTION 'unknown consent purpose %', NEW.purpose USING ERRCODE = '23514';
    END IF;
    IF NEW.granted_at > now() + interval '5 minutes' THEN
      RAISE EXCEPTION 'granted_at cannot be in the future' USING ERRCODE = '23514';
    END IF;
    IF (SELECT status FROM customer.customer WHERE id = NEW.customer_id) = 'ERASED' THEN
      RAISE EXCEPTION 'the customer has been erased' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.withdrawn_at IS NOT NULL THEN
    RAISE EXCEPTION 'consent % is already withdrawn', OLD.id USING ERRCODE = '42501';
  END IF;
  IF NEW.withdrawn_at IS NULL
     OR (to_jsonb(NEW) - 'withdrawn_at' - 'withdrawal_reason' - 'withdrawn_by' - 'retained_for_legal_obligation')
        <> (to_jsonb(OLD) - 'withdrawn_at' - 'withdrawal_reason' - 'withdrawn_by' - 'retained_for_legal_obligation') THEN
    RAISE EXCEPTION 'a consent record is immutable; only its withdrawal can be recorded' USING ERRCODE = '42501';
  END IF;
  IF OLD.lawful_basis <> 'CONSENT' THEN
    RAISE EXCEPTION 'a legitimate-use record is not a consent and cannot be withdrawn' USING ERRCODE = '23514';
  END IF;
  -- The database decides the flag; whatever the caller sent is ignored.
  NEW.retained_for_legal_obligation := OLD.purpose = ANY (customer.servicing_purposes())
                                       AND customer.has_retention_obligation(OLD.customer_id, NEW.withdrawn_at);
  RETURN NEW;
END $$;
CREATE TRIGGER consent_guard BEFORE INSERT OR UPDATE OR DELETE ON customer.consent
  FOR EACH ROW EXECUTE FUNCTION customer.guard_consent();
CREATE TRIGGER consent_no_truncate BEFORE TRUNCATE ON customer.consent
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_change();

CREATE FUNCTION customer.log_consent_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO customer.consent_event (consent_id, customer_id, purpose, event, at, actor)
    VALUES (NEW.id, NEW.customer_id, NEW.purpose, 'GRANTED', NEW.granted_at, NEW.recorded_by);
  ELSE
    INSERT INTO customer.consent_event (consent_id, customer_id, purpose, event, at, actor, reason, retained_for_legal_obligation)
    VALUES (NEW.id, NEW.customer_id, NEW.purpose, 'WITHDRAWN', NEW.withdrawn_at, NEW.withdrawn_by, NEW.withdrawal_reason,
            NEW.retained_for_legal_obligation);
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER consent_history AFTER INSERT OR UPDATE ON customer.consent
  FOR EACH ROW EXECUTE FUNCTION customer.log_consent_event();

-- A lawful basis for the purpose at that moment: a consent that is granted, not withdrawn and not expired, or
-- a legitimate-use record (DPDP s.7) that has not expired. A withdrawal takes effect from withdrawn_at.
CREATE FUNCTION customer.has_consent(p_customer uuid, p_purpose text, p_at timestamptz DEFAULT now()) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT EXISTS (
    SELECT 1 FROM customer.consent c
     WHERE c.customer_id = p_customer AND c.purpose = upper(replace(p_purpose, '-', '_'))
       AND c.granted_at <= p_at
       AND (c.withdrawn_at IS NULL OR c.withdrawn_at > p_at)
       AND (c.expires_at IS NULL OR c.expires_at > p_at))
$$;

-- Hook for the reporting module: may this customer's accounts go into a credit-bureau file? Yes with a current
-- bureau-reporting consent or legitimate-use record, and also after a withdrawal that was flagged
-- retained_for_legal_obligation (the lender's duty to furnish credit information on a live loan continues).
CREATE FUNCTION customer.bureau_reportable(p_customer uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT customer.has_consent(p_customer, 'CREDIT_BUREAU_REPORTING')
      OR EXISTS (SELECT 1 FROM customer.consent c
                  WHERE c.customer_id = p_customer AND c.purpose = 'CREDIT_BUREAU_REPORTING'
                    AND c.retained_for_legal_obligation
                    AND customer.has_retention_obligation(p_customer))
$$;

-- =========================================================================================================
-- 4. KYC documents with metadata (US-032)
-- =========================================================================================================
INSERT INTO platform.enumeration (enum_type, code, label, sort_order) VALUES
  ('kyc-document-type','PAN','PAN card',10),
  ('kyc-document-type','AADHAAR_MASKED','Aadhaar (masked, last four digits only)',20),
  ('kyc-document-type','PASSPORT','Passport',30),
  ('kyc-document-type','VOTER_ID','Voter ID',40),
  ('kyc-document-type','DRIVING_LICENCE','Driving licence',50),
  ('kyc-document-type','ADDRESS_PROOF','Address proof',60),
  ('kyc-document-type','PHOTO','Photograph',70),
  ('kyc-document-type','SIGNATURE','Signature',80),
  ('kyc-document-type','INCOME_PROOF','Income proof',90),
  ('kyc-document-type','REGISTRATION_CERTIFICATE','Registration or incorporation certificate',100),
  ('kyc-document-type','BOARD_RESOLUTION','Board resolution or authority letter',110)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE customer.document IS 'Superseded by customer.kyc_document (V17); never used.';

-- Metadata only: the file lives in the document store under store_key. The document number is never stored:
-- only its last four characters and, except for Aadhaar, a keyed hash. An Aadhaar number is never accepted,
-- hashed or stored in full; the uploaded copy must be the masked one and the verifier confirms that.
CREATE TABLE customer.kyc_document (
    id            uuid PRIMARY KEY,
    customer_id   uuid NOT NULL REFERENCES customer.customer(id),
    doc_type      text NOT NULL,
    number_last4  text CHECK (number_last4 ~ '^[A-Z0-9]{1,4}$'),
    number_hash   bytea CHECK (octet_length(number_hash) = 32),
    issue_date    date,
    expiry_date   date,
    status        text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','REJECTED')),
    status_reason text,
    verified_by   text,
    verified_at   timestamptz,
    masking_confirmed boolean NOT NULL DEFAULT false,
    store_key     text NOT NULL UNIQUE CHECK (store_key ~ '^tenants/[a-z][a-z0-9-]{2,30}/kyc/[0-9a-f-]{36}/[0-9a-f-]{36}$'),
    content_type  text NOT NULL CHECK (content_type IN ('application/pdf','image/jpeg','image/png')),
    size_bytes    bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 5242880),
    sha256        text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    uploaded_by   text NOT NULL,
    uploaded_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT kyc_dates CHECK (expiry_date IS NULL OR issue_date IS NULL OR expiry_date > issue_date),
    CONSTRAINT kyc_aadhaar_masked_only CHECK (doc_type <> 'AADHAAR_MASKED'
                                              OR (number_hash IS NULL AND (number_last4 IS NULL OR number_last4 ~ '^[0-9]{4}$'))),
    CONSTRAINT kyc_decided CHECK ((status = 'PENDING') = (verified_by IS NULL) AND (status = 'PENDING') = (verified_at IS NULL)),
    CONSTRAINT kyc_verifier_is_not_uploader CHECK (verified_by IS NULL OR lower(verified_by) <> lower(uploaded_by)),
    CONSTRAINT kyc_rejection_reason CHECK (status <> 'REJECTED' OR length(trim(coalesce(status_reason, ''))) > 0),
    CONSTRAINT kyc_aadhaar_masking_confirmed CHECK (NOT (doc_type = 'AADHAAR_MASKED' AND status = 'VERIFIED') OR masking_confirmed)
);
CREATE INDEX kyc_document_customer ON customer.kyc_document (customer_id, doc_type);

CREATE FUNCTION customer.guard_kyc_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'KYC documents are not deleted; reject the document or upload a new one' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'PENDING' THEN
      RAISE EXCEPTION 'a document is uploaded as PENDING and verified by someone else' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.enumeration WHERE enum_type = 'kyc-document-type' AND code = NEW.doc_type AND active) THEN
      RAISE EXCEPTION 'unknown KYC document type %', NEW.doc_type USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'document % is already %', OLD.id, OLD.status USING ERRCODE = '42501';
  END IF;
  IF (to_jsonb(NEW) - 'status' - 'status_reason' - 'verified_by' - 'verified_at' - 'masking_confirmed')
     <> (to_jsonb(OLD) - 'status' - 'status_reason' - 'verified_by' - 'verified_at' - 'masking_confirmed') THEN
    RAISE EXCEPTION 'document metadata is immutable; only the verification result can be recorded' USING ERRCODE = '42501';
  END IF;
  IF NEW.status = 'VERIFIED' AND NEW.expiry_date IS NOT NULL AND NEW.expiry_date < current_date THEN
    RAISE EXCEPTION 'the document expired on % and cannot be verified', NEW.expiry_date USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER kyc_document_guard BEFORE INSERT OR UPDATE OR DELETE ON customer.kyc_document
  FOR EACH ROW EXECUTE FUNCTION customer.guard_kyc_document();

-- The documents a customer needs before KYC is VERIFIED: tenant property kyc.required-documents, a comma list
-- of document types ("pan,address-proof,photo" and "PAN,ADDRESS_PROOF,PHOTO" mean the same).
CREATE FUNCTION customer.kyc_required_documents() RETURNS text[] LANGUAGE sql STABLE AS $$
  SELECT coalesce(array_agg(DISTINCT upper(replace(trim(x), '-', '_'))), ARRAY['PAN','ADDRESS_PROOF','PHOTO'])
    FROM unnest(string_to_array(coalesce(
           (SELECT value FROM platform.system_property WHERE key = 'kyc.required-documents'),
           'pan,address-proof,photo'), ',')) x
   WHERE trim(x) <> ''
$$;

CREATE FUNCTION customer.kyc_complete(p_customer uuid, p_on date DEFAULT current_date) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT NOT EXISTS (
    SELECT 1 FROM unnest(customer.kyc_required_documents()) req
     WHERE NOT EXISTS (SELECT 1 FROM customer.kyc_document d
                        WHERE d.customer_id = p_customer AND d.doc_type = req AND d.status = 'VERIFIED'
                          AND (d.expiry_date IS NULL OR d.expiry_date >= p_on)))
$$;

-- kyc_status can be VERIFIED only while the required set is verified and unexpired.
CREATE FUNCTION customer.guard_kyc_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.kyc_status = 'VERIFIED' AND (TG_OP = 'INSERT' OR OLD.kyc_status <> 'VERIFIED')
     AND NOT customer.kyc_complete(NEW.id) THEN
    RAISE EXCEPTION 'KYC cannot be VERIFIED: the required documents (%) are not all verified and unexpired',
      array_to_string(customer.kyc_required_documents(), ', ') USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER customer_kyc_status_guard BEFORE INSERT OR UPDATE OF kyc_status ON customer.customer
  FOR EACH ROW EXECUTE FUNCTION customer.guard_kyc_status();

CREATE FUNCTION customer.refresh_kyc_status(p_customer uuid, p_on date DEFAULT current_date) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE cur text; nxt text;
BEGIN
  SELECT kyc_status INTO cur FROM customer.customer WHERE id = p_customer FOR NO KEY UPDATE;
  IF customer.kyc_complete(p_customer, p_on) THEN
    nxt := 'VERIFIED';
  ELSIF cur = 'VERIFIED' THEN
    nxt := CASE WHEN EXISTS (SELECT 1 FROM customer.kyc_document d
                              WHERE d.customer_id = p_customer AND d.status = 'VERIFIED' AND d.expiry_date < p_on
                                AND d.doc_type = ANY (customer.kyc_required_documents()))
                THEN 'EXPIRED' ELSE 'PENDING' END;
  ELSE
    nxt := cur;
  END IF;
  IF nxt IS DISTINCT FROM cur THEN
    UPDATE customer.customer SET kyc_status = nxt, version = version + 1 WHERE id = p_customer;
  END IF;
  RETURN nxt;
END $$;

CREATE FUNCTION customer.kyc_document_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM customer.refresh_kyc_status(NEW.customer_id);
  RETURN NULL;
END $$;
CREATE TRIGGER kyc_document_status AFTER INSERT OR UPDATE ON customer.kyc_document
  FOR EACH ROW EXECUTE FUNCTION customer.kyc_document_changed();

-- For a daily job: customers whose verified documents have run out move to EXPIRED. Returns how many changed.
CREATE FUNCTION customer.expire_kyc(p_on date DEFAULT current_date) RETURNS int LANGUAGE plpgsql AS $$
DECLARE c record; n int := 0;
BEGIN
  FOR c IN SELECT id FROM customer.customer WHERE kyc_status = 'VERIFIED' AND NOT customer.kyc_complete(id, p_on) LOOP
    PERFORM customer.refresh_kyc_status(c.id, p_on);
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;
