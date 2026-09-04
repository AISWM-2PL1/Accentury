variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"
}

variable "alert_email" {
  type        = string
  description = "경보를 받을 이메일 주소. SNS 이메일 구독은 수신자가 확인 링크를 눌러야 활성화된다 (Terraform이 대신 확인할 수 없다)."

  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", var.alert_email))
    error_message = "alert_email은 이메일 주소 한 개여야 합니다."
  }
}

variable "alb_arn_suffix" {
  type        = string
  description = "ALB의 CloudWatch 차원 값 (aws_lb.arn_suffix, app/이름/id 형태)"
}

variable "target_group_arn_suffix" {
  type        = string
  description = "대상 그룹의 CloudWatch 차원 값 (aws_lb_target_group.arn_suffix, targetgroup/이름/id 형태)"
}

variable "db_instance_identifier" {
  type        = string
  description = "RDS 인스턴스 식별자 (DBInstanceIdentifier 차원)"
}

variable "ecs_cluster_name" {
  type        = string
  description = "backend가 도는 ECS 클러스터 이름 (AWS/ECS 지표의 ClusterName 차원, KAN-165). fargate 모듈 출력 cluster_name."
}

variable "ecs_service_name" {
  type        = string
  description = "backend ECS 서비스 이름 (AWS/ECS 지표의 ServiceName 차원, KAN-165). fargate 모듈 출력 service_name."
}

variable "alb_5xx_threshold" {
  type        = number
  description = "1분 동안 허용하는 5xx 응답 수. 이 값을 넘는 분이 alb_5xx_evaluation_periods만큼 연속되면 알린다."
  default     = 5

  validation {
    condition     = var.alb_5xx_threshold >= 0
    error_message = "alb_5xx_threshold는 0 이상이어야 합니다."
  }
}

variable "alb_5xx_evaluation_periods" {
  type        = number
  description = "5xx 경보가 요구하는 연속 위반 분 수. 티켓 AC의 \"5xx가 연속 발생하면\"을 이 값으로 센다."
  default     = 2

  validation {
    condition     = var.alb_5xx_evaluation_periods >= 1
    error_message = "alb_5xx_evaluation_periods는 1 이상이어야 합니다."
  }
}

variable "rds_free_storage_threshold_bytes" {
  type        = number
  description = "RDS 여유 스토리지 하한 (바이트). 기본 2GiB = gp3 20GiB의 10%."
  default     = 2147483648
}

variable "backend_cpu_threshold" {
  type        = number
  description = "backend ECS 서비스 CPU 평균(%) 상한 (KAN-165). Fargate 0.5 vCPU는 버스트가 없어 지속 80%면 요청이 느려지는 구간이다."
  default     = 80

  validation {
    condition     = var.backend_cpu_threshold > 0 && var.backend_cpu_threshold <= 100
    error_message = "backend_cpu_threshold는 0 초과 100 이하의 퍼센트여야 합니다."
  }
}

variable "backend_cpu_evaluation_periods" {
  type        = number
  description = "backend-cpu-high가 요구하는 연속 위반 분 수. 기동 직후 JIT 스파이크와 스모크 한 바퀴로는 서지 않을 만큼."
  default     = 5

  validation {
    condition     = var.backend_cpu_evaluation_periods >= 1
    error_message = "backend_cpu_evaluation_periods는 1 이상이어야 합니다."
  }
}

variable "backend_memory_threshold" {
  type        = number
  description = "backend ECS 서비스 메모리 평균(%) 상한 (KAN-165). 힙 상한이 태스크 메모리의 75%라 그 위는 네이티브 영역까지 차오른 상태다."
  default     = 85

  validation {
    condition     = var.backend_memory_threshold > 0 && var.backend_memory_threshold <= 100
    error_message = "backend_memory_threshold는 0 초과 100 이하의 퍼센트여야 합니다."
  }
}

variable "backend_memory_evaluation_periods" {
  type        = number
  description = "backend-mem-high가 요구하는 연속 위반 분 수. GC 뒤에도 내려가지 않는 상태를 보려는 값이다."
  default     = 3

  validation {
    condition     = var.backend_memory_evaluation_periods >= 1
    error_message = "backend_memory_evaluation_periods는 1 이상이어야 합니다."
  }
}

variable "ai_metric_namespace" {
  type        = string
  description = "AI 호스트가 Healthy 지표를 올리는 CloudWatch 네임스페이스 (KAN-36). ai-host 모듈의 metric_namespace와 같아야 한다."
  default     = "accentury/ai"
}

variable "backend_metric_namespace" {
  type        = string
  description = "backend가 Micrometer로 지표를 올리는 CloudWatch 네임스페이스 (KAN-36). application-deploy.yml의 management.cloudwatch.metrics.export.namespace와 fargate 모듈의 metric_namespace가 같은 값이다."
  default     = "accentury/backend"
}

variable "ai_unhealthy_evaluation_periods" {
  type        = number
  description = "ai-unhealthy 경보가 요구하는 연속 실패 분 수 (KAN-36). reload 한 번의 컨테이너 재생성(수십 초)으로는 서지 않을 만큼."
  default     = 3

  validation {
    condition     = var.ai_unhealthy_evaluation_periods >= 1
    error_message = "ai_unhealthy_evaluation_periods는 1 이상이어야 합니다."
  }
}

# ---- KAN-38 관측성 경보의 임계치 ----

variable "ai_temp_residue_threshold" {
  type        = number
  description = "AI 임시 디렉터리의 잔존 파일 수 상한 (KAN-38). 이 지표는 처리 중인 파일도 세는데 동시 추론이 구조적으로 12건을 넘지 못하므로(워커 4 x 태스크 3), 정상 부하가 닿지 않는 20을 기본값으로 한다."
  default     = 20

  validation {
    condition     = var.ai_temp_residue_threshold >= 1
    error_message = "ai_temp_residue_threshold는 1 이상이어야 합니다."
  }
}

variable "analysis_backlog_threshold" {
  type        = number
  description = "전 인스턴스의 진행 중 분석 건수 상한 (KAN-38). 폴링 혼잡 임계치(application.yml의 congestion-threshold, 기본 30)의 두 배 - 서버가 폴링 간격을 올려 압력을 뺀 뒤에도 그만큼 쌓였다면 사람이 볼 일이다."
  default     = 60

  validation {
    condition     = var.analysis_backlog_threshold >= 1
    error_message = "analysis_backlog_threshold는 1 이상이어야 합니다."
  }
}

variable "analysis_backlog_evaluation_periods" {
  type        = number
  description = "analysis-backlog-high가 요구하는 연속 위반 분 수. 다섯 문항을 몰아 제출하는 순간으로는 서지 않을 만큼."
  default     = 5

  validation {
    condition     = var.analysis_backlog_evaluation_periods >= 1
    error_message = "analysis_backlog_evaluation_periods는 1 이상이어야 합니다."
  }
}

variable "analysis_timeout_threshold" {
  type        = number
  description = "5분 동안 허용하는 분석 타임아웃 건수 (KAN-38). 실행 잔류와 큐 유실의 합이고, 정상 운영에서는 0이다 - 배포 중 태스크 교체로 나는 한두 건 위에 선을 긋는다."
  default     = 5

  validation {
    condition     = var.analysis_timeout_threshold >= 0
    error_message = "analysis_timeout_threshold는 0 이상이어야 합니다."
  }
}
