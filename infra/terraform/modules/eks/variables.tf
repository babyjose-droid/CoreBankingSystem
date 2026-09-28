variable "name" {
  description = "Cluster name, e.g. corebanking-prod."
  type        = string
}

variable "kubernetes_version" {
  description = "EKS Kubernetes version. Upgrade one minor version at a time."
  type        = string
  default     = "1.34"
}

variable "vpc_id" {
  description = "VPC ID."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnet IDs for the control plane ENIs and nodes."
  type        = list(string)
}

variable "endpoint_public_access" {
  description = "Expose the API server endpoint publicly. Default false: private endpoint only (reach via VPN/SSM/CI runners in the VPC)."
  type        = bool
  default     = false
}

variable "public_access_cidrs" {
  description = "CIDRs allowed to reach a public endpoint when endpoint_public_access is true."
  type        = list(string)
  default     = []
}

variable "api_allowed_cidrs" {
  description = "CIDRs (e.g. VPN, CI runner subnets) allowed to reach the private API endpoint on 443."
  type        = list(string)
  default     = []
}

variable "kms_key_arn" {
  description = "KMS key for envelope encryption of Kubernetes secrets and the control-plane log group (must allow CloudWatch Logs)."
  type        = string
}

variable "cluster_log_retention_days" {
  description = "Retention for control-plane logs (API, audit, authenticator). Audit logs support RBI/CERT-In investigations."
  type        = number
  default     = 365
}

variable "admin_principal_arns" {
  description = "IAM principals (roles) granted cluster-admin through EKS access entries."
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  description = "Instance types for the managed node group."
  type        = list(string)
  default     = ["m7i.xlarge"]
}

variable "node_capacity_type" {
  description = "ON_DEMAND or SPOT. Keep ON_DEMAND for production workloads."
  type        = string
  default     = "ON_DEMAND"
}

variable "node_min_size" {
  description = "Minimum nodes."
  type        = number
  default     = 3
}

variable "node_desired_size" {
  description = "Desired nodes (initial; the cluster autoscaler/Karpenter owns it afterwards)."
  type        = number
  default     = 3
}

variable "node_max_size" {
  description = "Maximum nodes."
  type        = number
  default     = 9
}

variable "node_disk_size_gb" {
  description = "Root EBS volume size for nodes."
  type        = number
  default     = 50
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}
