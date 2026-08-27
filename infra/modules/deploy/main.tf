# 배포 파이프라인용 IAM 역할 (KAN-127). GitHub Actions가 OIDC로 맡는다 - 장기 액세스 키 없음.
#
# 환경마다 역할이 따로 있고, 신뢰 정책의 sub 조건이 GitHub environment 이름(staging | prod)에
# 묶인다. staging 워크플로가 받은 토큰으로는 prod 역할을 맡을 수 없다 (KAN-35 자격 증명 분리
# AC의 CI 쪽 경계). 어느 브랜치가 그 environment로 배포할 수 있는지는 GitHub 저장소의
# environment deployment branch policy가 정한다 (staging = Dev, prod = Release,
# infra/README.md "GitHub 설정").
#
# sub 형식은 repo:OWNER/REPO:environment:NAME이다. 워크플로 job이 `environment:`를 지정하면
# 토큰의 sub가 ref(브랜치) 형식이 아니라 environment 형식으로 나온다. 브랜치 형식(ref:refs/heads/Dev)
# 으로 묶지 않는 이유: KAN-128의 prod 승인 게이트가 GitHub environment의 required reviewers라
# job이 어차피 environment를 지정해야 하고, 그러면 sub가 environment 형식이 된다.
#
# 2026-07-15 이후 만든 저장소(이 레포 포함)는 sub에 소유자와 저장소의 숫자 ID가 붙는 불변 형식
# (repo:OWNER@ID/REPO@ID:environment:NAME)을 쓴다 - 이름이 재활용돼도 다른 주체가 못 맡게 하는
# 장치다. 2026-08-26 첫 실행이 "Not authorized"로 실패한 원인이 이것이었다. 두 형식을 모두 허용한다
# (StringEquals의 값 목록은 OR). 실제 형식은 `gh api repos/OWNER/REPO/actions/oidc/customization/sub`의
# sub_claim_prefix로 확인한다.

locals {
  name = "accentury-${var.env}"

  github_subjects = [
    "repo:${var.github_repository}:environment:${var.env}",
    "repo:${replace(var.github_repository, "/", "@${var.github_owner_id}/")}@${var.github_repository_id}:environment:${var.env}",
  ]
}

# 공급자는 계정에 1개뿐이라 bootstrap 스택이 소유한다 (infra/bootstrap/github-oidc.tf).
# bootstrap apply 전이면 여기서 plan이 실패한다 - 조용히 빈 역할을 만드는 것보다 낫다.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.github_subjects
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${local.name}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

# 웹 번들 업로드와 캐시 무효화만 (KAN-127). 이 환경의 버킷 1개와 배포 1개로 한정한다.
# DeleteObject는 주지 않는다 - 파이프라인이 sync --delete를 쓰지 않기 때문이다. 구 index.html을
# 아직 든 브라우저가 이전 해시 자산을 계속 받아야 하고, 등급 이미지(KAN-132)와 개인정보처리방침
# (KAN-133)이 같은 버킷에 산다. 지울 권한이 없으면 파이프라인 결함이 그것들을 지울 수도 없다.
# KAN-128(이미지 배포)은 SSM PutParameter와 Run Command 문장을 여기에 덧붙인다.
data "aws_iam_policy_document" "web_deploy" {
  statement {
    sid       = "ListWebBucket"
    actions   = ["s3:ListBucket"]
    resources = [var.web_bucket_arn]
  }

  statement {
    sid       = "PutWebObjects"
    actions   = ["s3:PutObject"]
    resources = ["${var.web_bucket_arn}/*"]
  }

  # GetInvalidation은 `aws cloudfront wait invalidation-completed`가 쓴다.
  statement {
    sid       = "InvalidateDistribution"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = [var.cloudfront_distribution_arn]
  }
}

resource "aws_iam_role_policy" "web_deploy" {
  name   = "web-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.web_deploy.json
}

# ---- 이미지 배포 (KAN-128) ----
#
# 파이프라인(.github/workflows/deploy.yml)이 하는 일과 1:1로 대응한다. 태그 조회와 SSM IMAGE_TAG
# 갱신, Run Command로 EC2에 reload 지시, 결과 조회, 스모크용 관리자 토큰 읽기. ECR push는
# staging 역할만 갖는다(승격 모델) - prod 역할로는 새 이미지를 만들 수 없고 staging이 검증한
# SHA를 고를 수만 있다.
#
# Run Command 대상은 인스턴스 ID가 아니라 tag:Name=accentury-{env}다. user_data가 바뀌면 인스턴스가
# 교체되는데(compute 모듈), ID를 변수로 따라가면 그때마다 GitHub 변수를 고쳐야 한다. 신뢰 정책과
# 마찬가지로 이 환경 이름이 붙은 인스턴스에만 보낼 수 있다.

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  ecr_repository_arns = [
    for repo in ["accentury/backend", "accentury/ai"] :
    "arn:aws:ecr:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:repository/${repo}"
  ]
  parameter_arn_prefix = "arn:aws:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter${var.ssm_prefix}"
}

data "aws_iam_policy_document" "image_deploy" {
  # 배포마다 바뀌는 유일한 파라미터. Terraform 소유가 아니라 파이프라인이 쓴다 (KAN-129 결정).
  statement {
    sid       = "ReadWriteImageTag"
    actions   = ["ssm:GetParameter", "ssm:PutParameter"]
    resources = ["${local.parameter_arn_prefix}/IMAGE_TAG"]
  }

  # E2E 스모크(KAN-138)가 합성 트래픽 표시에 쓰는 토큰. SecureString의 AWS 관리 키(aws/ssm)는
  # SSM 경유 호출에 계정 내 주체를 허용하므로 별도 kms 권한이 없다 (compute 모듈과 같은 근거).
  statement {
    sid       = "ReadAdminToken"
    actions   = ["ssm:GetParameter"]
    resources = ["${local.parameter_arn_prefix}/ACCENTURY_ADMIN_TOKEN"]
  }

  statement {
    sid       = "RunDeployOnOwnInstances"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ec2:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:instance/*"]

    condition {
      test     = "StringEquals"
      variable = "ssm:resourceTag/Name"
      values   = [local.name]
    }
  }

  statement {
    sid       = "RunShellScriptDocument"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ssm:${data.aws_region.current.region}::document/AWS-RunShellScript"]
  }

  # 명령 결과 조회는 리소스 수준 제한이 없는 API다.
  statement {
    sid       = "ReadCommandResult"
    actions   = ["ssm:ListCommandInvocations", "ssm:GetCommandInvocation"]
    resources = ["*"]
  }

  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # 태그 실재 확인(describe-images)과 pull. 두 환경 공통.
  statement {
    sid = "EcrRead"
    actions = [
      "ecr:DescribeImages",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchCheckLayerAvailability",
    ]
    resources = local.ecr_repository_arns
  }

  # push는 staging만 (ci_image_push). 삭제 권한은 어디에도 없다 - IMMUTABLE 태그를 지워 다시 올리는
  # 일은 사람이 콘솔에서 의도적으로만 한다.
  dynamic "statement" {
    for_each = var.ci_image_push ? [1] : []

    content {
      sid = "EcrPush"
      actions = [
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage",
      ]
      resources = local.ecr_repository_arns
    }
  }
}

resource "aws_iam_role_policy" "image_deploy" {
  name   = "image-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.image_deploy.json
}
