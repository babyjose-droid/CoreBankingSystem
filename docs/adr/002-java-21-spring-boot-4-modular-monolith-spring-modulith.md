# ADR-002: Java 21 + Spring Boot 4 modular monolith (Spring Modulith)

Status: Accepted (amended)  
Date: 2026-09-27

## Context
The SAD v1.0 named Spring Boot 3. By the start of Phase 1 (Sep 2026) Spring Boot 4.1 and Spring Modulith 2.1 are the supported lines.

## Decision
Java 21, Spring Boot 4.1, Spring Modulith 2.1. One deployable. Each top-level package under com.corebanking is a module; ModularityTest fails the build on boundary violations or cycles. Pure libraries (calc, ledger-core) have no Spring dependency.

## Consequences
Simple to run, test and ship for a single builder. Modules can be split into services later if a customer or scale requires it, because boundaries are already enforced.
