variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"

  validation {
    condition     = contains(["staging", "prod"], var.env)
    error_message = "env는 staging 또는 prod여야 합니다."
  }
}

variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR (환경 간 겹치지 않게)"
}

variable "azs" {
  type        = list(string)
  description = "서브넷을 배치할 가용 영역 2개 (ALB 필수 요건)"

  validation {
    condition     = length(var.azs) == 2
    error_message = "azs는 정확히 2개여야 합니다 (ALB가 서로 다른 AZ 2개를 요구)."
  }
}

variable "public_subnet_cidrs" {
  type        = list(string)
  description = "퍼블릭 서브넷 CIDR 2개 (EC2 배치, azs와 같은 순서)"

  validation {
    condition     = length(var.public_subnet_cidrs) == 2
    error_message = "public_subnet_cidrs는 정확히 2개여야 합니다."
  }
}

variable "private_subnet_cidrs" {
  type        = list(string)
  description = "사설 서브넷 CIDR 2개 (internal ALB와 RDS 배치, azs와 같은 순서)"

  validation {
    condition     = length(var.private_subnet_cidrs) == 2
    error_message = "private_subnet_cidrs는 정확히 2개여야 합니다."
  }
}

variable "private_zone_name" {
  type        = string
  description = "backend -> ai 내부 호출용 Route 53 프라이빗 호스팅 영역 이름 (KAN-36). 두 환경이 같은 값이다 - VPC별로 풀린다."
  default     = "accentury.internal"

  validation {
    condition     = !endswith(var.private_zone_name, ".")
    error_message = "private_zone_name은 끝에 점이 없어야 합니다 (ai.<영역> 형태로 이름을 조립한다)."
  }
}
