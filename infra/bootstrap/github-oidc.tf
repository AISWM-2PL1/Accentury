# GitHub Actions OIDC 자격 증명 공급자 (KAN-127). 배포 파이프라인이 장기 액세스 키 없이
# 환경별 IAM 역할을 맡는 출발점이다 (KAN-127 웹 번들, KAN-128 이미지 배포 공용).
#
# bootstrap에 있는 이유: IAM OIDC 공급자는 계정에 URL당 1개만 있을 수 있다. 환경 스택 2벌이
# 각자 만들면 두 번째 apply가 EntityAlreadyExists로 실패한다. 환경별 역할은 각 환경 스택
# (modules/deploy)이 만들고, 이 공급자는 data 소스로 조회만 한다.
#
# thumbprint_list를 두지 않는다. GitHub는 AWS가 자체 신뢰 루트 CA 목록으로 검증하는 공급자라
# 지문이 무시되고(provider 문서), 한 번 적어 두면 나중에 지워도 Terraform이 옛 값을 계속 쓴다.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # 지우면 두 환경의 배포 역할이 한꺼번에 신뢰 대상을 잃는다.
  lifecycle {
    prevent_destroy = true
  }
}

output "github_oidc_provider_arn" {
  value       = aws_iam_openid_connect_provider.github.arn
  description = "envs/*의 modules/deploy가 data 소스로 조회하는 공급자"
}
