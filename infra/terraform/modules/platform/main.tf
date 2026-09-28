# One CoreBanking environment in one region: network, platform CMK, EKS, Aurora, documents bucket,
# backend IAM role, container registry and per-tenant resources. envs/* are thin wrappers around this.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

module "network" {
  source = "../network"

  name                  = var.name
  cidr                  = var.vpc_cidr
  azs                   = var.azs
  public_subnet_cidrs   = var.public_subnet_cidrs
  private_subnet_cidrs  = var.private_subnet_cidrs
  database_subnet_cidrs = var.database_subnet_cidrs
  nat_gateway_per_az    = var.nat_gateway_per_az

  flow_logs_retention_days = var.log_retention_days
  flow_logs_kms_key_arn    = module.platform_kms.key_arns["platform"]

  tags = var.tags
}

# Platform key: EKS secrets, CloudWatch log groups, Aurora storage, ECR, default bucket encryption.
# Tenant data gets per-tenant keys (module.tenant).
module "platform_kms" {
  source = "../kms"

  name_prefix          = var.name
  admin_principal_arns = var.kms_admin_principal_arns
  multi_region         = false

  keys = {
    platform = {
      description           = "CoreBanking ${var.environment} platform key (${data.aws_region.current.region})"
      allow_cloudwatch_logs = true
    }
  }

  tags = var.tags
}

module "eks" {
  source = "../eks"

  name                       = var.name
  kubernetes_version         = var.kubernetes_version
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.private_subnet_ids
  api_allowed_cidrs          = var.eks_api_allowed_cidrs
  kms_key_arn                = module.platform_kms.key_arns["platform"]
  cluster_log_retention_days = var.log_retention_days
  admin_principal_arns       = var.eks_admin_principal_arns
  node_instance_types        = var.node_instance_types
  node_min_size              = var.node_min_size
  node_desired_size          = var.node_desired_size
  node_max_size              = var.node_max_size

  tags = var.tags
}

module "aurora" {
  source = "../aurora-postgres"

  name                       = "${var.name}-pg"
  vpc_id                     = module.network.vpc_id
  db_subnet_group_name       = module.network.database_subnet_group_name
  allowed_security_group_ids = [module.eks.cluster_security_group_id]
  kms_key_arn                = module.platform_kms.key_arns["platform"]
  engine_version             = var.aurora_engine_version
  instance_class             = var.aurora_instance_class
  instance_count             = var.aurora_instance_count
  create_global_cluster      = var.aurora_create_global_cluster
  global_cluster_identifier  = var.aurora_global_cluster_identifier
  deletion_protection        = var.aurora_deletion_protection

  performance_insights_retention_days = var.aurora_performance_insights_retention_days

  tags = var.tags
}

module "documents" {
  source = "../s3-documents"

  bucket_name         = var.documents_bucket_name
  kms_key_arn         = module.platform_kms.key_arns["platform"]
  object_lock_enabled = var.documents_object_lock_enabled
  object_lock_mode    = var.documents_object_lock_mode
  force_destroy       = var.documents_force_destroy

  tags = var.tags
}

# ---- Backend IAM role (IRSA) ---------------------------------------------------------------------

data "aws_iam_policy_document" "backend_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [module.eks.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_issuer}:sub"
      values   = ["system:serviceaccount:${var.app_namespace}:${var.app_service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_issuer}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backend" {
  name                 = "${var.name}-backend"
  description          = "CoreBanking backend pods (Helm serviceAccount.irsaRoleArn)"
  assume_role_policy   = data.aws_iam_policy_document.backend_assume.json
  max_session_duration = 3600

  tags = var.tags
}

# ---- Container registry --------------------------------------------------------------------------

resource "aws_ecr_repository" "backend" {
  count = var.create_ecr_repository ? 1 : 0

  name                 = "corebanking/backend"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = module.platform_kms.key_arns["platform"]
  }

  tags = var.tags
}

# ---- Tenants -------------------------------------------------------------------------------------

module "tenant" {
  source   = "../tenant"
  for_each = var.tenants

  tenant_code               = each.key
  environment               = var.environment
  name_prefix               = var.name
  kms_admin_principal_arns  = var.kms_admin_principal_arns
  app_role_arn              = aws_iam_role.backend.arn
  app_role_name             = aws_iam_role.backend.name
  attach_policy_to_app_role = each.value.attach_policy_to_app_role
  db_host                   = module.aurora.writer_endpoint
  db_port                   = module.aurora.port
  db_cluster_resource_id    = module.aurora.cluster_resource_id
  documents_bucket_arn      = module.documents.bucket_arn

  secret_replica_region      = each.value.secret_replica_kms_key_arn == null ? null : var.dr_region
  secret_replica_kms_key_arn = each.value.secret_replica_kms_key_arn

  tags = var.tags
}

# DR: replicas of the prod tenant keys so PII ciphertext (ADR-013) stays decryptable after failover.
resource "aws_kms_replica_key" "tenant" {
  for_each = var.tenant_key_replicas

  description             = "CoreBanking ${var.environment} replica of tenant ${each.key} key"
  primary_key_arn         = each.value
  deletion_window_in_days = 30

  tags = merge(var.tags, { Tenant = each.key })
}

resource "aws_kms_alias" "tenant_replica" {
  for_each = var.tenant_key_replicas

  name          = "alias/${var.name}/tenant/${each.key}"
  target_key_id = aws_kms_replica_key.tenant[each.key].key_id
}
