variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"
}

variable "subnet_id" {
  type        = string
  description = "EC2를 배치할 퍼블릭 서브넷 (2026-08-20 결정, NAT 회피)"
}

variable "ec2_sg_id" {
  type        = string
  description = "ec2-sg (alb-sg만 8080 허용, SSH 없음)"
}

variable "instance_type" {
  type        = string
  description = "인스턴스 타입 (t3.small). x86_64 전제 - 이미지도 linux/amd64로 빌드해야 한다."
}

variable "ssm_prefix" {
  type        = string
  description = "이 환경의 SSM Parameter Store 경로 접두사 (/accentury/staging 등, KAN-129)"

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
  description = "루트 볼륨 GiB (컨테이너 이미지 2종 + 로그 여유분)"
  default     = 20
}
