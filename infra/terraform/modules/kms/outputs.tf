output "key_arns" {
  description = "Key ARNs by key name."
  value       = { for k, v in aws_kms_key.this : k => v.arn }
}

output "key_ids" {
  description = "Key IDs by key name."
  value       = { for k, v in aws_kms_key.this : k => v.key_id }
}

output "alias_names" {
  description = "Alias names by key name."
  value       = { for k, v in aws_kms_alias.this : k => v.name }
}
