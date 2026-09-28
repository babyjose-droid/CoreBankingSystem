variable "name" {
  description = "Environment resource prefix, e.g. corebanking-prod."
  type        = string
}

variable "environment" {
  description = "Environment name: sandbox, uat, prod, dr."
  type        = string
}

# ---- Network -------------------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "VPC CIDR."
  type        = string
}

variable "azs" {
  description = "Three availability zones."
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "Public subnet CIDRs (one per AZ)."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "Private subnet CIDRs (one per AZ)."
  type        = list(string)
}

variable "database_subnet_cidrs" {
  description = "Database subnet CIDRs (one per AZ)."
  type        = list(string)
}

variable "nat_gateway_per_az" {
  description = "NAT gateway per AZ (true) or one shared (false)."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "Retention for flow logs and EKS control-plane logs."
  type        = number
  default     = 365
}

# ---- Access --------------------------------------------------------------------------------------

variable "kms_admin_principal_arns" {
  description = "Principals that administer CMKs."
  type        = list(string)
  default     = []
}

variable "eks_admin_principal_arns" {
  description = "Principals granted EKS cluster-admin via access entries."
  type        = list(string)
  default     = []
}

variable "eks_api_allowed_cidrs" {
  description = "CIDRs (VPN, CI runners) allowed to reach the private EKS API endpoint."
  type        = list(string)
  default     = []
}

# ---- EKS -----------------------------------------------------------------------------------------

variable "kubernetes_version" {
  description = "EKS version."
  type        = string
  default     = "1.34"
}

variable "node_instance_types" {
  description = "Node instance types."
  type        = list(string)
  default     = ["m7i.xlarge"]
}

variable "node_min_size" {
  description = "Minimum nodes."
  type        = number
  default     = 3
}

variable "node_desired_size" {
  description = "Desired nodes."
  type        = number
  default     = 3
}

variable "node_max_size" {
  description = "Maximum nodes."
  type        = number
  default     = 9
}

variable "app_namespace" {
  description = "Kubernetes namespace of the CoreBanking backend (IRSA trust)."
  type        = string
  default     = "corebanking"
}

variable "app_service_account" {
  description = "Kubernetes service account of the backend (Helm chart fullname by default)."
  type        = string
  default     = "corebanking"
}

# ---- Aurora --------------------------------------------------------------------------------------

variable "aurora_engine_version" {
  description = "Aurora PostgreSQL 16 version."
  type        = string
  default     = "16.8"
}

variable "aurora_instance_class" {
  description = "Aurora instance class."
  type        = string
  default     = "db.r7g.large"
}

variable "aurora_instance_count" {
  description = "Aurora instances (writer + readers)."
  type        = number
  default     = 2
}

variable "aurora_create_global_cluster" {
  description = "Make this cluster the primary of an Aurora global database (prod)."
  type        = bool
  default     = false
}

variable "aurora_global_cluster_identifier" {
  description = "Join this global cluster as secondary (dr)."
  type        = string
  default     = null
}

variable "aurora_performance_insights_retention_days" {
  description = "Performance Insights retention."
  type        = number
  default     = 7
}

variable "aurora_deletion_protection" {
  description = "Deletion protection."
  type        = bool
  default     = true
}

# ---- Documents -----------------------------------------------------------------------------------

variable "documents_bucket_name" {
  description = "Documents bucket name (globally unique)."
  type        = string
}

variable "documents_object_lock_enabled" {
  description = "Enable S3 Object Lock on the documents bucket."
  type        = bool
  default     = false
}

variable "documents_object_lock_mode" {
  description = "GOVERNANCE or COMPLIANCE."
  type        = string
  default     = "GOVERNANCE"
}

variable "documents_force_destroy" {
  description = "Allow destroying a non-empty bucket (sandbox only)."
  type        = bool
  default     = false
}

# ---- Registry ------------------------------------------------------------------------------------

variable "create_ecr_repository" {
  description = "Create the backend ECR repository in this region."
  type        = bool
  default     = true
}

# ---- Tenants -------------------------------------------------------------------------------------

variable "tenants" {
  description = <<-EOT
    Tenants hosted in this environment, keyed by tenant code. Provisioning adds an entry and applies.
    attach_policy_to_app_role: attach the tenant IAM policy to the backend role (pooled/dedicated tiers).
    secret_replica_kms_key_arn: tenant key replica in the DR region; when set the DB secret is replicated there.
  EOT
  type = map(object({
    attach_policy_to_app_role  = optional(bool, true)
    secret_replica_kms_key_arn = optional(string)
  }))
  default = {}
}

variable "dr_region" {
  description = "DR region for secret replicas."
  type        = string
  default     = "ap-south-2"
}

variable "tenant_key_replicas" {
  description = "DR only: primary tenant key ARNs (from prod, multi-Region keys) to replicate into this region, keyed by tenant code."
  type        = map(string)
  default     = {}
}

variable "tags" {
  description = "Extra tags (provider default_tags already carry Product/Environment/DataClassification)."
  type        = map(string)
  default     = {}
}
