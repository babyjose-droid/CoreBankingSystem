# Customer managed key (CMK) factory. One key per tenant keeps cryptographic isolation between
# tenants (ADR-003, ADR-013): PII field encryption, Secrets Manager secrets and S3 objects of a tenant
# are encrypted under that tenant's key, and crypto-shredding a tenant is possible by disabling it.
# Keys stay in India regions; multi-Region replicas are only created in ap-south-2 for DR.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account_root = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"
}

data "aws_iam_policy_document" "key" {
  # In a key policy, Resource "*" means "this key"; these checks target identity policies.
  #checkov:skip=CKV_AWS_109:Key policy - resource is implicitly this key
  #checkov:skip=CKV_AWS_111:Key policy - resource is implicitly this key
  #checkov:skip=CKV_AWS_356:Key policy - resource is implicitly this key
  for_each = var.keys

  statement {
    sid       = "EnableIamPolicies"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = [local.account_root]
    }
  }

  dynamic "statement" {
    for_each = length(var.admin_principal_arns) > 0 ? [1] : []

    content {
      sid = "KeyAdministration"
      actions = [
        "kms:Create*",
        "kms:Describe*",
        "kms:Enable*",
        "kms:List*",
        "kms:Put*",
        "kms:Update*",
        "kms:Revoke*",
        "kms:Disable*",
        "kms:Get*",
        "kms:Delete*",
        "kms:TagResource",
        "kms:UntagResource",
        "kms:ScheduleKeyDeletion",
        "kms:CancelKeyDeletion",
        "kms:ReplicateKey",
        "kms:RotateKeyOnDemand",
      ]
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.admin_principal_arns
      }
    }
  }

  dynamic "statement" {
    for_each = length(each.value.user_principal_arns) > 0 ? [1] : []

    content {
      sid = "KeyUsage"
      actions = [
        "kms:Encrypt",
        "kms:Decrypt",
        "kms:ReEncrypt*",
        "kms:GenerateDataKey*",
        "kms:DescribeKey",
      ]
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = each.value.user_principal_arns
      }

      dynamic "condition" {
        for_each = length(each.value.via_services) > 0 ? [1] : []

        content {
          test     = "StringEquals"
          variable = "kms:ViaService"
          values   = each.value.via_services
        }
      }
    }
  }

  # Lets AWS services acting for the key users (EBS via the Auto Scaling service-linked role, RDS,
  # Secrets Manager) create grants on the key for resources they attach.
  dynamic "statement" {
    for_each = length(each.value.user_principal_arns) > 0 ? [1] : []

    content {
      sid       = "AllowGrantsForAwsResources"
      actions   = ["kms:CreateGrant", "kms:ListGrants", "kms:RevokeGrant"]
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = each.value.user_principal_arns
      }

      condition {
        test     = "Bool"
        variable = "kms:GrantIsForAWSResource"
        values   = ["true"]
      }
    }
  }

  dynamic "statement" {
    for_each = each.value.allow_cloudwatch_logs ? [1] : []

    content {
      sid = "CloudWatchLogsUsage"
      actions = [
        "kms:Encrypt*",
        "kms:Decrypt*",
        "kms:ReEncrypt*",
        "kms:GenerateDataKey*",
        "kms:Describe*",
      ]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["logs.${data.aws_region.current.region}.amazonaws.com"]
      }

      # Only log groups in this account may use the key (prevents confused-deputy use).
      condition {
        test     = "ArnLike"
        variable = "kms:EncryptionContext:aws:logs:arn"
        values   = ["arn:${data.aws_partition.current.partition}:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:*"]
      }
    }
  }
}

resource "aws_kms_key" "this" {
  for_each = var.keys

  description             = each.value.description
  key_usage               = "ENCRYPT_DECRYPT"
  multi_region            = var.multi_region
  enable_key_rotation     = true
  rotation_period_in_days = var.rotation_period_in_days
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.key[each.key].json

  tags = merge(var.tags, { KeyName = each.key })
}

resource "aws_kms_alias" "this" {
  for_each = var.keys

  name          = "alias/${var.name_prefix}/${each.key}"
  target_key_id = aws_kms_key.this[each.key].key_id
}
