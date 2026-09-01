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
  description = "루트 볼륨 GiB. 스텁 20, 실모델 전환(KAN-36 B단계) 시 tfvars로 40 (가중치 포함 이미지 약 8.5GB x SHA 태그 2개 공존)."
  default     = 20
}

variable "config_parameter_names" {
  type        = list(string)
  description = "config 모듈이 만든 ai 하위 경로 파라미터 이름 목록 (ai_parameter_names). 값은 쓰지 않는다 - 인스턴스가 파라미터 생성 뒤에 첫 부팅하도록 순서만 잡는다 (KAN-129)."
}

variable "metric_namespace" {
  type        = string
  description = "이 호스트의 EC2 역할이 PutMetricData 할 수 있는 유일한 CloudWatch 네임스페이스 (KAN-36). health 타이머(ai-health-metric.sh)가 쓰고 monitoring 모듈의 ai-unhealthy 경보가 같은 이름을 본다."
}

variable "private_zone_id" {
  type        = string
  description = "자기 A 레코드를 UPSERT할 프라이빗 호스팅 영역 (network 모듈 private_zone_id, KAN-36)."
}

variable "dns_name" {
  type        = string
  description = "그 레코드 이름 - backend의 ai-base-url 호스트 (network 모듈 ai_dns_name, KAN-36)."
}

variable "vpc_cidr" {
  type        = string
  description = "컨테이너 egress 허용 대역 - 그 밖(인터넷, IMDS)은 호스트 iptables가 버린다 (ai-egress-guard.sh, KAN-36)."
}
