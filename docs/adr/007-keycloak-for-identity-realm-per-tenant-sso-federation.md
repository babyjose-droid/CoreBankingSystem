# ADR-007: Keycloak for identity, realm per tenant, SSO federation

Status: Accepted (amended 2026-09-28, 2026-10-03)  
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

## Amendment (2026-10-03): local-only development sign-in
The console signs in through SSO only, and passwords never touch it. One exception exists for the local
development stack: a "Development sign-in (local only)" form that uses the OAuth password grant, so the stack can
be tested where the redirect to Keycloak does not work (embedded test browsers). MFA, lockout and federation stay
in Keycloak for every real tenant. Guard rails:
- The password grant is enabled on one client only, `console-dev`, which exists only in the local template realm
  together with its `dev-*` users. `infra/keycloak/new-tenant-realm.py` always removes the client and its scope
  mappings from a rendered tenant realm and refuses to render if any client still allows the grant.
- CI (`infra.yml`) asserts that `console-dev` is the only password-grant client in the template, that its token
  contents equal the console's, and that a rendered tenant realm contains neither it nor any password-grant client.
- The console shows the form only with `VITE_DEV_LOGIN=1` on the Vite dev server and outside mock mode; a
  production build compiles the flag to false, and the same-origin `/realms` route it calls exists only on the
  dev server. The sign-in function checks the flag itself.
- The password is read at submit, sent once and never stored or logged. The console rejects a token whose issuer
  is not the configured authority.
- The backend is unchanged: it validates these tokens exactly like SSO tokens.

## Consequences
- Keycloak has to be run with high availability and upgraded like any other production service.
- For pooled SaaS, Amazon Cognito remains an option if the cost of operating Keycloak proves too high. That decision is due before the first external tenant goes live.
