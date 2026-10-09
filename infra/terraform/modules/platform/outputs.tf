output "vpc_id" {
  description = "VPC ID."
  value       = module.network.vpc_id
}

output "database_subnet_cidrs" {
  description = "Database subnet CIDRs -> Helm networkPolicy.database.cidrs."
  value       = module.network.database_subnet_cidrs
}

output "nat_public_ips" {
  description = "Egress IPs for partner allow-lists."
  value       = module.network.nat_public_ips
}

output "platform_kms_key_arn" {
  description = "Platform CMK ARN."
  value       = module.platform_kms.key_arns["platform"]
}

output "eks_cluster_name" {
  description = "EKS cluster name."
  value       = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  description = "EKS private API endpoint."
  value       = module.eks.cluster_endpoint
}

output "aurora_writer_endpoint" {
  description = "Aurora writer endpoint."
  value       = module.aurora.writer_endpoint
}

output "aurora_global_cluster_id" {
  description = "Aurora global cluster identifier (prod: pass to the dr env)."
  value       = module.aurora.global_cluster_id
}

output "aurora_master_secret_arn" {
  description = "Secrets Manager ARN of the Aurora master credentials."
  value       = module.aurora.master_secret_arn
}

output "documents_bucket_name" {
  description = "Documents bucket."
  value       = module.documents.bucket_name
}

output "backend_role_arn" {
  description = "Backend IRSA role ARN -> Helm serviceAccount.irsaRoleArn."
  value       = aws_iam_role.backend.arn
}

output "ecr_repository_url" {
  description = "Backend image repository URL."
  value       = try(aws_ecr_repository.backend[0].repository_url, null)
}

output "tenants" {
  description = "Per-tenant outputs to record in control.tenant (kms_key_arn, db_secret_arn) and use in provisioning."
  value = {
    for code, t in module.tenant : code => {
      kms_key_arn    = t.kms_key_arn
      db_secret_arn  = t.db_secret_arn
      db_name        = t.db_name
      db_username    = t.db_username
      s3_prefix      = t.s3_prefix
      iam_policy_arn = t.iam_policy_arn
    }
  }
}

output "tenant_key_replica_arns" {
  description = "DR tenant key replica ARNs (feed back into prod tenants[*].secret_replica_kms_key_arn)."
  value       = { for code, k in aws_kms_replica_key.tenant : code => k.arn }
}

output "mail_dkim_tokens" {
  description = "SES Easy DKIM tokens to publish in DNS (empty when mail_domain is not set)."
  value       = var.mail_domain == null ? [] : module.mail[0].dkim_tokens
}
