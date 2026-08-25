# 계정 공유 리소스(호스팅 영역, 인증서)는 import하지 않고 data로 조회만 한다
# (KAN-140, 2026-08-20 확정). Terraform이 소유하지 않으므로 destroy 대상에
# 포함되지 않는다.

data "aws_route53_zone" "this" {
  name = var.hosted_zone_name
}

# 인증서 조회는 도메인 + 태그로 고정한다. domain은 주 도메인 이름만 비교하고 SAN은
# 보지 않으며(2026-08-25 실측: domain = "*.accentury.app"은 empty result), data 소스가
# SAN 목록을 내보내지도 않는다. 그래서 나중에 apex 전용 인증서가 하나 더 발급되면
# most_recent가 그것을 잡아 staging.accentury.app이 인증서 불일치로 502가 난다.
# 와일드카드 인증서 2장(us-east-1, 서울)에 accentury-role = wildcard 태그를 붙여 두고
# (KAN-119, README 사전 요건) 그 태그로만 고른다. 태그가 없으면 plan이 empty result로
# 시끄럽게 실패한다 - 조용한 502보다 낫다 (PR 리뷰 반영, 2026-08-25).
locals {
  wildcard_certificate_tags = { accentury-role = "wildcard" }
}

data "aws_acm_certificate" "cloudfront" {
  provider = aws.us_east_1

  # 발급 때 지정한 주 도메인 이름과 같아야 한다. 2026-08-24 CLI 확인 결과
  # 주 도메인은 accentury.app이고 SAN에 *.accentury.app이 있다.
  domain      = var.acm_certificate_domain
  statuses    = ["ISSUED"]
  tags        = local.wildcard_certificate_tags
  most_recent = true
}

# 같은 도메인의 서울 리전 인증서 (KAN-119의 2장째). ALB 443 리스너에 걸어 오리진
# 구간도 HTTPS로 만든다 (KAN-125). 2026-08-25 CLI 확인: accentury.app + *.accentury.app, ISSUED.
data "aws_acm_certificate" "alb" {
  domain      = var.acm_certificate_domain
  statuses    = ["ISSUED"]
  tags        = local.wildcard_certificate_tags
  most_recent = true
}

module "network" {
  source = "../../modules/network"

  env                  = var.env
  vpc_cidr             = var.vpc_cidr
  azs                  = var.azs
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
}

module "data" {
  source = "../../modules/data"

  env                 = var.env
  private_subnet_ids  = module.network.private_subnet_ids
  rds_sg_id           = module.network.rds_sg_id
  instance_class      = var.db_instance_class
  deletion_protection = var.db_deletion_protection
  skip_final_snapshot = var.db_skip_final_snapshot
}

module "compute" {
  source = "../../modules/compute"

  env           = var.env
  subnet_id     = module.network.public_subnet_ids[0]
  ec2_sg_id     = module.network.ec2_sg_id
  instance_type = var.instance_type
  ssm_prefix    = var.ssm_prefix
}

module "edge" {
  source = "../../modules/edge"

  env                 = var.env
  domain              = var.domain
  vpc_id              = module.network.vpc_id
  private_subnet_ids  = module.network.private_subnet_ids
  alb_sg_id           = module.network.alb_sg_id
  instance_id         = module.compute.instance_id
  acm_certificate_arn = data.aws_acm_certificate.cloudfront.arn
  alb_certificate_arn = data.aws_acm_certificate.alb.arn
  zone_id             = data.aws_route53_zone.this.zone_id
}
