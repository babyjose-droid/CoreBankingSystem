provider "aws" {
  region              = var.region
  allowed_account_ids = length(var.allowed_account_ids) > 0 ? var.allowed_account_ids : null

  default_tags {
    tags = {
      Product            = "CoreBanking"
      Environment        = var.environment
      DataClassification = var.data_classification
      DataResidency      = "India"
      ManagedBy          = "Terraform"
    }
  }
}
