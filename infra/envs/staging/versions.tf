# 이 파일은 envs/staging과 envs/prod가 동일해야 한다. 환경 간 차이는
# terraform.tfvars와 backend.tf(state key)뿐이다 (KAN-140 AC).

terraform {
  # 1.10 미만은 S3 백엔드의 use_lockfile(네이티브 잠금)이 없다. 1.11 미만은 write-only 인자
  # (modules/config의 aws_ssm_parameter.value_wo, KAN-164)가 없어 plan부터 실패한다.
  required_version = ">= 1.11.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
