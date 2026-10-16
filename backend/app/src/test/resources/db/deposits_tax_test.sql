-- Phase 4 (P4-3b, tenant V33): tax deducted at source on deposit interest - the TAX_DEDUCTED movement, the tax ledger and its
-- check against the TDS payable head, and Form 121 declarations. Run on a fresh tenant database after the tenant migrations.
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
CREATE FUNCTION pg_temp.immediate(sql text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE sql;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
CREATE FUNCTION pg_temp.check(ok boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF ok IS NOT TRUE THEN RAISE EXCEPTION 'FAIL %', label; END IF;
  RAISE NOTICE 'PASS %', label;
END $$;
CREATE FUNCTION pg_temp.customer(n int, p_name text) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE cid uuid := ('00000000-0000-0000-0000-0000000000c' || n)::uuid; body text := '9001' || lpad(n::text, 9, '0'); t text; did uuid;
BEGIN
  INSERT INTO customer.customer (id, customer_no, customer_type, display_name, home_branch, status)
  VALUES (cid, body || platform.luhn_digit(body), 'INDIVIDUAL', 'CLAUDE-TEST ' || p_name, 'HO', 'ACTIVE');
  FOREACH t IN ARRAY ARRAY['PAN','ADDRESS_PROOF','PHOTO'] LOOP
    did := gen_random_uuid();
    INSERT INTO customer.kyc_document (id, customer_id, doc_type, store_key, content_type, size_bytes, sha256, uploaded_by)
    VALUES (did, cid, t, 'tenants/claude-test/kyc/' || cid || '/' || did, 'application/pdf', 1024, repeat('ab', 32), 'maker');
  END LOOP;
  UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now() WHERE customer_id = cid;
  RETURN cid;
END $$;
-- A savings or current account in one name, opened as the application opens it.
CREATE FUNCTION pg_temp.casa(p_product text, p_customer int, p_branch text DEFAULT 'HO') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE aid uuid;
BEGIN
  INSERT INTO deposits.account (family, product_code, product_version, customer_id, branch_code, operating_mode, nomination_mode, status, opened_on, opened_by)
  VALUES ('CASA', p_product, 0, ('00000000-0000-0000-0000-0000000000c' || p_customer)::uuid, p_branch, 'SINGLE', 'NONE', 'ACTIVE', '1999-01-01', 'maker')
  RETURNING id INTO aid;
  INSERT INTO deposits.account_holder VALUES (aid, 1, ('00000000-0000-0000-0000-0000000000c' || p_customer)::uuid);
  INSERT INTO deposits.casa_account VALUES (aid, CASE WHEN p_product = 'SB01' THEN DATE '2026-06-30' END);
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN aid;
END $$;
-- A booked term deposit of 1,00,000 for 12 months on FDC1.
CREATE FUNCTION pg_temp.fd(p_customer int, p_payout text DEFAULT 'BANK_ACCOUNT', p_payout_account uuid DEFAULT NULL) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE aid uuid; p deposits.product%ROWTYPE; card record; a deposits.account%ROWTYPE;
BEGIN
  INSERT INTO deposits.account (family, product_code, product_version, customer_id, branch_code, operating_mode, nomination_mode, status, opened_on, opened_by)
  VALUES ('TERM_DEPOSIT', 'FDC1', 0, ('00000000-0000-0000-0000-0000000000c' || p_customer)::uuid, 'HO', 'SINGLE', 'NONE', 'ACTIVE', '1999-01-01', 'maker')
  RETURNING id INTO aid;
  SELECT * INTO a FROM deposits.account WHERE id = aid;
  INSERT INTO deposits.account_holder VALUES (aid, 1, ('00000000-0000-0000-0000-0000000000c' || p_customer)::uuid);
  SELECT * INTO p FROM deposits.product WHERE code = 'FDC1';
  SELECT * INTO card FROM deposits.card_rate(p.rate_card, 100000, a.opened_on, (a.opened_on + interval '12 months')::date);
  INSERT INTO deposits.term_deposit (account_id, principal, tenure_months, tenure_days, booked_on, rate_percent, card_rate_percent,
      card_from_amount, card_from_months, card_from_days, add_ons, interest_period_months, cumulative, day_count, rounding, tds_applicable,
      premature_allowed, premature_penalty_percent, premature_min_days, payout_to, payout_account_id, maturity_instruction)
  VALUES (aid, 100000, 12, 0, '1999-01-01', card.rate_percent, card.rate_percent, card.from_amount, card.from_months, card.from_days, '[]',
      p.interest_period_months, true, p.day_count, p.rounding, p.tds_applicable, p.premature_allowed, p.premature_penalty_percent,
      p.premature_min_days, p_payout, p_payout_account, 'PAY_OUT');
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN aid;
END $$;
CREATE FUNCTION pg_temp.today() RETURNS date LANGUAGE sql STABLE AS $$ SELECT business_date FROM platform.business_day WHERE id = 1 $$;
CREATE FUNCTION pg_temp.no(p_account uuid) RETURNS text LANGUAGE sql STABLE AS $$ SELECT account_no FROM deposits.account WHERE id = p_account $$;
CREATE FUNCTION pg_temp.head(p_account uuid) RETURNS text LANGUAGE sql STABLE AS $$
  SELECT p.gl_liability FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = p_account $$;
CREATE FUNCTION pg_temp.branch(p_account uuid) RETURNS text LANGUAGE sql STABLE AS $$ SELECT branch_code FROM deposits.account WHERE id = p_account $$;
CREATE FUNCTION pg_temp.balance(p_account uuid) RETURNS numeric LANGUAGE sql STABLE AS $$
  SELECT coalesce((SELECT balance FROM deposits.account_balance WHERE account_id = p_account), 0) $$;
-- A movement as the application writes it: a two-leg lot (the account on its product's head against p_gl) and the
-- movement row, in one transaction. p_extra: lotAmount, lotHead, valueDate, party (adds the other bank's account).
CREATE FUNCTION pg_temp.move(p_account uuid, p_kind text, p_amount numeric, p_gl text DEFAULT '1201', p_extra jsonb DEFAULT '{}') RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid(); dir text; mid uuid; amt numeric := coalesce((p_extra->>'lotAmount')::numeric, p_amount);
BEGIN
  dir := CASE WHEN p_kind IN ('CASH_DEPOSIT','BANK_TRANSFER_IN','TRANSFER_IN','FUNDING') THEN 'CREDIT' ELSE 'DEBIT' END;
  dir := coalesce(p_extra->>'direction', dir);
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'TEST', pg_temp.today(), pg_temp.today(), 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), pg_temp.branch(p_account), coalesce(p_extra->>'lotHead', pg_temp.head(p_account)), pg_temp.no(p_account),
          CASE dir WHEN 'CREDIT' THEN 'CR' ELSE 'DR' END, amt),
         (lot, pg_temp.today(), pg_temp.branch(p_account), p_gl, p_gl, CASE dir WHEN 'CREDIT' THEN 'DR' ELSE 'CR' END, amt);
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, created_by,
                                 party_name_cipher, party_account_cipher, party_account_last4, party_ifsc)
  VALUES (p_account, p_kind, dir, p_amount, 0, '1999-01-01', coalesce((p_extra->>'valueDate')::date, pg_temp.today()), lot, 'maker',
          CASE WHEN p_extra ? 'party' THEN '\x01'::bytea END, CASE WHEN p_extra ? 'party' THEN '\x02'::bytea END,
          CASE WHEN p_extra ? 'party' THEN '6789' END, p_extra->>'party')
  RETURNING id INTO mid;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
-- A transfer: one lot, a movement on each account.
CREATE FUNCTION pg_temp.transfer(p_from uuid, p_to uuid, p_amount numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid();
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'TEST', pg_temp.today(), pg_temp.today(), 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), pg_temp.branch(p_from), pg_temp.head(p_from), pg_temp.no(p_from), 'DR', p_amount),
         (lot, pg_temp.today(), pg_temp.branch(p_to), pg_temp.head(p_to), pg_temp.no(p_to), 'CR', p_amount);
  IF pg_temp.branch(p_from) <> pg_temp.branch(p_to) THEN
    INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
    VALUES (lot, pg_temp.today(), pg_temp.branch(p_from), '1900', '1900', 'CR', p_amount),
           (lot, pg_temp.today(), pg_temp.branch(p_to), '1900', '1900', 'DR', p_amount);
  END IF;
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, counterparty_id, created_by)
  VALUES (p_from, 'TRANSFER_OUT', 'DEBIT', p_amount, 0, pg_temp.today(), pg_temp.today(), lot, p_to, 'maker'),
         (p_to, 'TRANSFER_IN', 'CREDIT', p_amount, 0, pg_temp.today(), pg_temp.today(), lot, p_from, 'maker');
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN lot;
END $$;
-- The reversal of every movement of a lot: the mirror lot and a REVERSAL movement for each; a funding reversed takes
-- its deposit back to BOOKED.
CREATE FUNCTION pg_temp.reverse(p_movement uuid, p_reason text DEFAULT 'posted to the wrong account', p_amount numeric DEFAULT NULL,
                                 p_mark_lot boolean DEFAULT true)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE o deposits.movement%ROWTYPE; lot uuid := gen_random_uuid(); m deposits.movement%ROWTYPE; rid uuid;
BEGIN
  SELECT * INTO o FROM deposits.movement WHERE id = p_movement;
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, reverses, created_by)
  VALUES (lot, 'REVERSAL', pg_temp.today(), o.value_date, CASE WHEN p_mark_lot THEN o.lot_id END, 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  SELECT lot, pg_temp.today(), branch_code, gl_code, account_no, CASE side WHEN 'DR' THEN 'CR' ELSE 'DR' END, amount
    FROM ledger.account_entry WHERE lot_id = o.lot_id AND business_date = o.business_date;
  FOR m IN SELECT * FROM deposits.movement WHERE lot_id = o.lot_id ORDER BY seq LOOP
    INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, reverses,
                                   reversal_reason, created_by)
    VALUES (m.account_id, 'REVERSAL', CASE m.direction WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END, coalesce(p_amount, m.amount), 0,
            pg_temp.today(), pg_temp.today(), lot, m.id, p_reason, 'checker')
    RETURNING id INTO rid;
    UPDATE deposits.movement SET reversed_by = rid WHERE id = m.id;
    IF m.kind = 'FUNDING' THEN
      UPDATE deposits.term_deposit SET start_date = NULL, maturity_date = NULL, funding_source = NULL, funding_account_id = NULL,
             funding_movement_id = NULL, funding_reference = NULL WHERE account_id = m.account_id;
      UPDATE deposits.account SET status = 'BOOKED' WHERE id = m.account_id;
    END IF;
  END LOOP;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
-- Funding a booked deposit with its principal, as the application does. p_extra: amount, from (paying account),
-- start (start date), bank (records a payout bank account).
CREATE FUNCTION pg_temp.fund(p_deposit uuid, p_source text, p_extra jsonb DEFAULT '{}') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid(); amt numeric := coalesce((p_extra->>'amount')::numeric, 100000); from_acct uuid := (p_extra->>'from')::uuid;
        mid uuid; st date := coalesce((p_extra->>'start')::date, pg_temp.today());
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'TEST', pg_temp.today(), pg_temp.today(), 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), 'HO', CASE p_source WHEN 'ACCOUNT' THEN pg_temp.head(from_acct) WHEN 'CASH' THEN '1201' ELSE '1203' END,
          CASE p_source WHEN 'ACCOUNT' THEN pg_temp.no(from_acct) WHEN 'CASH' THEN '1201' ELSE '1203' END, 'DR', amt),
         (lot, pg_temp.today(), 'HO', '2403', pg_temp.no(p_deposit), 'CR', amt);
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, counterparty_id, created_by)
  VALUES (p_deposit, 'FUNDING', 'CREDIT', amt, 0, pg_temp.today(), pg_temp.today(), lot, from_acct, 'maker') RETURNING id INTO mid;
  IF p_source = 'ACCOUNT' THEN
    INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, counterparty_id, created_by)
    VALUES (from_acct, 'FUNDING_PAID', 'DEBIT', amt, 0, pg_temp.today(), pg_temp.today(), lot, p_deposit, 'maker');
  END IF;
  UPDATE deposits.term_deposit SET start_date = st, maturity_date = (st + interval '12 months')::date, funding_source = p_source,
         funding_account_id = from_acct, funding_movement_id = mid,
         payout_bank_name_cipher = CASE WHEN p_extra ? 'bank' THEN '\x01'::bytea END,
         payout_bank_account_cipher = CASE WHEN p_extra ? 'bank' THEN '\x02'::bytea END,
         payout_bank_account_last4 = CASE WHEN p_extra ? 'bank' THEN '4321' END, payout_bank_ifsc = p_extra->>'bank'
   WHERE account_id = p_deposit;
  UPDATE deposits.account SET status = 'ACTIVE' WHERE id = p_deposit;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
CREATE FUNCTION pg_temp.hold(p_account uuid, p_kind text, p_amount numeric DEFAULT NULL) RETURNS uuid LANGUAGE sql AS $$
  INSERT INTO deposits.account_hold (account_id, kind, amount, reason, placed_on, placed_by)
  VALUES (p_account, p_kind, p_amount, 'CLAUDE-TEST order', '1999-01-01', 'maker') RETURNING id $$;
CREATE FUNCTION pg_temp.release(p_hold uuid) RETURNS void LANGUAGE sql AS $$
  UPDATE deposits.account_hold SET status = 'RELEASED', released_on = '1999-01-01', released_by = 'checker', release_reason = 'order lifted'
   WHERE id = p_hold $$;

INSERT INTO platform.branch (code, name, state_code, is_head_office) VALUES ('HO', 'Head Office', '32', true);
INSERT INTO platform.branch (code, name, state_code, parent_code) VALUES ('MUM', 'Mumbai', '27', 'HO');
INSERT INTO platform.business_day VALUES (1, '2026-06-30', 'OPEN');
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Bank Ltd', 'BANK', '32');
SELECT ledger.load_starter_kit('BANK');
SELECT ledger.load_lending_heads();
SELECT ledger.load_deposit_heads();
SELECT pg_temp.customer(1, 'Asha Depositor');
SELECT pg_temp.customer(2, 'Binu Other');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode, interest_posting_months,
                              cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('SB01', 'CLAUDE-TEST Savings', 'CASA', 'SAVINGS', 'ACTUAL_365', 1000, '[{"fromBalance": 0, "ratePercent": 2.75}]', 'INCREMENTAL', 3,
        true, '2401', '5108', '2026-06-30', 'maker');
INSERT INTO deposits.product (code, name, family, product_type, min_balance, cheque_facility, gl_liability, effective_from, updated_by)
VALUES ('CA01', 'CLAUDE-TEST Current', 'CASA', 'CURRENT', 10000, true, '2402', '2026-06-30', 'maker');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, min_tenure_months, min_tenure_days, max_tenure_months,
    max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed, premature_penalty_percent, premature_min_days,
    auto_renewal_allowed, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('FDC1', 'CLAUDE-TEST FD', 'TERM_DEPOSIT', 'FD_CUMULATIVE', 'ACTUAL_365', 10000, 0, 7, 120, 0, 3,
        '[{"fromAmount": 0, "fromMonths": 0, "fromDays": 7, "ratePercent": 4.5}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.1}]',
        '[]', true, 1, 7, true, '2403', '5107', '2026-06-30', 'maker');
CREATE TEMP TABLE acct (name text PRIMARY KEY, id uuid);
INSERT INTO acct VALUES ('sb', pg_temp.casa('SB01', 1)), ('ca_mum', pg_temp.casa('CA01', 1, 'MUM')), ('sb_other', pg_temp.casa('SB01', 2));
CREATE FUNCTION pg_temp.a(p text) RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT id FROM acct WHERE name = p $$;

INSERT INTO acct VALUES ('fd', pg_temp.fd(1, 'CASA', pg_temp.a('sb'))), ('fd2', pg_temp.fd(1, 'CASA', pg_temp.a('sb')));
CREATE TEMP TABLE mv (name text PRIMARY KEY, id uuid);
-- a two-leg lot: Dr p_dr_gl / Cr p_cr_gl, with the given account numbers on each leg
CREATE FUNCTION pg_temp.lot(p_dr_gl text, p_dr_no text, p_cr_gl text, p_cr_no text, p_amount numeric, p_type text DEFAULT 'TEST') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid();
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, p_type, pg_temp.today(), pg_temp.today(), 'eod');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), 'HO', p_dr_gl, p_dr_no, 'DR', p_amount), (lot, pg_temp.today(), 'HO', p_cr_gl, p_cr_no, 'CR', p_amount);
  RETURN lot;
END $$;
-- interest credited from payable to an account, as the day-end does
CREATE FUNCTION pg_temp.credit(p_account uuid, p_amount numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := pg_temp.lot('2404', '2404', pg_temp.head(p_account), pg_temp.no(p_account), p_amount, 'DEPOSIT_INTEREST_CREDIT'); mid uuid;
BEGIN
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, created_by)
  VALUES (p_account, 'INTEREST_CREDIT', 'CREDIT', p_amount, 0, pg_temp.today(), lot, 'eod') RETURNING id INTO mid;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
-- a deposit's interest paid to a savings account
CREATE FUNCTION pg_temp.payout(p_deposit uuid, p_to uuid, p_amount numeric, p_counterparty boolean DEFAULT true) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := pg_temp.lot(pg_temp.head(p_deposit), pg_temp.no(p_deposit), pg_temp.head(p_to), pg_temp.no(p_to), p_amount, 'DEPOSIT_INTEREST_PAYOUT'); mid uuid;
BEGIN
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, counterparty_id, created_by)
  VALUES (p_deposit, 'INTEREST_PAYOUT', 'DEBIT', p_amount, 0, pg_temp.today(), lot, CASE WHEN p_counterparty THEN p_to END, 'eod') RETURNING id INTO mid;
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, counterparty_id, created_by)
  VALUES (p_to, 'TRANSFER_IN', 'CREDIT', p_amount, 0, pg_temp.today(), lot, p_deposit, 'eod');
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
INSERT INTO mv VALUES ('cash', pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 200000));
INSERT INTO mv VALUES ('fund', pg_temp.fund(pg_temp.a('fd'), 'ACCOUNT', jsonb_build_object('from', pg_temp.a('sb'))));
SELECT ledger.load_deposit_heads();
-- tax taken from an account, as the day-end does after a credit
CREATE FUNCTION pg_temp.tax(p_account uuid, p_amount numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := pg_temp.lot(pg_temp.head(p_account), pg_temp.no(p_account), '2205', '2205', p_amount, 'DEPOSIT_TAX_DEDUCTED'); mid uuid;
BEGIN
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, created_by)
  VALUES (p_account, 'TAX_DEDUCTED', 'DEBIT', p_amount, 0, pg_temp.today(), lot, 'eod') RETURNING id INTO mid;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
UPDATE customer.customer SET pan_hash = decode(repeat('ab', 32), 'hex') WHERE id = '00000000-0000-0000-0000-0000000000c1';
INSERT INTO customer.customer (id, customer_no, customer_type, display_name, home_branch, status)
VALUES ('00000000-0000-0000-0000-0000000000c9', '9001000000009' || platform.luhn_digit('9001000000009'), 'NON_INDIVIDUAL', 'CLAUDE-TEST Traders', 'HO', 'ACTIVE');

-- 1. settings and the movement ---------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT value FROM platform.system_property WHERE key = 'deposits.gl.tds-payable') = '2205'
                     AND (SELECT value FROM platform.system_property WHERE key = 'deposits.tds.rounding') = 'RUPEE_HALF_UP'
                     AND EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2205'),
                     'T1 tax deducted is owed on 2205, rounded to the rupee');
INSERT INTO mv VALUES ('fdint', pg_temp.credit(pg_temp.a('fd'), 66666.67));
INSERT INTO mv VALUES ('fdtax', pg_temp.tax(pg_temp.a('fd'), 6667));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('fd')) = 100000 + 66666.67 - 6667, 'T2 a funded deposit takes the tax on its interest');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.tax(pg_temp.a('fd'), 60000) $q$, '23514', 'T3 never below its principal');
SELECT pg_temp.hold(pg_temp.a('sb'), 'TOTAL_FREEZE');
INSERT INTO mv VALUES ('sbint', pg_temp.credit(pg_temp.a('sb'), 100));
INSERT INTO mv VALUES ('sbtax', pg_temp.tax(pg_temp.a('sb'), 10));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 100000 + 90, 'T4 a freeze does not stop the tax on interest');
SELECT pg_temp.release((SELECT id FROM deposits.account_hold WHERE account_id = pg_temp.a('sb') AND status = 'ACTIVE'));
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, created_by)
                                VALUES (pg_temp.a('sb'), 'TAX_DEDUCTED', 'CREDIT', 1, 0, pg_temp.today(), pg_temp.lot('2205', '2205', '2401', pg_temp.no(pg_temp.a('sb')), 1), 'eod') $q$, '23514',
                           'T5 tax is deducted, not credited');

-- 2. the tax ledger -------------------------------------------------------------------------------------------------
INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, rate_percent, basis, senior, pan_on_file,
                                 interest_movement_id, tax_movement_id, lot_id, created_by)
VALUES ('00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.a('fd'), pg_temp.today(), 66666.67, 6667, 10, 'RATE', false, true,
        (SELECT id FROM mv WHERE name = 'fdint'), (SELECT id FROM mv WHERE name = 'fdtax'),
        (SELECT lot_id FROM deposits.movement WHERE id = (SELECT id FROM mv WHERE name = 'fdtax')), 'eod');
SELECT pg_temp.check((SELECT count(*) FROM deposits.tds_problems()) = 1, 'L1 tax on the payable head that the ledger does not record is a problem');
INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, rate_percent, basis, senior, pan_on_file,
                                 interest_movement_id, tax_movement_id, lot_id, created_by)
VALUES ('00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.a('sb'), pg_temp.today(), 100, 10, 10, 'RATE', false, true,
        (SELECT id FROM mv WHERE name = 'sbint'), (SELECT id FROM mv WHERE name = 'sbtax'),
        (SELECT lot_id FROM deposits.movement WHERE id = (SELECT id FROM mv WHERE name = 'sbtax')), 'eod');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM deposits.tds_problems()), 'L2 the payable head equals the tax the ledger records');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, basis, senior, pan_on_file, created_by)
                                VALUES ('00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.a('fd'), pg_temp.today(), 100, 101, 'BELOW_THRESHOLD', false, true, 'eod') $q$, '23514',
                           'L3 tax never exceeds the interest');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, rate_percent, basis, senior, pan_on_file, created_by)
                                VALUES ('00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.a('fd'), pg_temp.today(), 100, 10, 10, 'RATE', false, true, 'eod') $q$, '23514',
                           'L4 tax deducted has its posting');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, basis, senior, pan_on_file, created_by)
                                VALUES ('00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.a('fd'), pg_temp.today(), 100, 0, 'RATE', false, true, 'eod') $q$, '23514',
                           'L5 a deduction at the rate names the rate');
INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, basis, senior, pan_on_file, created_by)
VALUES ('00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.a('fd'), pg_temp.today(), 5000, 0, 'BELOW_THRESHOLD', false, true, 'eod');
SELECT pg_temp.check((SELECT sum(interest) = 71766.67 AND sum(tax) = 6677 FROM deposits.tds_ledger WHERE customer_id = '00000000-0000-0000-0000-0000000000c1' AND tax_year = 2026),
                     'L6 a credit within the threshold is recorded too: the year adds up across the depositor''s accounts');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.tds_ledger SET tax = 0 $q$, '42501', 'L7 the tax ledger is kept as written');

-- 3. Form 121 ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'TDS_DECLARATION' AND action = '*') = 1,
                     'D1 a checker approves a declaration and its revocation');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.tds_declaration (id, customer_id, tax_year, received_on, recorded_by)
                                VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c9', 2026, pg_temp.today(), 'maker') $q$, '23514',
                           'D2 Form 121 is a declaration by an individual');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.tds_declaration (id, customer_id, tax_year, received_on, recorded_by)
                                VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 2026, pg_temp.today(), 'maker') $q$, '23514',
                           'D3 a declaration needs a PAN on file');
INSERT INTO deposits.tds_declaration (id, customer_id, tax_year, reference, received_on, recorded_by)
VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000c1', 2026, 'ACK-1', pg_temp.today(), 'maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.tds_declaration (id, customer_id, tax_year, received_on, recorded_by)
                                VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.today(), 'maker') $q$, '23505',
                           'D4 one declaration in force per depositor and tax year');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.tds_declaration SET reference = 'X' WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$, '42501',
                           'D5 a declaration never changes');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.tds_declaration SET status = 'REVOKED' WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$, '23514',
                           'D6 a revocation has its date and reason');
UPDATE deposits.tds_declaration SET status = 'REVOKED', revoked_on = pg_temp.today(), revoked_by = 'maker', revoke_reason = 'income above the limit'
 WHERE id = '00000000-0000-0000-0000-0000000000d1';
SELECT pg_temp.expect_fail($q$ UPDATE deposits.tds_declaration SET revoke_reason = 'again' WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$, '42501',
                           'D7 a revoked declaration never changes');
INSERT INTO deposits.tds_declaration (id, customer_id, tax_year, received_on, recorded_by)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.today(), 'maker');
SELECT pg_temp.check((SELECT count(*) = 2 FROM deposits.tds_declaration WHERE customer_id = '00000000-0000-0000-0000-0000000000c1'),
                     'D8 after a revocation a new declaration can be recorded');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.tds_declaration $q$, '42501', 'D9 a declaration is never deleted');

SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM deposits.balance_problems()), 'Z1 at the end every balance agrees with the ledger');
SELECT pg_temp.check((SELECT count(*) = 0 FROM (SELECT lot_id FROM ledger.account_entry GROUP BY lot_id, branch_code
                                                  HAVING sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) <> 0) x),
                     'Z2 and every lot balances per branch');
SELECT 'TESTS PASSED' AS result;
