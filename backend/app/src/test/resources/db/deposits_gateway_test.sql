-- Phase 4 (P4-2b, tenant V31): deposit money through the payment gateway - transfers out followed to payment or return,
-- funding a term deposit through a payment link, and the integration tables taking a deposit account instead of a loan.
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

INSERT INTO acct VALUES ('fd', pg_temp.fd(1, 'CASA', pg_temp.a('sb'))), ('fd_bank', pg_temp.fd(1));
CREATE TEMP TABLE mv (name text PRIMARY KEY, id uuid);
-- a lot that clears the in-transit head against the disbursement bank (no account leg), as DepositPostings.gatewayPaid
CREATE FUNCTION pg_temp.paid_lot(p_amount numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid();
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'DEPOSIT_GATEWAY_PAID', pg_temp.today(), pg_temp.today(), 'system');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), 'HO', '2408', '2408', 'DR', p_amount), (lot, pg_temp.today(), 'HO', '1202', '1202', 'CR', p_amount);
  RETURN lot;
END $$;
-- the reversal of a lot without movements (the paid lot of a transfer the bank sent back)
CREATE FUNCTION pg_temp.reverse_lot(p_lot uuid) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid();
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, reverses, created_by)
  VALUES (lot, 'REVERSAL', pg_temp.today(), pg_temp.today(), p_lot, 'system');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  SELECT lot, pg_temp.today(), branch_code, gl_code, account_no, CASE side WHEN 'DR' THEN 'CR' ELSE 'DR' END, amount
    FROM ledger.account_entry WHERE lot_id = p_lot;
  RETURN lot;
END $$;
INSERT INTO mv VALUES ('cash', pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 10000));

-- 1. transfers out through the gateway ------------------------------------------------------------------------
SELECT pg_temp.check((SELECT value FROM platform.system_property WHERE key = 'deposits.gl.in-transit') = '2408'
                     AND EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2408'),
                     'G1 money sent through the gateway waits on a setting head, 2408 payout in transit');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.gateway_transfer (movement_id) VALUES ((SELECT id FROM mv WHERE name = 'cash')) $q$, '23514',
                           'G2 only a bank transfer out is sent through the gateway');
INSERT INTO mv VALUES ('nameless', pg_temp.move(pg_temp.a('sb'), 'BANK_TRANSFER_OUT', 100, '1202'));
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.gateway_transfer (movement_id) VALUES ((SELECT id FROM mv WHERE name = 'nameless')) $q$, '23514',
                           'G3 a transfer sent through the gateway names the payee''s bank account');
INSERT INTO mv VALUES ('gw1', pg_temp.move(pg_temp.a('sb'), 'BANK_TRANSFER_OUT', 500, '2408', '{"party": "HDFC0001234"}'));
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.gateway_transfer (movement_id, status, paid_lot_id) VALUES ((SELECT id FROM mv WHERE name = 'gw1'), 'PAID', pg_temp.paid_lot(500)) $q$, '23514',
                           'G4 a gateway transfer starts as SENT');
INSERT INTO deposits.gateway_transfer (movement_id) VALUES ((SELECT id FROM mv WHERE name = 'gw1'));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 10000 - 100 - 500, 'G5 the money leaves the account when the transfer is sent');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.gateway_transfer SET status = 'PAID' WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw1') $q$, '23514',
                           'G6 a paid transfer has its payment posting');
UPDATE deposits.gateway_transfer SET status = 'PAID', paid_lot_id = pg_temp.paid_lot(500), utr = 'CLAUDETESTUTR1' WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw1');
SELECT pg_temp.check((SELECT sum(CASE side WHEN 'CR' THEN amount ELSE -amount END) FROM ledger.account_entry WHERE gl_code = '2408') = 0,
                     'G7 once paid, nothing is left in transit');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.gateway_transfer SET status = 'SENT', paid_lot_id = NULL, utr = NULL WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw1') $q$, '42501',
                           'G8 the payment of a paid transfer never changes');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.gateway_transfer SET status = 'RETURNED', return_lot_id = pg_temp.reverse_lot(paid_lot_id), return_reason = 'returned by the bank'
                                WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw1') $q$, '23514',
                           'G9 a transfer is returned once its movement is reversed');
-- the bank sent it back: the paid lot is reversed, then the movement, then the transfer is returned
CREATE TEMP TABLE rl AS SELECT pg_temp.reverse_lot((SELECT paid_lot_id FROM deposits.gateway_transfer WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw1'))) AS id;
SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'gw1'), 'returned by the bank: account closed');
UPDATE deposits.gateway_transfer SET status = 'RETURNED', return_lot_id = (SELECT id FROM rl), return_reason = 'returned by the bank: account closed'
 WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw1');
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 10000 - 100
                     AND (SELECT sum(CASE side WHEN 'CR' THEN amount ELSE -amount END) FROM ledger.account_entry WHERE gl_code = '2408') = 0
                     AND (SELECT sum(CASE side WHEN 'CR' THEN amount ELSE -amount END) FROM ledger.account_entry WHERE gl_code = '1202') = 100,
                     'G10 a returned transfer is back in the account, and the bank and in-transit heads are where they were');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.gateway_transfer SET utr = 'X' WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw1') $q$, '42501',
                           'G11 a returned transfer never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.gateway_transfer $q$, '42501', 'G12 a gateway transfer is never deleted');
INSERT INTO mv VALUES ('gw2', pg_temp.move(pg_temp.a('sb'), 'BANK_TRANSFER_OUT', 300, '2408', '{"party": "HDFC0001234"}'));
INSERT INTO deposits.gateway_transfer (movement_id) VALUES ((SELECT id FROM mv WHERE name = 'gw2'));
SELECT pg_temp.expect_fail($q$ UPDATE deposits.gateway_transfer SET status = 'RETURNED', return_reason = 'failed', return_lot_id = pg_temp.paid_lot(300)
                                WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw2') $q$, '23514',
                           'G13 a transfer that failed before it was paid has no payment to take back');
SELECT pg_temp.reverse((SELECT id FROM mv WHERE name = 'gw2'), 'the payout failed');
UPDATE deposits.gateway_transfer SET status = 'RETURNED', return_reason = 'the payout failed' WHERE movement_id = (SELECT id FROM mv WHERE name = 'gw2');
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 10000 - 100, 'G14 a failed transfer is back in the account');
INSERT INTO mv VALUES ('gw3', pg_temp.move(pg_temp.a('sb'), 'BANK_TRANSFER_OUT', 200, '2408', '{"party": "HDFC0001234"}'));
INSERT INTO deposits.gateway_transfer (movement_id) VALUES ((SELECT id FROM mv WHERE name = 'gw3'));

-- 2. funding a term deposit through a payment link ------------------------------------------------------------
INSERT INTO mv VALUES ('fund', pg_temp.fund(pg_temp.a('fd'), 'GATEWAY'));
SELECT pg_temp.check((SELECT funding_source FROM deposits.term_deposit WHERE account_id = pg_temp.a('fd')) = 'GATEWAY'
                     AND (SELECT status FROM deposits.account WHERE id = pg_temp.a('fd')) = 'ACTIVE'
                     AND pg_temp.balance(pg_temp.a('fd')) = 100000,
                     'F1 a term deposit is funded through a payment link (funding source GATEWAY)');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET funding_source = 'CHEQUE' WHERE account_id = pg_temp.a('fd') $q$, '23514',
                           'F2 the funding sources are ACCOUNT, CASH, BANK_TRANSFER and GATEWAY');
UPDATE deposits.term_deposit SET payout_bank_name_cipher = '\x01', payout_bank_account_cipher = '\x02', payout_bank_account_last4 = '4321',
       payout_bank_ifsc = 'ICIC0004321' WHERE account_id = pg_temp.a('fd_bank');
SELECT pg_temp.check((SELECT status FROM deposits.account WHERE id = pg_temp.a('fd_bank')) = 'BOOKED'
                     AND (SELECT payout_bank_ifsc FROM deposits.term_deposit WHERE account_id = pg_temp.a('fd_bank')) = 'ICIC0004321',
                     'F3 a booked deposit paid out to a bank account has the account recorded before a payment link funds it');

-- 3. the integration tables take a deposit account -------------------------------------------------------------
INSERT INTO integration.beneficiary (id, deposit_account_id, customer_id, holder_name_cipher, account_cipher, account_last4, account_hash, ifsc, created_by)
VALUES ('00000000-0000-0000-0000-0000000000b1', pg_temp.a('sb'), '00000000-0000-0000-0000-0000000000c1', '\x01', '\x02', '1234', '\x03', 'HDFC0001234', 'system');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.beneficiary (id, customer_id, holder_name_cipher, account_cipher, account_last4, account_hash, ifsc, created_by)
                                VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', '\x01', '\x02', '1234', '\x03', 'HDFC0001234', 'system') $q$, '23514',
                           'I1 a beneficiary belongs to a loan or a deposit account');
INSERT INTO integration.payout_instruction (id, reference, deposit_account_id, deposit_movement_id, account_no, customer_id, branch_code, attempt_no, amount, beneficiary_id)
VALUES ('00000000-0000-0000-0000-0000000000d1', 'POCLAUDE1T1', pg_temp.a('sb'), (SELECT id FROM mv WHERE name = 'gw3'), pg_temp.no(pg_temp.a('sb')),
        '00000000-0000-0000-0000-0000000000c1', 'HO', 1, 200, '00000000-0000-0000-0000-0000000000b1');
SELECT pg_temp.check((SELECT loan_id IS NULL AND status = 'INITIATED' FROM integration.payout_instruction WHERE id = '00000000-0000-0000-0000-0000000000d1'),
                     'I2 a payout pays a deposit transfer, without a loan');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.payout_instruction (id, reference, deposit_account_id, deposit_movement_id, account_no, customer_id, branch_code, attempt_no, amount)
                                VALUES (gen_random_uuid(), 'POCLAUDE1T2', pg_temp.a('sb'), (SELECT id FROM mv WHERE name = 'gw3'), pg_temp.no(pg_temp.a('sb')),
                                        '00000000-0000-0000-0000-0000000000c1', 'HO', 1, 200) $q$, '23505',
                           'I3 a transfer is paid out once');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.payout_instruction (id, reference, deposit_account_id, account_no, customer_id, branch_code, attempt_no, amount)
                                VALUES (gen_random_uuid(), 'POCLAUDE1T3', pg_temp.a('sb'), pg_temp.no(pg_temp.a('sb')), '00000000-0000-0000-0000-0000000000c1', 'HO', 1, 200) $q$, '23514',
                           'I4 a deposit payout names its transfer');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.payout_instruction (id, reference, customer_id, branch_code, attempt_no, amount)
                                VALUES (gen_random_uuid(), 'POCLAUDE1T4', '00000000-0000-0000-0000-0000000000c1', 'HO', 1, 200) $q$, '23514',
                           'I5 a payout pays a loan or a deposit account');
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET account_no = 'X' WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$, '42501',
                           'I6 the deposit account of a payout never changes');
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET deposit_settled_at = now() WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$, '23514',
                           'I7 a deposit payout is settled once the bank has paid it');
UPDATE integration.payout_instruction SET status = 'SENT', provider = 'SIMULATOR' WHERE id = '00000000-0000-0000-0000-0000000000d1';
UPDATE integration.payout_instruction SET status = 'SUCCESS', deposit_settled_at = now() WHERE id = '00000000-0000-0000-0000-0000000000d1';
SELECT pg_temp.check((SELECT deposit_settled_at IS NOT NULL FROM integration.payout_instruction WHERE id = '00000000-0000-0000-0000-0000000000d1'),
                     'I8 a paid deposit payout records when the in-transit head was cleared');

INSERT INTO integration.collection_order (id, reference, deposit_account_id, account_no, purpose, branch_code, amount, provider, expires_at, created_by)
VALUES ('00000000-0000-0000-0000-0000000000e1', 'COCLAUDE1N1', pg_temp.a('sb'), pg_temp.no(pg_temp.a('sb')), 'CREDIT', 'HO', 2500, 'SIMULATOR', now() + interval '1 day', 'maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.collection_order (id, reference, deposit_account_id, account_no, branch_code, amount, provider, expires_at, created_by)
                                VALUES (gen_random_uuid(), 'COCLAUDE1N2', pg_temp.a('sb'), pg_temp.no(pg_temp.a('sb')), 'HO', 2500, 'SIMULATOR', now() + interval '1 day', 'maker') $q$, '23514',
                           'C1 a payment link for a deposit account is a credit or a funding, not a loan repayment');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.collection_order (id, reference, purpose, branch_code, amount, provider, expires_at, created_by)
                                VALUES (gen_random_uuid(), 'COCLAUDE1N3', 'CREDIT', 'HO', 2500, 'SIMULATOR', now() + interval '1 day', 'maker') $q$, '23514',
                           'C2 a payment link is for a loan or a deposit account');
INSERT INTO integration.collection_order (id, reference, deposit_account_id, account_no, purpose, branch_code, amount, provider, expires_at, created_by)
VALUES (gen_random_uuid(), 'COCLAUDE2N1', pg_temp.a('fd_bank'), pg_temp.no(pg_temp.a('fd_bank')), 'FUNDING', 'HO', 100000, 'SIMULATOR', now() + interval '1 day', 'maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.collection_order (id, reference, deposit_account_id, account_no, purpose, branch_code, amount, provider, expires_at, created_by)
                                VALUES (gen_random_uuid(), 'COCLAUDE2N2', pg_temp.a('fd_bank'), pg_temp.no(pg_temp.a('fd_bank')), 'FUNDING', 'HO', 100000, 'SIMULATOR', now() + interval '1 day', 'maker') $q$, '23505',
                           'C3 a term deposit has one live payment link: it is funded once');
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_order SET purpose = 'FUNDING' WHERE id = '00000000-0000-0000-0000-0000000000e1' $q$, '42501',
                           'C4 the account and the purpose of a payment link never change');

INSERT INTO integration.collection_payment (id, provider, provider_payment_id, order_id, deposit_account_id, amount, paid_at, source)
VALUES ('00000000-0000-0000-0000-0000000000f1', 'SIMULATOR', 'CLAUDEPAY1', '00000000-0000-0000-0000-0000000000e1', pg_temp.a('sb'), 2500, now(), 'POLL');
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_payment SET status = 'POSTED', value_date = pg_temp.today() WHERE id = '00000000-0000-0000-0000-0000000000f1' $q$, '23514',
                           'C5 a posted payment names its posting');
INSERT INTO mv VALUES ('link', pg_temp.move(pg_temp.a('sb'), 'BANK_TRANSFER_IN', 2500, '1203'));
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_payment SET status = 'POSTED', deposit_movement_id = (SELECT id FROM mv WHERE name = 'link')
                                WHERE id = '00000000-0000-0000-0000-0000000000f1' $q$, '23514',
                           'C6 a posted payment has its value date');
UPDATE integration.collection_payment SET status = 'POSTED', deposit_movement_id = (SELECT id FROM mv WHERE name = 'link'), value_date = pg_temp.today(), posted_at = now()
 WHERE id = '00000000-0000-0000-0000-0000000000f1';
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.collection_payment (id, provider, provider_payment_id, deposit_account_id, amount, paid_at, source, status, deposit_movement_id, value_date)
                                VALUES (gen_random_uuid(), 'SIMULATOR', 'CLAUDEPAY2', pg_temp.a('sb'), 2500, now(), 'POLL', 'POSTED', (SELECT id FROM mv WHERE name = 'link'), pg_temp.today()) $q$, '23505',
                           'C7 a movement is the posting of one payment');
SELECT pg_temp.check((SELECT category = 'POSTED_NOT_SETTLED' AND account_no = pg_temp.no(pg_temp.a('sb')) AND branch_code = 'HO' AND loan_no IS NULL
                        FROM integration.collection_reconciliation WHERE provider_payment_id = 'CLAUDEPAY1'),
                     'C8 reconciliation shows the deposit account and branch of a payment');
INSERT INTO integration.gateway_settlement (provider, provider_payment_id, amount, settled_on, file_ref, imported_by)
VALUES ('SIMULATOR', 'CLAUDEPAY1', 2500, pg_temp.today(), 'CLAUDE-TEST-1', 'maker');
SELECT pg_temp.check((SELECT category FROM integration.collection_reconciliation WHERE provider_payment_id = 'CLAUDEPAY1') = 'MATCHED',
                     'C9 and matches it to the settlement');

SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM deposits.balance_problems()), 'Z1 at the end every balance agrees with the ledger');
SELECT pg_temp.check((SELECT count(*) = 0 FROM (SELECT lot_id FROM ledger.account_entry GROUP BY lot_id, branch_code
                                                  HAVING sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) <> 0) x),
                     'Z2 and every lot balances per branch');
SELECT 'TESTS PASSED' AS result;
