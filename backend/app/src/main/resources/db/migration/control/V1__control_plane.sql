-- Control plane: one database for the whole platform. Holds no customer data (ADR-003).
CREATE SCHEMA IF NOT EXISTS control;

CREATE TABLE control.edition (
    code        text PRIMARY KEY,               -- STARTER | GROWTH | ENTERPRISE
    name        text NOT NULL
);

CREATE TABLE control.module (
    code        text PRIMARY KEY,               -- LENDING | CASA | TD | GL | COLLECTIONS | CO_LENDING ...
    name        text NOT NULL
);

CREATE TABLE control.edition_module (
    edition_code text REFERENCES control.edition(code),
    module_code  text REFERENCES control.module(code),
    PRIMARY KEY (edition_code, module_code)
);

CREATE TABLE control.tenant (
    id              uuid PRIMARY KEY,
    code            text NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z0-9-]{2,30}$'),
    legal_name      text NOT NULL,
    entity_type     text NOT NULL CHECK (entity_type IN ('NBFC','BANK','SFB','COOP_BANK','HFC','MFI')),
    deployment_tier text NOT NULL CHECK (deployment_tier IN ('POOLED','DEDICATED','STANDALONE')),
    edition_code    text NOT NULL REFERENCES control.edition(code),
    region          text NOT NULL DEFAULT 'ap-south-1',
    dr_region       text NOT NULL DEFAULT 'ap-south-2',
    db_secret_arn   text,                        -- pointer to credentials; never the credentials
    kms_key_arn     text,                        -- per-tenant encryption key
    status          text NOT NULL DEFAULT 'PROVISIONING'
                    CHECK (status IN ('PROVISIONING','ACTIVE','SUSPENDED','OFFBOARDING','CLOSED')),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE control.tenant_module (
    tenant_id   uuid REFERENCES control.tenant(id),
    module_code text REFERENCES control.module(code),
    enabled     boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, module_code)
);

CREATE TABLE control.usage_meter (
    tenant_id   uuid NOT NULL REFERENCES control.tenant(id),
    period      date NOT NULL,                   -- first day of month
    metric      text NOT NULL,                   -- ACTIVE_LOANS | ACTIVE_CASA | API_CALLS | USERS
    quantity    bigint NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (tenant_id, period, metric)
);

CREATE TABLE control.operator_action (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   uuid REFERENCES control.tenant(id),
    operator    text NOT NULL,
    action      text NOT NULL,
    reason      text NOT NULL,                   -- support access requires a ticket/reason
    at          timestamptz NOT NULL DEFAULT now()
);

INSERT INTO control.edition VALUES ('STARTER','Starter'),('GROWTH','Growth'),('ENTERPRISE','Enterprise');
INSERT INTO control.module VALUES
 ('LENDING','Loans'),('CASA','Savings and current accounts'),('TD','Term deposits'),('GL','General ledger'),
 ('COLLECTIONS','Collections'),('CO_LENDING','Co-lending'),('REPORTS','Regulatory reports');
INSERT INTO control.edition_module VALUES
 ('STARTER','LENDING'),('STARTER','GL'),('STARTER','REPORTS'),
 ('GROWTH','LENDING'),('GROWTH','GL'),('GROWTH','REPORTS'),('GROWTH','COLLECTIONS'),('GROWTH','CO_LENDING'),
 ('ENTERPRISE','LENDING'),('ENTERPRISE','GL'),('ENTERPRISE','REPORTS'),('ENTERPRISE','COLLECTIONS'),
 ('ENTERPRISE','CO_LENDING'),('ENTERPRISE','CASA'),('ENTERPRISE','TD');
