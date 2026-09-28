# Runbook: tenant onboarding

Audience: platform operations. Every step that changes production goes through a reviewed PR
(Terraform) or a ticketed operator action recorded in `control.operator_action` (reason required).

Example tenant: code `acme-finance`, NBFC, POOLED tier, GROWTH edition, prod (ap-south-1) with DR
(ap-south-2).

## 0. Pre-checks

- [ ] Signed agreement incl. RBI outsourcing obligations and DPDP data-processing terms.
- [ ] Tenant code chosen: `^[a-z][a-z0-9-]{2,30}$`, never reused (it names the DB, realm, key alias and S3 prefix).
- [ ] Tier (POOLED / DEDICATED / STANDALONE) and edition agreed. STANDALONE installs skip steps 2–4 and
      use the Helm chart on the customer's cluster (`values-standalone.yaml`).
- [ ] Identity: built-in login with TOTP, or federation to the tenant's IdP (AD / Entra ID) — collect metadata.

## 1. Register in the control plane (status PROVISIONING)

```sql
INSERT INTO control.tenant (id, code, legal_name, entity_type, deployment_tier, edition_code, status)
VALUES (gen_random_uuid(), 'acme-finance', 'Acme Finance Limited', 'NBFC', 'POOLED', 'GROWTH', 'PROVISIONING');

INSERT INTO control.tenant_module (tenant_id, module_code, enabled)
SELECT t.id, em.module_code, true FROM control.tenant t
JOIN control.edition_module em ON em.edition_code = t.edition_code
WHERE t.code = 'acme-finance';

INSERT INTO control.operator_action (tenant_id, operator, action, reason)
SELECT id, '<your id>', 'ONBOARD_START', '<ticket>' FROM control.tenant WHERE code = 'acme-finance';
```

PROVISIONING tenants are ignored by the backend until step 7.

## 2. Cloud resources (Terraform `modules/tenant`)

Add the tenant to `infra/terraform/envs/prod/terraform.tfvars`:

```hcl
tenants = {
  "acme-finance" = {
    attach_policy_to_app_role = true
  }
}
```

Open a PR; after review: `terraform plan` / `apply` in `envs/prod`. This creates:

| Resource | Purpose |
|----------|---------|
| KMS key `alias/corebanking-prod/tenant/acme-finance` (multi-Region, rotation on) | PII field encryption (ADR-013), secret + document encryption |
| Secret `corebanking/prod/tenants/acme-finance/db` | generated DB credentials + JDBC URL (`sslmode=verify-full`) |
| IAM policy `corebanking-prod-tenant-acme-finance` | backend access to that key, secret and `tenants/acme-finance/` in the documents bucket |

Record the outputs: `terraform output -json tenants`.

> Pooled tier: each tenant attaches one managed policy to the backend role; IAM caps managed policies
> per role (default 10, max 20). Beyond ~15 pooled tenants switch to tag-based (ABAC) policies.

## 3. DR copies

1. In `envs/dr/terraform.tfvars` add `tenant_key_replicas = { "acme-finance" = "<prod kms_key_arn>" }`; apply `envs/dr`.
2. In prod tfvars set `secret_replica_kms_key_arn = "<dr tenant_key_replica_arns[acme-finance]>"`; apply `envs/prod`.
   The DB secret now replicates to ap-south-2. The database itself is replicated by the Aurora global cluster.

## 4. Create the tenant database

From the provisioning job / SSM session inside the VPC, as the Aurora master user (master secret:
`terraform output` → `aurora_master_secret_arn`). Take the password from the tenant secret; do not
paste it into tickets or shell history (`read -s TENANT_PW`, then
`psql -v tenant_pw="$TENANT_PW" ...`).

```sql
CREATE ROLE tenant_acme_finance LOGIN PASSWORD :'tenant_pw';
CREATE DATABASE tenant_acme_finance OWNER tenant_acme_finance;
REVOKE ALL ON DATABASE tenant_acme_finance FROM PUBLIC;
\c tenant_acme_finance
CREATE EXTENSION IF NOT EXISTS pgaudit;
```

Schemas are **not** created here: the backend's `TenantMigrator` runs the Flyway `tenant/` migrations.

## 5. Keycloak realm

```bash
python3 infra/keycloak/new-tenant-realm.py --tenant acme-finance \
  --console-url https://acme-finance.console.<domain> --display-name "Acme Finance Limited" \
  --out /tmp/acme-finance-realm.json
```

The renderer keeps the security baseline (password policy, lockout after 5 failures, TOTP, 5-min
access tokens, 30-min idle sessions), sets the `tenant` claim and redirect URIs, and strips demo users.

1. Import: Admin console → Create realm → upload the file (or `kcadm.sh create realms -f ...`).
2. Rotate the `corebanking-service` client secret (Clients → Credentials → Regenerate) and store it
   in Secrets Manager next to the tenant secret. Never leave `change-me`.
3. Federation (if any): add the tenant IdP under Identity providers; map its groups to MAKER /
   CHECKER / OPERATIONS / AUDITOR / TENANT_ADMIN. Keep MFA enforced.
4. Create the tenant's first TENANT_ADMIN user with a temporary password delivered out of band.
   Delete `/tmp/acme-finance-realm.json`.

## 6. Backend credentials

Current code (`TenancyConfig.envCredentials`) reads tenant DB credentials from environment variables
`COREBANKING_TENANT_ACME_FINANCE_URL / _USER / _PASSWORD`. Sync them from the tenant secret into the
Kubernetes Secret named in the chart's `existingSecret` (External Secrets Operator), e.g. keys
`jdbcUrl`, `username`, `password` → those three variables. A Secrets Manager-backed
`TenantCredentials` (secret ARN from `control.tenant.db_secret_arn`) is planned and will remove this step.

> The backend accepts tokens from any realm under `COREBANKING_OIDC_ISSUER_PREFIX` and requires the realm name
> to equal the token's `tenant` claim, so a new tenant can log in as soon as its realm exists. Nothing needs to
> be redeployed.

## 7. Activate

```sql
UPDATE control.tenant
SET db_secret_arn = '<db_secret_arn>', kms_key_arn = '<kms_key_arn>', status = 'ACTIVE'
WHERE code = 'acme-finance';
```

Then `kubectl -n corebanking rollout restart deploy/corebanking`. On start the backend migrates the
new tenant database; a failing tenant is logged and kept out of rotation, other tenants start normally.

## 8. Verify

- [ ] Backend logs: Flyway `tenant/` migrations applied for `acme-finance`, no errors.
- [ ] `SELECT count(*) FROM flyway_schema_history` (schema `platform`) in the tenant DB matches the release.
- [ ] Login as the tenant admin: password change + TOTP enrolment forced; token has `tenant=acme-finance`,
      `aud` contains `api`.
- [ ] A test upload lands under `tenants/acme-finance/` encrypted with the tenant key (`aws s3api head-object`).
- [ ] DR: secret visible in ap-south-2; key replica enabled.
- [ ] `control.operator_action` row `ONBOARD_DONE` with ticket reference.

## Off-boarding (outline)

Set status `OFFBOARDING` → export data for the tenant (agreed format) → retain per RBI record-keeping
rules (KYC and transaction records: minimum 5 years after the relationship ends) → drop the database →
schedule deletion of the tenant key (crypto-shredding of remaining backups/documents) after the
retention period → remove the realm → status `CLOSED`.
