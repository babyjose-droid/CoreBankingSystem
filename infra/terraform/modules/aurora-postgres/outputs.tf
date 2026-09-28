output "cluster_id" {
  description = "Cluster identifier."
  value       = aws_rds_cluster.this.id
}

output "cluster_arn" {
  description = "Cluster ARN."
  value       = aws_rds_cluster.this.arn
}

output "cluster_resource_id" {
  description = "Cluster resource ID (used in rds-db:connect IAM policies)."
  value       = aws_rds_cluster.this.cluster_resource_id
}

output "writer_endpoint" {
  description = "Writer endpoint."
  value       = aws_rds_cluster.this.endpoint
}

output "reader_endpoint" {
  description = "Reader endpoint."
  value       = aws_rds_cluster.this.reader_endpoint
}

output "port" {
  description = "Port."
  value       = aws_rds_cluster.this.port
}

output "security_group_id" {
  description = "Security group attached to the cluster."
  value       = aws_security_group.this.id
}

output "global_cluster_id" {
  description = "Global cluster identifier (null when not part of a global database)."
  value       = local.global_cluster_id
}

output "master_secret_arn" {
  description = "Secrets Manager ARN holding the master credentials."
  value       = local.use_managed_secret ? aws_rds_cluster.this.master_user_secret[0].secret_arn : (local.generate_password ? aws_secretsmanager_secret.master[0].arn : null)
}
