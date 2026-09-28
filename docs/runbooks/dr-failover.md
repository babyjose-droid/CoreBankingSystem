# Runbook: DR failover to ap-south-2 (Hyderabad)

Audience: platform on-call + incident commander. Primary: ap-south-1 (Mumbai). DR: ap-south-2
(Hyderabad). Both regions are in India, so failover keeps data within RBI data-localisation rules.

## Targets (to be confirmed with the business and each tenant's BCP)

| Metric | Target | Basis |
|--------|--------|-------|
| RPO | **15 minutes** | Aurora global database replication lag is typically < 1 s; 15 min covers lag spikes and documents |
| RTO | **4 hours** | warm-standby EKS in DR, IaC-provisioned; includes decision time |

Both are *targets to confirm*: validate them in the half-yearly DR drill (RBI expects periodic DR
drills with results reported to the board/IT strategy committee) and record the achieved numbers.

## What is already in DR (steady state)

| Component | DR state | Source |
|-----------|----------|--------|
| Aurora | secondary cluster in the global database, read-only | `envs/dr` (`aurora_global_cluster_identifier`) |
| Tenant keys | multi-Region replicas | `envs/dr` `tenant_key_replicas` |
| Tenant DB secrets | Secrets Manager replicas | prod `tenants[*].secret_replica_kms_key_arn` |
| EKS | cluster with a small node group, no traffic | `envs/dr` |
| Images | TBD: ECR replication rule prod → DR | open item |
| Documents (S3) | TBD: cross-region replication prod → DR bucket | open item (checkov CKV_AWS_144) |
| Keycloak | TBD: depends on the ADR-007 hosting decision | open item |

## Decision

Fail over only when the incident commander declares a regional outage or prolonged unavailability
of a critical dependency in ap-south-1 that exceeds (RTO − failover time). Record the decision time.
Two options:

- **Managed switchover** (primary healthy, planned drill/migration): zero data loss, Aurora
  `switchover-global-cluster`.
- **Failover** (primary unavailable): possible data loss up to the replication lag, Aurora
  `failover-global-cluster --allow-data-loss`.

## Procedure

### 1. Freeze (T+0)

- [ ] Announce the incident; freeze deployments and Terraform applies to prod.
- [ ] If the primary is still reachable: stop EOD and batch jobs; scale the prod backend to 0 to stop writes.
- [ ] Note the Aurora replication lag (`AuroraGlobalDBReplicationLag`, `AuroraGlobalDBRPOLag`) — this is the RPO achieved.

### 2. Promote the database

```bash
# Planned switchover (no data loss):
aws rds switchover-global-cluster --region ap-south-2 \
  --global-cluster-identifier <global-id> --target-db-cluster-identifier <dr-cluster-arn>

# Unplanned failover (primary region down):
aws rds failover-global-cluster --region ap-south-2 \
  --global-cluster-identifier <global-id> --target-db-cluster-identifier <dr-cluster-arn> \
  --allow-data-loss
```

Wait until the DR cluster is `available` and writer. Add a DR reader instance if load needs it.

### 3. Application stack in DR

- [ ] Scale the DR node group (`node_desired_size`) to the prod size.
- [ ] Point tenant JDBC URLs to the DR writer endpoint (update the replicated secrets / ExternalSecret
      and the control-plane URL), keep `sslmode=verify-full`.
- [ ] Deploy the same chart version and image digest as prod with DR values (IRSA role from
      `envs/dr` output `backend_role_arn`, DB CIDRs from `database_subnet_cidrs`).
- [ ] Keycloak available in DR with the same realms (TBD).
- [ ] Wait for readiness: all ACTIVE tenants migrated/validated (no pending Flyway migrations expected).

### 4. Traffic

- [ ] Switch DNS / global load balancer to the DR ingress (low TTL records prepared in advance).
- [ ] Update partner allow-lists with DR NAT IPs (`nat_public_ips` output) — bureaus, payment
      aggregators, NACH/UPI sponsors. Prepare these in advance; some partners need days.

### 5. Verify

- [ ] Login + TOTP works for each tenant; `tenant` claim routes to the right DB.
- [ ] Trial balance per tenant matches the last known good figures (EOD reports) — any gap equals lost
      transactions within RPO; reconcile with channel/partner logs.
- [ ] Resume EOD for the current business date (see `eod-operations.md`).
- [ ] Record: decision time, promotion done, traffic switched, first successful transaction → achieved RTO.

### 6. Communicate

- Tenants: status updates at a fixed cadence. Tenants handle their own RBI incident reporting;
  CERT-In reporting within 6 hours where applicable.

## Failback

After ap-south-1 recovers: rebuild the old primary as a secondary of the global cluster (Terraform
apply of `envs/prod` with the cluster joining as secondary, or AWS re-adds it after switchover),
let it catch up, then run a **managed switchover** back in a maintenance window following the same
steps. Restore the prod/DR Terraform settings afterwards so state matches reality.

## Drill checklist (half-yearly)

- [ ] Managed switchover to DR and back in a sandbox/UAT copy, then in prod within a maintenance window.
- [ ] Measure RTO/RPO; file the report; open items for every deviation.
- [ ] Verify backups: restore a tenant DB from a 30-day-old point in time into an isolated account.
