# Disaster recovery: ap-south-2 (Hyderabad). Warm standby: network, EKS with a small node group,
# Aurora secondary cluster in the prod global database, replicas of the prod tenant keys.
# Failover procedure: docs/runbooks/dr-failover.md.

module "platform" {
  source = "../../modules/platform"

  name                  = "corebanking-${var.environment}"
  environment           = var.environment
  vpc_cidr              = var.vpc_cidr
  azs                   = var.azs
  public_subnet_cidrs   = var.public_subnet_cidrs
  private_subnet_cidrs  = var.private_subnet_cidrs
  database_subnet_cidrs = var.database_subnet_cidrs
  nat_gateway_per_az    = true
  log_retention_days    = 400

  kms_admin_principal_arns = var.kms_admin_principal_arns
  eks_admin_principal_arns = var.eks_admin_principal_arns
  eks_api_allowed_cidrs    = var.eks_api_allowed_cidrs

  # Scaled up during failover (see runbook).
  node_instance_types = ["m7i.xlarge"]
  node_min_size       = 2
  node_desired_size   = 2
  node_max_size       = 12

  aurora_instance_class            = "db.r7g.xlarge"
  aurora_instance_count            = 1
  aurora_global_cluster_identifier = var.aurora_global_cluster_identifier

  documents_bucket_name         = var.documents_bucket_name
  documents_object_lock_enabled = true

  # Tenants are provisioned in prod; their DB secrets replicate here and their keys are replicated below.
  tenants             = {}
  tenant_key_replicas = var.tenant_key_replicas
}
