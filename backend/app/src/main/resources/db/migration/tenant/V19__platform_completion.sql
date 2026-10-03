-- P2-7 platform completion: custom fields (US-014), support access (US-007), posting during end of day
-- (US-111), job catalogue (US-112) with report scheduling (US-113), usage figures for metering (US-004).

-- =========================================================================================================
-- 1. Custom fields (US-014)
-- =========================================================================================================
-- A definition says which extra attributes a customer, a loan account or a loan product may carry. Values live
-- in a jsonb column `custom` on the entity and are checked here, whoever writes them.
CREATE TABLE platform.custom_field (
    entity      text NOT NULL CHECK (entity IN ('CUSTOMER','LOAN_ACCOUNT','LOAN_PRODUCT')),
    key         text NOT NULL CHECK (key ~ '^[a-z][A-Za-z0-9]{1,39}$'),          -- camelCase, as in the API
    label       text NOT NULL CHECK (length(trim(label)) BETWEEN 1 AND 80),
    data_type   text NOT NULL CHECK (data_type IN ('TEXT','NUMBER','DATE','BOOLEAN','ENUM')),
    enum_type   text,                                   -- platform.enumeration type that lists the choices (ENUM only)
    widget      text CHECK (widget IN ('TEXT','TEXTAREA','NUMBER','DATE','CHECKBOX','SELECT','RADIO')),
    required    boolean NOT NULL DEFAULT false,
    regex       text,                                   -- TEXT only; the whole value must match
    min_value   numeric,                                -- NUMBER: lowest value; TEXT: shortest length
    max_value   numeric,                                -- NUMBER: highest value; TEXT: longest length
    pii         boolean NOT NULL DEFAULT false,         -- personal data: stored encrypted, returned masked
    active      boolean NOT NULL DEFAULT true,
    sort_order  int NOT NULL DEFAULT 0,
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (entity, key),
    CONSTRAINT custom_field_enum_type CHECK ((data_type = 'ENUM') = (enum_type IS NOT NULL)),
    CONSTRAINT custom_field_regex_text_only CHECK (regex IS NULL OR data_type = 'TEXT'),
    CONSTRAINT custom_field_bounds_type CHECK ((min_value IS NULL AND max_value IS NULL) OR data_type IN ('TEXT','NUMBER')),
    CONSTRAINT custom_field_bounds_order CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value),
    CONSTRAINT custom_field_text_bounds CHECK (data_type <> 'TEXT' OR (coalesce(min_value, 0) >= 0 AND coalesce(max_value, 0) >= 0
                                               AND coalesce(min_value, 0) = trunc(coalesce(min_value, 0))
                                               AND coalesce(max_value, 0) = trunc(coalesce(max_value, 0)))),
    CONSTRAINT custom_field_pii_text_only CHECK (NOT pii OR data_type = 'TEXT')
);

CREATE FUNCTION platform.guard_custom_field() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'custom field %.% cannot be deleted; deactivate it', OLD.entity, OLD.key USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'UPDATE' AND (NEW.entity <> OLD.entity OR NEW.key <> OLD.key OR NEW.data_type <> OLD.data_type
                           OR NEW.enum_type IS DISTINCT FROM OLD.enum_type OR NEW.pii <> OLD.pii) THEN
    RAISE EXCEPTION 'the type and the personal-data flag of custom field %.% cannot change: values are already stored',
      OLD.entity, OLD.key USING ERRCODE = '23514';
  END IF;
  IF NEW.regex IS NOT NULL THEN
    BEGIN
      PERFORM '' ~ ('^(?:' || NEW.regex || ')$');
    EXCEPTION WHEN invalid_regular_expression THEN
      RAISE EXCEPTION 'custom field %.%: the pattern is not a valid regular expression', NEW.entity, NEW.key USING ERRCODE = '23514';
    END;
  END IF;
  IF NEW.enum_type IS NOT NULL AND NOT EXISTS (SELECT 1 FROM platform.enumeration e WHERE e.enum_type = NEW.enum_type) THEN
    RAISE EXCEPTION 'custom field %.%: enumeration type % has no values', NEW.entity, NEW.key, NEW.enum_type USING ERRCODE = '23514';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER custom_field_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.custom_field
  FOR EACH ROW EXECUTE FUNCTION platform.guard_custom_field();

-- Checks the custom values of one record. On an update, values that did not change are not judged again, so a
-- field that was deactivated or tightened later does not block unrelated edits; new and changed values are.
-- A personal-data field holds {"enc": <base64 ciphertext>, "mask": <masked text>} and nothing else: the clear
-- value is validated by the application before it is encrypted and never reaches the database.
CREATE FUNCTION platform.validate_custom(p_entity text, p_custom jsonb, p_old jsonb DEFAULT NULL) RETURNS void
LANGUAGE plpgsql STABLE AS $$
DECLARE k text; v jsonb; f platform.custom_field%ROWTYPE; t text; s text; n numeric; missing text;
BEGIN
  IF p_custom IS NULL OR jsonb_typeof(p_custom) <> 'object' THEN
    RAISE EXCEPTION 'custom values must be a JSON object' USING ERRCODE = '23514';
  END IF;
  FOR k, v IN SELECT e.key, e.value FROM jsonb_each(p_custom) e LOOP
    SELECT * INTO f FROM platform.custom_field c WHERE c.entity = p_entity AND c.key = k;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'custom field "%" is not defined for %', k, p_entity USING ERRCODE = '23514';
    END IF;
    CONTINUE WHEN p_old IS NOT NULL AND (p_old -> k) IS NOT DISTINCT FROM v;
    IF NOT f.active THEN
      RAISE EXCEPTION 'custom field "%" is no longer in use', k USING ERRCODE = '23514';
    END IF;
    t := jsonb_typeof(v);
    IF t = 'null' THEN
      RAISE EXCEPTION 'custom field "%": leave the field out instead of sending null', k USING ERRCODE = '23514';
    END IF;
    IF f.pii THEN
      IF t <> 'object' OR (SELECT count(*) FROM jsonb_object_keys(v)) <> 2
         OR jsonb_typeof(v -> 'enc') IS DISTINCT FROM 'string' OR jsonb_typeof(v -> 'mask') IS DISTINCT FROM 'string'
         OR (v ->> 'enc') !~ '^[A-Za-z0-9+/]{43,}={0,2}$' THEN
        RAISE EXCEPTION 'custom field "%" holds personal data and must be stored encrypted', k USING ERRCODE = '23514';
      END IF;
      CONTINUE;
    END IF;
    s := v #>> '{}';
    IF f.data_type = 'TEXT' THEN
      IF t <> 'string' THEN RAISE EXCEPTION 'custom field "%" must be text', k USING ERRCODE = '23514'; END IF;
      IF length(s) > 2000 THEN RAISE EXCEPTION 'custom field "%" is longer than 2000 characters', k USING ERRCODE = '23514'; END IF;
      IF f.min_value IS NOT NULL AND length(s) < f.min_value THEN
        RAISE EXCEPTION 'custom field "%" must have at least % characters', k, trunc(f.min_value) USING ERRCODE = '23514';
      END IF;
      IF f.max_value IS NOT NULL AND length(s) > f.max_value THEN
        RAISE EXCEPTION 'custom field "%" must have at most % characters', k, trunc(f.max_value) USING ERRCODE = '23514';
      END IF;
      IF f.regex IS NOT NULL AND s !~ ('^(?:' || f.regex || ')$') THEN
        RAISE EXCEPTION 'custom field "%" does not have the expected format', k USING ERRCODE = '23514';
      END IF;
    ELSIF f.data_type = 'NUMBER' THEN
      IF t <> 'number' THEN RAISE EXCEPTION 'custom field "%" must be a number', k USING ERRCODE = '23514'; END IF;
      n := s::numeric;
      IF f.min_value IS NOT NULL AND n < f.min_value THEN
        RAISE EXCEPTION 'custom field "%" must be at least %', k, f.min_value USING ERRCODE = '23514';
      END IF;
      IF f.max_value IS NOT NULL AND n > f.max_value THEN
        RAISE EXCEPTION 'custom field "%" must be at most %', k, f.max_value USING ERRCODE = '23514';
      END IF;
    ELSIF f.data_type = 'BOOLEAN' THEN
      IF t <> 'boolean' THEN RAISE EXCEPTION 'custom field "%" must be true or false', k USING ERRCODE = '23514'; END IF;
    ELSIF f.data_type = 'DATE' THEN
      IF t <> 'string' OR s !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
        RAISE EXCEPTION 'custom field "%" must be a date as YYYY-MM-DD', k USING ERRCODE = '23514';
      END IF;
      BEGIN
        PERFORM s::date;
      EXCEPTION WHEN others THEN
        RAISE EXCEPTION 'custom field "%" must be a date as YYYY-MM-DD', k USING ERRCODE = '23514';
      END;
    ELSE  -- ENUM
      IF t <> 'string' OR NOT EXISTS (SELECT 1 FROM platform.enumeration e
                                      WHERE e.enum_type = f.enum_type AND e.code = s AND e.active) THEN
        RAISE EXCEPTION 'custom field "%" must be one of the active values of %', k, f.enum_type USING ERRCODE = '23514';
      END IF;
    END IF;
  END LOOP;
  SELECT string_agg(c.key, ', ' ORDER BY c.key) INTO missing FROM platform.custom_field c
   WHERE c.entity = p_entity AND c.active AND c.required AND NOT (p_custom ? c.key);
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'required custom field(s) missing for %: %', p_entity, missing USING ERRCODE = '23514';
  END IF;
END $$;

ALTER TABLE customer.customer    ADD COLUMN custom jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(custom) = 'object');
ALTER TABLE lending.loan_account ADD COLUMN custom jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(custom) = 'object');
ALTER TABLE lending.loan_product ADD COLUMN custom jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(custom) = 'object');

-- Runs at commit and looks at the row as it is then, so a record may be inserted first and given its custom
-- values later in the same transaction. Arguments: the entity and the name of the key column.
CREATE FUNCTION platform.check_custom_values() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cur jsonb;
BEGIN
  EXECUTE format('SELECT custom FROM %s WHERE %I::text = $1', TG_RELID::regclass, TG_ARGV[1])
     INTO cur USING to_jsonb(NEW) ->> TG_ARGV[1];
  IF cur IS NOT NULL THEN
    PERFORM platform.validate_custom(TG_ARGV[0], cur, CASE WHEN TG_OP = 'UPDATE' THEN OLD.custom END);
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER customer_custom_values AFTER INSERT OR UPDATE OF custom ON customer.customer
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_custom_values('CUSTOMER', 'id');
CREATE CONSTRAINT TRIGGER loan_account_custom_values AFTER INSERT OR UPDATE OF custom ON lending.loan_account
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_custom_values('LOAN_ACCOUNT', 'id');
CREATE CONSTRAINT TRIGGER loan_product_custom_values AFTER INSERT OR UPDATE OF custom ON lending.loan_product
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_custom_values('LOAN_PRODUCT', 'code');

-- =========================================================================================================
-- 2. Time-boxed support access (US-007, ADR-016)
-- =========================================================================================================
-- The record lives in the tenant's own database: the tenant owns who was let in, and the request filter reads
-- it on every support call. A platform engineer asks; only a tenant user can approve, reject or revoke.
CREATE TABLE platform.support_access (
    id               uuid PRIMARY KEY,
    engineer_subject text NOT NULL CHECK (length(engineer_subject) BETWEEN 1 AND 100),   -- `sub` of the platform-realm token
    engineer         text NOT NULL CHECK (engineer ~ '^[A-Za-z0-9._@-]{2,80}$'),
    reason           text NOT NULL CHECK (length(trim(reason)) BETWEEN 10 AND 500),
    ticket           text NOT NULL CHECK (ticket ~ '^[A-Za-z0-9][A-Za-z0-9._/#-]{1,39}$'),
    scope            text NOT NULL DEFAULT 'READ_ONLY' CHECK (scope = 'READ_ONLY'),
    duration_minutes int  NOT NULL CHECK (duration_minutes BETWEEN 15 AND 480),            -- at most 8 hours
    requested_at     timestamptz NOT NULL DEFAULT now(),
    status           text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','REJECTED','REVOKED')),
    decided_by       text,
    decided_at       timestamptz,
    decision_note    text,
    expires_at       timestamptz,
    revoked_by       text,
    revoked_at       timestamptz,
    revoke_reason    text,
    CONSTRAINT support_decided CHECK ((status = 'REQUESTED') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_at IS NULL)),
    CONSTRAINT support_approved_expires CHECK ((status IN ('APPROVED','REVOKED')) = (expires_at IS NOT NULL)),
    CONSTRAINT support_rejected_note CHECK (status <> 'REJECTED' OR length(trim(coalesce(decision_note, ''))) > 0),
    CONSTRAINT support_revoked CHECK ((status = 'REVOKED') = (revoked_by IS NOT NULL) AND (revoked_by IS NULL) = (revoked_at IS NULL)),
    CONSTRAINT support_max_eight_hours CHECK (expires_at IS NULL OR expires_at <= decided_at + interval '8 hours')
);
CREATE INDEX support_access_engineer ON platform.support_access (engineer_subject, requested_at DESC);

CREATE FUNCTION platform.guard_support_access() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'support access records cannot be deleted' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'REQUESTED' THEN
      RAISE EXCEPTION 'support access starts as a request; the tenant admin decides' USING ERRCODE = '23514';
    END IF;
    NEW.requested_at := now();
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.engineer_subject <> OLD.engineer_subject OR NEW.engineer <> OLD.engineer OR NEW.reason <> OLD.reason
     OR NEW.ticket <> OLD.ticket OR NEW.scope <> OLD.scope OR NEW.duration_minutes <> OLD.duration_minutes
     OR NEW.requested_at <> OLD.requested_at THEN
    RAISE EXCEPTION 'a support access request cannot be edited' USING ERRCODE = '42501';
  END IF;
  IF OLD.status = 'REQUESTED' AND NEW.status = 'APPROVED' THEN
    IF OLD.requested_at < now() - interval '24 hours' THEN
      RAISE EXCEPTION 'this request is older than 24 hours; the engineer must ask again' USING ERRCODE = '23514';
    END IF;
    IF NEW.decided_by IS NULL OR NEW.decided_by LIKE 'support:%' THEN
      RAISE EXCEPTION 'support access is approved by a tenant user' USING ERRCODE = '23514';
    END IF;
    NEW.decided_at := now();
    NEW.expires_at := now() + make_interval(mins => OLD.duration_minutes);     -- never taken from the caller
    NEW.revoked_by := NULL; NEW.revoked_at := NULL; NEW.revoke_reason := NULL;
    RETURN NEW;
  END IF;
  IF OLD.status = 'REQUESTED' AND NEW.status = 'REJECTED' THEN
    IF NEW.decided_by IS NULL OR NEW.decided_by LIKE 'support:%' THEN
      RAISE EXCEPTION 'support access is rejected by a tenant user' USING ERRCODE = '23514';
    END IF;
    NEW.decided_at := now();
    NEW.expires_at := NULL;
    RETURN NEW;
  END IF;
  IF OLD.status = 'APPROVED' AND NEW.status = 'REVOKED' THEN
    IF NEW.revoked_by IS NULL OR NEW.revoked_by LIKE 'support:%' THEN
      RAISE EXCEPTION 'support access is revoked by a tenant user' USING ERRCODE = '23514';
    END IF;
    IF NEW.decided_by <> OLD.decided_by OR NEW.decided_at <> OLD.decided_at OR NEW.expires_at <> OLD.expires_at
       OR NEW.decision_note IS DISTINCT FROM OLD.decision_note THEN
      RAISE EXCEPTION 'the approval of a support access cannot be edited' USING ERRCODE = '42501';
    END IF;
    NEW.revoked_at := now();
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'support access % is % and cannot become %', OLD.id, OLD.status, NEW.status USING ERRCODE = '23514';
END $$;
CREATE TRIGGER support_access_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.support_access
  FOR EACH ROW EXECUTE FUNCTION platform.guard_support_access();

-- What a grant is right now: an approved grant past its time shows as EXPIRED without anyone updating it.
CREATE VIEW platform.support_access_status AS
  SELECT s.*, CASE WHEN s.status = 'APPROVED' AND s.expires_at <= now() THEN 'EXPIRED'
                   WHEN s.status = 'REQUESTED' AND s.requested_at < now() - interval '24 hours' THEN 'LAPSED'
                   ELSE s.status END AS effective_status
    FROM platform.support_access s;

-- True only for the engineer the grant was given to, while it is approved, not revoked and not expired.
CREATE FUNCTION platform.support_access_active(p_id uuid, p_subject text, p_at timestamptz DEFAULT now()) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT EXISTS (SELECT 1 FROM platform.support_access s
                  WHERE s.id = p_id AND s.engineer_subject = p_subject AND s.status = 'APPROVED'
                    AND s.decided_at <= p_at AND s.expires_at > p_at)
$$;

-- A support engineer acts under the login 'support:<username>'. No staff profile may use that prefix, so a
-- tenant user can never be mistaken for one.
ALTER TABLE platform.staff_user ADD CONSTRAINT staff_username_not_support CHECK (username NOT LIKE 'support:%' AND user_id NOT LIKE 'support:%');

-- Branch scope: an engineer with a grant in force sees every branch (what they may do with it is limited by
-- the API: read-only calls, masked personal data). Everything else is unchanged from V13.
CREATE OR REPLACE FUNCTION platform.visible_branches(p_user text) RETURNS TABLE (branch_code text)
LANGUAGE sql STABLE AS $$
  WITH u AS (
    SELECT user_id, home_branch, all_branches FROM platform.staff_user
     WHERE (user_id = p_user OR lower(username) = lower(p_user)) AND status = 'ACTIVE'
     ORDER BY (user_id = p_user) DESC LIMIT 1)
  SELECT b.code FROM platform.branch b, u WHERE u.all_branches
  UNION
  SELECT u.home_branch FROM u
  UNION
  SELECT s.branch_code FROM platform.staff_branch_scope s JOIN u ON u.user_id = s.user_id
  UNION
  SELECT m.branch_code FROM platform.staff_branch_set_scope ss JOIN u ON u.user_id = ss.user_id
    JOIN platform.branch_set_member m ON m.set_code = ss.set_code
  UNION
  SELECT b.code FROM platform.branch b
   WHERE p_user LIKE 'support:%'
     AND EXISTS (SELECT 1 FROM platform.support_access g
                  WHERE 'support:' || g.engineer = p_user AND g.status = 'APPROVED' AND g.expires_at > now())
$$;

CREATE OR REPLACE FUNCTION platform.sees_all_branches(p_user text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce((SELECT all_branches FROM platform.staff_user
                    WHERE (user_id = p_user OR lower(username) = lower(p_user)) AND status = 'ACTIVE'
                    ORDER BY (user_id = p_user) DESC LIMIT 1), false)
      OR (p_user LIKE 'support:%'
          AND EXISTS (SELECT 1 FROM platform.support_access g
                       WHERE 'support:' || g.engineer = p_user AND g.status = 'APPROVED' AND g.expires_at > now()))
$$;

-- =========================================================================================================
-- 3. Posting during end of day (US-111, ADR-015)
-- =========================================================================================================
-- 3a. The ledger never takes an entry for a closed date or for a date that has not opened yet. Once a tenant has
--     completed an end of day, every date before the current business date is closed: the dates end of day closed
--     and the holidays in between. (Until now only the application checked the date of a lot. Before the first
--     end of day, earlier dates stay open so that opening balances can be loaded.)
CREATE FUNCTION ledger.guard_lot_date() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cur date; closed date;
BEGIN
  SELECT max(r.business_date) INTO closed FROM platform.eod_run r WHERE r.status IN ('COMPLETED','COMPLETED_WITH_EXCEPTIONS');
  SELECT business_date INTO cur FROM platform.business_day WHERE id = 1;
  IF closed IS NOT NULL AND (NEW.business_date <= closed OR NEW.business_date < cur) THEN
    RAISE EXCEPTION 'business date % is closed; nothing more can be posted to it', NEW.business_date USING ERRCODE = '23514';
  END IF;
  IF cur IS NOT NULL AND NEW.business_date > cur THEN
    RAISE EXCEPTION 'business date % has not opened yet (the business date is %)', NEW.business_date, cur USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER lot_date_guard BEFORE INSERT ON ledger.transaction_lot FOR EACH ROW EXECUTE FUNCTION ledger.guard_lot_date();

-- 3b. Receipts that arrive after the cut-off (end of day has started) are accepted here and booked on the next
--     business date, once it has opened. Nothing is posted to the ledger while the day is being closed.
CREATE TABLE lending.deferred_receipt (
    id                    uuid PRIMARY KEY,
    loan_id               uuid NOT NULL REFERENCES lending.loan_account(id),
    amount                platform.money NOT NULL CHECK (amount > 0),
    mode                  text,
    reference             text,
    received_at           timestamptz NOT NULL DEFAULT now(),        -- when the money was reported to us
    received_by           text NOT NULL,
    idempotency_key       text,
    cutoff_business_date  date NOT NULL,                             -- the date that was being closed
    expected_posting_date date NOT NULL,                             -- the next business date at the time of receipt
    status                text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPLIED','FAILED','CANCELLED')),
    applied_at            timestamptz,
    posting_date          date,                                      -- business date it was booked on
    value_date            date,                                      -- always the posting date (ADR-015)
    loan_txn_id           uuid REFERENCES lending.loan_txn(id),
    attempts              int NOT NULL DEFAULT 0,
    error                 text,
    resolved_by           text,
    resolution_note       text,
    CONSTRAINT deferred_applied CHECK ((status = 'APPLIED') = (loan_txn_id IS NOT NULL)
                                       AND (status = 'APPLIED') = (posting_date IS NOT NULL)
                                       AND (status = 'APPLIED') = (applied_at IS NOT NULL)),
    CONSTRAINT deferred_value_is_posting CHECK (value_date IS NOT DISTINCT FROM posting_date),
    CONSTRAINT deferred_posting_after_cutoff CHECK (posting_date IS NULL OR posting_date > cutoff_business_date),
    CONSTRAINT deferred_failed_has_error CHECK (status <> 'FAILED' OR error IS NOT NULL),
    CONSTRAINT deferred_cancelled_has_note CHECK (status <> 'CANCELLED' OR (resolved_by IS NOT NULL AND length(trim(coalesce(resolution_note, ''))) > 0))
);
CREATE UNIQUE INDEX deferred_receipt_idempotency ON lending.deferred_receipt (received_by, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX deferred_receipt_open ON lending.deferred_receipt (received_at) WHERE status IN ('PENDING','FAILED');
CREATE INDEX deferred_receipt_loan ON lending.deferred_receipt (loan_id, received_at);

CREATE FUNCTION lending.guard_deferred_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d platform.business_day%ROWTYPE; loan_status text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'a receipt accepted during end of day cannot be deleted' USING ERRCODE = '42501';
  END IF;
  -- FOR SHARE: the business day cannot change under us (end of day takes the row exclusively to move it).
  SELECT * INTO d FROM platform.business_day WHERE id = 1 FOR SHARE;
  IF TG_OP = 'INSERT' THEN
    IF d.status NOT IN ('EOD_RUNNING','EOD_FAILED') THEN
      RAISE EXCEPTION 'the business day is %; post the receipt directly', d.status USING ERRCODE = '23514';
    END IF;
    IF NEW.status <> 'PENDING' THEN
      RAISE EXCEPTION 'a receipt accepted during end of day starts as PENDING' USING ERRCODE = '23514';
    END IF;
    SELECT status INTO loan_status FROM lending.loan_account WHERE id = NEW.loan_id;
    IF loan_status IS DISTINCT FROM 'ACTIVE' THEN
      RAISE EXCEPTION 'the loan is % and cannot take a receipt', coalesce(loan_status, 'missing') USING ERRCODE = '23514';
    END IF;
    NEW.received_at := now();
    NEW.cutoff_business_date := d.business_date;                       -- never taken from the caller
    NEW.expected_posting_date := platform.next_working_day(d.business_date);
    NEW.attempts := 0;
    RETURN NEW;
  END IF;
  IF NEW.id <> OLD.id OR NEW.loan_id <> OLD.loan_id OR NEW.amount <> OLD.amount OR NEW.mode IS DISTINCT FROM OLD.mode
     OR NEW.reference IS DISTINCT FROM OLD.reference OR NEW.received_at <> OLD.received_at OR NEW.received_by <> OLD.received_by
     OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key OR NEW.cutoff_business_date <> OLD.cutoff_business_date
     OR NEW.expected_posting_date <> OLD.expected_posting_date THEN
    RAISE EXCEPTION 'a receipt accepted during end of day cannot be edited' USING ERRCODE = '42501';
  END IF;
  IF OLD.status IN ('PENDING','FAILED') AND NEW.status = 'APPLIED' THEN
    IF d.status <> 'OPEN' OR d.business_date <= OLD.cutoff_business_date THEN
      RAISE EXCEPTION 'the receipt is booked once the next business date has opened (the day is % on %)', d.status, d.business_date
        USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM lending.loan_txn t
                    WHERE t.id = NEW.loan_txn_id AND t.loan_id = OLD.loan_id AND t.txn_type = 'REPAYMENT'
                      AND t.amount = OLD.amount AND t.business_date = d.business_date AND t.value_date = d.business_date) THEN
      RAISE EXCEPTION 'the receipt must point at its repayment, booked and valued on %', d.business_date USING ERRCODE = '23514';
    END IF;
    NEW.posting_date := d.business_date;
    NEW.value_date := d.business_date;
    NEW.applied_at := now();
    NEW.attempts := OLD.attempts + 1;
    NEW.error := NULL;
    RETURN NEW;
  END IF;
  IF OLD.status IN ('PENDING','FAILED') AND NEW.status = 'FAILED' THEN
    NEW.attempts := OLD.attempts + 1;
    RETURN NEW;
  END IF;
  IF OLD.status = 'FAILED' AND NEW.status = 'PENDING' THEN          -- put back in the queue after the cause was fixed
    RETURN NEW;
  END IF;
  IF OLD.status = 'FAILED' AND NEW.status = 'CANCELLED' THEN        -- refunded or handled outside; needs who and why
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'receipt % is % and cannot become %', OLD.id, OLD.status, NEW.status USING ERRCODE = '23514';
END $$;
CREATE TRIGGER deferred_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.deferred_receipt
  FOR EACH ROW EXECUTE FUNCTION lending.guard_deferred_receipt();

-- One repayment can settle one receipt only.
CREATE UNIQUE INDEX deferred_receipt_txn ON lending.deferred_receipt (loan_txn_id) WHERE loan_txn_id IS NOT NULL;

-- =========================================================================================================
-- 4. Job catalogue (US-112) and report scheduling (US-113)
-- =========================================================================================================
CREATE TABLE platform.job_definition (
    code        text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{2,60}$'),
    name        text NOT NULL,
    kind        text NOT NULL CHECK (kind IN ('REPORT','DASHBOARD_REFRESH','KYC_EXPIRY','CONSENT_EXPIRY','EXPORT_CLEANUP',
                                              'USAGE_SNAPSHOT','DEFERRED_RECEIPTS')),
    schedule    text CHECK (schedule IS NULL OR schedule ~ '^\S+( \S+){5}$'),   -- cron: second minute hour day month weekday, IST
    enabled     boolean NOT NULL DEFAULT false,
    parameters  jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(parameters) = 'object'),
    built_in    boolean NOT NULL DEFAULT false,
    updated_by  text NOT NULL DEFAULT 'system',
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION platform.guard_job_definition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'job % cannot be deleted; disable it', OLD.code USING ERRCODE = '42501';
  END IF;
  IF NEW.code <> OLD.code OR NEW.kind <> OLD.kind OR NEW.built_in <> OLD.built_in THEN
    RAISE EXCEPTION 'the code and kind of job % cannot change', OLD.code USING ERRCODE = '42501';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER job_definition_guard BEFORE UPDATE OR DELETE ON platform.job_definition
  FOR EACH ROW EXECUTE FUNCTION platform.guard_job_definition();

CREATE TABLE platform.job_run (
    id            uuid PRIMARY KEY,
    job_code      text NOT NULL REFERENCES platform.job_definition(code),
    origin        text NOT NULL CHECK (origin IN ('SCHEDULE','MANUAL')),
    scheduled_for timestamptz,                         -- the cron fire time this run answers; NULL for a manual run
    requested_by  text NOT NULL,
    status        text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    processed     int NOT NULL DEFAULT 0 CHECK (processed >= 0),
    failed        int NOT NULL DEFAULT 0 CHECK (failed >= 0),
    error         text,
    artifact      jsonb CHECK (artifact IS NULL OR jsonb_typeof(artifact) = 'object'),
    CONSTRAINT job_run_scheduled CHECK ((origin = 'SCHEDULE') = (scheduled_for IS NOT NULL)),
    CONSTRAINT job_run_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT job_run_failed_has_error CHECK (status <> 'FAILED' OR error IS NOT NULL)
);
-- A fire time is answered once, whichever instance gets there first.
CREATE UNIQUE INDEX job_run_once_per_fire ON platform.job_run (job_code, scheduled_for) WHERE scheduled_for IS NOT NULL;
-- A job never runs twice at the same time (a manual run against a scheduled one, or two instances).
CREATE UNIQUE INDEX job_single_running ON platform.job_run (job_code) WHERE status = 'RUNNING';
CREATE INDEX job_run_recent ON platform.job_run (started_at DESC);

CREATE FUNCTION platform.guard_job_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'job runs cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'RUNNING' THEN RAISE EXCEPTION 'a job run starts as RUNNING' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  IF OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED','FAILED') AND NEW.id = OLD.id AND NEW.job_code = OLD.job_code
     AND NEW.origin = OLD.origin AND NEW.scheduled_for IS NOT DISTINCT FROM OLD.scheduled_for
     AND NEW.requested_by = OLD.requested_by AND NEW.started_at = OLD.started_at THEN
    NEW.finished_at := coalesce(NEW.finished_at, now());
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'job run % is finished and cannot be changed', OLD.id USING ERRCODE = '42501';
END $$;
CREATE TRIGGER job_run_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.job_run
  FOR EACH ROW EXECUTE FUNCTION platform.guard_job_run();

INSERT INTO platform.job_definition (code, name, kind, schedule, enabled, parameters, built_in) VALUES
  ('DEFERRED_RECEIPTS', 'Book receipts accepted during end of day', 'DEFERRED_RECEIPTS', '0 */5 * * * *', true, '{}', true),
  ('DASHBOARD_REFRESH', 'Dashboard metrics refresh', 'DASHBOARD_REFRESH', '0 */15 * * * *', true, '{}', true),
  ('KYC_EXPIRY', 'KYC expiry', 'KYC_EXPIRY', '0 30 0 * * *', true, '{}', true),
  ('CONSENT_EXPIRY', 'Consent expiry', 'CONSENT_EXPIRY', '0 45 0 * * *', true, '{}', true),
  ('EXPORT_CLEANUP', 'Remove report files past their retention period', 'EXPORT_CLEANUP', '0 0 2 * * *', true, '{}', true),
  ('USAGE_SNAPSHOT', 'Usage metering snapshot', 'USAGE_SNAPSHOT', '0 55 23 * * *', true, '{}', true);
-- One job per report, off until someone schedules it (maker-checker).
INSERT INTO platform.job_definition (code, name, kind, enabled, parameters, built_in)
SELECT 'REPORT_' || d.code, 'Scheduled report: ' || d.name, 'REPORT', false, jsonb_build_object('reportCode', d.code), true
  FROM reporting.report_definition d;

-- A report added by a later migration gets its job the same way.
CREATE FUNCTION platform.add_report_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO platform.job_definition (code, name, kind, enabled, parameters, built_in)
  VALUES ('REPORT_' || NEW.code, 'Scheduled report: ' || NEW.name, 'REPORT', false, jsonb_build_object('reportCode', NEW.code), true)
  ON CONFLICT (code) DO NOTHING;
  RETURN NEW;
END $$;
CREATE TRIGGER report_definition_job AFTER INSERT ON reporting.report_definition
  FOR EACH ROW EXECUTE FUNCTION platform.add_report_job();

-- ---- Report files past retention (US-113: generated files retained per policy) ---------------------------
-- A finished run stays the record of who exported what. The only later change is the note that its file was
-- removed under the retention policy.
ALTER TABLE reporting.report_run ADD COLUMN artifact_purged_at timestamptz;
CREATE OR REPLACE FUNCTION reporting.guard_report_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'report runs cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED','FAILED')
     AND NEW.id = OLD.id AND NEW.report_code = OLD.report_code AND NEW.requested_by = OLD.requested_by
     AND NEW.requested_at = OLD.requested_at AND NEW.parameters = OLD.parameters AND NEW.business_date = OLD.business_date
     AND NEW.artifact_purged_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.status = 'COMPLETED' AND OLD.artifact_purged_at IS NULL AND NEW.artifact_purged_at IS NOT NULL
     AND (to_jsonb(NEW) - 'artifact_purged_at') = (to_jsonb(OLD) - 'artifact_purged_at') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'report run % is finished and cannot be changed', OLD.id USING ERRCODE = '42501';
END $$;

-- Completed runs whose file is older than the retention period and still stored.
CREATE FUNCTION reporting.purgeable_runs(p_retention_days int)
RETURNS TABLE (id uuid, artifact_key text, rejected_count int) LANGUAGE sql STABLE AS $$
  SELECT r.id, r.artifact_key, r.rejected_count FROM reporting.report_run r
   WHERE r.status = 'COMPLETED' AND r.artifact_purged_at IS NULL
     AND r.finished_at < now() - make_interval(days => greatest(p_retention_days, 1))
   ORDER BY r.finished_at
$$;

-- ---- Dashboard metrics refresh ---------------------------------------------------------------------------
-- Whole-book figures per business date, kept so the dashboard can show a trend. The live dashboard (V16) stays
-- limited to the caller's branches; this history is for users who see every branch.
CREATE TABLE reporting.dashboard_snapshot (
    business_date         date PRIMARY KEY,
    refreshed_at          timestamptz NOT NULL DEFAULT now(),
    active_loans          bigint NOT NULL,
    portfolio_outstanding numeric NOT NULL,
    overdue_amount        numeric NOT NULL,
    overdue_loans         bigint NOT NULL,
    gross_npa             numeric NOT NULL,
    npa_loans             bigint NOT NULL,
    disbursed             numeric NOT NULL,
    collected             numeric NOT NULL,
    pending_approvals     bigint NOT NULL
);
CREATE FUNCTION reporting.refresh_dashboard_snapshot() RETURNS date LANGUAGE plpgsql AS $$
DECLARE d date;
BEGIN
  SELECT business_date INTO d FROM platform.business_day WHERE id = 1;
  IF d IS NULL THEN RETURN NULL; END IF;
  INSERT INTO reporting.dashboard_snapshot AS s (business_date, active_loans, portfolio_outstanding, overdue_amount, overdue_loans,
                                                 gross_npa, npa_loans, disbursed, collected, pending_approvals)
  SELECT d,
         count(*) FILTER (WHERE l.status IN ('ACTIVE','FROZEN')),
         coalesce(sum(l.principal_outstanding) FILTER (WHERE l.status IN ('ACTIVE','FROZEN')), 0),
         coalesce(sum(l.overdue_amount) FILTER (WHERE l.status IN ('ACTIVE','FROZEN')), 0),
         count(*) FILTER (WHERE l.status IN ('ACTIVE','FROZEN') AND l.overdue_amount > 0),
         coalesce(sum(l.principal_outstanding) FILTER (WHERE l.status IN ('ACTIVE','FROZEN') AND reporting.is_npa(l.asset_class)), 0),
         count(*) FILTER (WHERE l.status IN ('ACTIVE','FROZEN') AND reporting.is_npa(l.asset_class)),
         (SELECT coalesce(sum(t.amount), 0) FROM lending.loan_txn t
           WHERE t.business_date = d AND t.reversed_by IS NULL AND t.txn_type = 'DISBURSEMENT'),
         (SELECT coalesce(sum(t.amount), 0) FROM lending.loan_txn t
           WHERE t.business_date = d AND t.reversed_by IS NULL AND t.txn_type IN ('REPAYMENT','PREPAYMENT','PRECLOSURE','CANCELLATION')),
         (SELECT count(*) FROM platform.approval_request r WHERE r.status = 'PENDING')
    FROM lending.loan_account l
  ON CONFLICT (business_date) DO UPDATE SET refreshed_at = now(), active_loans = EXCLUDED.active_loans,
      portfolio_outstanding = EXCLUDED.portfolio_outstanding, overdue_amount = EXCLUDED.overdue_amount,
      overdue_loans = EXCLUDED.overdue_loans, gross_npa = EXCLUDED.gross_npa, npa_loans = EXCLUDED.npa_loans,
      disbursed = EXCLUDED.disbursed, collected = EXCLUDED.collected, pending_approvals = EXCLUDED.pending_approvals;
  RETURN d;
END $$;

-- ---- Consent expiry --------------------------------------------------------------------------------------
-- customer.has_consent already treats a consent past its expiry as not in force. This records the expiry once
-- in the consent history, so the trail shows when the consent ended and who can be asked to renew it.
ALTER TABLE customer.consent_event DROP CONSTRAINT consent_event_event_check;
ALTER TABLE customer.consent_event ADD CONSTRAINT consent_event_event_check CHECK (event IN ('GRANTED','WITHDRAWN','EXPIRED'));
CREATE FUNCTION customer.expire_consents(p_at timestamptz DEFAULT now()) RETURNS int LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  INSERT INTO customer.consent_event (consent_id, customer_id, purpose, event, at, actor, reason)
  SELECT c.id, c.customer_id, c.purpose, 'EXPIRED', c.expires_at, 'system', 'validity period ended'
    FROM customer.consent c
   WHERE c.expires_at IS NOT NULL AND c.expires_at <= p_at AND c.withdrawn_at IS NULL
     AND NOT EXISTS (SELECT 1 FROM customer.consent_event e WHERE e.consent_id = c.id AND e.event = 'EXPIRED');
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

-- =========================================================================================================
-- 5. Usage figures for metering (US-004). The control plane stores them; the tenant database only counts.
-- =========================================================================================================
CREATE FUNCTION platform.usage_snapshot()
RETURNS TABLE (active_loans bigint, active_customers bigint, staff_users bigint, database_bytes bigint)
LANGUAGE sql STABLE AS $$
  SELECT (SELECT count(*) FROM lending.loan_account WHERE status IN ('ACTIVE','FROZEN')),
         (SELECT count(*) FROM customer.customer WHERE status = 'ACTIVE'),
         (SELECT count(*) FROM platform.staff_user WHERE status = 'ACTIVE'),
         pg_database_size(current_database())
$$;

-- =========================================================================================================
-- 6. Approval rules for the new maker-checker entities (one checker, as the '*' rule already gives; listed so
--    the tenant can raise them).
-- =========================================================================================================
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('CUSTOM_FIELD', '*', NULL, 1),
  ('JOB_SCHEDULE', '*', NULL, 1),
  ('LOAN_PRODUCT_CUSTOM', '*', NULL, 1)
ON CONFLICT DO NOTHING;
