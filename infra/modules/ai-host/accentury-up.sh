#!/bin/bash
# ai 컨테이너 기동 (KAN-124, 전용 호스트 KAN-36, ALB 뒤 KAN-201). systemd accentury.service의 ExecStart와 ExecReload가 부른다.
#
#   1. IMDSv2로 자기 인스턴스 ID를 읽는다 - 컨테이너 로그 스트림 이름에 쓴다.
#   2. SSM Parameter Store에서 이 호스트 몫을 읽어 /run/accentury/*.env를 만든다.
#        /accentury/{env}/ai/* 와 /accentury/{env}/IMAGE_TAG (IAM도 그 둘만 허용한다)
#   3. ECR에 로그인한다 (토큰은 12시간짜리라 매 기동마다 새로 받는다).
#   4. docker compose up -d
#
# KAN-36에서는 3번 앞에 프라이빗 영역에 자기 사설 IP로 A 레코드를 UPSERT하는 단계가 있었다 - backend가 부르는
# 고정 이름(ai.accentury.internal)이 인스턴스 하나를 가리키던 시절이다. KAN-201부터 그 이름은 앞단 내부 ALB의
# alias(Terraform 소유)이고 대상 등록은 ASG가 하므로, 이 스크립트는 자기 주소를 어디에도 알리지 않는다.
#
# backend는 이 호스트에 없다 - ECS Fargate 서비스(infra/modules/fargate, KAN-165)가 SSM 값을 태스크 정의의
# secrets로 받는다. 배포 파이프라인(KAN-128)은 SSM의 IMAGE_TAG를 새 SHA로 바꾼 뒤 이 호스트에서
# `systemctl reload accentury`로 이 스크립트를 다시 태우고(env 파일이 바뀐 서비스만 compose가 다시 만든다),
# backend는 ecs update-service로 굴린다. 재부팅 뒤에도 같은 경로라 마지막으로 반영된 태그가 그대로 뜬다.
#
# /run은 tmpfs다. env 파일은 root 전용(umask 077)이고 재부팅하면 사라졌다가 다시 만들어진다. docker는
# 컨테이너 환경 변수를 /var/lib/docker/containers/*/config.v2.json(암호화 루트 볼륨, root 전용)에 남기므로
# "호스트 디스크에 평문 0"은 아니다 (Codex P2, KAN-129에서 판단).
#
# 파라미터 이름의 마지막 조각이 환경 변수 이름이다 (예: /accentury/staging/ai/ACCENTURY_AI_INTERNAL_TOKEN).
#   IMAGE_TAG        -> compose.env  (compose 파일 안의 보간 전용, 컨테이너에 안 들어간다)
#   그 외 전부        -> ai.env (/ai 하위 경로 아래 어떤 이름이든 - KAN-22의 모델 설정 포함)
# 여기에 env.conf의 ACCENTURY_ENV를 더한다 - 지표 차원(env)으로 쓴다 (KAN-36). compose.env에는 로깅
# 드라이버가 보간하는 값 3개(AWS_REGION, AI_LOG_GROUP, INSTANCE_ID)도 함께 적는다 (KAN-203).
# 값에 탭이나 개행이 들어가면 안 된다 (aws --output text가 그 둘로 행을 나눈다).
set -euo pipefail

# 환경별 값(ACCENTURY_ENV, SSM_PREFIX, ECR_REGISTRY, AWS_REGION, AI_LOG_GROUP, VPC_CIDR)은 Terraform이 써 둔다.
# shellcheck source=/dev/null
. /etc/accentury/env.conf

RUN_DIR=/run/accentury
umask 077
mkdir -p "$RUN_DIR"

# 일시 장애 재시도 (Codex P1). 첫 부팅에서는 인스턴스 프로파일의 IAM 전파가 수십 초 늦거나
# SSM/ECR 호출이 간헐적으로 실패할 수 있는데, oneshot 유닛은 한 번 실패하면 아무도 다시
# 부르지 않아 재부팅이나 사람 손이 닿을 때까지 컨테이너가 없는 채로 남는다. 백오프 합계 약 4분
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

# 자기 인스턴스 신원 (IMDSv2) - 컨테이너 로그 스트림 이름이다 (KAN-203, ai/<인스턴스 ID>). ASG가 교체해도
# 스트림이 갈리므로 교체 전 인스턴스의 로그가 새 인스턴스의 로그에 섞이지 않고, 여러 대가 함께 돌 때도
# (KAN-201) 어느 대상이 처리했는지 스트림으로 가른다. IMDS는 호스트에서만 닿는다 (시작 템플릿의 hop limit 1 -
# 컨테이너는 못 본다). 재시도까지 다 실패하면 기동이 실패한다 - 이름 없는 스트림으로 뜨는 것보다 낫다.
read_identity() {
  local token
  # -f를 붙인다 - 없으면 IMDS가 내는 4xx/5xx 본문이 종료 코드 0과 함께 값으로 잡힌다. 그 쓰레기 값은
  # compose.env를 거쳐 로그 스트림 이름이 되므로 조용히 흘러간다 (Codex 리뷰). --max-time은 링크 로컬
  # 주소가 응답을 멈췄을 때 매달리지 않게 한다.
  token=$(curl -fsS --max-time 5 -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 60") || return 1
  INSTANCE_ID=$(curl -fsS --max-time 5 -H "X-aws-ec2-metadata-token: $token" \
    "http://169.254.169.254/latest/meta-data/instance-id") || return 1
  # 형태까지 본다. compose.env의 KEY=VALUE 한 줄이 되고 로그 스트림 이름이 되므로, 개행이나 엉뚱한
  # 문자열이 들어오면 파일 규약과 스트림 이름이 함께 깨진다.
  [[ "$INSTANCE_ID" =~ ^i-[0-9a-f]+$ ]] || return 1
}
retry read_identity

compose_env="$RUN_DIR/compose.env"
ai_env="$RUN_DIR/ai.env"

# 임시 파일에 다 쓴 뒤 한 번에 바꿔 끼운다 - SSM 조회가 중간에 실패해도 직전 기동의
# 온전한 파일이 남는다.
tmp_compose="$(mktemp "$RUN_DIR/compose.env.XXXXXX")"
tmp_ai="$(mktemp "$RUN_DIR/ai.env.XXXXXX")"
trap 'rm -f "$tmp_compose" "$tmp_ai" "$RUN_DIR"/params.*' EXIT

printf 'ECR_REGISTRY=%s\n' "$ECR_REGISTRY" > "$tmp_compose"
# 로깅 드라이버(awslogs)가 보간하는 값 (KAN-203). 그룹 이름은 Terraform이 env.conf에 써 둔 것이고,
# 스트림 이름은 이 인스턴스 ID로 갈린다.
printf 'AWS_REGION=%s\n' "$AWS_REGION" >> "$tmp_compose"
printf 'AI_LOG_GROUP=%s\n' "$AI_LOG_GROUP" >> "$tmp_compose"
printf 'INSTANCE_ID=%s\n' "$INSTANCE_ID" >> "$tmp_compose"
printf 'ACCENTURY_ENV=%s\n' "$ACCENTURY_ENV" > "$tmp_ai"

# 조회 결과를 파일에 먼저 받는다 - 파이프 중간에서 재시도하면 앞서 쓴 절반이 남는다.
params="$(mktemp "$RUN_DIR/params.XXXXXX")"
echo "SSM ${SSM_PREFIX}/ai/* 와 ${SSM_PREFIX}/IMAGE_TAG 읽는 중 (region=${AWS_REGION})."
fetch_params() {
  aws ssm get-parameters-by-path \
    --region "$AWS_REGION" \
    --path "$SSM_PREFIX/ai" \
    --with-decryption \
    --query 'Parameters[].[Name,Value]' \
    --output text > "$params"
}
# IMAGE_TAG는 이름으로 하나만 읽는다 - 이 호스트 역할은 자기 하위 경로와 이 이름만 허용된다 (IAM).
# 부재(ParameterNotFound)는 "아직 배포 전"이라 재시도 없이 바로 실패하고, 그 밖의 오류만 재시도한다.
fetch_image_tag() {
  local out
  if out=$(aws ssm get-parameter --region "$AWS_REGION" --name "$SSM_PREFIX/IMAGE_TAG" \
      --query 'Parameter.Value' --output text 2>&1); then
    printf '%s/IMAGE_TAG\t%s\n' "$SSM_PREFIX" "$out" >> "$params"
    return 0
  fi
  if grep -q ParameterNotFound <<<"$out"; then
    echo "SSM ${SSM_PREFIX}/IMAGE_TAG 가 없습니다. 배포 파이프라인(KAN-128)이 반영한 이미지 태그가 있어야 기동합니다." >&2
    exit 1
  fi
  echo "$out" >&2
  return 1
}
retry fetch_params
retry fetch_image_tag

while IFS=$'\t' read -r name value; do
  key="${name##*/}"
  case "$key" in
    IMAGE_TAG) printf 'IMAGE_TAG=%s\n' "$value" >> "$tmp_compose" ;;
    *)         printf '%s=%s\n' "$key" "$value" >> "$tmp_ai" ;;
  esac
done < "$params"
rm -f "$params"

# 태그 부재는 일시 장애가 아니라 "아직 배포 전" 상태다 - 재시도 없이 바로 실패한다.
if ! grep -q '^IMAGE_TAG=' "$tmp_compose"; then
  echo "SSM ${SSM_PREFIX}/IMAGE_TAG 가 없습니다. 배포 파이프라인(KAN-128)이 반영한 이미지 태그가 있어야 기동합니다." >&2
  exit 1
fi

mv "$tmp_compose" "$compose_env"
mv "$tmp_ai" "$ai_env"
trap - EXIT

echo "ai.env $(wc -l < "$ai_env")개 변수. $(grep '^IMAGE_TAG=' "$compose_env")"

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

# 배포를 거듭하면 이전 SHA 이미지가 루트 볼륨에 쌓인다 - ECR 라이프사이클 정책은
# 원격만 정리한다 (Codex P2). 어느 컨테이너도 쓰지 않는 이미지를 지운다. 롤백은 ECR에서
# 다시 당기면 되므로(최근 50개 보관) 로컬 사본을 남길 이유가 없다. 실패해도 기동은 성공이다.
docker image prune -af > /dev/null || echo "이미지 정리 실패 - 기동에는 영향 없음" >&2
