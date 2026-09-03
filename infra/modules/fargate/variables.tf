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
  description = "서비스를 처음 만들 때의 태스크 수. 그 뒤는 오토스케일링(KAN-168)이 min과 max 사이에서 조절하고 Terraform은 이 값을 다시 보지 않는다 (ignore_changes). min 이상 max 이하여야 한다."
  default     = 1
}

variable "alb_arn_suffix" {
  type        = string
  description = "ALB의 CloudWatch 차원 값 (aws_lb.arn_suffix, app/이름/id). 목표 추적 정책의 resource_label 앞부분 (KAN-168). edge 모듈 출력 alb_arn_suffix."

  validation {
    condition     = startswith(var.alb_arn_suffix, "app/")
    error_message = "alb_arn_suffix는 app/<이름>/<id> 형태여야 합니다 (aws_lb.arn_suffix)."
  }
}

variable "target_group_arn_suffix" {
  type        = string
  description = "대상 그룹의 CloudWatch 차원 값 (aws_lb_target_group.arn_suffix, targetgroup/이름/id). 목표 추적 정책의 resource_label 뒷부분 (KAN-168). edge 모듈 출력 target_group_arn_suffix."

  validation {
    condition     = startswith(var.target_group_arn_suffix, "targetgroup/")
    error_message = "target_group_arn_suffix는 targetgroup/<이름>/<id> 형태여야 합니다 (aws_lb_target_group.arn_suffix)."
  }
}

variable "autoscaling_min_capacity" {
  type        = number
  description = "오토스케일링 하한 (KAN-168). 1로 확정 (2026-09-02) - 2로 예열하면 월 약 24달러(온디맨드 0.5 vCPU / 2 GB 1개)가 더 드는데 프로토타입 트래픽에 과하고, 스파이크 초기 1분에서 2분은 혼잡 판정(pollAfterMs 3000ms)이 흡수하며, AI 서버 1대가 병목이라 backend 2개 상시가 처리량을 늘리지 않는다."
  default     = 1

  validation {
    condition     = var.autoscaling_min_capacity >= 1
    error_message = "autoscaling_min_capacity는 1 이상이어야 합니다 - 0이면 요청을 받을 태스크가 없다."
  }
}

variable "autoscaling_max_capacity" {
  type        = number
  description = "오토스케일링 상한 (KAN-168). 3으로 제한 - AI 서버가 EC2 1대라 backend를 늘려도 분석 처리량은 늘지 않고, backend 확장은 세션 생성과 폴링을 버티는 용도다."
  default     = 3

  validation {
    condition     = var.autoscaling_max_capacity >= 1
    error_message = "autoscaling_max_capacity는 1 이상이어야 합니다."
  }
}

variable "autoscaling_target_requests_per_minute" {
  type        = number
  description = <<-EOT
    목표 추적의 목표값 - 태스크 1개가 1분에 받는 ALB 요청 수 (ALBRequestCountPerTarget, KAN-168). 이 값을 넘는 분이
    3회 연속이면 늘리고, 이 값에 한참 못 미치는 분이 15회 연속이면 줄인다. 초기값 1000의 산정 (2026-09-02):
    - 응시자 1명은 음성 문항 업로드 뒤 pollAfterMs 800ms로 상태를 폴링하므로 분석을 기다리는 동안 분당 약 75건이다
      (세션당 /complete 상한 120건과 같은 자릿수). 응시 전체(세션 1 + 음성 5 + 어휘 5 + 폴링 + 완료 + 결과)로 보면
      3분에서 4분 동안 평균 분당 약 20건이다.
    - 1000이면 태스크 1개가 "동시에 폴링 중인 응시자 약 13명" 또는 "응시 중인 사용자 약 50명"에서 늘어난다. 혼잡
      판정 임계(전 인스턴스 PROCESSING 30건, KAN-167)와 같은 자릿수라 두 장치가 같은 구간에서 같이 동작한다.
    - Fargate 0.5 vCPU의 폴링 처리 한계(수천 건/분)보다 훨씬 낮게 잡아, 늘어나는 데 걸리는 3분(경보) + 2분(기동)
      동안 태스크 1개가 포화되지 않게 한다.
    KAN-169의 부하 시험 결과로 조정한다 - 값은 두 환경이 같아야 하므로 tfvars가 아니라 이 기본값을 고친다.
  EOT
  default     = 1000

  validation {
    condition     = var.autoscaling_target_requests_per_minute > 0
    error_message = "autoscaling_target_requests_per_minute는 0보다 커야 합니다."
  }
}

variable "autoscaling_scale_out_cooldown" {
  type        = number
  description = "스케일아웃 뒤 다음 스케일아웃까지의 초 (KAN-168). 60 - 태스크 기동이 30초에서 2분이라(README 실측 2분 1초) 그보다 짧게 두어 큰 스파이크에는 연달아 늘린다. 목표 추적은 쿨다운 중이라도 더 큰 증가는 허용한다."
  default     = 60
}

variable "autoscaling_scale_in_cooldown" {
  type        = number
  description = "스케일인 뒤 다음 스케일인까지의 초 (KAN-168). 300 - 줄이기는 천천히. 스케일인 경보 자체가 15분 연속 조건이라 이 값은 연속 축소의 간격만 정한다."
  default     = 300
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
