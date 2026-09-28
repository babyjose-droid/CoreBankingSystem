variable "region" {
  description = "AWS region. Only India regions are allowed (RBI data localisation): ap-south-1 Mumbai, ap-south-2 Hyderabad."
  type        = string
  default     = "ap-south-1"

  validation {
    condition     = contains(["ap-south-1", "ap-south-2"], var.region)
    error_message = "CoreBanking data must stay in India: use ap-south-1 or ap-south-2."
  }
}

variable "environment" {
  description = "Environment name (default tag Environment)."
  type        = string
  default     = "sandbox"
}

variable "data_classification" {
  description = "Default tag DataClassification."
  type        = string
  default     = "Internal-TestDataOnly"
}

variable "allowed_account_ids" {
  description = "AWS account IDs this configuration may run against (guards against applying to the wrong account). Set in tfvars."
  type        = list(string)
  default     = []
}

variable "azs" {
  description = "Three availability zones in the region."
  type        = list(string)
  default     = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
}

variable "vpc_cidr" {
  description = "VPC CIDR (non-overlapping across environments/regions)."
  type        = string
  default     = "10.30.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "Public subnet CIDRs."
  type        = list(string)
  default     = ["10.30.96.0/22", "10.30.100.0/22", "10.30.104.0/22"]
}

variable "private_subnet_cidrs" {
  description = "Private subnet CIDRs."
  type        = list(string)
  default     = ["10.30.0.0/19", "10.30.32.0/19", "10.30.64.0/19"]
}

variable "database_subnet_cidrs" {
  description = "Database subnet CIDRs."
  type        = list(string)
  default     = ["10.30.112.0/24", "10.30.113.0/24", "10.30.114.0/24"]
}

variable "kms_admin_principal_arns" {
  description = "IAM role ARNs that administer CMKs (e.g. the platform admin SSO role)."
  type        = list(string)
  default     = []
}

variable "eks_admin_principal_arns" {
  description = "IAM role ARNs granted EKS cluster-admin."
  type        = list(string)
  default     = []
}

variable "eks_api_allowed_cidrs" {
  description = "CIDRs allowed to reach the private EKS API (VPN, CI runners)."
  type        = list(string)
  default     = []
}

variable "documents_bucket_name" {
  description = "Globally unique documents bucket name, e.g. corebanking-sandbox-documents-<random suffix>."
  type        = string
}

variable "tenants" {
  description = "Tenants in this environment (see modules/platform variable tenants)."
  type = map(object({
    attach_policy_to_app_role  = optional(bool, true)
    secret_replica_kms_key_arn = optional(string)
  }))
  default = {
    "demo-nbfc" = {}
  }
}
