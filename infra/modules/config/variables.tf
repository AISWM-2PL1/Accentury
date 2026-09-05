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
  description = "이 환경의 도메인 (prod = accentury.app, staging = staging.accentury.app). web-test-url과 asset-base-url에 쓴다."
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

variable "ai_dns_name" {
  type        = string
  description = "backend가 AI를 부르는 프라이빗 DNS 이름 (network 모듈 출력 ai_dns_name, KAN-36). ACCENTURY_ANALYSIS_AIBASEURL = http://<이 값>:8000"
}

variable "analysis_ai_timeout" {
  type        = string
  description = "backend가 AI 호출에 거는 연결/읽기 타임아웃 (accentury.analysis.ai-timeout). 실모델 추론 1건보다 넉넉해야 한다 (KAN-22 임시값, KAN-172 재확정)."
  default     = "85s"
}

variable "analysis_processing_timeout" {
  type        = string
  description = "실행 잔류 한도 (accentury.analysis.processing-timeout). ai-timeout x 3 + 백오프보다 길어야 기동이 통과한다 (AnalysisDispatchConfig)."
  default     = "300s"
}

variable "analysis_dispatch_concurrency" {
  type        = number
  description = "분석 전달 워커 수 (accentury.analysis.dispatch-concurrency). AI가 추론을 한 번에 하나만 돌리므로 태스크 하나가 보내는 동시 호출을 1로 묶는다 (KAN-22). 태스크 여러 개가 동시에 뜨면 그만큼은 여전히 겹친다 - 아래 README 참고."
  default     = 1

  validation {
    condition     = var.analysis_dispatch_concurrency >= 1
    error_message = "analysis_dispatch_concurrency는 1 이상이어야 합니다."
  }
}

variable "ai_analysis_timeout_seconds" {
  type        = number
  description = "AI 서버가 분석 1건에 거는 상한(초, ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS). backend의 analysis_ai_timeout보다 짧아야 한다 (KAN-22)."
  default     = 75
}
