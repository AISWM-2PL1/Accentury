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
  description = "backend가 AI 호출에 거는 연결/읽기 타임아웃 (accentury.analysis.ai-timeout). AI 자신의 상한(ai_analysis_timeout_seconds 75초)보다 10초 길다 (KAN-172 확정 - KAN-22 임시값과 같은 값이고 staging 실증이 있다). 코드 기본값과 같다."
  default     = "85s"
}

variable "analysis_processing_timeout" {
  type        = string
  description = "실행 잔류 한도 (accentury.analysis.processing-timeout). ai-timeout x 3 + 백오프(255.9초)보다 길어야 기동이 통과한다 (AnalysisDispatchConfig). 그 위의 반올림 300초로 확정 (KAN-172). 코드 기본값과 같다."
  default     = "300s"
}

variable "analysis_dispatch_concurrency" {
  type        = number
  description = "분석 전달 워커 수 (accentury.analysis.dispatch-concurrency). AI가 추론을 한 번에 하나만 돌리고 8GB에서 2건이면 OOM이라(KAN-57) 태스크 하나가 보내는 동시 호출을 1로 묶는다 (KAN-22, KAN-172 확정). 태스크 여러 개가 동시에 뜨면 그만큼은 여전히 겹친다 - 아래 README 참고. 코드 기본값과 같다."
  default     = 1

  validation {
    condition     = var.analysis_dispatch_concurrency >= 1
    error_message = "analysis_dispatch_concurrency는 1 이상이어야 합니다."
  }
}

variable "ai_analysis_timeout_seconds" {
  type        = number
  description = "AI 서버가 분석 1건에 거는 상한(초, ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS). backend의 analysis_ai_timeout보다 짧아야 한다 (KAN-22). 단일 lock 대기와 워커 재적재 대기까지 포함하는 값이라 롤링 배포 중 backend 태스크 최대 6개(상한 3 x 200%)가 겹친 6 x P95 11초 = 67초와 재적재 31초 + 추론 11초 = 42초를 덮는 75초로 확정 (KAN-172, Codex 리뷰 P1 - 짧으면 추론 중인 요청을 끊어 멀쩡한 워커를 죽이고 재전송이 새 워커를 또 죽인다). 코드 기본값과 같다."
  default     = 75
}

variable "training_bucket_name" {
  type        = string
  description = "staging 전용 학습 데이터 S3 버킷 이름 (KAN-201). 값이 있으면 ACCENTURY_TRAINING_BUCKET 파라미터를 만들어 backend가 분석 종결마다 음성 WAV와 메타 JSON을 그 버킷에 남긴다 (accentury.training.bucket). null이면 파라미터 자체가 없고 backend는 저장 코드를 만들지 않는다 - prod는 반드시 null이다 (FR-DP-01 그대로)."
  default     = null
}
