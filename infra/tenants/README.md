# Tenant specs (US-001: provision a tenant in one action)

Each hosted tenant has one reviewed file: `infra/tenants/<environment>/<code>.json`
(template: `tools/tenant/tenant-spec.example.json`). **Merging the pull request that adds the file is the
one action.** The `provision-tenant` workflow then runs `tools/tenant/provision_tenant.py`, which performs
every step of `docs/runbooks/tenant-onboarding.md`:

| Step | What happens |
|------|--------------|
| validate | Spec checks: code, tier, edition, head office (GST state code), admins, https URLs. |
| terraform | `tenants.auto.tfvars.json` is generated from **all** spec files of the environment, then plan + apply (KMS key, DB secret, IAM policy). |
| database | Tenant role and database on Aurora (TLS verify-full), `pgaudit`. Idempotent. |
| realm | Keycloak realm from the security baseline, integration-client secret rotated into Secrets Manager, first TENANT_ADMIN users with a temporary password (stored in Secrets Manager `…/initial-admins` for out-of-band delivery; never printed) and forced password change + TOTP. |
| credentials | Tenant added to the Helm value `tenantDbSecrets.tenants`; External Secrets sync; rolling restart. |
| control-plane | `POST /platform/v1/tenants`: control-plane row, migrations, starter kit, first staff profiles (admins and the integration client see every branch). |
| verify | Tenant is ACTIVE with its edition's modules. |

A failed run is re-run from the Actions page (optionally from a step); every step is safe to repeat.

Rules:

- The file name is the tenant code and the folder is the environment; the tool rejects anything else.
- No secrets in spec files. Admin e-mails are work addresses of the tenant's IT administrators.
- `STANDALONE` tenants are listed for the realm and control-plane steps only; their infrastructure is the bank's.
- Removing a file does **not** off-board a tenant (see the off-boarding section of the runbook).
- The environment's Terraform `tenants` variable comes only from these files; do not also set it in `terraform.tfvars`.
