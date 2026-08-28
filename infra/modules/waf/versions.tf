# envs가 `providers = { aws = aws.us_east_1 }`로 프로바이더를 넘긴다. 이 선언이 없으면
# validate가 "undefined provider" 경고를 낸다 (동작은 같다).
terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}
