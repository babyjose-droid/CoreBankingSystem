variable "name" {
  description = "Cluster identifier, e.g. corebanking-prod-pooled-1."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID."
  type        = string
}

variable "db_subnet_group_name" {
  description = "DB subnet group (isolated database subnets)."
  type        = string
}

variable "allowed_security_group_ids" {
  description = "Security groups allowed to connect on 5432 (e.g. the EKS cluster security group)."
  type        = list(string)
  default     = []
}

variable "allowed_cidr_blocks" {
  description = "CIDRs allowed to connect on 5432 (e.g. a bastion/SSM subnet). Keep empty where possible."
  type        = list(string)
  default     = []
}

variable "kms_key_arn" {
  description = "KMS key for storage, Performance Insights and the managed master secret. Must exist in this region."
  type        = string
}

variable "engine_version" {
  description = "Aurora PostgreSQL 16 engine version. Pin explicitly; minor upgrades roll out via auto_minor_version_upgrade in the maintenance window."
  type        = string
  default     = "16.8"

  validation {
    condition     = startswith(var.engine_version, "16.")
    error_message = "CoreBanking targets PostgreSQL 16 (ADR-003)."
  }
}

variable "instance_class" {
  description = "Instance class for writer and readers."
  type        = string
  default     = "db.r7g.large"
}

variable "instance_count" {
  description = "Number of instances (1 writer + readers). Use >= 2 in production for Multi-AZ failover."
  type        = number
  default     = 2
}

variable "master_username" {
  description = "Master username (primary cluster only)."
  type        = string
  default     = "cbadmin"
}

variable "backup_retention_days" {
  description = "Automated backup retention; also the point-in-time-recovery window. 35 is the Aurora maximum."
  type        = number
  default     = 35
}

variable "preferred_backup_window" {
  description = "Daily backup window (UTC). Default 21:30-22:30 UTC = 03:00-04:00 IST, after end-of-day batch."
  type        = string
  default     = "21:30-22:30"
}

variable "preferred_maintenance_window" {
  description = "Weekly maintenance window (UTC). Default Sunday 04:00-05:00 IST."
  type        = string
  default     = "sat:22:30-sat:23:30"
}

variable "performance_insights_retention_days" {
  description = "Performance Insights retention (7 is free; 31-731 billed)."
  type        = number
  default     = 7
}

variable "deletion_protection" {
  description = "Prevent accidental deletion. Only disable for throwaway sandboxes."
  type        = bool
  default     = true
}

variable "create_global_cluster" {
  description = "Create an Aurora global database with this cluster as primary (production: DR replica in ap-south-2)."
  type        = bool
  default     = false
}

variable "global_cluster_identifier" {
  description = "Join an existing global cluster as a secondary (DR region). Leave null for a standalone or primary cluster."
  type        = string
  default     = null
}

variable "iam_auth_enabled" {
  description = "Enable IAM database authentication (short-lived tokens instead of passwords for operators/services)."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}
