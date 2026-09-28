# Aurora PostgreSQL 16 cluster that hosts tenant databases (ADR-003: database per tenant).
# Encrypted at rest with a CMK, TLS enforced (rds.force_ssl), IAM auth, 35-day PITR, deletion
# protection and audit logging to CloudWatch. Optional Aurora global database for DR in ap-south-2
# (ADR-004); both regions are in India, satisfying RBI data localisation.

locals {
  is_secondary = var.global_cluster_identifier != null
  # Secrets Manager-managed master passwords are not supported for Aurora global databases, so global
  # (primary or secondary) clusters get a generated password stored in Secrets Manager by this module.
  is_global           = var.create_global_cluster || local.is_secondary
  use_managed_secret  = !local.is_global
  generate_password   = var.create_global_cluster
  global_cluster_id   = var.create_global_cluster ? aws_rds_global_cluster.this[0].id : var.global_cluster_identifier
  cluster_log_exports = ["postgresql"]
}

resource "aws_security_group" "this" {
  name        = "${var.name}-aurora"
  description = "PostgreSQL access to ${var.name}"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${var.name}-aurora" })
}

resource "aws_vpc_security_group_ingress_rule" "from_sg" {
  for_each = toset(var.allowed_security_group_ids)

  security_group_id            = aws_security_group.this.id
  description                  = "PostgreSQL from ${each.value}"
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_ingress_rule" "from_cidr" {
  for_each = toset(var.allowed_cidr_blocks)

  security_group_id = aws_security_group.this.id
  description       = "PostgreSQL from ${each.value}"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
}

resource "aws_rds_cluster_parameter_group" "this" {
  name        = "${var.name}-aurora-pg16"
  family      = "aurora-postgresql16"
  description = "CoreBanking Aurora PostgreSQL 16 cluster parameters"

  # Reject any non-TLS connection (encryption in transit).
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "ssl_min_protocol_version"
    value = "TLSv1.2"
  }

  # Connection audit trail.
  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  # DDL and role changes are logged by pgaudit; DML audit lives in the application's audit schema.
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements,pgaudit"
    apply_method = "pending-reboot"
  }

  parameter {
    name  = "pgaudit.log"
    value = "ddl,role"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  # IST for server-side timestamps in logs; the application stores timestamptz.
  parameter {
    name  = "timezone"
    value = "Asia/Kolkata"
  }

  tags = var.tags
}

resource "aws_db_parameter_group" "this" {
  name        = "${var.name}-aurora-pg16-instance"
  family      = "aurora-postgresql16"
  description = "CoreBanking Aurora PostgreSQL 16 instance parameters"

  tags = var.tags
}

resource "aws_rds_global_cluster" "this" {
  count = var.create_global_cluster ? 1 : 0

  global_cluster_identifier = "${var.name}-global"
  engine                    = "aurora-postgresql"
  engine_version            = var.engine_version
  storage_encrypted         = true
  deletion_protection       = var.deletion_protection
}

resource "random_password" "master" {
  count = local.generate_password ? 1 : 0

  length           = 32
  special          = true
  override_special = "!#%^*-_=+"
}

resource "aws_secretsmanager_secret" "master" {
  count = local.generate_password ? 1 : 0

  name                    = "corebanking/${var.name}/master"
  description             = "Master credentials for Aurora global cluster ${var.name}"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 30

  tags = var.tags
}

resource "aws_secretsmanager_secret_version" "master" {
  count = local.generate_password ? 1 : 0

  secret_id = aws_secretsmanager_secret.master[0].id
  secret_string = jsonencode({
    username = var.master_username
    password = random_password.master[0].result
  })
}

resource "aws_rds_cluster" "this" {
  #checkov:skip=CKV2_AWS_27:Statement logging via pgaudit (ddl,role) and log_min_duration_statement; full DML logging would log PII
  cluster_identifier        = var.name
  engine                    = "aurora-postgresql"
  engine_version            = var.engine_version
  global_cluster_identifier = local.global_cluster_id

  master_username               = local.is_secondary ? null : var.master_username
  manage_master_user_password   = local.use_managed_secret ? true : null
  master_user_secret_kms_key_id = local.use_managed_secret ? var.kms_key_arn : null
  master_password               = local.generate_password ? random_password.master[0].result : null

  db_subnet_group_name            = var.db_subnet_group_name
  vpc_security_group_ids          = [aws_security_group.this.id]
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.this.name
  port                            = 5432

  storage_encrypted                   = true
  kms_key_id                          = var.kms_key_arn
  iam_database_authentication_enabled = var.iam_auth_enabled
  deletion_protection                 = var.deletion_protection

  backup_retention_period      = var.backup_retention_days
  preferred_backup_window      = var.preferred_backup_window
  preferred_maintenance_window = var.preferred_maintenance_window
  copy_tags_to_snapshot        = true
  skip_final_snapshot          = false
  final_snapshot_identifier    = "${var.name}-final"

  enabled_cloudwatch_logs_exports = local.cluster_log_exports

  tags = var.tags

  lifecycle {
    # A secondary follows the global cluster's version; engine upgrades are done on the global cluster.
    ignore_changes = [global_cluster_identifier, replication_source_identifier]
  }
}

# ---- Instances -----------------------------------------------------------------------------------

data "aws_iam_policy_document" "monitoring_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "monitoring" {
  name               = "${var.name}-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.monitoring_assume.json

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "monitoring" {
  role       = aws_iam_role.monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_rds_cluster_instance" "this" {
  count = var.instance_count

  identifier                 = "${var.name}-${count.index + 1}"
  cluster_identifier         = aws_rds_cluster.this.id
  engine                     = aws_rds_cluster.this.engine
  engine_version             = aws_rds_cluster.this.engine_version
  instance_class             = var.instance_class
  db_parameter_group_name    = aws_db_parameter_group.this.name
  publicly_accessible        = false
  auto_minor_version_upgrade = true
  ca_cert_identifier         = "rds-ca-rsa2048-g1"
  promotion_tier             = count.index

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = var.kms_key_arn
  performance_insights_retention_period = var.performance_insights_retention_days
  monitoring_interval                   = 60
  monitoring_role_arn                   = aws_iam_role.monitoring.arn

  copy_tags_to_snapshot = true

  tags = var.tags
}
