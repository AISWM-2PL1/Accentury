variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "RDS 서브넷 그룹에 넣을 사설 서브넷 (서로 다른 AZ 2개)"
}

variable "rds_sg_id" {
  type        = string
  description = "rds-sg (backend-sg만 5432 허용, KAN-165)"
}

variable "instance_class" {
  type        = string
  description = "RDS 인스턴스 클래스 (db.t4g.micro)"
}

variable "allocated_storage" {
  type        = number
  description = "스토리지 크기 GiB"
  default     = 20
}

variable "deletion_protection" {
  type        = bool
  description = "삭제 보호 (prod true). teardown 시에는 먼저 false로 apply해야 destroy가 된다."
}

variable "skip_final_snapshot" {
  type        = bool
  description = "삭제 시 최종 스냅샷 생략 여부 (staging true, prod false)"
}
