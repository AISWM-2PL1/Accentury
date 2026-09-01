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
  description = "backend EC2 인스턴스 타입 (t3.small)"
}

variable "ai_instance_type" {
  type        = string
  description = "AI 추론 호스트 인스턴스 타입 (KAN-36). 2026-09-01 결정으로 A단계 스텁 모드부터 c7i.xlarge - 실모델(RSS 7.1GB, 지속 추론)에 t 계열은 탈락이고, 단계 전환에 인스턴스 교체를 없앤다. P95 미달 시 c7i.2xlarge 또는 g4dn.xlarge (KAN-57 실측 후)."
}

variable "ai_root_volume_size" {
  type        = number
  description = "AI 호스트 루트 볼륨 GiB (KAN-36). 스텁 20, 실모델 전환(B단계)에서 40 - 가중치 포함 이미지 약 8.5GB x SHA 태그 2개 공존."
  default     = 20
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

variable "ci_image_push" {
  type        = bool
  description = "배포 역할의 ECR push 권한 (KAN-128 승격 모델). staging true, prod false - prod 이미지는 언제나 staging을 거친 SHA다."
}

variable "waf_enforce" {
  type        = bool
  description = "WAF 규칙을 차단(Block)으로 돌릴지 (KAN-149). false면 전부 Count(기록만). 먼저 false로 관찰한 뒤 오탐 기록을 남기고 true로 바꾼다."
}

variable "waf_rate_limit" {
  type        = number
  description = "WAF rate-based rule: IP당 5분 창의 세션 생성 + 음성 업로드 허용 수 (KAN-149). 산정 근거는 README 'WAF 웹 ACL'."
}

variable "db_deletion_protection" {
  type        = bool
  description = "RDS 삭제 보호 (prod true)"
}

variable "db_skip_final_snapshot" {
  type        = bool
  description = "RDS 삭제 시 최종 스냅샷 생략 (staging true)"
}

variable "github_repository" {
  type        = string
  description = "배포 워크플로가 도는 GitHub 저장소 (OWNER/REPO). OIDC 신뢰 정책의 sub 조건에 들어간다 (KAN-127). 두 환경이 같은 값이라 tfvars가 아니라 기본값이다."
  default     = "AISWM-2PL1/Accentury"
}

# 불변 subject claim용 숫자 ID (KAN-127). 확인: gh api repos/AISWM-2PL1/Accentury --jq '[.owner.id, .id]'
variable "github_owner_id" {
  type    = number
  default = 295795156
}

variable "github_repository_id" {
  type    = number
  default = 1308814203
}

variable "alert_email" {
  type        = string
  description = "CloudWatch 경보 수신 주소 (KAN-134, 2026-08-28 확정). 두 환경이 같은 값이라 tfvars가 아니라 기본값이다. 환경별로 나누려면 tfvars에 한 줄 넣으면 된다."
  default     = "team2pl1@gmail.com"
}
