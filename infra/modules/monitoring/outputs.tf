output "alerts_topic_arn" {
  value       = aws_sns_topic.alerts.arn
  description = "경보가 publish하는 SNS 토픽. KAN-38이 지표 경보를 추가할 때 같은 토픽을 쓴다."
}

output "email_subscription_arn" {
  value       = aws_sns_topic_subscription.email.arn
  description = "이메일 구독. 수신자가 확인 링크를 누르기 전에는 값이 \"pending confirmation\"이다 (README '경보와 알림')."
}

output "alarm_names" {
  value = [
    aws_cloudwatch_metric_alarm.no_healthy_target.alarm_name,
    aws_cloudwatch_metric_alarm.alb_5xx.alarm_name,
    aws_cloudwatch_metric_alarm.rds_free_storage.alarm_name,
    aws_cloudwatch_metric_alarm.backend_cpu_high.alarm_name,
    aws_cloudwatch_metric_alarm.backend_memory_high.alarm_name,
    aws_cloudwatch_metric_alarm.ai_unhealthy.alarm_name,
    aws_cloudwatch_metric_alarm.ai_circuit_open.alarm_name,
  ]
  description = "생성된 경보 이름 7종 (KAN-134의 ALB, RDS 3종 + KAN-165의 backend 서비스 2종 + KAN-36의 AI 2종). describe-alarms로 상태를 확인할 때 쓴다."
}
