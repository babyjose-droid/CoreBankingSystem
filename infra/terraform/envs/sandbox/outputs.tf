output "eks_cluster_name" {
  description = "EKS cluster name."
  value       = module.platform.eks_cluster_name
}

output "aurora_writer_endpoint" {
  description = "Aurora writer endpoint."
  value       = module.platform.aurora_writer_endpoint
}

output "aurora_global_cluster_id" {
  description = "Aurora global cluster ID."
  value       = module.platform.aurora_global_cluster_id
}

output "database_subnet_cidrs" {
  description = "Database subnet CIDRs for the Helm chart network policy."
  value       = module.platform.database_subnet_cidrs
}

output "backend_role_arn" {
  description = "Backend IRSA role for the Helm chart."
  value       = module.platform.backend_role_arn
}

output "documents_bucket_name" {
  description = "Documents bucket."
  value       = module.platform.documents_bucket_name
}

output "ecr_repository_url" {
  description = "Backend image repository."
  value       = module.platform.ecr_repository_url
}

output "nat_public_ips" {
  description = "Egress IPs for partner allow-lists."
  value       = module.platform.nat_public_ips
}

output "tenants" {
  description = "Per-tenant resource identifiers for control.tenant."
  value       = module.platform.tenants
}

output "tenant_key_replica_arns" {
  description = "Tenant key replicas in this region (dr)."
  value       = module.platform.tenant_key_replica_arns
}
