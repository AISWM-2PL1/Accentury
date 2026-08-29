output "domain" {
  value = var.domain
}

output "cloudfront_distribution_id" {
  value = module.edge.distribution_id
}

output "cloudfront_domain_name" {
  value = module.edge.distribution_domain_name
}

output "alb_dns_name" {
  value = module.edge.alb_dns_name
}

output "web_bucket" {
  value = module.edge.web_bucket
}

output "ec2_instance_id" {
  value = module.compute.instance_id
}

output "rds_endpoint" {
  value = module.data.endpoint
}

output "rds_master_user_secret_arn" {
  value       = module.data.master_user_secret_arn
  description = "RDS 관리형 마스터 시크릿 (7일 자동 회전). 값을 SSM에 복사하지 않는다 - backend가 SPRING_DATASOURCE_URL의 secretsManagerSecretId로 연결 시점에 직접 읽는다 (KAN-129). 운영자가 psql로 붙을 때만 get-secret-value로 읽는다."
}

output "github_deploy_role_arn" {
  value       = module.deploy.github_deploy_role_arn
  description = "GitHub environment 변수 AWS_DEPLOY_ROLE_ARN에 넣는다 (KAN-127, README 'GitHub 설정')"
}

output "waf_log_group" {
  value       = module.waf.log_group_name
  description = "us-east-1의 WAF 로그 그룹 (KAN-149). Count 관찰과 차단 확인 쿼리는 README 'WAF 웹 ACL'."
}

output "alerts_topic_arn" {
  value       = module.monitoring.alerts_topic_arn
  description = "경보 알림 SNS 토픽 (KAN-134). 이메일 구독은 수신자가 확인 링크를 눌러야 활성화된다 - README '경보와 알림'."
}

output "alarm_names" {
  value       = module.monitoring.alarm_names
  description = "생성된 CloudWatch 경보 4종 (KAN-134)"
}
