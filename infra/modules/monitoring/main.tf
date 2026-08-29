# 최소 알림: SNS 이메일 + CloudWatch 경보 (KAN-134).
#
# 목적은 하나다. prod를 무인으로 운영하니 서버가 죽은 것을 사용자보다 먼저 알아야 한다.
# KAN-38(전체 관측성: 지표 수집, 대시보드, correlation ID 규약)의 최소 선행분만 앞당긴
# 것이고, KAN-38 본체는 그대로 남는다. 이 모듈이 만드는 것은 경보 4종뿐이며 지표를
# 새로 수집하지 않는다 - 전부 AWS가 이미 내보내는 표준 지표다.
#
#   필수 (티켓 Requirements)
#     unhealthy-hosts   대상 그룹에 비정상 대상이 있다 = backend가 죽었거나 헬스체크 실패
#     alb-5xx           사용자가 본 5xx가 연속으로 임계치를 넘었다
#   선택 (티켓 "선택" 항목, 2026-08-28 두 환경 모두 포함으로 확정)
#     rds-free-storage  RDS 여유 스토리지 하한
#     ec2-cpu-surplus   EC2 초과 CPU 크레딧 상한 (크레딧을 다 쓰고 빌리기 시작)
#
# 전부 ap-northeast-2다. WAF 로그 그룹(KAN-149)만 us-east-1인데 그것은 CLOUDFRONT 스코프
# 웹 ACL의 제약이고, 여기서 보는 ALB, RDS, EC2 지표는 리소스와 같은 서울 리전에 있다.

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

# ---- 필수 경보 1: 대상 그룹 비정상 (backend 다운) ----

# backend 컨테이너가 죽으면 /actuator/health(KAN-131)가 응답하지 않아 ALB가 대상을 비정상으로
# 판정하고 UnHealthyHostCount가 1이 된다. 대상은 환경당 EC2 1대뿐이라(KAN-124) 1 = 전면 장애다.
#
# 1분 × 2회 + CloudWatch 평가 지연으로 약 3분 안에 메일이 나간다 (티켓 AC "수 분 내").
#
# treat_missing_data = "breaching": 이 지표는 대상 그룹에 등록된 대상이 있는 한 계속 나온다.
# 데이터가 끊겼다는 것은 ALB나 대상 그룹 자체가 사라졌다는 뜻이라 그것도 장애로 센다.
# 대가로, 스택을 새로 apply한 직후 EC2가 아직 부팅 중인 몇 분 동안 한 번 울린다. 이때는
# 대상이 실제로 비정상이므로 경보가 맞고, 뜨고 나면 OK 알림이 따라온다.
resource "aws_cloudwatch_metric_alarm" "unhealthy_hosts" {
  alarm_name        = "${local.name}-unhealthy-hosts"
  alarm_description = "accentury ${var.env}: ALB 대상 그룹에 비정상 대상이 있습니다. backend 컨테이너 또는 EC2를 확인하세요. (KAN-134)"

  namespace   = "AWS/ApplicationELB"
  metric_name = "UnHealthyHostCount"
  dimensions = {
    TargetGroup  = var.target_group_arn_suffix
    LoadBalancer = var.alb_arn_suffix
  }

  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-unhealthy-hosts" }
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
# 서버가 죽은 것은 위의 unhealthy-hosts가 트래픽과 무관하게 잡는다.
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

# ---- 선택 경보 1: RDS 여유 스토리지 ----

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

# ---- 선택 경보 2: EC2 CPU 크레딧 소진 ----

# t3.small은 버스트 인스턴스다. 기준 성능(vCPU당 20%)을 넘겨 쓰면 쌓아 둔 크레딧을 깎고,
# 그것마저 떨어지면 초과 크레딧을 빌려 쓴다.
#
# compute 모듈이 credit_specification을 지정하지 않으므로 T3 기본값인 unlimited로 뜬다
# (2026-08-28 실측 확인). unlimited에서는 크레딧이 0이 돼도 스로틀이 걸리지 않고 빌린 만큼이
# vCPU 시간당 요금으로 청구된다. 그래서 이 경보는 "느려진다"가 아니라 "부하가 기준선을 넘겨
# 요금이 붙기 시작한다"는 신호다. 프로토타입에서 그 상태는 인스턴스 크기를 올릴 때가 됐다는
# 뜻이기도 하다.
#
# 지표는 CPUCreditBalance가 아니라 CPUSurplusCreditBalance다. unlimited 인스턴스는 잔액 0으로
# 시작해 시간당 24개씩 쌓으므로, 잔액 하한으로 경보를 걸면 새로 뜬 인스턴스가 임계값에 닿을
# 때까지 몇 시간 동안 무조건 운다. 2026-08-28 staging 실증에서 실제로 그렇게 됐다
# (CPUCreditBalance 0.0, 기동 직후 ALARM). 반대로 초과 크레딧은 정상 부하에서 0 근처에
# 머물다가 기준선을 넘겨 쓴 만큼만 쌓이므로, 기동 시점에 오탐이 없고 "크레딧을 다 쓰고
# 빚을 지기 시작했다"는 뜻이 그대로 지표가 된다.
#
# 임계값 144: 시간당 24개를 버니 여섯 시간치 벌이만큼 빚진 상태다. 576을 넘으면 실제 과금이
# 시작되므로 그 4분의 1 지점에서 먼저 알린다. 기동 직후의 짧은 스파이크(실측 0.5)는 한참
# 아래다. 5분 × 2회를 요구해 순간 스파이크로는 서지 않는다.
# treat_missing_data는 RDS와 같은 이유로 missing이다 (중지된 인스턴스는 지표를 내지 않는다).
resource "aws_cloudwatch_metric_alarm" "ec2_cpu_surplus_credit" {
  alarm_name        = "${local.name}-ec2-cpu-surplus"
  alarm_description = "accentury ${var.env}: EC2가 초과 CPU 크레딧을 ${var.ec2_surplus_credit_threshold}개 넘게 빌렸습니다. 벌어들이는 크레딧보다 많이 쓰는 상태이고, 576을 넘으면 초과분이 과금됩니다. (KAN-134)"

  namespace   = "AWS/EC2"
  metric_name = "CPUSurplusCreditBalance"
  dimensions  = { InstanceId = var.ec2_instance_id }

  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.ec2_surplus_credit_threshold
  treat_missing_data  = "missing"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = { Name = "${local.name}-ec2-cpu-surplus" }
}
