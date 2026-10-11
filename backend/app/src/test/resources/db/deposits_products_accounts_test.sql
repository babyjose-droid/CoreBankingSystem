-- Phase 4 (P4-1, tenant V29): deposit products, account opening with holders and nominees, term deposit booking.
-- Run on a fresh tenant database after the tenant migrations.
\set QUIET on
CREATE FUNCTION pg_temp.expect_fail(sql text, code text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE sql;
  EXCEPTION WHEN others THEN
    IF SQLSTATE = code THEN RAISE NOTICE 'PASS % [%]', label, SQLERRM; RETURN; END IF;
    RAISE EXCEPTION 'FAIL % : wrong error % %', label, SQLSTATE, SQLERRM;
  END;
  RAISE EXCEPTION 'FAIL % : no error raised', label;
END $$;
CREATE FUNCTION pg_temp.check(ok boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF ok IS NOT TRUE THEN RAISE EXCEPTION 'FAIL %', label; END IF;
  RAISE NOTICE 'PASS %', label;
END $$;
-- The test switches the legal entity between institution types; a real tenant has one type for life.
CREATE FUNCTION pg_temp.become(p_type text) RETURNS void LANGUAGE sql AS $$
  UPDATE platform.legal_entity SET deposit_taking = false WHERE id = 1;
  UPDATE platform.legal_entity SET entity_type = p_type WHERE id = 1;
$$;
-- A customer, with the three required KYC documents verified by someone else when p_verified.
CREATE FUNCTION pg_temp.customer(n int, p_name text, p_type text, p_verified boolean) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE cid uuid := ('00000000-0000-0000-0000-0000000000c' || n)::uuid; body text := '9001' || lpad(n::text, 9, '0'); t text; did uuid;
BEGIN
  INSERT INTO customer.customer (id, customer_no, customer_type, display_name, home_branch, status)
  VALUES (cid, body || platform.luhn_digit(body), p_type, 'CLAUDE-TEST ' || p_name, 'HO', 'ACTIVE');
  IF p_verified THEN
    FOREACH t IN ARRAY ARRAY['PAN','ADDRESS_PROOF','PHOTO'] LOOP
      did := gen_random_uuid();
      INSERT INTO customer.kyc_document (id, customer_id, doc_type, store_key, content_type, size_bytes, sha256, uploaded_by)
      VALUES (did, cid, t, 'tenants/claude-test/kyc/' || cid || '/' || did, 'application/pdf', 1024, repeat('ab', 32), 'maker');
    END LOOP;
    UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now() WHERE customer_id = cid;
  END IF;
  RETURN cid;
END $$;
-- Opens an account as the application does: the account, its holders and nominees and the row of its family in
-- one transaction. Term deposit terms are taken from the product unless p_extra says otherwise. The deferred
-- checks are run before returning, so a refusal is raised here.
CREATE FUNCTION pg_temp.open(p_family text, p_product text, p_mode text, p_nomination text, p_holders int[], p_nominees jsonb,
                             p_extra jsonb DEFAULT '{}') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE aid uuid; p deposits.product%ROWTYPE; card record; n jsonb; i int := 0; h int; a deposits.account%ROWTYPE;
        principal numeric; tm int; td int; extra numeric; addons jsonb;
BEGIN
  INSERT INTO deposits.account (family, product_code, product_version, customer_id, branch_code, operating_mode, nomination_mode, status, opened_on, opened_by)
  VALUES (p_family, p_product, 0, ('00000000-0000-0000-0000-0000000000c' || coalesce(p_extra->>'customer', p_holders[1]::text))::uuid, 'HO',
          p_mode, p_nomination, 'ACTIVE', '1999-01-01', 'maker')
  RETURNING id INTO aid;
  SELECT * INTO a FROM deposits.account WHERE id = aid;
  FOREACH h IN ARRAY p_holders LOOP
    i := i + 1;
    INSERT INTO deposits.account_holder VALUES (aid, coalesce((p_extra->>'firstHolderNo')::int, 1) + i - 1, ('00000000-0000-0000-0000-0000000000c' || h)::uuid);
  END LOOP;
  i := 0;
  FOR n IN SELECT * FROM jsonb_array_elements(p_nominees) LOOP
    i := i + 1;
    INSERT INTO deposits.nominee (account_id, nominee_no, name, relationship, share_percent, is_minor, guardian_name)
    VALUES (aid, i, 'CLAUDE-TEST ' || (n->>'name'), coalesce(n->>'relationship', 'SPOUSE'), (n->>'share')::numeric,
            coalesce((n->>'minor')::boolean, false), n->>'guardian');
  END LOOP;
  IF p_family = 'CASA' AND NOT coalesce((p_extra->>'noRow')::boolean, false) THEN
    INSERT INTO deposits.casa_account VALUES (aid, (p_extra->>'nextInterestDate')::date);
  ELSIF p_family = 'TERM_DEPOSIT' THEN
    SELECT * INTO p FROM deposits.product WHERE code = p_product;
    principal := coalesce((p_extra->>'principal')::numeric, 100000);
    tm := coalesce((p_extra->>'months')::int, 12);
    td := coalesce((p_extra->>'days')::int, 0);
    SELECT * INTO card FROM deposits.card_rate(p.rate_card, principal, a.opened_on, (a.opened_on + make_interval(months => tm))::date + td);
    addons := coalesce(p_extra->'addOns', '[]'::jsonb);
    SELECT coalesce(sum((x->>'percent')::numeric), 0) INTO extra FROM jsonb_array_elements(addons) x;
    INSERT INTO deposits.term_deposit (account_id, principal, tenure_months, tenure_days, booked_on, rate_percent, card_rate_percent,
        card_from_amount, card_from_months, card_from_days, add_ons, interest_period_months, cumulative, day_count, rounding, tds_applicable,
        premature_allowed, premature_penalty_percent, premature_min_days, payout_to, payout_account_id, maturity_instruction)
    VALUES (aid, principal, tm, td, '1999-01-01',
        coalesce((p_extra->>'rate')::numeric, coalesce((p_extra->>'cardRate')::numeric, card.rate_percent) + extra),
        coalesce((p_extra->>'cardRate')::numeric, card.rate_percent), coalesce((p_extra->>'fromAmount')::numeric, card.from_amount),
        card.from_months, card.from_days, addons, p.interest_period_months, p.product_type = 'FD_CUMULATIVE',
        coalesce(p_extra->>'dayCount', p.day_count), p.rounding, p.tds_applicable, p.premature_allowed, p.premature_penalty_percent,
        p.premature_min_days, coalesce(p_extra->>'payoutTo', 'BANK_ACCOUNT'), (p_extra->>'payoutAccount')::uuid,
        coalesce(p_extra->>'instruction', 'PAY_OUT'));
  END IF;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN aid;
END $$;

INSERT INTO platform.branch (code, name, state_code, is_head_office) VALUES ('HO', 'Head Office', '32', true);
INSERT INTO platform.branch (code, name, state_code, parent_code, status) VALUES ('OLD', 'Closed branch', '32', 'HO', 'CLOSED');
INSERT INTO platform.business_day VALUES (1, '2026-06-30', 'OPEN');
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Bank Ltd', 'BANK', '32');
SELECT ledger.load_starter_kit('BANK');
SELECT ledger.load_lending_heads();
SELECT ledger.load_deposit_heads();
SELECT pg_temp.customer(1, 'Asha Depositor', 'INDIVIDUAL', true);
SELECT pg_temp.customer(2, 'Binu Joint', 'INDIVIDUAL', true);
SELECT pg_temp.customer(3, 'Chitra Pending', 'INDIVIDUAL', false);
SELECT pg_temp.customer(4, 'Traders LLP', 'NON_INDIVIDUAL', true);
SELECT pg_temp.customer(5, 'Deepa Third', 'INDIVIDUAL', true);
SELECT pg_temp.customer(6, 'Eldho Blocked', 'INDIVIDUAL', true);
UPDATE customer.customer SET status = 'BLOCKED' WHERE id = '00000000-0000-0000-0000-0000000000c6';

-- the rule added by V29 ------------------------------------------------------------------------------------
SELECT pg_temp.check(deposits.rule_value('current.interest-allowed') = 0
                     AND (SELECT NOT verified FROM deposits.rule WHERE code = 'current.interest-allowed')
                     AND (SELECT count(*) = 1 FROM deposits.rule WHERE code = 'current.interest-allowed'),
                     'R1 a bank''s current account bears no interest (one BANK row, not verified)');

-- savings and current products ------------------------------------------------------------------------------
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode, interest_posting_months,
                              cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('SB01', 'CLAUDE-TEST Savings', 'CASA', 'SAVINGS', 'ACTUAL_365', 1000,
        '[{"fromBalance": 0, "ratePercent": 2.75}, {"fromBalance": 100000, "ratePercent": 3.5}]', 'INCREMENTAL', 3, true, '2401', '5108', '2026-06-30', 'maker');
SELECT pg_temp.check((SELECT version = 1 AND status = 'ACTIVE' AND rounding = 'PAISE_HALF_UP' AND tds_applicable FROM deposits.product WHERE code = 'SB01')
                     AND (SELECT count(*) = 1 AND bool_and(snapshot->>'name' = 'CLAUDE-TEST Savings' AND changed_by = 'maker')
                            FROM deposits.product_history WHERE product_code = 'SB01'),
                     'P1 a savings product is stored as version 1 and its version is kept in the history');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode,
    interest_posting_months, cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
  VALUES ('SB02', 'CLAUDE-TEST Early band', 'CASA', 'SAVINGS', 'ACTUAL_365', 0, '[{"fromBalance": 0, "ratePercent": 2.5}, {"fromBalance": 50000, "ratePercent": 3}]',
          'INCREMENTAL', 3, false, '2401', '5108', '2026-06-30', 'maker') $q$, '23514', 'P2 a second savings rate below 1,00,000 is refused');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode,
    interest_posting_months, cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
  VALUES ('SB02', 'CLAUDE-TEST Whole balance', 'CASA', 'SAVINGS', 'ACTUAL_365', 0, '[{"fromBalance": 0, "ratePercent": 2.5}, {"fromBalance": 100000, "ratePercent": 3}]',
          'WHOLE_BALANCE', 3, false, '2401', '5108', '2026-06-30', 'maker') $q$, '23514',
  'P3 with whole-balance bands a second band from exactly 1,00,000 is refused too');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, min_balance, cheque_facility, gl_liability, effective_from, updated_by)
  VALUES ('SB02', 'CLAUDE-TEST No interest', 'CASA', 'SAVINGS', 0, false, '2401', '2026-06-30', 'maker') $q$, '23514',
  'P4 a savings product needs its interest bands');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode,
    interest_posting_months, cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
  VALUES ('SB02', 'CLAUDE-TEST Bands', 'CASA', 'SAVINGS', 'ACTUAL_365', 0, '[{"fromBalance": 500, "ratePercent": 2.5}]',
          'INCREMENTAL', 3, false, '2401', '5108', '2026-06-30', 'maker') $q$, '23514', 'P5 the first interest band starts at 0');
INSERT INTO deposits.product (code, name, family, product_type, min_balance, cheque_facility, gl_liability, effective_from, updated_by)
VALUES ('CA01', 'CLAUDE-TEST Current', 'CASA', 'CURRENT', 10000, true, '2402', '2026-06-30', 'maker');
SELECT pg_temp.check((SELECT interest_bands IS NULL AND interest_posting_months IS NULL FROM deposits.product WHERE code = 'CA01'),
                     'P6 a current product bears no interest');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode,
    interest_posting_months, cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
  VALUES ('CA02', 'CLAUDE-TEST Paying current', 'CASA', 'CURRENT', 'ACTUAL_365', 0, '[{"fromBalance": 0, "ratePercent": 2}]',
          'WHOLE_BALANCE', 3, false, '2402', '5108', '2026-06-30', 'maker') $q$, '23514', 'P7 a current product with interest bands is refused');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, min_balance, cheque_facility, min_amount, gl_liability, effective_from, updated_by)
  VALUES ('CA02', 'CLAUDE-TEST Mixed', 'CASA', 'CURRENT', 0, false, 5000, '2402', '2026-06-30', 'maker') $q$, '23514',
  'P8 a savings or current product has no term deposit settings');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, min_balance, cheque_facility, gl_liability, effective_from, updated_by)
  VALUES ('CA02', 'CLAUDE-TEST Wrong head', 'CASA', 'CURRENT', 0, false, '5107', '2026-06-30', 'maker') $q$, '23514',
  'P9 deposits are booked under a liability head');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, min_balance, cheque_facility, gl_liability, effective_from, updated_by)
  VALUES ('FD99', 'CLAUDE-TEST Wrong family', 'CASA', 'FD_PAYOUT', 0, false, '2402', '2026-06-30', 'maker') $q$, '23514',
  'P10 the product type belongs to its family');

-- term deposit products: the rate card ----------------------------------------------------------------------
CREATE FUNCTION pg_temp.fd(p_code text, p_type text, p_period int, p_card jsonb, p_extra jsonb DEFAULT '{}') RETURNS void LANGUAGE sql AS $$
  INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, max_amount, min_tenure_months, min_tenure_days,
      max_tenure_months, max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed, premature_penalty_percent,
      premature_min_days, auto_renewal_allowed, gl_liability, gl_interest_expense, effective_from, updated_by)
  VALUES (p_code, 'CLAUDE-TEST ' || p_code, 'TERM_DEPOSIT', p_type, 'ACTUAL_365', coalesce((p_extra->>'minAmount')::numeric, 10000),
      (p_extra->>'maxAmount')::numeric, coalesce((p_extra->>'minMonths')::int, 0), coalesce((p_extra->>'minDays')::int, 7),
      coalesce((p_extra->>'maxMonths')::int, 120), 0, p_period, p_card, coalesce(p_extra->'addOns', '[{"code": "SENIOR_CITIZEN", "percent": 0.5}]'),
      coalesce((p_extra->>'premature')::boolean, true),
      CASE WHEN p_extra ? 'penalty' THEN (p_extra->>'penalty')::numeric ELSE 1 END,
      CASE WHEN p_extra ? 'minPeriod' THEN (p_extra->>'minPeriod')::int ELSE 7 END,
      coalesce((p_extra->>'renewal')::boolean, true), '2403', '5107', '2026-06-30', 'maker')
$$;
CREATE FUNCTION pg_temp.card() RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
  SELECT '[{"fromAmount": 0, "fromMonths": 0, "fromDays": 7, "ratePercent": 4.5}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.1},
           {"fromAmount": 0, "fromMonths": 24, "fromDays": 0, "ratePercent": 7.25}, {"fromAmount": 500000, "fromMonths": 0, "fromDays": 7, "ratePercent": 4.75},
           {"fromAmount": 500000, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.35}, {"fromAmount": 500000, "fromMonths": 24, "fromDays": 0, "ratePercent": 7.5}]'::jsonb
$$;
SELECT pg_temp.fd('FDC1', 'FD_CUMULATIVE', 3, pg_temp.card());
SELECT pg_temp.fd('FDP1', 'FD_PAYOUT', 0, pg_temp.card(), '{"renewal": false}');
SELECT pg_temp.check((SELECT count(*) = 2 FROM deposits.product WHERE family = 'TERM_DEPOSIT' AND status = 'ACTIVE'),
                     'C1 a product whose card covers every amount and tenure it accepts is stored');
SELECT pg_temp.check(deposits.rate_card_problems(pg_temp.card(), 10000, 0, 7) = '{}', 'C2 and the card has no problems');
SELECT pg_temp.check(deposits.rate_card_problems(pg_temp.card() - 4, 10000, 0, 7) = ARRAY['no rate for amount from 500000 and tenure from 12 months'],
                     'C3 a missing cell is named');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fd('FDX1', 'FD_CUMULATIVE', 3, pg_temp.card() - 4) $q$, '23514', 'C4 and the product is refused');
SELECT pg_temp.check(deposits.rate_card_problems('[{"fromAmount": 25000, "fromMonths": 0, "fromDays": 30, "ratePercent": 4.5}]', 10000, 0, 7)
                     = ARRAY['no rate for amounts below 25000; the product accepts deposits from 10000',
                             'no rate for tenures shorter than 30 days; the product accepts 7 days'],
                     'C5 a card that starts above the smallest deposit or the shortest tenure leaves a gap');
SELECT pg_temp.check(deposits.rate_card_problems('[]', 10000, 0, 7) = ARRAY['the rate table has no rates']
                     AND deposits.rate_card_problems(NULL, 10000, 0, 7) = ARRAY['the rate table has no rates'], 'C6 an empty card has no rates');
SELECT pg_temp.check(deposits.rate_card_problems('[{"fromAmount": 0, "fromMonths": 1, "fromDays": 0, "ratePercent": 5}, {"fromAmount": 0, "fromMonths": 0, "fromDays": 31, "ratePercent": 5.1},
                                                   {"fromAmount": 0, "fromMonths": 1, "fromDays": 0, "ratePercent": 5.2}]', 10000, 1, 0)
                     = ARRAY['two rates for amount from 0 and tenure from 1 months', 'tenure steps 1 months and 31 days cannot both be used'],
                     'C7 two rates for one cell, and two tenure steps that sort alike, are refused');
SELECT pg_temp.check(deposits.rate_card_problems('[{"fromAmount": 0, "fromMonths": 12, "ratePercent": 7}]', 10000, 12, 0)
                     = ARRAY['every row of the rate table needs fromAmount, fromMonths, fromDays and ratePercent as numbers']
                     AND deposits.rate_card_problems('[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": -1}]', 10000, 12, 0)
                     = ARRAY['a rate table row cannot have a negative amount, tenure or rate, and tenures are whole months and days'],
                     'C8 a row that is not complete, or is negative, is refused');
SELECT pg_temp.check((SELECT (from_amount, from_months, from_days, rate_percent) = (0::numeric, 12, 0, 7.1::numeric)
                        FROM deposits.card_rate(pg_temp.card(), 250000, '2026-07-01', '2027-10-01'))
                     AND (SELECT (from_amount, from_months, rate_percent) = (500000::numeric, 24, 7.5::numeric)
                            FROM deposits.card_rate(pg_temp.card(), 500000, '2026-07-01', '2028-07-01'))
                     AND (SELECT (from_months, from_days, rate_percent) = (0, 7, 4.5::numeric)
                            FROM deposits.card_rate(pg_temp.card(), 499999.99, '2026-07-01', '2027-06-30')),
                     'C9 the card rate: the amount step not above the amount, the longest tenure step reached');
SELECT pg_temp.check((SELECT rate_percent = 7.1 FROM deposits.card_rate(pg_temp.card(), 50000, '2026-07-01', DATE '2026-07-01' + 365))
                     AND (SELECT rate_percent = 4.5 FROM deposits.card_rate(pg_temp.card(), 50000, '2027-07-01', DATE '2027-07-01' + 365)),
                     'C10 steps are decided on real dates: 365 days are twelve months, except across 29 February');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM deposits.card_rate(pg_temp.card(), 50000, '2026-07-01', '2026-07-05')),
                     'C11 a deposit shorter than the first step has no rate');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fd('FDX1', 'FD_CUMULATIVE', 0, pg_temp.card()) $q$, '23514', 'C12 a cumulative product compounds at a rest');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fd('FDX1', 'FD_CUMULATIVE', 3, pg_temp.card(), '{"premature": false, "penalty": null, "minPeriod": null}') $q$,
                           '23514', 'C13 a bank product from 10,000 must allow premature withdrawal');
SELECT pg_temp.fd('FDL1', 'FD_CUMULATIVE', 3, pg_temp.card(), '{"minAmount": 10000000.01, "premature": false, "penalty": null, "minPeriod": null}');
SELECT pg_temp.check((SELECT NOT premature_allowed FROM deposits.product WHERE code = 'FDL1'), 'C14 a product for deposits above one crore may refuse it');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fd('FDX1', 'FD_CUMULATIVE', 3, pg_temp.card(), '{"penalty": null}') $q$, '23514',
                           'C15 a product that allows premature withdrawal needs its penalty');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fd('FDX1', 'FD_CUMULATIVE', 3, pg_temp.card(), '{"addOns": [{"code": "senior", "percent": 0.5}]}') $q$,
                           '23514', 'C16 an add-on needs a proper code');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fd('FDX1', 'FD_CUMULATIVE', 3, pg_temp.card(), '{"minMonths": 24, "minDays": 0, "maxMonths": 12}') $q$,
                           '23514', 'C17 the shortest tenure is not above the longest');

-- versions --------------------------------------------------------------------------------------------------
UPDATE deposits.product SET name = 'CLAUDE-TEST FDC1 revised', rate_card = jsonb_set(rate_card, '{1,ratePercent}', '7.2'), updated_by = 'maker2' WHERE code = 'FDC1';
SELECT pg_temp.check((SELECT version = 2 FROM deposits.product WHERE code = 'FDC1')
                     AND (SELECT array_agg(version ORDER BY version) = ARRAY[1, 2] FROM deposits.product_history WHERE product_code = 'FDC1')
                     AND (SELECT snapshot#>>'{rate_card,1,ratePercent}' = '7.1' FROM deposits.product_history WHERE product_code = 'FDC1' AND version = 1)
                     AND (SELECT snapshot#>>'{rate_card,1,ratePercent}' = '7.2' AND changed_by = 'maker2' FROM deposits.product_history
                           WHERE product_code = 'FDC1' AND version = 2), 'V1 a change is a new version; the earlier one stays in the history');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.product SET rate_card = rate_card - 5 WHERE code = 'FDC1' $q$, '23514',
                           'V2 a change that opens a gap in the card is refused');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.product SET product_type = 'FD_PAYOUT' WHERE code = 'FDC1' $q$, '42501', 'V3 the type of a product never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.product WHERE code = 'FDC1' $q$, '42501', 'V4 a product is not deleted');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.product_history SET changed_by = 'someone' $q$, '42501', 'V5 the history cannot be changed');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.product_history $q$, '42501', 'V6 or deleted');
SELECT pg_temp.expect_fail($q$ TRUNCATE deposits.product_history $q$, '42501', 'V7 or truncated');
SELECT pg_temp.expect_fail($q$ TRUNCATE deposits.product CASCADE $q$, '42501', 'V8 nor the products');
UPDATE deposits.product SET rate_card = jsonb_set(rate_card, '{1,ratePercent}', '7.1') WHERE code = 'FDC1';

-- savings and current accounts ------------------------------------------------------------------------------
SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SIMULTANEOUS', ARRAY[1], '[{"name": "Nominee One", "share": 100}]', '{"nextInterestDate": "2026-06-30"}');
SELECT pg_temp.check((SELECT account_no ~ '^2001[0-9]{10}$' AND platform.luhn_digit(left(account_no, 13)) = right(account_no, 1)::int
                             AND status = 'ACTIVE' AND opened_on = '2026-06-30' AND product_version = 1
                        FROM deposits.account WHERE product_code = 'SB01'),
                     'A1 a savings account gets a number of the CASA series, is ACTIVE and opened on the business date');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[3], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'A2 no account for a customer whose KYC is not verified');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[6], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'A3 nor for a customer who is not ACTIVE');
SELECT pg_temp.open('CASA', 'SB01', 'EITHER_OR_SURVIVOR', 'NONE', ARRAY[1, 2], '[]', '{"nextInterestDate": "2026-06-30"}');
SELECT pg_temp.open('CASA', 'SB01', 'ANYONE_OR_SURVIVOR', 'NONE', ARRAY[1, 2, 5], '[]', '{"nextInterestDate": "2026-06-30"}');
SELECT pg_temp.check((SELECT count(*) = 2 FROM deposits.account WHERE operating_mode <> 'SINGLE')
                     AND (SELECT count(*) = 5 FROM deposits.account_holder h JOIN deposits.account a ON a.id = h.account_id WHERE a.operating_mode <> 'SINGLE'),
                     'A4 joint accounts: either or survivor with two holders, anyone or survivor with three');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[1, 2], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'A5 a single account has one holder');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'EITHER_OR_SURVIVOR', 'NONE', ARRAY[1], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'A6 either or survivor needs two holders');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'FORMER_OR_SURVIVOR', 'NONE', ARRAY[1, 2, 5], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'A7 former or survivor has exactly two');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'JOINTLY', 'NONE', ARRAY[1, 4], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'A8 a joint account is held by individuals');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[2], '[]', '{"nextInterestDate": "2026-06-30", "customer": "1"}') $q$, '23514',
                           'A9 the first holder is the account''s customer');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"nextInterestDate": "2026-06-30", "firstHolderNo": 2}') $q$, '23514',
                           'A10 holders are numbered from 1');
SELECT pg_temp.open('CASA', 'CA01', 'SINGLE', 'NONE', ARRAY[4], '[]');
SELECT pg_temp.check((SELECT c.next_interest_date IS NULL FROM deposits.casa_account c JOIN deposits.account a ON a.id = c.account_id WHERE a.product_code = 'CA01'),
                     'A11 a company opens a current account in its single name; it has no interest date');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'CA01', 'SINGLE', 'NONE', ARRAY[4], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'A12 an account that bears no interest has no interest date');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[1], '[]') $q$, '23514', 'A13 and a savings account needs one');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"nextInterestDate": "2026-06-29"}') $q$, '23514',
                           'A14 not before the account was opened');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"noRow": true}') $q$, '23514',
                           'A15 an account without the row of its family is refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'A16 a savings account cannot be opened on a term deposit product');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.account (family, product_code, product_version, customer_id, branch_code, operating_mode, nomination_mode, status, opened_on, opened_by)
  VALUES ('CASA', 'SB01', 1, '00000000-0000-0000-0000-0000000000c1', 'OLD', 'SINGLE', 'NONE', 'ACTIVE', '2026-06-30', 'maker') $q$, '23514',
  'A17 nor at a branch that is closed');

-- nominees (bank: up to four, together with shares or one after another) ---------------------------------------
SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SIMULTANEOUS', ARRAY[2],
                    '[{"name": "N1", "share": 33.33}, {"name": "N2", "share": 33.33}, {"name": "N3", "share": 33.34, "relationship": "CHILD", "minor": true, "guardian": "CLAUDE-TEST Guardian"}]',
                    '{"nextInterestDate": "2026-06-30"}');
SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SUCCESSIVE', ARRAY[5], '[{"name": "First"}, {"name": "Second"}, {"name": "Third"}, {"name": "Fourth"}]',
                    '{"nextInterestDate": "2026-06-30"}');
SELECT pg_temp.check((SELECT count(*) = 3 AND sum(share_percent) = 100 FROM deposits.nominee n JOIN deposits.account a ON a.id = n.account_id
                       WHERE a.customer_id = '00000000-0000-0000-0000-0000000000c2' AND a.nomination_mode = 'SIMULTANEOUS')
                     AND (SELECT count(*) = 4 AND count(share_percent) = 0 FROM deposits.nominee n JOIN deposits.account a ON a.id = n.account_id
                           WHERE a.nomination_mode = 'SUCCESSIVE'),
                     'M1 three nominees with shares of 100 in all; four nominees one after another, without shares');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SIMULTANEOUS', ARRAY[1], '[{"name": "N1", "share": 60}, {"name": "N2", "share": 30}]',
                                                  '{"nextInterestDate": "2026-06-30"}') $q$, '23514', 'M2 shares that total 90 are refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SIMULTANEOUS', ARRAY[1],
    '[{"name": "N1", "share": 20}, {"name": "N2", "share": 20}, {"name": "N3", "share": 20}, {"name": "N4", "share": 20}, {"name": "N5", "share": 20}]',
    '{"nextInterestDate": "2026-06-30"}') $q$, '23514', 'M3 a fifth nominee is refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SUCCESSIVE', ARRAY[1], '[{"name": "N1", "share": 50}, {"name": "N2", "share": 50}]',
                                                  '{"nextInterestDate": "2026-06-30"}') $q$, '23514', 'M4 successive nominees have no shares');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SUCCESSIVE', ARRAY[1], '[{"name": "N1"}]', '{"nextInterestDate": "2026-06-30"}') $q$,
                           '23514', 'M5 successive nomination needs two nominees');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[1], '[{"name": "N1", "share": 100}]', '{"nextInterestDate": "2026-06-30"}') $q$,
                           '23514', 'M6 nominees cannot be named when the mode is NONE');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SIMULTANEOUS', ARRAY[1], '[]', '{"nextInterestDate": "2026-06-30"}') $q$,
                           '23514', 'M7 and a nomination names someone');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SIMULTANEOUS', ARRAY[1], '[{"name": "Child", "share": 100, "minor": true}]',
                                                  '{"nextInterestDate": "2026-06-30"}') $q$, '23514', 'M8 a minor nominee needs a guardian');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'SIMULTANEOUS', ARRAY[1], '[{"name": "N1", "share": 100, "relationship": "FRIEND"}]',
                                                  '{"nextInterestDate": "2026-06-30"}') $q$, '23514', 'M9 the relationship is one of the listed ones');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.nominee SET share_percent = 50 WHERE name = 'CLAUDE-TEST Nominee One' $q$, '42501', 'M10 a nominee is not edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.nominee WHERE name = 'CLAUDE-TEST Nominee One' $q$, '42501', 'M11 nor deleted');
CREATE FUNCTION pg_temp.end_nominee(p_also_mode boolean) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  UPDATE deposits.nominee SET status = 'ENDED', ended_at = now() WHERE name = 'CLAUDE-TEST Nominee One';
  IF p_also_mode THEN
    UPDATE deposits.account SET nomination_mode = 'NONE' WHERE id = (SELECT account_id FROM deposits.nominee WHERE name = 'CLAUDE-TEST Nominee One');
  END IF;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.end_nominee(false) $q$, '23514', 'M12 ending the only nominee leaves the nomination incomplete');
SELECT pg_temp.end_nominee(true);
SELECT pg_temp.check((SELECT status = 'ENDED' FROM deposits.nominee WHERE name = 'CLAUDE-TEST Nominee One'),
                     'M13 it is ended together with the change of the nomination mode');

-- what never changes ------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account SET account_no = '20010000000018' WHERE product_code = 'CA01' $q$, '42501',
                           'I1 an account number never changes');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account SET customer_id = '00000000-0000-0000-0000-0000000000c2' WHERE product_code = 'CA01' $q$, '42501',
                           'I2 nor the first holder');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.account WHERE product_code = 'CA01' $q$, '42501', 'I3 an account is not deleted');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.account_holder $q$, '42501', 'I4 holders are not removed');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account_holder SET holder_no = 3 WHERE holder_no = 2 $q$, '42501', 'I5 or renumbered');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.casa_account $q$, '42501', 'I6 the account row is not deleted');

-- term deposits ---------------------------------------------------------------------------------------------
SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'SIMULTANEOUS', ARRAY[1], '[{"name": "FD Nominee", "share": 100}]',
                    '{"principal": 250000, "months": 15, "addOns": [{"code": "SENIOR_CITIZEN", "percent": 0.5}], "instruction": "RENEW_WITH_INTEREST"}');
SELECT pg_temp.check((SELECT a.account_no ~ '^3001[0-9]{10}$' AND a.status = 'BOOKED' AND t.booked_on = '2026-06-30' AND t.start_date IS NULL
                             AND t.rate_percent = 7.6 AND t.card_rate_percent = 7.1 AND t.card_from_months = 12 AND t.cumulative
                             AND t.interest_period_months = 3 AND t.premature_penalty_percent = 1 AND t.premature_min_days = 7
                        FROM deposits.term_deposit t JOIN deposits.account a ON a.id = t.account_id WHERE t.principal = 250000),
                     'T1 a term deposit gets a number of its own series, is BOOKED, and carries the rate with its parts and the product''s terms');
SELECT pg_temp.check((SELECT bool_and(left(account_no, 4) = CASE family WHEN 'CASA' THEN '2001' ELSE '3001' END)
                             AND count(DISTINCT account_no) = count(*) FROM deposits.account),
                     'T2 savings and current numbers and term deposit numbers never share a range');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"cardRate": 9}') $q$, '23514',
                           'T3 a card rate that is not the product''s is refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"principal": 600000, "fromAmount": 0, "cardRate": 7.1}') $q$,
                           '23514', 'T4 so is the rate of another amount step');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"addOns": [{"code": "STAFF", "percent": 1}]}') $q$,
                           '23514', 'T5 an add-on the product does not have is refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"addOns": [{"code": "SENIOR_CITIZEN", "percent": 0.75}]}') $q$,
                           '23514', 'T6 or one at another percentage');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"rate": 7.5}') $q$, '23514',
                           'T7 the rate is the card rate plus the add-ons given');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"principal": 9999.99}') $q$, '23514',
                           'T8 an amount below the product''s smallest deposit is refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"months": 120, "days": 1}') $q$, '23514',
                           'T9 a tenure beyond the product''s longest is refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDC1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"dayCount": "ACTUAL_360"}') $q$, '23514',
                           'T10 the terms recorded are those of the product');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDP1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"instruction": "RENEW_PRINCIPAL"}') $q$, '23514',
                           'T11 renewal at maturity needs a product that allows it');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'SB01', 'SINGLE', 'NONE', ARRAY[1], '[]') $q$, '23514',
                           'T12 a term deposit cannot be booked on a savings product');
SELECT pg_temp.open('TERM_DEPOSIT', 'FDP1', 'EITHER_OR_SURVIVOR', 'NONE', ARRAY[1, 2], '[]', jsonb_build_object('principal', 600000, 'months', 24, 'payoutTo', 'CASA',
       'payoutAccount', (SELECT id FROM deposits.account WHERE operating_mode = 'EITHER_OR_SURVIVOR' AND family = 'CASA')));
SELECT pg_temp.check((SELECT t.rate_percent = 7.5 AND t.card_from_amount = 500000 AND NOT t.cumulative AND t.interest_period_months = 0
                             AND t.payout_account_id IS NOT NULL
                        FROM deposits.term_deposit t WHERE t.principal = 600000),
                     'T13 a joint payout deposit at the rate of the higher amount step, paying into the holders'' savings account');
SELECT pg_temp.expect_fail(format($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDP1', 'SINGLE', 'NONE', ARRAY[5], '[]', '{"payoutTo": "CASA", "payoutAccount": "%s"}') $q$,
                                  (SELECT id FROM deposits.account WHERE operating_mode = 'EITHER_OR_SURVIVOR' AND family = 'CASA')), '23514',
                           'T14 proceeds are not paid to an account the depositor does not hold');
SELECT pg_temp.expect_fail(format($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDP1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"payoutTo": "CASA", "payoutAccount": "%s"}') $q$,
                                  (SELECT account_id FROM deposits.term_deposit WHERE principal = 250000)), '23514',
                           'T15 nor to a term deposit');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDP1', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"payoutTo": "CASA"}') $q$, '23514',
                           'T16 a payout to a savings account names the account');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET rate_percent = 8 WHERE principal = 250000 $q$, '42501', 'T17 the rate of a booked deposit never changes');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET principal = 300000 WHERE principal = 250000 $q$, '42501', 'T18 nor its amount');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET start_date = '2026-07-01', maturity_date = '2027-09-30' WHERE principal = 250000 $q$, '23514',
                           'T19 the maturity date is the start date plus the tenure');
UPDATE deposits.term_deposit SET start_date = '2026-07-01', maturity_date = '2027-10-01', maturity_instruction = 'PAY_OUT' WHERE principal = 250000;
SELECT pg_temp.check((SELECT maturity_date = '2027-10-01' AND maturity_instruction = 'PAY_OUT' FROM deposits.term_deposit WHERE principal = 250000),
                     'T20 the funding dates and the maturity instruction can be set later');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.term_deposit $q$, '42501', 'T21 a term deposit is not deleted');
UPDATE deposits.product SET status = 'WITHDRAWN', updated_by = 'maker' WHERE code = 'FDP1';
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'FDP1', 'SINGLE', 'NONE', ARRAY[1], '[]') $q$, '23514', 'T22 a withdrawn product takes no new deposit');

-- a deposit-taking NBFC ---------------------------------------------------------------------------------------
SELECT pg_temp.become('NBFC');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fd('NF00', 'FD_PAYOUT', 1, '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 8.5}]',
                                                '{"minAmount": 5000, "minMonths": 12, "minDays": 0, "maxMonths": 60, "penalty": null, "minPeriod": null}') $q$,
                           '23514', 'N1 an NBFC whose registration is not recorded has no deposit product');
SELECT deposits.set_deposit_taking(true, 'CLAUDE-TEST-COR-0001');
CREATE FUNCTION pg_temp.nbfc_fd(p_code text, p_card jsonb, p_extra jsonb DEFAULT '{}') RETURNS void LANGUAGE sql AS $$
  SELECT pg_temp.fd(p_code, 'FD_PAYOUT', coalesce((p_extra->>'period')::int, 1), p_card,
                    '{"minAmount": 5000, "minMonths": 12, "minDays": 0, "maxMonths": 60, "penalty": null, "minPeriod": null}'::jsonb || p_extra)
$$;
SELECT pg_temp.nbfc_fd('NF01', '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 8.5}, {"fromAmount": 0, "fromMonths": 36, "fromDays": 0, "ratePercent": 12}]');
SELECT pg_temp.check((SELECT premature_penalty_percent IS NULL AND premature_min_days IS NULL AND premature_allowed FROM deposits.product WHERE code = 'NF01'),
                     'N2 once registered it has a product: 12 to 60 months, 12% with a 0.5 add-on, no penalty of its own');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.nbfc_fd('NF02', '[{"fromAmount": 0, "fromMonths": 6, "fromDays": 0, "ratePercent": 8}]', '{"minMonths": 6}') $q$,
                           '23514', 'N3 a tenure shorter than 12 months is refused (para 16)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.nbfc_fd('NF02', '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 8}]', '{"maxMonths": 72}') $q$,
                           '23514', 'N4 and one longer than 60 months');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.nbfc_fd('NF02', '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 12.75}]', '{"addOns": []}') $q$,
                           '23514', 'N5 a card rate above 12.5% is refused (para 19)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.nbfc_fd('NF02', '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 12.25}]') $q$,
                           '23514', 'N6 so is 12.25% that an add-on of 0.5 would take above it');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.nbfc_fd('NF02', '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 9}]', '{"penalty": 1, "minPeriod": 7}') $q$,
                           '23514', 'N7 the product has no premature penalty of its own (para 40 fixes the interest)');
SELECT pg_temp.nbfc_fd('NF03', '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 9}]', '{"premature": false}');
SELECT pg_temp.check((SELECT NOT premature_allowed FROM deposits.product WHERE code = 'NF03'),
                     'N8 whether premature repayment is offered is the NBFC''s choice (para 36)');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode,
    interest_posting_months, cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
  VALUES ('SB09', 'CLAUDE-TEST NBFC savings', 'CASA', 'SAVINGS', 'ACTUAL_365', 0, '[{"fromBalance": 0, "ratePercent": 3}]', 'INCREMENTAL', 3, false,
          '2403', '5107', '2026-06-30', 'maker') $q$, '23514', 'N9 no savings product for an NBFC (para 15)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('CASA', 'SB01', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"nextInterestDate": "2026-06-30"}') $q$, '23514',
                           'N10 and no savings account, even on a product that exists');
SELECT pg_temp.open('TERM_DEPOSIT', 'NF01', 'SINGLE', 'SIMULTANEOUS', ARRAY[1], '[{"name": "NBFC Nominee", "share": 100}]', '{"months": 36}');
SELECT pg_temp.check((SELECT t.rate_percent = 12 AND t.interest_period_months = 1 AND t.premature_penalty_percent IS NULL
                        FROM deposits.term_deposit t JOIN deposits.account a ON a.id = t.account_id WHERE a.product_code = 'NF01'),
                     'N11 a deposit of 36 months with one nominee is booked at the card rate');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'NF01', 'SINGLE', 'SIMULTANEOUS', ARRAY[1], '[{"name": "N1", "share": 50}, {"name": "N2", "share": 50}]') $q$,
                           '23514', 'N12 a second nominee is refused (para 50)');
SELECT pg_temp.open('TERM_DEPOSIT', 'NF01', 'ANYONE_OR_SURVIVOR', 'NONE', ARRAY[1, 2, 5], '[]', '{"months": 12}');
SELECT pg_temp.check((SELECT count(*) = 3 FROM deposits.account_holder h JOIN deposits.account a ON a.id = h.account_id
                       WHERE a.product_code = 'NF01' AND a.operating_mode = 'ANYONE_OR_SURVIVOR'), 'N13 a joint deposit is accepted (para 27)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'NF01', 'SINGLE', 'NONE', ARRAY[1], '[]', '{"months": 60, "days": 1}') $q$, '23514',
                           'N14 a deposit of 60 months and a day is refused');
UPDATE deposits.rule SET effective_to = '2026-06-29' WHERE kind = 'NBFC_DEPOSIT' AND code = 'td.max-rate-percent';
INSERT INTO deposits.rule (kind, code, value, effective_from, source, created_by) VALUES ('NBFC_DEPOSIT', 'td.max-rate-percent', 11, '2026-06-30', 'CLAUDE-TEST direction', 'checker1');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.open('TERM_DEPOSIT', 'NF01', 'SINGLE', 'NONE', ARRAY[2], '[]', '{"months": 36}') $q$, '23514',
                           'N15 when the ceiling falls to 11%, a booking at 12% is refused from that date, whatever the product says');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.product SET name = 'CLAUDE-TEST NF01 again' WHERE code = 'NF01' $q$, '23514',
                           'N16 and the product cannot be approved again until its card is brought under the ceiling');
SELECT 'TESTS PASSED' AS result;
