variable "bucket_name" {
  description = "Globally unique bucket name, e.g. corebanking-prod-documents-<suffix>. Tenants get prefixes tenants/<code>/."
  type        = string
}

variable "kms_key_arn" {
  description = "Default SSE-KMS key for the bucket. Tenant uploads are additionally required (by the tenant IAM policy) to use the tenant's own key."
  type        = string
}

variable "object_lock_enabled" {
  description = "Enable S3 Object Lock (WORM) for regulatory retention of KYC/loan documents. Cannot be disabled after creation."
  type        = bool
  default     = false
}

variable "object_lock_mode" {
  description = "Default retention mode when object lock is enabled: GOVERNANCE or COMPLIANCE."
  type        = string
  default     = "GOVERNANCE"

  validation {
    condition     = contains(["GOVERNANCE", "COMPLIANCE"], var.object_lock_mode)
    error_message = "object_lock_mode must be GOVERNANCE or COMPLIANCE."
  }
}

variable "object_lock_days" {
  description = "Default retention in days when object lock is enabled. RBI KYC master direction: records kept at least 5 years after the relationship ends; the application manages longer holds."
  type        = number
  default     = 1825
}

variable "noncurrent_version_expiration_days" {
  description = "Days after which noncurrent object versions are deleted (ignored while object lock retains them)."
  type        = number
  default     = 2555
}

variable "access_log_bucket" {
  description = "Optional bucket that receives S3 server access logs."
  type        = string
  default     = null
}

variable "force_destroy" {
  description = "Allow terraform destroy to delete a non-empty bucket. Sandbox only."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}
