variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"
}

variable "region" {
  type        = string
  description = "AWS 리전"
}

variable "domain" {
  type        = string
  description = "이 환경의 도메인 (prod = accentury.app, staging = staging.accentury.app)"
}

variable "hosted_zone_name" {
  type        = string
  description = "Route 53 호스팅 영역 이름 (data 조회용, Terraform 소유 아님)"
}

variable "acm_certificate_domain" {
  type        = string
  description = "us-east-1 인증서의 주 도메인 이름 (2026-08-24 CLI 확인: accentury.app)"
}

variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR"
}

variable "azs" {
  type        = list(string)
  description = "가용 영역 2개"
}

variable "public_subnet_cidrs" {
  type        = list(string)
  description = "퍼블릭 서브넷 CIDR 2개 (EC2)"
}

variable "private_subnet_cidrs" {
  type        = list(string)
  description = "사설 서브넷 CIDR 2개 (internal ALB, RDS)"
}

variable "instance_type" {
  type        = string
  description = "EC2 인스턴스 타입"
}

variable "db_instance_class" {
  type        = string
  description = "RDS 인스턴스 클래스"
}

variable "ssm_prefix" {
  type        = string
  description = "SSM Parameter Store 경로 접두사 (KAN-129). /accentury/{env}와 정확히 같아야 한다."

  # 접두사가 환경 이름과 어긋나면(prod tfvars에 /accentury/staging 오타) prod EC2 역할이 staging
  # 경로를 읽는다. "staging 설정으로 prod에 닿지 않는다"는 AC가 여기서 깨지므로 plan에서 세운다.
  validation {
    condition     = var.ssm_prefix == "/accentury/${var.env}"
    error_message = "ssm_prefix는 /accentury/${var.env}이어야 합니다 (env와 결합, KAN-129)."
  }
}

variable "db_deletion_protection" {
  type        = bool
  description = "RDS 삭제 보호 (prod true)"
}

variable "db_skip_final_snapshot" {
  type        = bool
  description = "RDS 삭제 시 최종 스냅샷 생략 (staging true)"
}
