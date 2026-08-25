#!/bin/bash
# 컨테이너 기동 (KAN-124). systemd accentury.service의 ExecStart와 ExecReload가 부른다.
#
#   1. SSM Parameter Store의 이 환경 경로(/accentury/{env}/*)를 읽어 /run/accentury/*.env를 만든다.
#   2. ECR에 로그인한다 (토큰은 12시간짜리라 매 기동마다 새로 받는다).
#   3. docker compose up -d
#
# 배포 파이프라인(KAN-128)은 SSM의 IMAGE_TAG를 새 SHA로 바꾼 뒤 `systemctl reload accentury`로
# 이 스크립트를 다시 태운다 - env 파일이 바뀐 서비스만 compose가 다시 만든다. 재부팅 뒤에도
# 같은 경로라 마지막으로 반영된 태그가 그대로 뜬다.
#
# /run은 tmpfs다. env 파일은 root 전용(umask 077)이고 재부팅하면 사라졌다가 다음 기동에서
# 다시 만들어진다 - 낡은 사본이 호스트에 쌓이지 않는다. 다만 docker는 컨테이너 환경 변수를
# /var/lib/docker/containers/*/config.v2.json(암호화된 루트 볼륨, root 전용)에 기록하므로
# "호스트 디스크에 평문 0"은 아니다 (Codex P2). 그 수준이 필요해지면 파일 시크릿 마운트로
# 바꾸고 앱이 파일을 읽게 해야 한다 - KAN-129에서 판단.
#
# 파라미터 이름의 마지막 조각이 환경 변수 이름이다 (예: /accentury/staging/SPRING_DATASOURCE_URL).
# 라우팅:
#   IMAGE_TAG        -> compose.env  (image: 보간 전용, 컨테이너에 안 들어간다)
#   ACCENTURY_AI_*   -> ai.env
#   그 외 전부        -> backend.env
# 값에 탭이나 개행이 들어가면 안 된다 (aws --output text가 그 둘로 행을 나눈다).
set -euo pipefail

# 환경별 값(ACCENTURY_ENV, SSM_PREFIX, ECR_REGISTRY, AWS_REGION)은 Terraform이 써 둔다.
# shellcheck source=/dev/null
. /etc/accentury/env.conf

RUN_DIR=/run/accentury
umask 077
mkdir -p "$RUN_DIR"

# 일시 장애 재시도 (Codex P1). 첫 부팅에서는 인스턴스 프로파일의 IAM 전파가 수십 초 늦거나
# SSM/ECR 호출이 간헐적으로 실패할 수 있는데, oneshot 유닛은 한 번 실패하면 아무도 다시
# 부르지 않아 재부팅이나 사람 손이 닿을 때까지 대상이 unhealthy로 남는다. 백오프 합계 약 4분
# (systemd TimeoutStartSec=900 안쪽). 그래도 실패하면 유닛이 failed로 남고 재부팅이 재시도다.
retry() {
  local attempt=1 delay=5
  until "$@"; do
    if (( attempt >= 8 )); then
      echo "재시도 ${attempt}회 모두 실패: $*" >&2
      return 1
    fi
    echo "실패 (${attempt}/8), ${delay}초 뒤 재시도: $*" >&2
    sleep "$delay"
    attempt=$((attempt + 1))
    delay=$((delay * 2 > 60 ? 60 : delay * 2))
  done
}

compose_env="$RUN_DIR/compose.env"
backend_env="$RUN_DIR/backend.env"
ai_env="$RUN_DIR/ai.env"

# 임시 파일에 다 쓴 뒤 한 번에 바꿔 끼운다 - SSM 조회가 중간에 실패해도 직전 기동의
# 온전한 파일이 남는다.
tmp_compose="$(mktemp "$RUN_DIR/compose.env.XXXXXX")"
tmp_backend="$(mktemp "$RUN_DIR/backend.env.XXXXXX")"
tmp_ai="$(mktemp "$RUN_DIR/ai.env.XXXXXX")"
trap 'rm -f "$tmp_compose" "$tmp_backend" "$tmp_ai" "$RUN_DIR"/params.*' EXIT

printf 'ECR_REGISTRY=%s\n' "$ECR_REGISTRY" > "$tmp_compose"

echo "SSM ${SSM_PREFIX}/* 읽는 중 (region=${AWS_REGION})."
# 조회 결과를 파일에 먼저 받는다 - 파이프 중간에서 재시도하면 앞서 쓴 절반이 남는다.
params="$(mktemp "$RUN_DIR/params.XXXXXX")"
fetch_params() {
  aws ssm get-parameters-by-path \
    --region "$AWS_REGION" \
    --path "$SSM_PREFIX" \
    --recursive \
    --with-decryption \
    --query 'Parameters[].[Name,Value]' \
    --output text > "$params"
}
retry fetch_params

while IFS=$'\t' read -r name value; do
  key="${name##*/}"
  case "$key" in
    IMAGE_TAG)      printf 'IMAGE_TAG=%s\n' "$value" >> "$tmp_compose" ;;
    ACCENTURY_AI_*) printf '%s=%s\n' "$key" "$value" >> "$tmp_ai" ;;
    *)              printf '%s=%s\n' "$key" "$value" >> "$tmp_backend" ;;
  esac
done < "$params"
rm -f "$params"

# 태그 부재는 일시 장애가 아니라 "아직 배포 전" 상태다 - 재시도 없이 바로 실패한다.
if ! grep -q '^IMAGE_TAG=' "$tmp_compose"; then
  echo "SSM ${SSM_PREFIX}/IMAGE_TAG 가 없습니다. 배포 파이프라인(KAN-128)이 반영한 이미지 태그가 있어야 기동합니다." >&2
  exit 1
fi

mv "$tmp_compose" "$compose_env"
mv "$tmp_backend" "$backend_env"
mv "$tmp_ai" "$ai_env"
trap - EXIT

echo "backend.env $(wc -l < "$backend_env")개, ai.env $(wc -l < "$ai_env")개 변수. $(grep '^IMAGE_TAG=' "$compose_env")"

# 인스턴스 프로파일(AmazonEC2ContainerRegistryReadOnly)로 ECR 로그인. 자격 증명은
# /root/.docker/config.json에 남지만 12시간 뒤 만료되는 토큰이고 root 전용이다.
ecr_login() {
  aws ecr get-login-password --region "$AWS_REGION" |
    docker login --username AWS --password-stdin "$ECR_REGISTRY" > /dev/null
}
retry ecr_login

cd /opt/accentury
# --env-file을 명시하면 프로젝트 디렉터리의 .env는 읽지 않는다. 태그는 IMMUTABLE이라
# 같은 태그가 이미 있으면 다시 당기지 않아도 된다 (기본 pull 정책 missing).
compose_up() {
  docker compose --env-file "$compose_env" up -d --remove-orphans
}
retry compose_up

# 배포를 거듭하면 이전 SHA 이미지가 루트 볼륨(20GB)에 쌓인다 - ECR 라이프사이클 정책은
# 원격만 정리한다 (Codex P2). 어느 컨테이너도 쓰지 않는 이미지를 지운다. 롤백은 ECR에서
# 다시 당기면 되므로(최근 50개 보관) 로컬 사본을 남길 이유가 없다. 실패해도 기동은 성공이다.
docker image prune -af > /dev/null || echo "이미지 정리 실패 - 기동에는 영향 없음" >&2
