-- V25: rate bases 9 and 10 and repayment method 18 (product owner, 10-Oct-2026).
--  * Floating interest rate slab: an interest table whose rows are a spread over the product's benchmark (mode
--    SPREAD). The loan's rate at booking and at every reset is benchmark + the slab's spread.
--  * Elapsed-tenure interest rate slab: the product's rate by month of the loan (rate_steps), known at booking,
--    disclosed in the KFS and applied at day-end like a reset (EMI changes, tenure kept). It is a fixed rate for the
--    RBI reset circular: nothing in it follows a benchmark.
--  * Tranche bullet (method 18): each tranche is repaid as a bullet on its own maturity date, given when it is
--    disbursed; interest falls due monthly on the total outstanding.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Interest tables: SPREAD rows are added to the benchmark of the product that uses the table.
-- ---------------------------------------------------------------------------------------------------------
DO $$
DECLARE c text;
BEGIN
  -- declared inline in V12: found by what it checks, not by a guessed name
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'lending.interest_table'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%ADDITIVE%' LOOP
    EXECUTE format('ALTER TABLE lending.interest_table DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE lending.interest_table
  ADD CONSTRAINT interest_table_mode_check CHECK (mode IN ('ADDITIVE','ABSOLUTE','SPREAD')),
  -- a spread table has no base of its own: the base is the product's benchmark on the day
  ADD CONSTRAINT interest_table_spread_no_base CHECK (mode <> 'SPREAD' OR base_rate = 0);

-- SPREAD: the rate returned is the slab's spread; the caller adds the benchmark (LoanService.price).
CREATE OR REPLACE FUNCTION lending.resolve_rate(p_table text, p_amount numeric, p_tenor int)
RETURNS TABLE (rate numeric, explanation text) LANGUAGE plpgsql STABLE AS $$
DECLARE t lending.interest_table%ROWTYPE; s lending.interest_slab%ROWTYPE;
BEGIN
  SELECT * INTO t FROM lending.interest_table WHERE code = p_table;
  IF NOT FOUND THEN RAISE EXCEPTION 'interest table % not found', p_table USING ERRCODE = 'P0002'; END IF;
  SELECT * INTO s FROM lending.interest_slab
   WHERE table_code = p_table AND p_amount BETWEEN min_amount AND max_amount AND p_tenor BETWEEN min_tenor_months AND max_tenor_months;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'no slab in % for amount % and tenor % months', p_table, p_amount, p_tenor USING ERRCODE = 'P0002';
  END IF;
  IF t.mode = 'ADDITIVE' THEN
    RETURN QUERY SELECT (t.base_rate + s.rate)::numeric, format('base %s%% + slab %s%% = %s%%', trim_scale(t.base_rate::numeric), trim_scale(s.rate::numeric), trim_scale((t.base_rate + s.rate)::numeric));
  ELSIF t.mode = 'SPREAD' THEN
    RETURN QUERY SELECT s.rate::numeric, format('slab spread %s%%', trim_scale(s.rate::numeric));
  ELSE
    RETURN QUERY SELECT s.rate::numeric, format('slab rate %s%%', trim_scale(s.rate::numeric));
  END IF;
END $$;

-- The table's mode decides how the product uses it: a SPREAD table needs a benchmark, any other table none.
CREATE FUNCTION lending.check_product_rate_table() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m text;
BEGIN
  IF NEW.interest_table_code IS NULL THEN RETURN NEW; END IF;
  SELECT mode INTO m FROM lending.interest_table WHERE code = NEW.interest_table_code;
  IF (m = 'SPREAD') <> (NEW.benchmark_code IS NOT NULL) THEN
    RAISE EXCEPTION 'product %: a SPREAD interest table goes with a benchmark, and a benchmark-linked product takes only a SPREAD table',
          NEW.code USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER loan_product_rate_table BEFORE INSERT OR UPDATE ON lending.loan_product
  FOR EACH ROW EXECUTE FUNCTION lending.check_product_rate_table();

-- ---------------------------------------------------------------------------------------------------------
-- 2. Products: the spread may come from the interest table; elapsed-tenure steps; method 18.
-- ---------------------------------------------------------------------------------------------------------
-- A benchmark-linked product with a spread of its own never used an interest table (LoanService priced it on the
-- benchmark first): the table code it may carry is dropped so that the link below names one source of spread.
UPDATE lending.loan_product SET interest_table_code = NULL
 WHERE benchmark_code IS NOT NULL AND spread IS NOT NULL AND interest_table_code IS NOT NULL;
ALTER TABLE lending.loan_product
  DROP CONSTRAINT product_floating_link,
  DROP CONSTRAINT loan_product_repayment_method_check,
  DROP CONSTRAINT product_tranche_methods,
  ADD CONSTRAINT product_floating_link CHECK ((benchmark_code IS NULL AND spread IS NULL AND reset_frequency_months IS NULL)
      OR (benchmark_code IS NOT NULL AND reset_frequency_months IS NOT NULL AND rate_type = 'FLOATING'
          AND (spread IS NOT NULL) <> (interest_table_code IS NOT NULL))),
  ADD CONSTRAINT loan_product_repayment_method_check
      CHECK (repayment_method IN ('EQUATED','STEP_EQUATED','FIXED_PRINCIPAL','BULLET_TOTAL_INTEREST','BULLET_PERIODIC_INTEREST','STRUCTURED',
                                  'TRANCHE_BULLET')),
  -- tranches re-schedule the loan on its method: only where the engine can (LoanAccount.requireTrancheable)
  ADD CONSTRAINT product_tranche_methods CHECK (NOT (multiple_disbursements OR top_up_allowed)
      OR (interest_basis = 'DAILY_REDUCING' AND principal_every = 1
          AND repayment_method IN ('EQUATED','FIXED_PRINCIPAL','BULLET_TOTAL_INTEREST','BULLET_PERIODIC_INTEREST','TRANCHE_BULLET'))),
  -- a tranche bullet product is one disbursed in tranches, interest by days on the balance
  ADD CONSTRAINT product_tranche_bullet CHECK (repayment_method <> 'TRANCHE_BULLET'
      OR (multiple_disbursements AND interest_basis = 'DAILY_REDUCING' AND NOT pre_emi)),
  -- [{"fromMonth":1,"ratePercent":10.5},{"fromMonth":13,"ratePercent":11}]: from month 1, ascending (ProductService)
  ADD COLUMN rate_steps jsonb CHECK (rate_steps IS NULL OR (jsonb_typeof(rate_steps) = 'array' AND jsonb_array_length(rate_steps) >= 2)),
  ADD CONSTRAINT product_rate_steps CHECK (rate_steps IS NULL
      OR (repayment_method = 'EQUATED' AND frequency = 'MONTHLY' AND interest_basis = 'DAILY_REDUCING' AND rate_type = 'FIXED'
          AND benchmark_code IS NULL AND interest_table_code IS NULL AND NOT multiple_disbursements AND NOT top_up_allowed
          AND bpi_mode IN ('NONE','ADD_TO_FIRST_INSTALMENT')));

-- ---------------------------------------------------------------------------------------------------------
-- 3. Tranches: each tranche of a tranche-bullet loan has its own maturity (null on every other method, and on
--    tranches booked before this migration). The engine state holds the same date (LoanAccount.TrancheRow).
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_tranche
  ADD COLUMN maturity_date date,
  ADD CONSTRAINT tranche_maturity_after_disbursal CHECK (maturity_date IS NULL OR maturity_date > business_date);
