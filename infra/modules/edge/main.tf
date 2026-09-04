# internal ALB + CloudFront VPC 오리진 + S3 정적 호스팅 (KAN-125, KAN-126).
#
# 대상은 backend Fargate 태스크다 (target_type ip, KAN-165). 태스크가 교체되고 늘어도(KAN-168)
# 바뀌지 않는 고정 오리진 지점, 헬스체크, 그리고 태스크 간 분산이 목적이다. 스킴은 internal -
# 퍼블릭 IP가 없어 CloudFront를 우회해 ALB에 도달하는 경로 자체가 없다 (2026-08-19 멘토링).

locals {
  name = "accentury-${var.env}"
}

data "aws_caller_identity" "current" {}

# ---- internal ALB와 대상 그룹 (KAN-125) ----

resource "aws_lb" "this" {
  name               = "${local.name}-alb"
  internal           = true
  load_balancer_type = "application"
  security_groups    = [var.alb_sg_id]
  subnets            = var.private_subnet_ids

  # 유휴 타임아웃은 기본 60초 유지 (KAN-125). 업로드는 202 즉시 응답,
  # 상태는 폴링이라 장시간 연결이 없다.
}

# 대상은 ECS 서비스가 등록하는 태스크 IP다 (KAN-165). target_type은 교체 강제 속성이라 instance(KAN-125)에서
# ip로 바꾸며 그룹이 새로 만들어졌고, 리스너가 옛 그룹을 가리키는 동안 옛 그룹을 지울 수 없으므로(ResourceInUse)
# create_before_destroy로 새 그룹 -> 리스너 전환 -> 옛 그룹 삭제 순서를 만든다. 대상 그룹 이름은 VPC 안에서
# 유일해야 해서 옛 이름(-be)을 못 쓴다 - 그래서 -backend다.
resource "aws_lb_target_group" "backend" {
  name        = "${local.name}-backend"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  # 종료 예산 (KAN-166, KAN-165): 대상이 빠진 뒤 진행 중 요청을 마무리할 시간. backend의 HTTP 요청은 전부 1초
  # 미만이고 readiness 하강(집계 health 503)이 이 등록 해제와 겹쳐 돌므로 기본 300초는 롤링 배포와 스케일인만
  # 늦춘다. ECS는 이 시간이 지나야 태스크에 SIGTERM을 보낸다.
  deregistration_delay = 30

  health_check {
    # KAN-131이 인증 없이 종합 status 한 줄만 노출하는 경로. readiness 하강(KAN-166)이 여기 반영되므로
    # 종료 중인 태스크는 새 요청을 받지 않는다. 여기에 인증이 걸리면 ALB가 대상을 계속 비정상 판정한다
    # (KAN-125 주의사항).
    path    = "/actuator/health"
    matcher = "200"
    # 기본값(30초 x 5회 = 2분 30초)이면 새 태스크가 healthy로 잡히는 데 그만큼 걸려 롤링 배포와 회로 차단기
    # 판정이 늦다. 10초 x 3회 = 30초로 줄인다 - 실패 판정도 30초라 ECS가 죽은 태스크를 빨리 교체한다.
    interval            = 10
    timeout             = 5
    healthy_threshold   = 3
    unhealthy_threshold = 3
  }

  lifecycle {
    create_before_destroy = true
  }
}

# 오리진 구간(CloudFront -> ALB)도 HTTPS다 (KAN-125, 2026-08-25 확정). 처음에는 HTTP 80으로
# 시작했지만 AWS 문서 확인 결과 전환에 걸림돌이 없었다:
#   - CloudFront는 오리진 인증서가 신뢰 CA 발급(ELB는 ACM 가능)이고, 인증서 도메인이
#     Origin domain 값 "또는 오리진으로 전달되는 Host 헤더" 중 하나와 일치하면 받아들인다
#     (Require HTTPS for communication between CloudFront and your custom origin).
#   - /v0/*, /admin/v0/* 동작은 Managed-AllViewer라 Host(accentury.app 등)가 ALB까지 온다.
#     그래서 ALB DNS 이름(internal-*.elb.amazonaws.com)과 인증서가 안 맞아도 문제없고,
#     서울 리전 ACM 인증서(accentury.app + *.accentury.app, KAN-119)로 충분하다.
#   - VPC 오리진 문서는 NLB TLS 리스너만 미지원으로 적고 ALB HTTPS에는 제약이 없다.
# HTTP 80 리스너는 두지 않는다 - 평문 경로를 남길 이유가 없고, alb-sg도 443만 연다.
#
# 리소스 이름이 http에서 https로 바뀌었다. state에 옛 리스너가 있는 환경(이 변경 전에
# 지은 스택)에서는 moved가 없으면 삭제 후 생성이 되어 그 사이 /v0/*, /admin/v0/* 전체가
# 502가 된다. port, protocol, certificate_arn, ssl_policy는 전부 제자리 수정 가능한
# 속성(load_balancer_arn만 교체 강제)이라 moved만 있으면 ModifyListener 한 번으로 끝난다
# (PR 리뷰 반영, 2026-08-25).
moved {
  from = aws_lb_listener.http
  to   = aws_lb_listener.https
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  # TLS 1.2 이상 + 1.3. 클라이언트는 CloudFront뿐이라 구형 호환은 필요 없다.
  # 이름은 2026-08-25 `aws elbv2 describe-ssl-policies`로 확인.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = var.alb_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}

# ---- CloudFront VPC 오리진 (KAN-126) ----

resource "aws_cloudfront_vpc_origin" "alb" {
  vpc_origin_endpoint_config {
    name = "${local.name}-alb"
    arn  = aws_lb.this.arn
    # http_port는 필수 속성이라 적지만 https-only 정책에서는 쓰이지 않는다.
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "https-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

# VPC 오리진이 alb-sg에 요구하는 인바운드 규칙 (KAN-121, KAN-126의 확인 항목):
# AWS 문서 "Use Amazon CloudFront with VPC origins" 기준, 계정에서 첫 VPC 오리진을
# 만들면 CloudFront가 해당 VPC에 CloudFront-VPCOrigins-Service-SG 보안 그룹을 만들어
# 오리진행 ENI에 붙인다. 오리진(ALB) SG는 그 SG를 소스로 허용하면 된다.
# IP 대역이 아니라 SG 참조라 KAN-121의 참조 사슬 원칙과도 맞는다.
# 이 SG는 VPC 오리진 생성 후에야 존재하므로 depends_on으로 조회를 apply 시점으로 늦춘다.
#
# 리뷰 기각 기록 (Codex P1, 2026-08-24): "오리진 생성 전에 관리형 접두사 목록
# (origin-facing)을 먼저 허용해야 한다"는 지적은 반영하지 않았다. 그 목록은 퍼블릭
# 오리진으로 나가는 CloudFront의 공인 IP 대역이고, VPC 오리진 트래픽은 VPC 안
# ENI의 사설 IP에서 오므로 매칭되지 않는다. KAN-121이 이 방식을 폐기한 결정과도
# 충돌한다. SG 규칙은 데이터 플레인만 막을 뿐 오리진 생성(Deploying)을 실패시키지
# 않으며, 같은 apply 안에서 오리진 생성 직후 이 규칙이 만들어져 배포가 트래픽을
# 흘리기 전에 자리를 잡는다. 실규칙 검증은 staging apply에서 한다 (KAN-121).
data "aws_security_group" "cloudfront_vpc_origins" {
  vpc_id = var.vpc_id

  filter {
    name   = "group-name"
    values = ["CloudFront-VPCOrigins-Service-SG"]
  }

  depends_on = [aws_cloudfront_vpc_origin.alb]
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id            = var.alb_sg_id
  description                  = "HTTPS from CloudFront VPC origin ENIs only"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = data.aws_security_group.cloudfront_vpc_origins.id
}

# ---- S3 정적 호스팅 (web 번들 + 등급 이미지, KAN-126) ----

resource "aws_s3_bucket" "web" {
  # S3 버킷 이름은 전역 유일이라 계정 ID를 붙인다.
  bucket = "${local.name}-web-${data.aws_caller_identity.current.account_id}"

  # teardown 시 객체가 남아 있어도 버킷을 지울 수 있게 한다. 내용물은 배포
  # 파이프라인(KAN-127)이 다시 만들 수 있는 산출물이라 잃어도 복구 가능하다.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket = aws_s3_bucket.web.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${local.name}-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# 해당 CloudFront 배포만 버킷을 읽을 수 있다 (직접 URL 접근 차단, KAN-126 AC).
data "aws_iam_policy_document" "web_bucket" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.web.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id
  policy = data.aws_iam_policy_document.web_bucket.json
}

# ---- CloudFront 배포 (KAN-126) ----

# SPA 라우팅용 경로 재작성. 배포 수준 사용자 정의 오류 응답은 쓰지 않는다 -
# /v0/*의 JSON 오류 봉투까지 index.html로 치환해 버린다 (KAN-126).
# 소스 파일 하나를 정본으로 두고 환경별 Function 2개를 만든다 (2026-08-24 확정,
# 환경별 Terraform state 분리 때문에 단일 Function 공유 소유가 불가).
resource "aws_cloudfront_function" "spa_rewrite" {
  name    = "${local.name}-spa-rewrite"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/spa-rewrite.js")
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "accentury ${var.env}"
  default_root_object = "index.html"
  aliases             = [var.domain]
  # 한국은 PriceClass_200에 포함된다. 북미, 유럽 외 엣지가 빠지는 PriceClass_100은
  # 주 사용자(한국)를 놓치고, All은 남미, 호주 비용만 더 낸다.
  price_class = "PriceClass_200"

  # WAF 웹 ACL (KAN-149, modules/waf). WAFv2는 ID가 아니라 ARN을 넣는다. 배포 앞단에서
  # 요청을 먼저 거르므로 차단된 요청은 ALB와 backend 로그에 남지 않는다 - 원인 추적은
  # us-east-1의 WAF 로그 그룹에서 한다 (README 'WAF 웹 ACL').
  web_acl_id = var.web_acl_arn

  origin {
    origin_id                = "s3-web"
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  origin {
    origin_id   = "vpc-alb"
    domain_name = aws_lb.this.dns_name

    vpc_origin_config {
      vpc_origin_id = aws_cloudfront_vpc_origin.alb.id
    }
  }

  # API 경로가 기본 동작보다 앞이어야 한다 (KAN-126, ordered가 default보다 우선).
  ordered_cache_behavior {
    path_pattern     = "/v0/*"
    target_origin_id = "vpc-alb"

    allowed_methods = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods  = ["GET", "HEAD"]

    # 캐싱 비활성 + 전 헤더 전달(Authorization 포함). correlation ID 박제와
    # 4xx TTL 우려(KAN-101)를 캐싱 비활성으로 해소한다.
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id

    viewer_protocol_policy = "redirect-to-https"
    compress               = true
  }

  # 관리자 API (명세서 §6, KAN-26 버전 전환/롤백, KAN-106 집계 조회). internal ALB
  # 구조에서 CloudFront가 백엔드로 가는 유일한 경로라, 이 동작이 없으면 §6 API가
  # 배포에서 도달 불가가 되고 E2E 스모크(KAN-138)의 /admin/v0/analytics 검증도
  # 기본 동작에 빠져 index.html로 치환된다 (2026-08-24 추가). 인증은 백엔드의
  # X-Admin-Token이 한다.
  ordered_cache_behavior {
    path_pattern     = "/admin/v0/*"
    target_origin_id = "vpc-alb"

    allowed_methods = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods  = ["GET", "HEAD"]

    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id

    viewer_protocol_policy = "redirect-to-https"
    compress               = true
  }

  default_cache_behavior {
    target_origin_id = "s3-web"

    allowed_methods = ["GET", "HEAD"]
    cached_methods  = ["GET", "HEAD"]

    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    # SPA 재작성은 기본 동작에만 붙인다. /v0/*에는 붙이지 않는다 (KAN-126).
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = var.acm_certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# ---- Route 53 레코드 (KAN-126, KAN-119 레코드 계획 2종 중 이 환경 몫) ----
# 호스팅 영역은 Terraform 소유가 아니다 (data 소스 참조만, KAN-140).

resource "aws_route53_record" "a" {
  zone_id = var.zone_id
  name    = var.domain
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "aaaa" {
  zone_id = var.zone_id
  name    = var.domain
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false
  }
}
