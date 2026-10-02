-- P2-5 (V17): role amount limits, associates and exposure, consent (DPDP), KYC documents.
-- Run on a fresh tenant database after migrations. All data is fake (CLAUDE-TEST).
\set QUIET on
CREATE FUNCTION pg_temp.expect_fail(sql text, code text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE sql;
    SET CONSTRAINTS ALL IMMEDIATE;
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

-- Fixtures ---------------------------------------------------------------------------------------------------
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-10-20','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,status) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST Borrower','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c2','90010000000021','INDIVIDUAL','CLAUDE-TEST Guarantor','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c3','90010000000039','INDIVIDUAL','CLAUDE-TEST Co-applicant','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c4','90010000000047','INDIVIDUAL','CLAUDE-TEST Blocked','HO','BLOCKED'),
  ('00000000-0000-0000-0000-0000000000c5','90010000000054','NON_INDIVIDUAL','CLAUDE-TEST Traders LLP','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c6','90010000000062','INDIVIDUAL','CLAUDE-TEST No loans','HO','ACTIVE');
INSERT INTO platform.approval_request (id,entity_type,action,payload,maker) VALUES
  ('00000000-0000-0000-0000-00000000cc01','AMOUNT_LIMIT','CREATE','{}','maker');

-- 1. Amount limits ---------------------------------------------------------------------------------------------
INSERT INTO platform.amount_limit (id,role_name,txn_type,per_txn_max,per_day_max,effective_from,effective_to,approval_id,created_by) VALUES
  ('00000000-0000-0000-0000-0000000011a1','MAKER','LOAN_DISBURSEMENT',200000,500000,'2026-04-01','2026-12-31','00000000-0000-0000-0000-00000000cc01','maker'),
  ('00000000-0000-0000-0000-0000000011a2','CHECKER','LOAN_DISBURSEMENT',1000000,NULL,'2026-04-01',NULL,'00000000-0000-0000-0000-00000000cc01','maker');
SELECT pg_temp.check((SELECT count(*) FROM platform.amount_limit) = 2, 'L1 limits per role and transaction type');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.amount_limit (role_name,txn_type,per_txn_max,effective_from,created_by)
                               VALUES ('MAKER','LOAN_DISBURSEMENT',300000,'2026-10-01','maker') $q$,
                           '23P01', 'L2 periods for one role and type cannot overlap');
INSERT INTO platform.amount_limit (role_name,txn_type,per_txn_max,effective_from,created_by)
  VALUES ('MAKER','LOAN_DISBURSEMENT',300000,'2027-01-01','maker');
SELECT pg_temp.check((SELECT count(*) FROM platform.amount_limit WHERE role_name = 'MAKER') = 2, 'L3 a later period is accepted');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.amount_limit (role_name,txn_type,per_txn_max,effective_from,created_by)
                               VALUES ('MAKER','CASH_WITHDRAWAL',1,'2026-04-01','maker') $q$, '23514', 'L4 unknown transaction type rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.amount_limit (role_name,txn_type,per_txn_max,per_day_max,effective_from,created_by)
                               VALUES ('MAKER','VOUCHER',50000,10000,'2026-04-01','maker') $q$, '23514', 'L5 day limit below the transaction limit rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.amount_limit (role_name,txn_type,per_txn_max,effective_from,created_by)
                               VALUES ('MAKER','VOUCHER',-1,'2026-04-01','maker') $q$, '23514', 'L6 negative limit rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.amount_limit (role_name,txn_type,per_txn_max,effective_from,effective_to,created_by)
                               VALUES ('MAKER','VOUCHER',1,'2026-04-01','2026-03-01','maker') $q$, '23514', 'L7 period cannot end before it starts');
SELECT pg_temp.expect_fail($q$ UPDATE platform.amount_limit SET per_txn_max = 99999999 WHERE id = '00000000-0000-0000-0000-0000000011a1' $q$,
                           '42501', 'L8 a limit cannot be edited in place');
UPDATE platform.amount_limit SET effective_to = '2026-11-30' WHERE id = '00000000-0000-0000-0000-0000000011a1';
SELECT pg_temp.check((SELECT effective_to FROM platform.amount_limit WHERE id = '00000000-0000-0000-0000-0000000011a1') = '2026-11-30',
                     'L9 a limit period can be ended');
SELECT pg_temp.expect_fail($q$ DELETE FROM platform.amount_limit $q$, '42501', 'L10 limits are never deleted');
SELECT pg_temp.check(platform.limit_used('Maker.One','LOAN_DISBURSEMENT','MAKE','2026-10-20') = 0, 'L11 nothing used at the start of the day');
INSERT INTO platform.amount_limit_usage (username,txn_type,stage,business_date,amount,reference) VALUES
  ('maker.one','LOAN_DISBURSEMENT','MAKE','2026-10-20',150000,'CLAUDE-TEST-1'),
  ('MAKER.ONE','LOAN_DISBURSEMENT','MAKE','2026-10-20',100000,'CLAUDE-TEST-2'),
  ('maker.one','LOAN_DISBURSEMENT','APPROVE','2026-10-20',70000,'CLAUDE-TEST-3'),
  ('maker.one','LOAN_DISBURSEMENT','MAKE','2026-10-19',900000,'CLAUDE-TEST-4'),
  ('maker.two','LOAN_DISBURSEMENT','MAKE','2026-10-20',5000,'CLAUDE-TEST-5');
SELECT pg_temp.check(platform.limit_used('Maker.One','LOAN_DISBURSEMENT','MAKE','2026-10-20') = 250000,
                     'L12 usage adds up per user, type, stage and business day (user name case-insensitive)');
SELECT pg_temp.check(platform.limit_used('maker.one','LOAN_DISBURSEMENT','APPROVE','2026-10-20') = 70000, 'L13 approvals are counted separately');
SELECT pg_temp.expect_fail($q$ UPDATE platform.amount_limit_usage SET amount = 0 $q$, '42501', 'L14 usage is append-only');

-- 2. Loan parties and relationships -------------------------------------------------------------------------------
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,created_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-10-20','SANCTIONED','maker');
SELECT pg_temp.check((SELECT role || '/' || added_by FROM lending.loan_party WHERE loan_id = '00000000-0000-0000-0000-00000000aa01') = 'BORROWER/maker',
                     'P1 booking a loan records its borrower as a party');
INSERT INTO lending.loan_party (loan_id,customer_id,role,added_by) VALUES
  ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c2','GUARANTOR','maker'),
  ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c3','CO_APPLICANT','maker');
SELECT pg_temp.check((SELECT count(*) FROM lending.loan_party WHERE loan_id = '00000000-0000-0000-0000-00000000aa01') = 3, 'P2 guarantor and co-applicant added');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_party (loan_id,customer_id,role,added_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c1','GUARANTOR','maker') $q$,
  '23514', 'P3 the borrower cannot guarantee their own loan');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_party (loan_id,customer_id,role,added_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c1','CO_APPLICANT','maker') $q$,
  '23514', 'P4 the borrower cannot be a co-applicant on their own loan');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_party (loan_id,customer_id,role,added_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c2','CO_APPLICANT','maker') $q$,
  '23514', 'P5 one role per customer per loan');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_party (loan_id,customer_id,role,added_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c4','GUARANTOR','maker') $q$,
  '23514', 'P6 a guarantor must be an ACTIVE customer');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_party (loan_id,customer_id,role,added_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c6','BORROWER','maker') $q$,
  '23514', 'P7 a second borrower is rejected');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.loan_party WHERE role = 'GUARANTOR' $q$, '42501', 'P8 parties cannot be removed');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_party SET role = 'BORROWER' WHERE role = 'GUARANTOR' $q$, '42501', 'P9 parties cannot be changed');

INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,created_by) VALUES
  ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c2','GUARANTOR','maker'),
  ('00000000-0000-0000-0000-0000000000c5','00000000-0000-0000-0000-0000000000c3','AUTHORISED_SIGNATORY','maker');
SELECT pg_temp.check((SELECT count(*) FROM customer.relationship WHERE status = 'ACTIVE') = 2, 'R1 customer-level guarantor and authorised signatory');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c3','AUTHORISED_SIGNATORY','maker') $q$,
  '23514', 'R2 an authorised signatory only for a non-individual');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c3','00000000-0000-0000-0000-0000000000c5','AUTHORISED_SIGNATORY','maker') $q$,
  '23514', 'R3 a signatory must be an individual');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c1','GUARANTOR','maker') $q$,
  '23514', 'R4 no relationship with oneself');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c4','CO_APPLICANT','maker') $q$,
  '23514', 'R5 the related customer must be ACTIVE');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c2','GUARANTOR','maker') $q$,
  '23505', 'R6 the same active relationship twice is rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,share_percent,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c3','GUARANTOR',50,'maker') $q$,
  '23514', 'R7 a share only for nominees');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,loan_id,share_percent,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c2','NOMINEE','00000000-0000-0000-0000-00000000aa01',60,'maker') $q$,
  '23514', 'R8 nominee shares that total 60 are rejected');
BEGIN;
INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,loan_id,share_percent,created_by) VALUES
  ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c2','NOMINEE','00000000-0000-0000-0000-00000000aa01',60,'maker'),
  ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c3','NOMINEE','00000000-0000-0000-0000-00000000aa01',40,'maker');
COMMIT;
SELECT pg_temp.check((SELECT sum(share_percent) FROM customer.relationship WHERE relation_type = 'NOMINEE' AND status = 'ACTIVE') = 100,
                     'R9 two nominees totalling 100 are accepted in one transaction');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,loan_id,share_percent,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c6','NOMINEE','00000000-0000-0000-0000-00000000aa01',10,'maker') $q$,
  '23514', 'R10 a further nominee taking the total to 110 is rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,loan_id,share_percent,created_by)
  VALUES ('00000000-0000-0000-0000-0000000000c6','00000000-0000-0000-0000-0000000000c2','NOMINEE','00000000-0000-0000-0000-00000000aa01',100,'maker') $q$,
  '23514', 'R11 a nomination must name the customer''s own account');
SELECT pg_temp.expect_fail($q$ UPDATE customer.relationship SET status = 'ENDED', ended_at = now(), ended_by = 'maker'
  WHERE relation_type = 'NOMINEE' AND share_percent = 40 $q$, '23514', 'R12 ending one nominee leaves 60 percent: rejected');
BEGIN;
UPDATE customer.relationship SET status = 'ENDED', ended_at = now(), ended_by = 'maker' WHERE relation_type = 'NOMINEE' AND status = 'ACTIVE';
INSERT INTO customer.relationship (customer_id,related_customer_id,relation_type,loan_id,share_percent,created_by) VALUES
  ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000c6','NOMINEE','00000000-0000-0000-0000-00000000aa01',100,'maker');
COMMIT;
SELECT pg_temp.check((SELECT count(*) FROM customer.relationship WHERE relation_type = 'NOMINEE' AND status = 'ENDED') = 2
                     AND (SELECT count(*) FROM customer.relationship WHERE relation_type = 'NOMINEE' AND status = 'ACTIVE') = 1,
                     'R13 the nominee set is replaced; the old nominations stay as history');
SELECT pg_temp.expect_fail($q$ UPDATE customer.relationship SET share_percent = 50 WHERE status = 'ACTIVE' AND relation_type = 'NOMINEE' $q$,
                           '42501', 'R14 a relationship can only be ended, not edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM customer.relationship $q$, '42501', 'R15 relationships are never deleted');

-- 3. Exposure and the exposure limit -------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT as_borrower FROM customer.exposure WHERE customer_id = '00000000-0000-0000-0000-0000000000c1') = 100000,
                     'E1 a sanctioned loan counts at the sanctioned amount');
SELECT pg_temp.check((SELECT as_guarantor = 100000 AND as_borrower = 0 AND loans_as_guarantor = 1 FROM customer.exposure
                       WHERE customer_id = '00000000-0000-0000-0000-0000000000c2'), 'E2 exposure as guarantor is shown separately');
SELECT pg_temp.check((SELECT as_co_applicant FROM customer.exposure WHERE customer_id = '00000000-0000-0000-0000-0000000000c3') = 100000,
                     'E3 exposure as co-applicant');
SELECT pg_temp.check((SELECT as_borrower + as_co_applicant + as_guarantor FROM customer.exposure
                       WHERE customer_id = '00000000-0000-0000-0000-0000000000c6') = 0, 'E4 a customer without loans has zero exposure');
UPDATE customer.customer SET exposure_limit = 250000 WHERE id = '00000000-0000-0000-0000-0000000000c1';
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status)
  VALUES ('00000000-0000-0000-0000-00000000aa02','10010000000025','00000000-0000-0000-0000-0000000000c1','PL01','HO',150000,18,12,'2026-10-20','SANCTIONED');
SELECT pg_temp.check((SELECT as_borrower FROM customer.exposure WHERE customer_id = '00000000-0000-0000-0000-0000000000c1') = 250000,
                     'E5 a second loan up to the limit is accepted');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status)
  VALUES (gen_random_uuid(),'10010000000033','00000000-0000-0000-0000-0000000000c1','PL01','HO',10000,18,12,'2026-10-20','SANCTIONED') $q$,
  '23514', 'E6 a loan that takes the borrower over the exposure limit is rejected');
-- disbursed and partly repaid: exposure follows the principal outstanding
UPDATE lending.loan_account SET status = 'ACTIVE', kfs_accepted_at = now(), state = '{}', disbursed_amount = 100000, principal_outstanding = 100000
 WHERE id = '00000000-0000-0000-0000-00000000aa01';
UPDATE lending.loan_account SET principal_outstanding = 60000 WHERE id = '00000000-0000-0000-0000-00000000aa01';
SELECT pg_temp.check((SELECT as_borrower FROM customer.exposure WHERE customer_id = '00000000-0000-0000-0000-0000000000c1') = 210000,
                     'E7 after disbursement the principal outstanding counts');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status)
  VALUES ('00000000-0000-0000-0000-00000000aa03','10010000000041','00000000-0000-0000-0000-0000000000c1','PL01','HO',40000,18,12,'2026-10-20','SANCTIONED');
SELECT pg_temp.check((SELECT as_borrower FROM customer.exposure WHERE customer_id = '00000000-0000-0000-0000-0000000000c1') = 250000,
                     'E8 repayment frees room under the limit');
UPDATE customer.customer SET exposure_limit = 200000 WHERE id = '00000000-0000-0000-0000-0000000000c1';
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET status = 'ACTIVE', kfs_accepted_at = now(), state = '{}', disbursed_amount = 150000
  WHERE id = '00000000-0000-0000-0000-00000000aa02' $q$, '23514', 'E9 the limit is checked again at disbursement');
UPDATE customer.customer SET exposure_limit = NULL WHERE id = '00000000-0000-0000-0000-0000000000c1';
UPDATE lending.loan_account SET status = 'ACTIVE', kfs_accepted_at = now(), state = '{}', disbursed_amount = 150000, principal_outstanding = 150000
 WHERE id = '00000000-0000-0000-0000-00000000aa02';
SELECT pg_temp.check((SELECT status FROM lending.loan_account WHERE id = '00000000-0000-0000-0000-00000000aa02') = 'ACTIVE',
                     'E10 without a limit the disbursement goes through');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET exposure_limit = -1 WHERE id = '00000000-0000-0000-0000-0000000000c1' $q$,
                           '23514', 'E11 a negative exposure limit is rejected');
-- borrower-level NPA looks only at the borrower's own accounts
UPDATE lending.loan_account SET asset_class = 'SUBSTANDARD', npa_since = '2026-10-20', dpd = 91 WHERE id = '00000000-0000-0000-0000-00000000aa01';
SELECT pg_temp.check((SELECT worst_class FROM lending.borrower_class WHERE customer_id = '00000000-0000-0000-0000-0000000000c1') = 'SUBSTANDARD'
                     AND NOT EXISTS (SELECT 1 FROM lending.borrower_class WHERE customer_id IN
                                       ('00000000-0000-0000-0000-0000000000c2','00000000-0000-0000-0000-0000000000c3')),
                     'E12 the borrower''s NPA does not reach the guarantor or co-applicant');

-- 4. Consent ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) FROM platform.enumeration WHERE enum_type = 'consent-purpose' AND active) = 5, 'C1 consent purposes seeded');
INSERT INTO customer.consent (id,customer_id,purpose,lawful_basis,notice_version,channel,evidence_ref,granted_at,expires_at,recorded_by) VALUES
  ('00000000-0000-0000-0000-00000000e001','00000000-0000-0000-0000-0000000000c1','MARKETING','CONSENT','CLAUDE-TEST-notice-v1','WEB','CLAUDE-TEST-otp-1','2026-01-01 10:00+05:30',NULL,'maker'),
  ('00000000-0000-0000-0000-00000000e002','00000000-0000-0000-0000-0000000000c1','CREDIT_BUREAU_REPORTING','CONSENT','CLAUDE-TEST-notice-v1','WEB','CLAUDE-TEST-otp-1','2026-01-01 10:00+05:30',NULL,'maker'),
  ('00000000-0000-0000-0000-00000000e003','00000000-0000-0000-0000-0000000000c1','ACCOUNT_AGGREGATOR','CONSENT','CLAUDE-TEST-notice-v1','API','CLAUDE-TEST-aa-1','2026-01-01 10:00+05:30','2026-02-01 10:00+05:30','maker'),
  ('00000000-0000-0000-0000-00000000e004','00000000-0000-0000-0000-0000000000c6','CREDIT_BUREAU_REPORTING','CONSENT','CLAUDE-TEST-notice-v1','BRANCH',NULL,'2026-01-01 10:00+05:30',NULL,'maker'),
  ('00000000-0000-0000-0000-00000000e005','00000000-0000-0000-0000-0000000000c2','CREDIT_BUREAU_REPORTING','LEGITIMATE_USE','CLAUDE-TEST-notice-v1','BRANCH',NULL,'2026-01-01 10:00+05:30',NULL,'maker');
SELECT pg_temp.check(customer.has_consent('00000000-0000-0000-0000-0000000000c1','MARKETING'), 'C2 a granted consent is in force');
SELECT pg_temp.check(customer.has_consent('00000000-0000-0000-0000-0000000000c1','marketing'), 'C3 the purpose may be written in kebab or lower case');
SELECT pg_temp.check(NOT customer.has_consent('00000000-0000-0000-0000-0000000000c1','MARKETING','2025-12-31 00:00+05:30'), 'C4 not before it was granted');
SELECT pg_temp.check(customer.has_consent('00000000-0000-0000-0000-0000000000c1','ACCOUNT_AGGREGATOR','2026-01-15 00:00+05:30')
                     AND NOT customer.has_consent('00000000-0000-0000-0000-0000000000c1','ACCOUNT_AGGREGATOR'), 'C5 a consent ends at its expiry');
SELECT pg_temp.check(NOT customer.has_consent('00000000-0000-0000-0000-0000000000c1','KYC_VERIFICATION'), 'C6 no record, no consent');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.consent (customer_id,purpose,lawful_basis,notice_version,channel,granted_at,recorded_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','SELLING_DATA','CONSENT','v1','WEB',now(),'maker') $q$, '23514', 'C7 unknown purpose rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.consent (customer_id,purpose,lawful_basis,notice_version,channel,granted_at,recorded_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','MARKETING','CONTRACT','v1','WEB',now(),'maker') $q$, '23514', 'C8 lawful basis is CONSENT or LEGITIMATE_USE');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.consent (customer_id,purpose,lawful_basis,notice_version,channel,granted_at,recorded_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','MARKETING','CONSENT',' ','WEB',now(),'maker') $q$, '23514', 'C9 the notice version shown is required');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.consent (customer_id,purpose,lawful_basis,notice_version,channel,granted_at,recorded_by)
  VALUES ('00000000-0000-0000-0000-0000000000c1','MARKETING','CONSENT','v1','WEB',now() + interval '2 days','maker') $q$, '23514', 'C10 a grant cannot be dated in the future');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.consent (customer_id,purpose,lawful_basis,notice_version,channel,granted_at,recorded_by,
    withdrawn_at,withdrawal_reason,withdrawn_by) VALUES ('00000000-0000-0000-0000-0000000000c1','MARKETING','CONSENT','v1','WEB',now(),'maker',now(),'x','maker') $q$,
  '23514', 'C11 a record starts as granted');
SELECT pg_temp.expect_fail($q$ UPDATE customer.consent SET notice_version = 'v2' WHERE id = '00000000-0000-0000-0000-00000000e001' $q$,
                           '42501', 'C12 a consent record is immutable');
SELECT pg_temp.expect_fail($q$ UPDATE customer.consent SET granted_at = now(), withdrawn_at = now(), withdrawal_reason = 'x', withdrawn_by = 'maker'
  WHERE id = '00000000-0000-0000-0000-00000000e001' $q$, '42501', 'C13 a withdrawal cannot rewrite the grant');
SELECT pg_temp.expect_fail($q$ DELETE FROM customer.consent WHERE id = '00000000-0000-0000-0000-00000000e001' $q$, '42501', 'C14 consent records are never deleted');
SELECT pg_temp.expect_fail($q$ TRUNCATE customer.consent CASCADE $q$, '42501', 'C15 nor truncated');
SELECT pg_temp.expect_fail($q$ UPDATE customer.consent SET withdrawn_at = now(), withdrawn_by = 'maker' WHERE id = '00000000-0000-0000-0000-00000000e001' $q$,
                           '23514', 'C16 a withdrawal needs a reason');
-- marketing: takes effect at once, nothing retained (the customer has live loans)
UPDATE customer.consent SET withdrawn_at = now(), withdrawal_reason = 'CLAUDE-TEST customer asked to stop offers', withdrawn_by = 'maker',
       retained_for_legal_obligation = true
 WHERE id = '00000000-0000-0000-0000-00000000e001';
SELECT pg_temp.check(NOT customer.has_consent('00000000-0000-0000-0000-0000000000c1','MARKETING'), 'C17 a marketing withdrawal takes effect immediately');
SELECT pg_temp.check((SELECT NOT retained_for_legal_obligation FROM customer.consent WHERE id = '00000000-0000-0000-0000-00000000e001'),
                     'C18 marketing is never retained, whatever the caller sends');
SELECT pg_temp.check((SELECT granted_at FROM customer.consent WHERE id = '00000000-0000-0000-0000-00000000e001') = '2026-01-01 10:00+05:30',
                     'C19 the grant is kept after withdrawal');
SELECT pg_temp.check(customer.has_consent('00000000-0000-0000-0000-0000000000c1','MARKETING','2026-06-01 00:00+05:30'),
                     'C20 history can still be asked: consent was in force in June');
SELECT pg_temp.expect_fail($q$ UPDATE customer.consent SET withdrawn_at = now(), withdrawal_reason = 'again', withdrawn_by = 'maker'
  WHERE id = '00000000-0000-0000-0000-00000000e001' $q$, '42501', 'C21 a consent is withdrawn once');
-- bureau reporting with a live loan: recorded, flagged retained
SELECT pg_temp.check(customer.has_retention_obligation('00000000-0000-0000-0000-0000000000c1')
                     AND NOT customer.has_retention_obligation('00000000-0000-0000-0000-0000000000c6'),
                     'C22 a retention obligation exists while the customer is party to a loan');
SELECT pg_temp.check(customer.has_retention_obligation('00000000-0000-0000-0000-0000000000c2'), 'C23 a guarantor of a live loan is under it too');
UPDATE customer.consent SET withdrawn_at = now(), withdrawal_reason = 'CLAUDE-TEST withdrawn by the customer', withdrawn_by = 'maker'
 WHERE id = '00000000-0000-0000-0000-00000000e002';
SELECT pg_temp.check((SELECT retained_for_legal_obligation FROM customer.consent WHERE id = '00000000-0000-0000-0000-00000000e002'),
                     'C24 withdrawing a servicing purpose on a live loan is flagged retained_for_legal_obligation');
SELECT pg_temp.check(NOT customer.has_consent('00000000-0000-0000-0000-0000000000c1','CREDIT_BUREAU_REPORTING'), 'C25 and the consent itself is no longer in force');
SELECT pg_temp.check(customer.bureau_reportable('00000000-0000-0000-0000-0000000000c1'), 'C26 bureau reporting continues under the legal obligation');
UPDATE customer.consent SET withdrawn_at = now(), withdrawal_reason = 'CLAUDE-TEST withdrawn by the customer', withdrawn_by = 'maker'
 WHERE id = '00000000-0000-0000-0000-00000000e004';
SELECT pg_temp.check((SELECT NOT retained_for_legal_obligation FROM customer.consent WHERE id = '00000000-0000-0000-0000-00000000e004')
                     AND NOT customer.bureau_reportable('00000000-0000-0000-0000-0000000000c6'),
                     'C27 without a loan the withdrawal is not retained and the customer is not reportable');
SELECT pg_temp.check(customer.bureau_reportable('00000000-0000-0000-0000-0000000000c2'), 'C28 a legitimate-use record makes a customer reportable');
SELECT pg_temp.check(NOT customer.bureau_reportable('00000000-0000-0000-0000-0000000000c3'), 'C29 no record: not reportable');
SELECT pg_temp.expect_fail($q$ UPDATE customer.consent SET withdrawn_at = now(), withdrawal_reason = 'x', withdrawn_by = 'maker'
  WHERE id = '00000000-0000-0000-0000-00000000e005' $q$, '23514', 'C30 a legitimate-use record cannot be withdrawn');
SELECT pg_temp.check((SELECT count(*) FILTER (WHERE event = 'GRANTED') = 5 AND count(*) FILTER (WHERE event = 'WITHDRAWN') = 3
                             AND count(*) FILTER (WHERE retained_for_legal_obligation) = 1 FROM customer.consent_event),
                     'C31 every grant and withdrawal is in the history');
SELECT pg_temp.expect_fail($q$ DELETE FROM customer.consent_event $q$, '42501', 'C32 the history is append-only');
INSERT INTO platform.system_property (key, value, updated_by) VALUES ('consent.servicing-purposes', 'loan-processing', 'maker');
SELECT pg_temp.check(customer.servicing_purposes() = ARRAY['LOAN_PROCESSING'], 'C33 the servicing purposes are a tenant property');
DELETE FROM platform.system_property WHERE key = 'consent.servicing-purposes';

-- 5. KYC documents -----------------------------------------------------------------------------------------------------
SELECT pg_temp.check(customer.kyc_required_documents() = ARRAY['ADDRESS_PROOF','PAN','PHOTO'], 'K1 default required set: pan, address-proof, photo');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET kyc_status = 'VERIFIED' WHERE id = '00000000-0000-0000-0000-0000000000c6' $q$,
                           '23514', 'K2 KYC cannot be set VERIFIED without the documents');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,kyc_status)
  VALUES (gen_random_uuid(),'90010000000070','INDIVIDUAL','CLAUDE-TEST Shortcut','HO','VERIFIED') $q$, '23514', 'K3 nor created as VERIFIED');
CREATE FUNCTION pg_temp.doc(n int, cust text, typ text, last4 text, hash bytea, expiry date) RETURNS uuid LANGUAGE sql AS $$
  INSERT INTO customer.kyc_document (id,customer_id,doc_type,number_last4,number_hash,expiry_date,store_key,content_type,size_bytes,sha256,uploaded_by)
  VALUES (('00000000-0000-0000-0000-0000000d00' || lpad(n::text, 2, '0'))::uuid, cust::uuid, typ, last4, hash, expiry,
          'tenants/claude-test/kyc/' || cust || '/00000000-0000-0000-0000-0000000d00' || lpad(n::text, 2, '0'),
          'application/pdf', 1024, repeat('ab', 32), 'maker')
  RETURNING id
$$;
SELECT pg_temp.doc(1, '00000000-0000-0000-0000-0000000000c6', 'PAN', '234F', sha256('CLAUDE-TEST-pan'), NULL);
SELECT pg_temp.doc(2, '00000000-0000-0000-0000-0000000000c6', 'ADDRESS_PROOF', NULL, NULL, current_date + 30);
SELECT pg_temp.doc(3, '00000000-0000-0000-0000-0000000000c6', 'PHOTO', NULL, NULL, NULL);
SELECT pg_temp.doc(4, '00000000-0000-0000-0000-0000000000c6', 'AADHAAR_MASKED', '0000', NULL, NULL);
SELECT pg_temp.check((SELECT count(*) FROM customer.kyc_document WHERE status = 'PENDING') = 4, 'K4 documents are uploaded as PENDING');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.doc(5, '00000000-0000-0000-0000-0000000000c6', 'AADHAAR_MASKED', '0000', sha256('x'), NULL) $q$,
                           '23514', 'K5 an Aadhaar number is never hashed or stored');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.doc(5, '00000000-0000-0000-0000-0000000000c6', 'PASSPORT', 'CLAUDE0000', NULL, NULL) $q$,
                           '23514', 'K6 only the last four characters of a document number fit');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.doc(5, '00000000-0000-0000-0000-0000000000c6', 'RATION_CARD', NULL, NULL, NULL) $q$,
                           '23514', 'K7 unknown document type rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.kyc_document (id,customer_id,doc_type,store_key,content_type,size_bytes,sha256,uploaded_by)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000c6','PHOTO','tenants/claude-test/kyc/' || gen_random_uuid() || '/' || gen_random_uuid(),
          'image/gif',10,repeat('ab',32),'maker') $q$, '23514', 'K8 only PDF, JPEG and PNG');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.kyc_document (id,customer_id,doc_type,store_key,content_type,size_bytes,sha256,uploaded_by)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000c6','PHOTO','tenants/claude-test/kyc/' || gen_random_uuid() || '/' || gen_random_uuid(),
          'image/png',5242881,repeat('ab',32),'maker') $q$, '23514', 'K9 files above 5 MB rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.kyc_document (id,customer_id,doc_type,store_key,content_type,size_bytes,sha256,uploaded_by)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000c6','PHOTO','../etc/passwd','image/png',10,repeat('ab',32),'maker') $q$,
  '23514', 'K10 the store key must follow tenants/<code>/kyc/<customer>/<uuid>');
SELECT pg_temp.expect_fail($q$ UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'MAKER', verified_at = now()
  WHERE id = '00000000-0000-0000-0000-0000000d0001' $q$, '23514', 'K11 the uploader cannot verify their own upload');
SELECT pg_temp.expect_fail($q$ UPDATE customer.kyc_document SET status = 'REJECTED', verified_by = 'checker', verified_at = now()
  WHERE id = '00000000-0000-0000-0000-0000000d0004' $q$, '23514', 'K12 a rejection needs a reason');
SELECT pg_temp.expect_fail($q$ UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now()
  WHERE id = '00000000-0000-0000-0000-0000000d0004' $q$, '23514', 'K13 Aadhaar is verified only when the checker confirms it is masked');
SELECT pg_temp.expect_fail($q$ UPDATE customer.kyc_document SET sha256 = repeat('cd', 32) WHERE id = '00000000-0000-0000-0000-0000000d0001' $q$,
                           '42501', 'K14 document metadata is immutable');
UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now() WHERE id = '00000000-0000-0000-0000-0000000d0001';
UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now() WHERE id = '00000000-0000-0000-0000-0000000d0002';
SELECT pg_temp.check((SELECT kyc_status FROM customer.customer WHERE id = '00000000-0000-0000-0000-0000000000c6') = 'PENDING'
                     AND NOT customer.kyc_complete('00000000-0000-0000-0000-0000000000c6'), 'K15 two of three required documents: still PENDING');
UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now() WHERE id = '00000000-0000-0000-0000-0000000d0003';
SELECT pg_temp.check((SELECT kyc_status FROM customer.customer WHERE id = '00000000-0000-0000-0000-0000000000c6') = 'VERIFIED',
                     'K16 when the last required document is verified the customer becomes VERIFIED');
SELECT pg_temp.expect_fail($q$ UPDATE customer.kyc_document SET status = 'REJECTED', status_reason = 'changed my mind', verified_by = 'checker2', verified_at = now()
  WHERE id = '00000000-0000-0000-0000-0000000d0003' $q$, '42501', 'K17 a decided document cannot be decided again');
UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now(), masking_confirmed = true
 WHERE id = '00000000-0000-0000-0000-0000000d0004';
SELECT pg_temp.check((SELECT status FROM customer.kyc_document WHERE id = '00000000-0000-0000-0000-0000000d0004') = 'VERIFIED',
                     'K18 masked Aadhaar verified with the masking confirmed');
SELECT pg_temp.check(NOT customer.kyc_complete('00000000-0000-0000-0000-0000000000c6', current_date + 31), 'K19 not complete after the address proof expires');
SELECT pg_temp.check(customer.expire_kyc(current_date + 31) = 1, 'K20 the daily job finds the customer whose document ran out');
SELECT pg_temp.check((SELECT kyc_status FROM customer.customer WHERE id = '00000000-0000-0000-0000-0000000000c6') = 'EXPIRED',
                     'K20b and moves the customer to EXPIRED');
SELECT pg_temp.check(customer.refresh_kyc_status('00000000-0000-0000-0000-0000000000c6') = 'VERIFIED',
                     'K21 re-evaluated today the customer is VERIFIED again');
INSERT INTO platform.system_property (key, value, updated_by) VALUES ('kyc.required-documents', 'pan, photo, passport', 'maker');
SELECT pg_temp.check(customer.kyc_required_documents() = ARRAY['PAN','PASSPORT','PHOTO'], 'K22 the required set is a tenant property');
SELECT pg_temp.check(customer.refresh_kyc_status('00000000-0000-0000-0000-0000000000c6') = 'PENDING',
                     'K23 a wider required set sends the customer back to PENDING');
SELECT pg_temp.doc(6, '00000000-0000-0000-0000-0000000000c6', 'PASSPORT', '0000', sha256('CLAUDE-TEST-passport'), current_date - 1);
SELECT pg_temp.expect_fail($q$ UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now()
  WHERE id = '00000000-0000-0000-0000-0000000d0006' $q$, '23514', 'K24 an expired document cannot be verified');
UPDATE customer.kyc_document SET status = 'REJECTED', status_reason = 'CLAUDE-TEST expired passport', verified_by = 'checker', verified_at = now()
 WHERE id = '00000000-0000-0000-0000-0000000d0006';
SELECT pg_temp.check((SELECT kyc_status FROM customer.customer WHERE id = '00000000-0000-0000-0000-0000000000c6') = 'PENDING',
                     'K25 a rejected document does not count');
SELECT pg_temp.expect_fail($q$ DELETE FROM customer.kyc_document $q$, '42501', 'K26 documents are not deleted');
\echo ALL P2-5 CUSTOMER TESTS PASSED
