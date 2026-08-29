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

variable "ec2_instance_id" {
  type        = string
  description = "backend와 ai가 도는 EC2 인스턴스 ID (InstanceId 차원)"
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

variable "ec2_surplus_credit_threshold" {
  type        = number
  description = "EC2 초과(surplus) CPU 크레딧 상한. t3.small은 시간당 24개를 벌고 576개가 쌓이면 초과분이 과금되기 시작한다. 기본 144는 여섯 시간치 벌이만큼 빚을 진 상태로, 과금 시작선의 4분의 1이다."
  default     = 144

  validation {
    condition     = var.ec2_surplus_credit_threshold > 0
    error_message = "ec2_surplus_credit_threshold는 0보다 커야 합니다. 0으로 두면 기동 직후의 짧은 스파이크에도 경보가 섭니다."
  }
}
