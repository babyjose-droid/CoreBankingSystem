# Production primary: ap-south-1 (Mumbai), three AZs, Aurora global database primary replicating to
# the dr env in ap-south-2 (ADR-004). Both regions are in India (RBI data localisation, DPDP).

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

  node_instance_types = ["m7i.xlarge"]
  node_min_size       = 3
  node_desired_size   = 3
  node_max_size       = 12

  aurora_instance_class                      = "db.r7g.xlarge"
  aurora_instance_count                      = 2
  aurora_create_global_cluster               = true
  aurora_performance_insights_retention_days = 31

  documents_bucket_name         = var.documents_bucket_name
  documents_object_lock_enabled = true
  documents_object_lock_mode    = var.documents_object_lock_mode

  tenants = var.tenants
}
