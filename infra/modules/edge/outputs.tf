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
