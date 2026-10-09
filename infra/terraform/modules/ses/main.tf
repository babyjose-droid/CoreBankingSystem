# Outgoing e-mail through Amazon SES, used over its SMTP interface (spring.mail.*), so any SMTP relay can replace it.
# A verified domain identity with Easy DKIM, a configuration set that records bounces and complaints, and an IAM user
# that may only send from that domain. The SMTP password is derived from an access key of that user: create the key
# out of band (never in Terraform state) and store host, user and password in the environment's secret.

resource "aws_sesv2_email_identity" "domain" {
  email_identity         = var.domain
  configuration_set_name = aws_sesv2_configuration_set.this.configuration_set_name

  tags = var.tags
}

resource "aws_sesv2_email_identity_mail_from_attributes" "domain" {
  count = var.mail_from_subdomain == null ? 0 : 1

  email_identity         = aws_sesv2_email_identity.domain.email_identity
  behavior_on_mx_failure = "USE_DEFAULT_VALUE"
  mail_from_domain       = "${var.mail_from_subdomain}.${var.domain}"
}

resource "aws_sesv2_configuration_set" "this" {
  configuration_set_name = "${var.name}-mail"

  delivery_options {
    tls_policy = "REQUIRE"
  }

  reputation_options {
    reputation_metrics_enabled = true
  }

  suppression_options {
    suppressed_reasons = ["BOUNCE", "COMPLAINT"]
  }

  tags = var.tags
}

resource "aws_iam_user" "smtp" {
  name = "${var.name}-ses-smtp"
  tags = var.tags
}

data "aws_iam_policy_document" "smtp" {
  statement {
    sid       = "SendFromTheDomainOnly"
    actions   = ["ses:SendRawEmail"]
    resources = [aws_sesv2_email_identity.domain.arn]

    condition {
      test     = "StringLike"
      variable = "ses:FromAddress"
      values   = ["*@${var.domain}"]
    }
  }
}

resource "aws_iam_user_policy" "smtp" {
  name   = "ses-send"
  user   = aws_iam_user.smtp.name
  policy = data.aws_iam_policy_document.smtp.json
}
