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
  description = "KAN-129가 SSM 파라미터를 채울 때 읽는 마스터 자격 증명"
}
