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

output "ecs_cluster_name" {
  value       = module.fargate.cluster_name
  description = "backend ECS 클러스터 (KAN-165). 서비스 상태: aws ecs describe-services --cluster <이 값> --services backend"
}

output "ecs_service_name" {
  value       = module.fargate.service_name
  description = "backend ECS 서비스 이름 (KAN-165)"
}

output "backend_task_definition_family" {
  value       = module.fargate.task_definition_family
  description = "backend 태스크 정의 패밀리 (KAN-165). 파이프라인이 리비전을 올린다."
}

output "backend_log_group" {
  value       = module.fargate.log_group_name
  description = "backend 컨테이너 로그 (KAN-165). aws logs tail <이 값> --follow"
}

output "ai_log_group" {
  value       = module.ai_host.log_group_name
  description = "ai 컨테이너 로그 (KAN-203). aws logs tail <이 값> --follow. backend와 접두사가 같아 Logs Insights에서 함께 고를 수 있다."
}

output "backend_autoscaling_resource_id" {
  value       = module.fargate.autoscaling_resource_id
  description = "backend 오토스케일링 대상 (KAN-168). 이력: aws application-autoscaling describe-scaling-activities --service-namespace ecs --resource-id <이 값>"
}

output "backend_autoscaling_alarm_names" {
  value       = module.fargate.autoscaling_alarm_names
  description = "목표 추적이 만든 경보 2개 (KAN-168). AlarmHigh = 스케일아웃(1분 x 3회), AlarmLow = 스케일인(1분 x 15회). alarm_names(monitoring)와 별개다."
}

output "ai_asg_name" {
  value       = module.ai_host.asg_name
  description = "AI 호스트 ASG (KAN-36). 인스턴스 조회: aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names <이 값>"
}

output "ai_dns_name" {
  value       = module.network.ai_dns_name
  description = "backend가 AI를 부르는 프라이빗 이름 (KAN-36). VPC 안에서만 풀리고, KAN-201부터 내부 ALB의 alias다."
}

output "ai_alb_dns_name" {
  value       = module.ai_host.alb_dns_name
  description = "AI 호스트 앞 내부 ALB의 DNS 이름 (KAN-201). ai_dns_name의 alias 대상 - 실증 시 대조용."
}

output "ai_target_group_name" {
  value       = module.ai_host.target_group_name
  description = "AI 대상 그룹 이름 (KAN-201). 대상별 healthy: aws elbv2 describe-target-health --target-group-arn $(aws elbv2 describe-target-groups --names <이 값> --query 'TargetGroups[0].TargetGroupArn' --output text)"
}

output "private_zone_id" {
  value       = module.network.private_zone_id
  description = "내부 호출용 프라이빗 호스팅 영역 (KAN-36). A 레코드는 ai-host 모듈이 ALB alias로 만든다 (KAN-201)."
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
  description = "생성된 CloudWatch 경보 12종 (KAN-134의 ALB, RDS 3종 + KAN-165의 backend 서비스 2종 + KAN-36의 AI 4종 + KAN-38의 관측성 3종)"
}

output "dashboard_name" {
  value       = module.monitoring.dashboard_name
  description = "운영 대시보드 이름 (KAN-38). 콘솔 경로는 CloudWatch > 대시보드 > 이 이름이다."
}

output "dashboard_url" {
  value       = module.monitoring.dashboard_url
  description = "운영 대시보드 바로가기 (KAN-38) - README '관측성 지표와 대시보드'의 확인 절차가 이 출력을 쓴다."
}

output "training_bucket" {
  value       = one(aws_s3_bucket.training[*].bucket)
  description = "staging 전용 학습 데이터 S3 버킷 (KAN-201). prod는 null이다. 샘플 확인: aws s3 ls s3://<이 값>/ --recursive"
}
