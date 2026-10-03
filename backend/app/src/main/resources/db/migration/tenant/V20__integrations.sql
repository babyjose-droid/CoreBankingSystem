-- P2-2 integrations: provider framework, payouts (US-051), gateway collections (US-073), e-mandates and NACH
-- (US-070 – US-072), signed outbound webhooks (US-121), API clients (US-120), SMS and e-mail (US-123), and the
-- small additions the LOS flow needs (US-124).
--
-- Everything lives in the schema "integration". Account numbers, provider secrets, signing secrets, message
-- recipients and message texts are stored only as ciphertext written by the application with the tenant's data
-- key (ADR-013); the database keeps the last four characters where staff need to recognise an account.
-- Status lifecycles are enforced here as well as in the application (integration-core Lifecycle).

CREATE SCHEMA IF NOT EXISTS integration;

-- ---------------------------------------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------------------------------------
-- Tenant properties used by the integrations (all optional; the default applies when the key is absent):
--   payout.failure-action                 PROPOSE | PARK        what happens to a staff-approved disbursement whose payout failed
--   payout.max-send-attempts              1..20   (8)
--   collections.max-back-value-days       0..31   (3)
--   collections.link-validity-hours       1..720  (72)
--   nach.presentation.lead-days           0..10   (2)     working days between presentation and settlement
--   nach.max-presentations-per-demand     1..10   (3)
--   nach.representation.gap-days          1..30   (3)     working days from a bounce to the next settlement date
--   nach.format / nach.encoding           GENERIC / FIXED | CSV
--   nach.utility-code / nach.sponsor-bank-code            defaults for new mandates
--   webhook.max-attempts                  1..30   (12)
--   webhook.secret-overlap-hours          0..168  (24)
--   notify.rate-limit-per-minute          1..6000 (60)
--   notify.max-attempts                   1..20   (5)
--   notify.due-reminder.lead-days         0..15   (3)
CREATE FUNCTION integration.property(p_key text, p_default text) RETURNS text LANGUAGE sql STABLE AS $$
  SELECT coalesce((SELECT nullif(trim(value), '') FROM platform.system_property WHERE key = p_key), p_default)
$$;

-- A whole-number property within [p_min, p_max]; anything else (missing, not a number, out of range) is the default.
CREATE FUNCTION integration.int_property(p_key text, p_default int, p_min int, p_max int) RETURNS int LANGUAGE plpgsql STABLE AS $$
DECLARE v text := integration.property(p_key, NULL); n int;
BEGIN
  IF v IS NULL OR v !~ '^[0-9]{1,6}$' THEN RETURN p_default; END IF;
  n := v::int;
  IF n < p_min OR n > p_max THEN RETURN p_default; END IF;
  RETURN n;
END $$;

-- The date n working days after d, by the tenant's holiday calendar (n = 0 gives d itself).
CREATE FUNCTION integration.working_days_after(d date, n int) RETURNS date LANGUAGE plpgsql STABLE AS $$
DECLARE x date := d;
BEGIN
  IF n < 0 OR n > 60 THEN RAISE EXCEPTION 'n must be 0 to 60' USING ERRCODE = '22023'; END IF;
  FOR i IN 1..n LOOP x := platform.next_working_day(x); END LOOP;
  RETURN x;
END $$;

-- Allowed status changes, the same table as integration-core Lifecycle.
CREATE FUNCTION integration.transition_ok(p_entity text, p_from text, p_to text) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
  SELECT (p_entity, p_from, p_to) IN (
    ('PAYOUT','INITIATED','ON_HOLD'), ('PAYOUT','INITIATED','SENT'), ('PAYOUT','INITIATED','SUCCESS'),
    ('PAYOUT','INITIATED','FAILED'), ('PAYOUT','INITIATED','CANCELLED'),
    ('PAYOUT','ON_HOLD','INITIATED'), ('PAYOUT','ON_HOLD','CANCELLED'),
    ('PAYOUT','SENT','SUCCESS'), ('PAYOUT','SENT','FAILED'), ('PAYOUT','SENT','RETURNED'),
    ('PAYOUT','SUCCESS','RETURNED'),
    ('ORDER','CREATED','PAID'), ('ORDER','CREATED','FAILED'), ('ORDER','CREATED','EXPIRED'), ('ORDER','CREATED','CANCELLED'),
    ('ORDER','FAILED','PAID'), ('ORDER','EXPIRED','PAID'),
    ('MANDATE','DRAFT','SUBMITTED'), ('MANDATE','DRAFT','CANCELLED'),
    ('MANDATE','SUBMITTED','ACTIVE'), ('MANDATE','SUBMITTED','REJECTED'), ('MANDATE','SUBMITTED','CANCELLED'),
    ('MANDATE','ACTIVE','SUSPENDED'), ('MANDATE','ACTIVE','CANCELLED'), ('MANDATE','ACTIVE','EXPIRED'),
    ('MANDATE','SUSPENDED','ACTIVE'), ('MANDATE','SUSPENDED','CANCELLED'), ('MANDATE','SUSPENDED','EXPIRED'),
    ('PRESENTATION','GENERATED','SUCCESS'), ('PRESENTATION','GENERATED','BOUNCED'), ('PRESENTATION','GENERATED','WITHDRAWN'),
    ('DELIVERY','PENDING','RETRY'), ('DELIVERY','PENDING','DELIVERED'), ('DELIVERY','PENDING','DEAD'),
    ('DELIVERY','RETRY','RETRY'), ('DELIVERY','RETRY','DELIVERED'), ('DELIVERY','RETRY','DEAD'),
    ('DELIVERY','DEAD','PENDING'),
    ('PAYMENT','RECEIVED','POSTED'), ('PAYMENT','RECEIVED','UNMATCHED'), ('PAYMENT','UNMATCHED','RECEIVED'),
    ('PAYMENT','UNMATCHED','REFUND_DUE'), ('PAYMENT','FAILED','RECEIVED'))
$$;

-- Trigger: rows are never deleted; status follows the lifecycle named by the first argument; the columns named
-- by the remaining arguments never change.
CREATE FUNCTION integration.guard_row() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o jsonb; n jsonb; col text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '% rows cannot be deleted', TG_TABLE_NAME USING ERRCODE = '42501';
  END IF;
  IF TG_ARGV[0] <> '-' THEN                 -- nested: a table without a status column never reaches the comparison
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT integration.transition_ok(TG_ARGV[0], OLD.status, NEW.status) THEN
      RAISE EXCEPTION '% cannot go from % to %', lower(TG_ARGV[0]), OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
  END IF;
  IF TG_NARGS > 1 THEN
    o := to_jsonb(OLD); n := to_jsonb(NEW);
    FOR i IN 1..TG_NARGS - 1 LOOP
      col := TG_ARGV[i];
      IF (o -> col) IS DISTINCT FROM (n -> col) THEN
        RAISE EXCEPTION '%.% cannot be changed', TG_TABLE_NAME, col USING ERRCODE = '42501';
      END IF;
    END LOOP;
  END IF;
  RETURN NEW;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Provider configuration (per tenant, per kind), maintained through maker-checker (entity PROVIDER_CONFIG)
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE integration.provider_config (
    id             uuid PRIMARY KEY,
    kind           text NOT NULL CHECK (kind IN ('PAYOUT','COLLECTION','MANDATE','SMS','EMAIL')),
    provider       text NOT NULL CHECK (provider ~ '^[A-Z][A-Z0-9_]{1,30}$'),
    settings       jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(settings) = 'object'),
    secrets_cipher bytea,                                    -- AES-GCM of a JSON object {name: secret}; never returned
    secret_hints   jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(secret_hints) = 'object'),   -- {name: last 4 characters}
    status         text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    version        int NOT NULL CHECK (version >= 1),
    approval_id    uuid REFERENCES platform.approval_request(id),
    updated_by     text NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (kind, version)
);
-- one active provider per kind; earlier versions stay as INACTIVE rows (the history)
CREATE UNIQUE INDEX provider_one_active ON integration.provider_config (kind) WHERE status = 'ACTIVE';

-- A secret must never be stored as a plain setting: names that look like secrets are refused, and a hint is at
-- most four characters.
CREATE FUNCTION integration.guard_provider_config() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE k text; v text;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'provider configuration cannot be deleted' USING ERRCODE = '42501'; END IF;
  FOR k IN SELECT jsonb_object_keys(NEW.settings) LOOP
    IF k ~* '(secret|password|passwd|salt|token|apikey|api_key|private|credential)' OR lower(k) = 'key' THEN
      RAISE EXCEPTION 'setting "%" looks like a secret: secrets are stored encrypted, not in settings', k USING ERRCODE = '23514';
    END IF;
  END LOOP;
  FOR k, v IN SELECT * FROM jsonb_each_text(NEW.secret_hints) LOOP
    IF length(v) > 4 THEN RAISE EXCEPTION 'a secret hint holds at most the last four characters' USING ERRCODE = '23514'; END IF;
  END LOOP;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'INACTIVE' THEN RAISE EXCEPTION 'a superseded provider configuration cannot change' USING ERRCODE = '42501'; END IF;
    IF (to_jsonb(NEW) - 'status' - 'updated_at' - 'updated_by') <> (to_jsonb(OLD) - 'status' - 'updated_at' - 'updated_by') THEN
      RAISE EXCEPTION 'a provider configuration is replaced by a new version, not edited' USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER provider_config_guard BEFORE INSERT OR UPDATE OR DELETE ON integration.provider_config
  FOR EACH ROW EXECUTE FUNCTION integration.guard_provider_config();

-- ---------------------------------------------------------------------------------------------------------
-- Payouts (US-051)
-- ---------------------------------------------------------------------------------------------------------
-- The bank account a loan's disbursement is paid to. Replaced, never edited.
CREATE TABLE integration.beneficiary (
    id                 uuid PRIMARY KEY,
    loan_id            uuid NOT NULL REFERENCES lending.loan_account(id),
    customer_id        uuid NOT NULL REFERENCES customer.customer(id),
    holder_name_cipher bytea NOT NULL,
    account_cipher     bytea NOT NULL,
    account_last4      text NOT NULL CHECK (account_last4 ~ '^[A-Za-z0-9]{1,4}$'),
    account_hash       bytea NOT NULL,                      -- keyed hash: "same account as before?" without decrypting
    ifsc               text NOT NULL CHECK (ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
    validation         text NOT NULL DEFAULT 'PENDING' CHECK (validation IN ('PENDING','VALID','INVALID','UNAVAILABLE')),
    validation_ref     text,
    validation_note    text,
    name_at_bank_cipher bytea,
    status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REPLACED')),
    created_by         text NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX beneficiary_one_active ON integration.beneficiary (loan_id) WHERE status = 'ACTIVE';
CREATE TRIGGER beneficiary_guard BEFORE UPDATE OR DELETE ON integration.beneficiary
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('-', 'loan_id', 'customer_id', 'holder_name_cipher', 'account_cipher',
                                                      'account_last4', 'account_hash', 'ifsc', 'created_by', 'created_at');

CREATE TABLE integration.payout_instruction (
    id              uuid PRIMARY KEY,
    reference       text NOT NULL UNIQUE CHECK (reference ~ '^[A-Z0-9]{6,40}$'),    -- our idempotency key with the provider
    loan_id         uuid NOT NULL REFERENCES lending.loan_account(id),
    loan_no         text NOT NULL,
    customer_id     uuid NOT NULL REFERENCES customer.customer(id),
    branch_code     text NOT NULL REFERENCES platform.branch(code),
    attempt_no      int NOT NULL CHECK (attempt_no >= 1),
    amount          platform.money NOT NULL CHECK (amount > 0),
    mode            text NOT NULL DEFAULT 'IMPS' CHECK (mode IN ('IMPS','NEFT','RTGS')),
    beneficiary_id  uuid REFERENCES integration.beneficiary(id),
    provider        text,
    status          text NOT NULL DEFAULT 'INITIATED'
                    CHECK (status IN ('INITIATED','ON_HOLD','SENT','SUCCESS','FAILED','RETURNED','CANCELLED')),
    provider_ref    text,
    utr             text,
    failure_code    text,
    failure_reason  text,
    stp             boolean NOT NULL DEFAULT false,          -- the loan was disbursed straight through by an API client
    disbursement_txn uuid REFERENCES lending.loan_txn(id),
    attempts        int NOT NULL DEFAULT 0,                  -- sends and status polls made
    next_attempt_at timestamptz DEFAULT now(),               -- NULL: nothing more to do automatically
    last_error      text,
    needs_action    boolean NOT NULL DEFAULT false,          -- flagged for operations
    action_note     text,
    failure_action  text CHECK (failure_action IN ('REVERSED','PROPOSED','PARKED')),
    reversal_approval_id uuid REFERENCES platform.approval_request(id),
    sent_at         timestamptz,
    completed_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (loan_id, attempt_no),
    CHECK (status NOT IN ('SENT','SUCCESS','RETURNED') OR beneficiary_id IS NOT NULL),
    CHECK (status NOT IN ('SENT','SUCCESS','RETURNED') OR provider IS NOT NULL)
);
-- A loan never has two payouts that are in flight or paid: a retry is possible only after FAILED, RETURNED or CANCELLED.
CREATE UNIQUE INDEX payout_one_live ON integration.payout_instruction (loan_id)
  WHERE status IN ('INITIATED','ON_HOLD','SENT','SUCCESS');
CREATE INDEX payout_due ON integration.payout_instruction (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX payout_attention ON integration.payout_instruction (created_at) WHERE needs_action;
CREATE TRIGGER payout_guard BEFORE UPDATE OR DELETE ON integration.payout_instruction
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('PAYOUT', 'reference', 'loan_id', 'loan_no', 'customer_id', 'attempt_no',
                                                      'amount', 'stp', 'created_at');

-- Every status change and every operator action on a payout. Append-only.
CREATE TABLE integration.payout_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payout_id   uuid NOT NULL REFERENCES integration.payout_instruction(id),
    at          timestamptz NOT NULL DEFAULT now(),
    from_status text,
    to_status   text NOT NULL,
    source      text NOT NULL CHECK (source IN ('SYSTEM','API','WEBHOOK','POLL','OPERATOR')),
    actor       text NOT NULL,
    detail      jsonb
);
CREATE INDEX payout_event_payout ON integration.payout_event (payout_id, id);
CREATE TRIGGER payout_event_immutable BEFORE UPDATE OR DELETE ON integration.payout_event
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- ---------------------------------------------------------------------------------------------------------
-- Collections through a payment gateway (US-073)
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE integration.collection_order (
    id              uuid PRIMARY KEY,
    reference       text NOT NULL UNIQUE CHECK (reference ~ '^[A-Z0-9]{6,40}$'),
    loan_id         uuid NOT NULL REFERENCES lending.loan_account(id),
    loan_no         text NOT NULL,
    branch_code     text NOT NULL REFERENCES platform.branch(code),
    amount          platform.money NOT NULL CHECK (amount > 0),
    methods         text[] NOT NULL DEFAULT '{}' CHECK (methods <@ ARRAY['UPI','CARD','NETBANKING']),
    provider        text NOT NULL,
    provider_ref    text,
    payment_url     text,
    status          text NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED','PAID','FAILED','EXPIRED','CANCELLED')),
    expires_at      timestamptz NOT NULL,
    idempotency_key text,
    polls           int NOT NULL DEFAULT 0,
    next_poll_at    timestamptz,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX collection_order_idempotency ON integration.collection_order (created_by, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
CREATE INDEX collection_order_loan ON integration.collection_order (loan_id, created_at DESC);
CREATE INDEX collection_order_poll ON integration.collection_order (next_poll_at) WHERE next_poll_at IS NOT NULL;
CREATE TRIGGER collection_order_guard BEFORE UPDATE OR DELETE ON integration.collection_order
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('ORDER', 'reference', 'loan_id', 'loan_no', 'amount', 'provider', 'created_by', 'created_at');

-- Provider callbacks, stored before they are answered (replay protection: one row per provider event id).
CREATE TABLE integration.inbound_event (
    id              uuid PRIMARY KEY,
    kind            text NOT NULL CHECK (kind IN ('PAYOUT','COLLECTION','MANDATE')),
    provider        text NOT NULL,
    event_id        text NOT NULL CHECK (length(event_id) BETWEEN 1 AND 200),
    body_cipher     bytea NOT NULL,                          -- the raw body as received, encrypted
    body_sha256     text NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    parsed          jsonb NOT NULL,                          -- reference, provider ids, status, amount: no personal data
    status          text NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','PROCESSED','IGNORED','FAILED')),
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz DEFAULT now(),
    outcome         text,
    received_at     timestamptz NOT NULL DEFAULT now(),
    processed_at    timestamptz,
    UNIQUE (provider, kind, event_id)
);
CREATE INDEX inbound_event_due ON integration.inbound_event (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE TRIGGER inbound_event_guard BEFORE UPDATE OR DELETE ON integration.inbound_event
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('-', 'kind', 'provider', 'event_id', 'body_cipher', 'body_sha256', 'parsed', 'received_at');

-- Money the gateway says it received. One row per provider payment id: a repeated notification can never post twice.
CREATE TABLE integration.collection_payment (
    id                  uuid PRIMARY KEY,
    provider            text NOT NULL,
    provider_payment_id text NOT NULL CHECK (length(provider_payment_id) BETWEEN 1 AND 200),
    order_id            uuid REFERENCES integration.collection_order(id),
    loan_id             uuid REFERENCES lending.loan_account(id),
    amount              platform.money NOT NULL CHECK (amount > 0),
    method              text,
    utr                 text,
    paid_at             timestamptz NOT NULL,
    status              text NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','POSTED','UNMATCHED','FAILED','REFUND_DUE')),
    value_date          date,
    value_date_note     text,
    review              boolean NOT NULL DEFAULT false,
    loan_txn_id         uuid REFERENCES lending.loan_txn(id),
    attempts            int NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz DEFAULT now(),
    last_error          text,
    source              text NOT NULL CHECK (source IN ('WEBHOOK','POLL','SETTLEMENT')),
    inbound_event_id    uuid REFERENCES integration.inbound_event(id),
    resolved_by         text,
    resolution_note     text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    posted_at           timestamptz,
    UNIQUE (provider, provider_payment_id),
    CHECK ((status = 'POSTED') = (loan_txn_id IS NOT NULL)),
    CHECK (status <> 'POSTED' OR (loan_id IS NOT NULL AND value_date IS NOT NULL))
);
CREATE UNIQUE INDEX collection_payment_txn ON integration.collection_payment (loan_txn_id) WHERE loan_txn_id IS NOT NULL;
CREATE INDEX collection_payment_due ON integration.collection_payment (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX collection_payment_open ON integration.collection_payment (created_at) WHERE status IN ('RECEIVED','UNMATCHED');

CREATE FUNCTION integration.guard_collection_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'gateway payments cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.status IN ('POSTED','REFUND_DUE') THEN
    RAISE EXCEPTION 'gateway payment % is % and cannot change', OLD.provider_payment_id, OLD.status USING ERRCODE = '42501';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status AND NOT integration.transition_ok('PAYMENT', OLD.status, NEW.status) THEN
    RAISE EXCEPTION 'gateway payment cannot go from % to %', OLD.status, NEW.status USING ERRCODE = '23514';
  END IF;
  IF NEW.provider <> OLD.provider OR NEW.provider_payment_id <> OLD.provider_payment_id OR NEW.amount <> OLD.amount
     OR NEW.paid_at <> OLD.paid_at THEN
    RAISE EXCEPTION 'provider, payment id, amount and payment time of a gateway payment cannot be changed' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER collection_payment_guard BEFORE UPDATE OR DELETE ON integration.collection_payment
  FOR EACH ROW EXECUTE FUNCTION integration.guard_collection_payment();

-- Lines of the gateway's settlement report, loaded by operations (CSV). One line per provider payment id.
CREATE TABLE integration.gateway_settlement (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider            text NOT NULL,
    provider_payment_id text NOT NULL,
    amount              platform.money NOT NULL CHECK (amount > 0),
    fee                 platform.money NOT NULL DEFAULT 0 CHECK (fee >= 0),
    settled_on          date NOT NULL,
    utr                 text,
    file_ref            text NOT NULL,
    imported_by         text NOT NULL,
    imported_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_payment_id)
);
CREATE TRIGGER gateway_settlement_immutable BEFORE UPDATE OR DELETE ON integration.gateway_settlement
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- Reconciliation of gateway settlements against postings, unmatched in both directions.
--   MATCHED               posted, settled, amounts equal
--   PAYMENT_NOT_POSTED    money at the gateway that is not in the books (the unmatched receipts queue)
--   POSTED_NOT_SETTLED    in the books, no settlement line yet
--   SETTLED_NOT_RECEIVED  the gateway settled a payment we never received a notification for
--   AMOUNT_MISMATCH       settlement amount differs from the payment amount
--   REFUND_DUE            operations decided to return the money
CREATE VIEW integration.collection_reconciliation AS
  SELECT coalesce(p.provider, s.provider) AS provider,
         coalesce(p.provider_payment_id, s.provider_payment_id) AS provider_payment_id,
         p.id AS payment_id, p.loan_id, l.loan_no, l.branch_code, p.status AS payment_status,
         p.amount AS payment_amount, p.paid_at, p.value_date, p.loan_txn_id, p.review, p.last_error,
         s.amount AS settled_amount, s.fee AS settlement_fee, s.settled_on, s.utr AS settlement_utr,
         CASE WHEN p.id IS NULL THEN 'SETTLED_NOT_RECEIVED'
              WHEN p.status = 'REFUND_DUE' THEN 'REFUND_DUE'
              WHEN p.status IN ('RECEIVED','UNMATCHED') THEN 'PAYMENT_NOT_POSTED'
              WHEN s.id IS NULL THEN 'POSTED_NOT_SETTLED'
              WHEN s.amount <> p.amount THEN 'AMOUNT_MISMATCH'
              ELSE 'MATCHED' END AS category
    FROM (SELECT * FROM integration.collection_payment WHERE status <> 'FAILED') p
    FULL JOIN integration.gateway_settlement s
           ON s.provider = p.provider AND s.provider_payment_id = p.provider_payment_id
    LEFT JOIN lending.loan_account l ON l.id = p.loan_id;

-- ---------------------------------------------------------------------------------------------------------
-- e-Mandates and NACH (US-070 – US-072)
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE integration.mandate (
    id                 uuid PRIMARY KEY,
    mandate_ref        text NOT NULL UNIQUE CHECK (mandate_ref ~ '^[A-Z0-9]{6,30}$'),
    loan_id            uuid NOT NULL REFERENCES lending.loan_account(id),
    loan_no            text NOT NULL,
    customer_id        uuid NOT NULL REFERENCES customer.customer(id),
    branch_code        text NOT NULL REFERENCES platform.branch(code),
    umrn               text UNIQUE CHECK (umrn ~ '^[A-Z0-9]{20}$'),
    status             text NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT','SUBMITTED','ACTIVE','REJECTED','SUSPENDED','CANCELLED','EXPIRED')),
    max_amount         platform.money NOT NULL CHECK (max_amount > 0),
    frequency          text NOT NULL CHECK (frequency IN ('MONTHLY','QUARTERLY','HALF_YEARLY','YEARLY','AS_PRESENTED')),
    start_date         date NOT NULL,
    end_date           date,                                  -- NULL: until cancelled
    holder_name_cipher bytea NOT NULL,
    account_cipher     bytea NOT NULL,
    account_last4      text NOT NULL CHECK (account_last4 ~ '^[A-Za-z0-9]{1,4}$'),
    ifsc               text NOT NULL CHECK (ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
    account_type       text NOT NULL CHECK (account_type IN ('SB','CA','CC','OT')),
    sponsor_bank_code  text NOT NULL CHECK (sponsor_bank_code ~ '^[A-Z0-9]{1,11}$'),
    utility_code       text NOT NULL CHECK (utility_code ~ '^[A-Z0-9]{1,18}$'),
    provider           text,
    provider_ref       text,
    authentication_url text,
    reject_code        text,
    reject_reason      text,
    attempts           int NOT NULL DEFAULT 0,
    next_attempt_at    timestamptz DEFAULT now(),
    last_error         text,
    created_by         text NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date IS NULL OR end_date > start_date),
    CHECK (status NOT IN ('ACTIVE','SUSPENDED') OR umrn IS NOT NULL)
);
-- one mandate per loan that is in registration or usable
CREATE UNIQUE INDEX mandate_one_live ON integration.mandate (loan_id) WHERE status IN ('DRAFT','SUBMITTED','ACTIVE','SUSPENDED');
CREATE INDEX mandate_due ON integration.mandate (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE TRIGGER mandate_guard BEFORE UPDATE OR DELETE ON integration.mandate
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('MANDATE', 'mandate_ref', 'loan_id', 'loan_no', 'customer_id', 'max_amount',
      'frequency', 'start_date', 'end_date', 'holder_name_cipher', 'account_cipher', 'account_last4', 'ifsc', 'account_type',
      'created_by', 'created_at');

-- A UMRN, once given, stays.
CREATE FUNCTION integration.guard_mandate_umrn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.umrn IS NOT NULL AND NEW.umrn IS DISTINCT FROM OLD.umrn THEN
    RAISE EXCEPTION 'the UMRN of a mandate cannot be changed' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mandate_umrn_guard BEFORE UPDATE ON integration.mandate
  FOR EACH ROW EXECUTE FUNCTION integration.guard_mandate_umrn();

CREATE TABLE integration.mandate_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mandate_id  uuid NOT NULL REFERENCES integration.mandate(id),
    at          timestamptz NOT NULL DEFAULT now(),
    from_status text,
    to_status   text NOT NULL,
    source      text NOT NULL CHECK (source IN ('SYSTEM','API','WEBHOOK','POLL','FILE','OPERATOR')),
    actor       text NOT NULL,
    detail      jsonb
);
CREATE INDEX mandate_event_mandate ON integration.mandate_event (mandate_id, id);
CREATE TRIGGER mandate_event_immutable BEFORE UPDATE OR DELETE ON integration.mandate_event
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- Mandate status of a loan (US-070 acceptance: "mandate status on loan"): the newest mandate.
CREATE VIEW integration.loan_mandate AS
  SELECT DISTINCT ON (m.loan_id) m.loan_id, m.id AS mandate_id, m.mandate_ref, m.umrn, m.status, m.max_amount, m.frequency,
         m.start_date, m.end_date, m.account_last4, m.ifsc, m.sponsor_bank_code, m.utility_code, m.reject_code, m.reject_reason
    FROM integration.mandate m
   ORDER BY m.loan_id, (m.status IN ('ACTIVE','SUSPENDED')) DESC, m.created_at DESC;

-- NACH debit return reasons. INDICATIVE: compiled from commonly published lists of NPCI ACH debit return
-- reason codes; "verified" stays false until the list has been checked against NPCI's current circular or the
-- sponsor bank's own code list. A code that is not in this table is still recorded on the presentation, with
-- the description 'Unknown return reason'. "representable" drives automatic re-presentation and can be changed
-- by the tenant with the sponsor bank's guidance.
CREATE TABLE integration.nach_return_reason (
    code          text PRIMARY KEY CHECK (code ~ '^[A-Z0-9]{1,3}$'),
    description   text NOT NULL,
    category      text NOT NULL CHECK (category IN ('FUNDS','ACCOUNT','MANDATE','CUSTOMER','TECHNICAL','OTHER')),
    representable boolean NOT NULL DEFAULT false,
    verified      boolean NOT NULL DEFAULT false
);
INSERT INTO integration.nach_return_reason (code, description, category, representable) VALUES
  ('01','Account closed or transferred','ACCOUNT',false),
  ('02','No such account','ACCOUNT',false),
  ('03','Account description does not tally','ACCOUNT',false),
  ('04','Balance insufficient','FUNDS',true),
  ('05','Not arranged for','FUNDS',true),
  ('06','Payment stopped by drawer','CUSTOMER',false),
  ('07','Payment stopped under court order or account under attachment','ACCOUNT',false),
  ('08','Mandate not received','MANDATE',false),
  ('09','Miscellaneous - others','OTHER',false),
  ('51','KYC documents pending','ACCOUNT',false),
  ('52','Documents pending for account holder turning major','ACCOUNT',false),
  ('53','Account inactive','ACCOUNT',false),
  ('54','Dormant account','ACCOUNT',false),
  ('55','Account in zero balance or no transactions have happened','ACCOUNT',false),
  ('56','Small account, first transaction to be from base branch','ACCOUNT',false),
  ('57','Amount exceeds limit set on account by bank for debit per transaction','ACCOUNT',false),
  ('58','Account reached maximum debit limit set on account by bank','ACCOUNT',false),
  ('59','Network failure (CBS)','TECHNICAL',true),
  ('60','Account holder expired','CUSTOMER',false),
  ('61','Mandate cancelled','MANDATE',false),
  ('62','Account under litigation','ACCOUNT',false),
  ('64','Aadhaar number not mapped to account number','ACCOUNT',false),
  ('65','Account holder name invalid','ACCOUNT',false),
  ('66','UMRN does not exist','MANDATE',false),
  ('68','Account blocked or frozen','ACCOUNT',false),
  ('69','Customer insolvent or insane','CUSTOMER',false),
  ('70','Customer to refer to the branch','OTHER',false);

CREATE TABLE integration.nach_file (
    id              uuid PRIMARY KEY,
    direction       text NOT NULL CHECK (direction IN ('PRESENTATION','RESPONSE')),
    file_ref        text NOT NULL CHECK (file_ref ~ '^[A-Za-z0-9-]{1,30}$'),      -- a response carries the reference of the file it answers
    format          text NOT NULL,
    encoding        text NOT NULL CHECK (encoding IN ('FIXED','CSV')),
    settlement_date date,
    record_count    int CHECK (record_count >= 0),
    total_amount    platform.money,
    success_count   int,
    success_amount  platform.money,
    content_sha256  text NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    control_key     text,                                    -- file reference + control totals: a re-sent response is recognised even when its bytes differ
    document_key    text,                                    -- where the file is kept in the document store
    presentation_file_id uuid REFERENCES integration.nach_file(id),
    status          text NOT NULL CHECK (status IN ('GENERATED','RECEIVED','PROCESSED','REJECTED','DUPLICATE')),
    summary         jsonb,
    error           text,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    processed_at    timestamptz,
    UNIQUE (direction, content_sha256)                      -- file-level idempotency: the same bytes are never taken twice
);
CREATE UNIQUE INDEX nach_presentation_file_ref ON integration.nach_file (file_ref) WHERE direction = 'PRESENTATION';
CREATE UNIQUE INDEX nach_response_control ON integration.nach_file (control_key) WHERE direction = 'RESPONSE' AND status = 'PROCESSED';
CREATE TRIGGER nach_file_guard BEFORE UPDATE OR DELETE ON integration.nach_file
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('-', 'direction', 'file_ref', 'format', 'encoding', 'content_sha256', 'created_by', 'created_at');

-- One debit presented for one demand. Row-level idempotency: an outcome is recorded once and never changes.
CREATE TABLE integration.nach_presentation (
    id               uuid PRIMARY KEY,
    file_id          uuid NOT NULL REFERENCES integration.nach_file(id),
    seq              int NOT NULL CHECK (seq >= 1),
    item_ref         text NOT NULL UNIQUE CHECK (item_ref ~ '^[A-Za-z0-9-]{1,30}$'),
    mandate_id       uuid NOT NULL REFERENCES integration.mandate(id),
    loan_id          uuid NOT NULL REFERENCES lending.loan_account(id),
    loan_no          text NOT NULL,
    due_date         date NOT NULL,                           -- the demand this debit is for
    settlement_date  date NOT NULL,
    amount           platform.money NOT NULL CHECK (amount > 0),
    attempt_no       int NOT NULL CHECK (attempt_no >= 1),
    status           text NOT NULL DEFAULT 'GENERATED' CHECK (status IN ('GENERATED','SUCCESS','BOUNCED','WITHDRAWN')),
    response_file_id uuid REFERENCES integration.nach_file(id),
    return_code      text,
    return_reason    text,
    bank_ref         text,
    posting          text CHECK (posting IN ('POSTED','PENDING','FAILED')),      -- SUCCESS only: has the repayment been posted?
    posting_error    text,
    loan_txn_id      uuid REFERENCES lending.loan_txn(id),
    bounce_charge    text CHECK (bounce_charge IN ('CHARGED','NO_FEE_RULE','PENDING','FAILED')),   -- BOUNCED only
    bounce_charge_note text,
    represent_on     date,                                    -- BOUNCED only: earliest settlement date of the next presentation
    represent_note   text,
    processed_at     timestamptz,
    UNIQUE (file_id, seq),
    UNIQUE (loan_id, due_date, attempt_no),
    CHECK (status <> 'BOUNCED' OR return_code IS NOT NULL),
    CHECK ((posting = 'POSTED') = (loan_txn_id IS NOT NULL)),
    CHECK (posting IS NULL OR status = 'SUCCESS'),
    CHECK (bounce_charge IS NULL OR status = 'BOUNCED')
);
-- a demand is in at most one open presentation
CREATE UNIQUE INDEX nach_one_open ON integration.nach_presentation (loan_id, due_date) WHERE status = 'GENERATED';
CREATE UNIQUE INDEX nach_presentation_txn ON integration.nach_presentation (loan_txn_id) WHERE loan_txn_id IS NOT NULL;
CREATE INDEX nach_presentation_mandate ON integration.nach_presentation (mandate_id, due_date);
CREATE INDEX nach_presentation_pending ON integration.nach_presentation (processed_at)
  WHERE posting IN ('PENDING','FAILED') OR bounce_charge IN ('PENDING','FAILED');
CREATE TRIGGER nach_presentation_guard BEFORE UPDATE OR DELETE ON integration.nach_presentation
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('PRESENTATION', 'file_id', 'seq', 'item_ref', 'mandate_id', 'loan_id', 'due_date',
                                                      'settlement_date', 'amount', 'attempt_no');

-- Once an outcome is recorded, the outcome fields stay; only the follow-up (posting, bounce charge) can complete.
CREATE FUNCTION integration.guard_presentation_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'GENERATED' THEN
    IF NEW.return_code IS DISTINCT FROM OLD.return_code OR NEW.response_file_id IS DISTINCT FROM OLD.response_file_id
       OR NEW.bank_ref IS DISTINCT FROM OLD.bank_ref THEN
      RAISE EXCEPTION 'the outcome of presentation % is already recorded', OLD.item_ref USING ERRCODE = '42501';
    END IF;
    IF OLD.posting = 'POSTED' AND (NEW.posting IS DISTINCT FROM OLD.posting OR NEW.loan_txn_id IS DISTINCT FROM OLD.loan_txn_id) THEN
      RAISE EXCEPTION 'presentation % is already posted', OLD.item_ref USING ERRCODE = '42501';
    END IF;
    IF OLD.bounce_charge IN ('CHARGED','NO_FEE_RULE') AND NEW.bounce_charge IS DISTINCT FROM OLD.bounce_charge THEN
      RAISE EXCEPTION 'the bounce charge of presentation % is already settled', OLD.item_ref USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER nach_presentation_outcome BEFORE UPDATE ON integration.nach_presentation
  FOR EACH ROW EXECUTE FUNCTION integration.guard_presentation_outcome();

-- How often a demand has been presented and what happened last (re-presentation rules, loan screens).
CREATE VIEW integration.nach_demand_status AS
  SELECT p.loan_id, p.due_date, count(*) FILTER (WHERE p.status <> 'WITHDRAWN') AS presentations,
         bool_or(p.status = 'SUCCESS') AS collected, bool_or(p.status = 'GENERATED') AS open,
         (array_agg(p.status ORDER BY p.attempt_no DESC))[1] AS last_status,
         (array_agg(p.return_code ORDER BY p.attempt_no DESC))[1] AS last_return_code,
         max(p.represent_on) FILTER (WHERE p.status = 'BOUNCED') AS represent_on
    FROM integration.nach_presentation p
   GROUP BY p.loan_id, p.due_date;

-- ---------------------------------------------------------------------------------------------------------
-- Outbound webhooks (US-121)
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE integration.webhook_endpoint (
    id             uuid PRIMARY KEY,
    name           text NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    url            text NOT NULL CHECK (url ~ '^https://[^[:space:]]+$' AND length(url) <= 2000),
    event_types    text[] NOT NULL CHECK (cardinality(event_types) >= 1 AND event_types <@ ARRAY[
                       'loan.disbursed','payment.received','payment.bounced','loan.closed','loan.npa','mandate.status','payout.status']),
    status         text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    secret_pending_for text,                                 -- who may collect the next signing secret, once
    version        int NOT NULL DEFAULT 1,
    approval_id    uuid REFERENCES platform.approval_request(id),
    created_by     text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX webhook_endpoint_name ON integration.webhook_endpoint (lower(name));

-- Signing secrets. During a rotation the old secret stays valid until valid_until, and deliveries carry a
-- signature for each secret in force.
CREATE TABLE integration.webhook_secret (
    endpoint_id   uuid NOT NULL REFERENCES integration.webhook_endpoint(id),
    kid           int NOT NULL CHECK (kid >= 1),
    secret_cipher bytea NOT NULL,
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    valid_until   timestamptz,                               -- NULL: the current secret
    PRIMARY KEY (endpoint_id, kid)
);
CREATE UNIQUE INDEX webhook_secret_current ON integration.webhook_secret (endpoint_id) WHERE valid_until IS NULL;
CREATE TRIGGER webhook_secret_guard BEFORE UPDATE OR DELETE ON integration.webhook_secret
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('-', 'endpoint_id', 'kid', 'secret_cipher', 'created_by', 'created_at');

-- Every event that was (or could have been) sent: the store the replay console reads from.
CREATE TABLE integration.webhook_event (
    id           uuid PRIMARY KEY,
    type         text NOT NULL,
    aggregate_id text NOT NULL,
    data         jsonb NOT NULL,                             -- allow-listed fields only (integration-core WebhookEvents)
    occurred_at  timestamptz NOT NULL,
    outbox_id    bigint UNIQUE,                              -- one event per outbox row: the relay can run twice safely
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX webhook_event_type ON integration.webhook_event (type, occurred_at DESC);
CREATE TRIGGER webhook_event_immutable BEFORE UPDATE OR DELETE ON integration.webhook_event
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

CREATE TABLE integration.webhook_delivery (
    id              uuid PRIMARY KEY,
    endpoint_id     uuid NOT NULL REFERENCES integration.webhook_endpoint(id),
    event_id        uuid NOT NULL REFERENCES integration.webhook_event(id),
    replay_of       uuid REFERENCES integration.webhook_delivery(id),
    requested_by    text,                                    -- who asked for the replay
    status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RETRY','DELIVERED','DEAD')),
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz DEFAULT now(),
    last_status     int,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    delivered_at    timestamptz,
    CHECK ((status IN ('DELIVERED','DEAD')) = (next_attempt_at IS NULL))
);
-- an event is fanned out to an endpoint once; replays are separate rows
CREATE UNIQUE INDEX webhook_delivery_once ON integration.webhook_delivery (endpoint_id, event_id) WHERE replay_of IS NULL;
CREATE INDEX webhook_delivery_due ON integration.webhook_delivery (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX webhook_delivery_endpoint ON integration.webhook_delivery (endpoint_id, created_at DESC);
CREATE TRIGGER webhook_delivery_guard BEFORE UPDATE OR DELETE ON integration.webhook_delivery
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('DELIVERY', 'endpoint_id', 'event_id', 'replay_of', 'created_at');

CREATE TABLE integration.webhook_attempt (
    delivery_id uuid NOT NULL REFERENCES integration.webhook_delivery(id),
    attempt_no  int NOT NULL,
    at          timestamptz NOT NULL DEFAULT now(),
    status_code int,
    duration_ms int,
    error       text,
    PRIMARY KEY (delivery_id, attempt_no)
);
CREATE TRIGGER webhook_attempt_immutable BEFORE UPDATE OR DELETE ON integration.webhook_attempt
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- The outbox relay records an event it could not fan out, so that one bad event never blocks the ones behind it.
ALTER TABLE platform.outbox
  ADD COLUMN relay_attempts int NOT NULL DEFAULT 0,
  ADD COLUMN relay_error    text;

-- ---------------------------------------------------------------------------------------------------------
-- API clients (US-120). The client itself is a Keycloak client in the tenant's realm; its secret is never here.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE integration.api_client (
    client_id        text PRIMARY KEY CHECK (client_id ~ '^ext-[a-z0-9][a-z0-9-]{1,50}$'),
    name             text NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    scopes           text[] NOT NULL CHECK (cardinality(scopes) >= 1),
    home_branch      text NOT NULL REFERENCES platform.branch(code),
    all_branches     boolean NOT NULL DEFAULT false,
    keycloak_id      text NOT NULL,
    service_username text NOT NULL UNIQUE,                    -- the login in tokens; also the staff profile's username
    secret_pending_for text,                                  -- who may collect the client secret, once
    secret_issued_at timestamptz,
    approval_id      uuid REFERENCES platform.approval_request(id),
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
-- An API client can never hold a permission that approves, administers or configures (integration-core ApiClientScopes).
ALTER TABLE integration.api_client ADD CONSTRAINT api_client_scopes_allowed CHECK (scopes <@ ARRAY[
    'customer:view','customer:create','consent:view','consent:record','kyc:upload','product:view','loan:view','loan:create',
    'loan:stp','loan:repay','payout:view','payout:beneficiary','collection:view','collection:create','mandate:view',
    'mandate:register','report:run']);

-- Two checkers for anything that gives an API client a money-moving scope, and for a new secret of such a client.
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('API_CLIENT','CREATE_SENSITIVE',NULL,2),
  ('API_CLIENT','SCOPE_CHANGE_SENSITIVE',NULL,2),
  ('API_CLIENT','ROTATE_SECRET_SENSITIVE',NULL,2);

-- ---------------------------------------------------------------------------------------------------------
-- SMS and e-mail (US-123)
-- ---------------------------------------------------------------------------------------------------------
-- Templates per tenant, maintained through maker-checker (entity MESSAGE_TEMPLATE). An SMS template carries the
-- three DLT registrations (TRAI TCCCPR 2018): principal entity id, header (sender id) and content template id.
CREATE TABLE integration.message_template (
    code            text NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{2,40}$'),
    channel         text NOT NULL CHECK (channel IN ('SMS','EMAIL')),
    language        text NOT NULL DEFAULT 'en' CHECK (language ~ '^[a-z]{2}$'),
    category        text NOT NULL DEFAULT 'TRANSACTIONAL' CHECK (category IN ('TRANSACTIONAL','SERVICE','PROMOTIONAL')),
    subject         text,
    body            text NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    dlt_entity_id   text CHECK (dlt_entity_id ~ '^[0-9]{6,25}$'),
    dlt_template_id text CHECK (dlt_template_id ~ '^[0-9]{6,25}$'),
    dlt_header      text CHECK (dlt_header ~ '^[A-Za-z0-9]{3,11}$'),
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RETIRED')),
    version         int NOT NULL DEFAULT 1,
    approval_id     uuid REFERENCES platform.approval_request(id),
    updated_by      text NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (code, channel, language),
    CHECK (channel <> 'SMS' OR (dlt_entity_id IS NOT NULL AND dlt_template_id IS NOT NULL AND dlt_header IS NOT NULL)),
    CHECK (channel <> 'EMAIL' OR (subject IS NOT NULL AND length(trim(subject)) BETWEEN 1 AND 200))
);
CREATE TABLE integration.message_template_history (
    code        text NOT NULL,
    channel     text NOT NULL,
    language    text NOT NULL,
    version     int NOT NULL,
    snapshot    jsonb NOT NULL,
    changed_by  text NOT NULL,
    changed_at  timestamptz NOT NULL DEFAULT now(),
    approval_id uuid REFERENCES platform.approval_request(id),
    PRIMARY KEY (code, channel, language, version)
);
CREATE TRIGGER message_template_history_immutable BEFORE UPDATE OR DELETE ON integration.message_template_history
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- A customer's opt-out of a channel. Applies to promotional and service messages; a transactional message about
-- the customer's own loan is still sent (DPDP s.7 legitimate use; see docs/phase2-status.md).
CREATE TABLE integration.message_opt_out (
    customer_id uuid NOT NULL REFERENCES customer.customer(id),
    channel     text NOT NULL CHECK (channel IN ('SMS','EMAIL')),
    source      text NOT NULL,
    recorded_by text NOT NULL,
    at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, channel)
);

-- May this message go to this customer on this channel?
--   TRANSACTIONAL  always (legitimate use: servicing the customer's own loan)
--   SERVICE        unless the customer opted out of the channel
--   PROMOTIONAL    only with a current MARKETING consent and no opt-out
CREATE FUNCTION integration.message_allowed(p_customer uuid, p_channel text, p_category text) RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT CASE
    WHEN p_category = 'TRANSACTIONAL' THEN NULL
    WHEN EXISTS (SELECT 1 FROM integration.message_opt_out o WHERE o.customer_id = p_customer AND o.channel = p_channel) THEN 'OPT_OUT'
    WHEN p_category = 'PROMOTIONAL' AND NOT customer.has_consent(p_customer, 'MARKETING') THEN 'NO_CONSENT'
    ELSE NULL END
$$;

-- The delivery log. The recipient and the text are ciphertext; staff see the masked recipient.
CREATE TABLE integration.message (
    id               uuid PRIMARY KEY,
    template_code    text NOT NULL,
    channel          text NOT NULL CHECK (channel IN ('SMS','EMAIL')),
    language         text NOT NULL,
    category         text NOT NULL,
    customer_id      uuid REFERENCES customer.customer(id),
    loan_id          uuid REFERENCES lending.loan_account(id),
    event_key        text NOT NULL,                           -- what caused it, e.g. loan.disbursed:<outbox id>
    recipient_cipher bytea,
    recipient_masked text,
    body_cipher      bytea,
    status           text NOT NULL CHECK (status IN ('QUEUED','SENT','FAILED','SUPPRESSED')),
    suppress_reason  text CHECK (suppress_reason IN ('NO_CONSENT','OPT_OUT','NO_RECIPIENT','NO_PROVIDER','RENDER_ERROR')),
    provider         text,
    provider_ref     text,
    attempts         int NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    sent_at          timestamptz,
    UNIQUE (template_code, channel, event_key),              -- one message per event and channel, however often the event is relayed
    CHECK ((status = 'SUPPRESSED') = (suppress_reason IS NOT NULL)),
    CHECK ((status = 'QUEUED') = (next_attempt_at IS NOT NULL)),
    CHECK (status = 'SUPPRESSED' OR (recipient_cipher IS NOT NULL AND body_cipher IS NOT NULL))
);
CREATE INDEX message_due ON integration.message (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX message_customer ON integration.message (customer_id, created_at DESC);
CREATE INDEX message_sent ON integration.message (sent_at) WHERE sent_at IS NOT NULL;

CREATE FUNCTION integration.guard_message() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'the delivery log cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.status IN ('SENT','SUPPRESSED') THEN
    RAISE EXCEPTION 'message % is % and cannot change', OLD.id, OLD.status USING ERRCODE = '42501';
  END IF;
  IF OLD.status = 'FAILED' AND NEW.status <> 'QUEUED' THEN
    RAISE EXCEPTION 'a failed message can only be queued again' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER message_guard BEFORE UPDATE OR DELETE ON integration.message
  FOR EACH ROW EXECUTE FUNCTION integration.guard_message();

-- ---------------------------------------------------------------------------------------------------------
-- LOS integration (US-124): the LOS's own id of a customer, for create-or-get
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE customer.customer ADD COLUMN external_ref text CHECK (external_ref ~ '^[A-Za-z0-9._:-]{1,64}$');
CREATE UNIQUE INDEX customer_external_ref ON customer.customer (external_ref) WHERE external_ref IS NOT NULL;

-- A failed payout can lead to the disbursement being reversed: staff-approved loans through maker-checker
-- (entity LOAN_DISBURSEMENT_REVERSAL, one checker by the default rule).
