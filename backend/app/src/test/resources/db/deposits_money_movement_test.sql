-- Phase 4 (P4-2, tenant V30): money movement on deposit accounts - movements and balances, freezes and liens,
-- reversals, funding a term deposit. Run on a fresh tenant database after the tenant migrations.
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

-- settings and rules -----------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 4 FROM platform.system_property WHERE key IN ('deposits.gl.cash','deposits.gl.bank-in',
                        'deposits.gl.bank-out','deposits.funding-within-days')),
                     'S1 the cash and bank heads and the funding window are settings');
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'DEPOSIT_TXN' AND action = '*' AND min_amount IS NULL) = 0
                     AND (SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'DEPOSIT_TXN' AND action = '*' AND min_amount = 200000) = 1
                     AND (SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'DEPOSIT_TXN' AND action = 'REVERSE') = 1
                     AND (SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'DEPOSIT_HOLD') = 1,
                     'S2 money moves at once below 2,00,000; from it, and for every reversal and hold, a checker is needed');
INSERT INTO platform.amount_limit (role_name, txn_type, per_txn_max, effective_from, created_by)
VALUES ('MAKER', 'DEPOSIT_DEBIT', 50000, '2026-04-01', 'admin'), ('MAKER', 'DEPOSIT_CREDIT', 500000, '2026-04-01', 'admin');
SELECT pg_temp.check((SELECT count(*) = 2 FROM platform.amount_limit WHERE txn_type LIKE 'DEPOSIT_%'),
                     'S3 role amount limits take the two deposit types');

-- movements and balances -------------------------------------------------------------------------------------
CREATE TEMP TABLE mv (name text PRIMARY KEY, id uuid);
INSERT INTO mv VALUES ('dep1', pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10000));
SELECT pg_temp.check((SELECT balance_after = 10000 AND business_date = '2026-06-30' AND value_date = '2026-06-30' FROM deposits.movement
                       WHERE id = (SELECT id FROM mv WHERE name = 'dep1'))
                     AND pg_temp.balance(pg_temp.a('sb')) = 10000,
                     'M1 a cash deposit is recorded with the balance after it, on the business date');
INSERT INTO mv VALUES ('wd1', pg_temp.move(pg_temp.a('sb'), 'CASH_WITHDRAWAL', 2500.50));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 7499.50, 'M2 a withdrawal takes the balance down');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_WITHDRAWAL', 7499.51) $q$, '23514',
                           'M3 a withdrawal never takes the balance below zero');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10, '1201', '{"valueDate": "2026-06-29"}') $q$, '23514',
                           'M4 a movement takes value on the business date');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10, '1201', '{"direction": "DEBIT"}') $q$, '23514',
                           'M5 a cash deposit is a credit');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10.005) $q$, '23514', 'M6 an amount is in whole paise');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'FUNDING', 10) $q$, '23514', 'M7 a savings account is not funded like a deposit');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10, '1201', '{"lotHead": "2402"}') $q$, '23514',
                           'M8 the lot posts to the account on its product''s own liability head');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10, '1201', '{"lotAmount": 11}') $q$, '23514',
                           'M9 the movement is exactly what the lot posts to the account');
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 7499.50 AND (SELECT count(*) = 2 FROM deposits.movement WHERE account_id = pg_temp.a('sb')),
                     'M10 a refused movement leaves nothing behind');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account_balance SET balance = 1000000 $q$, '42501', 'M11 a balance changes only by a movement');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.account_balance VALUES (pg_temp.a('sb_other'), 5) $q$, '42501',
                           'M12 a balance is not written directly');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.movement SET amount = 1 WHERE id = (SELECT id FROM mv WHERE name = 'dep1') $q$, '42501',
                           'M13 a movement never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.movement $q$, '42501', 'M14 a movement is never deleted');
SELECT pg_temp.expect_fail($q$ TRUNCATE deposits.movement CASCADE $q$, '42501', 'M15 movements are not truncated');
INSERT INTO mv VALUES ('bin', pg_temp.move(pg_temp.a('sb'), 'BANK_TRANSFER_IN', 500.50, '1203', '{"party": "HDFC0001234"}'));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 8000
                     AND (SELECT party_ifsc = 'HDFC0001234' AND party_account_last4 = '6789' FROM deposits.movement WHERE id = (SELECT id FROM mv WHERE name = 'bin')),
                     'M16 a bank transfer in keeps the other bank''s account, sealed, with its IFSC and last four digits');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10, '1201', '{"party": "HDFC0001234"}') $q$, '23514',
                           'M17 only a bank transfer carries the other bank''s account');

-- transfers --------------------------------------------------------------------------------------------------
SELECT pg_temp.transfer(pg_temp.a('sb'), pg_temp.a('ca_mum'), 3000);
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 5000 AND pg_temp.balance(pg_temp.a('ca_mum')) = 3000
                     AND (SELECT count(*) = 2 AND bool_and(counterparty_id IS NOT NULL) FROM deposits.movement WHERE kind IN ('TRANSFER_IN','TRANSFER_OUT')),
                     'T1 a transfer between branches moves the money and names the other account on each side');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.transfer(pg_temp.a('ca_mum'), pg_temp.a('sb'), 3000.01) $q$, '23514',
                           'T2 a transfer never takes the paying account below zero');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM deposits.balance_problems()), 'T3 every balance agrees with the ledger');

-- freezes and liens ------------------------------------------------------------------------------------------
CREATE TEMP TABLE hd (name text PRIMARY KEY, id uuid);
INSERT INTO hd VALUES ('dfz', pg_temp.hold(pg_temp.a('sb'), 'DEBIT_FREEZE'));
SELECT pg_temp.check((SELECT placed_on = '2026-06-30' AND status = 'ACTIVE' FROM deposits.account_hold WHERE id = (SELECT id FROM hd WHERE name = 'dfz')),
                     'H1 a freeze is placed on the business date');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_WITHDRAWAL', 10) $q$, '23514', 'H2 a debit freeze stops a withdrawal');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.transfer(pg_temp.a('sb'), pg_temp.a('ca_mum'), 10) $q$, '23514', 'H3 and a transfer out');
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 1000);
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 6000, 'H4 but not a deposit');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.hold(pg_temp.a('sb'), 'DEBIT_FREEZE') $q$, '23505', 'H5 one freeze of a kind at a time');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account_hold SET reason = 'changed' WHERE id = (SELECT id FROM hd WHERE name = 'dfz') $q$, '42501',
                           'H6 a hold never changes, except to be released');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.account_hold $q$, '42501', 'H7 a hold is never deleted');
SELECT pg_temp.release((SELECT id FROM hd WHERE name = 'dfz'));
SELECT pg_temp.check((SELECT status = 'RELEASED' AND released_on = '2026-06-30' FROM deposits.account_hold WHERE id = (SELECT id FROM hd WHERE name = 'dfz')),
                     'H8 a released freeze records when, by whom and why');
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_WITHDRAWAL', 1000);
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 5000, 'H9 once released, money goes out again');
INSERT INTO hd VALUES ('cfz', pg_temp.hold(pg_temp.a('sb'), 'CREDIT_FREEZE'));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10) $q$, '23514', 'H10 a credit freeze stops a deposit');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.transfer(pg_temp.a('ca_mum'), pg_temp.a('sb'), 10) $q$, '23514', 'H11 and a transfer in');
SELECT pg_temp.release((SELECT id FROM hd WHERE name = 'cfz'));
INSERT INTO hd VALUES ('tfz', pg_temp.hold(pg_temp.a('sb'), 'TOTAL_FREEZE'));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10) $q$, '23514', 'H12 a total freeze stops money in');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_WITHDRAWAL', 10) $q$, '23514', 'H13 and money out');
SELECT pg_temp.release((SELECT id FROM hd WHERE name = 'tfz'));
INSERT INTO hd VALUES ('lien', pg_temp.hold(pg_temp.a('sb'), 'LIEN', 4000));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_WITHDRAWAL', 1000.01) $q$, '23514',
                           'H14 a debit never takes the balance below the liens');
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_WITHDRAWAL', 1000);
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 4000, 'H15 money above the liens goes out');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.hold(pg_temp.a('sb'), 'LIEN', 0.01) $q$, '23514', 'H16 a lien is placed only within the available balance');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.hold(pg_temp.a('sb'), 'TOTAL_FREEZE', 5) $q$, '23514', 'H17 a freeze has no amount');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.hold(pg_temp.a('sb'), 'LIEN') $q$, '23514', 'H18 a lien has an amount');

-- reversals --------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'bin')) $q$, '23514',
                           'R1 reversing a deposit that would break the lien is refused');
SELECT pg_temp.release((SELECT id FROM hd WHERE name = 'lien'));
INSERT INTO hd VALUES ('tfz2', pg_temp.hold(pg_temp.a('sb'), 'TOTAL_FREEZE'));
SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'wd1'));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 6500.50
                     AND (SELECT reversed_by IS NOT NULL FROM deposits.movement WHERE id = (SELECT id FROM mv WHERE name = 'wd1'))
                     AND (SELECT reversal_reason = 'posted to the wrong account' FROM deposits.movement WHERE reverses = (SELECT id FROM mv WHERE name = 'wd1')),
                     'R2 a reversal (a correction) is made under a total freeze, with its reason, and marks what it reverses');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'wd1')) $q$, '23505', 'R3 a movement is reversed once: the ledger refuses its lot twice');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'wd1'), 'again', NULL, false) $q$, '23514',
                           'R3b and so does the movement, whatever the lot says');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.reverse((SELECT reversed_by FROM deposits.movement WHERE id = (SELECT id FROM mv WHERE name = 'wd1'))) $q$, '23514',
                           'R4 a reversal is not reversed');
SELECT pg_temp.release((SELECT id FROM hd WHERE name = 'tfz2'));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'bin'), 'x', 400) $q$, '23514',
                           'R5 a reversal is the same amount');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'bin'), ' ') $q$, '23514', 'R6 a reversal has a reason');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM deposits.balance_problems()), 'R7 every balance still agrees with the ledger');

-- funding a term deposit -------------------------------------------------------------------------------------
INSERT INTO acct VALUES ('fd', pg_temp.fd(1, 'CASA', pg_temp.a('sb')));
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 100000);
SELECT pg_temp.move(pg_temp.a('sb_other'), 'CASH_DEPOSIT', 100000);
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('fd'), 'CASH_DEPOSIT', 10) $q$, '23514', 'F1 money comes into a term deposit by its funding only');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.hold(pg_temp.a('fd'), 'LIEN', 10) $q$, '23514', 'F2 a booked deposit takes no lien');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.immediate('UPDATE deposits.account SET status = ''ACTIVE'' WHERE id = ''' || pg_temp.a('fd') || '''') $q$, '23514',
                           'F3 a deposit is not made active without its funding');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fund(pg_temp.a('fd'), 'ACCOUNT', jsonb_build_object('from', pg_temp.a('sb'), 'amount', 99999)) $q$, '23514',
                           'F4 a deposit is funded with its principal');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fund(pg_temp.a('fd'), 'ACCOUNT', jsonb_build_object('from', pg_temp.a('sb_other'))) $q$, '23514',
                           'F5 from an account the depositor holds');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fund(pg_temp.a('fd'), 'ACCOUNT', jsonb_build_object('from', pg_temp.a('sb'), 'start', '2026-07-01')) $q$, '23514',
                           'F6 on the business date, when its interest starts');
INSERT INTO mv VALUES ('fund', pg_temp.fund(pg_temp.a('fd'), 'ACCOUNT', jsonb_build_object('from', pg_temp.a('sb'))));
SELECT pg_temp.check((SELECT status = 'ACTIVE' FROM deposits.account WHERE id = pg_temp.a('fd'))
                     AND (SELECT start_date = '2026-06-30' AND maturity_date = '2027-06-30' AND funding_source = 'ACCOUNT' FROM deposits.term_deposit
                           WHERE account_id = pg_temp.a('fd'))
                     AND pg_temp.balance(pg_temp.a('fd')) = 100000 AND pg_temp.balance(pg_temp.a('sb')) = 6500.50,
                     'F7 funded from the savings account: active, interest from today, the principal moved across');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fund(pg_temp.a('fd'), 'CASH') $q$, '23514', 'F8 a deposit is funded once');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET principal = 200000 WHERE account_id = pg_temp.a('fd') $q$, '42501',
                           'F9 the terms stay fixed');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.hold(pg_temp.a('fd'), 'DEBIT_FREEZE') $q$, '23514', 'F10 a term deposit takes a lien, not a freeze');
INSERT INTO hd VALUES ('fdlien', pg_temp.hold(pg_temp.a('fd'), 'LIEN', 75000));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'fund'), 'paid from the wrong account') $q$, '23514',
                           'F11 a funding under lien is not reversed');
SELECT pg_temp.release((SELECT id FROM hd WHERE name = 'fdlien'));
SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'fund'), 'paid from the wrong account');
SELECT pg_temp.check((SELECT status = 'BOOKED' FROM deposits.account WHERE id = pg_temp.a('fd'))
                     AND (SELECT start_date IS NULL FROM deposits.term_deposit WHERE account_id = pg_temp.a('fd'))
                     AND pg_temp.balance(pg_temp.a('fd')) = 0 AND pg_temp.balance(pg_temp.a('sb')) = 106500.50,
                     'F12 the funding reversed: booked again, nothing held, the money back in the savings account');
INSERT INTO acct VALUES ('fd_bank', pg_temp.fd(1));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fund(pg_temp.a('fd_bank'), 'CASH') $q$, '23514',
                           'F13 a deposit paid out to a bank account is funded with that bank account recorded');
SELECT pg_temp.fund(pg_temp.a('fd_bank'), 'CASH', '{"bank": "SBIN0001234"}');
SELECT pg_temp.check((SELECT payout_bank_ifsc = 'SBIN0001234' AND payout_bank_account_last4 = '4321' AND funding_source = 'CASH'
                        FROM deposits.term_deposit WHERE account_id = pg_temp.a('fd_bank')),
                     'F14 funded in cash, with the payout bank account sealed and its IFSC kept');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET payout_bank_ifsc = 'SBIN0009999' WHERE account_id = pg_temp.a('fd') $q$, '23514',
                           'F15 a deposit paid to an account here has no bank account');
INSERT INTO acct VALUES ('fd_late', pg_temp.fd(1));
SELECT set_config('corebanking.eod', 'on', false);
UPDATE platform.business_day SET business_date = '2026-07-30' WHERE id = 1;
SELECT set_config('corebanking.eod', 'off', false);
SELECT pg_temp.fund(pg_temp.a('fd'), 'CASH');
SELECT pg_temp.check((SELECT start_date = '2026-07-30' AND maturity_date = '2027-07-30' FROM deposits.term_deposit WHERE account_id = pg_temp.a('fd')),
                     'F16 funded on the 30th day after booking: interest starts that day, and the maturity follows from it');
SELECT set_config('corebanking.eod', 'on', false);
UPDATE platform.business_day SET business_date = '2026-07-31' WHERE id = 1;
SELECT set_config('corebanking.eod', 'off', false);
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.fund(pg_temp.a('fd_late'), 'CASH', '{"bank": "SBIN0001234"}') $q$, '23514',
                           'F17 a booking not funded within 30 days lapses');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM deposits.balance_problems()), 'I1 at the end every balance agrees with the ledger');
SELECT pg_temp.check((SELECT count(*) = 0 FROM (SELECT lot_id FROM ledger.account_entry GROUP BY lot_id, branch_code
                                                  HAVING sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) <> 0) x),
                     'I2 and every lot balances per branch');
SELECT 'TESTS PASSED' AS result;
