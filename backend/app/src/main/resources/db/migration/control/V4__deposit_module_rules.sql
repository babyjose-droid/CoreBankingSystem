-- Phase 4 (P4-0): which institution may be licensed for the deposit modules.
--   CASA (savings and current accounts) are demand deposits: banks only. An NBFC may not accept a deposit
--   repayable on demand (RBI NBFC - Acceptance of Public Deposits Directions, 2025, para 15).
--   TD (term deposits): banks, and an NBFC only when RBI has registered it to accept public deposits (NBFC-D).
--   HFC: its deposit rules are in the HFC Directions, which the product does not encode yet. MFI: no deposits.
-- The control plane only records the registration; the tenant's own RBI registration is what permits the business.

ALTER TABLE control.tenant
  ADD COLUMN deposit_taking           boolean NOT NULL DEFAULT false,
  ADD COLUMN deposit_registration_ref text,          -- RBI certificate of registration (the evidence), no personal data
  ADD CONSTRAINT deposit_taking_is_for_a_registered_nbfc
      CHECK (NOT deposit_taking OR (entity_type = 'NBFC' AND nullif(btrim(deposit_registration_ref), '') IS NOT NULL));

CREATE FUNCTION control.module_allowed(p_entity_type text, p_deposit_taking boolean, p_module text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE p_module
           WHEN 'CASA' THEN p_entity_type IN ('BANK','SFB','COOP_BANK')
           WHEN 'TD'   THEN p_entity_type IN ('BANK','SFB','COOP_BANK') OR (p_entity_type = 'NBFC' AND coalesce(p_deposit_taking, false))
           ELSE true
         END
$$;

-- Entitlements recorded before this rule existed: an NBFC on the ENTERPRISE edition was given CASA and TD with the
-- edition. They could not be used (no deposit API existed). Switch them off and leave a line in the operator log.
INSERT INTO control.operator_action (tenant_id, operator, action, reason)
SELECT m.tenant_id, 'migration-V4', 'MODULE_DISABLED',
       m.module_code || ' is not available to a ' || t.entity_type || ' (deposit module rules)'
  FROM control.tenant_module m JOIN control.tenant t ON t.id = m.tenant_id
 WHERE m.enabled AND NOT control.module_allowed(t.entity_type, t.deposit_taking, m.module_code);
UPDATE control.tenant_module m SET enabled = false
  FROM control.tenant t
 WHERE t.id = m.tenant_id AND m.enabled AND NOT control.module_allowed(t.entity_type, t.deposit_taking, m.module_code);

CREATE FUNCTION control.check_tenant_module() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t control.tenant%ROWTYPE;
BEGIN
  IF NOT NEW.enabled THEN RETURN NEW; END IF;
  SELECT * INTO t FROM control.tenant WHERE id = NEW.tenant_id;
  IF NOT control.module_allowed(t.entity_type, t.deposit_taking, NEW.module_code) THEN
    RAISE EXCEPTION 'module % cannot be enabled for tenant %: %', NEW.module_code, t.code,
      CASE WHEN NEW.module_code = 'CASA' THEN 'savings and current accounts are for banks; an institution of type ' || t.entity_type || ' cannot accept demand deposits'
           WHEN t.entity_type = 'NBFC' THEN 'term deposits need an NBFC registered by RBI to accept public deposits; record the registration first'
           ELSE 'deposits are not supported for an institution of type ' || t.entity_type END
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tenant_module_allowed BEFORE INSERT OR UPDATE ON control.tenant_module
  FOR EACH ROW EXECUTE FUNCTION control.check_tenant_module();

-- The entity type or the registration cannot change under a module that depends on it.
CREATE FUNCTION control.check_tenant_modules_still_allowed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bad text;
BEGIN
  SELECT string_agg(m.module_code, ', ' ORDER BY m.module_code) INTO bad
    FROM control.tenant_module m
   WHERE m.tenant_id = NEW.id AND m.enabled AND NOT control.module_allowed(NEW.entity_type, NEW.deposit_taking, m.module_code);
  IF bad IS NOT NULL THEN
    RAISE EXCEPTION 'tenant % has module(s) % enabled, which this change would not allow; disable them first', NEW.code, bad
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tenant_modules_still_allowed BEFORE UPDATE OF entity_type, deposit_taking ON control.tenant
  FOR EACH ROW EXECUTE FUNCTION control.check_tenant_modules_still_allowed();

-- Record (or withdraw) an NBFC's registration to accept public deposits. Every change is in the operator log.
CREATE FUNCTION control.set_deposit_taking(p_tenant text, p_deposit_taking boolean, p_registration_ref text, p_operator text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE t control.tenant%ROWTYPE;
BEGIN
  SELECT * INTO t FROM control.tenant WHERE code = p_tenant FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'tenant % not found', p_tenant USING ERRCODE = 'P0002'; END IF;
  IF p_deposit_taking AND t.entity_type <> 'NBFC' THEN
    RAISE EXCEPTION 'tenant % is of type %; the registration to accept public deposits is recorded for an NBFC only', p_tenant, t.entity_type
      USING ERRCODE = '23514';
  END IF;
  IF p_deposit_taking AND nullif(btrim(p_registration_ref), '') IS NULL THEN
    RAISE EXCEPTION 'the RBI registration reference is required' USING ERRCODE = '23514';
  END IF;
  UPDATE control.tenant
     SET deposit_taking = p_deposit_taking,
         deposit_registration_ref = CASE WHEN p_deposit_taking THEN btrim(p_registration_ref) ELSE deposit_registration_ref END
   WHERE id = t.id;
  INSERT INTO control.operator_action (tenant_id, operator, action, reason)
  VALUES (t.id, p_operator, CASE WHEN p_deposit_taking THEN 'DEPOSIT_TAKING_ON' ELSE 'DEPOSIT_TAKING_OFF' END,
          coalesce(nullif(btrim(p_registration_ref), ''), 'registration withdrawn'));
END $$;
