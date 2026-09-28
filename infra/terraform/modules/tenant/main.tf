# Per-tenant cloud resources, called by tenant provisioning (see docs/runbooks/tenant-onboarding.md):
#   * a tenant CMK (PII field encryption per ADR-013, secret and document encryption)
#   * a Secrets Manager secret with generated DB credentials for the tenant database
#   * an IAM policy scoping the backend to this tenant's key, secret and S3 prefix
# The database itself (CREATE DATABASE/ROLE) is created by the provisioning job inside the VPC, and
# schemas by the application's Flyway migrations. Outputs feed control.tenant.db_secret_arn/kms_key_arn.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  db_identifier = "tenant_${replace(var.tenant_code, "-", "_")}"
  s3_prefix     = "tenants/${var.tenant_code}/"
  tags          = merge(var.tags, { Tenant = var.tenant_code })
}

module "key" {
  source = "../kms"

  name_prefix          = "${var.name_prefix}/tenant"
  admin_principal_arns = var.kms_admin_principal_arns
  multi_region         = var.multi_region_key

  keys = {
    (var.tenant_code) = {
      description         = "CoreBanking ${var.environment} tenant ${var.tenant_code}: PII, secrets and documents"
      user_principal_arns = var.app_role_arn == null ? [] : [var.app_role_arn]
    }
  }

  tags = local.tags
}

resource "random_password" "db" {
  length           = 32
  special          = true
  override_special = "-_=+.~"
}

resource "aws_secretsmanager_secret" "db" {
  name                    = "corebanking/${var.environment}/tenants/${var.tenant_code}/db"
  description             = "Database credentials for tenant ${var.tenant_code}"
  kms_key_id              = module.key.key_arns[var.tenant_code]
  recovery_window_in_days = 30

  dynamic "replica" {
    for_each = var.secret_replica_region == null ? [] : [1]

    content {
      region     = var.secret_replica_region
      kms_key_id = var.secret_replica_kms_key_arn
    }
  }

  tags = local.tags
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    engine   = "postgres"
    host     = var.db_host
    port     = var.db_port
    dbname   = local.db_identifier
    username = local.db_identifier
    password = random_password.db.result
    jdbcUrl  = "jdbc:postgresql://${var.db_host}:${var.db_port}/${local.db_identifier}?sslmode=verify-full"
  })
}

data "aws_iam_policy_document" "tenant" {
  statement {
    sid       = "ListOwnPrefix"
    actions   = ["s3:ListBucket"]
    resources = [var.documents_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [local.s3_prefix, "${local.s3_prefix}*"]
    }
  }

  statement {
    sid = "ReadDeleteOwnObjects"
    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:DeleteObject",
    ]
    resources = ["${var.documents_bucket_arn}/${local.s3_prefix}*"]
  }

  # Uploads must be encrypted with the tenant's own key (crypto-isolation between tenants).
  statement {
    sid       = "PutOwnObjectsWithTenantKey"
    actions   = ["s3:PutObject"]
    resources = ["${var.documents_bucket_arn}/${local.s3_prefix}*"]

    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-server-side-encryption-aws-kms-key-id"
      values   = [module.key.key_arns[var.tenant_code]]
    }
  }

  statement {
    sid = "UseTenantKey"
    actions = [
      "kms:Encrypt",
      "kms:Decrypt",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:DescribeKey",
    ]
    resources = [module.key.key_arns[var.tenant_code]]
  }

  statement {
    sid       = "ReadTenantDbSecret"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [aws_secretsmanager_secret.db.arn]
  }

  dynamic "statement" {
    for_each = var.db_cluster_resource_id == null ? [] : [1]

    content {
      sid       = "IamDbAuth"
      actions   = ["rds-db:connect"]
      resources = ["arn:${data.aws_partition.current.partition}:rds-db:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:dbuser:${var.db_cluster_resource_id}/${local.db_identifier}"]
    }
  }
}

resource "aws_iam_policy" "tenant" {
  name        = "${var.name_prefix}-tenant-${var.tenant_code}"
  description = "CoreBanking backend access to tenant ${var.tenant_code} key, DB secret and documents prefix"
  policy      = data.aws_iam_policy_document.tenant.json

  tags = local.tags
}

resource "aws_iam_role_policy_attachment" "app" {
  count = var.attach_policy_to_app_role ? 1 : 0

  role       = var.app_role_name
  policy_arn = aws_iam_policy.tenant.arn
}
