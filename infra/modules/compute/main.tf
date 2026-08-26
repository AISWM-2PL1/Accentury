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

# 자기 환경 경로의 파라미터와 자기 환경 RDS 시크릿만 읽는다. staging 설정으로 prod
# 리소스에 접근할 수 없어야 한다는 KAN-129 AC를 IAM 경계로 보강한다. SecureString과
# RDS 관리형 시크릿 모두 AWS 관리 키(aws/ssm, aws/secretsmanager)를 쓰므로 같은 계정
# 안에서는 별도 kms 권한이 필요 없다.
data "aws_iam_policy_document" "ssm_params" {
  statement {
    sid = "ReadEnvParameters"
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

  # backend 컨테이너가 연결 시점에 RDS 마스터 자격 증명을 읽는다 (KAN-129, awsSecretsManager
  # 플러그인). 인스턴스 프로파일 자격 증명이 컨테이너 안 SDK 기본 체인(IMDSv2)으로 흘러간다.
  # 시크릿 값은 SSM에 복사하지 않는다 - 7일 회전 때문이다.
  statement {
    sid       = "ReadRdsMasterSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.rds_master_user_secret_arn]
  }
}

# 정책 이름(read-env-parameters)은 시크릿 문장이 추가된 뒤에도 그대로 둔다 - name은 교체 강제
# 속성이라 바꾸면 삭제 후 생성 사이에 인스턴스가 SSM을 못 읽는다. 문장 추가는 제자리 갱신이다.
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
    # 16KB user_data 상한 대비 gzip + base64 (평문이면 91%, 압축하면 약 68%).
    compose_yml_b64 = base64gzip(file("${path.module}/docker-compose.yml"))
    up_script_b64   = base64gzip(file("${path.module}/accentury-up.sh"))
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
    # backend 컨테이너 안의 AWS SDK가 인스턴스 프로파일 자격 증명을 IMDSv2로 받는다 (KAN-129,
    # Secrets Manager 조회). compose 기본 bridge 네트워크는 호스트를 한 홉 더 거치므로 기본값
    # 1이면 PUT 토큰 응답이 컨테이너에 닿지 못해 자격 증명 조회가 조용히 실패한다. 호스트의
    # aws CLI(SSM, ECR 로그인)는 1로도 되지만 컨테이너 때문에 2가 필요하다.
    # 이 값은 bridge 안 컨테이너 전부에 열리므로 ai 컨테이너는 internal 네트워크에만 붙여
    # IMDS에 닿는 경로를 끊는다 (docker-compose.yml networks, Codex P1).
    http_put_response_hop_limit = 2
  }

  lifecycle {
    ignore_changes = [ami]

    # 첫 부팅의 accentury-up.sh가 SSM을 읽을 때 config 모듈의 파라미터가 이미 있어야 한다 (KAN-129).
    # 없으면 env 파일이 빈 채로 만들어지고 컨테이너 재시작은 그 파일을 재사용하므로 reload 전까지
    # 기동이 막힌다. 모듈 depends_on을 쓰지 않는 이유: 그것은 이 모듈의 data 소스(AMI, 계정, IAM
    # 정책 문서)까지 apply 시점으로 미뤄 user_data가 plan에서 unknown이 되고, 그러면 config 값 하나만
    # 바뀌어도(관리자 토큰 재발급 등) user_data_replace_on_change가 인스턴스를 교체한다 (Codex P1).
    # 이름 목록 참조는 파라미터 "존재"에만 의존하고 값 변경에는 반응하지 않는다. 조건은 항진명제가
    # 아니라 접두사 일치다 - config가 만든 이름이 이 인스턴스가 읽을 ssm_prefix 아래에 있어야
    # 기동 스크립트가 그 값을 본다 (두 모듈이 다른 접두사를 받으면 여기서 막힌다).
    precondition {
      condition     = contains(var.config_parameter_names, "${var.ssm_prefix}/SPRING_PROFILES_ACTIVE")
      error_message = "config 모듈의 SSM 파라미터가 이 인스턴스의 ssm_prefix(${var.ssm_prefix}) 아래에 있어야 합니다 (KAN-129)."
    }
  }

  tags = { Name = local.name }
}
