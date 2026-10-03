-- P2-6 product completion: repayment methods, frequencies and rate bases on the product (US-039, US-054), benchmark
-- rates for floating loans, disbursement in tranches (US-050), sanctioned-amount / maturity amendments and the
-- asset-class override (US-059), product templates (US-038), and GST credit notes for fees waived or reversed after
-- their tax invoice was issued (US-106).

-- ---------------------------------------------------------------------------------------------------------
-- 1. Product: method, frequency, interest basis, broken-period interest, tranches, floating-rate link.
--    min_tenor_months / max_tenor_months count periods of the product's frequency (months for MONTHLY).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.benchmark (
    code        text PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]{2,20}$'),
    name        text NOT NULL,
    source      text NOT NULL,                       -- who publishes it: RBI, FBIL, the lender's own ALCO …
    external    boolean NOT NULL DEFAULT true        -- an external benchmark in RBI's sense (repo, T-bill, FBIL rate)
);
-- Rate history. A rate applies from its effective date until the next one. History is never rewritten.
CREATE TABLE lending.benchmark_rate (
    benchmark_code text NOT NULL REFERENCES lending.benchmark(code),
    effective_from date NOT NULL,
    rate           platform.rate NOT NULL CHECK (rate >= 0),
    recorded_by    text NOT NULL,
    approval_id    uuid REFERENCES platform.approval_request(id),
    recorded_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (benchmark_code, effective_from)
);
CREATE TRIGGER benchmark_rate_immutable BEFORE UPDATE OR DELETE ON lending.benchmark_rate
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();
CREATE FUNCTION lending.benchmark_rate_on(p_code text, p_date date) RETURNS numeric LANGUAGE sql STABLE AS $$
  SELECT rate::numeric FROM lending.benchmark_rate
   WHERE benchmark_code = p_code AND effective_from <= p_date ORDER BY effective_from DESC LIMIT 1
$$;
-- The benchmarks themselves; their rates are recorded by the lender (maker-checker), never seeded.
INSERT INTO lending.benchmark (code, name, source, external) VALUES
  ('REPO',  'RBI policy repo rate', 'Reserve Bank of India', true),
  ('TBILL91', '91-day Treasury bill yield', 'FBIL', true),
  ('LENDER_PLR', 'Lender''s prime lending rate', 'Lender (ALCO)', false);

ALTER TABLE lending.loan_product DROP CONSTRAINT loan_product_repayment_method_check;
DO $$
DECLARE c text;
BEGIN
  -- constraints declared inline in earlier migrations: found by what they check, not by a guessed name
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'lending.loan_product'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%day_count%' LOOP
    EXECUTE format('ALTER TABLE lending.loan_product DROP CONSTRAINT %I', c);
  END LOOP;
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'lending.fee_rule'::regclass AND contype = 'c'
              AND (pg_get_constraintdef(oid) LIKE '%deduct_from_disbursal%' OR pg_get_constraintdef(oid) LIKE '%PART_PREPAYMENT%') LOOP
    EXECUTE format('ALTER TABLE lending.fee_rule DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE lending.loan_product
  ADD CONSTRAINT loan_product_repayment_method_check
      CHECK (repayment_method IN ('EQUATED','STEP_EQUATED','FIXED_PRINCIPAL','BULLET_TOTAL_INTEREST','BULLET_PERIODIC_INTEREST','STRUCTURED')),
  ADD CONSTRAINT loan_product_day_count_check
      CHECK (day_count IN ('ACTUAL_365','ACTUAL_360','ACTUAL_ACTUAL','THIRTY_360','THIRTY_E_360','ACTUAL_366','ACTUAL_364','ACTUAL_336','ACTUAL_372')),
  ADD COLUMN frequency        text NOT NULL DEFAULT 'MONTHLY'
      CHECK (frequency IN ('DAILY','WEEKLY','FORTNIGHTLY','MONTHLY','QUARTERLY','HALF_YEARLY','YEARLY')),
  ADD COLUMN interest_basis   text NOT NULL DEFAULT 'DAILY_REDUCING' CHECK (interest_basis IN ('DAILY_REDUCING','PERIODIC_REDUCING','FLAT')),
  ADD COLUMN bpi_mode         text NOT NULL DEFAULT 'NONE'
      CHECK (bpi_mode IN ('NONE','ADD_TO_FIRST_INSTALMENT','SEPARATE_DEMAND','DEDUCT_AT_DISBURSAL')),
  ADD COLUMN step_percent     numeric(7,4) CHECK (step_percent > -50 AND step_percent <= 100 AND step_percent <> 0),
  ADD COLUMN step_every       int CHECK (step_every >= 1),
  ADD COLUMN principal_every  int NOT NULL DEFAULT 1 CHECK (principal_every >= 1),
  ADD COLUMN multiple_disbursements boolean NOT NULL DEFAULT false,
  ADD COLUMN pre_emi          boolean NOT NULL DEFAULT false,     -- interest only on the amount drawn until fully disbursed
  ADD COLUMN top_up_allowed   boolean NOT NULL DEFAULT false,     -- the sanctioned amount of a live account may be raised
  ADD COLUMN benchmark_code   text REFERENCES lending.benchmark(code),
  ADD COLUMN spread           platform.rate,                      -- floating: rate = benchmark + spread
  ADD COLUMN reset_frequency_months int CHECK (reset_frequency_months BETWEEN 1 AND 60),
  ADD CONSTRAINT product_step_fields CHECK ((repayment_method = 'STEP_EQUATED') = (step_percent IS NOT NULL AND step_every IS NOT NULL)
                                            AND (step_percent IS NULL) = (step_every IS NULL)),
  ADD CONSTRAINT product_principal_every CHECK (principal_every = 1 OR repayment_method = 'FIXED_PRINCIPAL'),
  ADD CONSTRAINT product_flat_is_equated CHECK (interest_basis <> 'FLAT' OR repayment_method = 'EQUATED'),
  ADD CONSTRAINT product_structured_is_daily CHECK (repayment_method <> 'STRUCTURED' OR interest_basis = 'DAILY_REDUCING'),
  -- tranches re-schedule the loan on its method: only where the engine can (LoanAccount.requireTrancheable)
  ADD CONSTRAINT product_tranche_methods CHECK (NOT (multiple_disbursements OR top_up_allowed)
      OR (interest_basis = 'DAILY_REDUCING' AND principal_every = 1
          AND repayment_method IN ('EQUATED','FIXED_PRINCIPAL','BULLET_TOTAL_INTEREST','BULLET_PERIODIC_INTEREST'))),
  ADD CONSTRAINT product_pre_emi CHECK (NOT pre_emi OR (multiple_disbursements AND repayment_method = 'EQUATED')),
  ADD CONSTRAINT product_floating_link CHECK ((benchmark_code IS NULL AND spread IS NULL AND reset_frequency_months IS NULL)
      OR (benchmark_code IS NOT NULL AND spread IS NOT NULL AND reset_frequency_months IS NOT NULL AND rate_type = 'FLOATING'));

-- Fees: EVERY_DISBURSEMENT is charged on each tranche; it may be deducted from the payout like a DISBURSEMENT fee.
ALTER TABLE lending.fee_rule
  ADD CONSTRAINT fee_rule_event_check CHECK (event IN ('DISBURSEMENT','EVERY_DISBURSEMENT','PRECLOSURE','PART_PREPAYMENT','BOUNCE',
                                                       'LATE_PAYMENT','CANCELLATION','ADHOC')),
  ADD CONSTRAINT fee_rule_deduct_on_disbursement CHECK (deduct_from_disbursal = false OR event IN ('DISBURSEMENT','EVERY_DISBURSEMENT'));

-- ---------------------------------------------------------------------------------------------------------
-- 2. Loan account: frequency, tranches, floating-rate link, asset-class override.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_account
  ADD COLUMN frequency              text NOT NULL DEFAULT 'MONTHLY'
      CHECK (frequency IN ('DAILY','WEEKLY','FORTNIGHTLY','MONTHLY','QUARTERLY','HALF_YEARLY','YEARLY')),
  ADD COLUMN undrawn_amount         platform.money NOT NULL DEFAULT 0 CHECK (undrawn_amount >= 0),
  ADD COLUMN benchmark_code         text REFERENCES lending.benchmark(code),
  ADD COLUMN spread                 platform.rate,
  ADD COLUMN reset_frequency_months int,
  ADD COLUMN next_rate_reset        date,
  ADD COLUMN class_floor            text CHECK (class_floor IN ('SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS')),
  ADD COLUMN class_floor_until      date,
  -- product options frozen into the account at booking (US-043): a later change of the product does not reach it
  ADD COLUMN multiple_disbursements boolean NOT NULL DEFAULT false,
  ADD COLUMN pre_emi                boolean NOT NULL DEFAULT false,
  ADD COLUMN top_up_allowed         boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT loan_pre_emi_needs_tranches CHECK (NOT pre_emi OR multiple_disbursements),
  ADD CONSTRAINT loan_disbursed_within_sanction CHECK (disbursed_amount IS NULL OR disbursed_amount <= sanctioned_amount),
  ADD CONSTRAINT loan_floating_link CHECK ((benchmark_code IS NULL) = (spread IS NULL) AND (benchmark_code IS NULL) = (reset_frequency_months IS NULL)),
  ADD CONSTRAINT loan_class_floor_fields CHECK ((class_floor IS NULL) = (class_floor_until IS NULL));
-- A SANCTIONED loan has drawn nothing: its whole amount is undrawn.
UPDATE lending.loan_account SET undrawn_amount = sanctioned_amount WHERE status IN ('APPLIED','SANCTIONED');

-- One row per disbursement. The engine state holds the same list (LoanAccount.TrancheRow); this copy is for
-- queries, reports and the reconciliation below.
CREATE TABLE lending.loan_tranche (
    loan_id           uuid NOT NULL REFERENCES lending.loan_account(id),
    tranche_no        int NOT NULL CHECK (tranche_no >= 1),
    txn_id            uuid NOT NULL UNIQUE REFERENCES lending.loan_txn(id),
    business_date     date NOT NULL,
    amount            platform.money NOT NULL CHECK (amount > 0),
    fees_deducted     platform.money NOT NULL DEFAULT 0 CHECK (fees_deducted >= 0),      -- fees and their GST taken from the payout
    interest_deducted platform.money NOT NULL DEFAULT 0 CHECK (interest_deducted >= 0),  -- broken-period interest taken upfront
    net_disbursed     numeric(20,4) GENERATED ALWAYS AS (amount - fees_deducted - interest_deducted) STORED,
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (loan_id, tranche_no),
    CONSTRAINT tranche_net_not_negative CHECK (amount - fees_deducted - interest_deducted >= 0),
    CONSTRAINT tranche_interest_on_first_only CHECK (interest_deducted = 0 OR tranche_no = 1)
);
CREATE TRIGGER loan_tranche_immutable BEFORE UPDATE OR DELETE ON lending.loan_tranche
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();
-- Tranches are numbered without gaps and never add up to more than the sanctioned amount.
CREATE FUNCTION lending.check_loan_tranche() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sanctioned numeric; total numeric; last_no int;
BEGIN
  SELECT sanctioned_amount INTO sanctioned FROM lending.loan_account WHERE id = NEW.loan_id FOR UPDATE;
  SELECT coalesce(sum(amount), 0), coalesce(max(tranche_no), 0) INTO total, last_no FROM lending.loan_tranche WHERE loan_id = NEW.loan_id;
  IF NEW.tranche_no <> last_no + 1 THEN
    RAISE EXCEPTION 'tranche % of loan % is out of sequence (last is %)', NEW.tranche_no, NEW.loan_id, last_no USING ERRCODE = '23514';
  END IF;
  IF total + NEW.amount > sanctioned THEN
    RAISE EXCEPTION 'tranches of % would exceed the sanctioned amount %', total + NEW.amount, sanctioned USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER loan_tranche_check BEFORE INSERT ON lending.loan_tranche FOR EACH ROW EXECUTE FUNCTION lending.check_loan_tranche();
-- Loans disbursed before this migration: one tranche each, from their disbursement transaction.
INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, fees_deducted, created_by)
SELECT l.id, 1, t.id, t.business_date, l.disbursed_amount, greatest(l.disbursed_amount - coalesce(l.net_disbursed, l.disbursed_amount), 0), t.created_by
  FROM lending.loan_account l
  JOIN LATERAL (SELECT * FROM lending.loan_txn x WHERE x.loan_id = l.id AND x.txn_type = 'DISBURSEMENT' ORDER BY x.seq LIMIT 1) t ON true
 WHERE l.disbursed_amount > 0;

-- Floating-rate loans whose rate is due for a reset: the reset date has come and benchmark + spread differs from
-- the rate charged. The reset itself is a RATE_CHANGE amendment, because the borrower chooses between EMI and
-- tenure (RBI, 18-Aug-2023).
CREATE VIEW lending.rate_reset_due AS
  SELECT l.id, l.loan_no, l.branch_code, l.customer_id, l.benchmark_code, l.spread, coalesce(l.current_rate, l.rate) AS current_rate,
         lending.benchmark_rate_on(l.benchmark_code, d.business_date) AS benchmark_rate,
         lending.benchmark_rate_on(l.benchmark_code, d.business_date) + l.spread AS new_rate, l.next_rate_reset
    FROM lending.loan_account l CROSS JOIN platform.business_day d
   WHERE d.id = 1 AND l.status IN ('ACTIVE','FROZEN') AND l.benchmark_code IS NOT NULL
     AND l.next_rate_reset <= d.business_date
     AND lending.benchmark_rate_on(l.benchmark_code, d.business_date) + l.spread <> coalesce(l.current_rate, l.rate);

-- ---------------------------------------------------------------------------------------------------------
-- 3. Amendment history: the new kinds. An asset-class override is never marked reversed (as a restructure).
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_amendment DROP CONSTRAINT loan_amendment_kind_check;
ALTER TABLE lending.loan_amendment
  ADD CONSTRAINT loan_amendment_kind_check CHECK (kind IN ('RATE_CHANGE','TENURE_CHANGE','EMI_CHANGE','DUE_DAY_CHANGE','RESTRUCTURE',
                                                           'MATURITY_CHANGE','SANCTION_CHANGE','NPA_OVERRIDE')),
  ADD CONSTRAINT npa_override_has_no_reversal CHECK (kind <> 'NPA_OVERRIDE' OR reversed_by IS NULL);

-- Maker-checker. An asset-class override (manual NPA mark) and its release (un-mark) each need two checkers, like
-- a restructure: they change income recognition and provisioning. A change of the sanctioned amount needs one (a
-- top-up above the product's limits is refused).
INSERT INTO platform.approval_rule (entity_type, action, min_amount, checkers_required) VALUES
  ('LOAN_NPA_OVERRIDE', 'OVERRIDE', NULL, 2),
  ('LOAN_NPA_OVERRIDE', 'RELEASE', NULL, 2),
  ('LOAN_SANCTION_CHANGE', 'AMEND', NULL, 1),
  ('BENCHMARK_RATE', 'CREATE', NULL, 1)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- 4. Product templates (US-038): starting points for the product wizard. "product" has the shape of the product
--    proposal (POST /loan-products) without code, status and version. Figures are illustrative defaults for the
--    lender to change; nothing here is a live product.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_product_template (
    code        text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{2,30}$'),
    name        text NOT NULL,
    description text NOT NULL,
    category    text NOT NULL CHECK (category IN ('RETAIL','BUSINESS','GOLD','MICRO','CONSUMER','HOUSING','AGRI')),
    product     jsonb NOT NULL CHECK (jsonb_typeof(product) = 'object'
                    AND product ?& ARRAY['name','repaymentMethod','minAmount','maxAmount','minTenorMonths','maxTenorMonths','minRate','maxRate']
                    AND NOT product ?| ARRAY['code','status','version']),
    sort_order  int NOT NULL DEFAULT 0,
    active      boolean NOT NULL DEFAULT true
);
INSERT INTO lending.loan_product_template (code, name, description, category, sort_order, product) VALUES
 ('PERSONAL_EMI', 'Personal loan (EMI)',
  'Unsecured monthly EMI loan on a fixed, daily-reducing rate. Processing fee deducted at disbursement; foreclosure charge on the principal pre-closed.',
  'RETAIL', 10, '{
   "name":"Personal loan","repaymentMethod":"EQUATED","frequency":"MONTHLY","interestBasis":"DAILY_REDUCING","bpiMode":"ADD_TO_FIRST_INSTALMENT",
   "minAmount":25000,"maxAmount":500000,"minTenorMonths":6,"maxTenorMonths":60,"minRate":12,"maxRate":30,"rateType":"FIXED",
   "dayCount":"ACTUAL_365","rounding":"RUPEE_HALF_UP","penalChargeRate":24,"maxMoratoriumMonths":0,"coolingOffDays":3,"secured":false,
   "appropriationSequence":["INTEREST","PRINCIPAL","PENAL","FEE"],"appropriationMode":"BY_DEMAND","prepaymentMode":"REDUCE_EMI",
   "fees":[{"code":"PF","name":"Processing fee","event":"DISBURSEMENT","calcType":"PERCENT","percent":2,"minAmount":500,"maxAmount":10000,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":true},
           {"code":"FC","name":"Foreclosure charge","event":"PRECLOSURE","calcType":"PERCENT","percent":3,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":false},
           {"code":"BC","name":"Bounce charge","event":"BOUNCE","calcType":"FIXED","amount":500,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":false}]}'),
 ('BUSINESS_EMI', 'Business loan (EMI, optional moratorium)',
  'Term loan to a business: monthly EMI with up to six months'' interest-only moratorium.',
  'BUSINESS', 20, '{
   "name":"Business loan","repaymentMethod":"EQUATED","frequency":"MONTHLY","interestBasis":"DAILY_REDUCING","bpiMode":"SEPARATE_DEMAND",
   "minAmount":100000,"maxAmount":5000000,"minTenorMonths":12,"maxTenorMonths":60,"minRate":14,"maxRate":28,"rateType":"FIXED",
   "dayCount":"ACTUAL_365","rounding":"RUPEE_HALF_UP","penalChargeRate":24,"maxMoratoriumMonths":6,"coolingOffDays":3,"secured":false,
   "appropriationSequence":["INTEREST","PRINCIPAL","PENAL","FEE"],"appropriationMode":"BY_DEMAND","prepaymentMode":"REDUCE_TENURE",
   "fees":[{"code":"PF","name":"Processing fee","event":"DISBURSEMENT","calcType":"PERCENT","percent":1.5,"minAmount":2500,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":true},
           {"code":"FC","name":"Foreclosure charge","event":"PRECLOSURE","calcType":"PERCENT","percent":4,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":false}]}'),
 ('BUSINESS_STEP_UP', 'Business loan (step-up EMI)',
  'EMI that rises by 10% every twelve instalments, for a business whose cash flow is expected to grow.',
  'BUSINESS', 25, '{
   "name":"Business loan - step-up","repaymentMethod":"STEP_EQUATED","frequency":"MONTHLY","interestBasis":"DAILY_REDUCING","bpiMode":"NONE",
   "stepPercent":10,"stepEvery":12,
   "minAmount":100000,"maxAmount":5000000,"minTenorMonths":24,"maxTenorMonths":84,"minRate":14,"maxRate":28,"rateType":"FIXED",
   "dayCount":"ACTUAL_365","rounding":"RUPEE_HALF_UP","penalChargeRate":24,"maxMoratoriumMonths":0,"coolingOffDays":3,"secured":false,
   "appropriationSequence":["INTEREST","PRINCIPAL","PENAL","FEE"],"appropriationMode":"BY_DEMAND","prepaymentMode":"REDUCE_EMI",
   "fees":[{"code":"PF","name":"Processing fee","event":"DISBURSEMENT","calcType":"PERCENT","percent":1.5,"minAmount":2500,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":true}]}'),
 ('GOLD_BULLET', 'Gold loan (bullet)',
  'Secured loan against gold ornaments: principal and all interest in one payment at maturity, up to twelve months.',
  'GOLD', 30, '{
   "name":"Gold loan - bullet","repaymentMethod":"BULLET_TOTAL_INTEREST","frequency":"MONTHLY","interestBasis":"DAILY_REDUCING","bpiMode":"NONE",
   "minAmount":5000,"maxAmount":2500000,"minTenorMonths":1,"maxTenorMonths":12,"minRate":9,"maxRate":24,"rateType":"FIXED",
   "dayCount":"ACTUAL_365","rounding":"RUPEE_HALF_UP","penalChargeRate":18,"maxMoratoriumMonths":0,"coolingOffDays":3,"secured":true,
   "appropriationSequence":["INTEREST","PRINCIPAL","PENAL","FEE"],"appropriationMode":"BY_DEMAND","prepaymentMode":"REDUCE_EMI",
   "fees":[{"code":"APR","name":"Appraisal charge","event":"DISBURSEMENT","calcType":"FIXED","amount":250,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":true}]}'),
 ('MICRO_WEEKLY', 'Micro-loan (weekly collection)',
  'Small unsecured loan repaid in equal weekly instalments. No prepayment charge; the tenor is in weeks.',
  'MICRO', 40, '{
   "name":"Micro-loan - weekly","repaymentMethod":"EQUATED","frequency":"WEEKLY","interestBasis":"DAILY_REDUCING","bpiMode":"NONE",
   "minAmount":5000,"maxAmount":125000,"minTenorMonths":12,"maxTenorMonths":104,"minRate":18,"maxRate":26,"rateType":"FIXED",
   "dayCount":"ACTUAL_365","rounding":"RUPEE_HALF_UP","penalChargeRate":12,"maxMoratoriumMonths":0,"coolingOffDays":3,"secured":false,
   "appropriationSequence":["INTEREST","PRINCIPAL","PENAL","FEE"],"appropriationMode":"BY_DEMAND","prepaymentMode":"REDUCE_TENURE",
   "fees":[{"code":"PF","name":"Processing fee","event":"DISBURSEMENT","calcType":"PERCENT","percent":1,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":true}]}'),
 ('CONSUMER_FLAT', 'Consumer durable loan (flat rate)',
  'Purchase finance quoted at a flat rate: equal instalments of (amount + flat interest) / tenor. The KFS and the APR show the equivalent reducing rate.',
  'CONSUMER', 50, '{
   "name":"Consumer durable loan","repaymentMethod":"EQUATED","frequency":"MONTHLY","interestBasis":"FLAT","bpiMode":"NONE",
   "minAmount":5000,"maxAmount":200000,"minTenorMonths":3,"maxTenorMonths":24,"minRate":0,"maxRate":20,"rateType":"FIXED",
   "dayCount":"ACTUAL_365","rounding":"RUPEE_HALF_UP","penalChargeRate":24,"maxMoratoriumMonths":0,"coolingOffDays":3,"secured":false,
   "appropriationSequence":["INTEREST","PRINCIPAL","PENAL","FEE"],"appropriationMode":"BY_DEMAND","prepaymentMode":"REDUCE_EMI",
   "fees":[{"code":"PF","name":"Processing fee","event":"DISBURSEMENT","calcType":"FIXED","amount":499,"gstRate":18,"taxTreatment":"INCLUSIVE","deductFromDisbursal":false}]}'),
 ('HOME_FLOATING', 'Home loan (floating rate, tranches, pre-EMI)',
  'Secured loan disbursed in stages as construction proceeds: interest only on the amount drawn until fully disbursed, then EMIs. Rate = repo rate + spread, reset quarterly. No foreclosure charge (floating-rate loan to an individual).',
  'HOUSING', 60, '{
   "name":"Home loan - floating","repaymentMethod":"EQUATED","frequency":"MONTHLY","interestBasis":"DAILY_REDUCING","bpiMode":"NONE",
   "multipleDisbursements":true,"preEmi":true,"topUpAllowed":true,"benchmarkCode":"REPO","spread":2.75,"resetFrequencyMonths":3,
   "minAmount":500000,"maxAmount":50000000,"minTenorMonths":60,"maxTenorMonths":360,"minRate":7,"maxRate":15,"rateType":"FLOATING",
   "dayCount":"ACTUAL_365","rounding":"RUPEE_HALF_UP","penalChargeRate":24,"maxMoratoriumMonths":0,"coolingOffDays":3,"secured":true,
   "appropriationSequence":["INTEREST","PRINCIPAL","PENAL","FEE"],"appropriationMode":"BY_DEMAND","prepaymentMode":"REDUCE_TENURE",
   "fees":[{"code":"PF","name":"Processing fee","event":"DISBURSEMENT","calcType":"PERCENT","percent":0.5,"maxAmount":25000,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":true},
           {"code":"TF","name":"Stage disbursement charge","event":"EVERY_DISBURSEMENT","calcType":"FIXED","amount":500,"gstRate":18,"taxTreatment":"EXCLUSIVE","deductFromDisbursal":true}]}'),
 ('AGRI_SEASONAL', 'Seasonal loan (structured schedule)',
  'Principal falls due on the dates and in the amounts set for each loan (for example after each harvest), with the interest accrued to that date.',
  'AGRI', 70, '{
   "name":"Seasonal loan","repaymentMethod":"STRUCTURED","frequency":"HALF_YEARLY","interestBasis":"DAILY_REDUCING","bpiMode":"NONE",
   "minAmount":25000,"maxAmount":1000000,"minTenorMonths":1,"maxTenorMonths":10,"minRate":9,"maxRate":18,"rateType":"FIXED",
   "dayCount":"ACTUAL_365","rounding":"RUPEE_HALF_UP","penalChargeRate":12,"maxMoratoriumMonths":0,"coolingOffDays":3,"secured":false,
   "appropriationSequence":["INTEREST","PRINCIPAL","PENAL","FEE"],"appropriationMode":"BY_DEMAND","prepaymentMode":"REDUCE_EMI",
   "fees":[]}');

-- ---------------------------------------------------------------------------------------------------------
-- 5. GST credit notes (CGST Act s.34). A fee that already has a tax invoice is credited, not edited:
--      WAIVER   - the unpaid fee is waived (in full or part). The engine posts the taxable part against fee income
--                 and the tax part against the output-tax heads; the note is built from those entries, so it
--                 always equals what was posted.
--      REVERSAL - the fee transaction is reversed in a later month than its invoice: the invoice was (or will be)
--                 reported for its month, so it is credited in full and marked CREDITED. A reversal in the month
--                 of the invoice still cancels the invoice (V16).
--    tax_adjustable: the note is dated within s.34(2)'s limit (30 November after the financial year of the
--    invoice), so output tax may be reduced by it. The engine never posts a tax reduction for a waiver after that.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE platform.number_series DROP CONSTRAINT number_series_family_check;
ALTER TABLE platform.number_series ADD CONSTRAINT number_series_family_check
  CHECK (family IN ('LOAN','CASA','TERM_DEPOSIT','CUSTOMER','VOUCHER','GST_INVOICE','GST_CREDIT_NOTE'));
CREATE SEQUENCE platform.seq_gst_credit_note;
INSERT INTO platform.number_series VALUES ('GST_CREDIT_NOTE','7002',9,'platform.seq_gst_credit_note');

ALTER TABLE lending.fee_invoice DROP CONSTRAINT fee_invoice_status_check;
ALTER TABLE lending.fee_invoice ADD CONSTRAINT fee_invoice_status_check CHECK (status IN ('ISSUED','CANCELLED','CREDITED'));

-- An invoice never changes or disappears; the changes allowed are ISSUED -> CANCELLED and ISSUED -> CREDITED, once.
CREATE OR REPLACE FUNCTION lending.guard_fee_invoice() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'tax invoices cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.status = 'ISSUED' AND NEW.status IN ('CANCELLED','CREDITED')
     -- total is generated from the other columns and is not yet computed for NEW in a BEFORE trigger
     AND (to_jsonb(NEW) - '{status,cancelled_on,cancelled_by_txn,total}'::text[])
       = (to_jsonb(OLD) - '{status,cancelled_on,cancelled_by_txn,total}'::text[]) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'tax invoice % is immutable', OLD.invoice_no USING ERRCODE = '42501';
END $$;

CREATE FUNCTION lending.credit_note_in_time(p_invoice_date date, p_note_date date) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
  SELECT p_note_date <= make_date(extract(year FROM p_invoice_date)::int + CASE WHEN extract(month FROM p_invoice_date) >= 4 THEN 1 ELSE 0 END, 11, 30)
$$;

CREATE TABLE lending.fee_credit_note (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    credit_note_no   text NOT NULL UNIQUE CHECK (length(credit_note_no) <= 16),
    credit_note_date date NOT NULL,
    invoice_id       uuid NOT NULL REFERENCES lending.fee_invoice(id),
    loan_id          uuid NOT NULL REFERENCES lending.loan_account(id),
    txn_id           uuid NOT NULL REFERENCES lending.loan_txn(id),          -- the waiver, or the reversal
    lot_id           uuid UNIQUE REFERENCES ledger.transaction_lot(id),      -- the waiver's lot; NULL for a reversal
    reason           text NOT NULL CHECK (reason IN ('WAIVER','REVERSAL')),
    taxable_value    platform.money NOT NULL CHECK (taxable_value >= 0),
    cgst             platform.money NOT NULL DEFAULT 0 CHECK (cgst >= 0),
    sgst             platform.money NOT NULL DEFAULT 0 CHECK (sgst >= 0),
    igst             platform.money NOT NULL DEFAULT 0 CHECK (igst >= 0),
    total            numeric(20,4) GENERATED ALWAYS AS (taxable_value + cgst + sgst + igst) STORED,
    tax_adjustable   boolean NOT NULL,
    status           text NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED','CANCELLED')),
    cancelled_on     date,
    cancelled_by_txn uuid REFERENCES lending.loan_txn(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT credit_note_lot_by_reason CHECK ((reason = 'WAIVER') = (lot_id IS NOT NULL)),
    CONSTRAINT credit_note_not_empty CHECK (taxable_value + cgst + sgst + igst > 0),
    CONSTRAINT credit_note_cancel_fields CHECK ((status = 'CANCELLED') = (cancelled_on IS NOT NULL))
);
CREATE UNIQUE INDEX fee_credit_note_one_reversal ON lending.fee_credit_note (invoice_id) WHERE reason = 'REVERSAL' AND status = 'ISSUED';
CREATE INDEX fee_credit_note_invoice ON lending.fee_credit_note (invoice_id);
CREATE INDEX fee_credit_note_date ON lending.fee_credit_note (credit_note_date);

-- A credit note belongs to its invoice's loan, is not dated before it, keeps its tax heads, and the live notes of an
-- invoice never add up to more than the invoice. Like an invoice it is immutable but for ISSUED -> CANCELLED, once.
CREATE FUNCTION lending.guard_fee_credit_note() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i lending.fee_invoice%ROWTYPE; used record;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'credit notes cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'ISSUED' AND NEW.status = 'CANCELLED'
       AND (to_jsonb(NEW) - '{status,cancelled_on,cancelled_by_txn,total}'::text[])
         = (to_jsonb(OLD) - '{status,cancelled_on,cancelled_by_txn,total}'::text[]) THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'credit note % is immutable', OLD.credit_note_no USING ERRCODE = '42501';
  END IF;
  SELECT * INTO i FROM lending.fee_invoice WHERE id = NEW.invoice_id FOR UPDATE;
  IF i.loan_id <> NEW.loan_id THEN
    RAISE EXCEPTION 'credit note % and invoice % are on different loans', NEW.credit_note_no, i.invoice_no USING ERRCODE = '23514';
  END IF;
  IF i.status = 'CANCELLED' THEN
    RAISE EXCEPTION 'invoice % is cancelled; there is nothing to credit', i.invoice_no USING ERRCODE = '23514';
  END IF;
  IF NEW.credit_note_date < i.invoice_date THEN
    RAISE EXCEPTION 'credit note % is dated before its invoice %', NEW.credit_note_no, i.invoice_no USING ERRCODE = '23514';
  END IF;
  IF (NEW.igst > 0 AND i.igst = 0) OR (NEW.cgst + NEW.sgst > 0 AND i.cgst + i.sgst = 0) THEN
    RAISE EXCEPTION 'credit note % uses a tax head its invoice % does not', NEW.credit_note_no, i.invoice_no USING ERRCODE = '23514';
  END IF;
  IF NEW.tax_adjustable AND NOT lending.credit_note_in_time(i.invoice_date, NEW.credit_note_date) THEN
    RAISE EXCEPTION 'credit note % is past the time limit of section 34(2) for invoice %: tax cannot be adjusted',
      NEW.credit_note_no, i.invoice_no USING ERRCODE = '23514';
  END IF;
  SELECT coalesce(sum(taxable_value), 0) AS taxable, coalesce(sum(cgst), 0) AS cgst, coalesce(sum(sgst), 0) AS sgst, coalesce(sum(igst), 0) AS igst
    INTO used FROM lending.fee_credit_note WHERE invoice_id = NEW.invoice_id AND status = 'ISSUED';
  IF used.taxable + NEW.taxable_value > i.taxable_value OR used.cgst + NEW.cgst > i.cgst
     OR used.sgst + NEW.sgst > i.sgst OR used.igst + NEW.igst > i.igst THEN
    RAISE EXCEPTION 'credit notes would exceed invoice % (taxable % of %)', i.invoice_no, used.taxable + NEW.taxable_value, i.taxable_value
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER fee_credit_note_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.fee_credit_note
  FOR EACH ROW EXECUTE FUNCTION lending.guard_fee_credit_note();

-- Issues the credit notes not yet issued (for one loan, or every loan). Called by lending.issue_fee_invoices, after
-- the invoices themselves. Safe to call any number of times. Returns the number of notes issued.
CREATE FUNCTION lending.issue_fee_credit_notes(p_loan uuid DEFAULT NULL) RETURNS int LANGUAGE plpgsql AS $$
DECLARE r record; n int := 0;
BEGIN
  -- a waiver that was itself reversed: its note is cancelled (kept, with its number)
  UPDATE lending.fee_credit_note c
     SET status = 'CANCELLED', cancelled_on = rev.business_date, cancelled_by_txn = rev.id
    FROM lending.loan_txn t JOIN lending.loan_txn rev ON rev.id = t.reversed_by
   WHERE c.txn_id = t.id AND c.reason = 'WAIVER' AND c.status = 'ISSUED' AND (p_loan IS NULL OR c.loan_id = p_loan);

  -- a fee reversed in a later month than its invoice: credit what is left of the invoice
  FOR r IN
    SELECT i.id AS invoice_id, i.loan_id, rev.id AS rev_txn, rev.business_date,
           i.taxable_value - coalesce(u.taxable, 0) AS taxable, i.cgst - coalesce(u.cgst, 0) AS cgst,
           i.sgst - coalesce(u.sgst, 0) AS sgst, i.igst - coalesce(u.igst, 0) AS igst, i.invoice_date
      FROM lending.fee_invoice i
      JOIN lending.loan_txn t ON t.id = i.txn_id
      JOIN lending.loan_txn rev ON rev.id = t.reversed_by
      LEFT JOIN LATERAL (SELECT sum(taxable_value) AS taxable, sum(cgst) AS cgst, sum(sgst) AS sgst, sum(igst) AS igst
                           FROM lending.fee_credit_note c WHERE c.invoice_id = i.id AND c.status = 'ISSUED') u ON true
     WHERE i.status = 'ISSUED' AND (p_loan IS NULL OR i.loan_id = p_loan)
       AND date_trunc('month', rev.business_date) > date_trunc('month', i.invoice_date)
     ORDER BY rev.business_date, i.invoice_no
  LOOP
    IF r.taxable + r.cgst + r.sgst + r.igst > 0 THEN
      INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, reason, taxable_value, cgst, sgst, igst,
                                           tax_adjustable)
      VALUES (platform.next_number('GST_CREDIT_NOTE'), r.business_date, r.invoice_id, r.loan_id, r.rev_txn, 'REVERSAL', r.taxable, r.cgst,
              r.sgst, r.igst, lending.credit_note_in_time(r.invoice_date, r.business_date));
      n := n + 1;
    END IF;
    UPDATE lending.fee_invoice SET status = 'CREDITED' WHERE id = r.invoice_id;
  END LOOP;

  -- waivers: one note per waiver lot that reduced output tax, against the live invoice of the charge it names
  FOR r IN
    SELECT t.id AS txn_id, t.loan_id, t.seq, u.lot_id, lot.business_date, i.id AS invoice_id, i.invoice_date,
           coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code = g.fee_income_gl), 0) AS taxable,
           coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code = g.cgst_gl), 0) AS cgst,
           coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code = g.sgst_gl), 0) AS sgst,
           coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code = g.igst_gl), 0) AS igst
      FROM lending.loan_txn t
     CROSS JOIN LATERAL unnest(t.lot_ids) u(lot_id)
      JOIN ledger.transaction_lot lot ON lot.id = u.lot_id AND lot.lot_type = 'WAIVER'
      JOIN lending.loan_gl g ON g.loan_id = t.loan_id
      JOIN ledger.account_entry e ON e.lot_id = u.lot_id
      JOIN LATERAL (SELECT substring(x.narration FROM '^FEE waiver (C[0-9]+)$') AS charge_ref
                      FROM ledger.account_entry x
                     WHERE x.lot_id = u.lot_id AND x.side = 'CR' AND x.narration ~ '^FEE waiver C[0-9]+$' LIMIT 1) w ON true
      JOIN lending.fee_invoice i ON i.loan_id = t.loan_id AND i.charge_ref = w.charge_ref AND i.status = 'ISSUED'
     WHERE t.txn_type = 'WAIVER' AND t.reversed_by IS NULL AND (p_loan IS NULL OR t.loan_id = p_loan)
       AND NOT EXISTS (SELECT 1 FROM lending.fee_credit_note c WHERE c.lot_id = u.lot_id)
     GROUP BY t.id, t.loan_id, t.seq, u.lot_id, lot.business_date, i.id, i.invoice_date
    HAVING coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code IN (g.cgst_gl, g.sgst_gl, g.igst_gl)), 0) > 0
     ORDER BY lot.business_date, t.seq
  LOOP
    INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, lot_id, reason, taxable_value, cgst,
                                         sgst, igst, tax_adjustable)
    VALUES (platform.next_number('GST_CREDIT_NOTE'), r.business_date, r.invoice_id, r.loan_id, r.txn_id, r.lot_id, 'WAIVER', r.taxable, r.cgst,
            r.sgst, r.igst, true);
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;

-- lending.issue_fee_invoices as in V16, with three changes: an invoice whose fee is reversed is cancelled only when
-- the reversal falls in the invoice's month (otherwise it is credited, above); fees deducted from the second and
-- later tranches continue the D<n> numbering of the loan instead of restarting at D1; and the credit notes are
-- issued at the end. Still returns the number of invoices issued.
CREATE OR REPLACE FUNCTION lending.issue_fee_invoices(p_loan uuid DEFAULT NULL) RETURNS int LANGUAGE plpgsql AS $$
DECLARE r record; n int := 0;
BEGIN
  UPDATE lending.fee_invoice i
     SET status = 'CANCELLED', cancelled_on = rev.business_date, cancelled_by_txn = rev.id
    FROM lending.loan_txn t JOIN lending.loan_txn rev ON rev.id = t.reversed_by
   WHERE i.txn_id = t.id AND i.status = 'ISSUED' AND (p_loan IS NULL OR i.loan_id = p_loan)
     AND date_trunc('month', rev.business_date) = date_trunc('month', i.invoice_date)
     AND NOT EXISTS (SELECT 1 FROM lending.fee_credit_note c
                       JOIN lending.loan_txn w ON w.id = c.txn_id
                      WHERE c.invoice_id = i.id AND c.status = 'ISSUED' AND w.reversed_by IS NULL);

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
                  ELSE 'D' || row_number() OVER (PARTITION BY f.loan_id, f.lot_type ORDER BY f.seq, f.first_entry) END AS charge_ref
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
  PERFORM lending.issue_fee_credit_notes(p_loan);
  RETURN n;
END $$;

-- Net GST output per document: invoices as positive rows, live credit notes as negative rows, for the tax return
-- (the GST output summary report of V16 counts invoices only; it should read this view once the report is revised).
CREATE VIEW lending.gst_output_document AS
  SELECT 'INVOICE' AS document_type, i.invoice_no AS document_no, i.invoice_date AS document_date, i.invoice_no AS against_invoice_no,
         i.loan_id, i.branch_code, i.supplier_state, i.place_of_supply, i.gst_rate,
         i.taxable_value::numeric AS taxable_value, i.cgst::numeric AS cgst, i.sgst::numeric AS sgst, i.igst::numeric AS igst
    FROM lending.fee_invoice i WHERE i.status IN ('ISSUED','CREDITED')
  UNION ALL
  SELECT 'CREDIT_NOTE', c.credit_note_no, c.credit_note_date, i.invoice_no, c.loan_id, i.branch_code, i.supplier_state, i.place_of_supply, i.gst_rate,
         -c.taxable_value::numeric, CASE WHEN c.tax_adjustable THEN -c.cgst::numeric ELSE 0 END,
         CASE WHEN c.tax_adjustable THEN -c.sgst::numeric ELSE 0 END, CASE WHEN c.tax_adjustable THEN -c.igst::numeric ELSE 0 END
    FROM lending.fee_credit_note c JOIN lending.fee_invoice i ON i.id = c.invoice_id WHERE c.status = 'ISSUED';
