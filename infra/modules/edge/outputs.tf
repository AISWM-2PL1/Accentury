output "distribution_id" {
  value       = aws_cloudfront_distribution.this.id
  description = "웹 배포 파이프라인의 캐시 무효화 대상 (KAN-127)"
}

output "distribution_domain_name" {
  value = aws_cloudfront_distribution.this.domain_name
}

output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "web_bucket" {
  value       = aws_s3_bucket.web.bucket
  description = "web 번들과 등급 이미지 업로드 대상 (KAN-127)"
}

output "web_bucket_arn" {
  value       = aws_s3_bucket.web.arn
  description = "배포 역할의 업로드 권한 범위 (KAN-127, modules/deploy)"
}

output "distribution_arn" {
  value       = aws_cloudfront_distribution.this.arn
  description = "배포 역할의 무효화 권한 범위 (KAN-127, modules/deploy)"
}

output "alb_arn_suffix" {
  value       = aws_lb.this.arn_suffix
  description = "CloudWatch AWS/ApplicationELB의 LoadBalancer 차원 값 (KAN-134)"
}

output "target_group_arn_suffix" {
  value       = aws_lb_target_group.backend.arn_suffix
  description = "CloudWatch AWS/ApplicationELB의 TargetGroup 차원 값 (KAN-134)"
}
