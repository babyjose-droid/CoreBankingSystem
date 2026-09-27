# ADR-011: Money is BigDecimal in Java and NUMERIC(20,4) in SQL

Status: Accepted  
Date: 2026-09-27

## Context
Floating point cannot represent paise exactly.

## Decision
Never double/float for money or rates. Money columns use the platform.money domain (NUMERIC(20,4)); rates platform.rate (NUMERIC(9,6)). Rounding happens only where the product rule says, via the Rounding enum.

## Consequences
Slightly more verbose code; no reconciliation drift.
