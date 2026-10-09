variable "name" {
  description = "Environment resource prefix, e.g. corebanking-prod."
  type        = string
}

variable "domain" {
  description = "Sending domain (its DNS gets the DKIM records from the dkim_tokens output), e.g. mail.example.in."
  type        = string
}

variable "mail_from_subdomain" {
  description = "Optional custom MAIL FROM subdomain (e.g. bounce), for SPF alignment."
  type        = string
  default     = null
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}
