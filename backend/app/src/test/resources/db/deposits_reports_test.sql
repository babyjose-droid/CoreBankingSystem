-- Phase 4 (P4-7, tenant V38): the deposit reports (book, maturity profile, interest paid, TDS), their helpers and branch scope.
-- Run on a fresh tenant database after the tenant migrations.
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
INSERT INTO platform.branch (code, name, state_code, parent_code) VALUES ('MUM', 'Mumbai', '27', 'HO');
INSERT INTO platform.business_day VALUES (1, '2026-06-25', 'OPEN');
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Bank Ltd', 'BANK', '32');
SELECT ledger.load_starter_kit('BANK');
SELECT ledger.load_lending_heads();
SELECT ledger.load_deposit_heads();
INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches, status) VALUES
  ('sub-all', 'all', 'CLAUDE-TEST All', 'HO', true, 'ACTIVE'), ('sub-mum', 'mum', 'CLAUDE-TEST Mumbai', 'MUM', false, 'ACTIVE');
SELECT pg_temp.customer(1, 'Asha Depositor');
SELECT pg_temp.customer(2, 'Biju Other');
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
CREATE TEMP TABLE acct (name text PRIMARY KEY, id uuid);
CREATE FUNCTION pg_temp.a(p text) RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT id FROM acct WHERE name = p $$;
CREATE FUNCTION pg_temp.day(d date) RETURNS void LANGUAGE sql AS $$
  SELECT set_config('corebanking.eod', 'on', true);
  UPDATE platform.business_day SET business_date = d WHERE id = 1;
  SELECT set_config('corebanking.eod', 'off', true);
$$;
INSERT INTO acct VALUES ('sb', pg_temp.casa('SB01', 1)), ('mum', pg_temp.casa('SB01', 2, 'MUM')), ('fd', pg_temp.fd(1));
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 50000);
SELECT pg_temp.move(pg_temp.a('mum'), 'CASH_DEPOSIT', 7000);
SELECT pg_temp.fund(pg_temp.a('fd'), 'CASH', '{"bank": "HDFC0001234"}');
SELECT pg_temp.day('2026-07-10');
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_WITHDRAWAL', 1500.25);
-- interest credited (one of it reversed), interest recovered, interest paid out, tax deducted on a credit
INSERT INTO acct VALUES ('i1', pg_temp.move(pg_temp.a('sb'), 'INTEREST_CREDIT', 120.50, '2404', '{"direction": "CREDIT"}'));
INSERT INTO acct VALUES ('i2', pg_temp.move(pg_temp.a('sb'), 'INTEREST_CREDIT', 99, '2404', '{"direction": "CREDIT"}'));
SELECT pg_temp.reverse(pg_temp.a('i2'));
INSERT INTO acct VALUES ('fi', pg_temp.move(pg_temp.a('fd'), 'INTEREST_CREDIT', 1800, '2404', '{"direction": "CREDIT"}'));
INSERT INTO acct VALUES ('ft', pg_temp.move(pg_temp.a('fd'), 'TAX_DEDUCTED', 180, '2205'));
UPDATE customer.customer SET pan_hash = decode(repeat('02', 32), 'hex'), pan_last4 = '0002' WHERE id = '00000000-0000-0000-0000-0000000000c2';
INSERT INTO deposits.tds_declaration (id, customer_id, tax_year, reference, received_on, recorded_by)
VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000c2', 2026, 'CLAUDE-TEST ACK 9', '2026-04-05', 'maker');
INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, rate_percent, basis, senior, pan_on_file,
                                 interest_movement_id, tax_movement_id, lot_id, created_by)
SELECT '00000000-0000-0000-0000-0000000000c1', 2026, pg_temp.a('fd'), '2026-07-10', 1800, 180, 10, 'RATE', false, true, pg_temp.a('fi'), pg_temp.a('ft'),
       (SELECT lot_id FROM deposits.movement WHERE id = pg_temp.a('ft')), 'system';
INSERT INTO acct VALUES ('mi', pg_temp.move(pg_temp.a('mum'), 'INTEREST_CREDIT', 40, '2404', '{"direction": "CREDIT"}'));
INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, basis, senior, pan_on_file, declaration_id,
                                 interest_movement_id, created_by)
VALUES ('00000000-0000-0000-0000-0000000000c2', 2026, pg_temp.a('mum'), '2026-07-10', 40, 0, 'DECLARATION', false, true,
        '00000000-0000-0000-0000-0000000000d1', pg_temp.a('mi'), 'system');

-- ---- catalogue ----------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 4 FROM reporting.report_definition WHERE code LIKE 'DEPOSIT_%' AND permission = 'deposit:view'
                       AND output_format = 'CSV' AND NOT contains_pii), 'K1 four deposit reports in the catalogue, needing deposit:view, no personal data');
SELECT pg_temp.check((SELECT count(*) = 4 FROM platform.job_definition WHERE code LIKE 'REPORT_DEPOSIT_%' AND kind = 'REPORT' AND NOT enabled),
                     'K2 each can be scheduled: its job, off until scheduled');

-- ---- helpers ------------------------------------------------------------------------------------------------------------
SELECT pg_temp.check(deposits.balance_as_at(pg_temp.a('sb'), '2026-06-24') = 0 AND deposits.balance_as_at(pg_temp.a('sb'), '2026-06-25') = 50000
                     AND deposits.balance_as_at(pg_temp.a('sb'), '2026-07-10') = 48620.25, 'H1 a balance at the end of any day');
SELECT pg_temp.check(deposits.balance_as_at(pg_temp.a('sb'), '2026-07-10') = pg_temp.balance(pg_temp.a('sb')), 'H2 today''s agrees with the balance kept');
SELECT pg_temp.check(reporting.maturity_bucket('2026-07-10', '2026-07-10') = 'Matured, not paid'
                     AND reporting.maturity_bucket('2026-07-10', '2026-07-11') = '1 day'
                     AND reporting.maturity_bucket('2026-07-10', '2026-07-17') = '2 to 7 days'
                     AND reporting.maturity_bucket('2026-07-10', '2026-07-18') = '8 to 14 days'
                     AND reporting.maturity_bucket('2026-07-10', '2026-08-09') = '15 to 30 days'
                     AND reporting.maturity_bucket('2026-07-10', '2026-08-10') = '31 days to 2 months'
                     AND reporting.maturity_bucket('2026-07-10', '2026-09-10') = '31 days to 2 months'
                     AND reporting.maturity_bucket('2026-07-10', '2026-09-11') = 'Over 2 to 3 months'
                     AND reporting.maturity_bucket('2026-07-10', '2027-07-10') = 'Over 6 months to 1 year'
                     AND reporting.maturity_bucket('2026-07-10', '2027-07-11') = 'Over 1 to 3 years'
                     AND reporting.maturity_bucket('2026-07-10', '2031-07-11') = 'Over 5 years', 'H3 the residual maturity buckets, edges included');
SELECT pg_temp.check((SELECT count(*) = 12 FROM reporting.maturity_buckets()), 'H4 twelve buckets');

-- ---- deposit book ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 3 FROM reporting.rpt_deposit_book('all', '{}')), 'D1 the book: savings at two branches and the deposit');
SELECT pg_temp.check((SELECT balance = 48620.25 AND accounts = 1 FROM reporting.rpt_deposit_book('all', '{}') WHERE branch_code = 'HO' AND family = 'CASA'),
                     'D2 the savings balance less the reversed interest');
SELECT pg_temp.check((SELECT balance = 101620 FROM reporting.rpt_deposit_book('all', '{}') WHERE family = 'TERM_DEPOSIT'), 'D3 the deposit with interest less tax');
SELECT pg_temp.check((SELECT count(*) = 1 AND min(branch_code) = 'MUM' FROM reporting.rpt_deposit_book('mum', '{}')), 'D4 a Mumbai user sees Mumbai only');
SELECT pg_temp.check((SELECT sum(balance) = 157000 FROM reporting.rpt_deposit_book('all', '{"asOf": "2026-06-25"}')), 'D5 as at an earlier day-end');
SELECT pg_temp.check((SELECT as_at = '2026-07-10' FROM reporting.rpt_deposit_book('all', '{"asOf": "2027-01-01"}') LIMIT 1), 'D6 not past the business date');

-- ---- maturity profile -----------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 12 FROM reporting.rpt_deposit_maturity_profile('all', '{}')), 'P1 every bucket for the branch with deposits');
SELECT pg_temp.check((SELECT deposits = 1 AND principal = 100000 AND balance = 101620 FROM reporting.rpt_deposit_maturity_profile('all', '{}')
                       WHERE bucket = 'Over 6 months to 1 year'), 'P2 the deposit in its bucket at its balance');
SELECT pg_temp.check((SELECT count(*) = 0 FROM reporting.rpt_deposit_maturity_profile('mum', '{}')), 'P3 no deposit in Mumbai');
SELECT pg_temp.check((SELECT count(*) = 0 FROM reporting.rpt_deposit_maturity_profile('all', '{"asOf": "2026-06-24"}')), 'P4 none before it started');

-- ---- interest paid ----------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT interest_credited = 120.50 AND net_interest = 120.50 AND tax_deducted = 0
                        FROM reporting.rpt_deposit_interest_paid('all', '{"from": "2026-07-01", "to": "2026-07-31"}') WHERE account_no = pg_temp.no(pg_temp.a('sb'))),
                     'I1 savings interest credited, the reversed credit left out');
SELECT pg_temp.check((SELECT interest_credited = 1800 AND tax_deducted = 180
                        FROM reporting.rpt_deposit_interest_paid('all', '{"from": "2026-07-01", "to": "2026-07-31"}') WHERE account_no = pg_temp.no(pg_temp.a('fd'))),
                     'I2 the deposit''s interest with the tax deducted');
SELECT pg_temp.check((SELECT count(*) = 0 FROM reporting.rpt_deposit_interest_paid('all', '{"from": "2026-06-01", "to": "2026-06-30"}')), 'I3 none in June');
SELECT pg_temp.check((SELECT count(*) = 1 FROM reporting.rpt_deposit_interest_paid('mum', '{"from": "2026-07-01", "to": "2026-07-31"}')), 'I4 branch scope');

-- ---- TDS ------------------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 2 FROM reporting.rpt_deposit_tds('all', '{"from": "2026-07-01", "to": "2026-09-30"}')), 'T1 every credit of the quarter');
SELECT pg_temp.check((SELECT tax = 180 AND rate_percent = 10 AND basis = 'RATE' AND tax_year = '2026-27' AND quarter = 2 AND pan_on_file
                        FROM reporting.rpt_deposit_tds('all', '{"from": "2026-07-01", "to": "2026-09-30"}') WHERE account_no = pg_temp.no(pg_temp.a('fd'))),
                     'T2 the tax, the rate, its basis, the tax year and quarter');
SELECT pg_temp.check((SELECT basis = 'DECLARATION' AND tax = 0 AND declaration_reference = 'CLAUDE-TEST ACK 9'
                        FROM reporting.rpt_deposit_tds('all', '{"from": "2026-07-01", "to": "2026-09-30"}') WHERE branch_code = 'MUM'),
                     'T3 a credit not taxed under a declaration, with its reference');
SELECT pg_temp.check((SELECT count(*) = 1 FROM reporting.rpt_deposit_tds('mum', '{"from": "2026-07-01", "to": "2026-09-30"}')), 'T4 branch scope');
\echo ALL DEPOSIT REPORT TESTS PASSED
