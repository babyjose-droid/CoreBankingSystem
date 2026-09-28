-- Tenant provisioning details and log (US-001, US-005).
ALTER TABLE control.tenant
  ADD COLUMN starter_kit          text NOT NULL DEFAULT 'NBFC' CHECK (starter_kit IN ('NBFC','BANK')),
  ADD COLUMN oidc_realm           text,
  ADD COLUMN provisioned_at       timestamptz;

CREATE TABLE control.provisioning_log (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES control.tenant(id),
    step      text NOT NULL,
    status    text NOT NULL CHECK (status IN ('OK','FAILED','SKIPPED')),
    detail    text,
    at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE control.migration_run (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_by  text NOT NULL,
    dry_run     boolean NOT NULL,
    started_at  timestamptz NOT NULL DEFAULT now(),
    result      jsonb
);
