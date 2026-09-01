output "cluster_name" {
  value       = aws_ecs_cluster.this.name
  description = "ECS 클러스터 이름 (accentury-{env}). 파이프라인 update-service와 경보 차원(ClusterName)이 쓴다."
}

output "cluster_arn" {
  value       = aws_ecs_cluster.this.arn
  description = "배포 역할의 ecs:cluster 조건 값 (modules/deploy)."
}

output "service_name" {
  value       = aws_ecs_service.backend.name
  description = "backend 서비스 이름. 경보 차원(ServiceName)이 쓴다."
}

output "service_arn" {
  value       = aws_ecs_service.backend.arn
  description = "배포 역할의 UpdateService, DescribeServices 리소스 (modules/deploy)."
}

output "task_definition_family" {
  value       = aws_ecs_task_definition.backend.family
  description = "태스크 정의 패밀리 (accentury-{env}-backend). 파이프라인이 리비전을 올리는 대상이다."
}

output "task_definition_family_arn" {
  value       = "arn:aws:ecs:${local.region}:${local.account_id}:task-definition/${local.family}"
  description = "리비전 없는 패밀리 ARN. 배포 역할의 RegisterTaskDefinition 리소스 한정에 쓴다 (modules/deploy)."
}

output "task_role_arn" {
  value       = aws_iam_role.task.arn
  description = "태스크 역할. 파이프라인이 리비전을 등록할 때 iam:PassRole 대상이다 (modules/deploy)."
}

output "execution_role_arn" {
  value       = aws_iam_role.execution.arn
  description = "실행 역할. 파이프라인이 리비전을 등록할 때 iam:PassRole 대상이다 (modules/deploy)."
}

output "log_group_name" {
  value       = aws_cloudwatch_log_group.backend.name
  description = "backend 컨테이너 로그 그룹 (/accentury/{env}/backend). aws logs tail로 본다."
}
