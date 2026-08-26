# backend 컨테이너 환경 변수의 정본 - SSM Parameter Store /accentury/{env}/* (KAN-129).
#
# 값이 전부 다른 모듈의 출력(RDS 주소, 마스터 시크릿 ARN, VPC CIDR, 도메인)이거나 두 환경이
# 같은 상수라, 손으로 put-parameter 하면 재구축 때마다 어긋난다. 이름의 마지막 조각이 그대로
# 컨테이너 환경 변수 이름이다 (accentury-up.sh, KAN-124) - Spring relaxed binding 규칙에 따라
# 점은 밑줄로, 대시는 제거, 대문자다 (accentury.trusted-proxies -> ACCENTURY_TRUSTEDPROXIES).
#
# 같은 이름의 파라미터가 계정에 이미 있으면(재구축 전 수동 생성분) apply가 ParameterAlreadyExists로
# 실패한다 - aws_ssm_parameter는 남의 값을 덮어쓰지 않는다. 그때는 README "이미 있는 SSM 파라미터"
# 절대로 import한다.
#
# 여기 없는 것:
#   IMAGE_TAG        - 배포 파이프라인(KAN-128)이 쓴다. Terraform이 소유하면 배포마다 drift다.
#   ACCENTURY_AI_*   - 스텁은 설정 없이 뜬다 (KAN-22 실모델 때 추가).
#   DB 비밀번호      - SSM에 두지 않는다. RDS 관리형 시크릿은 7일마다 회전되므로 복사본은 첫
#                      회전에서 죽는다. backend가 URL의 secretsManagerSecretId로 Secrets Manager에서
#                      직접 읽는다 (application-deploy.yml, awsSecretsManager 플러그인).

terraform {
  required_providers {
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }
}

# ---- 배포 공통 상수 (두 환경 같은 값) ----

# 이 프로파일에서만 backend가 필수값 누락 시 기동을 세운다 (DeploymentConfigGuard).
resource "aws_ssm_parameter" "spring_profiles_active" {
  name  = "${var.ssm_prefix}/SPRING_PROFILES_ACTIVE"
  type  = "String"
  value = "deploy"
}

# compose 내부 네트워크의 ai 서비스 - 포트를 발행하지 않으므로 backend만 닿는다 (KAN-124).
resource "aws_ssm_parameter" "ai_base_url" {
  name  = "${var.ssm_prefix}/ACCENTURY_ANALYSIS_AIBASEURL"
  type  = "String"
  value = "http://ai:8000"
}

# ---- 환경별 값 ----

# AWS Advanced JDBC Wrapper URL. 사용자 이름과 비밀번호 파라미터는 없다 - 시크릿 ARN만 넘기면
# 플러그인이 연결 시점에 읽고, 회전 뒤 인증 실패가 나면 다시 받아 재접속한다 (KAN-129 확정).
# ARN을 URL 쿼리에 그대로 넣는다 - 쿼리 구분자(&, =)가 ARN에 없어 인코딩이 필요 없다.
resource "aws_ssm_parameter" "datasource_url" {
  name  = "${var.ssm_prefix}/SPRING_DATASOURCE_URL"
  type  = "String"
  value = "jdbc:aws-wrapper:postgresql://${var.rds_endpoint}/${var.db_name}?secretsManagerSecretId=${var.rds_master_user_secret_arn}"
}

# 요청 제한의 기준 IP를 정할 때 신뢰하는 프록시 대역 (KAN-28 ClientIps). VPC CIDR 하나다 -
# CloudFront VPC 오리진 ENI와 internal ALB가 둘 다 VPC 안이라 XFF의 오른쪽 두 홉이 이 대역에
# 들고, 그 앞의 값(뷰어 IP)이 사용자다. CloudFront 오리진 페이싱 공인 대역은 VPC 오리진에서
# 매칭될 일이 없어 넣지 않는다 (2026-08-25 정정).
resource "aws_ssm_parameter" "trusted_proxies" {
  name  = "${var.ssm_prefix}/ACCENTURY_TRUSTEDPROXIES"
  type  = "String"
  value = var.vpc_cidr
}

# 공유 카드가 여는 웹 테스트 URL (§3.7, KAN-30) - 캠페인 파라미터까지 붙은 완성 URL.
resource "aws_ssm_parameter" "web_test_url" {
  name  = "${var.ssm_prefix}/ACCENTURY_RESULT_WEBTESTURL"
  type  = "String"
  value = "https://${var.domain}/t?c=kko_share"
}

# ---- 시크릿 ----

# 관리자 API(§6)와 E2E 스모크(KAN-138) 합성 트래픽 표시의 공유 시크릿. AdminAuth가 32자 미만을
# 거부하므로 그 위로 넉넉히 잡는다. 특수문자를 빼는 것은 curl과 워크플로 YAML에서 따옴표 문제를
# 만들지 않기 위해서다 - 48자 영숫자면 엔트로피는 충분하다.
# state에 평문이 남는다 (S3 암호화 + 버전 관리 버킷, KAN-140이 수용한 범위). 값 조회:
#   aws ssm get-parameter --with-decryption --name /accentury/{env}/ACCENTURY_ADMIN_TOKEN --query Parameter.Value --output text
# 재발급은 envs 루트에서 `terraform taint 'module.config.random_password.admin_token'` 후 apply, 그리고
# 인스턴스에서 systemctl reload accentury (README "관리자 토큰" 절).
resource "random_password" "admin_token" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "admin_token" {
  name  = "${var.ssm_prefix}/ACCENTURY_ADMIN_TOKEN"
  type  = "SecureString" # AWS 관리 키(aws/ssm) - EC2 역할에 별도 kms 권한이 필요 없다 (compute IAM 주석).
  value = random_password.admin_token.result
}
