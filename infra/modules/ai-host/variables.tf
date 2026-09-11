variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"
}

variable "subnet_ids" {
  type        = list(string)
  description = "퍼블릭 서브넷 (2026-08-20 결정, NAT 회피). ASG가 전부를 vpc_zone_identifier로 써 AZ 장애에도 교체가 된다."

  validation {
    condition     = length(var.subnet_ids) >= 1
    error_message = "subnet_ids는 1개 이상이어야 합니다."
  }
}

variable "security_group_id" {
  type        = string
  description = "ai-sg (backend-sg만 8000, SSH 없음. KAN-121, KAN-36, KAN-165)."
}

variable "instance_type" {
  type        = string
  description = "인스턴스 타입. x86_64 전제 - 이미지도 linux/amd64로 빌드해야 한다. c7i.xlarge (KAN-36, 2026-09-01 결정으로 A단계부터)."
}

variable "ssm_prefix" {
  type        = string
  description = "이 환경의 SSM Parameter Store 경로 접두사 (/accentury/staging 등, KAN-129). 이 호스트는 그 아래 /ai 하위 경로와 IMAGE_TAG만 읽는다 (KAN-36)."

  validation {
    condition     = startswith(var.ssm_prefix, "/accentury/")
    error_message = "ssm_prefix는 /accentury/로 시작해야 합니다 (KAN-129 경로 규약)."
  }
}

variable "compose_version" {
  type        = string
  description = "설치할 docker compose 플러그인 버전 (재현 가능한 프로비저닝을 위해 고정)"
  default     = "v2.39.1"
}

variable "root_volume_size" {
  type        = number
  description = "루트 볼륨 GiB. 스텁 20, 실모델(KAN-36 B단계) tfvars 40 - ai 이미지 7GB x SHA 태그 2개 공존 + pull 임시 공간 (근거는 envs tfvars 주석). 값을 바꾸면 시작 템플릿이 새 버전이 되어 인스턴스가 교체된다."
  default     = 20
}

variable "config_parameter_names" {
  type        = list(string)
  description = "config 모듈이 만든 ai 하위 경로 파라미터 이름 목록 (ai_parameter_names). 값은 쓰지 않는다 - 인스턴스가 파라미터 생성 뒤에 첫 부팅하도록 순서만 잡는다 (KAN-129)."
}

variable "log_retention_days" {
  type        = number
  description = "ai 컨테이너 로그의 CloudWatch Logs 보존 일수 (KAN-203). backend 로그 그룹(fargate 모듈의 같은 이름 변수)과 기본값이 같고 두 환경 모두 tfvars로 덮지 않는다 - 사후 추적 창이 계층마다 다르면 한쪽 로그만 남은 시점이 생겨 correlationId로 이을 수 없다."
  default     = 14
}

variable "metric_namespace" {
  type        = string
  description = "이 호스트의 EC2 역할이 PutMetricData 할 수 있는 유일한 CloudWatch 네임스페이스 (KAN-36). health 타이머(ai-health-metric.sh)가 쓰고 monitoring 모듈의 ai-unhealthy 경보가 같은 이름을 본다."
}

variable "private_zone_id" {
  type        = string
  description = "내부 ALB의 alias A 레코드를 만들 프라이빗 호스팅 영역 (network 모듈 private_zone_id, KAN-36, KAN-201)."
}

variable "dns_name" {
  type        = string
  description = "그 레코드 이름 - backend의 ai-base-url 호스트 (network 모듈 ai_dns_name, KAN-36). KAN-201부터 인스턴스 IP가 아니라 ALB alias다."
}

variable "vpc_id" {
  type        = string
  description = "대상 그룹이 속할 VPC (network 모듈 vpc_id, KAN-201)."
}

variable "alb_security_group_id" {
  type        = string
  description = "내부 ALB의 SG (network 모듈 ai_alb_sg_id, KAN-201) - backend-sg만 8000 인바운드, ai-sg로만 8000 아웃바운드."
}

variable "min_size" {
  type        = number
  description = "ASG 최소 대수이자 초기 desired (KAN-201). 평시 1대 - 스케일링 정책이 밀릴 때만 늘리고 15분 한산하면 여기까지 줄인다."
  default     = 1

  validation {
    condition     = var.min_size >= 1
    error_message = "min_size는 1 이상이어야 합니다 - 0이면 AI가 아예 없어 backend 회로가 열린 채 머뭅니다."
  }
}

variable "max_size" {
  type        = number
  description = "ASG 최대 대수 (KAN-201). 두 환경 tfvars 3. 계정의 On-Demand Standard vCPU 쿼터 256(2026-09-11 실측)에서 c7i.xlarge(4 vCPU)는 두 환경 합산 64대까지 가능하므로 이 값은 비용 상한이지 쿼터 한계가 아니다. min_size 이상이어야 한다."
  default     = 1

  validation {
    condition     = var.max_size >= 1
    error_message = "max_size는 1 이상이어야 합니다."
  }
}

variable "health_check_grace_period" {
  type        = number
  description = "ASG가 새 인스턴스의 대상 그룹 상태 검사 결과를 무시하는 시간(초, KAN-201). 첫 부팅 = docker 설치 + 이미지 7GB pull + 모델 적재를 덮어야 한다 - 짧으면 워밍업 중인 새 인스턴스를 종료해 교체가 무한히 돈다. 파이프라인의 원격 healthy 대기(600초)와 pull 시간을 더한 값이고, staging 실측(2026-09-11, 아래 README)으로 확정한다."
  default     = 900
}

variable "alb_idle_timeout" {
  type        = number
  description = "내부 ALB 유휴 타임아웃(초, KAN-201). backend 읽기 타임아웃(config 모듈 analysis_ai_timeout 85초)보다 길어야 한다 - 짧으면 긴 추론을 ALB가 먼저 504로 끊고 backend가 재전송해 같은 오디오를 얹는다."
  default     = 90

  validation {
    condition     = var.alb_idle_timeout > 85
    error_message = "alb_idle_timeout은 backend의 ai-timeout(85초)보다 길어야 합니다."
  }
}

variable "deregistration_delay" {
  type        = number
  description = "대상이 빠진 뒤 진행 중 요청을 마무리할 시간(초, KAN-201). AI 상한(ai_analysis_timeout_seconds 75초)보다 길어야 축소나 교체로 빠지는 인스턴스의 진행 중 추론이 끝난다."
  default     = 90
}

variable "scaling_metric_namespace" {
  type        = string
  description = "스케일링 기준 지표(accentury.analysis.processing.value)가 있는 CloudWatch 네임스페이스 - backend 것(accentury/backend)이다 (KAN-201)."
}

variable "scale_out_threshold" {
  type        = number
  description = "진행 중 분석이 이 건수 이상으로 2분 연속이면 +1 (KAN-201). backend의 폴링 혼잡 임계치(congestion-threshold 6, KAN-172)와 같은 값 - 서버가 폴링 간격을 올려 압력을 빼는 것과 같은 지점에서 처리량도 늘린다. AI가 1건 10초라 6건은 대기열 1분이다."
  default     = 6
}

variable "scale_in_threshold" {
  type        = number
  description = "진행 중 분석이 이 건수 이하로 15분 연속이면 -1 (KAN-201). 1은 거의 빈 상태다 - 2대 이상이 동시에 바쁜 상태(2 이상)에서는 줄이지 않는다."
  default     = 1
}

variable "scale_out_warmup" {
  type        = number
  description = "확대 뒤 새 인스턴스가 지표에 기여한다고 보기까지의 시간(초, KAN-201) - 그동안 같은 경보로 또 늘리지 않는다. 첫 부팅 시간(health_check_grace_period와 같은 근거)이다."
  default     = 600
}

variable "vpc_cidr" {
  type        = string
  description = "컨테이너 egress 허용 대역 - 그 밖(인터넷, IMDS)은 호스트 iptables가 버린다 (ai-egress-guard.sh, KAN-36)."
}
