#!/usr/bin/env bash
# App Link 검증 파일을 환경의 웹 버킷에 올린다 (KAN-32 4단계).
#
#   scripts/publish-well-known.sh staging
#   scripts/publish-well-known.sh prod
#
# ── 왜 배포 파이프라인이 아니라 손으로 도는 스크립트인가 ──────────────────────
# 올리는 것은 `infra/well-known/<env>/.well-known/` 두 파일뿐이고, 바뀌는 시점이 웹 번들과
# 전혀 다르다 - 서명 키나 팀 ID가 바뀔 때, 즉 몇 년에 한 번이다. 그 두 파일 때문에 web-deploy를
# 건드리면 매 배포가 지문을 다시 밀어 올리게 되고, 지문의 정본이 레포인지 버킷인지가 흐려진다.
#
# 웹 배포와 서로를 지우지 않는다: web-deploy.yml은 `aws s3 sync`를 `--delete` 없이 쓰고,
# 배포 역할에는 `s3:DeleteObject` 자체가 없다 (infra/modules/deploy/main.tf). 그래서 여기서
# 올린 `.well-known/*` 객체는 이후의 웹 배포에 살아남는다. 반대로 이 스크립트는 그 두 키만
# `cp`하므로 웹 번들을 건드리지 않는다. 몇 번을 돌려도 같은 결과다 (멱등).
#
# 필요한 권한은 대상 버킷의 `s3:PutObject`와 배포의 `cloudfront:CreateInvalidation` 둘뿐이다
# (배포 역할이 둘 다 갖고 있다. DeleteObject는 필요 없다).
#
# ── content-type을 반드시 박는 이유 ──────────────────────────────────────────
# `apple-app-site-association`은 확장자가 없어서 S3가 타입을 못 알아보고
# `binary/octet-stream`으로 올린다. 애플은 `application/json`(또는 text/plain)을 요구하므로
# 그대로 두면 파일은 있는데 검증만 안 되는 상태가 된다.
#
# cache-control은 5분으로 짧게 잡는다. 애플 CDN과 안드로이드 검증기는 자기 일정으로 다시
# 받아 가므로 우리 쪽 캐시를 길게 잡을 값이 없고, 1년짜리 immutable로 올려 두면 키 교체가
# 1년 걸리는 사고가 된다.
set -euo pipefail

usage() {
  cat <<'USAGE'
사용법: scripts/publish-well-known.sh <staging|prod>

  infra/well-known/<env>/.well-known/ 아래 두 파일을 그 환경의 웹 S3 버킷에 올리고,
  CloudFront를 무효화한 뒤 도메인으로 실제 응답을 받아 로컬 파일과 대조한다.

환경 변수로 덮어쓸 수 있는 값 (없으면 terraform output에서 읽는다):
  WEB_BUCKET                 대상 S3 버킷 이름
  CLOUDFRONT_DISTRIBUTION_ID 무효화할 배포 ID

예:
  scripts/publish-well-known.sh staging
  WEB_BUCKET=... CLOUDFRONT_DISTRIBUTION_ID=... scripts/publish-well-known.sh prod
USAGE
}

if [[ $# -ne 1 ]]; then
  usage >&2
  exit 2
fi
if [[ "$1" == "-h" || "$1" == "--help" ]]; then
  usage
  exit 0
fi

ENV_NAME="$1"
case "$ENV_NAME" in
  staging) DOMAIN="staging.accentury.app" ;;
  prod) DOMAIN="accentury.app" ;;
  *)
    echo "오류: 환경은 staging 또는 prod다 (받은 값: $ENV_NAME)" >&2
    exit 2
    ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$REPO_ROOT/infra/well-known/$ENV_NAME/.well-known"
FILES=(assetlinks.json apple-app-site-association)

for name in "${FILES[@]}"; do
  if [[ ! -f "$SRC_DIR/$name" ]]; then
    echo "오류: $SRC_DIR/$name 이(가) 없다." >&2
    exit 1
  fi
done

for cmd in aws curl python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "오류: $cmd 가 필요하다." >&2
    exit 1
  fi
done

# 올리기 전에 JSON인지 본다. 깨진 JSON은 200으로 서빙되므로 curl 검증만으로는 안 걸리고,
# 안드로이드 검증기와 애플 CDN이 조용히 무시하는 형태로 며칠 뒤에 드러난다.
# jq 대신 python3을 쓰는 이유는 macOS에 늘 있기 때문이다.
for name in "${FILES[@]}"; do
  if ! python3 -m json.tool "$SRC_DIR/$name" >/dev/null; then
    echo "오류: $SRC_DIR/$name 이(가) JSON으로 파싱되지 않는다. 올리지 않는다." >&2
    exit 1
  fi
done

# 대상은 환경변수 우선, 없으면 그 환경의 terraform output에서 읽는다.
tf_output() {
  terraform -chdir="$REPO_ROOT/infra/envs/$ENV_NAME" output -raw "$1" 2>/dev/null
}

if [[ -z "${WEB_BUCKET:-}" ]]; then
  if ! command -v terraform >/dev/null 2>&1; then
    echo "오류: WEB_BUCKET이 없고 terraform도 없다. 환경변수로 넘겨라." >&2
    exit 1
  fi
  WEB_BUCKET="$(tf_output web_bucket || true)"
fi
if [[ -z "${CLOUDFRONT_DISTRIBUTION_ID:-}" ]]; then
  if ! command -v terraform >/dev/null 2>&1; then
    echo "오류: CLOUDFRONT_DISTRIBUTION_ID가 없고 terraform도 없다. 환경변수로 넘겨라." >&2
    exit 1
  fi
  CLOUDFRONT_DISTRIBUTION_ID="$(tf_output cloudfront_distribution_id || true)"
fi

if [[ -z "$WEB_BUCKET" || -z "$CLOUDFRONT_DISTRIBUTION_ID" ]]; then
  echo "오류: 대상을 정하지 못했다 (버킷='$WEB_BUCKET', 배포='$CLOUDFRONT_DISTRIBUTION_ID')." >&2
  echo "  infra/envs/$ENV_NAME 에서 terraform init이 돼 있는지 확인하거나 환경변수로 넘겨라." >&2
  exit 1
fi

echo "환경   : $ENV_NAME ($DOMAIN)"
echo "버킷   : $WEB_BUCKET"
echo "배포   : $CLOUDFRONT_DISTRIBUTION_ID"
echo ""

for name in "${FILES[@]}"; do
  echo "업로드: $name"
  aws s3 cp "$SRC_DIR/$name" "s3://$WEB_BUCKET/.well-known/$name" \
    --content-type 'application/json' \
    --cache-control 'public, max-age=300' \
    --no-progress
done

echo ""
echo "CloudFront 무효화"
invalidation_id=$(aws cloudfront create-invalidation \
  --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" \
  --paths '/.well-known/*' \
  --query Invalidation.Id --output text)
echo "  $invalidation_id 완료 대기"
aws cloudfront wait invalidation-completed \
  --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" --id "$invalidation_id"

echo ""
echo "도메인으로 확인"
failed=0
for name in "${FILES[@]}"; do
  url="https://$DOMAIN/.well-known/$name"
  body="$(mktemp)"
  # -o로 본문을 받고 -w로 상태·타입을 한 줄에 받는다. --fail을 쓰지 않는 이유는
  # 4xx일 때도 본문을 봐야 원인(리라이트에 걸린 index.html인지, 진짜 404인지)이 보이기 때문이다.
  read -r status content_type < <(curl -s -o "$body" -w '%{http_code} %{content_type}\n' "$url")

  if [[ "$status" != "200" ]]; then
    echo "  ✗ $name - HTTP $status" >&2
    failed=1
  elif [[ "$content_type" != application/json* ]]; then
    # AASA가 text/html로 오면 SPA 재작성 Function의 /.well-known/ 예외가 빠진 것이다.
    echo "  ✗ $name - content-type이 '$content_type' (application/json 이어야 한다)" >&2
    echo "      SPA 재작성 예외를 확인해라: infra/modules/edge/spa-rewrite.js" >&2
    failed=1
  elif ! cmp -s "$body" "$SRC_DIR/$name"; then
    echo "  ✗ $name - 받은 내용이 로컬 파일과 다르다 (캐시가 남았거나 다른 객체가 올라가 있다)" >&2
    failed=1
  else
    echo "  ✓ $name - 200 application/json, 내용 일치"
  fi
  rm -f "$body"
done

if [[ "$failed" -ne 0 ]]; then
  echo "" >&2
  echo "게시가 끝나지 않았다. 위 항목을 고치고 다시 돌려라 (멱등이라 몇 번 돌려도 된다)." >&2
  exit 1
fi

cat <<NEXT

─────────────────────────────────────────────────────────────────────
다음 확인 (외부 캐시라 반영에 시간이 걸릴 수 있다)
─────────────────────────────────────────────────────────────────────
안드로이드 - 구글 검증기가 우리 선언을 읽는지:
  https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://$DOMAIN&relation=delegate_permission/common.handle_all_urls

iOS - 애플 CDN이 받아 간 사본:
  https://app-site-association.cdn-apple.com/a/v1/$DOMAIN

기기 확인 명령은 infra/well-known/README.md "게시 뒤 확인"에 있다.
NEXT
