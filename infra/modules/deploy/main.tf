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
