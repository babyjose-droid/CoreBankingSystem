output "tenant_code" {
  description = "Tenant code."
  value       = var.tenant_code
}

output "kms_key_arn" {
  description = "Tenant CMK ARN -> control.tenant.kms_key_arn."
  value       = module.key.key_arns[var.tenant_code]
}

output "db_secret_arn" {
  description = "Secrets Manager ARN of the tenant DB credentials -> control.tenant.db_secret_arn."
  value       = aws_secretsmanager_secret.db.arn
}

output "db_name" {
  description = "Database name to create for the tenant."
  value       = local.db_identifier
}

output "db_username" {
  description = "Database role to create for the tenant (owner of the tenant database)."
  value       = local.db_identifier
}

output "s3_prefix" {
  description = "Tenant prefix in the documents bucket."
  value       = local.s3_prefix
}

output "iam_policy_arn" {
  description = "IAM policy granting the backend access to this tenant's resources."
  value       = aws_iam_policy.tenant.arn
}
