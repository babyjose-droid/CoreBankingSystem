# ADR-003: PostgreSQL 16, database per tenant, schema per module

Status: Accepted  
Date: 2026-09-27

## Context
Regulated entities expect strong data isolation, per-tenant encryption keys, per-tenant backup/restore and clean off-boarding.

## Decision
Control-plane database (control schema) holds tenants, editions, entitlements and usage, and no customer data. Each tenant gets its own database (Aurora PostgreSQL in cloud tiers) with schemas platform, ledger, customer, lending, audit (CASA/TD added in Phase 3). Requests are routed by TenantDataSourceRouter from the verified JWT tenant claim.

## Consequences
Higher connection count (one small pool per tenant) — mitigated with RDS Proxy and pool sizing. Migrations run per tenant; a failing tenant is isolated, not fatal.
