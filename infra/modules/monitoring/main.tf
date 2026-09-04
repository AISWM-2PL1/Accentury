# 최소 알림: SNS 이메일 + CloudWatch 경보 (KAN-134), AI 호스트 경보 (KAN-36), Fargate 전환 재구성 (KAN-165).
#
# 목적은 하나다. prod를 무인으로 운영하니 서버가 죽은 것을 사용자보다 먼저 알아야 한다.
# KAN-38(전체 관측성: 지표 수집, 대시보드, correlation ID 규약)의 최소 선행분만 앞당긴
# 것이고, KAN-38 본체는 그대로 남는다. 지표를 새로 수집하지 않는다 - AI 지표 2종을 빼면
# 전부 AWS가 이미 내보내는 표준 지표다.
#
#   필수 (KAN-134 Requirements)
#     no-healthy-target 대상 그룹에 healthy 대상이 없다 = backend가 죽었거나 헬스체크 실패 (KAN-165에서 지표 교체)
#     alb-5xx           사용자가 본 5xx가 연속으로 임계치를 넘었다
#   선택 (KAN-134 "선택" 항목, 2026-08-28 두 환경 모두 포함으로 확정)
#     rds-free-storage  RDS 여유 스토리지 하한
#   backend Fargate 서비스 (KAN-165, EC2 크레딧 경보 ec2-cpu-surplus의 자리)
#     backend-cpu-high  서비스 CPU 평균이 지속적으로 높다 - 0.5 vCPU는 버스트가 없어 크기 상향 신호
#     backend-mem-high  서비스 메모리 평균이 높다 - JVM OOM(ExitOnOutOfMemoryError로 태스크 사망) 전 조기 신호
#   AI 전용 호스트 (KAN-36)
#     ai-unhealthy      AI 호스트의 health 프로브 실패 (호스트 타이머가 올리는 커스텀 지표)
#     ai-circuit-open   backend의 AI 회로가 열렸다 (backend가 Micrometer로 올리는 커스텀 지표)
#
# 전부 ap-northeast-2다. WAF 로그 그룹(KAN-149)만 us-east-1인데 그것은 CLOUDFRONT 스코프
# 웹 ACL의 제약이고, 여기서 보는 ALB, RDS, ECS 지표는 리소스와 같은 서울 리전에 있다.

locals {
  name = "accentury-${var.env}"
}

# ---- 알림 채널 ----

# 토픽 정책을 따로 주지 않는다. 기본 정책이 "같은 계정(AWS:SourceOwner)"의 publish를
# 허용하고 CloudWatch 경보는 그 계정 안에서 publish하므로 그대로 동작한다.
#
# SSE(kms_master_key_id)도 켜지 않는다. AWS 관리형 키 alias/aws/sns는 키 정책에
# cloudwatch.amazonaws.com이 없어서, 켜면 경보가 조용히 publish에 실패한다(알림이 안 오는데
# 경보 상태는 ALARM으로 정상이라 원인 찾기가 오래 걸린다). 켜려면 고객 관리형 키를 만들어
# 그 서비스 주체에 Decrypt와 GenerateDataKey를 줘야 하는데, 이 토픽이 나르는 내용은
# "어느 경보가 어느 상태로 바뀌었다"뿐이라 그 비용과 복잡도를 쓸 이유가 없다.
resource "aws_sns_topic" "alerts" {
  name         = "${local.name}-alerts"
  display_name = "accentury ${var.env}"

  tags = { Name = "${local.name}-alerts" }
}

# 이메일 구독은 Terraform이 확인까지 해 줄 수 없다. apply 직후 상태는 PendingConfirmation이고
# 수신자가 AWS가 보낸 메일의 확인 링크를 눌러야 활성화된다. 누르기 전에는 경보가 울려도
# 메일이 오지 않으므로, 환경을 새로 지을 때마다 이 단계를 먼저 확인한다 (README '경보와 알림').
#
# destroy 시에도 미확인 구독은 AWS에서 지워지지 않고 state에서만 빠진다. 다만 토픽을 지우면
# 딸린 구독이 함께 사라지므로 실제로 잔존물이 남지는 않는다.
resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  # 복구도 알린다. 경보만 오고 해제가 안 오면 "아직 죽어 있는지"를 콘솔에서 확인해야 한다.
  alarm_actions = [aws_sns_topic.alerts.arn]
}

# ---- 필수 경보 1: healthy 대상 없음 (backend 다운) ----

# KAN-134의 unhealthy-hosts(UnHealthyHostCount >= 1)를 KAN-165에서 HealthyHostCount < 1로 바꿨다. EC2 대상은
# 컨테이너가 죽어도 인스턴스가 등록된 채 unhealthy로 남아 그 지표가 1이 됐지만, Fargate 태스크는 죽는 순간
# ECS가 대상 그룹에서 등록 해제해 UnHealthyHostCount가 0에 머문다 - 헬스체크만 실패하는 태스크도 ECS가
# 곧 교체해 unhealthy 구간이 1분 안팎이라 "2회 연속"에 못 미친다. 사용자 관점의 장애는 "받아 줄 healthy
# 대상이 없다"이고, 그것은 대상이 교체 중이든 등록 해제됐든 HealthyHostCount 0으로 나타난다. 오토스케일링
# (KAN-168) 뒤에는 "여럿 중 하나가 죽었다"는 이 경보에 안 잡히지만 그것은 부분 장애라 ECS가 알아서 교체한다.
#
# 1분 x 2회 + CloudWatch 평가 지연으로 약 3분 안에 메일이 나간다 (티켓 AC "수 분 내"). 태스크 교체(이미지
# pull + JVM 기동 + healthy)가 2분에서 3분이라 교체 한 번은 경보 직전에 끝나거나 한 번 울리고 OK가 따라온다.
#
# treat_missing_data = "breaching": 이 지표는 대상 그룹에 등록된 대상이 있는 한 계속 나온다. 데이터가
# 끊겼다는 것은 등록된 대상이 하나도 없거나(서비스가 태스크를 못 띄움) ALB나 대상 그룹 자체가 사라졌다는
# 뜻이라 그것도 장애로 센다. 대가로 스택을 새로 apply한 직후 첫 태스크가 뜨는 몇 분 동안 한 번 울린다.
resource "aws_cloudwatch_metric_alarm" "no_healthy_target" {
  alarm_name        = "${local.name}-no-healthy-target"
  alarm_description = "accentury ${var.env}: ALB 대상 그룹에 healthy 대상이 없습니다. backend ECS 서비스의 태스크와 배포 상태를 확인하세요. (KAN-134, KAN-165)"

  namespace   = "AWS/ApplicationELB"
  metric_name = "HealthyHostCount"
  dimensions = {
    TargetGroup  = var.target_group_arn_suffix
    LoadBalancer = var.alb_arn_suffix
  }

  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-no-healthy-target" }
}

# ---- 필수 경보 2: ALB 5xx 급증 ----

# 두 지표를 더한다. 사용자가 받은 5xx 전부가 대상이기 때문이다.
#   HTTPCode_ELB_5XX_Count     ALB가 스스로 낸 502, 503, 504. 대상이 없거나 응답하지 않을 때.
#   HTTPCode_Target_5XX_Count  backend가 낸 500대. 코드 오류, DB 장애 등.
# 앞의 것만 보면 backend가 500을 쏟는 상황을 놓치고, 뒤의 것만 보면 backend가 아예 죽은
# 상황을 놓친다. 합계 하나로 보면 경보는 한 개로 유지되어 "이 티켓은 경보 2종만"이라는
# KAN-38 경계도 지켜진다.
#
# treat_missing_data = "notBreaching": 트래픽이 없으면 이 지표는 아예 나오지 않는다.
# 무인 프로토타입에서 새벽에 요청이 0인 것은 정상이고, 그때 5xx 경보를 울릴 이유가 없다.
# 서버가 죽은 것은 위의 no-healthy-target이 트래픽과 무관하게 잡는다.
resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name        = "${local.name}-alb-5xx"
  alarm_description = "accentury ${var.env}: 5xx 응답이 ${var.alb_5xx_evaluation_periods}분 연속 분당 ${var.alb_5xx_threshold}건을 넘었습니다. ALB 자체 5xx와 backend 5xx의 합입니다. (KAN-134)"

  evaluation_periods  = var.alb_5xx_evaluation_periods
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alb_5xx_threshold
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "total_5xx"
    expression  = "SUM([elb_5xx, target_5xx])"
    label       = "5xx 합계 (ALB + backend)"
    return_data = true
  }

  metric_query {
    id = "elb_5xx"

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_ELB_5XX_Count"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      period      = 60
      stat        = "Sum"
    }
  }

  metric_query {
    id = "target_5xx"

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      period      = 60
      stat        = "Sum"
    }
  }

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-alb-5xx" }
}

# ---- 선택 경보: RDS 여유 스토리지 ----

# gp3 20GiB 고정에 자동 확장이 없다(KAN-122). 다 차면 인스턴스가 storage-full 상태로 멈추고
# 복구는 스토리지 증설뿐이라 미리 알아야 한다.
#
# treat_missing_data = "missing": staging RDS는 미사용 기간에 중지할 수 있다(KAN-122).
# 중지된 인스턴스는 지표를 내지 않는데 그것을 장애로 셀 이유가 없다.
resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name        = "${local.name}-rds-free-storage"
  alarm_description = "accentury ${var.env}: RDS 여유 스토리지가 ${floor(var.rds_free_storage_threshold_bytes / 1073741824)}GiB 아래로 떨어졌습니다. 스토리지가 다 차면 인스턴스가 멈춥니다. (KAN-134)"

  namespace   = "AWS/RDS"
  metric_name = "FreeStorageSpace"
  dimensions  = { DBInstanceIdentifier = var.db_instance_identifier }

  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = var.rds_free_storage_threshold_bytes
  treat_missing_data  = "missing"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-rds-free-storage" }
}

# ---- backend Fargate 서비스 경보 2종 (KAN-165) ----

# KAN-134의 ec2-cpu-surplus(t3.small 초과 CPU 크레딧)는 backend EC2와 함께 사라졌다. ai EC2(c7i.xlarge)는
# 버스트 계열이 아니라 크레딧 지표 자체가 없어 옮길 곳이 없다. 그 자리에 ECS 서비스의 표준 지표 둘을 둔다 -
# 둘 다 Container Insights 없이 AWS/ECS 네임스페이스에 서비스 단위 평균으로 나온다.
#
# CPU: Fargate 0.5 vCPU는 버스트가 없다. t3.small은 순간 2 vCPU까지 끌어 썼지만 여기서는 항상 정확히 0.5라,
# 평균이 지속적으로 높으면 요청이 느려지고 있다는 뜻이자 크기(태스크 cpu) 상향 신호다. 오토스케일링(KAN-168)은
# CPU가 아니라 요청 수로 늘리므로 이 경보와 겹치지 않는다. 5분 연속을 요구해 기동 직후 JVM JIT 스파이크와
# 스모크 한 바퀴로는 서지 않는다.
#
# treat_missing_data = "notBreaching": 태스크가 하나도 없으면 지표가 끊기는데 그것은 no-healthy-target이 잡는다.
resource "aws_cloudwatch_metric_alarm" "backend_cpu_high" {
  alarm_name        = "${local.name}-backend-cpu-high"
  alarm_description = "accentury ${var.env}: backend 서비스 CPU 평균이 ${var.backend_cpu_evaluation_periods}분 연속 ${var.backend_cpu_threshold}%를 넘었습니다. Fargate 0.5 vCPU는 버스트가 없습니다 - 태스크 크기 상향 또는 오토스케일링(KAN-168)을 검토하세요. (KAN-165)"

  namespace   = "AWS/ECS"
  metric_name = "CPUUtilization"
  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = var.ecs_service_name
  }

  statistic           = "Average"
  period              = 60
  evaluation_periods  = var.backend_cpu_evaluation_periods
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.backend_cpu_threshold
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-backend-cpu-high" }
}

# 메모리: JVM 힙은 태스크 2 GB의 75%(1.5 GB)이고 힙이 마르면 ExitOnOutOfMemoryError로 즉시 죽어 ECS가 태스크를
# 교체한다 (backend/Dockerfile). 그 사망은 no-healthy-target으로 사후에 알지만, 이 경보는 그 전에 "차오르고
# 있다"를 알린다 - 메모리 누수와 크기 부족을 재기동 반복 전에 잡는다. 3분 연속이면 GC 뒤에도 안 내려가는 상태다.
resource "aws_cloudwatch_metric_alarm" "backend_memory_high" {
  alarm_name        = "${local.name}-backend-mem-high"
  alarm_description = "accentury ${var.env}: backend 서비스 메모리 평균이 ${var.backend_memory_evaluation_periods}분 연속 ${var.backend_memory_threshold}%를 넘었습니다. 힙이 마르면 태스크가 죽고 교체됩니다 - 누수 또는 태스크 메모리 상향을 검토하세요. (KAN-165)"

  namespace   = "AWS/ECS"
  metric_name = "MemoryUtilization"
  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = var.ecs_service_name
  }

  statistic           = "Average"
  period              = 60
  evaluation_periods  = var.backend_memory_evaluation_periods
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.backend_memory_threshold
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-backend-mem-high" }
}

# ---- AI 호스트 경보 1: health 프로브 실패 (KAN-36) ----

# AI 호스트는 ALB 뒤가 아니라 대상 그룹 health가 없다. 대신 호스트의 systemd 타이머
# (ai-host 모듈 ai-health-metric.sh)가 1분마다 /internal/v0/health를 찔러 Healthy 0|1을
# 올린다 (네임스페이스 accentury/ai, 차원 env). 워밍업 중(503 STARTING)도 0이다.
#
# treat_missing_data = "breaching": 지표가 끊겼다는 것은 타이머가 도는 호스트 자체가 없거나
# (ASG 교체 중, 스택 철거) 지표를 못 올리는 상태라 그것도 장애로 센다. 대가로 apply 직후와
# 인스턴스 교체 직후 몇 분은 한 번 운다 - no-healthy-target과 같은 성격이고 OK 알림이 따라온다.
# 3회 연속을 요구해 reload 한 번(컨테이너 재생성 수십 초)으로는 서지 않는다.
resource "aws_cloudwatch_metric_alarm" "ai_unhealthy" {
  alarm_name        = "${local.name}-ai-unhealthy"
  alarm_description = "accentury ${var.env}: AI 호스트의 health 프로브가 ${var.ai_unhealthy_evaluation_periods}분 연속 실패했습니다. ai 컨테이너, ASG 인스턴스, 준비 상태(STARTING)를 확인하세요. (KAN-36)"

  namespace   = var.ai_metric_namespace
  metric_name = "Healthy"
  dimensions  = { env = var.env }

  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = var.ai_unhealthy_evaluation_periods
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-ai-unhealthy" }
}

# ---- AI 호스트 경보 2: backend의 AI 회로 열림 (KAN-36) ----

# backend가 Micrometer CloudWatch 레지스트리로 회로 상태 게이지(accentury.ai.circuit.state,
# 0 닫힘 / 1 반열림 / 2 열림)를 1분마다 올린다 (AnalysisDispatchConfig, application-deploy.yml).
# 레지스트리는 게이지 이름에 .value를 붙여 내보낸다. 회로가 열리면 업로드가 전부 503이므로
# ai-unhealthy보다 사용자에게 가까운 신호다 - AI가 떠 있어도 추론만 죽은 장애(계약 위반,
# 타임아웃 연속)는 이 경보만 잡는다. 태스크가 여럿이면(KAN-168) 회로는 태스크별이라 이 지표는
# 그중 최대값이다 - 하나라도 열리면 경보다.
#
# 임계값은 2(열림)다. 반열림(1)은 health가 UP이라 "다음 업로드 1건으로 시험한다"는 대기 상태이고,
# 트래픽이 없으면 시험이 없어 밤새 1에 머문다 - 1 이상으로 걸면 잠깐 죽었다 복구된 AI가 아침까지
# ALARM으로 남는다 (리뷰 P2). 사용자 요청이 503으로 끊기는 것은 열림(2)뿐이고, 추론이 죽은 채
# 시험이 반복 실패하는 장애는 쿨다운(최대 80초)마다 2로 돌아와 1분 최대값이 2를 유지한다.
#
# treat_missing_data = "notBreaching": backend가 죽으면 지표가 끊기는데 그것은 no-healthy-target이
# 잡는다. 2회 연속을 요구해 반열림 시험 실패로 잠깐 다시 열린 1분으로는 서지 않는다.
resource "aws_cloudwatch_metric_alarm" "ai_circuit_open" {
  alarm_name        = "${local.name}-ai-circuit-open"
  alarm_description = "accentury ${var.env}: backend의 AI 회로가 열려 있습니다(상태 2). 업로드가 503으로 끊기는 중입니다. AI 호스트 상태와 backend 로그(AI 회로 열림 사유)를 확인하세요. (KAN-36)"

  namespace   = var.backend_metric_namespace
  metric_name = "accentury.ai.circuit.state.value"
  dimensions  = { env = var.env }

  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 2
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-ai-circuit-open" }
}
