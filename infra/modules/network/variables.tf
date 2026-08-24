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
