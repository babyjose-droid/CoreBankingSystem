-- Phase 1 reference data: legal entity, branch sets, territory, enumerations, system properties, tax rates,
-- weekly offs. Stories US-010, US-011, US-012, US-013, US-016, US-017.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE platform.legal_entity (
    id              smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),       -- one legal entity per tenant database
    legal_name      text NOT NULL,
    entity_type     text NOT NULL CHECK (entity_type IN ('NBFC','BANK','SFB','COOP_BANK','HFC','MFI')),
    cin             text,
    rbi_registration text,
    pan             text,
    gstin           text,
    registered_state text NOT NULL,
    financial_year_start_month smallint NOT NULL DEFAULT 4 CHECK (financial_year_start_month BETWEEN 1 AND 12)
);

CREATE TABLE platform.branch_set (
    code  text PRIMARY KEY,
    name  text NOT NULL
);
CREATE TABLE platform.branch_set_member (
    set_code    text NOT NULL REFERENCES platform.branch_set(code),
    branch_code text NOT NULL REFERENCES platform.branch(code),
    PRIMARY KEY (set_code, branch_code)
);

-- A branch cannot be closed while it still holds live accounts.
CREATE FUNCTION platform.check_branch_close() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE live int;
BEGIN
  IF NEW.status = 'CLOSED' AND OLD.status <> 'CLOSED' THEN
    SELECT count(*) INTO live FROM lending.loan_account
     WHERE branch_code = NEW.code AND status IN ('SANCTIONED','ACTIVE');
    IF live > 0 THEN
      RAISE EXCEPTION 'branch % has % live loan accounts; transfer them before closing', NEW.code, live
        USING ERRCODE = '23514';
    END IF;
    IF NEW.is_head_office THEN
      RAISE EXCEPTION 'the head office cannot be closed' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER branch_close_guard BEFORE UPDATE ON platform.branch FOR EACH ROW EXECUTE FUNCTION platform.check_branch_close();

-- Weekly offs: NULL branch = all branches; week_of_month NULL = every week (e.g. Sunday),
-- otherwise the nth occurrence in the month (e.g. 2nd and 4th Saturday for banks).
CREATE TABLE platform.weekly_off (
    branch_code   text REFERENCES platform.branch(code),
    day_of_week   smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- ISO: 1 = Monday
    week_of_month smallint CHECK (week_of_month BETWEEN 1 AND 5),
    UNIQUE NULLS NOT DISTINCT (branch_code, day_of_week, week_of_month)
);

CREATE FUNCTION platform.is_working_day(d date, branch text DEFAULT NULL) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT NOT EXISTS (
           SELECT 1 FROM platform.holiday h
            WHERE h.day = d AND (h.branch_code IS NULL OR h.branch_code = branch))
     AND NOT EXISTS (
           SELECT 1 FROM platform.weekly_off w
            WHERE (w.branch_code IS NULL OR w.branch_code = branch)
              AND w.day_of_week = extract(isodow FROM d)
              AND (w.week_of_month IS NULL OR w.week_of_month = (extract(day FROM d)::int - 1) / 7 + 1))
$$;

CREATE FUNCTION platform.next_working_day(d date, branch text DEFAULT NULL) RETURNS date
LANGUAGE plpgsql STABLE AS $$
DECLARE x date := d + 1; guard int := 0;
BEGIN
  WHILE NOT platform.is_working_day(x, branch) LOOP
    x := x + 1; guard := guard + 1;
    IF guard > 366 THEN RAISE EXCEPTION 'no working day within a year after %', d; END IF;
  END LOOP;
  RETURN x;
END $$;

-- Territory masters (US-012). Loaded from the India Post / LGD files by upload.
CREATE TABLE platform.country  (code text PRIMARY KEY, name text NOT NULL);
CREATE TABLE platform.state    (code text PRIMARY KEY, country_code text NOT NULL REFERENCES platform.country(code),
                                name text NOT NULL, gst_state_code text UNIQUE, is_union_territory boolean NOT NULL DEFAULT false);
CREATE TABLE platform.district (id serial PRIMARY KEY, state_code text NOT NULL REFERENCES platform.state(code),
                                name text NOT NULL, UNIQUE (state_code, name));
CREATE TABLE platform.city     (id serial PRIMARY KEY, district_id int NOT NULL REFERENCES platform.district(id),
                                name text NOT NULL, UNIQUE (district_id, name));
CREATE TABLE platform.pincode  (pincode text NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
                                city_id int NOT NULL REFERENCES platform.city(id), PRIMARY KEY (pincode, city_id));

-- Enumerations and system properties (US-013). Changes go through maker-checker and are audited.
CREATE TABLE platform.enumeration (
    enum_type  text NOT NULL,
    code       text NOT NULL,
    label      text NOT NULL,
    sort_order int NOT NULL DEFAULT 0,
    active     boolean NOT NULL DEFAULT true,
    PRIMARY KEY (enum_type, code)
);
CREATE TABLE platform.system_property (
    key        text PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9_.-]+$'),
    value      text NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Tax rates (US-017): effective-dated; periods for one code can never overlap.
CREATE TABLE platform.tax_rate (
    code           text NOT NULL,
    tax_type       text NOT NULL CHECK (tax_type IN ('GST','TDS')),
    rate_percent   numeric(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    effective_from date NOT NULL,
    effective_to   date,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    PRIMARY KEY (code, effective_from),
    EXCLUDE USING gist (code WITH =, daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') WITH &&)
);

CREATE FUNCTION platform.tax_rate_on(tax_code text, value_date date) RETURNS numeric
LANGUAGE plpgsql STABLE AS $$
DECLARE r numeric;
BEGIN
  SELECT rate_percent INTO r FROM platform.tax_rate
   WHERE code = tax_code AND effective_from <= value_date AND (effective_to IS NULL OR effective_to >= value_date);
  IF NOT FOUND THEN RAISE EXCEPTION 'no % rate effective on %', tax_code, value_date USING ERRCODE = 'P0002'; END IF;
  RETURN r;
END $$;

-- Business date (US-016): may only move through end of day. The EOD service sets
-- `corebanking.eod = on` for its transaction and calls platform.advance_business_date().
CREATE FUNCTION platform.guard_business_day() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.business_date IS DISTINCT FROM OLD.business_date
     AND coalesce(current_setting('corebanking.eod', true), 'off') <> 'on' THEN
    RAISE EXCEPTION 'the business date can only change through end of day' USING ERRCODE = '42501';
  END IF;
  IF NEW.business_date < OLD.business_date THEN
    RAISE EXCEPTION 'the business date cannot move backwards' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER business_day_guard BEFORE UPDATE ON platform.business_day FOR EACH ROW EXECUTE FUNCTION platform.guard_business_day();
CREATE TRIGGER business_day_no_delete BEFORE DELETE ON platform.business_day FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

CREATE FUNCTION platform.advance_business_date() RETURNS date LANGUAGE plpgsql AS $$
DECLARE cur date; nxt date;
BEGIN
  SELECT business_date INTO cur FROM platform.business_day WHERE id = 1 FOR UPDATE;
  nxt := platform.next_working_day(cur);
  PERFORM set_config('corebanking.eod', 'on', true);
  UPDATE platform.business_day SET business_date = nxt, status = 'OPEN' WHERE id = 1;
  PERFORM set_config('corebanking.eod', 'off', true);
  RETURN nxt;
END $$;
