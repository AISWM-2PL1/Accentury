output "asg_name" {
  value       = aws_autoscaling_group.ai.name
  description = "ai 호스트 ASG 이름 (KAN-36). 교체 실증(강제 종료)과 인스턴스 조회에 쓴다."
}

output "iam_role_name" {
  value       = aws_iam_role.this.name
  description = "이 호스트의 EC2 역할 이름"
}
