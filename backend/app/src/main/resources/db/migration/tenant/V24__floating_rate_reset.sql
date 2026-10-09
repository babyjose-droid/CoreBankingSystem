-- V24: automatic floating-rate reset at day-end (RBI "Reset of Floating Interest Rate on Equated Monthly Instalments
-- (EMI) based Personal Loans", 18-Aug-2023; product owner decision D-14). On its reset date a floating loan's rate
-- becomes benchmark + spread through the amendment code (LoanAccount); the history row and the loan transaction are
-- of kind RATE_RESET. Resets outside the product band are applied and flagged, never refused.

-- ---------------------------------------------------------------------------------------------------------
-- 1. What a reset changes. The product's default is the lender's board policy; the borrower may choose otherwise
--    for the loan. Switching to a fixed rate is an amendment (SWITCH_TO_FIXED) that ends the resets.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_product
  ADD COLUMN rate_reset_option text NOT NULL DEFAULT 'KEEP_TENURE_CHANGE_EMI'
      CHECK (rate_reset_option IN ('KEEP_EMI_CHANGE_TENURE','KEEP_TENURE_CHANGE_EMI'));

ALTER TABLE lending.loan_account
  ADD COLUMN rate_reset_preference text CHECK (rate_reset_preference IN ('KEEP_EMI_CHANGE_TENURE','KEEP_TENURE_CHANGE_EMI')),
  ADD COLUMN rate_outside_band     boolean NOT NULL DEFAULT false,   -- the last reset set a rate outside the product band
  ADD COLUMN rate_fixed_since      date,                             -- a floating loan switched to a fixed rate
  ADD CONSTRAINT loan_reset_choice_floating CHECK (rate_reset_preference IS NULL OR benchmark_code IS NOT NULL),
  ADD CONSTRAINT loan_fixed_since_floating CHECK (rate_fixed_since IS NULL OR (benchmark_code IS NOT NULL AND next_rate_reset IS NULL));

-- ---------------------------------------------------------------------------------------------------------
-- 2. History: the day-end's rate changes have no maker-checker request (they follow from the contract), so their
--    rows carry no approval and no checker. Every other kind keeps both, and four eyes.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_amendment DROP CONSTRAINT loan_amendment_kind_check;
ALTER TABLE lending.loan_amendment
  ALTER COLUMN approval_id DROP NOT NULL,
  ALTER COLUMN checked_by DROP NOT NULL,
  ADD CONSTRAINT loan_amendment_kind_check CHECK (kind IN ('RATE_CHANGE','TENURE_CHANGE','EMI_CHANGE','DUE_DAY_CHANGE','RESTRUCTURE',
                                                           'MATURITY_CHANGE','SANCTION_CHANGE','NPA_OVERRIDE','SWITCH_TO_FIXED',
                                                           'RATE_RESET','RATE_STEP')),
  ADD CONSTRAINT amendment_system_kinds CHECK ((kind IN ('RATE_RESET','RATE_STEP')) = (approval_id IS NULL)
                                               AND (kind IN ('RATE_RESET','RATE_STEP')) = (checked_by IS NULL));

-- ---------------------------------------------------------------------------------------------------------
-- 3. Floating loans booked before this migration: the floating link goes into the engine parameters (frozen like
--    the rest of the product, US-043; band and maximum tenure from the product as it is now) and the next reset
--    date into the engine state. A date already passed is caught up at the next day-end, at the benchmark rate of
--    the latest reset date passed.
-- ---------------------------------------------------------------------------------------------------------
UPDATE lending.loan_account l
   SET product_snapshot = l.product_snapshot || jsonb_build_object('floating', jsonb_build_object(
           'benchmarkCode', l.benchmark_code, 'spread', l.spread::numeric, 'resetMonths', l.reset_frequency_months,
           'defaultOption', p.rate_reset_option, 'maxTenureMonths', p.max_tenor_months,
           'minRate', p.min_rate::numeric, 'maxRate', p.max_rate::numeric))
  FROM lending.loan_product p
 WHERE p.code = l.product_code AND l.benchmark_code IS NOT NULL AND jsonb_typeof(l.product_snapshot) = 'object'
   AND NOT l.product_snapshot ? 'floating';

UPDATE lending.loan_account
   SET state = state || jsonb_build_object('rateReset', jsonb_build_object('next', next_rate_reset, 'fixedSince', NULL,
                                                                         'outsideBand', false, 'stepsApplied', 1))
 WHERE benchmark_code IS NOT NULL AND status IN ('ACTIVE','FROZEN') AND jsonb_typeof(state) = 'object' AND state <> '{}'::jsonb
   AND state->'rateReset' IS NULL;

-- ---------------------------------------------------------------------------------------------------------
-- 4. Reports: floating loans due for a reset in the next n days (with the rate the reset would set on the
--    benchmark recorded for that date), and the resets and steps applied in a period. Branch-scoped like every
--    report; the API lists the same rows (GET /rate-resets/upcoming, GET /rate-resets).
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION reporting.rpt_rate_resets_due(p_user text, p_params jsonb)
RETURNS TABLE (loan_id uuid, loan_no text, customer_no text, customer_name text, branch_code text, product_code text,
               next_rate_reset date, benchmark_code text, benchmark_rate numeric, spread numeric, current_rate numeric,
               projected_rate numeric, projected_outside_band boolean, reset_option text, principal_outstanding numeric,
               current_emi numeric)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.business_date() AS d, coalesce(nullif(p_params->>'days', '')::int, 30) AS n)
  SELECT l.id, l.loan_no, c.customer_no, c.display_name, l.branch_code, l.product_code, l.next_rate_reset, l.benchmark_code,
         b.rate, l.spread::numeric, coalesce(l.current_rate, l.rate)::numeric, b.rate + l.spread,
         b.rate + l.spread NOT BETWEEN p.min_rate AND p.max_rate,
         coalesce(l.rate_reset_preference, l.product_snapshot->'floating'->>'defaultOption', p.rate_reset_option),
         l.principal_outstanding::numeric, coalesce(l.current_emi, l.emi)::numeric
    FROM lending.loan_account l
    JOIN customer.customer c ON c.id = l.customer_id
    JOIN lending.loan_product p ON p.code = l.product_code
    CROSS JOIN prm
    CROSS JOIN LATERAL (SELECT lending.benchmark_rate_on(l.benchmark_code, greatest(l.next_rate_reset, prm.d)) AS rate) b
   WHERE l.status IN ('ACTIVE','FROZEN') AND l.benchmark_code IS NOT NULL AND l.next_rate_reset IS NOT NULL
     AND l.next_rate_reset <= prm.d + prm.n
     AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   ORDER BY l.next_rate_reset, l.loan_no
$$;

CREATE FUNCTION reporting.rpt_rate_resets_applied(p_user text, p_params jsonb)
RETURNS TABLE (effective_date date, loan_id uuid, loan_no text, customer_no text, branch_code text, product_code text, kind text,
               benchmark_code text, benchmark_rate numeric, spread numeric, rate_before numeric, rate_after numeric,
               emi_before numeric, emi_after numeric, tenure_before int, tenure_after int, maturity_before date, maturity_after date,
               requested_option text, applied_option text, fallback_reason text, outside_band boolean, reset_dates text,
               posted_on date)
LANGUAGE sql STABLE AS $$
  SELECT a.business_date, l.id, l.loan_no, c.customer_no, l.branch_code, l.product_code, a.kind,
         a.parameters->>'benchmarkCode', (a.parameters->>'benchmarkRate')::numeric, (a.parameters->>'spread')::numeric,
         a.rate_before::numeric, a.rate_after::numeric, a.emi_before::numeric, a.emi_after::numeric, a.tenure_before, a.tenure_after,
         a.maturity_before, a.maturity_after, a.parameters->>'requestedOption', a.parameters->>'appliedOption',
         a.parameters->>'fallbackReason', coalesce((a.parameters->>'outsideBand')::boolean, false),
         (SELECT string_agg(x, ' ') FROM jsonb_array_elements_text(coalesce(a.parameters->'resetDates', '[]'::jsonb)) x),
         t.business_date
    FROM lending.loan_amendment a
    JOIN lending.loan_txn t ON t.id = a.txn_id
    JOIN lending.loan_account l ON l.id = a.loan_id
    JOIN customer.customer c ON c.id = l.customer_id
   WHERE a.kind IN ('RATE_RESET','RATE_STEP') AND a.reversed_by IS NULL
     AND a.business_date BETWEEN reporting.param_date(p_params, 'from', date_trunc('month', reporting.business_date())::date)
                             AND reporting.param_date(p_params, 'to', reporting.business_date())
     AND (coalesce(p_params->>'outsideBandOnly', '') NOT IN ('true','yes','1') OR coalesce((a.parameters->>'outsideBand')::boolean, false))
     AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   ORDER BY a.business_date, l.loan_no
$$;

INSERT INTO reporting.report_definition (code, name, description, parameters, permission, sql_function, output_format, contains_pii,
                                         all_branches_only, sort_order) VALUES
  ('RATE_RESETS_DUE', 'Floating-rate resets due', 'Floating-rate loans whose next reset falls in the next n days, with the rate the reset would set on the benchmark recorded for that date and whether it is outside the product band.',
   '{"type":"object","properties":{"days":{"type":"string","title":"Days ahead (blank = 30)"}}}',
   'report:run', 'reporting.rpt_rate_resets_due', 'CSV', false, false, 85),
  ('RATE_RESETS_APPLIED', 'Floating-rate resets applied', 'Rate resets and elapsed-tenure steps applied at day-end in the period: benchmark, spread, rate, EMI and tenure before and after, the option applied and why, and resets outside the product band (D-14).',
   '{"type":"object","properties":{"from":{"type":"string","format":"date","title":"From"},"to":{"type":"string","format":"date","title":"To"},"outsideBandOnly":{"type":"string","title":"Only outside the band (true / blank)"}},"required":["from","to"]}',
   'report:run', 'reporting.rpt_rate_resets_applied', 'CSV', false, false, 86);
