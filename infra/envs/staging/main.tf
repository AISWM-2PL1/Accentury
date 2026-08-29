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

# backend 컨테이너 환경 변수 (KAN-129). 값이 network, data 모듈 출력이라 여기서 조립한다.
module "config" {
  source = "../../modules/config"

  ssm_prefix                 = var.ssm_prefix
  domain                     = var.domain
  vpc_cidr                   = var.vpc_cidr
  rds_endpoint               = module.data.endpoint
  rds_master_user_secret_arn = module.data.master_user_secret_arn
}

module "compute" {
  source = "../../modules/compute"

  env                        = var.env
  subnet_id                  = module.network.public_subnet_ids[0]
  ec2_sg_id                  = module.network.ec2_sg_id
  instance_type              = var.instance_type
  ssm_prefix                 = var.ssm_prefix
  rds_master_user_secret_arn = module.data.master_user_secret_arn
  # 인스턴스가 SSM 파라미터 생성 뒤에 첫 부팅하도록 순서를 잡는다 (compute/main.tf precondition).
  # IMAGE_TAG는 Terraform 밖이라 재구축 시 이전 값이 남아 있을 수 있어 이 순서가 실제로 문제가 된다.
  config_parameter_names = module.config.parameter_names
}

# CloudFront 앞단 WAF (KAN-149). CLOUDFRONT 스코프 웹 ACL과 그 로그 그룹은 us-east-1에만
# 만들 수 있어 프로바이더 별칭을 넘긴다. Count/Block 전환은 tfvars의 waf_enforce로 한다.
module "waf" {
  source = "../../modules/waf"

  providers = {
    aws = aws.us_east_1
  }

  env        = var.env
  enforce    = var.waf_enforce
  rate_limit = var.waf_rate_limit
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
  web_acl_arn         = module.waf.web_acl_arn
}

# 배포 파이프라인 역할 (KAN-127). GitHub Actions가 OIDC로 맡는다. 공급자는 bootstrap 소유.
module "deploy" {
  source = "../../modules/deploy"

  env                         = var.env
  github_repository           = var.github_repository
  github_owner_id             = var.github_owner_id
  github_repository_id        = var.github_repository_id
  ssm_prefix                  = var.ssm_prefix
  ci_image_push               = var.ci_image_push
  web_bucket_arn              = module.edge.web_bucket_arn
  cloudfront_distribution_arn = module.edge.distribution_arn
}

# 최소 알림 (KAN-134). ALB, RDS, EC2가 이미 내보내는 표준 지표에 경보 4종과 SNS 이메일을 건다.
# 지표를 새로 수집하지 않으므로 backend와 ai 코드에는 영향이 없다. 전체 관측성은 KAN-38.
module "monitoring" {
  source = "../../modules/monitoring"

  env                     = var.env
  alert_email             = var.alert_email
  alb_arn_suffix          = module.edge.alb_arn_suffix
  target_group_arn_suffix = module.edge.target_group_arn_suffix
  db_instance_identifier  = module.data.instance_identifier
  ec2_instance_id         = module.compute.instance_id
}
