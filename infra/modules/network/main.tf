# VPC, 서브넷, 보안 그룹 3종 참조 사슬 (KAN-121).
#
# 보안의 핵심은 참조 사슬이다: CloudFront VPC 오리진 -> alb-sg -> ec2-sg -> rds-sg.
# 각 계층이 바로 앞 계층의 보안 그룹만 허용하므로 앞 단계를 건너뛴 직접 접근이 없다.
# 규칙은 IP 대역이 아니라 보안 그룹 참조로 지정한다 - 인스턴스를 교체해도 규칙을
# 고칠 필요가 없다.

locals {
  name = "accentury-${var.env}"
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = local.name }
}

# 퍼블릭 서브넷 2개: EC2 배치 (2026-08-20 결정).
# EC2를 사설로 내리면 ECR pull과 SSM Session Manager 아웃바운드가 끊겨
# NAT 게이트웨이 또는 VPC 엔드포인트 7종이 필요해진다. ec2-sg가 alb-sg만
# 허용하므로 인바운드는 이미 닫혀 있다.
resource "aws_subnet" "public" {
  count = 2

  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = var.azs[count.index]
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-public-${var.azs[count.index]}" }
}

# 사설 서브넷 2개: internal ALB와 RDS 배치. 아웃바운드 인터넷이 필요 없어
# NAT 게이트웨이를 두지 않는다 (KAN-121).
resource "aws_subnet" "private" {
  count = 2

  vpc_id            = aws_vpc.this.id
  cidr_block        = var.private_subnet_cidrs[count.index]
  availability_zone = var.azs[count.index]

  tags = { Name = "${local.name}-private-${var.azs[count.index]}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  count = 2

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# 사설 라우트 테이블에는 로컬 라우트만 있다 (인터넷 경로 없음).
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${local.name}-private" }
}

resource "aws_route_table_association" "private" {
  count = 2

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# ---- 보안 그룹 3종 ----
# 규칙은 인라인이 아니라 aws_vpc_security_group_*_rule 리소스로 분리한다.
# 인라인로 서로 참조하면 SG 간 순환 참조가 생긴다.

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb-sg"
  description = "internal ALB - inbound only from CloudFront VPC origin service SG"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${local.name}-alb-sg" }
}

# alb-sg 인바운드(CloudFront VPC 오리진 서비스 SG 참조)는 edge 모듈에서 추가한다.
# 그 SG(CloudFront-VPCOrigins-Service-SG)는 계정 첫 VPC 오리진 생성 시점에
# AWS가 만들어 주므로, VPC 오리진 리소스가 있는 쪽에서만 조회할 수 있다.

resource "aws_vpc_security_group_egress_rule" "alb_to_ec2" {
  security_group_id            = aws_security_group.alb.id
  description                  = "forward and health check to EC2 targets"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.ec2.id
}

resource "aws_security_group" "ec2" {
  name        = "${local.name}-ec2-sg"
  description = "EC2 app host - inbound 8080 only from alb-sg, no SSH"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${local.name}-ec2-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "ec2_from_alb" {
  security_group_id            = aws_security_group.ec2.id
  description                  = "backend 8080 from internal ALB only"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id
}

# 아웃바운드 전체 허용: ECR 이미지 pull, SSM Session Manager, RDS 접속이 전부
# EC2가 밖으로 거는 연결이다. SSH(22) 인바운드는 어디에도 열지 않는다 (KAN-121).
resource "aws_vpc_security_group_egress_rule" "ec2_all_ipv4" {
  security_group_id = aws_security_group.ec2.id
  description       = "outbound for ECR pull, SSM, RDS"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "ec2_all_ipv6" {
  security_group_id = aws_security_group.ec2.id
  description       = "outbound for ECR pull, SSM, RDS"
  ip_protocol       = "-1"
  cidr_ipv6         = "::/0"
}

resource "aws_security_group" "rds" {
  name        = "${local.name}-rds-sg"
  description = "RDS - inbound 5432 only from ec2-sg"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${local.name}-rds-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_ec2" {
  security_group_id            = aws_security_group.rds.id
  description                  = "postgres 5432 from EC2 only"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.ec2.id
}
