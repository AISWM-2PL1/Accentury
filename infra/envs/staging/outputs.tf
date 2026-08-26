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
