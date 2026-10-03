-- P2-7 platform completion (V19): custom fields, support access, posting during end of day, job catalogue,
-- report retention, dashboard snapshot, consent expiry, usage figures. Run on a fresh tenant database.
\set QUIET on
CREATE FUNCTION pg_temp.expect_fail(sql text, code text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE sql;
    SET CONSTRAINTS ALL IMMEDIATE;      -- custom values are checked by a deferred trigger: make it speak now
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

-- ---------------------------------------------------------------------------------------------------------
-- Fixture: two branches, business date Friday 09-Oct-2026 (Sunday is a weekly off), one product, two customers,
-- one active loan and one sanctioned loan.
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE'), ('MUM','Mumbai',NULL,'27','HO',false,'ACTIVE');
INSERT INTO platform.weekly_off (branch_code, day_of_week, week_of_month) VALUES (NULL, 7, NULL);
INSERT INTO platform.business_day VALUES (1,'2026-10-09','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches, status) VALUES
  ('sub-admin','tenant.admin','CLAUDE-TEST Admin','HO',true,'ACTIVE'),
  ('sub-mum','mum.user','CLAUDE-TEST Mumbai','MUM',false,'ACTIVE'),
  ('sub-eng','eng.one','CLAUDE-TEST Same name as the engineer','MUM',false,'ACTIVE');
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST One','HO'),
  ('00000000-0000-0000-0000-0000000000c2','90010000000021','INDIVIDUAL','CLAUDE-TEST Two','MUM');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,kfs_accepted_at,state)
VALUES ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-06-30','ACTIVE',now(),'{}'),
       ('00000000-0000-0000-0000-00000000aa02','10010000000025','00000000-0000-0000-0000-0000000000c2','PL01','MUM',50000,18,12,'2026-10-09','SANCTIONED',NULL,'{}');
INSERT INTO platform.enumeration (enum_type, code, label) VALUES
  ('sourcing-channel','DSA','Direct selling agent'), ('sourcing-channel','BRANCH','Branch walk-in'), ('sourcing-channel','OLD','Old channel');
UPDATE platform.enumeration SET active = false WHERE enum_type = 'sourcing-channel' AND code = 'OLD';

-- =========================================================================================================
-- Custom fields
-- =========================================================================================================
INSERT INTO platform.custom_field (entity, key, label, data_type, enum_type, required, regex, min_value, max_value, pii, updated_by) VALUES
  ('CUSTOMER','employeeCode','Employee code','TEXT',NULL,false,'[A-Z]{2}[0-9]{4}',NULL,NULL,false,'checker1'),
  ('CUSTOMER','nickName','Nick name','TEXT',NULL,false,NULL,2,10,false,'checker1'),
  ('CUSTOMER','familySize','Family size','NUMBER',NULL,false,NULL,1,20,false,'checker1'),
  ('CUSTOMER','anniversary','Anniversary','DATE',NULL,false,NULL,NULL,NULL,false,'checker1'),
  ('CUSTOMER','staffRelative','Relative of staff','BOOLEAN',NULL,false,NULL,NULL,NULL,false,'checker1'),
  ('CUSTOMER','sourcingChannel','Sourcing channel','ENUM','sourcing-channel',false,NULL,NULL,NULL,false,'checker1'),
  ('CUSTOMER','passportNo','Passport number','TEXT',NULL,false,'[A-Z][0-9]{7}',NULL,NULL,true,'checker1'),
  ('LOAN_ACCOUNT','dealerCode','Dealer code','TEXT',NULL,false,'[A-Z0-9]{3,8}',NULL,NULL,false,'checker1'),
  ('LOAN_PRODUCT','campaign','Campaign','TEXT',NULL,false,NULL,NULL,40,false,'checker1');
SELECT pg_temp.check((SELECT count(*) FROM platform.custom_field) = 9, 'C1 definitions for customer, loan account and loan product');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,updated_by) VALUES ('BRANCH','x1','X','TEXT','m') $q$,
                           '23514', 'C2 only customer, loan account and loan product carry custom fields');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,updated_by) VALUES ('CUSTOMER','x1','X','JSON','m') $q$,
                           '23514', 'C3 unknown data type rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,updated_by) VALUES ('CUSTOMER','Bad Key','X','TEXT','m') $q$,
                           '23514', 'C4 key format enforced');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,updated_by) VALUES ('CUSTOMER','x1','X','ENUM','m') $q$,
                           '23514', 'C5 an ENUM field needs its enumeration type');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,enum_type,updated_by) VALUES ('CUSTOMER','x1','X','ENUM','no-such-type','m') $q$,
                           '23514', 'C6 the enumeration type must have values');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,regex,updated_by) VALUES ('CUSTOMER','x1','X','TEXT','[A-Z','m') $q$,
                           '23514', 'C7 an invalid pattern is rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,regex,updated_by) VALUES ('CUSTOMER','x1','X','NUMBER','[0-9]+','m') $q$,
                           '23514', 'C8 a pattern is for text only');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,pii,updated_by) VALUES ('CUSTOMER','x1','X','NUMBER',true,'m') $q$,
                           '23514', 'C9 only text can be flagged as personal data');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.custom_field (entity,key,label,data_type,min_value,max_value,updated_by) VALUES ('CUSTOMER','x1','X','NUMBER',5,1,'m') $q$,
                           '23514', 'C10 minimum cannot exceed maximum');
SELECT pg_temp.expect_fail($q$ UPDATE platform.custom_field SET data_type = 'NUMBER', regex = NULL WHERE entity = 'CUSTOMER' AND key = 'employeeCode' $q$,
                           '23514', 'C11 the type of a field cannot change');
SELECT pg_temp.expect_fail($q$ UPDATE platform.custom_field SET pii = false WHERE entity = 'CUSTOMER' AND key = 'passportNo' $q$,
                           '23514', 'C12 the personal-data flag cannot change');
SELECT pg_temp.expect_fail($q$ DELETE FROM platform.custom_field WHERE key = 'nickName' $q$, '42501', 'C13 a definition is deactivated, never deleted');

-- values
UPDATE customer.customer SET custom = '{"employeeCode":"AB1234","nickName":"Appu","familySize":4,"anniversary":"2020-02-29",
   "staffRelative":false,"sourcingChannel":"DSA","passportNo":{"enc":"AQAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","mask":"****4567"}}'
 WHERE customer_no = '90010000000013';
SELECT pg_temp.check((SELECT custom ->> 'nickName' FROM customer.customer WHERE customer_no = '90010000000013') = 'Appu',
                     'V1 a full set of valid values is accepted');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"shoeSize":9}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V2 unknown key rejected');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"familySize":"four"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V3 a number field refuses text');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"nickName":7}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V4 a text field refuses a number');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"employeeCode":"ab1234"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V5 the pattern must match');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"employeeCode":"AB1234-and-more"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V6 the whole value must match the pattern, not a part of it');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"nickName":"A"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V7 text shorter than the minimum length rejected');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"nickName":"Abcdefghijk"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V8 text longer than the maximum length rejected');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"familySize":21}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V9 number above the maximum rejected');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"familySize":0}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V10 number below the minimum rejected');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"anniversary":"2026-02-30"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V11 an impossible date rejected');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"anniversary":"30-02-2026"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V12 a date must be YYYY-MM-DD');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"staffRelative":"yes"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V13 a boolean field refuses text');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"sourcingChannel":"ONLINE"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V14 an enumeration value that does not exist rejected');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"sourcingChannel":"OLD"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V15 an inactive enumeration value rejected');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"nickName":null}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V16 null is not a value');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"passportNo":"K1234567"}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V17 personal data in clear is refused');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"passportNo":{"enc":"short","mask":"****"}}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V18 a personal-data value must look like ciphertext');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"passportNo":{"enc":"AQAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","mask":"****","clear":"K1234567"}}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V19 a personal-data value holds ciphertext and mask only');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = '{"nickName":{"enc":"AQAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","mask":"**"}}' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V20 an ordinary field cannot hide behind the encrypted shape');
SELECT pg_temp.check((SELECT custom FROM customer.customer WHERE customer_no = '90010000000021') = '{}'::jsonb, 'V21 refused values leave the record unchanged');
SELECT pg_temp.expect_fail($q$ ALTER TABLE customer.customer ALTER COLUMN custom DROP DEFAULT; UPDATE customer.customer SET custom = '[1]' WHERE customer_no = '90010000000021' $q$,
                           '23514', 'V22 custom values are an object');

-- other entities
UPDATE lending.loan_account SET custom = '{"dealerCode":"DLR001"}' WHERE loan_no = '10010000000017';
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET custom = '{"dealerCode":"x"}' WHERE loan_no = '10010000000017' $q$,
                           '23514', 'V23 loan account values are checked');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET custom = '{"nickName":"Appu"}' WHERE loan_no = '10010000000017' $q$,
                           '23514', 'V24 a customer field is unknown on a loan');
UPDATE lending.loan_product SET custom = '{"campaign":"Onam 2026"}' WHERE code = 'PL01';
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET custom = '{"campaign":7}' WHERE code = 'PL01' $q$,
                           '23514', 'V25 loan product values are checked');
SELECT pg_temp.check((SELECT custom ->> 'dealerCode' FROM lending.loan_account WHERE loan_no = '10010000000017') = 'DLR001'
                     AND (SELECT custom ->> 'campaign' FROM lending.loan_product WHERE code = 'PL01') = 'Onam 2026',
                     'V26 values are stored on the loan account and the loan product');
-- other updates of a record never re-judge its custom values
UPDATE lending.loan_account SET dpd = 3 WHERE loan_no = '10010000000017';
SELECT pg_temp.check((SELECT dpd FROM lending.loan_account WHERE loan_no = '10010000000017') = 3, 'V27 day-end updates are not held up by custom fields');

-- required fields: checked at commit, against the row as it is then
INSERT INTO platform.custom_field (entity, key, label, data_type, required, updated_by) VALUES ('LOAN_ACCOUNT','purpose','Purpose of loan','TEXT',true,'checker1');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,state)
  VALUES ('00000000-0000-0000-0000-00000000aa03','10010000000033','00000000-0000-0000-0000-0000000000c2','PL01','MUM',20000,18,12,'2026-10-09','SANCTIONED','{}') $q$,
  '23514', 'Q1 a new loan without a required field is refused');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET custom = '{"dealerCode":"DLR002"}' WHERE loan_no = '10010000000017' $q$,
                           '23514', 'Q2 changing custom values of an older loan must now supply the required field');
BEGIN;
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,state)
VALUES ('00000000-0000-0000-0000-00000000aa03','10010000000033','00000000-0000-0000-0000-0000000000c2','PL01','MUM',20000,18,12,'2026-10-09','SANCTIONED','{}');
UPDATE lending.loan_account SET custom = '{"purpose":"Two-wheeler","dealerCode":"DLR002"}' WHERE loan_no = '10010000000033';
COMMIT;
SELECT pg_temp.check((SELECT custom ->> 'purpose' FROM lending.loan_account WHERE loan_no = '10010000000033') = 'Two-wheeler',
                     'Q3 insert first, custom values later in the same transaction: accepted at commit');
UPDATE lending.loan_account SET status = 'CANCELLED' WHERE loan_no = '10010000000033';
SELECT pg_temp.check(true, 'Q4 loans booked before the field became required still take ordinary updates');

-- deactivated and tightened definitions
UPDATE platform.custom_field SET active = false WHERE entity = 'CUSTOMER' AND key = 'nickName';
UPDATE platform.custom_field SET max_value = 3 WHERE entity = 'CUSTOMER' AND key = 'familySize';
UPDATE customer.customer SET custom = custom || '{"staffRelative":true}' WHERE customer_no = '90010000000013';
SELECT pg_temp.check((SELECT (custom ->> 'staffRelative')::boolean AND custom ? 'nickName' AND (custom ->> 'familySize') = '4'
                        FROM customer.customer WHERE customer_no = '90010000000013'),
                     'D1 values that did not change are not judged again after a field is deactivated or tightened');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = custom || '{"nickName":"Kuttan"}' WHERE customer_no = '90010000000013' $q$,
                           '23514', 'D2 a deactivated field takes no new value');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET custom = custom || '{"familySize":5}' WHERE customer_no = '90010000000013' $q$,
                           '23514', 'D3 a changed value is judged by the rule in force');
SELECT pg_temp.expect_fail($q$ SELECT platform.validate_custom('CUSTOMER', '{"nickName":"Appu"}') $q$, '23514',
                           'D4 a new record cannot use a deactivated field');

-- =========================================================================================================
-- Support access
-- =========================================================================================================
INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes)
VALUES ('00000000-0000-0000-0000-0000000005a1','kc-sub-eng-1','eng.one','CLAUDE-TEST EOD stuck on step 3','SUP-1001',120);
SELECT pg_temp.check((SELECT status = 'REQUESTED' AND scope = 'READ_ONLY' AND expires_at IS NULL FROM platform.support_access
                       WHERE id = '00000000-0000-0000-0000-0000000005a1'), 'S1 a request starts REQUESTED, read-only, with no expiry');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes, status, decided_by, decided_at, expires_at)
  VALUES (gen_random_uuid(),'kc-sub-eng-1','eng.one','CLAUDE-TEST self approval attempt','SUP-1002',60,'APPROVED','eng.one',now(),now() + interval '1 hour') $q$,
  '23514', 'S2 a grant cannot be created already approved');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes)
  VALUES (gen_random_uuid(),'kc-sub-eng-1','eng.one','CLAUDE-TEST too long a window','SUP-1003',481) $q$, '23514', 'S3 more than 8 hours rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes)
  VALUES (gen_random_uuid(),'kc-sub-eng-1','eng.one','CLAUDE-TEST too short a window','SUP-1004',5) $q$, '23514', 'S4 less than 15 minutes rejected');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes, scope)
  VALUES (gen_random_uuid(),'kc-sub-eng-1','eng.one','CLAUDE-TEST wants to write','SUP-1005',60,'READ_WRITE') $q$, '23514', 'S5 only read-only access exists');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes)
  VALUES (gen_random_uuid(),'kc-sub-eng-1','eng.one','look','SUP-1006',60) $q$, '23514', 'S6 a reason is required');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes)
  VALUES (gen_random_uuid(),'kc-sub-eng-1','eng.one','CLAUDE-TEST no ticket given','',60) $q$, '23514', 'S7 a ticket is required');
SELECT pg_temp.check(NOT platform.support_access_active('00000000-0000-0000-0000-0000000005a1','kc-sub-eng-1'), 'S8 a request gives no access');
SELECT pg_temp.check((SELECT count(*) FROM platform.visible_branches('support:eng.one')) = 0 AND NOT platform.sees_all_branches('support:eng.one'),
                     'S9 a request gives no branch scope');
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET status = 'APPROVED', decided_by = 'support:eng.one' WHERE id = '00000000-0000-0000-0000-0000000005a1' $q$,
                           '23514', 'S10 an engineer cannot approve');
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET status = 'APPROVED' WHERE id = '00000000-0000-0000-0000-0000000005a1' $q$,
                           '23514', 'S11 an approval names who approved');
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET duration_minutes = 480 WHERE id = '00000000-0000-0000-0000-0000000005a1' $q$,
                           '42501', 'S12 a request cannot be edited');
-- the approver tries to hand out a week; the database sets the expiry from the requested duration
UPDATE platform.support_access SET status = 'APPROVED', decided_by = 'tenant.admin', expires_at = now() + interval '7 days'
 WHERE id = '00000000-0000-0000-0000-0000000005a1';
SELECT pg_temp.check((SELECT expires_at = decided_at + interval '120 minutes' AND decided_at > now() - interval '1 minute' AND decided_by = 'tenant.admin' FROM platform.support_access
                       WHERE id = '00000000-0000-0000-0000-0000000005a1'), 'S13 the expiry is the approval time plus the requested duration, whatever the caller sends');
SELECT pg_temp.check(platform.support_access_active('00000000-0000-0000-0000-0000000005a1','kc-sub-eng-1'), 'S14 an approved grant is in force for its engineer');
SELECT pg_temp.check(NOT platform.support_access_active('00000000-0000-0000-0000-0000000005a1','kc-sub-eng-2'), 'S15 another engineer cannot use the grant');
SELECT pg_temp.check(platform.support_access_active('00000000-0000-0000-0000-0000000005a1','kc-sub-eng-1', now() + interval '119 minutes')
                     AND NOT platform.support_access_active('00000000-0000-0000-0000-0000000005a1','kc-sub-eng-1', now() + interval '120 minutes'),
                     'S16 the grant ends at its expiry');
SELECT pg_temp.check(NOT platform.support_access_active(gen_random_uuid(),'kc-sub-eng-1'), 'S17 an unknown grant id gives nothing');
SELECT pg_temp.check((SELECT count(*) FROM platform.visible_branches('support:eng.one')) = 2 AND platform.sees_all_branches('support:eng.one')
                     AND platform.can_see_branch('support:eng.one','MUM'), 'S18 while the grant is in force the engineer sees every branch');
SELECT pg_temp.check((SELECT string_agg(branch_code, ',') FROM platform.visible_branches('eng.one')) = 'MUM' AND NOT platform.sees_all_branches('eng.one'),
                     'S19 a tenant user with the same name gains nothing from the grant');
SELECT pg_temp.check((SELECT count(*) FROM platform.visible_branches('support:eng.two')) = 0, 'S20 another engineer sees nothing');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.staff_user (user_id, username, display_name, home_branch) VALUES ('sub-x','support:eng.one','CLAUDE-TEST','HO') $q$,
                           '23514', 'S21 no staff profile can use the support prefix');
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET expires_at = expires_at + interval '1 day' WHERE id = '00000000-0000-0000-0000-0000000005a1' $q$,
                           '23514', 'S22 a grant cannot be extended');
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET status = 'REVOKED', revoked_by = 'tenant.admin', expires_at = now() + interval '7 hours'
                                WHERE id = '00000000-0000-0000-0000-0000000005a1' $q$, '42501', 'S23 revoking cannot rewrite the approval');
UPDATE platform.support_access SET status = 'REVOKED', revoked_by = 'tenant.admin', revoke_reason = 'CLAUDE-TEST issue solved'
 WHERE id = '00000000-0000-0000-0000-0000000005a1';
SELECT pg_temp.check(NOT platform.support_access_active('00000000-0000-0000-0000-0000000005a1','kc-sub-eng-1')
                     AND (SELECT count(*) FROM platform.visible_branches('support:eng.one')) = 0 AND NOT platform.sees_all_branches('support:eng.one'),
                     'S24 revocation ends access and branch scope at once');
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET status = 'APPROVED', decided_by = 'tenant.admin' WHERE id = '00000000-0000-0000-0000-0000000005a1' $q$,
                           '23514', 'S25 a revoked grant cannot be approved again');
SELECT pg_temp.expect_fail($q$ DELETE FROM platform.support_access $q$, '42501', 'S26 support access records cannot be deleted');
INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes)
VALUES ('00000000-0000-0000-0000-0000000005a2','kc-sub-eng-1','eng.one','CLAUDE-TEST second look at the report','SUP-1010',30),
       ('00000000-0000-0000-0000-0000000005a3','kc-sub-eng-1','eng.one','CLAUDE-TEST request left waiting','SUP-1011',30),
       ('00000000-0000-0000-0000-0000000005a4','kc-sub-eng-1','eng.one','CLAUDE-TEST short grant','SUP-1012',15);
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET status = 'REJECTED', decided_by = 'tenant.admin' WHERE id = '00000000-0000-0000-0000-0000000005a2' $q$,
                           '23514', 'S27 a rejection needs a note');
UPDATE platform.support_access SET status = 'REJECTED', decided_by = 'tenant.admin', decision_note = 'CLAUDE-TEST not needed' WHERE id = '00000000-0000-0000-0000-0000000005a2';
SELECT pg_temp.check(NOT platform.support_access_active('00000000-0000-0000-0000-0000000005a2','kc-sub-eng-1')
                     AND (SELECT expires_at IS NULL FROM platform.support_access WHERE id = '00000000-0000-0000-0000-0000000005a2'),
                     'S28 a rejected request gives no access');
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET status = 'APPROVED', decided_by = 'tenant.admin' WHERE id = '00000000-0000-0000-0000-0000000005a2' $q$,
                           '23514', 'S29 a rejected request cannot be approved later');
-- time passing (the guard is switched off only to move the clock of the fixture)
ALTER TABLE platform.support_access DISABLE TRIGGER support_access_guard;
UPDATE platform.support_access SET requested_at = now() - interval '25 hours' WHERE id = '00000000-0000-0000-0000-0000000005a3';
UPDATE platform.support_access SET status = 'APPROVED', decided_by = 'tenant.admin', decided_at = now() - interval '16 minutes',
       expires_at = now() - interval '1 minute', requested_at = now() - interval '20 minutes' WHERE id = '00000000-0000-0000-0000-0000000005a4';
ALTER TABLE platform.support_access ENABLE TRIGGER support_access_guard;
SELECT pg_temp.expect_fail($q$ UPDATE platform.support_access SET status = 'APPROVED', decided_by = 'tenant.admin' WHERE id = '00000000-0000-0000-0000-0000000005a3' $q$,
                           '23514', 'S30 a request older than 24 hours cannot be approved');
SELECT pg_temp.check(NOT platform.support_access_active('00000000-0000-0000-0000-0000000005a4','kc-sub-eng-1')
                     AND (SELECT count(*) FROM platform.visible_branches('support:eng.one')) = 0,
                     'S31 an expired grant gives no access and no branch scope, with nobody revoking it');
SELECT pg_temp.check((SELECT string_agg(effective_status, ',' ORDER BY id) FROM platform.support_access_status) = 'REVOKED,REJECTED,LAPSED,EXPIRED',
                     'S32 the status view shows lapsed requests and expired grants');
SELECT pg_temp.expect_fail($q$ ALTER TABLE platform.support_access DISABLE TRIGGER support_access_guard;
                               UPDATE platform.support_access SET expires_at = decided_at + interval '9 hours' WHERE id = '00000000-0000-0000-0000-0000000005a4' $q$,
                           '23514', 'S33 even without the guard no grant can run past 8 hours');
ALTER TABLE platform.support_access ENABLE TRIGGER support_access_guard;

-- =========================================================================================================
-- Posting during end of day: cut-off, next-date booking, closed dates
-- =========================================================================================================
CREATE FUNCTION pg_temp.post(p_id uuid, p_date date) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (p_id, 'RECEIPT', p_date, p_date, 'test');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (p_id, p_date, 'HO', '1202', '1202', 'DR', 1000), (p_id, p_date, 'HO', '1101', '10010000000017', 'CR', 1000);
END $$;
SELECT pg_temp.post('00000000-0000-0000-0000-0000000000f1', '2026-10-09');
SELECT pg_temp.check((SELECT count(*) FROM ledger.account_entry WHERE business_date = '2026-10-09') = 2, 'P1 the open business date takes postings');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.post('00000000-0000-0000-0000-0000000000f2', '2026-10-10') $q$, '23514',
                           'P2 a date that has not opened yet takes no postings');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.deferred_receipt (id, loan_id, amount, received_by, cutoff_business_date, expected_posting_date)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000aa01', 5000, 'los-client', '2026-10-09', '2026-10-10') $q$,
  '23514', 'P3 while the day is open a receipt is posted directly, not deferred');

-- cut-off: end of day starts for Friday 09-Oct
INSERT INTO platform.eod_run (business_date, status, started_by) VALUES ('2026-10-09', 'RUNNING', 'test');
UPDATE platform.business_day SET status = 'EOD_RUNNING' WHERE id = 1;
SELECT pg_temp.post('00000000-0000-0000-0000-0000000000f3', '2026-10-09');
SELECT pg_temp.check((SELECT count(*) FROM ledger.account_entry WHERE business_date = '2026-10-09') = 4, 'P4 end of day still posts to the date it is closing');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.post('00000000-0000-0000-0000-0000000000f4', '2026-10-10') $q$, '23514',
                           'P5 during end of day nothing is posted to the next date either');
-- the caller sends wrong dates; the database sets them
INSERT INTO lending.deferred_receipt (id, loan_id, amount, mode, reference, received_by, idempotency_key, cutoff_business_date, expected_posting_date)
VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-00000000aa01', 5000, 'UPI', 'CLAUDE-TEST-UTR-1', 'los-client', 'key-1', '2020-01-01', '2020-01-02');
SELECT pg_temp.check((SELECT cutoff_business_date = '2026-10-09' AND expected_posting_date = '2026-10-10' AND status = 'PENDING'
                             AND posting_date IS NULL AND value_date IS NULL
                        FROM lending.deferred_receipt WHERE id = '00000000-0000-0000-0000-0000000000d1'),
                     'P6 after the cut-off a receipt is accepted for the next business date');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.deferred_receipt (id, loan_id, amount, received_by, idempotency_key, cutoff_business_date, expected_posting_date)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000aa01', 5000, 'los-client', 'key-1', '2026-10-09', '2026-10-10') $q$,
  '23505', 'P7 the same idempotency key is not accepted twice');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.deferred_receipt (id, loan_id, amount, received_by, cutoff_business_date, expected_posting_date)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000aa02', 5000, 'los-client', '2026-10-09', '2026-10-10') $q$,
  '23514', 'P8 a loan that is not active takes no receipt');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.deferred_receipt (id, loan_id, amount, received_by, cutoff_business_date, expected_posting_date)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000aa01', 0, 'los-client', '2026-10-09', '2026-10-10') $q$,
  '23514', 'P9 the amount must be positive');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.deferred_receipt (id, loan_id, amount, received_by, cutoff_business_date, expected_posting_date, status)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000aa01', 100, 'los-client', '2026-10-09', '2026-10-10', 'FAILED') $q$,
  '23514', 'P10 a receipt starts as PENDING');
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,amount,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb01','00000000-0000-0000-0000-00000000aa01',1,'REPAYMENT','2026-10-09','2026-10-09',5000,'{}','system');
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET status = 'APPLIED', loan_txn_id = '00000000-0000-0000-0000-00000000bb01'
                                WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$, '23514', 'P11 a receipt cannot be booked while the day is being closed');

-- end of day fails: receipts are still accepted, still for the next date
UPDATE platform.eod_run SET status = 'FAILED', finished_at = now() WHERE business_date = '2026-10-09';
UPDATE platform.business_day SET status = 'EOD_FAILED' WHERE id = 1;
INSERT INTO lending.deferred_receipt (id, loan_id, amount, received_by, cutoff_business_date, expected_posting_date)
VALUES ('00000000-0000-0000-0000-0000000000d2', '00000000-0000-0000-0000-00000000aa01', 700, 'los-client', '2026-10-09', '2026-10-09'),
       ('00000000-0000-0000-0000-0000000000d3', '00000000-0000-0000-0000-00000000aa01', 800, 'los-client', '2026-10-09', '2026-10-09');
SELECT pg_temp.check((SELECT count(*) FROM lending.deferred_receipt WHERE status = 'PENDING' AND expected_posting_date = '2026-10-10') = 3,
                     'P12 receipts keep being accepted while a failed end of day waits for its restart');
UPDATE platform.eod_run SET status = 'RUNNING', finished_at = NULL WHERE business_date = '2026-10-09';
UPDATE platform.business_day SET status = 'EOD_RUNNING' WHERE id = 1;

-- the date rolls: Saturday 10-Oct opens, Friday is closed
SELECT pg_temp.check(platform.advance_business_date() = '2026-10-10', 'P13 end of day opens the next business date');
UPDATE platform.eod_run SET status = 'COMPLETED', finished_at = now(), next_business_date = '2026-10-10' WHERE business_date = '2026-10-09';
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.post('00000000-0000-0000-0000-0000000000f5', '2026-10-09') $q$, '23514',
                           'P14 a closed date never receives another entry');
SELECT pg_temp.expect_fail($q$ INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, reverses, created_by)
  VALUES ('00000000-0000-0000-0000-0000000000f6', 'REVERSAL', '2026-10-09', '2026-10-09', '00000000-0000-0000-0000-0000000000f1', 'test') $q$,
  '23514', 'P15 not even a reversal: corrections are booked on the open date');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.deferred_receipt (id, loan_id, amount, received_by, cutoff_business_date, expected_posting_date)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000aa01', 5000, 'los-client', '2026-10-10', '2026-10-12') $q$,
  '23514', 'P16 once the new date is open receipts are posted directly again');
-- booking the deferred receipts on the new date
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET status = 'APPLIED', loan_txn_id = '00000000-0000-0000-0000-00000000bb01'
                                WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$, '23514', 'P17 a repayment booked on the closed date cannot settle the receipt');
SELECT pg_temp.post('00000000-0000-0000-0000-0000000000f7', '2026-10-10');
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,amount,lot_ids,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb02','00000000-0000-0000-0000-00000000aa01',2,'REPAYMENT','2026-10-10','2026-10-10',5000,
   '{00000000-0000-0000-0000-0000000000f7}','{}','system'),
  ('00000000-0000-0000-0000-00000000bb03','00000000-0000-0000-0000-00000000aa01',3,'REPAYMENT','2026-10-10','2026-10-10',999,'{}','{}','system'),
  ('00000000-0000-0000-0000-00000000bb04','00000000-0000-0000-0000-00000000aa01',4,'REPAYMENT','2026-10-09','2026-10-10',700,'{}','{}','system');
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET status = 'APPLIED', loan_txn_id = '00000000-0000-0000-0000-00000000bb03'
                                WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$, '23514', 'P18 the repayment must be for the amount received');
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET status = 'APPLIED', loan_txn_id = '00000000-0000-0000-0000-00000000bb04'
                                WHERE id = '00000000-0000-0000-0000-0000000000d2' $q$, '23514', 'P19 a back-valued repayment cannot settle the receipt: value date is the posting date');
UPDATE lending.deferred_receipt SET status = 'APPLIED', loan_txn_id = '00000000-0000-0000-0000-00000000bb02', posting_date = '2020-01-01'
 WHERE id = '00000000-0000-0000-0000-0000000000d1';
SELECT pg_temp.check((SELECT status = 'APPLIED' AND posting_date = '2026-10-10' AND value_date = '2026-10-10' AND cutoff_business_date = '2026-10-09'
                             AND applied_at IS NOT NULL AND attempts = 1
                        FROM lending.deferred_receipt WHERE id = '00000000-0000-0000-0000-0000000000d1'),
                     'P20 the receipt is booked and valued on the next business date');
SELECT pg_temp.check((SELECT min(business_date) = '2026-10-10' FROM ledger.account_entry WHERE lot_id = '00000000-0000-0000-0000-0000000000f7')
                     AND (SELECT count(*) FROM ledger.account_entry WHERE business_date = '2026-10-09') = 4,
                     'P21 its ledger entries are on the new date; the closed date is untouched');
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET status = 'PENDING' WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$,
                           '23514', 'P22 a booked receipt is final');
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET status = 'APPLIED', loan_txn_id = '00000000-0000-0000-0000-00000000bb02'
                                WHERE id = '00000000-0000-0000-0000-0000000000d2' $q$, '23514', 'P23 one repayment settles one receipt');
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET amount = 1 WHERE id = '00000000-0000-0000-0000-0000000000d2' $q$,
                           '42501', 'P24 a receipt cannot be edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.deferred_receipt $q$, '42501', 'P25 a receipt cannot be deleted');
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET status = 'FAILED' WHERE id = '00000000-0000-0000-0000-0000000000d2' $q$,
                           '23514', 'P26 a failed booking records why');
UPDATE lending.deferred_receipt SET status = 'FAILED', error = 'CLAUDE-TEST loan is frozen' WHERE id IN ('00000000-0000-0000-0000-0000000000d2','00000000-0000-0000-0000-0000000000d3');
SELECT pg_temp.expect_fail($q$ UPDATE lending.deferred_receipt SET status = 'CANCELLED' WHERE id = '00000000-0000-0000-0000-0000000000d3' $q$,
                           '23514', 'P27 setting a receipt aside needs who and why');
UPDATE lending.deferred_receipt SET status = 'CANCELLED', resolved_by = 'ops.user', resolution_note = 'CLAUDE-TEST refunded to source' WHERE id = '00000000-0000-0000-0000-0000000000d3';
UPDATE lending.deferred_receipt SET status = 'PENDING' WHERE id = '00000000-0000-0000-0000-0000000000d2';
SELECT pg_temp.check((SELECT status = 'PENDING' AND attempts = 1 FROM lending.deferred_receipt WHERE id = '00000000-0000-0000-0000-0000000000d2')
                     AND (SELECT status = 'CANCELLED' FROM lending.deferred_receipt WHERE id = '00000000-0000-0000-0000-0000000000d3'),
                     'P28 a failed receipt can be queued again or set aside, never lost');
-- next cut-off: Saturday closes, Sunday is a weekly off, so the receipt is for Monday
INSERT INTO platform.eod_run (business_date, status, started_by) VALUES ('2026-10-10', 'RUNNING', 'test');
UPDATE platform.business_day SET status = 'EOD_RUNNING' WHERE id = 1;
INSERT INTO lending.deferred_receipt (id, loan_id, amount, received_by, cutoff_business_date, expected_posting_date)
VALUES ('00000000-0000-0000-0000-0000000000d4', '00000000-0000-0000-0000-00000000aa01', 1200, 'los-client', '2026-10-10', '2026-10-11');
SELECT pg_temp.check((SELECT expected_posting_date FROM lending.deferred_receipt WHERE id = '00000000-0000-0000-0000-0000000000d4') = '2026-10-12',
                     'P29 the next business date skips the weekly off');
SELECT platform.advance_business_date();
UPDATE platform.eod_run SET status = 'COMPLETED_WITH_EXCEPTIONS', finished_at = now() WHERE business_date = '2026-10-10';
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.post('00000000-0000-0000-0000-0000000000f8', '2026-10-10') $q$, '23514',
                           'P30 a date closed with exceptions is closed too');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.post('00000000-0000-0000-0000-0000000000f9', '2026-10-11') $q$, '23514',
                           'P31 the weekly off between two closed and open dates is closed as well');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.post('00000000-0000-0000-0000-0000000000fa', '2026-06-30') $q$, '23514',
                           'P32 so is every earlier date');
SELECT pg_temp.post('00000000-0000-0000-0000-0000000000fb', '2026-10-12');
SELECT pg_temp.check((SELECT business_date FROM platform.business_day) = '2026-10-12'
                     AND (SELECT count(*) FROM ledger.account_entry WHERE business_date = '2026-10-12') = 2, 'P33 the open date, Monday, takes postings');

-- =========================================================================================================
-- Job catalogue
-- =========================================================================================================
SELECT pg_temp.check((SELECT count(*) FROM platform.job_definition WHERE kind <> 'REPORT') = 6
                     AND (SELECT bool_and(enabled AND schedule IS NOT NULL AND built_in) FROM platform.job_definition WHERE kind <> 'REPORT'),
                     'J1 six housekeeping jobs are seeded, scheduled and on');
SELECT pg_temp.check((SELECT count(*) FROM platform.job_definition WHERE kind = 'REPORT') = (SELECT count(*) FROM reporting.report_definition)
                     AND (SELECT bool_and(NOT enabled AND schedule IS NULL AND parameters ->> 'reportCode' = substr(code, 8))
                            FROM platform.job_definition WHERE kind = 'REPORT'),
                     'J2 every report has a job, off until someone schedules it');
SELECT pg_temp.expect_fail($q$ UPDATE platform.job_definition SET kind = 'REPORT' WHERE code = 'KYC_EXPIRY' $q$, '42501', 'J3 the kind of a job cannot change');
SELECT pg_temp.expect_fail($q$ DELETE FROM platform.job_definition WHERE code = 'KYC_EXPIRY' $q$, '42501', 'J4 a job is disabled, never deleted');
SELECT pg_temp.expect_fail($q$ UPDATE platform.job_definition SET schedule = 'every night' WHERE code = 'KYC_EXPIRY' $q$, '23514', 'J5 a schedule has six cron fields');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.job_definition (code, name, kind) VALUES ('MINE_BITCOIN','x','SHELL') $q$, '23514', 'J6 unknown job kind rejected');
UPDATE platform.job_definition SET schedule = '0 0 6 1 * *', enabled = true, parameters = parameters || '{"period":"PREVIOUS_MONTH","runAs":"tenant.admin"}',
       updated_by = 'checker1' WHERE code = 'REPORT_LOAN_BOOK';
SELECT pg_temp.check((SELECT enabled AND updated_at > now() - interval '1 minute' FROM platform.job_definition WHERE code = 'REPORT_LOAN_BOOK'), 'J7 a report job can be scheduled');

INSERT INTO platform.job_run (id, job_code, origin, scheduled_for, requested_by)
VALUES ('00000000-0000-0000-0000-0000000000a1', 'KYC_EXPIRY', 'SCHEDULE', '2026-10-12 00:30:00+05:30', 'scheduler');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.job_run (id, job_code, origin, scheduled_for, requested_by)
  VALUES (gen_random_uuid(), 'KYC_EXPIRY', 'SCHEDULE', '2026-10-12 00:30:00+05:30', 'scheduler') $q$, '23505', 'J8 a second instance cannot answer the same fire time');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.job_run (id, job_code, origin, requested_by) VALUES (gen_random_uuid(), 'KYC_EXPIRY', 'MANUAL', 'ops.user') $q$,
                           '23505', 'J9 a manual run cannot start while the job is running');
INSERT INTO platform.job_run (id, job_code, origin, requested_by) VALUES ('00000000-0000-0000-0000-0000000000a2', 'CONSENT_EXPIRY', 'MANUAL', 'ops.user');
SELECT pg_temp.check((SELECT count(*) FROM platform.job_run WHERE status = 'RUNNING') = 2, 'J10 different jobs run side by side');
UPDATE platform.job_run SET status = 'COMPLETED', processed = 3 WHERE id = '00000000-0000-0000-0000-0000000000a1';
SELECT pg_temp.check((SELECT finished_at IS NOT NULL AND processed = 3 FROM platform.job_run WHERE id = '00000000-0000-0000-0000-0000000000a1'), 'J11 a run finishes once');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.job_run (id, job_code, origin, scheduled_for, requested_by)
  VALUES (gen_random_uuid(), 'KYC_EXPIRY', 'SCHEDULE', '2026-10-12 00:30:00+05:30', 'scheduler') $q$, '23505', 'J12 a fire time already answered is not run again later');
SELECT pg_temp.expect_fail($q$ UPDATE platform.job_run SET processed = 99 WHERE id = '00000000-0000-0000-0000-0000000000a1' $q$, '42501', 'J13 a finished run cannot be changed');
SELECT pg_temp.expect_fail($q$ UPDATE platform.job_run SET status = 'FAILED' WHERE id = '00000000-0000-0000-0000-0000000000a2' $q$, '23514', 'J14 a failed run records why');
UPDATE platform.job_run SET status = 'FAILED', error = 'CLAUDE-TEST interrupted' WHERE id = '00000000-0000-0000-0000-0000000000a2';
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.job_run (id, job_code, origin, requested_by, status) VALUES (gen_random_uuid(), 'KYC_EXPIRY', 'MANUAL', 'x', 'COMPLETED') $q$,
                           '23514', 'J15 a run starts as RUNNING');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.job_run (id, job_code, origin, requested_by) VALUES (gen_random_uuid(), 'KYC_EXPIRY', 'SCHEDULE', 'x') $q$,
                           '23514', 'J16 a scheduled run names its fire time');
SELECT pg_temp.expect_fail($q$ DELETE FROM platform.job_run $q$, '42501', 'J17 job runs cannot be deleted');
INSERT INTO platform.job_run (id, job_code, origin, requested_by) VALUES ('00000000-0000-0000-0000-0000000000a3', 'KYC_EXPIRY', 'MANUAL', 'ops.user');
UPDATE platform.job_run SET status = 'COMPLETED', artifact = '{"delivery":"PENDING_PROVIDER"}' WHERE id = '00000000-0000-0000-0000-0000000000a3';
SELECT pg_temp.check((SELECT count(*) FROM platform.job_run WHERE job_code = 'KYC_EXPIRY') = 2, 'J18 after a run ends the job can run again');
SELECT pg_temp.check((SELECT count(*) FROM platform.approval_rule WHERE entity_type IN ('CUSTOM_FIELD','JOB_SCHEDULE','LOAN_PRODUCT_CUSTOM') AND checkers_required = 1) = 3,
                     'J19 schedules and custom field definitions change through maker-checker');

INSERT INTO reporting.report_definition (code, name, description, permission, sql_function)
VALUES ('CLAUDE_TEST_REPORT', 'CLAUDE-TEST report', 'CLAUDE-TEST', 'report:run', 'reporting.rpt_loan_book');
SELECT pg_temp.check((SELECT NOT enabled AND kind = 'REPORT' AND parameters ->> 'reportCode' = 'CLAUDE_TEST_REPORT'
                        FROM platform.job_definition WHERE code = 'REPORT_CLAUDE_TEST_REPORT'), 'J20 a report added later gets its job');

-- report files past retention ---------------------------------------------------------------------------------
INSERT INTO reporting.report_run (id, report_code, requested_by, business_date, parameters, status) VALUES
  ('00000000-0000-0000-0000-0000000000e1', 'LOAN_BOOK', 'tenant.admin', '2026-06-30', '{}', 'RUNNING'),
  ('00000000-0000-0000-0000-0000000000e2', 'LOAN_BOOK', 'tenant.admin', '2026-10-12', '{}', 'RUNNING'),
  ('00000000-0000-0000-0000-0000000000e3', 'LOAN_BOOK', 'tenant.admin', '2026-10-12', '{}', 'RUNNING');
UPDATE reporting.report_run SET status = 'COMPLETED', row_count = 2, artifact_key = 'tenants/claude-test/reports/2026-06-30/LOAN_BOOK-e1.csv',
       artifact_bytes = 10, file_name = 'loan-book.csv', content_type = 'text/csv', finished_at = now() - interval '100 days'
 WHERE id = '00000000-0000-0000-0000-0000000000e1';
UPDATE reporting.report_run SET status = 'COMPLETED', row_count = 2, artifact_key = 'tenants/claude-test/reports/2026-10-12/LOAN_BOOK-e2.csv',
       artifact_bytes = 10, file_name = 'loan-book.csv', content_type = 'text/csv', finished_at = now() - interval '2 days'
 WHERE id = '00000000-0000-0000-0000-0000000000e2';
SELECT pg_temp.check((SELECT string_agg(id::text, ',') FROM reporting.purgeable_runs(90)) = '00000000-0000-0000-0000-0000000000e1',
                     'R1 only completed runs older than the retention period are due for removal');
SELECT pg_temp.check((SELECT count(*) FROM reporting.purgeable_runs(1)) = 2 AND (SELECT count(*) FROM reporting.purgeable_runs(0)) = 2,
                     'R2 the retention period is at least one day');
SELECT pg_temp.expect_fail($q$ UPDATE reporting.report_run SET artifact_purged_at = now(), row_count = 0 WHERE id = '00000000-0000-0000-0000-0000000000e1' $q$,
                           '42501', 'R3 marking a file as removed changes nothing else');
SELECT pg_temp.expect_fail($q$ UPDATE reporting.report_run SET artifact_purged_at = now() WHERE id = '00000000-0000-0000-0000-0000000000e3' $q$,
                           '42501', 'R4 a run without a file has nothing to remove');
UPDATE reporting.report_run SET artifact_purged_at = now() WHERE id = '00000000-0000-0000-0000-0000000000e1';
SELECT pg_temp.check((SELECT count(*) FROM reporting.purgeable_runs(90)) = 0
                     AND (SELECT requested_by = 'tenant.admin' AND artifact_key IS NOT NULL FROM reporting.report_run WHERE id = '00000000-0000-0000-0000-0000000000e1'),
                     'R5 the run stays as the record of who exported what; only its file is gone');
SELECT pg_temp.expect_fail($q$ UPDATE reporting.report_run SET artifact_purged_at = NULL WHERE id = '00000000-0000-0000-0000-0000000000e1' $q$,
                           '42501', 'R6 a removal cannot be undone or repeated');
SELECT pg_temp.expect_fail($q$ UPDATE reporting.report_run SET status = 'COMPLETED', row_count = 1, artifact_key = 'tenants/claude-test/reports/x.csv', finished_at = now(),
                                      artifact_purged_at = now() WHERE id = '00000000-0000-0000-0000-0000000000e3' $q$, '42501', 'R7 a run cannot finish as already removed');

-- dashboard snapshot ---------------------------------------------------------------------------------------------
UPDATE lending.loan_account SET principal_outstanding = 90000, overdue_amount = 2500 WHERE loan_no = '10010000000017';
SELECT pg_temp.check(reporting.refresh_dashboard_snapshot() = '2026-10-12', 'M1 the snapshot is taken for the business date');
SELECT pg_temp.check((SELECT active_loans = 1 AND portfolio_outstanding = 90000 AND overdue_amount = 2500 AND overdue_loans = 1 AND npa_loans = 0
                             AND gross_npa = 0 AND disbursed = 0 AND collected = 0
                        FROM reporting.dashboard_snapshot WHERE business_date = '2026-10-12'), 'M2 it holds the whole-book figures');
UPDATE lending.loan_account SET asset_class = 'SUBSTANDARD', npa_since = '2026-10-12' WHERE loan_no = '10010000000017';
SELECT reporting.refresh_dashboard_snapshot();
SELECT pg_temp.check((SELECT count(*) = 1 AND max(npa_loans) = 1 AND max(gross_npa) = 90000 FROM reporting.dashboard_snapshot),
                     'M3 a refresh on the same date replaces the figures');

-- consent expiry ----------------------------------------------------------------------------------------------------
INSERT INTO customer.consent (id,customer_id,purpose,lawful_basis,notice_version,channel,evidence_ref,granted_at,expires_at,recorded_by) VALUES
  ('00000000-0000-0000-0000-00000000ce01','00000000-0000-0000-0000-0000000000c1','MARKETING','CONSENT','v1','WEB','CLAUDE-TEST-OTP-1', now() - interval '400 days', now() - interval '35 days','maker'),
  ('00000000-0000-0000-0000-00000000ce02','00000000-0000-0000-0000-0000000000c1','LOAN_PROCESSING','CONSENT','v1','WEB','CLAUDE-TEST-OTP-2', now() - interval '10 days', now() + interval '355 days','maker'),
  ('00000000-0000-0000-0000-00000000ce03','00000000-0000-0000-0000-0000000000c2','MARKETING','CONSENT','v1','WEB','CLAUDE-TEST-OTP-3', now() - interval '10 days', NULL,'maker');
SELECT pg_temp.check(customer.expire_consents() = 1, 'N1 one consent has run past its validity');
SELECT pg_temp.check((SELECT count(*) FROM customer.consent_event WHERE event = 'EXPIRED' AND consent_id = '00000000-0000-0000-0000-00000000ce01'
                         AND at < now() AND actor = 'system') = 1, 'N2 the expiry is recorded in the consent history, at the time it took effect');
SELECT pg_temp.check(customer.expire_consents() = 0, 'N3 an expiry is recorded once');
SELECT pg_temp.check(NOT customer.has_consent('00000000-0000-0000-0000-0000000000c1','MARKETING', now())
                     AND customer.has_consent('00000000-0000-0000-0000-0000000000c1','LOAN_PROCESSING', now()), 'N4 only the expired consent is out of force');
SELECT pg_temp.check(customer.expire_consents(now() + interval '2 years') = 1, 'N5 a later run picks up the next expiry; open-ended consents never expire');

-- usage figures -----------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT active_loans = 1 AND active_customers = 2 AND staff_users = 3 AND database_bytes > 1000000 FROM platform.usage_snapshot()),
                     'U1 usage figures: active loans, active customers, staff users, database size');
UPDATE platform.staff_user SET status = 'EXITED' WHERE username = 'eng.one';
SELECT pg_temp.check((SELECT staff_users FROM platform.usage_snapshot()) = 2, 'U2 exited staff are not counted');
\echo ALL P2-7 PLATFORM TESTS PASSED
