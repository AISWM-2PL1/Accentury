# EC2 프로비저닝과 docker compose 부트스트랩 (KAN-124), 역할별 호스트 2종 (KAN-36).
#
# 이 모듈은 환경마다 두 번 호출된다.
#   role = "backend"  aws_instance 1대 (accentury-{env}). ALB 대상이고 backend 컨테이너만 띄운다.
#                     backend의 요청 제한, 회로 차단기, 혼잡 판정, 디스패처가 전부 인메모리라
#                     1대 고정이다 - Fargate 전환(KAN-165)과 다중 인스턴스 정리(KAN-167)가 푼다.
#   role = "ai"       ASG(min 1, max 1)의 전용 추론 호스트 (accentury-{env}-ai). ai 컨테이너만 띄우고
#                     8000을 발행하며, 부팅 시 프라이빗 영역에 자기 A 레코드를 UPSERT해 backend가
#                     고정 이름(ai.accentury.internal)으로 부른다. 상태 검사 실패 시 ASG가 교체한다.
#
# 두 역할이 user_data 골격, 기동 스크립트(accentury-up.sh), systemd 유닛, IAM 형태를 공유하고
# compose 파일(docker-compose.{backend,ai}.yml)과 SSM 읽기 범위만 다르다. 파일들은 user_data가
# 첫 부팅에 /opt/accentury로 옮기고, 환경별 값은 부팅마다 SSM Parameter Store에서 읽으므로 두
# 환경의 호스트 구성은 완전히 같다.

locals {
  is_backend = var.role == "backend"
  # backend 호스트 이름은 KAN-124 그대로(accentury-{env})다 - 배포 파이프라인의 Run Command 대상
  # 태그와 deploy 모듈의 조건이 이 이름을 본다. ai 호스트는 -ai 접미사다 (deploy 모듈도 둘 다 허용).
  name         = local.is_backend ? "accentury-${var.env}" : "accentury-${var.env}-ai"
  compose_file = local.is_backend ? "docker-compose.backend.yml" : "docker-compose.ai.yml"
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  # 이미지는 계정 공유 ECR(infra/bootstrap)에서 당긴다. staging과 prod가 같은 SHA 태그를
  # 승격 모델로 나눠 쓴다 (KAN-128).
  ecr_registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${data.aws_region.current.region}.amazonaws.com"
}

# AL2023 x86_64 최신 AMI를 SSM 퍼블릭 파라미터에서 읽는다. 값은 시간이 지나면
# 바뀌므로 ami를 ignore_changes로 고정한다 - 그래야 AMI 갱신이 "plan 변경 없음"
# AC를 깨고 인스턴스 교체를 유발하는 일이 없다. 교체가 필요하면 인스턴스를
# taint하거나 ignore_changes를 일시 해제한다. ai 호스트는 GPU(g4dn)로 가면 NVIDIA 드라이버
# AMI와 docker runtime 설정이 함께 바뀌므로 그때 이 파라미터 이름을 변수로 뺀다 (KAN-57 판정 후).
data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# ---- IAM: SSM Session Manager 접속 + ECR pull + 환경별 파라미터 읽기 + 역할별 추가 권한 ----

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = "${local.name}-ec2"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

# SSH 키 대신 SSM Session Manager로 접속한다 (KAN-124).
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# ECR에서 이미지를 당긴다 (KAN-120).
resource "aws_iam_role_policy_attachment" "ecr_read" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

locals {
  parameter_arn_prefix = "arn:aws:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter${var.ssm_prefix}"

  # 자기 환경 경로만 읽는다 (KAN-129 AC의 IAM 경계). ai 호스트는 그 안에서도 자기 하위 경로 /ai와
  # IMAGE_TAG만 읽는다 (KAN-36) - DB URL, 관리자 토큰 같은 backend 시크릿이 신뢰하지 않는 오디오를
  # 받는 호스트에 내려가지 않는다. backend 호스트의 기동 스크립트는 /ai 하위 경로를 읽지 않지만
  # (--recursive 없음) IAM으로 막지는 않는다 - 내부 호출 토큰은 어차피 양쪽이 같은 값이다.
  readable_parameters = local.is_backend ? [
    local.parameter_arn_prefix,
    "${local.parameter_arn_prefix}/*",
    ] : [
    "${local.parameter_arn_prefix}/ai",
    "${local.parameter_arn_prefix}/ai/*",
    "${local.parameter_arn_prefix}/IMAGE_TAG",
  ]
}

data "aws_iam_policy_document" "host" {
  statement {
    sid = "ReadEnvParameters"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = local.readable_parameters
  }

  # backend 컨테이너가 연결 시점에 RDS 마스터 자격 증명을 읽는다 (KAN-129, awsSecretsManager
  # 플러그인). 인스턴스 프로파일 자격 증명이 컨테이너 안 SDK 기본 체인(IMDSv2)으로 흘러간다.
  # 시크릿 값은 SSM에 복사하지 않는다 - 7일 회전 때문이다. SecureString과 RDS 관리형 시크릿 모두
  # AWS 관리 키(aws/ssm, aws/secretsmanager)를 쓰므로 같은 계정 안에서는 별도 kms 권한이 필요 없다.
  dynamic "statement" {
    for_each = local.is_backend ? [1] : []

    content {
      sid       = "ReadRdsMasterSecret"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = [var.rds_master_user_secret_arn]
    }
  }

  # 커스텀 지표 (KAN-36). backend는 Micrometer CloudWatch 레지스트리(회로 상태 게이지 등), ai는
  # 호스트 타이머의 health 지표다. PutMetricData는 리소스 수준 제한이 없는 API라 네임스페이스
  # 조건으로 좁힌다 - 이 호스트가 다른 서비스 이름으로 지표를 위조하지 못한다.
  statement {
    sid       = "PutHostMetrics"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = [var.metric_namespace]
    }
  }

  # ai 호스트가 부팅 시 자기 사설 IP로 A 레코드를 UPSERT한다 (accentury-up.sh, KAN-36). 영역 하나,
  # 레코드 이름 하나, A 타입, UPSERT만 - 이 호스트가 뚫려도 다른 이름을 만들거나 지우지 못한다.
  dynamic "statement" {
    for_each = local.is_backend ? [] : [1]

    content {
      sid       = "UpsertOwnAiRecord"
      actions   = ["route53:ChangeResourceRecordSets"]
      resources = ["arn:aws:route53:::hostedzone/${var.ai.private_zone_id}"]

      condition {
        test     = "ForAllValues:StringEquals"
        variable = "route53:ChangeResourceRecordSetsNormalizedRecordNames"
        values   = [var.ai.dns_name]
      }

      condition {
        test     = "ForAllValues:StringEquals"
        variable = "route53:ChangeResourceRecordSetsRecordTypes"
        values   = ["A"]
      }

      condition {
        test     = "ForAllValues:StringEquals"
        variable = "route53:ChangeResourceRecordSetsActions"
        values   = ["UPSERT"]
      }
    }
  }
}

# backend 정책 이름(read-env-parameters)은 문장이 늘어난 뒤에도 그대로 둔다 - name은 교체 강제
# 속성이라 바꾸면 삭제 후 생성 사이에 인스턴스가 SSM을 못 읽는다. 문장 추가는 제자리 갱신이다.
resource "aws_iam_role_policy" "host" {
  name   = local.is_backend ? "read-env-parameters" : "ai-host-access"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.host.json
}

# KAN-36 이전 주소(ssm_params)에서 옮긴다. 살아 있는 state에서 주소만 바뀌면 같은 역할의 같은 이름 정책을
# 삭제하고 다시 만드는데, 그 둘의 순서가 보장되지 않아 인스턴스가 SSM과 시크릿 권한을 잃을 수 있다 (Codex P1).
moved {
  from = aws_iam_role_policy.ssm_params
  to   = aws_iam_role_policy.host
}

resource "aws_iam_instance_profile" "this" {
  name = "${local.name}-ec2"
  role = aws_iam_role.this.name
}

# ---- user_data (두 역할 공통 템플릿) ----

locals {
  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    role             = var.role
    compose_version  = var.compose_version
    env              = var.env
    ssm_prefix       = var.ssm_prefix
    ecr_registry     = local.ecr_registry
    region           = data.aws_region.current.region
    metric_namespace = var.metric_namespace
    ai_dns_name      = local.is_backend ? "" : var.ai.dns_name
    ai_zone_id       = local.is_backend ? "" : var.ai.private_zone_id
    vpc_cidr         = local.is_backend ? "" : var.ai.vpc_cidr
    # 16KB user_data 상한 대비 gzip + base64 (평문이면 91%, 압축하면 약 68%였다 - KAN-129 시점).
    compose_yml_b64   = base64gzip(file("${path.module}/${local.compose_file}"))
    up_script_b64     = base64gzip(file("${path.module}/accentury-up.sh"))
    guard_script_b64  = local.is_backend ? "" : base64gzip(file("${path.module}/ai-egress-guard.sh"))
    health_script_b64 = local.is_backend ? "" : base64gzip(file("${path.module}/ai-health-metric.sh"))
  })

  # 첫 부팅의 accentury-up.sh가 SSM을 읽을 때 config 모듈의 파라미터가 이미 있어야 한다 (KAN-129).
  # 역할별로 "반드시 있어야 하는 이름" 하나를 본다 - 접두사 일치까지 함께 검사되는 셈이다.
  required_parameter = local.is_backend ? "${var.ssm_prefix}/SPRING_PROFILES_ACTIVE" : "${var.ssm_prefix}/ai/ACCENTURY_AI_INTERNAL_TOKEN"
}

# ---- backend: 고정 인스턴스 1대 ----

resource "aws_instance" "this" {
  count = local.is_backend ? 1 : 0

  ami           = data.aws_ssm_parameter.al2023_ami.insecure_value
  instance_type = var.instance_type

  subnet_id              = var.subnet_ids[0]
  vpc_security_group_ids = [var.security_group_id]
  iam_instance_profile   = aws_iam_instance_profile.this.name

  user_data = local.user_data
  # cloud-init은 이 스크립트를 인스턴스당 한 번만 실행한다. 기본 동작(in-place
  # 갱신)이면 user_data 변경이 적용된 것처럼 보이지만 실제로는 반영되지 않으므로
  # 인스턴스를 교체한다. compose 파일이나 기동 스크립트를 고쳐도 같은 이유로 교체된다.
  # 호스트는 무상태다 - 상태는 전부 RDS와 SSM에 있고, env 파일은 tmpfs(/run)에만 있다.
  user_data_replace_on_change = true

  root_block_device {
    volume_size = var.root_volume_size
    volume_type = "gp3"
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required" # IMDSv2 강제
    # backend 컨테이너 안의 AWS SDK가 인스턴스 프로파일 자격 증명을 IMDSv2로 받는다 (KAN-129,
    # Secrets Manager 조회, KAN-36 CloudWatch 지표). compose 기본 bridge 네트워크는 호스트를 한 홉
    # 더 거치므로 기본값 1이면 PUT 토큰 응답이 컨테이너에 닿지 못해 자격 증명 조회가 조용히 실패한다.
    # 이 호스트에는 backend 컨테이너뿐이라(ai는 전용 호스트, KAN-36) 열리는 대상도 backend뿐이다.
    http_put_response_hop_limit = 2
  }

  lifecycle {
    ignore_changes = [ami]

    # 없으면 env 파일이 빈 채로 만들어지고 컨테이너 재시작은 그 파일을 재사용하므로 reload 전까지
    # 기동이 막힌다. 모듈 depends_on을 쓰지 않는 이유: 그것은 이 모듈의 data 소스(AMI, 계정, IAM
    # 정책 문서)까지 apply 시점으로 미뤄 user_data가 plan에서 unknown이 되고, 그러면 config 값 하나만
    # 바뀌어도(관리자 토큰 재발급 등) user_data_replace_on_change가 인스턴스를 교체한다 (Codex P1).
    # 이름 목록 참조는 파라미터 "존재"에만 의존하고 값 변경에는 반응하지 않는다.
    precondition {
      condition     = contains(var.config_parameter_names, local.required_parameter)
      error_message = "config 모듈의 SSM 파라미터 ${local.required_parameter}가 config_parameter_names에 있어야 합니다 (KAN-129)."
    }
  }

  tags = { Name = local.name }
}

# 역할 변수화 이전(KAN-124)에는 count 없는 단일 리소스였다. 살아 있는 state가 있으면 주소만 옮긴다.
moved {
  from = aws_instance.this
  to   = aws_instance.this[0]
}

# ---- ai: 시작 템플릿 + ASG(min 1, max 1) ----

# 인스턴스 자체가 아니라 ASG가 소유한다 (KAN-36). 상태 검사 실패 시 자동 교체되고, user_data(compose,
# 스크립트)가 바뀌면 새 템플릿 버전으로 instance refresh가 돈다 - 1대뿐이라 교체 동안 분석은
# 끊기고 backend 회로가 열렸다가(KAN-28) 새 인스턴스가 UP이 되면 닫힌다. 2대 이상은 앞에 내부 LB가
# 필요해 미룬 결정이다 (티켓 표).
resource "aws_launch_template" "ai" {
  count = local.is_backend ? 0 : 1

  name_prefix   = "${local.name}-"
  image_id      = data.aws_ssm_parameter.al2023_ami.insecure_value
  instance_type = var.instance_type

  iam_instance_profile {
    name = aws_iam_instance_profile.this.name
  }

  # 퍼블릭 서브넷의 map_public_ip_on_launch가 퍼블릭 IP를 준다 (ECR pull, SSM). 외부 미노출은 서브넷이
  # 아니라 ai-sg(ec2-sg만 8000)로 성립한다.
  vpc_security_group_ids = [var.security_group_id]

  user_data = base64encode(local.user_data)
  # 템플릿을 고치면 새 버전이 기본이 된다 - ASG가 그 번호(latest_version)를 참조해 instance refresh가 돈다.
  update_default_version = true

  block_device_mappings {
    device_name = "/dev/xvda" # AL2023 루트 디바이스

    ebs {
      volume_size           = var.root_volume_size
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }

  metadata_options {
    http_tokens = "required" # IMDSv2 강제
    # 컨테이너가 IMDS에 닿지 못하게 한다 (KAN-36). 호스트의 aws CLI(SSM, ECR 로그인, Route 53, CloudWatch)는
    # 1로도 되고, ai 컨테이너는 인스턴스 자격 증명이 필요 없다 - 신뢰하지 않는 오디오를 받는 컨테이너에
    # Route 53 변경 권한이 흘러가면 안 된다. 인터넷 차단은 호스트 iptables가 한다 (ai-egress-guard.sh).
    http_put_response_hop_limit = 1
  }

  tag_specifications {
    resource_type = "instance"
    # 프로바이더 default_tags는 ASG가 띄우는 인스턴스에 전파되지 않으므로 여기 다시 적는다 (KAN-35 비용 태그).
    tags = {
      Name       = local.name
      env        = var.env
      project    = "accentury"
      managed-by = "terraform"
    }
  }

  tag_specifications {
    resource_type = "volume"
    tags = {
      Name       = local.name
      env        = var.env
      project    = "accentury"
      managed-by = "terraform"
    }
  }

  lifecycle {
    ignore_changes = [image_id]

    precondition {
      condition     = contains(var.config_parameter_names, local.required_parameter)
      error_message = "config 모듈의 SSM 파라미터 ${local.required_parameter}가 config_parameter_names에 있어야 합니다 (KAN-36)."
    }
  }

  tags = { Name = local.name }
}

resource "aws_autoscaling_group" "ai" {
  count = local.is_backend ? 0 : 1

  name                = local.name
  min_size            = 1
  max_size            = 1
  desired_capacity    = 1
  vpc_zone_identifier = var.subnet_ids

  # EC2 상태 검사(시스템, 인스턴스)만 본다 - ALB 뒤가 아니라 대상 그룹 health가 없다. 컨테이너 수준의
  # 죽음은 docker restart(unless-stopped)가, 그래도 health가 안 오면 ai-unhealthy 경보(monitoring 모듈)가
  # 잡는다. 유예는 첫 부팅(docker 설치 + 이미지 pull + 기동 스크립트 재시도)을 덮는 값이다.
  health_check_type         = "EC2"
  health_check_grace_period = 300

  # 첫 apply에서 인스턴스 프로파일이 IAM에 전파되기 전에 ASG가 시작을 시도하면 "Authentication Failure"로
  # 한 번 실패하고, ASG는 몇 십 초 뒤 스스로 다시 띄운다 (2026-09-01 staging 실측). 이 값이 false(기본)면
  # Terraform이 그 실패 활동을 보고 곧바로 apply를 실패시키고 리소스를 tainted로 남긴다 - 다음 apply가
  # 멀쩡한 인스턴스를 지우고 다시 만든다. true면 재시도가 성공해 capacity가 찰 때까지 기다린다.
  ignore_failed_scaling_activities = true

  # 버전을 숫자로 참조한다 - "$Latest" 문자열이면 템플릿이 바뀌어도 이 블록이 그대로라 instance refresh가
  # 시작되지 않고, 새 user_data는 다음 우연한 교체 때까지 반영되지 않는다 (Codex P2).
  launch_template {
    id      = aws_launch_template.ai[0].id
    version = aws_launch_template.ai[0].latest_version
  }

  # 템플릿이 바뀌면 인스턴스를 갈아 끼운다. 1대라 min_healthy 0 - 종료 뒤 새 인스턴스가 뜬다.
  instance_refresh {
    strategy = "Rolling"

    preferences {
      min_healthy_percentage = 0
      instance_warmup        = 120
    }
  }

  # 배포 파이프라인의 Run Command 대상(tag:Name)과 deploy 모듈의 조건이 이 태그를 본다.
  tag {
    key                 = "Name"
    value               = local.name
    propagate_at_launch = true
  }

  lifecycle {
    # desired는 min=max=1이라 바뀔 일이 없지만 콘솔 조작이 plan drift가 되지 않게 둔다.
    ignore_changes = [desired_capacity]
  }
}
