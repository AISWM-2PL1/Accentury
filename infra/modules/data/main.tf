# RDS PostgreSQL 프로비저닝 (KAN-122).
#
# PostgreSQL 16: backend/docker-compose.yml의 로컬 버전(postgres:16)과 동일 메이저.
# 단일 AZ, 퍼블릭 액세스 차단, 사설 서브넷 배치. 다중 AZ는 프로토타입 범위 외.

terraform {
  required_providers {
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }
}

locals {
  name = "accentury-${var.env}"
}

# 최종 스냅샷 이름에 붙는 고유 접미사. 이름이 고정이면 스택을 재구축한 뒤 두 번째
# destroy가 기존 스냅샷과의 이름 충돌로 거부된다. timestamp()는 매 plan에 값이
# 바뀌어 "plan 변경 없음" AC를 깨므로 state에 고정되는 random_id를 쓴다 -
# 스택 incarnation마다 새 값, 한 incarnation 안에서는 불변.
resource "random_id" "final_snapshot" {
  count = var.skip_final_snapshot ? 0 : 1

  byte_length = 4
}

resource "aws_db_subnet_group" "this" {
  name       = "${local.name}-rds"
  subnet_ids = var.private_subnet_ids

  tags = { Name = "${local.name}-rds" }
}

resource "aws_db_instance" "this" {
  identifier = local.name

  engine = "postgres"
  # 메이저 버전만 고정한다. 마이너는 자동 업그레이드(auto_minor_version_upgrade
  # 기본 true)에 맡기고, provider가 메이저 접두사 일치로 plan 드리프트를 내지 않는다.
  engine_version = "16"

  instance_class    = var.instance_class
  allocated_storage = var.allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "accentury"
  username = "accentury"
  # 마스터 비밀번호는 RDS가 생성해 Secrets Manager에 보관한다 (KAN-122의
  # "Secrets Manager 또는 SSM SecureString" 중 전자). 코드, tfvars, state 어디에도
  # 평문 비밀번호가 존재하지 않고, 환경별 시크릿이 자동으로 분리된다.
  manage_master_user_password = true

  multi_az               = false
  publicly_accessible    = false
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.rds_sg_id]

  backup_retention_period = 7

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${local.name}-final-${random_id.final_snapshot[0].hex}"

  tags = { Name = local.name }
}
