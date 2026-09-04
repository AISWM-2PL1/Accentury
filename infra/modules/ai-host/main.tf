# AI 추론 전용 EC2 호스트 (KAN-36 A단계). ASG(min 1, max 1)가 인스턴스 1대(accentury-{env}-ai)를 소유한다.
#
# backend는 이 호스트에 없다 - ECS Fargate 서비스다 (modules/fargate, KAN-165). KAN-124의 역할별 호스트 모듈
# (modules/compute, role = backend | ai)에서 backend 역할을 떼어 낸 것이 이 모듈이다. 남은 것은 ai 컨테이너만
# 띄우고 8000을 발행하며, 부팅 시 프라이빗 영역에 자기 A 레코드를 UPSERT해 backend가 고정 이름
# (ai.accentury.internal)으로 부르게 하는 호스트다. 상태 검사 실패 시 ASG가 교체한다.
#
# 파일들(docker-compose.ai.yml, accentury-up.sh, egress 가드와 health 스크립트)은 부팅 자산 버킷에 두고
# user_data가 첫 부팅에 /opt/accentury로 내려받으며(KAN-38, 아래 "부팅 자산 S3 버킷"), 환경별 값은 부팅마다
# SSM Parameter Store에서 읽으므로 두 환경의 호스트 구성은 완전히 같다. 리소스 이름(역할 accentury-{env}-ai-ec2, 정책 ai-host-access, ASG accentury-{env}-ai)은
# KAN-36 그대로다 - 살아 있는 state에서 주소만 바뀌고(module.ai_compute -> module.ai_host, envs의 moved 블록)
# 리소스는 교체되지 않는다. user_data(스크립트 정리)만 바뀌어 시작 템플릿 새 버전 + instance refresh가 한 번 돈다.

locals {
  name = "accentury-${var.env}-ai"
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  # 이미지는 계정 공유 ECR(infra/bootstrap)에서 당긴다. staging과 prod가 같은 SHA 태그를
  # 승격 모델로 나눠 쓴다 (KAN-128).
  ecr_registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${data.aws_region.current.region}.amazonaws.com"
}

# AL2023 x86_64 최신 AMI를 SSM 퍼블릭 파라미터에서 읽는다. 값은 시간이 지나면 바뀌므로 image_id를
# ignore_changes로 고정한다 - 그래야 AMI 갱신이 "plan 변경 없음" AC를 깨고 instance refresh를 유발하는
# 일이 없다. GPU(g4dn)로 가면 NVIDIA 드라이버 AMI와 docker runtime 설정이 함께 바뀌므로 그때 이 파라미터
# 이름을 변수로 뺀다 (KAN-57 판정 후).
data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# ---- IAM: SSM Session Manager 접속 + ECR pull + 자기 하위 경로 파라미터 읽기 + 지표 + A 레코드 ----

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

  # 자기 환경 경로 안에서도 하위 경로 /ai와 IMAGE_TAG만 읽는다 (KAN-36) - DB URL, 관리자 토큰 같은 backend
  # 시크릿이 신뢰하지 않는 오디오를 받는 호스트에 내려가지 않는다. backend 쪽은 반대로 /ai 하위 경로를 못 읽는다
  # (fargate 모듈 실행 역할이 config 파라미터 목록만 허용) - 내부 호출 토큰은 어차피 양쪽이 같은 값이다.
  readable_parameters = [
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

  # 호스트 타이머의 health 지표 (ai-health-metric.sh, KAN-36). PutMetricData는 리소스 수준 제한이 없는 API라
  # 네임스페이스 조건으로 좁힌다 - 이 호스트가 다른 서비스 이름으로 지표를 위조하지 못한다.
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

  # 부팅 자산(compose 파일과 스크립트 3개)을 내려받는다 (KAN-38). 버킷 전체가 아니라 ai-host/ 접두사만이고
  # 읽기 전용이다 - 이 호스트가 뚫려도 자기 부팅 파일을 바꿔치기하지 못한다.
  statement {
    sid       = "ReadBootAssets"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.boot.arn}/ai-host/*"]
  }

  # 부팅 시 자기 사설 IP로 A 레코드를 UPSERT한다 (accentury-up.sh, KAN-36). 영역 하나, 레코드 이름 하나, A 타입,
  # UPSERT만 - 이 호스트가 뚫려도 다른 이름을 만들거나 지우지 못한다.
  statement {
    sid       = "UpsertOwnAiRecord"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = ["arn:aws:route53:::hostedzone/${var.private_zone_id}"]

    condition {
      test     = "ForAllValues:StringEquals"
      variable = "route53:ChangeResourceRecordSetsNormalizedRecordNames"
      values   = [var.dns_name]
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

# 정책 이름은 교체 강제 속성이라 바꾸지 않는다 - 바꾸면 삭제 후 생성 사이에 인스턴스가 SSM 권한을 잃는다.
resource "aws_iam_role_policy" "host" {
  name   = "ai-host-access"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.host.json
}

resource "aws_iam_instance_profile" "this" {
  name = "${local.name}-ec2"
  role = aws_iam_role.this.name
}

# ---- 부팅 자산 S3 버킷 (KAN-38) ----

# compose 파일과 스크립트 3개를 user_data에 gzip + base64로 박던 방식(KAN-129 리뷰)은 EC2 raw 16KB 상한의
# 75%를 먹었다. KAN-38이 health 스크립트를 늘리면서 상한을 넘어 plan이 precondition에서 멈췄고, 그 자리에서
# error_message가 제시하던 두 갈래("주석을 줄이거나 S3 배치로 옮기세요") 중 뒤를 골랐다 - 주석을 깎는 쪽은
# 결정 근거를 태우면서 KAN-36 B단계(compose 확장)에 다시 걸린다. 옮긴 뒤 user_data는 약 29%만 쓴다.
resource "aws_s3_bucket" "boot" {
  # S3 버킷 이름은 전역 유일이라 계정 ID를 붙인다 (edge 모듈 web 버킷과 같은 규약).
  bucket = "${local.name}-boot-${data.aws_caller_identity.current.account_id}"

  # teardown 시 객체가 남아 있어도 버킷을 지울 수 있게 한다. 내용물은 이 모듈이 다시 올리는 산출물이다.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "boot" {
  bucket = aws_s3_bucket.boot.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

locals {
  # 키는 모듈 안 파일 이름이고 그대로 ai-host/ 아래의 오브젝트 키가 된다. 호스트에 놓이는 경로는
  # user_data의 fetch 호출이 정한다 - compose만 이름이 docker-compose.yml로 바뀐다.
  boot_assets = toset([
    "docker-compose.ai.yml",
    "accentury-up.sh",
    "ai-egress-guard.sh",
    "ai-health-metric.sh",
  ])

  # 네 파일 내용의 지문. user_data에 주석으로 실려서, 파일이 바뀌면 user_data가 바뀌고 시작 템플릿 새 버전과
  # instance refresh가 돈다. 이 값이 없으면 S3만 갱신되고 도는 인스턴스는 옛 스크립트를 계속 쓴다 - 파일을
  # 박아 넣던 시절에는 내용이 곧 user_data라 공짜로 성립하던 성질이다.
  boot_assets_hash = md5(join("", [for f in sort(tolist(local.boot_assets)) : filemd5("${path.module}/${f}")]))
}

resource "aws_s3_object" "boot" {
  for_each = local.boot_assets

  bucket = aws_s3_bucket.boot.id
  key    = "ai-host/${each.value}"
  source = "${path.module}/${each.value}"

  # 내용이 바뀌면 오브젝트를 다시 올린다 - source만으로는 Terraform이 변경을 못 본다.
  etag = filemd5("${path.module}/${each.value}")
}

# ---- user_data ----

locals {
  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    compose_version  = var.compose_version
    env              = var.env
    ssm_prefix       = var.ssm_prefix
    ecr_registry     = local.ecr_registry
    region           = data.aws_region.current.region
    metric_namespace = var.metric_namespace
    ai_dns_name      = var.dns_name
    ai_zone_id       = var.private_zone_id
    vpc_cidr         = var.vpc_cidr
    # 부팅 자산은 S3에서 받는다 (KAN-38). 해시는 파일 변경을 instance refresh로 잇는 고리다.
    boot_bucket      = aws_s3_bucket.boot.id
    boot_assets_hash = local.boot_assets_hash
  })

  # 첫 부팅의 accentury-up.sh가 SSM을 읽을 때 config 모듈의 파라미터가 이미 있어야 한다 (KAN-129).
  # "반드시 있어야 하는 이름" 하나를 본다 - 접두사 일치까지 함께 검사되는 셈이다.
  required_parameter = "${var.ssm_prefix}/ai/ACCENTURY_AI_INTERNAL_TOKEN"

  # EC2 user_data 상한은 raw 16KB다. Terraform length()는 글자 수라 한글 주석의 바이트를 못 세므로
  # base64 길이로 환산한다. 실모델 전환(B단계)에서 compose가 자라면 여기서 plan이 먼저 멈춘다.
  user_data_bytes     = floor(length(base64encode(local.user_data)) * 3 / 4)
  user_data_max_bytes = 16384
}

# ---- 시작 템플릿 + ASG(min 1, max 1) ----

# 인스턴스 자체가 아니라 ASG가 소유한다 (KAN-36). 상태 검사 실패 시 자동 교체되고, user_data(compose,
# 스크립트)가 바뀌면 새 템플릿 버전으로 instance refresh가 돈다 - 1대뿐이라 교체 동안 분석은 끊기고
# backend 회로가 열렸다가(KAN-28) 새 인스턴스가 UP이 되면 닫힌다. 2대 이상은 앞에 내부 LB가 필요해
# 미룬 결정이다 (티켓 표).
resource "aws_launch_template" "ai" {
  # 오브젝트가 먼저 올라가 있어야 첫 부팅의 fetch가 성공한다. 버킷 자체는 IAM 정책을 통해 이미 엮여 있지만
  # 오브젝트는 참조가 없어 순서가 보장되지 않는다 (KAN-129의 SSM 파라미터 선행과 같은 성격).
  depends_on = [aws_s3_object.boot]

  name_prefix   = "${local.name}-"
  image_id      = data.aws_ssm_parameter.al2023_ami.insecure_value
  instance_type = var.instance_type

  iam_instance_profile {
    name = aws_iam_instance_profile.this.name
  }

  # 퍼블릭 서브넷의 map_public_ip_on_launch가 퍼블릭 IP를 준다 (ECR pull, SSM). 외부 미노출은 서브넷이
  # 아니라 ai-sg(backend-sg만 8000)로 성립한다.
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

    precondition {
      condition     = local.user_data_bytes <= local.user_data_max_bytes
      error_message = "ai 호스트 user_data가 EC2 상한 ${local.user_data_max_bytes} 바이트를 넘습니다 (약 ${local.user_data_bytes} 바이트). compose와 스크립트는 이미 부팅 자산 버킷에 있으므로(KAN-38), 남은 것은 user_data.sh.tftpl 본문입니다 - 주석을 main.tf나 infra/README.md로 옮기거나 systemd 유닛 정의도 부팅 자산으로 내리세요."
    }
  }

  tags = { Name = local.name }
}

resource "aws_autoscaling_group" "ai" {
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
    id      = aws_launch_template.ai.id
    version = aws_launch_template.ai.latest_version
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

# KAN-36 A단계에서는 이 리소스들이 role 변수로 갈리는 compute 모듈의 count 리소스였다. 살아 있는 state에서 주소만 옮긴다.
moved {
  from = aws_launch_template.ai[0]
  to   = aws_launch_template.ai
}

moved {
  from = aws_autoscaling_group.ai[0]
  to   = aws_autoscaling_group.ai
}
