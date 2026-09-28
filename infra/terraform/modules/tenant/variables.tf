variable "tenant_code" {
  description = "Tenant code as in control.tenant.code, e.g. acme-finance."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,30}$", var.tenant_code))
    error_message = "tenant_code must match ^[a-z][a-z0-9-]{2,30}$ (same rule as TenantContext and control.tenant)."
  }
}

variable "environment" {
  description = "Environment name (sandbox, uat, prod)."
  type        = string
}

variable "name_prefix" {
  description = "Resource name prefix, e.g. corebanking-prod."
  type        = string
}

variable "kms_admin_principal_arns" {
  description = "Principals allowed to administer the tenant key."
  type        = list(string)
  default     = []
}

variable "app_role_arn" {
  description = "IAM role of the CoreBanking backend (IRSA/Pod Identity) that uses the tenant key, secret and documents."
  type        = string
  default     = null
}

variable "attach_policy_to_app_role" {
  description = "Attach the tenant policy to app_role_name. Mind the IAM limit on managed policies per role for pooled deployments with many tenants."
  type        = bool
  default     = false
}

variable "app_role_name" {
  description = "Name of the backend IAM role, when attach_policy_to_app_role is true."
  type        = string
  default     = null
}

variable "db_host" {
  description = "Writer endpoint of the Aurora cluster that hosts this tenant's database."
  type        = string
}

variable "db_port" {
  description = "Database port."
  type        = number
  default     = 5432
}

variable "db_cluster_resource_id" {
  description = "Aurora cluster resource ID; when set, the tenant policy allows IAM database authentication as the tenant user."
  type        = string
  default     = null
}

variable "documents_bucket_arn" {
  description = "ARN of the environment's documents bucket (s3-documents module)."
  type        = string
}

variable "multi_region_key" {
  description = "Create the tenant key as multi-Region so the DR env can replicate it to ap-south-2."
  type        = bool
  default     = true
}

variable "secret_replica_region" {
  description = "Optional DR region to replicate the DB secret to (e.g. ap-south-2). Requires secret_replica_kms_key_arn."
  type        = string
  default     = null
}

variable "secret_replica_kms_key_arn" {
  description = "Tenant key replica ARN in the DR region, used to encrypt the replicated secret."
  type        = string
  default     = null
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}
