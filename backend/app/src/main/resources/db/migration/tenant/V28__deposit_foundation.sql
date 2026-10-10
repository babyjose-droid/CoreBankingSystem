-- Phase 4 (P4-0): foundation for savings/current accounts and term deposits.
--   1. Whether this legal entity may take deposits, and under which rule set (BANK or NBFC_DEPOSIT).
--   2. The regulatory rule set as dated rows: a change by the regulator is a new row, not a code change.
--   3. GL heads for deposits.
-- No product or account table yet: those come with P4-1. Nothing here changes lending.

CREATE SCHEMA IF NOT EXISTS deposits;

-- ---------------------------------------------------------------------------------------------------------
-- 1. Deposit-taking status of the legal entity
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE platform.legal_entity
  ADD COLUMN deposit_taking           boolean NOT NULL DEFAULT false,   -- NBFC registered by RBI to accept public deposits
  ADD COLUMN deposit_registration_ref text,
  ADD CONSTRAINT deposit_taking_is_for_a_registered_nbfc
      CHECK (NOT deposit_taking OR (entity_type = 'NBFC' AND nullif(btrim(deposit_registration_ref), '') IS NOT NULL));

-- The rule set this tenant falls under, or NULL when it cannot take deposits.
--   BANK, SFB, COOP_BANK -> BANK. Small finance banks and co-operative banks have their own RBI Directions;
--     the BANK rule set is applied to them until those are read (docs/phase4-status.md).
--   NBFC -> NBFC_DEPOSIT only when registered to accept public deposits.
--   HFC, MFI -> none (HFC deposit rules are in the HFC Directions, not encoded).
CREATE FUNCTION deposits.rule_kind() RETURNS text LANGUAGE sql STABLE AS $$
  SELECT CASE WHEN entity_type IN ('BANK','SFB','COOP_BANK') THEN 'BANK'
              WHEN entity_type = 'NBFC' AND deposit_taking THEN 'NBFC_DEPOSIT'
         END
    FROM platform.legal_entity WHERE id = 1
$$;

-- ---------------------------------------------------------------------------------------------------------
-- 2. Rule set
-- value: a number; a yes/no rule is 1 or 0. A code that is absent for a kind does not apply to it.
-- verified: true only when the figure was read in the regulator's own text. effective_from is the date from which
-- this system applies the value; it is not the date the regulation commenced.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE deposits.rule (
    kind           text NOT NULL CHECK (kind IN ('BANK','NBFC_DEPOSIT')),
    code           text NOT NULL CHECK (code ~ '^[a-z][a-z0-9.-]+$'),
    value          numeric(20,4) NOT NULL,
    effective_from date NOT NULL,
    effective_to   date,
    source         text NOT NULL CHECK (btrim(source) <> ''),
    verified       boolean NOT NULL DEFAULT false,
    note           text,
    created_by     text NOT NULL DEFAULT 'migration',
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    PRIMARY KEY (kind, code, effective_from),
    EXCLUDE USING gist (kind WITH =, code WITH =,
                        daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') WITH &&)
);

-- A rule row is history: its value and start date never change. It can be ended (effective_to set once), and its
-- source, note and verified flag can be corrected. A new value is a new row from a later date.
CREATE FUNCTION deposits.guard_rule() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'a deposit rule cannot be deleted; end it with effective_to' USING ERRCODE = '42501';
  END IF;
  IF NEW.kind <> OLD.kind OR NEW.code <> OLD.code OR NEW.value <> OLD.value OR NEW.effective_from <> OLD.effective_from THEN
    RAISE EXCEPTION 'a deposit rule cannot be changed; end it and add a new row from a later date' USING ERRCODE = '42501';
  END IF;
  IF OLD.effective_to IS NOT NULL AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
    RAISE EXCEPTION 'this deposit rule has already ended on %', OLD.effective_to USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER rule_guard BEFORE UPDATE OR DELETE ON deposits.rule FOR EACH ROW EXECUTE FUNCTION deposits.guard_rule();
CREATE TRIGGER rule_no_truncate BEFORE TRUNCATE ON deposits.rule FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_change();

-- The value of a rule for this tenant on a date (default: the business date); NULL when it does not apply.
CREATE FUNCTION deposits.rule_value(p_code text, p_on date DEFAULT NULL) RETURNS numeric LANGUAGE sql STABLE AS $$
  SELECT r.value FROM deposits.rule r
   WHERE r.kind = deposits.rule_kind() AND r.code = p_code
     AND r.effective_from <= coalesce(p_on, (SELECT business_date FROM platform.business_day WHERE id = 1))
     AND (r.effective_to IS NULL OR r.effective_to >= coalesce(p_on, (SELECT business_date FROM platform.business_day WHERE id = 1)))
$$;

CREATE FUNCTION deposits.rule_required(p_code text, p_on date DEFAULT NULL) RETURNS numeric LANGUAGE plpgsql STABLE AS $$
DECLARE v numeric;
BEGIN
  v := deposits.rule_value(p_code, p_on);
  IF v IS NULL THEN
    RAISE EXCEPTION 'deposit rule % is not set for %', p_code, coalesce(deposits.rule_kind(), 'this institution') USING ERRCODE = 'P0002';
  END IF;
  RETURN v;
END $$;

-- The whole rule set in force for this tenant on a date: what the engine loads (DepositRules) and what staff see.
CREATE FUNCTION deposits.rules_in_force(p_on date DEFAULT NULL)
RETURNS TABLE (code text, value numeric, effective_from date, source text, verified boolean, note text)
LANGUAGE sql STABLE AS $$
  SELECT r.code, r.value, r.effective_from, r.source, r.verified, r.note FROM deposits.rule r
   WHERE r.kind = deposits.rule_kind()
     AND r.effective_from <= coalesce(p_on, (SELECT business_date FROM platform.business_day WHERE id = 1))
     AND (r.effective_to IS NULL OR r.effective_to >= coalesce(p_on, (SELECT business_date FROM platform.business_day WHERE id = 1)))
   ORDER BY r.code
$$;

-- Refuses, with a reason staff can act on, a deposit family this institution may not offer.
-- The product and account tables of P4-1 call it, so the rule holds whatever the application does.
CREATE FUNCTION deposits.assert_can_offer(p_family text) RETURNS void LANGUAGE plpgsql STABLE AS $$
DECLARE k text := deposits.rule_kind(); et text;
BEGIN
  IF p_family NOT IN ('CASA','TERM_DEPOSIT') THEN
    RAISE EXCEPTION 'unknown deposit family %', p_family USING ERRCODE = '22023';
  END IF;
  SELECT entity_type INTO et FROM platform.legal_entity WHERE id = 1;
  IF k IS NULL THEN
    RAISE EXCEPTION '%', CASE WHEN et IS NULL THEN 'the legal entity is not set up'
                              WHEN et = 'NBFC' THEN 'this NBFC is not recorded as registered by RBI to accept public deposits'
                              ELSE 'deposits are not supported for an institution of type ' || et END
      USING ERRCODE = '23514';
  END IF;
  IF p_family = 'CASA' AND coalesce(deposits.rule_value('demand-deposits-allowed'), 0) = 0 THEN
    RAISE EXCEPTION 'a deposit-taking NBFC cannot accept deposits repayable on demand (savings or current accounts)'
      USING ERRCODE = '23514';
  END IF;
END $$;

-- ---- seed: deposit-taking NBFC -----------------------------------------------------------------------------
-- RBI (Non-Banking Financial Companies - Acceptance of Public Deposits) Directions, 2025, RBI/DOR/2025-26/346
-- dated 28-Nov-2025. Full text read on 10-Oct-2026 in a reproduction of the notification (the RBI PDF was not opened).
INSERT INTO deposits.rule (kind, code, value, effective_from, source, verified, note) VALUES
 ('NBFC_DEPOSIT','demand-deposits-allowed',0,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 15',true,'No public deposit repayable on demand'),
 ('NBFC_DEPOSIT','insured',0,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 31(9)',true,'Advertisement and form state that the deposits are not insured'),
 ('NBFC_DEPOSIT','nominees.max',1,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 50',true,'One person, under the Banking Companies (Nomination) Rules, 1985'),
 ('NBFC_DEPOSIT','td.min-tenure-months',12,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 16',true,NULL),
 ('NBFC_DEPOSIT','td.max-tenure-months',60,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 16',true,NULL),
 ('NBFC_DEPOSIT','td.max-rate-percent',12.5,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 19',true,'Ceiling on the rate of interest per annum'),
 ('NBFC_DEPOSIT','td.min-compounding-rest-months',1,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 19',true,'Interest paid or compounded at rests not shorter than monthly'),
 ('NBFC_DEPOSIT','td.lock-in-months',3,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 35',true,'No premature repayment and no loan against the deposit, except on death'),
 ('NBFC_DEPOSIT','td.premature.no-interest-before-months',6,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 40',true,'After 3 months but before 6 months: no interest'),
 ('NBFC_DEPOSIT','td.premature.rate-cut-percent',2,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 40',true,'Below the rate for the period the deposit has run'),
 ('NBFC_DEPOSIT','td.premature.rate-cut-from-minimum-percent',3,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 40',true,'Below the minimum rate, when no rate exists for the period run'),
 ('NBFC_DEPOSIT','td.emergency.tiny-deposit-max',10000,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 7(19) and 37(1)',true,'All deposits of the sole or first-named depositor together'),
 ('NBFC_DEPOSIT','td.emergency.max-percent',50,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 37(2)',true,'Of the principal, without interest, within three months'),
 ('NBFC_DEPOSIT','td.emergency.max-amount',500000,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 37(2)',true,NULL),
 ('NBFC_DEPOSIT','td.maturity-notice-days',14,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 22',true,'At least this many days before maturity'),
 ('NBFC_DEPOSIT','td.loan-max-percent',75,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 36(2)',true,'Loan against the deposit, after three months'),
 ('NBFC_DEPOSIT','td.loan-spread-percent',2,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 36(2)',true,'Above the rate payable on the deposit'),
 ('NBFC_DEPOSIT','td.deposits-to-nof-multiple',1.5,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 17',true,'Public deposits outstanding against net owned fund'),
 ('NBFC_DEPOSIT','td.renewal-needs-consent',1,'2026-04-01','RBI NBFC Public Deposits Directions 2025, para 17',true,'No renewal without the express and voluntary consent of the depositor'),
 ('NBFC_DEPOSIT','tds.rate-percent',10,'2026-04-01','Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)',false,'Residents'),
 ('NBFC_DEPOSIT','tds.threshold',10000,'2026-04-01','Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)',false,'Payers other than banks, co-operative societies and post offices'),
 ('NBFC_DEPOSIT','tds.threshold-senior',10000,'2026-04-01','Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)',false,'No higher threshold for senior citizens with this kind of payer'),
 ('NBFC_DEPOSIT','tds.no-pan-rate-percent',20,'2026-04-01','Carried over from s.206AA of the 1961 Act; not confirmed under the 2025 Act',false,'Confirm before go-live'),
 ('NBFC_DEPOSIT','senior-citizen-age',60,'2026-04-01','Income-tax practice (resident aged 60 or more in the tax year)',false,NULL);

-- ---- seed: banks -------------------------------------------------------------------------------------------
-- RBI (Commercial Banks - Interest Rate on Deposits) Directions, 2025 (RBI/DOR/2025-26/153; the RBI page shows a
-- version updated on 1-Oct-2026). The RBI text could not be opened on 10-Oct-2026: these figures are from summaries
-- and are all marked verified = false. Minimum tenor, the periodicity of savings interest and overdue interest are
-- not seeded at all: the product's settings decide until the text is read.
INSERT INTO deposits.rule (kind, code, value, effective_from, source, verified, note) VALUES
 ('BANK','demand-deposits-allowed',1,'2026-04-01','Banking Regulation Act 1949 (banking includes deposits repayable on demand)',false,NULL),
 ('BANK','insured',1,'2026-04-01','DICGC deposit insurance; the cover amount is not seeded',false,'Confirm the cover amount before it is shown on receipts'),
 ('BANK','nominees.max',4,'2026-04-01','Banking Laws (Amendment) Act 2025, in force 1-Nov-2025 (PIB release via IIBF note)',false,'Simultaneous with shares totalling 100%, or successive'),
 ('BANK','savings.uniform-rate-up-to',100000,'2026-04-01','RBI Commercial Banks Interest Rate on Deposits Directions 2025 (summary)',false,'One rate on balances up to this amount; different rates allowed above'),
 ('BANK','td.premature.mandatory-up-to',10000000,'2026-04-01','RBI Commercial Banks Interest Rate on Deposits Directions 2025 (summary)',false,'Term deposits of individuals up to this amount must allow premature withdrawal'),
 ('BANK','casa.review-after-months',12,'2026-04-01','RBI Inoperative Accounts / Unclaimed Deposits instructions of 1-Jan-2024 (bank summary)',false,'Annual review of accounts with no customer-induced transaction'),
 ('BANK','casa.inoperative-after-months',24,'2026-04-01','RBI Inoperative Accounts / Unclaimed Deposits instructions of 1-Jan-2024 (bank summary)',false,'No customer-induced transaction for over two years; per account'),
 ('BANK','unclaimed-after-years',10,'2026-04-01','Depositor Education and Awareness Fund (press report of the RBI mandate)',false,'Balance not operated or claimed is transferred to the DEA Fund'),
 ('BANK','tds.rate-percent',10,'2026-04-01','Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)',false,'Residents'),
 ('BANK','tds.threshold',50000,'2026-04-01','Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)',false,'Aggregate interest in the tax year'),
 ('BANK','tds.threshold-senior',100000,'2026-04-01','Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)',false,'Senior citizens'),
 ('BANK','tds.no-pan-rate-percent',20,'2026-04-01','Carried over from s.206AA of the 1961 Act; not confirmed under the 2025 Act',false,'Confirm before go-live'),
 ('BANK','tds.on-savings-interest',0,'2026-04-01','Listed among the no-deduction cases by one practitioner source',false,'Decision D-17: off by default; confirm'),
 ('BANK','senior-citizen-age',60,'2026-04-01','Income-tax practice (resident aged 60 or more in the tax year)',false,NULL);

-- ---------------------------------------------------------------------------------------------------------
-- 3. GL heads for deposits. Added only to a tenant that can take deposits; heads it already has are kept.
-- Liabilities 2400-2409 and 2205, expense 5107-5108, income 4107-4108, clearing 1207 (asset) and 2409.
-- A premature-withdrawal penalty is a reduction of interest expense, not income, so it has no head.
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION ledger.load_deposit_heads() RETURNS int LANGUAGE plpgsql AS $$
DECLARE k text := deposits.rule_kind(); before int; after int;
BEGIN
  IF k IS NULL OR NOT EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2000') THEN RETURN 0; END IF;
  SELECT count(*) INTO before FROM ledger.gl_head;
  INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES
    ('2400','Deposits','LIABILITY','2000',false)
  ON CONFLICT (code) DO NOTHING;
  INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES
    ('2403','Term deposits','LIABILITY','2400',true),
    ('2404','Interest payable on deposits','LIABILITY','2400',true),
    ('2405','Recurring deposits','LIABILITY','2400',true),
    ('2406','Matured deposits unpaid','LIABILITY','2400',true),
    ('2407','Unclaimed deposits','LIABILITY','2400',true),
    ('2408','Deposit payout in transit','LIABILITY','2400',true),
    ('5107','Interest on deposits','EXPENSE','5000',true)
  ON CONFLICT (code) DO NOTHING;
  IF EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2200') THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES
      ('2205','TDS payable on deposit interest','LIABILITY','2200',true)
    ON CONFLICT (code) DO NOTHING;
  END IF;
  IF k = 'BANK' THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES
      ('2401','Savings deposits','LIABILITY','2400',true),
      ('2402','Current deposits','LIABILITY','2400',true),
      ('2409','Inward clearing','LIABILITY','2400',true),
      ('1207','Outward clearing','ASSET','1200',true),
      ('5108','Interest on savings deposits','EXPENSE','5000',true),
      ('4107','Account service charges','INCOME','4000',true),
      ('4108','Cheque charges','INCOME','4000',true)
    ON CONFLICT (code) DO NOTHING;
  END IF;
  SELECT count(*) INTO after FROM ledger.gl_head;
  RETURN after - before;
END $$;
SELECT ledger.load_deposit_heads();

-- Record (or withdraw) an NBFC's registration to accept public deposits in the tenant database. The platform
-- operator calls it together with control.set_deposit_taking; the heads are loaded when it is switched on.
CREATE FUNCTION deposits.set_deposit_taking(p_deposit_taking boolean, p_registration_ref text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE et text;
BEGIN
  SELECT entity_type INTO et FROM platform.legal_entity WHERE id = 1 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'the legal entity is not set up' USING ERRCODE = 'P0002'; END IF;
  IF p_deposit_taking AND et <> 'NBFC' THEN
    RAISE EXCEPTION 'this institution is of type %; the registration to accept public deposits is recorded for an NBFC only', et
      USING ERRCODE = '23514';
  END IF;
  IF p_deposit_taking AND nullif(btrim(p_registration_ref), '') IS NULL THEN
    RAISE EXCEPTION 'the RBI registration reference is required' USING ERRCODE = '23514';
  END IF;
  UPDATE platform.legal_entity
     SET deposit_taking = p_deposit_taking,
         deposit_registration_ref = CASE WHEN p_deposit_taking THEN btrim(p_registration_ref) ELSE deposit_registration_ref END
   WHERE id = 1;
  IF p_deposit_taking THEN PERFORM ledger.load_deposit_heads(); END IF;
END $$;
