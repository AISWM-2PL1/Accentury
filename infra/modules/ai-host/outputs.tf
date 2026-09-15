output "asg_name" {
  value       = aws_autoscaling_group.ai.name
  description = "ai 호스트 ASG 이름 (KAN-36). 교체 실증(강제 종료)과 인스턴스 조회에 쓴다."
}

output "log_group_name" {
  value       = aws_cloudwatch_log_group.ai.name
  description = "ai 컨테이너 로그 그룹 (KAN-203). awslogs 드라이버가 ai/<인스턴스 ID> 스트림을 만든다."
}

output "iam_role_name" {
  value       = aws_iam_role.this.name
  description = "이 호스트의 EC2 역할 이름"
}

output "alb_arn_suffix" {
  value       = aws_lb.ai.arn_suffix
  description = "내부 ALB의 CloudWatch 차원(LoadBalancer) 재료 (KAN-201). monitoring 모듈의 ai-alb-no-healthy-host 경보가 쓴다."
}

output "target_group_arn_suffix" {
  value       = aws_lb_target_group.ai.arn_suffix
  description = "대상 그룹의 CloudWatch 차원(TargetGroup) 재료 (KAN-201)."
}

output "target_group_name" {
  value       = aws_lb_target_group.ai.name
  description = "대상 그룹 이름 (accentury-{env}-ai-tg, KAN-201). 배포 파이프라인이 순차 reload 중 대상별 healthy를 이 이름으로 조회한다 (deploy.yml)."
}

output "alb_dns_name" {
  value       = aws_lb.ai.dns_name
  description = "내부 ALB의 DNS 이름 (KAN-201). backend는 이 값이 아니라 프라이빗 영역의 alias(ai.accentury.internal)를 부른다 - 실증 시 대조용."
}
