-- Phase 4 (P4-6, tenant V37): cheque books and leaves, inward and outward clearing, inoperative and unclaimed accounts,
-- the net owned fund and public deposits outstanding.
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
  INSERT INTO deposits.casa_account VALUES (aid, CASE WHEN p_product LIKE 'SB%' THEN DATE '2026-06-30' END);
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
SELECT pg_temp.customer(2, 'Biju Other');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode, interest_posting_months,
                              cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('SB01', 'CLAUDE-TEST Savings', 'CASA', 'SAVINGS', 'ACTUAL_365', 1000, '[{"fromBalance": 0, "ratePercent": 2.75}]', 'INCREMENTAL', 3,
        true, '2401', '5108', '2026-06-25', 'maker'),
       ('SB02', 'CLAUDE-TEST Savings without cheques', 'CASA', 'SAVINGS', 'ACTUAL_365', 0, '[{"fromBalance": 0, "ratePercent": 2.75}]', 'INCREMENTAL', 3,
        false, '2401', '5108', '2026-06-25', 'maker');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, min_tenure_months, min_tenure_days, max_tenure_months,
    max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed, premature_penalty_percent, premature_min_days,
    auto_renewal_allowed, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('FDC1', 'CLAUDE-TEST FD cumulative', 'TERM_DEPOSIT', 'FD_CUMULATIVE', 'ACTUAL_365', 10000, 0, 7, 120, 0, 3,
        '[{"fromAmount": 0, "fromMonths": 0, "fromDays": 7, "ratePercent": 4.5}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.1}]',
        '[]', true, 1, 7, true, '2403', '5107', '2026-06-25', 'maker');
CREATE TEMP TABLE acct (name text PRIMARY KEY, id uuid);
CREATE FUNCTION pg_temp.a(p text) RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT id FROM acct WHERE name = p $$;
INSERT INTO acct VALUES ('sb', pg_temp.casa('SB01', 1)), ('sb2', pg_temp.casa('SB01', 2)), ('nochq', pg_temp.casa('SB02', 1)), ('fd', pg_temp.fd(1));
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 50000);
SELECT pg_temp.move(pg_temp.a('sb2'), 'CASH_DEPOSIT', 4000);

-- ---- migration --------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 4 FROM platform.system_property WHERE key IN ('deposits.gl.inward-clearing','deposits.gl.outward-clearing',
                     'deposits.gl.unclaimed','deposits.cheque-valid-months')), 'M1 the clearing and unclaimed heads, and how long a cheque stays good');
SELECT pg_temp.check((SELECT checkers_required = 0 FROM platform.approval_rule WHERE entity_type = 'CHEQUE' AND action = 'STOP'),
                     'M2 a stop payment takes effect with no checker');
SELECT pg_temp.check(deposits.customer_induced('CASH_WITHDRAWAL') AND deposits.customer_induced('CHEQUE_PAID')
                     AND NOT deposits.customer_induced('INTEREST_CREDIT') AND NOT deposits.customer_induced('UNCLAIMED_TRANSFER'),
                     'M3 a withdrawal or a cheque keeps an account operative; interest or an unclaimed transfer does not');

-- ---- cheque books and leaves --------------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.book(p_account uuid, p_first int, p_leaves int DEFAULT 10) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE bid uuid;
BEGIN
  INSERT INTO deposits.cheque_book (account_id, first_leaf, leaves, issued_on, issued_by) VALUES (p_account, p_first, p_leaves, '1999-01-01', 'maker')
  RETURNING id INTO bid;
  INSERT INTO deposits.cheque_leaf (account_id, leaf_no, book_id) SELECT p_account, n, bid FROM generate_series(p_first, p_first + p_leaves - 1) n;
  RETURN bid;
END $$;
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.book(pg_temp.a('nochq'), 200001) $q$, '23514', 'K1 no cheque book on a product without the facility');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.book(pg_temp.a('fd'), 200001) $q$, '23514', 'K2 nor on a term deposit');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.book(pg_temp.a('sb'), 200001, 7) $q$, '23514', 'K3 a book has 10, 25, 50 or 100 leaves');
INSERT INTO acct VALUES ('book', pg_temp.book(pg_temp.a('sb'), 100001));
SELECT pg_temp.check((SELECT book_no ~ '^CB[0-9]{8}$' AND issued_on = DATE '2026-06-25' FROM deposits.cheque_book WHERE id = pg_temp.a('book'))
                     AND (SELECT count(*) = 10 FROM deposits.cheque_leaf WHERE book_id = pg_temp.a('book') AND status = 'UNUSED'),
                     'K4 a book of ten leaves, issued on the business date');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.cheque_leaf (account_id, leaf_no, book_id) VALUES (pg_temp.a('sb'), 100011, pg_temp.a('book')) $q$,
                           '23514', 'K5 a leaf belongs to its own book');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.book(pg_temp.a('sb2'), 100005) $q$, '23505', 'K6 a cheque number is issued once');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.cheque_book SET leaves = 25 $q$, '42501', 'K7 a cheque book never changes');
UPDATE deposits.cheque_leaf SET status = 'STOPPED', stop_reason = 'CLAUDE-TEST lost', stopped_by = 'maker' WHERE leaf_no = 100002;
SELECT pg_temp.check((SELECT stopped_on = DATE '2026-06-25' FROM deposits.cheque_leaf WHERE leaf_no = 100002), 'K8 a stop is dated the business date');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.cheque_leaf SET status = 'STOPPED', stop_reason = NULL WHERE leaf_no = 100003 $q$, '23514', 'K9 a stop has a reason');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.cheque_leaf SET status = 'PAID', paid_on = '2026-06-25', presentment_id = gen_random_uuid() WHERE leaf_no = 100002 $q$,
                           '23514', 'K10 a stopped cheque is not paid');
UPDATE deposits.cheque_leaf SET status = 'UNUSED' WHERE leaf_no = 100002;
SELECT pg_temp.check((SELECT stopped_on IS NULL AND stop_reason IS NULL FROM deposits.cheque_leaf WHERE leaf_no = 100002), 'K11 a stop revoked leaves no trace on the leaf');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.cheque_leaf SET book_id = gen_random_uuid() WHERE leaf_no = 100003 $q$, '42501', 'K12 only a leaf''s status changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.cheque_leaf WHERE leaf_no = 100003 $q$, '42501', 'K13 a leaf is never deleted');

-- ---- inward clearing ----------------------------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.present(p_account uuid, p_leaf int, p_amount numeric, p_kind text) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE mid uuid := pg_temp.move(p_account, p_kind, p_amount, '2409'); pid uuid;
BEGIN
  INSERT INTO deposits.cheque_presentment (account_id, account_no, leaf_no, amount, cheque_date, payee, presenting_bank, presented_on, status, movement_id, created_by)
  VALUES (p_account, pg_temp.no(p_account), p_leaf, p_amount, '2026-06-20', 'CLAUDE-TEST Payee', 'HDFC Bank', '1999-01-01', 'PAID', mid, 'maker') RETURNING id INTO pid;
  UPDATE deposits.cheque_leaf SET status = 'PAID', presentment_id = pid, paid_on = '1999-01-01' WHERE account_id = p_account AND leaf_no = p_leaf;
  RETURN pid;
END $$;
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.present(pg_temp.a('sb'), 100001, 500, 'CASH_WITHDRAWAL') $q$, '23514', 'I1 a cheque paid is a CHEQUE_PAID movement');
INSERT INTO acct VALUES ('p1', pg_temp.present(pg_temp.a('sb'), 100001, 5000, 'CHEQUE_PAID'));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 45000 AND (SELECT status = 'PAID' AND paid_on = DATE '2026-06-25' FROM deposits.cheque_leaf WHERE leaf_no = 100001)
                     AND (SELECT presented_on = DATE '2026-06-25' FROM deposits.cheque_presentment WHERE id = pg_temp.a('p1')),
                     'I2 cheque 100001 paid: the account debited, the leaf marked paid');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.cheque_leaf SET status = 'STOPPED', stop_reason = 'x' WHERE leaf_no = 100001 $q$, '23514', 'I3 a paid cheque is not stopped');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.cheque_presentment SET amount = 1 $q$, '42501', 'I4 a presentment never changes');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.cheque_presentment (account_no, leaf_no, amount, cheque_date, payee, presenting_bank, presented_on, status, created_by)
    VALUES ('20019999999999', 1, 10, '2026-06-20', 'x', 'y', '1999-01-01', 'RETURNED', 'maker') $q$, '23514', 'I5 a cheque returned has its reason');
INSERT INTO deposits.cheque_presentment (account_no, leaf_no, amount, cheque_date, payee, presenting_bank, presented_on, status, return_reason, created_by)
VALUES ('20019999999999', 1, 10, '2026-06-20', 'x', 'y', '1999-01-01', 'RETURNED', 'NO_SUCH_ACCOUNT', 'maker');
SELECT pg_temp.check((SELECT count(*) = 2 FROM deposits.cheque_presentment), 'I6 a cheque on an account not ours is recorded returned');

-- ---- outward clearing -----------------------------------------------------------------------------------------------------
WITH x AS (INSERT INTO deposits.cheque_deposit (account_id, cheque_no, drawee_ifsc, drawer_name, amount, cheque_date, lodged_on, created_by)
    VALUES (pg_temp.a('sb'), '123456', 'HDFC0001234', 'CLAUDE-TEST Drawer', 7500, '2026-06-20', '1999-01-01', 'maker') RETURNING id) INSERT INTO acct SELECT 'dep', id FROM x;
SELECT pg_temp.check((SELECT status = 'IN_CLEARING' AND lodged_on = DATE '2026-06-25' FROM deposits.cheque_deposit WHERE id = pg_temp.a('dep')),
                     'O1 a cheque paid in waits in clearing');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.cheque_deposit (account_id, cheque_no, drawee_ifsc, drawer_name, amount, cheque_date, lodged_on, created_by)
    VALUES (pg_temp.a('sb'), '12345', 'HDFC0001234', 'x', 1, '2026-06-20', '1999-01-01', 'maker') $q$, '23514', 'O2 a cheque number has six digits');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.cheque_deposit (account_id, cheque_no, drawee_ifsc, drawer_name, amount, cheque_date, lodged_on, created_by)
    VALUES (pg_temp.a('sb'), '123456', 'HDFC1001234', 'x', 1, '2026-06-20', '1999-01-01', 'maker') $q$, '23514', 'O3 the drawee bank by its IFSC');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.cheque_deposit (account_id, cheque_no, drawee_ifsc, drawer_name, amount, cheque_date, lodged_on, status, settled_on, movement_id, created_by)
    VALUES (pg_temp.a('sb'), '123456', 'HDFC0001234', 'x', 1, '2026-06-20', '1999-01-01', 'CLEARED', '2026-06-25', NULL, 'maker') $q$, '23514',
    'O4 a cheque paid in is first in clearing');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.cheque_deposit SET status = 'CLEARED', settled_on = '2026-06-25', settled_by = 'maker',
    movement_id = pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 7500, '1207') WHERE id = pg_temp.a('dep') $q$, '23514', 'O5 a cheque cleared is a CHEQUE_DEPOSIT');
UPDATE deposits.cheque_deposit SET status = 'CLEARED', settled_on = '1999-01-01', settled_by = 'maker',
       movement_id = pg_temp.move(pg_temp.a('sb'), 'CHEQUE_DEPOSIT', 7500, '1207', '{"direction": "CREDIT"}') WHERE id = pg_temp.a('dep');
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 52500 AND (SELECT settled_on = DATE '2026-06-25' FROM deposits.cheque_deposit WHERE id = pg_temp.a('dep')),
                     'O6 it clears: the account credited');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.cheque_deposit SET status = 'RETURNED', return_reason = 'x', movement_id = NULL WHERE id = pg_temp.a('dep') $q$,
                           '42501', 'O7 a cheque paid in is settled once');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.cheque_deposit $q$, '42501', 'O8 a clearing record is never deleted');

-- ---- inoperative ------------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT last_customer_txn_on = DATE '2026-06-25' FROM deposits.casa_account WHERE account_id = pg_temp.a('sb2')),
                     'D1 a deposit by the customer is the last customer transaction');
UPDATE deposits.casa_account SET last_customer_txn_on = '2024-01-10' WHERE account_id = pg_temp.a('sb2');
SELECT pg_temp.move(pg_temp.a('sb2'), 'INTEREST_CREDIT', 12, '5108', '{"direction": "CREDIT"}');
SELECT pg_temp.check((SELECT last_customer_txn_on = DATE '2024-01-10' FROM deposits.casa_account WHERE account_id = pg_temp.a('sb2')),
                     'D2 interest credited does not keep an account operative');
UPDATE deposits.casa_account SET inoperative_since = '2026-01-10' WHERE account_id = pg_temp.a('sb2');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb2'), 'CASH_WITHDRAWAL', 100) $q$, '23514', 'D3 nothing is taken out of an inoperative account');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb2'), 'CHARGE', 100, '4107') $q$, '23514', 'D4 nor charged to it');
SELECT pg_temp.move(pg_temp.a('sb2'), 'CASH_DEPOSIT', 100);
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb2')) = 4112, 'D5 it takes credits');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.book(pg_temp.a('sb2'), 300001) $q$, '23514', 'D6 no cheque book on an inoperative account');

-- ---- unclaimed -------------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb2'), 'UNCLAIMED_TRANSFER', 4000, '2407') $q$, '23514', 'U1 an unclaimed transfer takes the whole balance');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb2'), 'UNCLAIMED_REFUND', 10, '2407', '{"direction": "CREDIT"}') $q$, '23514',
                           'U2 nothing is refunded that was not transferred');
INSERT INTO acct VALUES ('ut', pg_temp.move(pg_temp.a('sb2'), 'UNCLAIMED_TRANSFER', 4112, '2407'));
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.unclaimed (account_id, transferred_on, amount, movement_id, last_operated_on)
    VALUES (pg_temp.a('sb2'), '2026-06-25', 4000, pg_temp.a('ut'), '2016-01-10') $q$, '23514', 'U3 the unclaimed row is for the amount transferred');
WITH x AS (INSERT INTO deposits.unclaimed (account_id, transferred_on, amount, movement_id, last_operated_on)
    VALUES (pg_temp.a('sb2'), '2026-06-25', 4112, pg_temp.a('ut'), '2016-01-10') RETURNING id) INSERT INTO acct SELECT 'un', id FROM x;
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb2')) = 0, 'U4 the balance is transferred to the unclaimed deposits head');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb2'), 'CASH_DEPOSIT', 10) $q$, '23514', 'U5 nothing moves until the claimant is refunded');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.unclaimed SET amount = 1 $q$, '42501', 'U6 an unclaimed transfer never changes');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.unclaimed SET refunded_on = '2026-06-25', refund_reference = 'x',
    refund_movement_id = pg_temp.move(pg_temp.a('sb2'), 'UNCLAIMED_REFUND', 100, '2407', '{"direction": "CREDIT"}') $q$, '23514', 'U7 it is refunded whole');
UPDATE deposits.unclaimed SET refunded_on = '2026-06-25', refund_reference = 'CLAUDE-TEST claim',
       refund_movement_id = pg_temp.move(pg_temp.a('sb2'), 'UNCLAIMED_REFUND', 4112, '2407', '{"direction": "CREDIT"}');
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb2')) = 4112, 'U8 the claimant refunded in full');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.unclaimed $q$, '42501', 'U9 an unclaimed transfer is never deleted');

-- ---- net owned fund and public deposits ------------------------------------------------------------------------------------
INSERT INTO deposits.net_owned_fund (as_of, amount, reference, recorded_by) VALUES ('2026-03-31', 50000000, 'CLAUDE-TEST audited', 'maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.net_owned_fund (as_of, amount, reference, recorded_by) VALUES ('2026-03-31', 1, 'x', 'maker') $q$, '23505',
                           'N1 one net owned fund per balance sheet date');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.net_owned_fund SET amount = 1 $q$, '42501', 'N2 a net owned fund recorded never changes');
SELECT pg_temp.check(deposits.public_deposits_outstanding() = 0, 'N3 a deposit booked and not funded is not outstanding');
SELECT pg_temp.fund(pg_temp.a('fd'), 'CASH', '{"bank": "HDFC0001234"}');
SELECT pg_temp.check(deposits.public_deposits_outstanding() = 100000, 'N4 once funded it is');
\echo ALL CHEQUE, DORMANCY AND REGISTER TESTS PASSED
