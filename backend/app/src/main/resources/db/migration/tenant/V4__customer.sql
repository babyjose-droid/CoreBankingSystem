-- Customer master. PII columns are encrypted at the application layer (ADR-013); the database only
-- ever sees ciphertext plus a keyed hash for exact-match search.
CREATE TABLE customer.customer (
    id              uuid PRIMARY KEY,
    customer_no     text NOT NULL UNIQUE,                 -- from number series CUSTOMER
    customer_type   text NOT NULL CHECK (customer_type IN ('INDIVIDUAL','NON_INDIVIDUAL')),
    display_name    text NOT NULL,
    date_of_birth   date,
    pan_cipher      bytea,
    pan_hash        bytea,                                -- HMAC for dedupe / search
    mobile_cipher   bytea,
    mobile_hash     bytea,
    email_cipher    bytea,
    ckyc_no         text,
    kyc_status      text NOT NULL DEFAULT 'PENDING' CHECK (kyc_status IN ('PENDING','VERIFIED','EXPIRED','REJECTED')),
    risk_category   text NOT NULL DEFAULT 'LOW' CHECK (risk_category IN ('LOW','MEDIUM','HIGH')),
    home_branch     text NOT NULL REFERENCES platform.branch(code),
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DORMANT','BLOCKED','CLOSED','ERASED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    version         int NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX customer_pan_unique ON customer.customer (pan_hash) WHERE pan_hash IS NOT NULL AND status <> 'ERASED';
CREATE INDEX customer_mobile ON customer.customer (mobile_hash);

CREATE TABLE customer.address (
    customer_id   uuid NOT NULL REFERENCES customer.customer(id),
    address_type  text NOT NULL CHECK (address_type IN ('PERMANENT','COMMUNICATION','OFFICE')),
    line_cipher   bytea NOT NULL,
    city          text NOT NULL,
    state_code    text NOT NULL,
    pincode       text NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    PRIMARY KEY (customer_id, address_type)
);

-- DPDP Act: consent per purpose, with withdrawal.
CREATE TABLE customer.consent (
    id            uuid PRIMARY KEY,
    customer_id   uuid NOT NULL REFERENCES customer.customer(id),
    purpose       text NOT NULL,                          -- KYC | CREDIT_BUREAU | MARKETING | ACCOUNT_AGGREGATOR
    notice_version text NOT NULL,
    given_at      timestamptz NOT NULL,
    withdrawn_at  timestamptz,
    channel       text NOT NULL
);
CREATE INDEX consent_active ON customer.consent (customer_id, purpose) WHERE withdrawn_at IS NULL;

CREATE TABLE customer.document (
    id            uuid PRIMARY KEY,
    customer_id   uuid NOT NULL REFERENCES customer.customer(id),
    doc_type      text NOT NULL,
    s3_key        text NOT NULL,                          -- object in the tenant's encrypted bucket
    sha256        text NOT NULL,
    uploaded_at   timestamptz NOT NULL DEFAULT now()
);
