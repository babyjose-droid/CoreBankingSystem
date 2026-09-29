-- Enumeration types are lower-case kebab names (gst-state, customer-type, voucher-type), as the console and the
-- customer and ledger forms already use them. V13 wrongly required upper-case; no tenant holds upper-case types yet.
ALTER TABLE platform.enumeration DROP CONSTRAINT enumeration_type_format;
ALTER TABLE platform.enumeration ADD CONSTRAINT enumeration_type_format CHECK (enum_type ~ '^[a-z][a-z0-9-]{1,40}$');
