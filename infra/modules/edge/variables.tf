variable "env" {
  type        = string
  description = "환경 이름 (staging | prod)"
}

variable "domain" {
  type        = string
  description = "이 환경의 도메인 (prod = accentury.app, staging = staging.accentury.app)"
}

variable "vpc_id" {
  type        = string
  description = "internal ALB가 들어갈 VPC"
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "internal ALB 배치용 사설 서브넷 (서로 다른 AZ 2개, RDS와 재사용)"
}

variable "alb_sg_id" {
  type        = string
  description = "alb-sg. CloudFront VPC 오리진 서비스 SG 인바운드 규칙을 이 모듈이 추가한다."
}

variable "instance_id" {
  type        = string
  description = "대상 그룹에 붙일 EC2 인스턴스"
}

variable "acm_certificate_arn" {
  type        = string
  description = "us-east-1 와일드카드 인증서 - CloudFront 뷰어 구간용 (KAN-119, data 소스로만 참조)"
}

variable "alb_certificate_arn" {
  type        = string
  description = "ALB 리전(ap-northeast-2) 와일드카드 인증서 - CloudFront에서 ALB로 가는 오리진 구간 HTTPS용 (KAN-119, KAN-125, data 소스로만 참조)"
}

variable "zone_id" {
  type        = string
  description = "accentury.app 호스팅 영역 (KAN-119, data 소스로만 참조)"
}

variable "web_acl_arn" {
  type        = string
  description = "CloudFront 배포에 붙일 WAFv2 웹 ACL ARN (KAN-149, modules/waf 출력, us-east-1 CLOUDFRONT 스코프)"
}
