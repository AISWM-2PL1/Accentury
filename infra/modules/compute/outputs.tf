output "instance_id" {
  value       = one(aws_instance.this[*].id)
  description = "backend 역할의 인스턴스 ID (ALB 대상 그룹 등록, 경보 차원). ai 역할은 null - 인스턴스는 ASG가 관리한다."
}

output "asg_name" {
  value       = one(aws_autoscaling_group.ai[*].name)
  description = "ai 역할의 ASG 이름 (KAN-36). 교체 실증(강제 종료)과 인스턴스 조회에 쓴다. backend 역할은 null."
}

output "iam_role_name" {
  value       = aws_iam_role.this.name
  description = "이 호스트의 EC2 역할 이름"
}
