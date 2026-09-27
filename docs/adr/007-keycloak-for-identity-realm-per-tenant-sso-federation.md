# ADR-007: Keycloak for identity, realm per tenant, SSO federation

Status: Proposed  
Date: 2026-09-27

## Context
Banks want their own IdP (AD/Azure AD) with MFA; NBFCs often want built-in login with OTP.

## Decision
Keycloak with one realm per tenant; tenant claim in the access token; MFA mandatory for staff; federation to the tenant's IdP where available.

## Consequences
Keycloak must be operated (HA, upgrades) — or replaced by Cognito for pooled SaaS if operations cost is too high. Decision D-06.
