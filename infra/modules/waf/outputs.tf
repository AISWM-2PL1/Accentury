output "web_acl_arn" {
  value       = aws_wafv2_web_acl.this.arn
  description = "CloudFront 배포의 web_acl_id에 넣는다 (WAFv2는 ID가 아니라 ARN)."
}

output "log_group_name" {
  value       = aws_cloudwatch_log_group.waf.name
  description = "us-east-1의 WAF 로그 그룹. Count 관찰과 차단 확인은 여기서 한다 (README 'WAF 웹 ACL')."
}
