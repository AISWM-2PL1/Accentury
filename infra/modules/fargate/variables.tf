variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"
}

variable "subnet_ids" {
  type        = list(string)
  description = "태스크 ENI를 둘 퍼블릭 서브넷 2개 (AZ 2개). 사설 서브넷은 NAT도 VPC 엔드포인트도 없어 ECR pull과 SSM에 닿을 길이 없다 (KAN-121, KAN-165)."

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "subnet_ids는 2개 이상이어야 합니다 (AZ 장애 시 다른 AZ에 태스크를 띄운다)."
  }
}

variable "security_group_id" {
  type        = string
  description = "backend-sg (alb-sg만 8080). network 모듈 출력 backend_sg_id."
}

variable "ssm_prefix" {
  type        = string
  description = "이 환경의 SSM Parameter Store 경로 접두사 (/accentury/staging 등, KAN-129). IMAGE_TAG를 여기서 읽는다."

  validation {
    condition     = startswith(var.ssm_prefix, "/accentury/") && !endswith(var.ssm_prefix, "/")
    error_message = "ssm_prefix는 /accentury/로 시작하고 끝에 /가 없어야 합니다 (KAN-129 경로 규약)."
  }
}

variable "config_parameter_names" {
  type        = list(string)
  description = "config 모듈이 만든 backend용 SSM 파라미터 이름 목록 (parameter_names). 전부 태스크 정의 secrets로 주입되고 실행 역할이 그것만 읽는다. 정본은 backend DeploymentConfigGuard.SSM_NAMES다."
}

variable "rds_master_user_secret_arn" {
  type        = string
  description = "이 환경 RDS의 관리형 마스터 시크릿 ARN. 태스크 역할에 이 시크릿 1개의 GetSecretValue만 준다 (KAN-129)."
}

variable "metric_namespace" {
  type        = string
  description = "태스크 역할이 PutMetricData 할 수 있는 유일한 CloudWatch 네임스페이스 (KAN-36). application-deploy.yml의 namespace, monitoring 모듈의 backend_metric_namespace와 같아야 한다."
}

variable "target_group_arn" {
  type        = string
  description = "서비스가 태스크를 등록할 ALB 대상 그룹 (edge 모듈, target_type ip)."
}

variable "alb_listener_arn" {
  type        = string
  description = "대상 그룹이 붙은 리스너 (edge 모듈). 값은 쓰지 않는다 - 서비스 생성이 리스너 뒤에 오도록 순서만 잡는다."
}

variable "task_cpu" {
  type        = number
  description = "태스크 CPU 단위 (1024 = 1 vCPU). 확정 크기 0.5 vCPU (2026-08-28). Fargate는 버스트가 없어 이 값이 곧 상한이다."
  default     = 512
}

variable "task_memory" {
  type        = number
  description = "태스크 메모리 MiB. 확정 크기 2 GB (2026-08-28). JVM 힙은 이 값의 75% (backend/Dockerfile MaxRAMPercentage) = 1.5 GB."
  default     = 2048
}

variable "desired_count" {
  type        = number
  description = "서비스 태스크 수. 오토스케일링(KAN-168) 전까지 1이다."
  default     = 1
}

variable "stop_timeout" {
  type        = number
  description = "SIGTERM 뒤 SIGKILL까지의 초 (KAN-166 종료 예산). Fargate 상한 120. backend의 웹 유예 15초 + 분석 워커 예산 90초보다 길어야 한다."
  default     = 120

  validation {
    condition     = var.stop_timeout >= 105 && var.stop_timeout <= 120
    error_message = "stop_timeout은 105(웹 유예 15초 + 워커 예산 90초) 이상 120(Fargate 상한) 이하여야 합니다 (KAN-166)."
  }
}

variable "health_check_grace_period_seconds" {
  type        = number
  description = "태스크 시작 뒤 ALB와 컨테이너 헬스체크 실패를 무시하는 초. JVM 기동(Flyway + 컨텍스트)이 끝나기 전에 ECS가 태스크를 죽이지 않게 한다. 2026-09-02 staging 실측(README 'backend Fargate 서비스'): 0.5 vCPU에서 Spring 기동 71초, 컨테이너 시작부터 ALB healthy까지 약 76초. 그 2배 안팎인 150초 - 이보다 짧으면 기동 중 태스크가 교체를 반복하고, 길면 죽은 이미지의 회로 차단기 판정(3회)이 그만큼 늦어진다."
  default     = 150
}

variable "container_health_start_period" {
  type        = number
  description = "컨테이너 healthCheck의 startPeriod 초 - 이 동안의 실패는 retries에 세지 않는다. 실측 기동 71초에 여유를 둔 값이고, 그 뒤로도 retries 6 x interval 10초가 더 있어 unhealthy 판정은 최소 180초 뒤다."
  default     = 120
}

variable "log_retention_days" {
  type        = number
  description = "CloudWatch Logs 보존 일수. EC2 시절 json-file 50MB 로테이션의 자리다."
  default     = 14
}
