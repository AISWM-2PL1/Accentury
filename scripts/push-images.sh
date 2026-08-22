#!/usr/bin/env bash
# BE, AI 이미지를 빌드해 ECR로 올린다 (KAN-120).
#
#   scripts/push-images.sh              # 현재 커밋으로 빌드 후 푸시
#   DRY_RUN=1 scripts/push-images.sh    # 푸시 없이 태그만 확인
#
# 이 스크립트가 정본인 이유는 태그 규칙 때문이다. 이미지는 언제나 commit SHA로만 올라간다 -
# 배포된 컨테이너를 보고 어느 소스에서 나왔는지 되짚을 수 없으면 롤백(KAN-128)이 성립하지
# 않는다. ECR 리포지토리는 IMMUTABLE로 만들어 같은 태그의 덮어쓰기를 AWS가 거부하지만,
# 그것만으로는 "SHA로 올린다"가 강제되지 않으므로 여기서 태그를 만든다.
set -euo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --query Account --output text)}"
REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

# 빌드 대상 아키텍처. 기본값은 빌드 머신을 따른다 - 애플 실리콘에서는 arm64가 나온다.
# 운영 EC2를 x86으로 띄우기로 하면 PLATFORM=linux/amd64로 넘겨야 이미지가 그 위에서 뜬다
# (인스턴스 선택은 KAN-124에서 확정한다).
PLATFORM="${PLATFORM:-}"
PLATFORM_ARG=()
[[ -n "$PLATFORM" ]] && PLATFORM_ARG=(--platform "$PLATFORM")

cd "$(dirname "$0")/.."

# 커밋되지 않은 변경이 있으면 멈춘다 - 그 상태로 만든 이미지는 SHA가 가리키는 소스와
# 내용이 다르고, 그러면 태그가 거짓말이 된다.
if [[ -n "$(git status --porcelain)" ]]; then
  echo "작업 트리가 깨끗하지 않습니다. 커밋하거나 되돌린 뒤 다시 실행하세요." >&2
  git status --short >&2
  exit 1
fi

SHA="$(git rev-parse --short=7 HEAD)"
echo "커밋 ${SHA} 기준으로 빌드합니다 (region=${AWS_REGION}, platform=${PLATFORM:-호스트 기본값})."

build_and_push() {
  local context="$1" repo="$2"
  local ref="${REGISTRY}/${repo}:${SHA}"

  echo "==> ${repo}:${SHA} 빌드"
  # 확장을 ${...+...}로 감싼 이유가 있다. macOS 기본 bash(3.2)는 set -u 아래에서 빈 배열의
  # "${arr[@]}"를 unbound variable로 친다 - PLATFORM을 주지 않는 기본 사용법에서 첫 빌드부터 죽는다.
  docker build ${PLATFORM_ARG[@]+"${PLATFORM_ARG[@]}"} -t "$ref" "$context"

  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    echo "    DRY_RUN - 푸시하지 않고 넘어갑니다: ${ref}"
    return
  fi

  echo "==> ${repo}:${SHA} 푸시"
  docker push -q "$ref"
  echo "    ${ref}"
}

if [[ "${DRY_RUN:-0}" != "1" ]]; then
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY" > /dev/null
fi

build_and_push ./backend accentury/backend
build_and_push ./ai      accentury/ai

echo
echo "완료. 배포는 이 두 태그를 그대로 가리킵니다:"
echo "  ${REGISTRY}/accentury/backend:${SHA}"
echo "  ${REGISTRY}/accentury/ai:${SHA}"
echo "latest 태그는 만들지 않습니다 - 어느 소스가 떠 있는지 태그만 보고 알 수 있어야 합니다."
