-- One database per tenant (ADR-003); one schema per module (ADR-003).
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS customer;
CREATE SCHEMA IF NOT EXISTS lending;
CREATE SCHEMA IF NOT EXISTS audit;

-- Money is always NUMERIC(20,4); never float (ADR-011).
CREATE DOMAIN platform.money AS numeric(20,4);
CREATE DOMAIN platform.rate  AS numeric(9,6);
