# EC2 프로비저닝과 docker compose 부트스트랩 (KAN-124).
#
# 환경당 인스턴스 1대가 구조적 전제다: backend의 요청 제한 5축(RateLimits),
# AI 회로 차단기, pollAfterMs 혼잡 판정, 분석 디스패처가 전부 인메모리다.
# scale 금지. 운영용 compose 파일(docker-compose.yml)과 기동 스크립트(accentury-up.sh)는
# 이 모듈의 파일이고, user_data가 첫 부팅에 /opt/accentury로 옮긴다. 환경별 값은 부팅마다
# SSM Parameter Store(${ssm_prefix}/*)에서 읽으므로 두 환경의 호스트 구성은 완전히 같다.

locals {
  name = "accentury-${var.env}"
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
# taint하거나 ignore_changes를 일시 해제한다.
data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# ---- IAM: SSM Session Manager 접속 + ECR pull + 환경별 파라미터 읽기 ----

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

# 자기 환경 경로의 파라미터만 읽는다. staging 설정으로 prod 리소스에 접근할 수
# 없어야 한다는 KAN-129 AC를 IAM 경계로 보강한다. SecureString은 AWS 관리 키
# (aws/ssm)를 쓰므로 별도 kms 권한이 필요 없다.
data "aws_iam_policy_document" "ssm_params" {
  statement {
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = [
      "arn:aws:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter${var.ssm_prefix}",
      "arn:aws:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter${var.ssm_prefix}/*",
    ]
  }
}

resource "aws_iam_role_policy" "ssm_params" {
  name   = "read-env-parameters"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.ssm_params.json
}

resource "aws_iam_instance_profile" "this" {
  name = "${local.name}-ec2"
  role = aws_iam_role.this.name
}

# ---- 인스턴스 ----

resource "aws_instance" "this" {
  ami           = data.aws_ssm_parameter.al2023_ami.insecure_value
  instance_type = var.instance_type

  subnet_id              = var.subnet_id
  vpc_security_group_ids = [var.ec2_sg_id]
  iam_instance_profile   = aws_iam_instance_profile.this.name

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    compose_version = var.compose_version
    env             = var.env
    ssm_prefix      = var.ssm_prefix
    ecr_registry    = local.ecr_registry
    region          = data.aws_region.current.region
    compose_yml     = file("${path.module}/docker-compose.yml")
    up_script       = file("${path.module}/accentury-up.sh")
  })
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
  }

  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = local.name }
}
