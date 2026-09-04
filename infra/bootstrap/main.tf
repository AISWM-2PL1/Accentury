# Terraform state 백엔드(S3 버킷) 생성 전용 스택 (KAN-140).
#
# 닭과 달걀 문제 때문에 이 스택만 로컬 state를 쓴다 - state를 담을 버킷을
# Terraform으로 만들려면 그 시점에는 아직 원격 백엔드가 없다. 여기서 만든
# terraform.tfstate는 버킷 이름 외에 비밀값이 없고, .gitignore(*.tfstate)로
# 레포에 들어가지 않는다. 재실행이 필요하면 import로 복구한다:
#   terraform import aws_s3_bucket.tfstate accentury-tfstate-<account_id>

terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-2"

  default_tags {
    tags = {
      project    = "accentury"
      managed-by = "terraform"
      # 두 환경이 공유하는 계정 단위 리소스라 env를 특정할 수 없다 (KAN-35 비용 태그).
      env = "shared"
    }
  }
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "tfstate" {
  bucket = "accentury-tfstate-${data.aws_caller_identity.current.account_id}"

  # state 파일에는 리소스 식별자와 일부 민감값이 들어간다. 실수로 destroy에
  # 쓸려 나가면 두 환경의 state가 통째로 사라지므로 코드 수준에서 막는다.
  lifecycle {
    prevent_destroy = true
  }
}

# state 파일 히스토리 보존 - 잘못된 apply 뒤 이전 state로 복구하는 유일한 수단이다.
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "state_bucket" {
  value       = aws_s3_bucket.tfstate.bucket
  description = "envs/*/backend.tf가 참조하는 원격 state 버킷 이름"
}
