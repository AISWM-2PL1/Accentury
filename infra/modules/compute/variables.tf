variable "role" {
  type        = string
  description = "이 호스트가 띄우는 서비스 (KAN-36). backend = 고정 EC2 1대(aws_instance, ALB 대상), ai = ASG(min 1, max 1)의 전용 추론 호스트. 두 역할이 user_data, 기동 스크립트, IAM 골격을 공유하고 compose 파일과 SSM 경로만 다르다."

  validation {
    condition     = contains(["backend", "ai"], var.role)
    error_message = "role은 backend 또는 ai여야 합니다."
  }
}

variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"
}

variable "subnet_ids" {
  type        = list(string)
  description = "퍼블릭 서브넷 (2026-08-20 결정, NAT 회피). backend는 첫 번째 하나에 고정 배치하고, ai ASG는 전부를 vpc_zone_identifier로 써 AZ 장애에도 교체가 된다."

  validation {
    condition     = length(var.subnet_ids) >= 1
    error_message = "subnet_ids는 1개 이상이어야 합니다."
  }
}

variable "security_group_id" {
  type        = string
  description = "backend: ec2-sg (alb-sg만 8080). ai: ai-sg (ec2-sg만 8000). 둘 다 SSH 없음 (KAN-121, KAN-36)."
}

variable "instance_type" {
  type        = string
  description = "인스턴스 타입. x86_64 전제 - 이미지도 linux/amd64로 빌드해야 한다. backend t3.small, ai c7i.xlarge (KAN-36, 2026-09-01 결정으로 A단계부터)."
}

variable "ssm_prefix" {
  type        = string
  description = "이 환경의 SSM Parameter Store 경로 접두사 (/accentury/staging 등, KAN-129). ai 역할은 그 아래 /ai 하위 경로와 IMAGE_TAG만 읽는다 (KAN-36)."

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
  description = "루트 볼륨 GiB. backend 20 (이미지 + 로그 여유분). ai는 스텁 20, 실모델 전환(KAN-36 B단계) 시 tfvars로 40 (가중치 포함 이미지 약 8.5GB x SHA 태그 2개 공존)."
  default     = 20
}

variable "rds_master_user_secret_arn" {
  type        = string
  description = "backend 역할 전용. 이 환경 RDS의 관리형 마스터 시크릿 ARN - backend가 연결 시점에 읽으므로(KAN-129) EC2 역할에 이 시크릿 1개의 GetSecretValue만 준다. ai 역할은 null."
  default     = null

  validation {
    condition     = var.role != "backend" || var.rds_master_user_secret_arn != null
    error_message = "backend 역할은 rds_master_user_secret_arn이 필요합니다."
  }
}

variable "config_parameter_names" {
  type        = list(string)
  description = "config 모듈이 만든 SSM 파라미터 이름 목록 (backend: parameter_names, ai: ai_parameter_names). 값은 쓰지 않는다 - 인스턴스가 파라미터 생성 뒤에 첫 부팅하도록 순서만 잡는다 (KAN-129)."
}

variable "metric_namespace" {
  type        = string
  description = "이 호스트의 EC2 역할이 PutMetricData 할 수 있는 유일한 CloudWatch 네임스페이스 (KAN-36). backend는 Micrometer 레지스트리(application-deploy.yml의 namespace와 같아야 한다), ai는 health 타이머(ai-health-metric.sh)가 쓴다. monitoring 모듈의 경보가 같은 이름을 본다."
}

variable "ai" {
  type = object({
    private_zone_id = string # 자기 A 레코드를 UPSERT할 프라이빗 호스팅 영역 (network 모듈)
    dns_name        = string # 그 레코드 이름 - backend의 ai-base-url 호스트 (network 모듈 ai_dns_name)
    vpc_cidr        = string # 컨테이너 egress 허용 대역 - 그 밖(인터넷, IMDS)은 호스트 iptables가 버린다
  })
  description = "ai 역할 전용 값 (KAN-36). backend 역할은 null."
  default     = null

  validation {
    condition     = (var.role == "ai") == (var.ai != null)
    error_message = "ai 역할은 ai 객체가 필요하고, backend 역할은 ai를 주지 않습니다."
  }
}
