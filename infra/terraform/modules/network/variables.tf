variable "name" {
  description = "Name prefix for all network resources, e.g. corebanking-prod."
  type        = string
}

variable "cidr" {
  description = "VPC CIDR. Use non-overlapping ranges per environment/region so VPCs can be peered for DR."
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "Exactly three availability zones, e.g. [\"ap-south-1a\", \"ap-south-1b\", \"ap-south-1c\"]."
  type        = list(string)

  validation {
    condition     = length(var.azs) == 3
    error_message = "Provide exactly three availability zones."
  }
}

variable "public_subnet_cidrs" {
  description = "Public subnets (load balancers, NAT gateways), one per AZ."
  type        = list(string)
  default     = ["10.0.96.0/22", "10.0.100.0/22", "10.0.104.0/22"]
}

variable "private_subnet_cidrs" {
  description = "Private subnets (EKS nodes and pods), one per AZ. Sized large because the VPC CNI gives pods VPC IPs."
  type        = list(string)
  default     = ["10.0.0.0/19", "10.0.32.0/19", "10.0.64.0/19"]
}

variable "database_subnet_cidrs" {
  description = "Isolated database subnets (Aurora), one per AZ. No route to the internet."
  type        = list(string)
  default     = ["10.0.112.0/24", "10.0.113.0/24", "10.0.114.0/24"]
}

variable "nat_gateway_per_az" {
  description = "One NAT gateway per AZ (production, AZ-independent egress) or a single shared NAT gateway (sandbox, lower cost)."
  type        = bool
  default     = true
}

variable "interface_endpoints" {
  description = "Interface VPC endpoint services (short names). Keeps AWS API traffic off the internet."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "secretsmanager", "kms", "sts", "logs"]
}

variable "flow_logs_retention_days" {
  description = "CloudWatch retention for VPC flow logs. RBI cyber security directions expect logs to be retained; 365 days minimum by default."
  type        = number
  default     = 365
}

variable "flow_logs_kms_key_arn" {
  description = "Optional KMS key ARN to encrypt the flow log group. The key policy must allow logs.<region>.amazonaws.com."
  type        = string
  default     = null
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}
