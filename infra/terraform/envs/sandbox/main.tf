# Sandbox / UAT: single region (ap-south-1), cost-reduced, synthetic test data only (CLAUDE-TEST).

module "platform" {
  source = "../../modules/platform"

  name                  = "corebanking-${var.environment}"
  environment           = var.environment
  vpc_cidr              = var.vpc_cidr
  azs                   = var.azs
  public_subnet_cidrs   = var.public_subnet_cidrs
  private_subnet_cidrs  = var.private_subnet_cidrs
  database_subnet_cidrs = var.database_subnet_cidrs
  nat_gateway_per_az    = false
  log_retention_days    = 90

  kms_admin_principal_arns = var.kms_admin_principal_arns
  eks_admin_principal_arns = var.eks_admin_principal_arns
  eks_api_allowed_cidrs    = var.eks_api_allowed_cidrs

  node_instance_types = ["m7i.large"]
  node_min_size       = 2
  node_desired_size   = 2
  node_max_size       = 4

  aurora_instance_class = "db.t4g.medium"
  aurora_instance_count = 1

  documents_bucket_name   = var.documents_bucket_name
  documents_force_destroy = true

  tenants = var.tenants
}
