output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "alb_sg_id" {
  value = aws_security_group.alb.id
}

output "ec2_sg_id" {
  value = aws_security_group.ec2.id
}

output "rds_sg_id" {
  value = aws_security_group.rds.id
}

output "ai_sg_id" {
  value       = aws_security_group.ai.id
  description = "AI 호스트 SG - ec2-sg에서 오는 8000만 허용 (KAN-36)"
}

output "private_zone_id" {
  value       = aws_route53_zone.private.zone_id
  description = "내부 호출용 프라이빗 호스팅 영역. AI 인스턴스가 부팅 시 자기 A 레코드를 UPSERT한다 (KAN-36)"
}

output "ai_dns_name" {
  value       = "ai.${var.private_zone_name}"
  description = "backend가 AI를 부르는 이름 (KAN-36). config 모듈이 http://<이 값>:8000 으로 ACCENTURY_ANALYSIS_AIBASEURL을 만든다"
}
