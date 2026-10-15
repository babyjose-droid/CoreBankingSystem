-- Phase 4 (P4-2b): deposit money through the payment gateway.
--   1. A bank transfer out of a savings or current account can be sent through the payout gateway. It posts at once
--      (Dr the account, Cr payout in transit 2408); when the bank pays, the in-transit head is cleared against the
--      disbursement bank; when it fails or comes back, the postings are reversed and the money returns to the account.
--      deposits.gateway_transfer follows each such transfer.
--   2. Money received through a gateway payment link credits a savings or current account, or funds a booked term
--      deposit (funding source GATEWAY).
--   3. The integration tables that were for loans only (payout instructions, beneficiaries, collection orders and
--      payments, the reconciliation view) take a deposit account instead of a loan.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Transfers out through the gateway
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('deposits.gl.in-transit', '2408', 'migration')     -- money sent through the gateway waits here until the bank pays
ON CONFLICT (key) DO NOTHING;

CREATE TABLE deposits.gateway_transfer (
    movement_id   uuid PRIMARY KEY REFERENCES deposits.movement(id),
    status        text NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT','PAID','RETURNED')),
    paid_lot_id   uuid UNIQUE REFERENCES ledger.transaction_lot(id),     -- in transit cleared against the bank
    utr           text CHECK (utr IS NULL OR length(utr) <= 60),
    return_lot_id uuid UNIQUE REFERENCES ledger.transaction_lot(id),     -- the reversal of the paid lot (returned after paid)
    return_reason text CHECK (return_reason IS NULL OR (btrim(return_reason) <> '' AND length(return_reason) <= 500)),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT gateway_transfer_state CHECK (CASE status
        WHEN 'SENT' THEN paid_lot_id IS NULL AND return_lot_id IS NULL AND return_reason IS NULL
        WHEN 'PAID' THEN paid_lot_id IS NOT NULL AND return_lot_id IS NULL AND return_reason IS NULL
        ELSE return_reason IS NOT NULL AND (paid_lot_id IS NULL) = (return_lot_id IS NULL) END)
);

CREATE FUNCTION deposits.guard_gateway_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m deposits.movement%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a gateway transfer is never deleted' USING ERRCODE = '42501'; END IF;
  SELECT * INTO m FROM deposits.movement WHERE id = NEW.movement_id;
  IF TG_OP = 'INSERT' THEN
    IF m.kind <> 'BANK_TRANSFER_OUT' OR m.party_account_cipher IS NULL THEN
      RAISE EXCEPTION 'only a bank transfer out to a named bank account is sent through the gateway' USING ERRCODE = '23514';
    END IF;
    IF NEW.status <> 'SENT' THEN RAISE EXCEPTION 'a gateway transfer starts as SENT' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  IF NEW.movement_id <> OLD.movement_id OR NEW.created_at <> OLD.created_at
     OR (OLD.paid_lot_id IS NOT NULL AND NEW.paid_lot_id IS DISTINCT FROM OLD.paid_lot_id) THEN
    RAISE EXCEPTION 'the movement and the payment of a gateway transfer never change' USING ERRCODE = '42501';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT ((OLD.status = 'SENT' AND NEW.status IN ('PAID','RETURNED')) OR (OLD.status = 'PAID' AND NEW.status = 'RETURNED')) THEN
      RAISE EXCEPTION 'a gateway transfer cannot go from % to %', OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'RETURNED' AND m.reversed_by IS NULL THEN
      RAISE EXCEPTION 'a gateway transfer is returned once its movement is reversed' USING ERRCODE = '23514';
    END IF;
  ELSIF OLD.status = 'RETURNED' THEN
    RAISE EXCEPTION 'a returned gateway transfer never changes' USING ERRCODE = '42501';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER gateway_transfer_guard BEFORE INSERT OR UPDATE OR DELETE ON deposits.gateway_transfer
  FOR EACH ROW EXECUTE FUNCTION deposits.guard_gateway_transfer();
CREATE TRIGGER gateway_transfer_no_truncate BEFORE TRUNCATE ON deposits.gateway_transfer
  FOR EACH STATEMENT EXECUTE FUNCTION deposits.forbid_change();

-- ---------------------------------------------------------------------------------------------------------
-- 2. Funding a term deposit through a payment link
-- ---------------------------------------------------------------------------------------------------------
DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'deposits.term_deposit'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%funding_source%' AND pg_get_constraintdef(oid) LIKE '%BANK_TRANSFER%' LOOP
    EXECUTE format('ALTER TABLE deposits.term_deposit DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE deposits.term_deposit
  ADD CONSTRAINT term_deposit_funding_source CHECK (funding_source IN ('ACCOUNT','CASH','BANK_TRANSFER','GATEWAY'));

-- ---------------------------------------------------------------------------------------------------------
-- 3. Integration tables: a loan or a deposit account
-- ---------------------------------------------------------------------------------------------------------
-- The payee of a deposit transfer, sealed with the integration key like a loan's beneficiary. One per transfer.
ALTER TABLE integration.beneficiary
  ALTER COLUMN loan_id DROP NOT NULL,
  ADD COLUMN deposit_account_id uuid REFERENCES deposits.account(id),
  ADD CONSTRAINT beneficiary_loan_or_deposit CHECK ((loan_id IS NULL) <> (deposit_account_id IS NULL));
DROP TRIGGER beneficiary_guard ON integration.beneficiary;
CREATE TRIGGER beneficiary_guard BEFORE UPDATE OR DELETE ON integration.beneficiary
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('-', 'loan_id', 'deposit_account_id', 'customer_id', 'holder_name_cipher',
                                                      'account_cipher', 'account_last4', 'account_hash', 'ifsc', 'created_by', 'created_at');

-- A payout pays a loan's disbursement or a deposit account's transfer out (one payout per transfer).
ALTER TABLE integration.payout_instruction
  ALTER COLUMN loan_id DROP NOT NULL,
  ALTER COLUMN loan_no DROP NOT NULL,
  ADD COLUMN deposit_account_id  uuid REFERENCES deposits.account(id),
  ADD COLUMN deposit_movement_id uuid UNIQUE REFERENCES deposits.movement(id),
  ADD COLUMN account_no          text,
  ADD COLUMN deposit_settled_at  timestamptz,          -- the deposits module cleared the in-transit head (SUCCESS)
  ADD CONSTRAINT payout_loan_or_deposit CHECK ((loan_id IS NULL) <> (deposit_account_id IS NULL)
                                               AND (loan_id IS NULL) = (loan_no IS NULL)
                                               AND (deposit_account_id IS NULL) = (deposit_movement_id IS NULL)
                                               AND (deposit_account_id IS NULL) = (account_no IS NULL)),
  ADD CONSTRAINT payout_deposit_settled CHECK (deposit_settled_at IS NULL OR (deposit_account_id IS NOT NULL AND status IN ('SUCCESS','RETURNED')));
DROP INDEX integration.payout_one_live;
CREATE UNIQUE INDEX payout_one_live
    ON integration.payout_instruction (loan_id, coalesce(disbursement_txn, '00000000-0000-0000-0000-000000000000'::uuid))
 WHERE status IN ('INITIATED','ON_HOLD','SENT','SUCCESS') AND loan_id IS NOT NULL;
CREATE INDEX payout_deposit_account ON integration.payout_instruction (deposit_account_id) WHERE deposit_account_id IS NOT NULL;
DROP TRIGGER payout_guard ON integration.payout_instruction;
CREATE TRIGGER payout_guard BEFORE UPDATE OR DELETE ON integration.payout_instruction
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('PAYOUT', 'reference', 'loan_id', 'loan_no', 'deposit_account_id',
                                                      'deposit_movement_id', 'account_no', 'customer_id', 'attempt_no', 'amount', 'stp',
                                                      'created_at');

-- A payment link for a loan, or for a deposit account: CREDIT (a savings or current account) or FUNDING (a booked
-- term deposit, for its principal).
ALTER TABLE integration.collection_order
  ALTER COLUMN loan_id DROP NOT NULL,
  ALTER COLUMN loan_no DROP NOT NULL,
  ADD COLUMN deposit_account_id uuid REFERENCES deposits.account(id),
  ADD COLUMN account_no         text,
  ADD COLUMN purpose            text NOT NULL DEFAULT 'REPAYMENT' CHECK (purpose IN ('REPAYMENT','CREDIT','FUNDING')),
  ADD CONSTRAINT collection_order_loan_or_deposit CHECK ((loan_id IS NULL) <> (deposit_account_id IS NULL)
                                                         AND (loan_id IS NULL) = (loan_no IS NULL)
                                                         AND (deposit_account_id IS NULL) = (account_no IS NULL)
                                                         AND (purpose = 'REPAYMENT') = (loan_id IS NOT NULL));
CREATE INDEX collection_order_deposit ON integration.collection_order (deposit_account_id, created_at DESC) WHERE deposit_account_id IS NOT NULL;
-- a term deposit has one live payment link at a time: it is funded once
CREATE UNIQUE INDEX collection_order_one_funding ON integration.collection_order (deposit_account_id)
 WHERE purpose = 'FUNDING' AND status = 'CREATED';
DROP TRIGGER collection_order_guard ON integration.collection_order;
CREATE TRIGGER collection_order_guard BEFORE UPDATE OR DELETE ON integration.collection_order
  FOR EACH ROW EXECUTE FUNCTION integration.guard_row('ORDER', 'reference', 'loan_id', 'loan_no', 'deposit_account_id', 'account_no',
                                                      'purpose', 'amount', 'provider', 'created_by', 'created_at');

-- A payment is posted to a loan (a repayment) or to a deposit account (a movement).
ALTER TABLE integration.collection_payment
  ADD COLUMN deposit_account_id  uuid REFERENCES deposits.account(id),
  ADD COLUMN deposit_movement_id uuid UNIQUE REFERENCES deposits.movement(id);
DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'integration.collection_payment'::regclass AND contype = 'c'
              AND (pg_get_constraintdef(oid) LIKE '%loan_txn_id IS NOT NULL%' OR pg_get_constraintdef(oid) LIKE '%loan_id IS NOT NULL%') LOOP
    EXECUTE format('ALTER TABLE integration.collection_payment DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE integration.collection_payment
  ADD CONSTRAINT collection_payment_one_target CHECK (loan_id IS NULL OR deposit_account_id IS NULL),
  ADD CONSTRAINT collection_payment_posted CHECK ((status = 'POSTED') = (loan_txn_id IS NOT NULL OR deposit_movement_id IS NOT NULL)
                                                  AND (loan_txn_id IS NULL OR deposit_movement_id IS NULL)
                                                  AND (loan_txn_id IS NULL OR loan_id IS NOT NULL)
                                                  AND (deposit_movement_id IS NULL OR deposit_account_id IS NOT NULL)),
  ADD CONSTRAINT collection_payment_posted_value_date CHECK (status <> 'POSTED' OR value_date IS NOT NULL);

-- Reconciliation: the same view, with the deposit account a payment went to (new columns at the end).
CREATE OR REPLACE VIEW integration.collection_reconciliation AS
  SELECT coalesce(p.provider, s.provider) AS provider,
         coalesce(p.provider_payment_id, s.provider_payment_id) AS provider_payment_id,
         p.id AS payment_id, p.loan_id, l.loan_no, coalesce(l.branch_code, d.branch_code) AS branch_code, p.status AS payment_status,
         p.amount AS payment_amount, p.paid_at, p.value_date, p.loan_txn_id, p.review, p.last_error,
         s.amount AS settled_amount, s.fee AS settlement_fee, s.settled_on, s.utr AS settlement_utr,
         CASE WHEN p.id IS NULL THEN 'SETTLED_NOT_RECEIVED'
              WHEN p.status = 'REFUND_DUE' THEN 'REFUND_DUE'
              WHEN p.status IN ('RECEIVED','UNMATCHED') THEN 'PAYMENT_NOT_POSTED'
              WHEN s.id IS NULL THEN 'POSTED_NOT_SETTLED'
              WHEN s.amount <> p.amount THEN 'AMOUNT_MISMATCH'
              ELSE 'MATCHED' END AS category,
         p.deposit_account_id, d.account_no, p.deposit_movement_id
    FROM (SELECT * FROM integration.collection_payment WHERE status <> 'FAILED') p
    FULL JOIN integration.gateway_settlement s
           ON s.provider = p.provider AND s.provider_payment_id = p.provider_payment_id
    LEFT JOIN lending.loan_account l ON l.id = p.loan_id
    LEFT JOIN deposits.account d ON d.id = p.deposit_account_id;
