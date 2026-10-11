-- Phase 4 (P4-1): deposit products, account opening and term deposit booking.
--   1. One more rule row: a current account bears no interest.
--   2. deposits.product: savings, current and fixed deposit products, versioned, with the history of every version.
--      An ACTIVE term deposit product has a rate for every amount and tenure it accepts, and stays inside the rule set.
--   3. deposits.account with its holders, nominees and the terms frozen at booking (deposits.term_deposit).
-- No money moves here: a savings or current account opens with no balance and a term deposit is BOOKED, waiting
-- for its funds (P4-2). The application checks the same rules first, with the deposit engine, to answer 422 with
-- every reason; these triggers are what holds whatever the application does.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Rule: no interest on current accounts
-- From a summary of the RBI (Commercial Banks - Interest Rate on Deposits) Directions, 2025; the RBI text was not
-- opened (open item P4-01), so the row is verified = false like the other BANK rows.
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO deposits.rule (kind, code, value, effective_from, source, verified, note) VALUES
 ('BANK','current.interest-allowed',0,'2026-04-01','RBI Commercial Banks Interest Rate on Deposits Directions 2025 (summary)',false,
  'Current accounts bear no interest; the exception for a deceased depositor''s balance is not built');

-- ---------------------------------------------------------------------------------------------------------
-- 2. Products
-- ---------------------------------------------------------------------------------------------------------
-- Order of tenure steps, as the engine's Tenure.compareTo: a month counts as 31 days, so "364 days" sorts before
-- "12 months". Which step a deposit falls in is decided on its real dates (deposits.card_rate), not by this.
CREATE FUNCTION deposits.tenure_rank(p_months int, p_days int) RETURNS int LANGUAGE sql IMMUTABLE AS $$
  SELECT p_months * 31 + p_days
$$;

CREATE FUNCTION deposits.tenure_text(p_months int, p_days int) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN p_months = 0 THEN p_days || ' days'
              WHEN p_days = 0 THEN p_months || ' months'
              ELSE p_months || ' months ' || p_days || ' days' END
$$;

CREATE FUNCTION deposits.plain(p numeric) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN p = 0 THEN '0' WHEN p = trunc(p) THEN trunc(p)::text ELSE rtrim(rtrim(p::text, '0'), '.') END
$$;

CREATE TABLE deposits.product (
    code                      text PRIMARY KEY CHECK (code ~ '^[A-Z0-9]{2,12}$'),
    name                      text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 200),
    family                    text NOT NULL CHECK (family IN ('CASA','TERM_DEPOSIT')),
    product_type              text NOT NULL CHECK (product_type IN ('SAVINGS','CURRENT','FD_CUMULATIVE','FD_PAYOUT')),
    status                    text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','WITHDRAWN')),
    version                   int NOT NULL DEFAULT 1,
    day_count                 text CHECK (day_count IN ('ACTUAL_365','ACTUAL_360','ACTUAL_ACTUAL')),
    rounding                  text NOT NULL DEFAULT 'PAISE_HALF_UP'
                              CHECK (rounding IN ('PAISE_HALF_UP','PAISE_HALF_EVEN','RUPEE_HALF_UP','RUPEE_DOWN','RUPEE_UP')),
    tds_applicable            boolean NOT NULL DEFAULT true,   -- false: no tax is deducted and no preview shows any
    -- savings and current accounts
    min_balance               platform.money CHECK (min_balance >= 0),
    interest_bands            jsonb,           -- [{"fromBalance": 0, "ratePercent": 2.75}, ...]; NULL: no interest
    band_mode                 text CHECK (band_mode IN ('WHOLE_BALANCE','INCREMENTAL')),
    interest_posting_months   smallint CHECK (interest_posting_months IN (1, 3, 6, 12)),
    cheque_facility           boolean,
    -- term deposits
    min_amount                platform.money CHECK (min_amount > 0),
    max_amount                platform.money,
    min_tenure_months         int CHECK (min_tenure_months >= 0),
    min_tenure_days           int CHECK (min_tenure_days >= 0),
    max_tenure_months         int CHECK (max_tenure_months >= 0),
    max_tenure_days           int CHECK (max_tenure_days >= 0),
    interest_period_months    smallint CHECK (interest_period_months IN (0, 1, 3, 6, 12)),   -- 0: once, at maturity
    rate_card                 jsonb,           -- [{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.1}, ...]
    add_ons                   jsonb,           -- [{"code": "SENIOR_CITIZEN", "percent": 0.5}, ...]
    premature_allowed         boolean,
    premature_penalty_percent platform.rate CHECK (premature_penalty_percent >= 0),
    premature_min_days        int CHECK (premature_min_days >= 0),
    auto_renewal_allowed      boolean,
    -- general ledger
    gl_liability              text NOT NULL REFERENCES ledger.gl_head(code),
    gl_interest_expense       text REFERENCES ledger.gl_head(code),
    effective_from            date NOT NULL,
    approval_id               uuid REFERENCES platform.approval_request(id),
    updated_by                text NOT NULL,
    updated_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT product_type_of_family CHECK ((family = 'CASA') = (product_type IN ('SAVINGS','CURRENT'))),
    CONSTRAINT product_casa_settings CHECK (family <> 'CASA' OR (
        min_balance IS NOT NULL AND cheque_facility IS NOT NULL
        AND min_amount IS NULL AND max_amount IS NULL AND min_tenure_months IS NULL AND min_tenure_days IS NULL
        AND max_tenure_months IS NULL AND max_tenure_days IS NULL AND interest_period_months IS NULL AND rate_card IS NULL
        AND add_ons IS NULL AND premature_allowed IS NULL AND premature_penalty_percent IS NULL
        AND premature_min_days IS NULL AND auto_renewal_allowed IS NULL)),
    CONSTRAINT product_interest_settings CHECK (family <> 'CASA' OR (
        (interest_bands IS NULL AND band_mode IS NULL AND interest_posting_months IS NULL)
        OR (interest_bands IS NOT NULL AND band_mode IS NOT NULL AND interest_posting_months IS NOT NULL
            AND day_count IS NOT NULL AND gl_interest_expense IS NOT NULL))),
    CONSTRAINT product_savings_bears_interest CHECK (product_type <> 'SAVINGS' OR interest_bands IS NOT NULL),
    CONSTRAINT product_td_settings CHECK (family <> 'TERM_DEPOSIT' OR (
        min_amount IS NOT NULL AND min_tenure_months IS NOT NULL AND min_tenure_days IS NOT NULL
        AND max_tenure_months IS NOT NULL AND max_tenure_days IS NOT NULL AND interest_period_months IS NOT NULL
        AND rate_card IS NOT NULL AND add_ons IS NOT NULL AND premature_allowed IS NOT NULL
        AND auto_renewal_allowed IS NOT NULL AND day_count IS NOT NULL AND gl_interest_expense IS NOT NULL
        AND min_balance IS NULL AND interest_bands IS NULL AND band_mode IS NULL AND interest_posting_months IS NULL
        AND cheque_facility IS NULL)),
    CONSTRAINT product_td_limits CHECK (family <> 'TERM_DEPOSIT' OR (
        (max_amount IS NULL OR max_amount >= min_amount)
        AND min_tenure_months + min_tenure_days > 0
        AND min_tenure_months * 31 + min_tenure_days <= max_tenure_months * 31 + max_tenure_days)),
    CONSTRAINT product_cumulative_compounds CHECK (product_type <> 'FD_CUMULATIVE' OR interest_period_months > 0)
);

-- For tables that only ever gain rows.
CREATE FUNCTION deposits.forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% on deposits.% is not allowed: its rows are kept as they were written', TG_OP, TG_TABLE_NAME USING ERRCODE = '42501';
END $$;

CREATE TABLE deposits.product_history (
    product_code text NOT NULL REFERENCES deposits.product(code),
    version      int NOT NULL,
    snapshot     jsonb NOT NULL,
    changed_by   text NOT NULL,
    changed_at   timestamptz NOT NULL DEFAULT now(),
    approval_id  uuid REFERENCES platform.approval_request(id),
    PRIMARY KEY (product_code, version)
);
CREATE TRIGGER product_history_immutable BEFORE UPDATE OR DELETE ON deposits.product_history
  FOR EACH ROW EXECUTE FUNCTION deposits.forbid_change();
CREATE TRIGGER product_history_no_truncate BEFORE TRUNCATE ON deposits.product_history
  FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- What stops a term deposit product from being offered: every amount and tenure it accepts must have a rate
-- (the reference system refused bookings for tenures its product allowed). The same list as the engine's
-- DepositRateTable.coverageProblems, so a product the application approved is never refused here.
CREATE FUNCTION deposits.rate_card_problems(p_card jsonb, p_min_amount numeric, p_min_months int, p_min_days int)
RETURNS text[] LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE out text[] := '{}'; r record; lowest_amount numeric; first_step record;
BEGIN
  IF p_card IS NULL OR jsonb_typeof(p_card) <> 'array' OR jsonb_array_length(p_card) = 0 THEN
    RETURN ARRAY['the rate table has no rates'];
  END IF;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_card) c
              WHERE jsonb_typeof(c) <> 'object'
                 OR coalesce(jsonb_typeof(c->'fromAmount'), '') <> 'number' OR coalesce(jsonb_typeof(c->'fromMonths'), '') <> 'number'
                 OR coalesce(jsonb_typeof(c->'fromDays'), '') <> 'number' OR coalesce(jsonb_typeof(c->'ratePercent'), '') <> 'number') THEN
    RETURN ARRAY['every row of the rate table needs fromAmount, fromMonths, fromDays and ratePercent as numbers'];
  END IF;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_card) c
              WHERE (c->>'fromAmount')::numeric < 0 OR (c->>'ratePercent')::numeric < 0
                 OR (c->>'fromMonths')::numeric < 0 OR (c->>'fromDays')::numeric < 0
                 OR (c->>'fromMonths')::numeric <> trunc((c->>'fromMonths')::numeric)
                 OR (c->>'fromDays')::numeric <> trunc((c->>'fromDays')::numeric)) THEN
    RETURN ARRAY['a rate table row cannot have a negative amount, tenure or rate, and tenures are whole months and days'];
  END IF;
  FOR r IN SELECT a, m, d FROM (SELECT (c->>'fromAmount')::numeric AS a, (c->>'fromMonths')::int AS m, (c->>'fromDays')::int AS d
                                  FROM jsonb_array_elements(p_card) c) x GROUP BY a, m, d HAVING count(*) > 1 ORDER BY a, m, d LOOP
    out := out || ('two rates for amount from ' || deposits.plain(r.a) || ' and tenure from ' || deposits.tenure_text(r.m, r.d));
  END LOOP;
  FOR r IN SELECT min(deposits.tenure_text(m, d)) AS one, max(deposits.tenure_text(m, d)) AS other
             FROM (SELECT DISTINCT (c->>'fromMonths')::int AS m, (c->>'fromDays')::int AS d FROM jsonb_array_elements(p_card) c) x
            GROUP BY deposits.tenure_rank(m, d) HAVING count(*) > 1 LOOP
    out := out || ('tenure steps ' || r.one || ' and ' || r.other || ' cannot both be used');
  END LOOP;
  IF cardinality(out) > 0 THEN RETURN out; END IF;

  SELECT min((c->>'fromAmount')::numeric) INTO lowest_amount FROM jsonb_array_elements(p_card) c;
  IF lowest_amount > p_min_amount THEN
    out := out || ('no rate for amounts below ' || deposits.plain(lowest_amount) || '; the product accepts deposits from '
                   || deposits.plain(p_min_amount));
  END IF;
  SELECT (c->>'fromMonths')::int AS m, (c->>'fromDays')::int AS d INTO first_step FROM jsonb_array_elements(p_card) c
   ORDER BY deposits.tenure_rank((c->>'fromMonths')::int, (c->>'fromDays')::int) LIMIT 1;
  IF deposits.tenure_rank(first_step.m, first_step.d) > deposits.tenure_rank(p_min_months, p_min_days) THEN
    out := out || ('no rate for tenures shorter than ' || deposits.tenure_text(first_step.m, first_step.d) || '; the product accepts '
                   || deposits.tenure_text(p_min_months, p_min_days));
  END IF;
  FOR r IN
    SELECT a.a, t.m, t.d
      FROM (SELECT DISTINCT (c->>'fromAmount')::numeric AS a FROM jsonb_array_elements(p_card) c) a
     CROSS JOIN (SELECT DISTINCT (c->>'fromMonths')::int AS m, (c->>'fromDays')::int AS d FROM jsonb_array_elements(p_card) c) t
     WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(p_card) c
                        WHERE (c->>'fromAmount')::numeric = a.a AND (c->>'fromMonths')::int = t.m AND (c->>'fromDays')::int = t.d)
     ORDER BY a.a, deposits.tenure_rank(t.m, t.d)
  LOOP
    out := out || ('no rate for amount from ' || deposits.plain(r.a) || ' and tenure from ' || deposits.tenure_text(r.m, r.d));
  END LOOP;
  RETURN out;
END $$;

-- The card rate of a deposit, as the engine's DepositRateTable.resolve: the highest amount step not above the amount,
-- and the longest tenure step the deposit has run for on its real dates. No row when the card has no such cell.
CREATE FUNCTION deposits.card_rate(p_card jsonb, p_amount numeric, p_start date, p_maturity date)
RETURNS TABLE (from_amount numeric, from_months int, from_days int, rate_percent numeric) LANGUAGE sql IMMUTABLE AS $$
  WITH cells AS (
    SELECT (c->>'fromAmount')::numeric AS a, (c->>'fromMonths')::int AS m, (c->>'fromDays')::int AS d, (c->>'ratePercent')::numeric AS r
      FROM jsonb_array_elements(p_card) c),
  amount_step AS (SELECT max(a) AS a FROM cells WHERE a <= p_amount),
  tenure_step AS (
    SELECT m, d FROM (SELECT DISTINCT m, d FROM cells) s
     WHERE p_maturity >= (p_start + make_interval(months => m))::date + d
     ORDER BY deposits.tenure_rank(m, d) DESC LIMIT 1)
  SELECT c.a, c.m, c.d, c.r FROM cells c, amount_step, tenure_step
   WHERE c.a = amount_step.a AND c.m = tenure_step.m AND c.d = tenure_step.d
$$;

CREATE FUNCTION deposits.guard_product() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE problems text[]; v numeric; top numeric; up numeric; head ledger.gl_head%ROWTYPE; second record;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'a deposit product cannot be deleted; withdraw it' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF NEW.code <> OLD.code OR NEW.family <> OLD.family OR NEW.product_type <> OLD.product_type THEN
      RAISE EXCEPTION 'the code, family and type of a deposit product never change' USING ERRCODE = '42501';
    END IF;
    NEW.version := OLD.version + 1;
  ELSE
    NEW.version := 1;
  END IF;
  NEW.updated_at := now();
  SELECT * INTO head FROM ledger.gl_head WHERE code = NEW.gl_liability;
  IF head.category <> 'LIABILITY' OR NOT head.is_posting THEN
    RAISE EXCEPTION 'GL head % is not a liability posting head; deposits are liabilities', NEW.gl_liability USING ERRCODE = '23514';
  END IF;
  IF NEW.gl_interest_expense IS NOT NULL THEN
    SELECT * INTO head FROM ledger.gl_head WHERE code = NEW.gl_interest_expense;
    IF head.category <> 'EXPENSE' OR NOT head.is_posting THEN
      RAISE EXCEPTION 'GL head % is not an expense posting head; interest on deposits is an expense', NEW.gl_interest_expense USING ERRCODE = '23514';
    END IF;
  END IF;
  IF NEW.status <> 'ACTIVE' THEN RETURN NEW; END IF;       -- a withdrawn product takes no new account: nothing to hold

  PERFORM deposits.assert_can_offer(NEW.family);
  IF NEW.family = 'CASA' THEN
    IF NEW.interest_bands IS NOT NULL THEN
      IF jsonb_typeof(NEW.interest_bands) <> 'array' OR jsonb_array_length(NEW.interest_bands) = 0
         OR EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.interest_bands) b
                     WHERE coalesce(jsonb_typeof(b->'fromBalance'), '') <> 'number' OR coalesce(jsonb_typeof(b->'ratePercent'), '') <> 'number') THEN
        RAISE EXCEPTION 'deposit product %: every interest band needs fromBalance and ratePercent as numbers', NEW.code USING ERRCODE = '23514';
      END IF;
      IF EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.interest_bands) b
                  WHERE (b->>'fromBalance')::numeric < 0 OR (b->>'ratePercent')::numeric < 0 OR (b->>'ratePercent')::numeric > 100)
         OR (SELECT min((b->>'fromBalance')::numeric) FROM jsonb_array_elements(NEW.interest_bands) b) <> 0
         OR (SELECT count(*) <> count(DISTINCT (b->>'fromBalance')::numeric) FROM jsonb_array_elements(NEW.interest_bands) b) THEN
        RAISE EXCEPTION 'deposit product %: the interest bands start at 0, each at a different balance, with rates from 0 to 100', NEW.code
          USING ERRCODE = '23514';
      END IF;
      IF NEW.product_type = 'CURRENT' AND coalesce(deposits.rule_value('current.interest-allowed'), 1) = 0 THEN
        RAISE EXCEPTION 'deposit product %: a current account bears no interest', NEW.code USING ERRCODE = '23514';
      END IF;
      v := deposits.rule_value('savings.uniform-rate-up-to');
      IF v IS NOT NULL THEN
        SELECT (b->>'fromBalance')::numeric AS from_balance INTO second FROM jsonb_array_elements(NEW.interest_bands) b
         WHERE (b->>'fromBalance')::numeric > 0 ORDER BY 1 LIMIT 1;
        IF FOUND AND (second.from_balance < v OR (second.from_balance = v AND NEW.band_mode = 'WHOLE_BALANCE')) THEN
          RAISE EXCEPTION 'deposit product %: one rate must apply to balances up to %; the second band starts at %', NEW.code,
            deposits.plain(v), deposits.plain(second.from_balance) USING ERRCODE = '23514';
        END IF;
      END IF;
    END IF;
    RETURN NEW;
  END IF;

  problems := deposits.rate_card_problems(NEW.rate_card, NEW.min_amount, NEW.min_tenure_months, NEW.min_tenure_days);
  IF cardinality(problems) > 0 THEN
    RAISE EXCEPTION 'deposit product % cannot be approved: %', NEW.code, array_to_string(problems, '; ') USING ERRCODE = '23514';
  END IF;
  IF jsonb_typeof(NEW.add_ons) <> 'array'
     OR EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.add_ons) a
                 WHERE coalesce(a->>'code', '') !~ '^[A-Z][A-Z0-9_]{1,29}$' OR coalesce(jsonb_typeof(a->'percent'), '') <> 'number'
                    OR (a->>'percent')::numeric = 0)
     OR (SELECT count(*) <> count(DISTINCT a->>'code') FROM jsonb_array_elements(NEW.add_ons) a) THEN
    RAISE EXCEPTION 'deposit product %: each add-on needs a code, once, and a percentage that is not zero', NEW.code USING ERRCODE = '23514';
  END IF;
  v := deposits.rule_value('td.min-tenure-months');
  IF v IS NOT NULL AND deposits.tenure_rank(NEW.min_tenure_months, NEW.min_tenure_days) < deposits.tenure_rank(v::int, 0) THEN
    RAISE EXCEPTION 'deposit product %: the tenure % is shorter than % months', NEW.code,
      deposits.tenure_text(NEW.min_tenure_months, NEW.min_tenure_days), v::int USING ERRCODE = '23514';
  END IF;
  v := deposits.rule_value('td.max-tenure-months');
  IF v IS NOT NULL AND deposits.tenure_rank(NEW.max_tenure_months, NEW.max_tenure_days) > deposits.tenure_rank(v::int, 0) THEN
    RAISE EXCEPTION 'deposit product %: the tenure % is longer than % months', NEW.code,
      deposits.tenure_text(NEW.max_tenure_months, NEW.max_tenure_days), v::int USING ERRCODE = '23514';
  END IF;
  v := deposits.rule_value('td.min-compounding-rest-months');
  IF v IS NOT NULL AND NEW.interest_period_months <> 0 AND NEW.interest_period_months < v THEN
    RAISE EXCEPTION 'deposit product %: interest may not be paid or compounded more often than every % month(s)', NEW.code, v::int
      USING ERRCODE = '23514';
  END IF;
  -- the highest rate any depositor could be given: the highest card rate with every add-on that raises it
  SELECT max((c->>'ratePercent')::numeric) INTO top FROM jsonb_array_elements(NEW.rate_card) c;
  SELECT coalesce(sum((a->>'percent')::numeric) FILTER (WHERE (a->>'percent')::numeric > 0), 0) INTO up FROM jsonb_array_elements(NEW.add_ons) a;
  v := deposits.rule_value('td.max-rate-percent');
  IF v IS NOT NULL AND top + up > v THEN
    RAISE EXCEPTION 'deposit product %: the highest rate % above the ceiling of %', NEW.code,
      CASE WHEN up = 0 THEN deposits.plain(top) || '% is'
           ELSE deposits.plain(top) || '% with every add-on (' || deposits.plain(up) || '%) is ' || deposits.plain(top + up) || '%,' END,
      deposits.plain(v) || '%' USING ERRCODE = '23514';
  END IF;
  -- premature repayment: fixed by the rule set (a deposit-taking NBFC), or the product's own penalty and minimum period
  IF deposits.rule_value('td.premature.rate-cut-percent') IS NOT NULL THEN
    IF NEW.premature_penalty_percent IS NOT NULL OR NEW.premature_min_days IS NOT NULL THEN
      RAISE EXCEPTION 'deposit product %: the interest on premature repayment is fixed by the rule set for this institution', NEW.code
        USING ERRCODE = '23514';
    END IF;
  ELSIF NEW.premature_allowed <> (NEW.premature_penalty_percent IS NOT NULL AND NEW.premature_min_days IS NOT NULL)
        OR (NOT NEW.premature_allowed AND (NEW.premature_penalty_percent IS NOT NULL OR NEW.premature_min_days IS NOT NULL)) THEN
    RAISE EXCEPTION 'deposit product %: a product that allows premature withdrawal needs its penalty and minimum period, and one that does not has neither',
      NEW.code USING ERRCODE = '23514';
  END IF;
  v := deposits.rule_value('td.premature.mandatory-up-to');
  IF v IS NOT NULL AND NOT NEW.premature_allowed AND NEW.min_amount <= v THEN
    RAISE EXCEPTION 'deposit product %: term deposits of individuals up to % must allow premature withdrawal', NEW.code, deposits.plain(v)
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER product_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.product
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_product();
CREATE TRIGGER product_no_truncate BEFORE TRUNCATE ON deposits.product FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- Every version of a product is kept, whoever wrote it.
CREATE FUNCTION deposits.record_product_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO deposits.product_history (product_code, version, snapshot, changed_by, approval_id)
  VALUES (NEW.code, NEW.version, to_jsonb(NEW), NEW.updated_by, NEW.approval_id);
  RETURN NULL;
END $$;
CREATE TRIGGER product_version AFTER INSERT OR UPDATE ON deposits.product
  FOR EACH ROW EXECUTE FUNCTION deposits.record_product_version();

-- ---------------------------------------------------------------------------------------------------------
-- 3. Accounts
-- The account number comes from the series of its family (CASA 2001..., term deposits 3001...), issued here, so the
-- two families can never share a number (the reference system issued FD numbers that clashed with CASA numbers).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.account (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_no      text NOT NULL UNIQUE,
    family          text NOT NULL CHECK (family IN ('CASA','TERM_DEPOSIT')),
    product_code    text NOT NULL REFERENCES deposits.product(code),
    product_version int NOT NULL,
    customer_id     uuid NOT NULL REFERENCES customer.customer(id),      -- the sole or first-named holder
    branch_code     text NOT NULL REFERENCES platform.branch(code),
    operating_mode  text NOT NULL CHECK (operating_mode IN
                      ('SINGLE','EITHER_OR_SURVIVOR','FORMER_OR_SURVIVOR','ANYONE_OR_SURVIVOR','JOINTLY')),
    nomination_mode text NOT NULL CHECK (nomination_mode IN ('NONE','SIMULTANEOUS','SUCCESSIVE')),
    -- BOOKED: a term deposit waiting for its funds. The later states are used from P4-2 on.
    status          text NOT NULL CHECK (status IN ('BOOKED','ACTIVE','MATURED','CLOSED','CANCELLED')),
    opened_on       date NOT NULL,
    approval_id     uuid REFERENCES platform.approval_request(id),
    opened_by       text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_customer ON deposits.account (customer_id);
CREATE INDEX account_branch ON deposits.account (branch_code, family, status);

CREATE TABLE deposits.account_holder (
    account_id  uuid NOT NULL REFERENCES deposits.account(id),
    holder_no   smallint NOT NULL CHECK (holder_no BETWEEN 1 AND 4),     -- 1 is the first-named holder
    customer_id uuid NOT NULL REFERENCES customer.customer(id),
    PRIMARY KEY (account_id, holder_no),
    UNIQUE (account_id, customer_id)
);
CREATE INDEX account_holder_customer ON deposits.account_holder (customer_id);

-- A nominee is named, not necessarily a customer. The name is stored as a customer's display name is (SEC-03).
CREATE TABLE deposits.nominee (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid NOT NULL REFERENCES deposits.account(id),
    nominee_no    smallint NOT NULL CHECK (nominee_no BETWEEN 1 AND 10), -- the order named; decides for SUCCESSIVE
    name          text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 200),
    relationship  text NOT NULL CHECK (relationship IN ('SPOUSE','CHILD','PARENT','SIBLING','GRANDCHILD','OTHER_RELATIVE','OTHER')),
    share_percent numeric(5,2) CHECK (share_percent > 0 AND share_percent <= 100),
    is_minor      boolean NOT NULL DEFAULT false,
    guardian_name text CHECK (guardian_name IS NULL OR (btrim(guardian_name) <> '' AND length(guardian_name) <= 200)),
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ENDED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    ended_at      timestamptz,
    CONSTRAINT nominee_guardian_for_a_minor CHECK (is_minor = (guardian_name IS NOT NULL)),
    CONSTRAINT nominee_ended CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX nominee_order ON deposits.nominee (account_id, nominee_no) WHERE status = 'ACTIVE';

-- Savings and current accounts. next_interest_date: the last day of the interest period in progress, for an
-- account that bears interest; the day-end of P4-3 credits the period's interest and moves it on.
CREATE TABLE deposits.casa_account (
    account_id         uuid PRIMARY KEY REFERENCES deposits.account(id),
    next_interest_date date
);

-- A term deposit: the terms frozen at booking. The rate is fixed at booking, with its parts; interest starts on
-- the funding date (start_date, set in P4-2), and the maturity date follows from it.
CREATE TABLE deposits.term_deposit (
    account_id                uuid PRIMARY KEY REFERENCES deposits.account(id),
    principal                 platform.money NOT NULL CHECK (principal > 0),
    tenure_months             int NOT NULL CHECK (tenure_months >= 0),
    tenure_days               int NOT NULL CHECK (tenure_days >= 0),
    booked_on                 date NOT NULL,
    start_date                date,
    maturity_date             date,
    rate_percent              platform.rate NOT NULL CHECK (rate_percent >= 0),
    card_rate_percent         platform.rate NOT NULL,
    card_from_amount          platform.money NOT NULL,
    card_from_months          int NOT NULL,
    card_from_days            int NOT NULL,
    add_ons                   jsonb NOT NULL DEFAULT '[]',     -- the add-ons given, as on the product then
    interest_period_months    smallint NOT NULL,
    cumulative                boolean NOT NULL,
    day_count                 text NOT NULL,
    rounding                  text NOT NULL,
    tds_applicable            boolean NOT NULL,
    premature_allowed         boolean NOT NULL,
    premature_penalty_percent platform.rate,
    premature_min_days        int,
    payout_to                 text NOT NULL CHECK (payout_to IN ('CASA','BANK_ACCOUNT')),
    payout_account_id         uuid REFERENCES deposits.account(id),
    maturity_instruction      text NOT NULL CHECK (maturity_instruction IN ('PAY_OUT','RENEW_PRINCIPAL','RENEW_WITH_INTEREST')),
    CHECK (tenure_months + tenure_days > 0),
    CHECK ((start_date IS NULL) = (maturity_date IS NULL)),
    CONSTRAINT payout_account_for_casa CHECK ((payout_to = 'CASA') = (payout_account_id IS NOT NULL))
);

CREATE FUNCTION deposits.guard_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p deposits.product%ROWTYPE; b text;
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
  SELECT business_date INTO NEW.opened_on FROM platform.business_day WHERE id = 1;
  IF NEW.opened_on IS NULL THEN RAISE EXCEPTION 'business date not initialised' USING ERRCODE = 'P0002'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.account
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_account();

-- An account is opened only for customers who are ACTIVE and whose KYC is verified.
CREATE FUNCTION deposits.guard_holder() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c customer.customer%ROWTYPE;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'the holders of a deposit account are not changed here' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO c FROM customer.customer WHERE id = NEW.customer_id;
  IF c.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'customer % is %; an account is opened only for an ACTIVE customer', c.customer_no, c.status USING ERRCODE = '23514';
  END IF;
  IF c.kyc_status <> 'VERIFIED' THEN
    RAISE EXCEPTION 'the KYC of customer % is %; an account is opened only for a customer whose KYC is verified', c.customer_no, c.kyc_status
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER holder_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.account_holder
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_holder();

-- A nomination is replaced by ending its rows and adding new ones; a row is never edited or deleted.
CREATE FUNCTION deposits.guard_nominee() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'nominees are never deleted; end them instead' USING ERRCODE = '42501';
  END IF;
  IF OLD.status = 'ACTIVE' AND NEW.status = 'ENDED'
     AND (to_jsonb(NEW) - 'status' - 'ended_at') = (to_jsonb(OLD) - 'status' - 'ended_at') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'a nominee can only be ended, not edited' USING ERRCODE = '42501';
END $$;
CREATE TRIGGER nominee_guard BEFORE UPDATE OR DELETE ON deposits.nominee
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_nominee();

-- The whole account, checked at COMMIT so that it can be written in one transaction: holders against the
-- operating mode, the nomination against its mode and the rule set, and the row of its family.
CREATE FUNCTION deposits.check_account(p_account uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; n int; lo int; hi int; first_holder uuid; nominees int; total numeric; with_share int; max_nominees numeric;
BEGIN
  SELECT * INTO a FROM deposits.account WHERE id = p_account;
  IF NOT FOUND THEN RETURN; END IF;
  SELECT count(*), max(holder_no) INTO n, hi FROM deposits.account_holder WHERE account_id = p_account;
  SELECT customer_id INTO first_holder FROM deposits.account_holder WHERE account_id = p_account AND holder_no = 1;
  IF n = 0 OR hi <> n OR first_holder IS DISTINCT FROM a.customer_id THEN
    RAISE EXCEPTION 'account %: the holders are numbered from 1 and the first is the account''s customer', a.account_no USING ERRCODE = '23514';
  END IF;
  lo := CASE a.operating_mode WHEN 'SINGLE' THEN 1 ELSE 2 END;
  hi := CASE a.operating_mode WHEN 'SINGLE' THEN 1 WHEN 'EITHER_OR_SURVIVOR' THEN 2 WHEN 'FORMER_OR_SURVIVOR' THEN 2 ELSE 4 END;
  IF n < lo OR n > hi THEN
    RAISE EXCEPTION 'account %: operating mode % needs % holder(s); % named', a.account_no, a.operating_mode,
      CASE WHEN lo = hi THEN lo::text ELSE lo || ' to ' || hi END, n USING ERRCODE = '23514';
  END IF;
  IF n > 1 AND EXISTS (SELECT 1 FROM deposits.account_holder h JOIN customer.customer c ON c.id = h.customer_id
                        WHERE h.account_id = p_account AND c.customer_type <> 'INDIVIDUAL') THEN
    RAISE EXCEPTION 'account %: a joint account is held by individuals', a.account_no USING ERRCODE = '23514';
  END IF;

  SELECT count(*), sum(share_percent), count(share_percent) INTO nominees, total, with_share
    FROM deposits.nominee WHERE account_id = p_account AND status = 'ACTIVE';
  IF a.nomination_mode = 'NONE' AND nominees > 0 THEN
    RAISE EXCEPTION 'account %: nominees are named but the nomination mode is NONE', a.account_no USING ERRCODE = '23514';
  END IF;
  IF a.nomination_mode <> 'NONE' THEN
    max_nominees := deposits.rule_value('nominees.max', a.opened_on);
    IF nominees = 0 OR (a.nomination_mode = 'SUCCESSIVE' AND nominees < 2) THEN
      RAISE EXCEPTION 'account %: nomination mode % needs % nominee(s) or more', a.account_no, a.nomination_mode,
        CASE a.nomination_mode WHEN 'SUCCESSIVE' THEN 2 ELSE 1 END USING ERRCODE = '23514';
    END IF;
    IF max_nominees IS NOT NULL AND nominees > max_nominees THEN
      RAISE EXCEPTION 'account %: % nominees named; at most % allowed', a.account_no, nominees, max_nominees::int USING ERRCODE = '23514';
    END IF;
    IF (SELECT max(nominee_no) FROM deposits.nominee WHERE account_id = p_account AND status = 'ACTIVE') <> nominees THEN
      RAISE EXCEPTION 'account %: the nominees are numbered from 1 in the order named', a.account_no USING ERRCODE = '23514';
    END IF;
    IF a.nomination_mode = 'SIMULTANEOUS' AND (with_share <> nominees OR total <> 100) THEN
      RAISE EXCEPTION 'account %: nominee shares must total 100 percent; they total %', a.account_no, coalesce(deposits.plain(total), 'nothing')
        USING ERRCODE = '23514';
    END IF;
    IF a.nomination_mode = 'SUCCESSIVE' AND with_share > 0 THEN
      RAISE EXCEPTION 'account %: successive nominees have no shares; the order named decides', a.account_no USING ERRCODE = '23514';
    END IF;
  END IF;

  IF a.family = 'CASA' AND NOT EXISTS (SELECT 1 FROM deposits.casa_account WHERE account_id = p_account) THEN
    RAISE EXCEPTION 'account %: a savings or current account needs its account row', a.account_no USING ERRCODE = '23514';
  END IF;
  IF a.family = 'TERM_DEPOSIT' AND NOT EXISTS (SELECT 1 FROM deposits.term_deposit WHERE account_id = p_account) THEN
    RAISE EXCEPTION 'account %: a term deposit needs its terms', a.account_no USING ERRCODE = '23514';
  END IF;
END $$;

CREATE FUNCTION deposits.check_account_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME = 'account' THEN
    PERFORM deposits.check_account(NEW.id);
  ELSE
    PERFORM deposits.check_account(NEW.account_id);
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER account_complete AFTER INSERT OR UPDATE ON deposits.account
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_account_trigger();
CREATE CONSTRAINT TRIGGER holder_complete AFTER INSERT ON deposits.account_holder
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_account_trigger();
CREATE CONSTRAINT TRIGGER nominee_complete AFTER INSERT OR UPDATE ON deposits.nominee
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deposits.check_account_trigger();

CREATE FUNCTION deposits.guard_casa_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; p deposits.product%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a deposit account is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF NEW.account_id <> OLD.account_id THEN RAISE EXCEPTION 'the account of this row never changes' USING ERRCODE = '42501'; END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
  IF a.family <> 'CASA' THEN
    RAISE EXCEPTION 'account % is not a savings or current account', a.account_no USING ERRCODE = '23514';
  END IF;
  SELECT * INTO p FROM deposits.product WHERE code = a.product_code;
  IF (p.interest_bands IS NULL) <> (NEW.next_interest_date IS NULL) THEN
    RAISE EXCEPTION 'account %: an account that bears interest has the date its interest is next credited, and one that does not has none',
      a.account_no USING ERRCODE = '23514';
  END IF;
  IF NEW.next_interest_date < a.opened_on THEN
    RAISE EXCEPTION 'account %: the next interest date is before the account was opened', a.account_no USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER casa_account_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.casa_account
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_casa_account();

CREATE FUNCTION deposits.guard_term_deposit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a deposits.account%ROWTYPE; p deposits.product%ROWTYPE; pay deposits.account%ROWTYPE; card record; v numeric; maturity date; extra numeric;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a deposit account is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    -- the terms frozen at booking never change; the funding dates and the instructions do (P4-2, P4-4)
    IF (to_jsonb(NEW) - 'start_date' - 'maturity_date' - 'payout_to' - 'payout_account_id' - 'maturity_instruction')
       <> (to_jsonb(OLD) - 'start_date' - 'maturity_date' - 'payout_to' - 'payout_account_id' - 'maturity_instruction') THEN
      RAISE EXCEPTION 'the terms of a term deposit are fixed at booking' USING ERRCODE = '42501';
    END IF;
    IF NEW.start_date IS NOT NULL AND NEW.maturity_date <> (NEW.start_date + make_interval(months => NEW.tenure_months))::date + NEW.tenure_days THEN
      RAISE EXCEPTION 'the maturity date is the start date plus the tenure' USING ERRCODE = '23514';
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
  END IF;
  IF NEW.payout_account_id IS NOT NULL THEN
    SELECT * INTO a FROM deposits.account WHERE id = NEW.account_id;
    SELECT * INTO pay FROM deposits.account WHERE id = NEW.payout_account_id;
    IF pay.family <> 'CASA' OR pay.status <> 'ACTIVE' THEN
      RAISE EXCEPTION 'deposit %: interest and maturity proceeds are paid to an active savings or current account', a.account_no
        USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM deposits.account_holder h WHERE h.account_id = pay.id AND h.customer_id = a.customer_id) THEN
      RAISE EXCEPTION 'deposit %: account % is not held by the depositor', a.account_no, pay.account_no USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER term_deposit_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.term_deposit
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_term_deposit();
