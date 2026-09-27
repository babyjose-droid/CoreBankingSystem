# ADR-010: Build performed by Claude with human review of every merge

Status: Accepted  
Date: 2026-09-27

## Context
The product owner is building with Claude rather than a team.

## Decision
Claude writes code, tests and docs. Every merge needs a green CI run and review by a named human (banking developer or CA for finance logic). ADRs and docs are updated in the same PR as the code.

## Consequences
Single-builder risk is reduced by CI as the source of truth, ADRs, and a human technical owner.
