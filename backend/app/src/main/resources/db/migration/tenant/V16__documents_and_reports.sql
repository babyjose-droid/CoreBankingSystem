-- P2-4: documents and reports. Loan balances and statement lines from the ledger, GST fee invoices with their own
-- number series, the report catalogue with its run log, report functions (all branch-scoped), the consumer-bureau
-- extract and the dashboard metric functions. Stories US-048 (PDF), US-106 (reports), US-113, US-114, US-115.

CREATE SCHEMA IF NOT EXISTS reporting;

-- ---------------------------------------------------------------------------------------------------------
-- GST invoice numbers: their own series, issued in SQL so an invoice is numbered in the same transaction that
-- records it. Same format as the Java NumberSeries: prefix + zero-padded sequence + Luhn check digit (14 characters
-- with the starter values; GST allows at most 16). A rolled-back transaction leaves a gap, never a duplicate.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE platform.number_series DROP CONSTRAINT number_series_family_check;
ALTER TABLE platform.number_series ADD CONSTRAINT number_series_family_check
  CHECK (family IN ('LOAN','CASA','TERM_DEPOSIT','CUSTOMER','VOUCHER','GST_INVOICE'));
CREATE SEQUENCE platform.seq_gst_invoice;
INSERT INTO platform.number_series VALUES ('GST_INVOICE','7001',9,'platform.seq_gst_invoice');

CREATE FUNCTION platform.luhn_digit(body text) RETURNS int LANGUAGE sql IMMUTABLE AS $$
  SELECT ((10 - sum(CASE WHEN (length(body) - i) % 2 = 0 THEN CASE WHEN d * 2 > 9 THEN d * 2 - 9 ELSE d * 2 END ELSE d END) % 10) % 10)::int
    FROM (SELECT i, substr(body, i, 1)::int AS d FROM generate_series(1, length(body)) AS i) x
$$;

CREATE FUNCTION platform.next_number(p_family text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE s platform.number_series%ROWTYPE; v bigint; body text;
BEGIN
  SELECT * INTO s FROM platform.number_series WHERE family = p_family;
  IF NOT FOUND OR s.sequence_name !~ '^platform\.seq_[a-z_]+$' THEN
    RAISE EXCEPTION 'bad number series %', p_family USING ERRCODE = 'P0002';
  END IF;
  v := nextval(s.sequence_name::regclass);
  IF length(v::text) > s.width THEN RAISE EXCEPTION 'number series % is exhausted', p_family USING ERRCODE = '23514'; END IF;
  body := s.prefix || lpad(v::text, s.width, '0');
  RETURN body || platform.luhn_digit(body);
END $$;

-- State name for a state code as stored on a branch or an address (GST code such as '32', or the state code 'KL').
CREATE FUNCTION platform.state_name(p_code text) RETURNS text LANGUAGE sql STABLE AS $$
  SELECT name FROM platform.state WHERE gst_state_code = p_code OR code = p_code ORDER BY (gst_state_code = p_code) DESC LIMIT 1
$$;
CREATE FUNCTION platform.gst_state_code(p_code text) RETURNS text LANGUAGE sql STABLE AS $$
  SELECT coalesce((SELECT gst_state_code FROM platform.state WHERE gst_state_code = p_code OR code = p_code
                    ORDER BY (gst_state_code = p_code) DESC LIMIT 1), p_code)
$$;

-- ---------------------------------------------------------------------------------------------------------
-- The loan's GL heads (from the product snapshot frozen at booking; starter chart when a head is not set), and the
-- engine state read as rows. Dates in the stored state are ISO strings; an [y,m,d] array is accepted too.
-- ---------------------------------------------------------------------------------------------------------
CREATE VIEW lending.loan_gl AS
  SELECT l.id AS loan_id, l.loan_no,
         coalesce(l.product_snapshot->'gl'->>'principal', '1101')          AS principal_gl,
         coalesce(l.product_snapshot->'gl'->>'interestReceivable', '1102') AS interest_gl,
         coalesce(l.product_snapshot->'gl'->>'feeReceivable', '1103')      AS fee_gl,
         coalesce(l.product_snapshot->'gl'->>'penalReceivable', '1104')    AS penal_gl,
         coalesce(l.product_snapshot->'gl'->>'excessReceipts', '2302')     AS excess_gl,
         coalesce(l.product_snapshot->'gl'->>'feeIncome', '4102')          AS fee_income_gl,
         coalesce(l.product_snapshot->'gl'->>'interestIncome', '4101')     AS interest_income_gl,
         coalesce(l.product_snapshot->'gl'->>'interestSuspense', '2305')   AS suspense_gl,
         coalesce(l.product_snapshot->'gl'->>'cgst', '2201')               AS cgst_gl,
         coalesce(l.product_snapshot->'gl'->>'sgst', '2202')               AS sgst_gl,
         coalesce(l.product_snapshot->'gl'->>'igst', '2203')               AS igst_gl
    FROM lending.loan_account l;

CREATE FUNCTION lending.json_date(j jsonb) RETURNS date LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE jsonb_typeof(j)
           WHEN 'string' THEN (j #>> '{}')::date
           WHEN 'array'  THEN make_date((j->>0)::int, (j->>1)::int, (j->>2)::int)
         END
$$;

-- Demands raised on each loan (instalments that have fallen due), from the engine state.
CREATE VIEW lending.loan_demand AS
  SELECT l.id AS loan_id, l.loan_no, l.branch_code, l.product_code,
         (d->>'instalmentNo')::int                              AS instalment_no,
         lending.json_date(d->'dueDate')                        AS due_date,
         (d->>'principalDue')::numeric                          AS principal_due,
         (d->>'interestDue')::numeric                           AS interest_due,
         coalesce((d->>'principalPaid')::numeric, 0)            AS principal_paid,
         coalesce((d->>'interestPaid')::numeric, 0)             AS interest_paid,
         coalesce((d->>'principalRescheduled')::numeric, 0)     AS principal_rescheduled,
         coalesce((d->>'interestCapitalised')::numeric, 0)      AS interest_capitalised
    FROM lending.loan_account l
   CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(l.state->'demands') = 'array' THEN l.state->'demands' ELSE '[]'::jsonb END) d;

-- What the borrower owes at the end of business date p_upto, from the ledger. Interest includes interest accrued
-- but not yet due; charges are fees and penal charges with GST; advance is money received and not yet applied.
CREATE FUNCTION lending.loan_balance(p_loan uuid, p_upto date)
RETURNS TABLE (principal numeric, interest numeric, charges numeric, advance numeric) LANGUAGE sql STABLE AS $$
  SELECT coalesce(sum(CASE e.side WHEN 'DR' THEN e.amount ELSE -e.amount END) FILTER (WHERE e.gl_code = g.principal_gl), 0),
         coalesce(sum(CASE e.side WHEN 'DR' THEN e.amount ELSE -e.amount END) FILTER (WHERE e.gl_code = g.interest_gl), 0),
         coalesce(sum(CASE e.side WHEN 'DR' THEN e.amount ELSE -e.amount END) FILTER (WHERE e.gl_code IN (g.fee_gl, g.penal_gl)), 0),
         coalesce(sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END) FILTER (WHERE e.gl_code = g.excess_gl), 0)
    FROM lending.loan_gl g
    LEFT JOIN ledger.account_entry e ON e.account_no = g.loan_no AND e.business_date <= p_upto
         AND e.gl_code IN (g.principal_gl, g.interest_gl, g.fee_gl, g.penal_gl, g.excess_gl)
   WHERE g.loan_id = p_loan
$$;

-- Statement lines for a period, from the ledger entries on the loan account. Every financial transaction is one
-- line; day-end entries (interest accrual, penal charges, advance applied) between two transactions are summed into
-- one line per kind and month, so the lines stay in true order. Movements are signed: positive = charged to the
-- borrower, negative = paid, waived or reversed. The lines always add up to
-- loan_balance(p_to) - loan_balance(p_from - 1): an entry not linked to a loan transaction still appears.
CREATE FUNCTION lending.loan_statement(p_loan uuid, p_from date, p_to date)
RETURNS TABLE (line_no int, line_date date, txn_type text, particulars text, received numeric,
               principal numeric, interest numeric, charges numeric, advance numeric)
LANGUAGE sql STABLE AS $$
  WITH g AS (SELECT * FROM lending.loan_gl WHERE loan_id = p_loan),
  txn AS (
    SELECT t.id, t.seq, t.txn_type, t.amount, t.reversed_by,
           count(*) FILTER (WHERE t.txn_type <> 'EOD') OVER (ORDER BY t.seq) AS segment
      FROM lending.loan_txn t WHERE t.loan_id = p_loan),
  lot_txn AS (SELECT t.id AS txn_id, u.lot_id FROM lending.loan_txn t CROSS JOIN LATERAL unnest(t.lot_ids) u(lot_id) WHERE t.loan_id = p_loan),
  e AS (
    SELECT en.id AS entry_id, en.business_date, lot.lot_type, lot.value_date, t.id AS txn_id, t.seq, t.txn_type, t.segment,
           CASE en.side WHEN 'DR' THEN en.amount ELSE -en.amount END AS signed, en.gl_code, en.side, en.narration
      FROM g
      JOIN ledger.account_entry en ON en.account_no = g.loan_no AND en.business_date BETWEEN p_from AND p_to
           AND en.gl_code IN (g.principal_gl, g.interest_gl, g.fee_gl, g.penal_gl, g.excess_gl)
      JOIN ledger.transaction_lot lot ON lot.id = en.lot_id
      LEFT JOIN lot_txn lt ON lt.lot_id = en.lot_id
      LEFT JOIN txn t ON t.id = lt.txn_id),
  grouped AS (
    SELECT -- a transaction is one line; day-end and unlinked entries group by kind and month
           CASE WHEN e.txn_type IS NOT NULL AND e.txn_type <> 'EOD' THEN 'T' || e.txn_id::text
                ELSE 'E' || coalesce(e.segment, -1) || '|' || e.lot_type || '|' || to_char(e.business_date, 'YYYYMM') END AS grp,
           max(e.business_date) AS line_date, min(coalesce(e.seq, 2147483647)) AS first_seq, min(e.entry_id) AS first_entry,
           CASE WHEN bool_and(e.txn_type IS NOT NULL AND e.txn_type <> 'EOD') THEN min(e.txn_type) ELSE min(e.lot_type) END AS kind,
           bool_and(e.txn_type IS NOT NULL AND e.txn_type <> 'EOD') AS is_txn,
           (min(e.txn_id::text) FILTER (WHERE e.txn_type <> 'EOD'))::uuid AS txn_id,
           min(e.value_date) AS first_value, max(e.value_date) AS last_value,
           string_agg(DISTINCT e.narration, ', ') FILTER (WHERE e.lot_type = 'FEE_CHARGE' AND e.side = 'DR') AS fee_names,
           sum(e.signed) FILTER (WHERE e.gl_code = g.principal_gl)            AS principal,
           sum(e.signed) FILTER (WHERE e.gl_code = g.interest_gl)             AS interest,
           sum(e.signed) FILTER (WHERE e.gl_code IN (g.fee_gl, g.penal_gl))   AS charges,
           -sum(e.signed) FILTER (WHERE e.gl_code = g.excess_gl)              AS advance
      FROM e, g GROUP BY 1)
  SELECT (row_number() OVER (ORDER BY x.line_date, x.first_seq, x.first_entry))::int, x.line_date, x.kind,
         CASE WHEN x.is_txn THEN
                CASE x.kind
                  WHEN 'DISBURSEMENT' THEN 'Loan disbursed'
                  WHEN 'REPAYMENT'    THEN 'Payment received'
                  WHEN 'PREPAYMENT'   THEN 'Part-prepayment received'
                  WHEN 'PRECLOSURE'   THEN 'Pre-closure payment received'
                  WHEN 'CANCELLATION' THEN 'Loan cancelled in the cooling-off period'
                  WHEN 'FEE_CHARGE'   THEN 'Charge: ' || coalesce(x.fee_names, 'fee')
                  WHEN 'WAIVER'       THEN 'Waiver'
                  WHEN 'REVERSAL'     THEN 'Reversal of earlier entries'
                  WHEN 'RESTRUCTURE'  THEN 'Loan restructured'
                  WHEN 'AMENDMENT'    THEN 'Loan terms amended'
                  ELSE initcap(replace(x.kind, '_', ' ')) END
                || CASE WHEN x.kind <> 'FEE_CHARGE' AND x.fee_names IS NOT NULL THEN '; charge: ' || x.fee_names ELSE '' END
              ELSE
                CASE x.kind
                  WHEN 'ACCRUAL'           THEN 'Interest'
                  WHEN 'PENAL_CHARGE'      THEN 'Penal charges'
                  WHEN 'EXCESS_ADJUSTMENT' THEN 'Advance applied to dues'
                  ELSE initcap(replace(x.kind, '_', ' ')) END
                || ' ' || to_char(x.first_value, 'DD-Mon-YYYY')
                || CASE WHEN x.last_value <> x.first_value THEN ' to ' || to_char(x.last_value, 'DD-Mon-YYYY') ELSE '' END
         END,
         CASE WHEN NOT x.is_txn THEN NULL
              WHEN x.kind IN ('REPAYMENT','PREPAYMENT','PRECLOSURE','CANCELLATION') THEN t.amount
              WHEN x.kind = 'REVERSAL' THEN (SELECT -sum(r.amount) FROM lending.loan_txn r
                                              WHERE r.reversed_by = x.txn_id AND r.txn_type IN ('REPAYMENT','PREPAYMENT','PRECLOSURE','CANCELLATION'))
         END,
         coalesce(x.principal, 0), coalesce(x.interest, 0), coalesce(x.charges, 0), coalesce(x.advance, 0)
    FROM grouped x LEFT JOIN lending.loan_txn t ON t.id = x.txn_id
   ORDER BY 1
$$;

-- ---------------------------------------------------------------------------------------------------------
-- GST tax invoices for fees (US-106). One invoice per fee charged, numbered from the GST_INVOICE series, built
-- from the ledger entries of the fee (taxable value = credit to fee income; tax = credits to the GST heads), so an
-- invoice always equals what was posted. charge_ref links it to the loan's charge: C<n> for a fee charged to the
-- account (the engine's charge id), D<n> for the n-th fee deducted from the disbursement.
-- A fee whose transaction is reversed has its invoice CANCELLED (kept, with its number). A waiver after the invoice
-- needs a credit note, which is not built yet.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.fee_invoice (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_no       text NOT NULL UNIQUE CHECK (length(invoice_no) <= 16),
    invoice_date     date NOT NULL,
    loan_id          uuid NOT NULL REFERENCES lending.loan_account(id),
    txn_id           uuid NOT NULL REFERENCES lending.loan_txn(id),
    lot_id           uuid NOT NULL REFERENCES ledger.transaction_lot(id),
    charge_ref       text NOT NULL CHECK (charge_ref ~ '^[CD][0-9]+$'),
    description      text NOT NULL,
    sac              text NOT NULL DEFAULT '9971',
    branch_code      text NOT NULL REFERENCES platform.branch(code),
    supplier_state   text NOT NULL,                       -- GST state code of the branch
    place_of_supply  text NOT NULL,                       -- GST state code of the borrower's address
    taxable_value    platform.money NOT NULL CHECK (taxable_value >= 0),
    gst_rate         numeric(7,4),
    cgst             platform.money NOT NULL DEFAULT 0 CHECK (cgst >= 0),
    sgst             platform.money NOT NULL DEFAULT 0 CHECK (sgst >= 0),
    igst             platform.money NOT NULL DEFAULT 0 CHECK (igst >= 0),
    total            numeric(20,4) GENERATED ALWAYS AS (taxable_value + cgst + sgst + igst) STORED,
    status           text NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED','CANCELLED')),
    cancelled_on     date,
    cancelled_by_txn uuid REFERENCES lending.loan_txn(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (lot_id, description),
    CONSTRAINT invoice_tax_matches_place CHECK ((igst = 0 OR supplier_state <> place_of_supply)
                                            AND (cgst + sgst = 0 OR supplier_state = place_of_supply)),
    CONSTRAINT invoice_cancel_fields CHECK ((status = 'CANCELLED') = (cancelled_on IS NOT NULL))
);
CREATE UNIQUE INDEX fee_invoice_one_live_per_charge ON lending.fee_invoice (loan_id, charge_ref) WHERE status = 'ISSUED';
CREATE INDEX fee_invoice_date ON lending.fee_invoice (invoice_date);

-- An invoice never changes or disappears; the one change allowed is ISSUED -> CANCELLED, once.
CREATE FUNCTION lending.guard_fee_invoice() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'tax invoices cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.status = 'ISSUED' AND NEW.status = 'CANCELLED'
     -- total is generated from the other columns and is not yet computed for NEW in a BEFORE trigger
     AND (to_jsonb(NEW) - '{status,cancelled_on,cancelled_by_txn,total}'::text[])
       = (to_jsonb(OLD) - '{status,cancelled_on,cancelled_by_txn,total}'::text[]) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'tax invoice % is immutable', OLD.invoice_no USING ERRCODE = '42501';
END $$;
CREATE TRIGGER fee_invoice_guard BEFORE UPDATE OR DELETE ON lending.fee_invoice
  FOR EACH ROW EXECUTE FUNCTION lending.guard_fee_invoice();

-- Issues the invoices not yet issued (for one loan, or for every loan) and cancels those whose fee was reversed.
-- Safe to call any number of times. Returns the number of invoices issued.
CREATE FUNCTION lending.issue_fee_invoices(p_loan uuid DEFAULT NULL) RETURNS int LANGUAGE plpgsql AS $$
DECLARE r record; n int := 0;
BEGIN
  UPDATE lending.fee_invoice i
     SET status = 'CANCELLED', cancelled_on = rev.business_date, cancelled_by_txn = rev.id
    FROM lending.loan_txn t JOIN lending.loan_txn rev ON rev.id = t.reversed_by
   WHERE i.txn_id = t.id AND i.status = 'ISSUED' AND (p_loan IS NULL OR i.loan_id = p_loan);

  FOR r IN
    WITH lots AS (
      SELECT t.id AS txn_id, t.loan_id, t.seq, u.lot_id, u.ord, lot.lot_type, lot.business_date,
             coalesce((t.state_before->>'chargeSeq')::int, 0) AS seq_before
        FROM lending.loan_txn t
       CROSS JOIN LATERAL unnest(t.lot_ids) WITH ORDINALITY u(lot_id, ord)
        JOIN ledger.transaction_lot lot ON lot.id = u.lot_id
       WHERE t.reversed_by IS NULL AND t.txn_type <> 'REVERSAL' AND lot.lot_type IN ('FEE_CHARGE','DISBURSEMENT')
         AND (p_loan IS NULL OR t.loan_id = p_loan)),
    fees AS (
      SELECT lo.txn_id, lo.loan_id, lo.seq, lo.lot_id, lo.ord, lo.lot_type, lo.business_date, lo.seq_before,
             l.branch_code, l.product_snapshot,
             -- the fee income line is "<fee name> <loan no>"; the tax lines are "CGST <fee name> <loan no>"
             regexp_replace(CASE WHEN e.gl_code = g.fee_income_gl THEN e.narration
                                 ELSE regexp_replace(e.narration, '^(CGST|SGST|IGST) ', '') END,
                            ' ' || g.loan_no || '$', '') AS fee_name,
             min(e.id) AS first_entry,
             coalesce(sum(e.amount) FILTER (WHERE e.gl_code = g.fee_income_gl), 0) AS taxable,
             coalesce(sum(e.amount) FILTER (WHERE e.gl_code = g.cgst_gl), 0) AS cgst,
             coalesce(sum(e.amount) FILTER (WHERE e.gl_code = g.sgst_gl), 0) AS sgst,
             coalesce(sum(e.amount) FILTER (WHERE e.gl_code = g.igst_gl), 0) AS igst
        FROM lots lo
        JOIN lending.loan_account l ON l.id = lo.loan_id
        JOIN lending.loan_gl g ON g.loan_id = lo.loan_id
        JOIN ledger.account_entry e ON e.lot_id = lo.lot_id AND e.side = 'CR'
             AND e.gl_code IN (g.fee_income_gl, g.cgst_gl, g.sgst_gl, g.igst_gl)
       GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
    numbered AS (
      SELECT f.*,
             CASE WHEN f.lot_type = 'FEE_CHARGE'
                  THEN 'C' || (f.seq_before + dense_rank() OVER (PARTITION BY f.txn_id, f.lot_type ORDER BY f.ord))
                  ELSE 'D' || row_number() OVER (PARTITION BY f.txn_id, f.lot_type ORDER BY f.first_entry) END AS charge_ref
        FROM fees f WHERE f.taxable > 0)
    SELECT x.* FROM numbered x
     WHERE NOT EXISTS (SELECT 1 FROM lending.fee_invoice i WHERE i.lot_id = x.lot_id AND i.description = x.fee_name)
     ORDER BY x.business_date, x.seq, x.ord, x.first_entry
  LOOP
    INSERT INTO lending.fee_invoice (invoice_no, invoice_date, loan_id, txn_id, lot_id, charge_ref, description, branch_code,
                                     supplier_state, place_of_supply, taxable_value, gst_rate, cgst, sgst, igst)
    SELECT platform.next_number('GST_INVOICE'), r.business_date, r.loan_id, r.txn_id, r.lot_id, r.charge_ref, r.fee_name, r.branch_code,
           s.supplier, s.recipient,
           r.taxable,
           coalesce((SELECT (f->>'gstRatePercent')::numeric
                       FROM jsonb_array_elements(CASE WHEN jsonb_typeof(r.product_snapshot->'fees') = 'array'
                                                      THEN r.product_snapshot->'fees' ELSE '[]'::jsonb END) f
                      WHERE f->>'name' = r.fee_name LIMIT 1),
                    round((r.cgst + r.sgst + r.igst) * 100 / r.taxable, 2)),
           r.cgst, r.sgst, r.igst
      FROM (SELECT platform.gst_state_code(coalesce(r.product_snapshot->>'supplierState', b.state_code)) AS supplier,
                   platform.gst_state_code(coalesce(r.product_snapshot->>'recipientState', r.product_snapshot->>'supplierState', b.state_code)) AS recipient
              FROM platform.branch b WHERE b.code = r.branch_code) s;
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Report catalogue and run log (US-113).
-- parameters is a JSON Schema object ({"type":"object","properties":{…},"required":[…]}); the API checks a run's
-- parameters against it. sql_function is reporting.<name>(p_user text, p_params jsonb): it returns the rows and
-- filters them to the branches p_user may see. schedule and email_to are recorded for the scheduler and e-mail
-- delivery, which are not built yet (they wait for the notification provider decision, OI-06).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE reporting.report_definition (
    code              text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{2,40}$'),
    name              text NOT NULL,
    description       text NOT NULL,
    parameters        jsonb NOT NULL DEFAULT '{"type":"object","properties":{}}' CHECK (jsonb_typeof(parameters) = 'object'),
    permission        text NOT NULL CHECK (permission ~ '^[a-z]+:[a-z-]+$'),
    sql_function      text NOT NULL CHECK (sql_function ~ '^reporting\.[a-z][a-z0-9_]*$'),
    output_format     text NOT NULL DEFAULT 'CSV' CHECK (output_format IN ('CSV','UCRF')),
    contains_pii      boolean NOT NULL DEFAULT false,      -- unmasked personal data: every run and download is audited as an export
    all_branches_only boolean NOT NULL DEFAULT false,      -- a partial file would be wrong (bureau submission)
    schedule          text,                                -- cron expression; NULL = on demand. Not acted on yet.
    email_to          text[] NOT NULL DEFAULT '{}',        -- recipients of a scheduled run. Not acted on yet.
    active            boolean NOT NULL DEFAULT true,
    sort_order        int NOT NULL DEFAULT 0
);

CREATE TABLE reporting.report_run (
    id              uuid PRIMARY KEY,
    report_code     text NOT NULL REFERENCES reporting.report_definition(code),
    requested_by    text NOT NULL,
    requested_at    timestamptz NOT NULL DEFAULT now(),
    business_date   date NOT NULL,
    parameters      jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(parameters) = 'object'),
    status          text NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    row_count       int CHECK (row_count >= 0),
    rejected_count  int NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),   -- rows left out of a bureau file
    artifact_key    text,                                -- key in the document store: tenants/<code>/reports/…
    artifact_bytes  bigint,
    artifact_sha256 text,
    file_name       text,
    content_type    text,
    error           text,
    finished_at     timestamptz,
    schedule        text,                                -- the schedule that started the run; NULL for a run asked for by a user
    CONSTRAINT run_completed_has_artifact CHECK (status <> 'COMPLETED' OR (artifact_key IS NOT NULL AND row_count IS NOT NULL AND finished_at IS NOT NULL)),
    CONSTRAINT run_failed_has_error CHECK (status <> 'FAILED' OR (error IS NOT NULL AND finished_at IS NOT NULL)),
    CONSTRAINT run_artifact_key_is_tenant_scoped CHECK (artifact_key IS NULL OR artifact_key ~ '^tenants/[a-z][a-z0-9-]{2,30}/reports/[A-Za-z0-9._/-]+$')
);
CREATE INDEX report_run_by_user ON reporting.report_run (requested_by, requested_at DESC);
CREATE INDEX report_run_recent ON reporting.report_run (requested_at DESC);

-- A run is written once as RUNNING and finished once; a finished run is a record of who exported what.
CREATE FUNCTION reporting.guard_report_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'report runs cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED','FAILED')
     AND NEW.id = OLD.id AND NEW.report_code = OLD.report_code AND NEW.requested_by = OLD.requested_by
     AND NEW.requested_at = OLD.requested_at AND NEW.parameters = OLD.parameters AND NEW.business_date = OLD.business_date THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'report run % is finished and cannot be changed', OLD.id USING ERRCODE = '42501';
END $$;
CREATE TRIGGER report_run_guard BEFORE UPDATE OR DELETE ON reporting.report_run
  FOR EACH ROW EXECUTE FUNCTION reporting.guard_report_run();

-- ---------------------------------------------------------------------------------------------------------
-- Helpers for the report functions.
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION reporting.business_date() RETURNS date LANGUAGE sql STABLE AS $$
  SELECT business_date FROM platform.business_day WHERE id = 1
$$;

-- A date parameter, or the default when it is absent. A value that is not a date fails with 22007/22008.
CREATE FUNCTION reporting.param_date(p_params jsonb, p_name text, p_default date) RETURNS date LANGUAGE sql STABLE AS $$
  SELECT coalesce(nullif(p_params->>p_name, '')::date, p_default)
$$;

CREATE FUNCTION reporting.dpd_bucket(p_dpd int) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN p_dpd <= 0 THEN '0' WHEN p_dpd <= 30 THEN '1-30' WHEN p_dpd <= 60 THEN '31-60' WHEN p_dpd <= 90 THEN '61-90'
              WHEN p_dpd <= 180 THEN '91-180' WHEN p_dpd <= 365 THEN '181-365' ELSE '>365' END
$$;
CREATE FUNCTION reporting.dpd_buckets() RETURNS TABLE (bucket text, sort_order int) LANGUAGE sql IMMUTABLE AS $$
  VALUES ('0', 1), ('1-30', 2), ('31-60', 3), ('61-90', 4), ('91-180', 5), ('181-365', 6), ('>365', 7)
$$;

CREATE FUNCTION reporting.is_npa(p_class text) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
  SELECT p_class IN ('SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS')
$$;

-- Live loans of the branches p_user may see, as at the end of p_as_of. Today (or no date): the current figures.
-- An earlier date: the day-end figures kept in lending.dpd_history for that date (loans with no row that day,
-- for example not yet disbursed or already closed, are left out).
CREATE FUNCTION reporting.loan_position(p_user text, p_as_of date)
RETURNS TABLE (loan_id uuid, loan_no text, branch_code text, product_code text, customer_id uuid, sanctioned_amount numeric,
               principal_outstanding numeric, overdue_amount numeric, dpd int, asset_class text)
LANGUAGE sql STABLE AS $$
  SELECT l.id, l.loan_no, l.branch_code, l.product_code, l.customer_id, l.sanctioned_amount::numeric,
         l.principal_outstanding::numeric, l.overdue_amount::numeric, l.dpd, l.asset_class
    FROM lending.loan_account l
   WHERE (p_as_of IS NULL OR p_as_of >= reporting.business_date())
     AND l.status IN ('ACTIVE','FROZEN')
     AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
  UNION ALL
  SELECT l.id, l.loan_no, l.branch_code, l.product_code, l.customer_id, l.sanctioned_amount::numeric,
         h.principal_outstanding::numeric, h.overdue_amount::numeric, h.dpd, h.asset_class
    FROM lending.dpd_history h JOIN lending.loan_account l ON l.id = h.loan_id
   WHERE p_as_of < reporting.business_date() AND h.business_date = p_as_of
     AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Reports. Every function takes the user (branch scope) and the run's parameters, and returns plain rows.
-- ---------------------------------------------------------------------------------------------------------
-- Loan book: portfolio outstanding by branch and product.
CREATE FUNCTION reporting.rpt_loan_book(p_user text, p_params jsonb)
RETURNS TABLE (as_of date, branch_code text, branch_name text, product_code text, product_name text, loans bigint,
               sanctioned_amount numeric, principal_outstanding numeric, overdue_amount numeric, npa_loans bigint, npa_principal numeric)
LANGUAGE sql STABLE AS $$
  SELECT reporting.param_date(p_params, 'asOf', reporting.business_date()), p.branch_code, b.name, p.product_code, pr.name, count(*),
         sum(p.sanctioned_amount), sum(p.principal_outstanding), sum(p.overdue_amount),
         count(*) FILTER (WHERE reporting.is_npa(p.asset_class)),
         coalesce(sum(p.principal_outstanding) FILTER (WHERE reporting.is_npa(p.asset_class)), 0)
    FROM reporting.loan_position(p_user, reporting.param_date(p_params, 'asOf', NULL)) p
    JOIN platform.branch b ON b.code = p.branch_code
    JOIN lending.loan_product pr ON pr.code = p.product_code
   GROUP BY p.branch_code, b.name, p.product_code, pr.name
   ORDER BY p.branch_code, p.product_code
$$;

-- DPD bucket ageing by branch: every bucket is listed for a branch that has loans, zero or not.
CREATE FUNCTION reporting.rpt_dpd_ageing(p_user text, p_params jsonb)
RETURNS TABLE (as_of date, branch_code text, branch_name text, bucket text, loans bigint, principal_outstanding numeric,
               overdue_amount numeric)
LANGUAGE sql STABLE AS $$
  WITH pos AS (SELECT * FROM reporting.loan_position(p_user, reporting.param_date(p_params, 'asOf', NULL)))
  SELECT reporting.param_date(p_params, 'asOf', reporting.business_date()), br.branch_code, b.name, k.bucket,
         count(pos.loan_id), coalesce(sum(pos.principal_outstanding), 0), coalesce(sum(pos.overdue_amount), 0)
    FROM (SELECT DISTINCT branch_code FROM pos) br
    JOIN platform.branch b ON b.code = br.branch_code
   CROSS JOIN reporting.dpd_buckets() k
    LEFT JOIN pos ON pos.branch_code = br.branch_code AND reporting.dpd_bucket(pos.dpd) = k.bucket
   GROUP BY br.branch_code, b.name, k.bucket, k.sort_order
   ORDER BY br.branch_code, k.sort_order
$$;

-- Collections against demand: instalments that fell due in the period and what has been collected against them
-- (to date), plus all money received in the period (which also covers arrears, advances and prepayments).
CREATE FUNCTION reporting.rpt_collections_vs_demand(p_user text, p_params jsonb)
RETURNS TABLE (from_date date, to_date date, branch_code text, product_code text, instalments_due bigint, demand_principal numeric,
               demand_interest numeric, demand_total numeric, collected_against_demand numeric, demand_unpaid numeric,
               collection_efficiency_pct numeric, receipts_in_period numeric)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.param_date(p_params, 'from', date_trunc('month', reporting.business_date())::date) AS f,
                      reporting.param_date(p_params, 'to', reporting.business_date()) AS t),
  dem AS (
    SELECT d.branch_code, d.product_code, count(*) AS n, sum(d.principal_due) AS dp, sum(d.interest_due) AS di,
           sum(d.principal_paid + d.interest_paid) AS paid,
           sum(d.principal_due + d.interest_due - d.principal_paid - d.interest_paid - d.principal_rescheduled - d.interest_capitalised) AS unpaid
      FROM lending.loan_demand d, prm
     WHERE d.due_date BETWEEN prm.f AND prm.t
       AND d.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
     GROUP BY d.branch_code, d.product_code),
  rec AS (
    SELECT l.branch_code, l.product_code, sum(t.amount) AS received
      FROM lending.loan_txn t JOIN lending.loan_account l ON l.id = t.loan_id, prm
     WHERE t.txn_type IN ('REPAYMENT','PREPAYMENT','PRECLOSURE','CANCELLATION') AND t.reversed_by IS NULL
       AND t.business_date BETWEEN prm.f AND prm.t
       AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
     GROUP BY l.branch_code, l.product_code)
  SELECT prm.f, prm.t, coalesce(dem.branch_code, rec.branch_code), coalesce(dem.product_code, rec.product_code),
         coalesce(dem.n, 0), coalesce(dem.dp, 0), coalesce(dem.di, 0), coalesce(dem.dp + dem.di, 0), coalesce(dem.paid, 0),
         coalesce(dem.unpaid, 0),
         CASE WHEN coalesce(dem.dp + dem.di, 0) > 0 THEN round(dem.paid * 100 / (dem.dp + dem.di), 2) END,
         coalesce(rec.received, 0)
    FROM dem FULL JOIN rec ON rec.branch_code = dem.branch_code AND rec.product_code = dem.product_code, prm
   ORDER BY 3, 4
$$;

-- Disbursement register.
CREATE FUNCTION reporting.rpt_disbursement_register(p_user text, p_params jsonb)
RETURNS TABLE (disbursed_on date, loan_no text, customer_no text, customer_name text, branch_code text, product_code text,
               sanctioned_amount numeric, disbursed_amount numeric, deducted_at_disbursal numeric, net_disbursed numeric,
               rate numeric, tenor_months int, emi numeric, first_due_date date, source text, disbursed_by text)
LANGUAGE sql STABLE AS $$
  SELECT t.business_date, l.loan_no, c.customer_no, c.display_name, l.branch_code, l.product_code, l.sanctioned_amount::numeric,
         t.amount::numeric, (t.amount - coalesce(l.net_disbursed, t.amount))::numeric, coalesce(l.net_disbursed, t.amount)::numeric,
         l.rate::numeric, l.tenor_months, l.emi::numeric, l.first_due_date, l.source, t.created_by
    FROM lending.loan_txn t
    JOIN lending.loan_account l ON l.id = t.loan_id
    JOIN customer.customer c ON c.id = l.customer_id
   WHERE t.txn_type = 'DISBURSEMENT' AND t.reversed_by IS NULL
     AND t.business_date BETWEEN reporting.param_date(p_params, 'from', date_trunc('month', reporting.business_date())::date)
                             AND reporting.param_date(p_params, 'to', reporting.business_date())
     AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   ORDER BY t.business_date, l.loan_no
$$;

-- NPA register with provisions: required = secured part x secured rate + unsecured part x unsecured rate
-- (lending.provisioning_rate), as the day-end computes it.
CREATE FUNCTION reporting.rpt_npa_register(p_user text, p_params jsonb)
RETURNS TABLE (as_of date, loan_no text, customer_no text, customer_name text, branch_code text, product_code text, asset_class text,
               npa_since date, dpd int, principal_outstanding numeric, overdue_amount numeric, interest_suspense numeric,
               secured_portion numeric, provision_required numeric, provision_held numeric, provision_shortfall numeric,
               restructured_on date)
LANGUAGE sql STABLE AS $$
  SELECT reporting.business_date(), l.loan_no, c.customer_no, c.display_name, l.branch_code, l.product_code, l.asset_class, l.npa_since,
         l.dpd, l.principal_outstanding::numeric, l.overdue_amount::numeric, l.suspense::numeric, l.secured_portion::numeric,
         q.required, l.provision_held::numeric, greatest(q.required - l.provision_held, 0)::numeric, l.restructured_on
    FROM lending.loan_account l
    JOIN customer.customer c ON c.id = l.customer_id
   CROSS JOIN LATERAL (
     SELECT round((least(greatest(l.secured_portion, 0), l.principal_outstanding) * rs.rate
                   + (l.principal_outstanding - least(greatest(l.secured_portion, 0), l.principal_outstanding)) * ru.rate) / 100, 2) AS required
       FROM lending.provisioning_rate rs, lending.provisioning_rate ru
      WHERE rs.asset_class = l.asset_class AND rs.secured AND ru.asset_class = l.asset_class AND NOT ru.secured) q
   WHERE l.status IN ('ACTIVE','FROZEN') AND reporting.is_npa(l.asset_class)
     AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   ORDER BY l.branch_code, l.npa_since, l.loan_no
$$;

-- GST output register: every fee invoice of the period with its tax split and place of supply (US-106).
CREATE FUNCTION reporting.rpt_gst_output_register(p_user text, p_params jsonb)
RETURNS TABLE (invoice_no text, invoice_date date, status text, loan_no text, customer_no text, customer_name text, branch_code text,
               supplier_state text, place_of_supply text, place_of_supply_name text, supply_type text, sac text, description text,
               taxable_value numeric, gst_rate numeric, cgst numeric, sgst numeric, igst numeric, total numeric, cancelled_on date)
LANGUAGE sql STABLE AS $$
  SELECT i.invoice_no, i.invoice_date, i.status, l.loan_no, c.customer_no, c.display_name, i.branch_code, i.supplier_state,
         i.place_of_supply, platform.state_name(i.place_of_supply),
         CASE WHEN i.supplier_state = i.place_of_supply THEN 'INTRA_STATE' ELSE 'INTER_STATE' END, i.sac, i.description,
         i.taxable_value::numeric, i.gst_rate, i.cgst::numeric, i.sgst::numeric, i.igst::numeric, i.total, i.cancelled_on
    FROM lending.fee_invoice i
    JOIN lending.loan_account l ON l.id = i.loan_id
    JOIN customer.customer c ON c.id = l.customer_id
   WHERE i.invoice_date BETWEEN reporting.param_date(p_params, 'from', date_trunc('month', reporting.business_date())::date)
                            AND reporting.param_date(p_params, 'to', reporting.business_date())
     AND i.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   ORDER BY i.invoice_no
$$;

-- GST output by state and rate (live invoices only): the figures for the GST return.
CREATE FUNCTION reporting.rpt_gst_output_summary(p_user text, p_params jsonb)
RETURNS TABLE (from_date date, to_date date, supplier_state text, place_of_supply text, place_of_supply_name text, supply_type text,
               gst_rate numeric, invoices bigint, taxable_value numeric, cgst numeric, sgst numeric, igst numeric, total_tax numeric)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.param_date(p_params, 'from', date_trunc('month', reporting.business_date())::date) AS f,
                      reporting.param_date(p_params, 'to', reporting.business_date()) AS t)
  SELECT prm.f, prm.t, i.supplier_state, i.place_of_supply, platform.state_name(i.place_of_supply),
         CASE WHEN i.supplier_state = i.place_of_supply THEN 'INTRA_STATE' ELSE 'INTER_STATE' END, i.gst_rate, count(*),
         sum(i.taxable_value)::numeric, sum(i.cgst)::numeric, sum(i.sgst)::numeric, sum(i.igst)::numeric, sum(i.cgst + i.sgst + i.igst)::numeric
    FROM lending.fee_invoice i, prm
   WHERE i.status = 'ISSUED' AND i.invoice_date BETWEEN prm.f AND prm.t
     AND i.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   GROUP BY prm.f, prm.t, i.supplier_state, i.place_of_supply, i.gst_rate
   ORDER BY i.supplier_state, i.place_of_supply, i.gst_rate
$$;

-- Interest accrual and suspense movement: for the interest income, interest suspense and interest receivable heads
-- used by loans, the opening balance, the movement by kind of entry, and the closing balance, by branch.
-- Balances are shown as debit less credit (a credit balance, as income and suspense have, is negative).
CREATE FUNCTION reporting.rpt_interest_accrual_suspense(p_user text, p_params jsonb)
RETURNS TABLE (from_date date, to_date date, branch_code text, gl_code text, gl_name text, movement text, debit numeric, credit numeric,
               balance numeric)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.param_date(p_params, 'from', date_trunc('month', reporting.business_date())::date) AS f,
                      reporting.param_date(p_params, 'to', reporting.business_date()) AS t),
  heads AS (SELECT interest_income_gl AS gl FROM lending.loan_gl UNION SELECT suspense_gl FROM lending.loan_gl
            UNION SELECT interest_gl FROM lending.loan_gl UNION VALUES ('4101'), ('2305'), ('1102')),
  ent AS (
    SELECT e.branch_code, e.gl_code, e.business_date, lot.lot_type, e.side, e.amount
      FROM ledger.account_entry e JOIN ledger.transaction_lot lot ON lot.id = e.lot_id, prm
     WHERE e.gl_code IN (SELECT gl FROM heads) AND e.business_date <= prm.t
       AND e.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)),
  rows AS (
    SELECT ent.branch_code, ent.gl_code, 1 AS ord, 'Opening balance' AS movement, NULL::numeric AS debit, NULL::numeric AS credit,
           sum(CASE ent.side WHEN 'DR' THEN ent.amount ELSE -ent.amount END) FILTER (WHERE ent.business_date < prm.f) AS balance
      FROM ent, prm GROUP BY ent.branch_code, ent.gl_code
    UNION ALL
    SELECT ent.branch_code, ent.gl_code, 2, ent.lot_type, coalesce(sum(ent.amount) FILTER (WHERE ent.side = 'DR'), 0),
           coalesce(sum(ent.amount) FILTER (WHERE ent.side = 'CR'), 0), NULL
      FROM ent, prm WHERE ent.business_date >= prm.f GROUP BY ent.branch_code, ent.gl_code, ent.lot_type
    UNION ALL
    SELECT ent.branch_code, ent.gl_code, 3, 'Closing balance', NULL, NULL,
           sum(CASE ent.side WHEN 'DR' THEN ent.amount ELSE -ent.amount END)
      FROM ent GROUP BY ent.branch_code, ent.gl_code)
  SELECT prm.f, prm.t, r.branch_code, r.gl_code, h.name, r.movement, r.debit, r.credit, coalesce(r.balance, CASE WHEN r.ord <> 2 THEN 0 END)
    FROM rows r JOIN ledger.gl_head h ON h.code = r.gl_code, prm
   ORDER BY r.branch_code, r.gl_code, r.ord, r.movement
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Consumer credit-bureau extract (US-114): one row per loan to be reported for the month of p_params.asOf (default:
-- the business date). Reported: live and written-off loans, and loans closed or cancelled in that month.
-- The personal data comes back ENCRYPTED (pan_cipher, mobile_cipher, address_cipher): only the application, for a
-- user with bureau:export, decrypts it to write the file. The account type code comes from the system property
-- bureau.account-type.<product code in lower case>, else bureau.account-type.default; NULL when neither is set
-- (the account is then left out of the file with a reason).
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION reporting.bureau_consumer_rows(p_user text, p_params jsonb)
RETURNS TABLE (loan_no text, account_type text, date_opened date, date_last_payment date, date_closed date, sanctioned_amount numeric,
               current_balance numeric, amount_overdue numeric, dpd int, asset_class text, status text, restructured boolean,
               emi numeric, tenure_months int, rate numeric, customer_name text, date_of_birth date, gender text,
               pan_cipher bytea, mobile_cipher bytea, address_cipher bytea, city text, state_code text, pincode text)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.param_date(p_params, 'asOf', reporting.business_date()) AS d, reporting.business_date() AS today)
  SELECT l.loan_no,
         coalesce((SELECT value FROM platform.system_property WHERE key = 'bureau.account-type.' || lower(l.product_code)),
                  (SELECT value FROM platform.system_property WHERE key = 'bureau.account-type.default')),
         coalesce(l.disbursed_on, l.open_date),
         (SELECT max(t.business_date) FROM lending.loan_txn t
           WHERE t.loan_id = l.id AND t.reversed_by IS NULL AND t.business_date <= prm.d
             AND t.txn_type IN ('REPAYMENT','PREPAYMENT','PRECLOSURE','CANCELLATION')),
         CASE WHEN l.status IN ('CLOSED','CANCELLED') THEN l.closed_on END,
         l.sanctioned_amount::numeric,
         (SELECT b.principal + b.interest + b.charges FROM lending.loan_balance(l.id, prm.d) b),
         coalesce(h.overdue_amount, l.overdue_amount)::numeric,
         coalesce(h.dpd, l.dpd), coalesce(h.asset_class, l.asset_class), l.status, l.restructured_on IS NOT NULL AND l.restructured_on <= prm.d,
         l.emi::numeric, l.tenor_months, coalesce(l.current_rate, l.rate)::numeric,
         c.display_name, c.date_of_birth, c.gender, c.pan_cipher, c.mobile_cipher, a.line_cipher, a.city,
         platform.gst_state_code(a.state_code), a.pincode
    FROM lending.loan_account l
    JOIN customer.customer c ON c.id = l.customer_id
    LEFT JOIN customer.address a ON a.customer_id = c.id AND a.address_type = 'COMMUNICATION'
   CROSS JOIN prm
    LEFT JOIN LATERAL (SELECT x.* FROM lending.dpd_history x
                        WHERE prm.d < prm.today AND x.loan_id = l.id AND x.business_date <= prm.d
                        ORDER BY x.business_date DESC LIMIT 1) h ON true
   WHERE coalesce(l.disbursed_on, l.open_date) <= prm.d
     AND (l.status IN ('ACTIVE','FROZEN','WRITTEN_OFF')
          OR (l.status IN ('CLOSED','CANCELLED') AND l.disbursed_on IS NOT NULL
              AND l.closed_on BETWEEN date_trunc('month', prm.d)::date AND prm.d))
     AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   ORDER BY l.loan_no
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Dashboard (US-115): one function per group of metrics, each limited to the branches p_user may see.
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION reporting.dashboard_portfolio(p_user text)
RETURNS TABLE (active_loans bigint, portfolio_outstanding numeric, overdue_amount numeric, gross_npa numeric, npa_loans bigint,
               npa_percent numeric)
LANGUAGE sql STABLE AS $$
  SELECT count(*), coalesce(sum(p.principal_outstanding), 0), coalesce(sum(p.overdue_amount), 0),
         coalesce(sum(p.principal_outstanding) FILTER (WHERE reporting.is_npa(p.asset_class)), 0),
         count(*) FILTER (WHERE reporting.is_npa(p.asset_class)),
         CASE WHEN coalesce(sum(p.principal_outstanding), 0) > 0
              THEN round(coalesce(sum(p.principal_outstanding) FILTER (WHERE reporting.is_npa(p.asset_class)), 0) * 100
                         / sum(p.principal_outstanding), 2) END
    FROM reporting.loan_position(p_user, NULL) p
$$;

-- Disbursed and collected today and month to date, and collection efficiency for instalments due this month
-- (collected against them / their amount).
CREATE FUNCTION reporting.dashboard_flows(p_user text)
RETURNS TABLE (business_date date, disbursed_today numeric, disbursed_mtd numeric, disbursements_today bigint, disbursements_mtd bigint,
               collected_today numeric, collected_mtd numeric, demand_mtd numeric, collected_against_demand_mtd numeric,
               collection_efficiency_mtd numeric)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.business_date() AS d, date_trunc('month', reporting.business_date())::date AS m),
  tx AS (
    SELECT t.txn_type, t.business_date, t.amount
      FROM lending.loan_txn t JOIN lending.loan_account l ON l.id = t.loan_id, prm
     WHERE t.reversed_by IS NULL AND t.business_date BETWEEN prm.m AND prm.d
       AND t.txn_type IN ('DISBURSEMENT','REPAYMENT','PREPAYMENT','PRECLOSURE','CANCELLATION')
       AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)),
  dem AS (
    SELECT coalesce(sum(d.principal_due + d.interest_due), 0) AS demand, coalesce(sum(d.principal_paid + d.interest_paid), 0) AS paid
      FROM lending.loan_demand d, prm
     WHERE d.due_date BETWEEN prm.m AND prm.d
       AND d.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v))
  SELECT prm.d,
         coalesce((SELECT sum(amount) FROM tx WHERE txn_type = 'DISBURSEMENT' AND tx.business_date = prm.d), 0),
         coalesce((SELECT sum(amount) FROM tx WHERE txn_type = 'DISBURSEMENT'), 0),
         (SELECT count(*) FROM tx WHERE txn_type = 'DISBURSEMENT' AND tx.business_date = prm.d),
         (SELECT count(*) FROM tx WHERE txn_type = 'DISBURSEMENT'),
         coalesce((SELECT sum(amount) FROM tx WHERE txn_type <> 'DISBURSEMENT' AND tx.business_date = prm.d), 0),
         coalesce((SELECT sum(amount) FROM tx WHERE txn_type <> 'DISBURSEMENT'), 0),
         dem.demand, dem.paid,
         CASE WHEN dem.demand > 0 THEN round(dem.paid * 100 / dem.demand, 2) END
    FROM prm, dem
$$;

-- DPD distribution: count and principal per bucket; every bucket is returned.
CREATE FUNCTION reporting.dashboard_dpd(p_user text)
RETURNS TABLE (bucket text, sort_order int, loans bigint, amount numeric) LANGUAGE sql STABLE AS $$
  SELECT k.bucket, k.sort_order, count(p.loan_id), coalesce(sum(p.principal_outstanding), 0)
    FROM reporting.dpd_buckets() k
    LEFT JOIN reporting.loan_position(p_user, NULL) p ON reporting.dpd_bucket(p.dpd) = k.bucket
   GROUP BY k.bucket, k.sort_order ORDER BY k.sort_order
$$;

-- Work waiting and the last end of day. Approvals without a branch are counted only for all-branch users.
CREATE FUNCTION reporting.dashboard_operations(p_user text)
RETURNS TABLE (pending_approvals bigint, last_eod_business_date date, last_eod_status text, last_eod_finished_at timestamptz,
               open_eod_exceptions bigint)
LANGUAGE sql STABLE AS $$
  SELECT (SELECT count(*) FROM platform.approval_request r
           WHERE r.status = 'PENDING'
             AND (r.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
                  OR (r.branch_code IS NULL AND platform.sees_all_branches(p_user)))),
         e.business_date, e.status, e.finished_at,
         (SELECT count(*) FROM platform.eod_exception x WHERE NOT x.resolved AND platform.sees_all_branches(p_user))
    FROM (SELECT 1) one
    LEFT JOIN LATERAL (SELECT business_date, status, finished_at FROM platform.eod_run ORDER BY id DESC LIMIT 1) e ON true
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Catalogue.
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO reporting.report_definition (code, name, description, parameters, permission, sql_function, output_format, contains_pii,
                                         all_branches_only, sort_order) VALUES
  ('LOAN_BOOK', 'Loan book', 'Portfolio outstanding by branch and product: loans, amount sanctioned, principal outstanding, overdue and NPA.',
   '{"type":"object","properties":{"asOf":{"type":"string","format":"date","title":"As at (day-end; blank = now)"}}}',
   'report:run', 'reporting.rpt_loan_book', 'CSV', false, false, 10),
  ('DPD_AGEING', 'DPD bucket ageing', 'Loans and principal outstanding by days past due: 0, 1-30, 31-60, 61-90, 91-180, 181-365 and above 365, by branch.',
   '{"type":"object","properties":{"asOf":{"type":"string","format":"date","title":"As at (day-end; blank = now)"}}}',
   'report:run', 'reporting.rpt_dpd_ageing', 'CSV', false, false, 20),
  ('COLLECTIONS_VS_DEMAND', 'Collections against demand', 'Instalments that fell due in the period, what has been collected against them, and all money received in the period, by branch and product.',
   '{"type":"object","properties":{"from":{"type":"string","format":"date","title":"From"},"to":{"type":"string","format":"date","title":"To"}},"required":["from","to"]}',
   'report:run', 'reporting.rpt_collections_vs_demand', 'CSV', false, false, 30),
  ('DISBURSEMENT_REGISTER', 'Disbursement register', 'Every loan disbursed in the period with the amount, deductions and net payout.',
   '{"type":"object","properties":{"from":{"type":"string","format":"date","title":"From"},"to":{"type":"string","format":"date","title":"To"}},"required":["from","to"]}',
   'report:run', 'reporting.rpt_disbursement_register', 'CSV', false, false, 40),
  ('NPA_REGISTER', 'NPA register with provisions', 'Non-performing loans with class, NPA date, outstanding, interest suspense, provision required and held.',
   '{"type":"object","properties":{}}',
   'report:run', 'reporting.rpt_npa_register', 'CSV', false, false, 50),
  ('GST_OUTPUT_REGISTER', 'GST output register', 'Fee invoices of the period with taxable value, CGST, SGST, IGST and place of supply.',
   '{"type":"object","properties":{"from":{"type":"string","format":"date","title":"From"},"to":{"type":"string","format":"date","title":"To"}},"required":["from","to"]}',
   'report:run', 'reporting.rpt_gst_output_register', 'CSV', false, false, 60),
  ('GST_OUTPUT_SUMMARY', 'GST output by state', 'Taxable value and tax of live fee invoices by place of supply and rate, for the GST return.',
   '{"type":"object","properties":{"from":{"type":"string","format":"date","title":"From"},"to":{"type":"string","format":"date","title":"To"}},"required":["from","to"]}',
   'report:run', 'reporting.rpt_gst_output_summary', 'CSV', false, false, 70),
  ('INTEREST_ACCRUAL_SUSPENSE', 'Interest accrual and suspense movement', 'Opening balance, movement by kind of entry and closing balance of interest income, interest suspense and interest receivable, by branch.',
   '{"type":"object","properties":{"from":{"type":"string","format":"date","title":"From"},"to":{"type":"string","format":"date","title":"To"}},"required":["from","to"]}',
   'report:run', 'reporting.rpt_interest_accrual_suspense', 'CSV', false, false, 80),
  ('BUREAU_CONSUMER', 'Credit bureau consumer file (UCRF-style)', 'Monthly consumer-bureau file, one line per loan, with unmasked personal data. The layout must be validated against each bureau''s format specification before the first submission.',
   '{"type":"object","properties":{"asOf":{"type":"string","format":"date","title":"Date reported (blank = business date)"}}}',
   'bureau:export', 'reporting.bureau_consumer_rows', 'UCRF', true, true, 90);

-- Invoices for fees charged before this migration.
SELECT lending.issue_fee_invoices(NULL);
