# ADR-004: AWS Mumbai primary, Hyderabad DR; Terraform + EKS

Status: Accepted  
Date: 2026-09-27

## Context
RBI data localisation and IT outsourcing directions require data in India and a tested DR capability.

## Decision
ap-south-1 primary, ap-south-2 DR. Infrastructure only via Terraform; workloads on EKS; Aurora global database for tenant DBs.

## Consequences
Two-region cost from day one for production tenants; sandbox/UAT single-region.
