# ADR-007: Keycloak for identity, realm per tenant, SSO federation

Status: Accepted (amended 2026-09-28)  
Date: 2026-09-27

## Context
- Banks want to sign in through their own identity provider, such as AD or Azure AD, with MFA.
- NBFCs often want the product's own login with OTP.
- Phase 1 needs staff login, TOTP, lockout and a password policy (US-018, US-026).

## Decision
- **One Keycloak realm per tenant.** A template realm lives at `deploy/local/keycloak/demo-nbfc-realm.json`, and `infra/keycloak/new-tenant-realm.py` renders it for a new tenant.
- **Keycloak enforces the login rules.** TOTP is required, brute-force lockout is on, and the password policy covers length, complexity, history and expiry. The application does not implement any of these itself.
- **Access tokens carry two claims.**
  - `tenant`: the tenant code.
  - `permissions`: client roles of the `api` client, for example `customer:create`.
- **The backend accepts tokens from any realm under `corebanking.oidc.issuer-prefix`.** It then requires three things:
  - the realm name equals the `tenant` claim;
  - the audience includes `api`;
  - the tenant is ACTIVE in the control plane.

  A token from tenant A can never act on tenant B. A new tenant needs no redeploy.
- **Signing keys can be fetched from an internal URL** (`corebanking.oidc.jwks-base`) while the public issuer is kept for validation.
- **Platform operators use their own realm** named `platform`, with the authority `platform:operator`.

## Consequences
- Keycloak has to be run with high availability and upgraded like any other production service.
- For pooled SaaS, Amazon Cognito remains an option if the cost of operating Keycloak proves too high. That decision is due before the first external tenant goes live.
