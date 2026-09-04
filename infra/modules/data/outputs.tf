output "endpoint" {
  value       = aws_db_instance.this.endpoint
  description = "호스트:포트 (KAN-129 spring.datasource 주입용)"
}

output "address" {
  value = aws_db_instance.this.address
}

output "master_user_secret_arn" {
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
  description = "RDS 관리 마스터 자격 증명이 담긴 Secrets Manager 시크릿"
}

output "instance_identifier" {
  value       = aws_db_instance.this.identifier
  description = "CloudWatch AWS/RDS의 DBInstanceIdentifier 차원 값 (KAN-134)"
}
