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
#   ACCENTURY_AI_*   - 스텁은 설정 없이 뜬다 (KAN-22 실모델 때 추가). 예외 하나가 아래 내부 호출
#                      토큰인데, 그것은 ai 호스트 전용 하위 경로 {prefix}/ai/ 에 둔다 (KAN-36).
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

# 전용 EC2의 ai 서비스 (KAN-36 A단계). 값은 인스턴스 주소가 아니라 프라이빗 영역의 이름이다 -
# ASG가 인스턴스를 교체해도 새 인스턴스가 같은 이름의 A 레코드를 갱신하므로 이 값은 안 바뀐다.
# 이름 자체는 network 모듈이 정하고(ai.accentury.internal, 두 환경 같은 값) 여기는 조립만 한다.
# 배포 갱신은 systemctl reload accentury(backend 호스트)로 backend.env에 반영된다.
resource "aws_ssm_parameter" "ai_base_url" {
  name  = "${var.ssm_prefix}/ACCENTURY_ANALYSIS_AIBASEURL"
  type  = "String"
  value = "http://${var.ai_dns_name}:8000"
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

# 등급 이미지의 기준 URL (§3.7 share.imageUrl, KAN-132). backend가 등급 code로 `{기준}/{code}.png`를
# 만든다 - 값 5개 대신 1개다. 이미지는 웹 S3 버킷의 share/ 아래에 있고(scripts/publish-share-assets.sh)
# CloudFront 기본 동작(S3 오리진)이 서빙한다. 파일명에 확장자가 있어 SPA 재작성 Function에 걸리지
# 않는다. 도메인이 환경마다 달라 여기서 조립한다 - 코드 기본값(prod 도메인)이 staging에 새면 안 된다.
resource "aws_ssm_parameter" "asset_base_url" {
  name  = "${var.ssm_prefix}/ACCENTURY_RESULT_ASSETBASEURL"
  type  = "String"
  value = "https://${var.domain}/share"
}

# ---- 시크릿 ----

# 관리자 API(§6)와 E2E 스모크(KAN-138) 합성 트래픽 표시의 공유 시크릿. AdminAuth가 32자 미만을
# 거부하므로 그 위로 넉넉히 잡는다. 특수문자를 빼는 것은 curl과 워크플로 YAML에서 따옴표 문제를
# 만들지 않기 위해서다 - 48자 영숫자면 엔트로피는 충분하다.
# state에 평문이 남는다 (S3 암호화 + 버전 관리 버킷, KAN-140이 수용한 범위). 값 조회:
#   aws ssm get-parameter --with-decryption --name /accentury/{env}/ACCENTURY_ADMIN_TOKEN --query Parameter.Value --output text
# 재발급은 envs 루트에서 `terraform apply -replace='module.config.random_password.admin_token'`, 그리고
# 인스턴스에서 systemctl reload accentury (README "관리자 토큰" 절). taint는 0.15.2부터 deprecated다.
resource "random_password" "admin_token" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "admin_token" {
  name  = "${var.ssm_prefix}/ACCENTURY_ADMIN_TOKEN"
  type  = "SecureString" # AWS 관리 키(aws/ssm) - EC2 역할에 별도 kms 권한이 필요 없다 (compute IAM 주석).
  value = random_password.admin_token.result
}

# backend -> ai 내부 호출의 공유 시크릿 (KAN-36). 두 서비스가 다른 호스트로 갈라지면서 "같은
# compose 네트워크라 backend만 부를 수 있다"는 전제가 사라졌으므로, SG 한 겹 뒤에 헤더 검사를 한 겹
# 더 둔다. 난수 하나를 두 이름으로 싣는다 - backend 쪽은 Spring 프로퍼티 이름 규칙
# (accentury.analysis.ai-token -> ACCENTURY_ANALYSIS_AITOKEN), ai 쪽은 FastAPI 설정 이름
# (ACCENTURY_AI_INTERNAL_TOKEN)이고, ai 것은 ai 호스트 역할만 읽는 하위 경로 {prefix}/ai/ 에 둔다
# (compute 모듈 IAM). backend 호스트의 기동 스크립트는 하위 경로를 읽지 않는다 (--recursive 없음).
# 재발급은 admin_token과 같은 방식이고, 두 호스트 모두 reload해야 한다.
resource "random_password" "ai_internal_token" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "ai_token_backend" {
  name  = "${var.ssm_prefix}/ACCENTURY_ANALYSIS_AITOKEN"
  type  = "SecureString"
  value = random_password.ai_internal_token.result
}

resource "aws_ssm_parameter" "ai_token_ai" {
  name  = "${var.ssm_prefix}/ai/ACCENTURY_AI_INTERNAL_TOKEN"
  type  = "SecureString"
  value = random_password.ai_internal_token.result
}

# 실모델 전환에 맞춘 분석 시간 예산 (KAN-22, 임시값 - 정식 재확정은 KAN-172).
#
# 스텁 시절의 기본값(ai-timeout 10초)은 실모델에서 성립하지 않는다. 추론 1건이 08-30 실측으로
# 14~30초이고(KAN-159, 도커 amd64에서는 MFA만 23초) x86 CPU에서는 더 걸린다 - 기본값을 그대로
# 두면 모든 분석이 읽기 타임아웃으로 끊겨 재전송 예산만 태우고 회로가 열린다.
#
# 값 사이의 관계는 backend가 기동 시점에 강제한다 (AnalysisDispatchConfig).
#
#   processing-timeout > ai-timeout x (재시도 2 + 1) + 백오프 0.9초   -> 300 > 255.9
#   shutdown-budget(90초, 코드 기본값) > ai-timeout                    -> 90 > 85
#
# dispatch-concurrency를 1로 내리는 것이 이 조합의 핵심이다. AI는 추론을 한 번에 하나만 돌리므로
# (워커 프로세스 1개, GPU 슬롯 1) 4개를 동시에 보내면 뒤의 셋은 앞의 추론이 끝나기를 AI 안에서
# 기다리다 읽기 타임아웃에 걸린다 - 늘린 상한이 그대로 무의미해지는 자리다.
resource "aws_ssm_parameter" "analysis_ai_timeout" {
  name  = "${var.ssm_prefix}/ACCENTURY_ANALYSIS_AITIMEOUT"
  type  = "String"
  value = var.analysis_ai_timeout
}

resource "aws_ssm_parameter" "analysis_processing_timeout" {
  name  = "${var.ssm_prefix}/ACCENTURY_ANALYSIS_PROCESSINGTIMEOUT"
  type  = "String"
  value = var.analysis_processing_timeout
}

resource "aws_ssm_parameter" "analysis_dispatch_concurrency" {
  name  = "${var.ssm_prefix}/ACCENTURY_ANALYSIS_DISPATCHCONCURRENCY"
  type  = "String"
  value = tostring(var.analysis_dispatch_concurrency)
}

# AI 자신의 분석 상한 (초). backend의 읽기 타임아웃보다 짧게 둔다 - 그래야 AI가 스스로 끊고
# 503을 돌려주고(BE는 일시 장애로 보고 재전송한다) 멈춘 추론이 임시파일을 붙들지 않는다.
# 반대로 두면 BE가 먼저 포기하는데 AI는 계속 추론해 GPU 슬롯과 임시파일이 그만큼 더 남는다.
resource "aws_ssm_parameter" "ai_analysis_timeout_seconds" {
  name  = "${var.ssm_prefix}/ai/ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS"
  type  = "String"
  value = tostring(var.ai_analysis_timeout_seconds)
}
