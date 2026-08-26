variable "ssm_prefix" {
  type        = string
  description = "이 환경의 SSM Parameter Store 경로 접두사 (/accentury/staging 등). compute 모듈의 EC2 역할이 이 경로만 읽는다."

  validation {
    condition     = startswith(var.ssm_prefix, "/accentury/") && !endswith(var.ssm_prefix, "/")
    error_message = "ssm_prefix는 /accentury/로 시작하고 끝에 /가 없어야 합니다 (KAN-129 경로 규약)."
  }
}

variable "domain" {
  type        = string
  description = "이 환경의 도메인 (prod = accentury.app, staging = staging.accentury.app). web-test-url에 쓴다."
}

variable "vpc_cidr" {
  type        = string
  description = "이 환경 VPC CIDR. trusted-proxies 값이다 (CloudFront VPC 오리진 ENI와 ALB가 여기 든다)."
}

variable "rds_endpoint" {
  type        = string
  description = "RDS 호스트:포트 (data 모듈 출력 endpoint)."
}

variable "db_name" {
  type        = string
  description = "데이터베이스 이름. data 모듈 aws_db_instance.db_name과 같아야 한다."
  default     = "accentury"
}

variable "rds_master_user_secret_arn" {
  type        = string
  description = "RDS 관리형 마스터 시크릿 ARN (Secrets Manager). backend가 연결 시점에 읽는다. 값은 SSM에 복사하지 않는다."
}
