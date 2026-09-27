# ADR-012: Generic maker-checker

Status: Accepted  
Date: 2026-09-27

## Context
Every financial and master-data change needs four-eyes approval, and the reference system implemented this per screen.

## Decision
One platform.approval_request table and service: the proposed change is stored as JSON; a different user approves or rejects; the database forbids maker = checker. Modules register an applier per entity type.

## Consequences
Uniform approvals queue and audit; appliers must be idempotent.
