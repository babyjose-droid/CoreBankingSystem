-- Phase 4 (P4-5b, tenant V36): standing instructions (their shape, who may give them, what may change, the runs that record
-- them) and pledges (a lien naming a loan: on a term deposit of the borrower, for an open loan, released only once it is closed).
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
INSERT INTO platform.business_day VALUES (1, '2026-06-25', 'OPEN');
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Bank Ltd', 'BANK', '32');
SELECT ledger.load_starter_kit('BANK');
SELECT ledger.load_lending_heads();
SELECT ledger.load_deposit_heads();
SELECT pg_temp.customer(1, 'Asha Depositor');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode, interest_posting_months,
                              cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('SB01', 'CLAUDE-TEST Savings', 'CASA', 'SAVINGS', 'ACTUAL_365', 1000, '[{"fromBalance": 0, "ratePercent": 2.75}]', 'INCREMENTAL', 3,
        true, '2401', '5108', '2026-06-25', 'maker');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, min_tenure_months, min_tenure_days, max_tenure_months,
    max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed, premature_penalty_percent, premature_min_days,
    auto_renewal_allowed, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('FDC1', 'CLAUDE-TEST FD cumulative', 'TERM_DEPOSIT', 'FD_CUMULATIVE', 'ACTUAL_365', 10000, 0, 7, 120, 0, 3,
        '[{"fromAmount": 0, "fromMonths": 0, "fromDays": 7, "ratePercent": 4.5}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.1}]',
        '[]', true, 1, 7, true, '2403', '5107', '2026-06-25', 'maker');

SELECT pg_temp.customer(2, 'Biju Other');
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,8,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,kfs_accepted_at,state)
VALUES ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,13,12,'2026-06-25','ACTIVE',now(),'{}'),
       ('00000000-0000-0000-0000-00000000aa02','10010000000025','00000000-0000-0000-0000-0000000000c1','PL01','HO',50000,13,12,'2026-06-25','APPLIED',NULL,'{}');
CREATE TEMP TABLE acct (name text PRIMARY KEY, id uuid);
CREATE FUNCTION pg_temp.a(p text) RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT id FROM acct WHERE name = p $$;
INSERT INTO acct VALUES ('sb', pg_temp.casa('SB01', 1)), ('sb2', pg_temp.casa('SB01', 2)), ('fd', pg_temp.fd(1)), ('fd2', pg_temp.fd(2));
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 50000, '1101');
CREATE FUNCTION pg_temp.si(p_from uuid, p_kind text, p_to uuid, p_loan uuid, p_amount numeric, p_every int, p_start date DEFAULT NULL) RETURNS uuid
LANGUAGE sql AS $$
  INSERT INTO deposits.standing_instruction (from_account_id, kind, to_account_id, loan_id, amount, every_months, start_on, created_by)
  VALUES (p_from, p_kind, p_to, p_loan, p_amount, p_every, coalesce(p_start, pg_temp.today()), 'maker') RETURNING id $$;
\set L1 '''00000000-0000-0000-0000-00000000aa01'''
\set L2 '''00000000-0000-0000-0000-00000000aa02'''

-- ---- standing instructions -------------------------------------------------------------------------------
INSERT INTO acct VALUES ('rent', pg_temp.si(pg_temp.a('sb'), 'TRANSFER', pg_temp.a('sb2'), NULL, 5000, 1));
SELECT pg_temp.check((SELECT instruction_no ~ '^SI[0-9]{8}$' AND status = 'ACTIVE' FROM deposits.standing_instruction WHERE id = pg_temp.a('rent')),
                     'I1 a monthly transfer is given and numbered');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb'), 'TRANSFER', pg_temp.a('sb2'), NULL, NULL, 1) $q$, '23514', 'I2 a transfer has its amount');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb'), 'TRANSFER', pg_temp.a('sb2'), NULL, 5000, 2) $q$, '23514', 'I3 every 1, 3, 6 or 12 months');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb'), 'TRANSFER', pg_temp.a('sb'), NULL, 5000, 1) $q$, '23514', 'I4 an account does not pay itself');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb'), 'TRANSFER', pg_temp.a('sb2'), NULL, 5000, 1, '2026-06-24') $q$, '23514', 'I5 it does not start in the past');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('fd'), 'TRANSFER', pg_temp.a('sb2'), NULL, 5000, 1) $q$, '23514', 'I6 a term deposit gives no standing instruction');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb'), 'TRANSFER', pg_temp.a('fd2'), NULL, 5000, 1) $q$, '23514', 'I7 a transfer goes to a savings or current account');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb'), 'RD_INSTALMENT', pg_temp.a('fd'), NULL, NULL, NULL) $q$, '23514', 'I8 a fixed deposit takes no instalments');
INSERT INTO acct VALUES ('emi', pg_temp.si(pg_temp.a('sb'), 'LOAN_INSTALMENT', NULL, :L1, NULL, NULL));
SELECT pg_temp.check(pg_temp.a('emi') IS NOT NULL, 'I9 the borrower''s account pays the loan''s instalments');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb'), 'LOAN_INSTALMENT', NULL, '00000000-0000-0000-0000-00000000aa01', NULL, NULL) $q$, '23505', 'I10 one instruction per loan');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb2'), 'LOAN_INSTALMENT', NULL, '00000000-0000-0000-0000-00000000aa02', NULL, NULL) $q$, '23514', 'I11 a loan applied for, not sanctioned, is not paid');
UPDATE lending.loan_account SET status = 'SANCTIONED' WHERE id = :L2;
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb2'), 'LOAN_INSTALMENT', NULL, '00000000-0000-0000-0000-00000000aa02', NULL, NULL) $q$, '23514', 'I12 only an account of the borrower pays the loan');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb'), 'LOAN_INSTALMENT', pg_temp.a('sb2'), '00000000-0000-0000-0000-00000000aa02', NULL, NULL) $q$, '23514', 'I13 a loan instruction names no other account');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.standing_instruction SET amount = 6000 WHERE id = pg_temp.a('rent') $q$, '42501', 'I14 an instruction never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.standing_instruction WHERE id = pg_temp.a('rent') $q$, '42501', 'I15 nor is it deleted');

-- ---- runs ------------------------------------------------------------------------------------------------
INSERT INTO acct VALUES ('lot1', pg_temp.transfer(pg_temp.a('sb'), pg_temp.a('sb2'), 5000));
INSERT INTO acct SELECT 'out1', id FROM deposits.movement WHERE lot_id = pg_temp.a('lot1') AND kind = 'TRANSFER_OUT';
INSERT INTO deposits.standing_instruction_run (instruction_id, for_date, business_date, status, amount, lot_id, movement_id)
VALUES (pg_temp.a('rent'), '2026-06-25', '1999-01-01', 'DONE', 5000, pg_temp.a('lot1'), pg_temp.a('out1'));
SELECT pg_temp.check((SELECT business_date = '2026-06-25' FROM deposits.standing_instruction_run WHERE instruction_id = pg_temp.a('rent')),
                     'I16 a payment made is recorded for its date, on the business date');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.standing_instruction_run (instruction_id, for_date, business_date, status, amount, lot_id, movement_id)
    VALUES (pg_temp.a('rent'), '2026-06-25', current_date, 'DONE', 5000, pg_temp.a('lot1'), pg_temp.a('out1')) $q$, '23505', 'I17 a date is paid once');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.standing_instruction_run (instruction_id, for_date, business_date, status, amount, lot_id, movement_id)
    VALUES (pg_temp.a('emi'), '2026-07-25', current_date, 'DONE', 5000, pg_temp.a('lot1'), pg_temp.a('out1')) $q$, '23514', 'I18 a run is the debit of its own kind');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.standing_instruction_run (instruction_id, for_date, business_date, status, amount, lot_id, movement_id)
    VALUES (pg_temp.a('rent'), '2026-07-25', current_date, 'DONE', 4000, pg_temp.a('lot1'), pg_temp.a('out1')) $q$, '23514', 'I19 for the amount debited');
INSERT INTO deposits.standing_instruction_run (instruction_id, for_date, business_date, status, amount, reason)
VALUES (pg_temp.a('emi'), '2026-07-25', current_date, 'FAILED', 8932, 'not enough money');
UPDATE deposits.standing_instruction_run SET attempts = attempts + 1, reason = 'still not enough' WHERE instruction_id = pg_temp.a('emi');
SELECT pg_temp.check((SELECT attempts = 2 FROM deposits.standing_instruction_run WHERE instruction_id = pg_temp.a('emi')), 'I20 a failed attempt is counted again');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.standing_instruction_run SET amount = 1 WHERE instruction_id = pg_temp.a('rent') $q$, '42501', 'I21 a payment made never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.standing_instruction_run $q$, '42501', 'I22 nor is any run deleted');
UPDATE deposits.standing_instruction SET status = 'CANCELLED', closed_by = 'maker', close_reason = 'asked', closed_on = '1999-01-01' WHERE id = pg_temp.a('rent');
SELECT pg_temp.check((SELECT status = 'CANCELLED' AND closed_on = '2026-06-25' FROM deposits.standing_instruction WHERE id = pg_temp.a('rent')),
                     'I23 an instruction is cancelled with its reason, on the business date');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.standing_instruction SET status = 'ACTIVE', closed_on = NULL, close_reason = NULL, closed_by = NULL WHERE id = pg_temp.a('rent') $q$,
                           '42501', 'I24 a cancelled instruction stays cancelled');
SELECT pg_temp.move(pg_temp.a('sb'), 'LOAN_REPAYMENT', 1000, '1201');
SELECT pg_temp.check((SELECT direction = 'DEBIT' FROM deposits.movement WHERE account_id = pg_temp.a('sb') AND kind = 'LOAN_REPAYMENT'),
                     'I25 a savings account pays a loan instalment (a debit)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('fd2'), 'LOAN_REPAYMENT', 1, '1201') $q$, '23514', 'I25b a term deposit pays no loan');
SELECT pg_temp.check((SELECT count(*) = 1 FROM platform.job_definition WHERE code = 'STANDING_INSTRUCTIONS' AND enabled AND built_in), 'I26 the job is scheduled');

-- ---- pledges ---------------------------------------------------------------------------------------------
-- a funded deposit: its funding movement makes it hold 1,00,000
SELECT pg_temp.fund(pg_temp.a('fd'), 'CASH', '{"bank": "HDFC0001234"}');
CREATE FUNCTION pg_temp.pledge(p_account uuid, p_loan uuid, p_amount numeric DEFAULT 100000) RETURNS uuid LANGUAGE sql AS $$
  INSERT INTO deposits.account_hold (account_id, kind, amount, reason, reference, placed_on, placed_by, loan_id)
  VALUES (p_account, 'LIEN', p_amount, 'CLAUDE-TEST sanction', 'Loan', '1999-01-01', 'maker', p_loan) RETURNING id $$;
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.pledge(pg_temp.a('sb'), '00000000-0000-0000-0000-00000000aa01', 1000) $q$, '23514', 'P1 a loan is secured by a term deposit');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.pledge(pg_temp.a('fd2'), '00000000-0000-0000-0000-00000000aa01') $q$, '23514', 'P2 a deposit of the borrower');
INSERT INTO acct VALUES ('pledge', pg_temp.pledge(pg_temp.a('fd'), :L1, 50000));
SELECT pg_temp.check((SELECT kind = 'LIEN' AND loan_id = :L1 FROM deposits.account_hold WHERE id = pg_temp.a('pledge')), 'P3 the deposit is pledged for the loan');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.pledge(pg_temp.a('fd'), '00000000-0000-0000-0000-00000000aa02', 1) $q$, '23505', 'P4 a deposit secures one loan');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.account_hold (account_id, kind, reason, placed_on, placed_by, loan_id)
    VALUES (pg_temp.a('fd'), 'LIEN', 'x', '1999-01-01', 'maker', '00000000-0000-0000-0000-00000000aa02') $q$, '23514', 'P5 a pledge is a lien of an amount');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.release(pg_temp.a('pledge')) $q$, '23514', 'P6 not released while the loan is active');
UPDATE lending.loan_account SET status = 'CLOSED' WHERE id = :L1;
SELECT pg_temp.release(pg_temp.a('pledge'));
SELECT pg_temp.check((SELECT status = 'RELEASED' FROM deposits.account_hold WHERE id = pg_temp.a('pledge')), 'P7 released once the loan is closed');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.pledge(pg_temp.a('fd'), '00000000-0000-0000-0000-00000000aa01') $q$, '23514', 'P8 a closed loan takes no pledge');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.si(pg_temp.a('sb2'), 'LOAN_INSTALMENT', NULL, '00000000-0000-0000-0000-00000000aa01', NULL, NULL) $q$, '23514',
                           'P9 nor a new standing instruction');
\echo ALL STANDING INSTRUCTION AND PLEDGE TESTS PASSED
