-- Customer create and dedupe (US-028, US-029, US-030). PII stays encrypted (ADR-013); dedupe compares the
-- keyed hashes (blind indexes) the application computes with the tenant's index key.
ALTER TABLE customer.customer
  ADD COLUMN first_name_cipher  bytea,
  ADD COLUMN middle_name_cipher bytea,
  ADD COLUMN last_name_cipher   bytea,
  ADD COLUMN gender             text CHECK (gender IN ('FEMALE','MALE','OTHER')),
  ADD COLUMN name_dob_hash      bytea,
  ADD COLUMN aadhaar_ref_hash   bytea,      -- hash of the Aadhaar reference/vault token, never the number
  ADD COLUMN pan_last4          text CHECK (pan_last4 ~ '^[0-9]{4}$'),        -- for masked display only
  ADD COLUMN mobile_last4       text CHECK (mobile_last4 ~ '^[0-9]{4}$'),
  ADD COLUMN approval_id        uuid REFERENCES platform.approval_request(id),
  ADD COLUMN created_by         text;
CREATE INDEX customer_name_dob ON customer.customer (name_dob_hash);
CREATE UNIQUE INDEX customer_aadhaar_unique ON customer.customer (aadhaar_ref_hash)
  WHERE aadhaar_ref_hash IS NOT NULL AND status <> 'ERASED';

CREATE FUNCTION customer.find_duplicates(p_pan bytea, p_mobile bytea, p_name_dob bytea, p_aadhaar bytea DEFAULT NULL)
RETURNS TABLE (customer_id uuid, customer_no text, display_name text, rule text, strength text)
LANGUAGE sql STABLE AS $$
  SELECT id, customer_no, display_name, rule, strength FROM (
  SELECT id, customer_no, display_name, 'PAN' AS rule, 'EXACT' AS strength FROM customer.customer
   WHERE p_pan IS NOT NULL AND pan_hash = p_pan AND status <> 'ERASED'
  UNION
  SELECT id, customer_no, display_name, 'AADHAAR', 'EXACT' FROM customer.customer
   WHERE p_aadhaar IS NOT NULL AND aadhaar_ref_hash = p_aadhaar AND status <> 'ERASED'
  UNION
  SELECT id, customer_no, display_name, 'MOBILE', 'STRONG' FROM customer.customer
   WHERE p_mobile IS NOT NULL AND mobile_hash = p_mobile AND status <> 'ERASED'
  UNION
  SELECT id, customer_no, display_name, 'NAME_DOB', 'POSSIBLE' FROM customer.customer
   WHERE p_name_dob IS NOT NULL AND name_dob_hash = p_name_dob AND status <> 'ERASED') m
  ORDER BY CASE strength WHEN 'EXACT' THEN 1 WHEN 'STRONG' THEN 2 ELSE 3 END, customer_no
$$;

-- A create that goes ahead despite STRONG/POSSIBLE matches records who overrode and why.
CREATE TABLE customer.dedupe_override (
    approval_id uuid NOT NULL REFERENCES platform.approval_request(id),
    matched_customer_no text NOT NULL,
    rule        text NOT NULL,
    reason      text NOT NULL CHECK (length(trim(reason)) >= 10),
    PRIMARY KEY (approval_id, matched_customer_no, rule)
);
