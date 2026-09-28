variable "name_prefix" {
  description = "Prefix for key aliases, e.g. corebanking-prod. Aliases become alias/<name_prefix>/<key>."
  type        = string
}

variable "keys" {
  description = <<-EOT
    Map of customer managed keys to create, keyed by a short name (e.g. a tenant code).
    user_principal_arns: IAM principals allowed to use the key for encrypt/decrypt/data keys.
    allow_cloudwatch_logs: let CloudWatch Logs in this account/region encrypt log groups with the key.
    via_services: when set, principal usage is limited to calls made through these services
    (kms:ViaService, e.g. secretsmanager.ap-south-1.amazonaws.com).
  EOT
  type = map(object({
    description           = string
    user_principal_arns   = optional(list(string), [])
    via_services          = optional(list(string), [])
    allow_cloudwatch_logs = optional(bool, false)
  }))
}

variable "admin_principal_arns" {
  description = "IAM principals allowed to administer (but not use) the keys. The account root is always included so IAM policies work."
  type        = list(string)
  default     = []
}

variable "multi_region" {
  description = "Create multi-Region primary keys so they can be replicated to the DR region (ap-south-2) with aws_kms_replica_key."
  type        = bool
  default     = true
}

variable "deletion_window_in_days" {
  description = "Waiting period before a scheduled key deletion takes effect."
  type        = number
  default     = 30

  validation {
    condition     = var.deletion_window_in_days >= 7 && var.deletion_window_in_days <= 30
    error_message = "deletion_window_in_days must be between 7 and 30."
  }
}

variable "rotation_period_in_days" {
  description = "Automatic key rotation period."
  type        = number
  default     = 365
}

variable "tags" {
  description = "Extra tags for every key."
  type        = map(string)
  default     = {}
}
