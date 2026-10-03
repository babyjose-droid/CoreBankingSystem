-- Usage metering per tenant (US-004) and the index of support access requests (US-007).
-- The control plane still holds no customer data: counts and sizes only.

CREATE FUNCTION control.forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% on % is not allowed: the record is append-only', TG_OP, TG_TABLE_NAME USING ERRCODE = '42501';
END $$;

-- One row per tenant and calendar day (IST). The figures at the end of the day are a snapshot taken by the
-- tenant's USAGE_SNAPSHOT job; API calls are added through the day by every application instance.
CREATE TABLE control.usage_daily (
    tenant_id        uuid NOT NULL REFERENCES control.tenant(id),
    day              date NOT NULL,
    active_loans     bigint CHECK (active_loans >= 0),
    active_customers bigint CHECK (active_customers >= 0),
    staff_users      bigint CHECK (staff_users >= 0),
    api_calls        bigint NOT NULL DEFAULT 0 CHECK (api_calls >= 0),
    document_bytes   bigint CHECK (document_bytes >= 0),      -- files in the document store under the tenant's prefix
    database_bytes   bigint CHECK (database_bytes >= 0),      -- pg_database_size of the tenant database
    storage_bytes    bigint GENERATED ALWAYS AS (coalesce(document_bytes, 0) + coalesce(database_bytes, 0)) STORED,
    captured_at      timestamptz,                             -- when the snapshot figures were taken; NULL = only API calls so far
    PRIMARY KEY (tenant_id, day)
);
CREATE TRIGGER usage_daily_no_delete BEFORE DELETE ON control.usage_daily FOR EACH ROW EXECUTE FUNCTION control.forbid_change();

-- Adds API calls counted by one instance since its last flush. Several instances add to the same row.
-- A tenant code the control plane does not know is ignored (returns false): the counter must never fail a flush.
CREATE FUNCTION control.add_api_calls(p_tenant text, p_day date, p_calls bigint) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE t uuid;
BEGIN
  IF p_calls IS NULL OR p_calls <= 0 THEN RETURN false; END IF;
  SELECT id INTO t FROM control.tenant WHERE code = p_tenant;
  IF NOT FOUND THEN RETURN false; END IF;
  INSERT INTO control.usage_daily AS u (tenant_id, day, api_calls) VALUES (t, p_day, p_calls)
  ON CONFLICT (tenant_id, day) DO UPDATE SET api_calls = u.api_calls + EXCLUDED.api_calls;
  RETURN true;
END $$;

-- Stores the day's snapshot figures; a later snapshot of the same day replaces them. API calls are not touched.
CREATE FUNCTION control.record_usage(p_tenant text, p_day date, p_active_loans bigint, p_active_customers bigint, p_staff_users bigint,
                                     p_document_bytes bigint, p_database_bytes bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE t uuid;
BEGIN
  SELECT id INTO t FROM control.tenant WHERE code = p_tenant;
  IF NOT FOUND THEN RAISE EXCEPTION 'tenant % is not known to the control plane', p_tenant USING ERRCODE = 'P0002'; END IF;
  INSERT INTO control.usage_daily AS u (tenant_id, day, active_loans, active_customers, staff_users, document_bytes, database_bytes, captured_at)
  VALUES (t, p_day, p_active_loans, p_active_customers, p_staff_users, p_document_bytes, p_database_bytes, now())
  ON CONFLICT (tenant_id, day) DO UPDATE SET active_loans = EXCLUDED.active_loans, active_customers = EXCLUDED.active_customers,
      staff_users = EXCLUDED.staff_users, document_bytes = EXCLUDED.document_bytes, database_bytes = EXCLUDED.database_bytes,
      captured_at = EXCLUDED.captured_at;
END $$;

-- Monthly figures for billing: the peak and the closing value of each count, and the API calls of the month.
-- p_month is any day of the month; p_tenant NULL = every tenant with usage in the month.
CREATE FUNCTION control.usage_monthly(p_month date, p_tenant text DEFAULT NULL)
RETURNS TABLE (tenant_code text, legal_name text, edition text, month text, days_measured bigint,
               peak_active_loans bigint, closing_active_loans bigint, peak_active_customers bigint, closing_active_customers bigint,
               peak_staff_users bigint, api_calls numeric, peak_storage_bytes bigint, closing_storage_bytes bigint)
LANGUAGE sql STABLE AS $$
  WITH m AS (SELECT date_trunc('month', p_month)::date AS first, (date_trunc('month', p_month) + interval '1 month')::date AS next),
  d AS (
    -- snap = 1 marks the last day of the month that has snapshot figures: the closing values
    SELECT u.*, CASE WHEN u.captured_at IS NOT NULL
                     THEN row_number() OVER (PARTITION BY u.tenant_id, (u.captured_at IS NOT NULL) ORDER BY u.day DESC) END AS snap
      FROM control.usage_daily u, m WHERE u.day >= m.first AND u.day < m.next)
  SELECT t.code, t.legal_name, t.edition_code, to_char(m.first, 'YYYY-MM'),
         count(*) FILTER (WHERE d.captured_at IS NOT NULL),
         max(d.active_loans), max(d.active_loans) FILTER (WHERE d.snap = 1),
         max(d.active_customers), max(d.active_customers) FILTER (WHERE d.snap = 1),
         max(d.staff_users), sum(d.api_calls),
         max(d.storage_bytes) FILTER (WHERE d.captured_at IS NOT NULL), max(d.storage_bytes) FILTER (WHERE d.snap = 1)
    FROM d JOIN control.tenant t ON t.id = d.tenant_id CROSS JOIN m
   WHERE p_tenant IS NULL OR t.code = p_tenant
   GROUP BY t.code, t.legal_name, t.edition_code, m.first
   ORDER BY t.code
$$;

-- Support access: the request and its approval live in the tenant's own database (tenant V19), where the tenant
-- admin decides and every use is audited. The control plane keeps only this index, so an engineer can list
-- their requests across tenants. It records that access was asked for, never that it was given.
CREATE TABLE control.support_request (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES control.tenant(id),
    engineer_subject text NOT NULL,
    engineer         text NOT NULL,
    ticket           text NOT NULL,
    duration_minutes int NOT NULL CHECK (duration_minutes BETWEEN 15 AND 480),
    requested_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX support_request_engineer ON control.support_request (engineer_subject, requested_at DESC);
CREATE TRIGGER support_request_immutable BEFORE UPDATE OR DELETE ON control.support_request
  FOR EACH ROW EXECUTE FUNCTION control.forbid_change();
