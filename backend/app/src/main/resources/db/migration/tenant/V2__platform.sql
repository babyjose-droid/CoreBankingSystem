CREATE TABLE platform.branch (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    ifsc        text,
    state_code  text NOT NULL,                    -- drives GST place of supply
    parent_code text REFERENCES platform.branch(code),
    is_head_office boolean NOT NULL DEFAULT false,
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED'))
);
CREATE UNIQUE INDEX one_head_office ON platform.branch (is_head_office) WHERE is_head_office;

-- Single row: the tenant's current business date. Moved only by EOD/BOD.
CREATE TABLE platform.business_day (
    id            smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    business_date date NOT NULL,
    status        text NOT NULL CHECK (status IN ('OPEN','EOD_RUNNING','EOD_FAILED','CLOSED'))
);

CREATE TABLE platform.holiday (
    branch_code text REFERENCES platform.branch(code),   -- NULL = all branches
    day         date NOT NULL,
    reason      text NOT NULL,
    UNIQUE NULLS NOT DISTINCT (branch_code, day)
);

-- Number series per account family. Prefixes must be prefix-free (checked by trigger).
CREATE TABLE platform.number_series (
    family      text PRIMARY KEY CHECK (family IN ('LOAN','CASA','TERM_DEPOSIT','CUSTOMER','VOUCHER')),
    prefix      text NOT NULL UNIQUE CHECK (prefix ~ '^[0-9]{2,6}$'),
    width       smallint NOT NULL CHECK (width BETWEEN 4 AND 12),
    sequence_name text NOT NULL
);

CREATE FUNCTION platform.check_prefix_free() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM platform.number_series s
             WHERE s.family <> NEW.family
               AND (s.prefix LIKE NEW.prefix || '%' OR NEW.prefix LIKE s.prefix || '%')) THEN
    RAISE EXCEPTION 'number series prefix % overlaps another family', NEW.prefix USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER number_series_prefix_free BEFORE INSERT OR UPDATE ON platform.number_series
  FOR EACH ROW EXECUTE FUNCTION platform.check_prefix_free();

CREATE SEQUENCE platform.seq_loan;         CREATE SEQUENCE platform.seq_casa;
CREATE SEQUENCE platform.seq_term_deposit; CREATE SEQUENCE platform.seq_customer;
CREATE SEQUENCE platform.seq_voucher;
INSERT INTO platform.number_series VALUES
  ('LOAN','1001',9,'platform.seq_loan'), ('CASA','2001',9,'platform.seq_casa'),
  ('TERM_DEPOSIT','3001',9,'platform.seq_term_deposit'), ('CUSTOMER','9001',9,'platform.seq_customer'),
  ('VOUCHER','8001',9,'platform.seq_voucher');

-- Generic maker-checker (ADR-012). Every financial or master-data change goes through here.
CREATE TABLE platform.approval_request (
    id            uuid PRIMARY KEY,
    entity_type   text NOT NULL,              -- LOAN_PRODUCT | CUSTOMER | GL_HEAD | VOUCHER ...
    entity_id     text,
    action        text NOT NULL,              -- CREATE | UPDATE | CLOSE | REVERSE ...
    payload       jsonb NOT NULL,             -- proposed state
    maker         text NOT NULL,
    made_at       timestamptz NOT NULL DEFAULT now(),
    branch_code   text REFERENCES platform.branch(code),
    status        text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
    checker       text,
    checked_at    timestamptz,
    checker_note  text,
    CONSTRAINT maker_is_not_checker CHECK (checker IS NULL OR checker <> maker),
    CONSTRAINT decided_has_checker CHECK ((status IN ('PENDING','WITHDRAWN')) = (checker IS NULL))
);
CREATE INDEX approval_pending ON platform.approval_request (entity_type, made_at) WHERE status = 'PENDING';

-- Transactional outbox → SQS (ADR-008).
CREATE TABLE platform.outbox (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic        text NOT NULL,
    aggregate_id text NOT NULL,
    payload      jsonb NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX outbox_unpublished ON platform.outbox (id) WHERE published_at IS NULL;

-- EOD run log with per-account failure isolation.
CREATE TABLE platform.eod_run (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_date date NOT NULL,
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    status        text NOT NULL CHECK (status IN ('RUNNING','COMPLETED','COMPLETED_WITH_EXCEPTIONS','FAILED'))
);
CREATE TABLE platform.eod_exception (
    run_id      bigint NOT NULL REFERENCES platform.eod_run(id),
    step        text NOT NULL,
    account_no  text NOT NULL,
    error       text NOT NULL,
    resolved    boolean NOT NULL DEFAULT false,
    PRIMARY KEY (run_id, step, account_no)
);
