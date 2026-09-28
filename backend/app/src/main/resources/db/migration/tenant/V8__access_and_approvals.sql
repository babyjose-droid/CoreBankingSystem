-- Access and governance (US-020, US-022, US-023, US-025).
-- Identity, passwords, OTP and lockout live in Keycloak (ADR-007). Here we keep the staff profile that the
-- bank controls (home branch, branch scope, status) and the maker-checker machinery.

CREATE TABLE platform.staff_user (
    user_id       text PRIMARY KEY,                -- Keycloak subject
    username      text NOT NULL UNIQUE,
    display_name  text NOT NULL,
    home_branch   text NOT NULL REFERENCES platform.branch(code),
    all_branches  boolean NOT NULL DEFAULT false,   -- head-office users
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','EXITED')),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.staff_branch_scope (
    user_id     text NOT NULL REFERENCES platform.staff_user(user_id),
    branch_code text NOT NULL REFERENCES platform.branch(code),
    PRIMARY KEY (user_id, branch_code)
);

-- Maker-checker rules. Most specific rule wins (see kernel ApprovalPolicy); '*' = any.
CREATE TABLE platform.approval_rule (
    entity_type       text NOT NULL,
    action            text NOT NULL,
    min_amount        platform.money,
    checkers_required smallint NOT NULL CHECK (checkers_required BETWEEN 0 AND 3),
    UNIQUE NULLS NOT DISTINCT (entity_type, action, min_amount)
);
INSERT INTO platform.approval_rule VALUES
  ('*','*',NULL,1),
  ('VOUCHER','CREATE',1000000,2),
  ('VOUCHER','REVERSE',NULL,1),
  ('GL_HEAD','*',NULL,1);

ALTER TABLE platform.approval_request
  ADD COLUMN amount            platform.money,
  ADD COLUMN checkers_required smallint NOT NULL DEFAULT 1 CHECK (checkers_required BETWEEN 0 AND 3),
  ADD COLUMN current_state     jsonb,                  -- snapshot for the checker's diff (NULL for CREATE)
  ADD COLUMN idempotency_key   text,
  ADD COLUMN applied_ref       text;                   -- id of what the approval produced (customer id, lot id …)
CREATE UNIQUE INDEX approval_idempotency ON platform.approval_request (maker, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Every individual checker decision (two-checker rules need two distinct rows).
CREATE TABLE platform.approval_decision (
    request_id uuid NOT NULL REFERENCES platform.approval_request(id),
    checker    text NOT NULL,
    decision   text NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    note       text,
    at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, checker),
    CHECK (decision = 'APPROVE' OR (note IS NOT NULL AND length(trim(note)) > 0))
);

CREATE FUNCTION platform.check_decision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r platform.approval_request%ROWTYPE;
BEGIN
  SELECT * INTO r FROM platform.approval_request WHERE id = NEW.request_id FOR UPDATE;
  IF r.status <> 'PENDING' THEN
    RAISE EXCEPTION 'approval % is already %', r.id, r.status USING ERRCODE = '23514';
  END IF;
  IF lower(r.maker) = lower(NEW.checker) THEN
    RAISE EXCEPTION 'Maker cannot approve own request' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER decision_check BEFORE INSERT ON platform.approval_decision FOR EACH ROW EXECUTE FUNCTION platform.check_decision();
CREATE TRIGGER decision_immutable BEFORE UPDATE OR DELETE ON platform.approval_decision FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- Queue view with SLA ageing (US-023).
CREATE VIEW platform.approval_queue AS
  SELECT r.*,
         extract(epoch FROM (now() - r.made_at)) / 3600.0 AS age_hours,
         (SELECT count(*) FROM platform.approval_decision d WHERE d.request_id = r.id AND d.decision = 'APPROVE') AS approvals_so_far
    FROM platform.approval_request r;

-- Idempotency for API creates (US-029): same key + same request hash returns the stored response.
CREATE TABLE platform.idempotency_key (
    principal     text NOT NULL,
    key           text NOT NULL,
    request_hash  text NOT NULL,
    response_code int  NOT NULL,
    response_body jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (principal, key)
);
