output "dkim_tokens" {
  description = "Easy DKIM tokens: publish CNAME <token>._domainkey.<domain> -> <token>.dkim.amazonses.com."
  value       = aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens
}

output "configuration_set" {
  description = "Configuration set name (SMTP header X-SES-CONFIGURATION-SET)."
  value       = aws_sesv2_configuration_set.this.configuration_set_name
}

output "smtp_user_name" {
  description = "IAM user whose access key (created out of band) gives the SMTP credentials."
  value       = aws_iam_user.smtp.name
}
