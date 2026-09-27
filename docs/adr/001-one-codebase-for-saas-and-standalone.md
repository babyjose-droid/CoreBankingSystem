# ADR-001: One codebase for SaaS and standalone

Status: Accepted  
Date: 2026-09-27

## Context
CoreBanking is sold to NBFCs and banks either as multi-tenant SaaS (pooled or dedicated) or as a standalone install in the customer's own cloud or data centre.

## Decision
One codebase and one release train. The deployment tier is configuration (control.tenant.deployment_tier), never a fork. Standalone ships as a Helm chart plus Terraform modules; the control plane runs in a single-tenant mode.

## Consequences
Every feature must work without AWS-only services behind an interface (S3, SQS, Secrets Manager have adapters). Slightly more abstraction up front; no divergent branches later.
