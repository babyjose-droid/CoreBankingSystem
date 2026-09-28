terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Partial configuration: bucket, key, region and dynamodb_table come from backend.hcl
  # (terraform init -backend-config=backend.hcl). See backend.hcl.example. State is encrypted with
  # SSE-KMS and holds generated tenant DB passwords, so the state bucket must be private,
  # versioned, KMS-encrypted and located in ap-south-1.
  backend "s3" {}
}
