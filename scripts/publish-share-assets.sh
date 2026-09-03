#!/usr/bin/env bash
# 등급 공유 이미지 5장을 환경의 웹 버킷에 올린다 (KAN-132).
#
#   scripts/publish-share-assets.sh staging
#   scripts/publish-share-assets.sh prod
#
# ── 무엇을 어디에 ────────────────────────────────────────────────────────────
# `assets/share/<tier>.png` 5장(KAN-162 생성물)을 `s3://<웹 버킷>/share/<tier>.png`로 올린다.
# backend는 SSM `ACCENTURY_RESULT_ASSETBASEURL`(= https://<도메인>/share)에 등급 code를 붙여
# `/result`의 `share.imageUrl`을 만든다 (TierAssets). 파일명은 등급 code 소문자로 고정이라 이
# 스크립트도 backend도 이름을 다시 정하지 않는다.
#
# ── 왜 배포 파이프라인이 아니라 손으로 도는 스크립트인가 ──────────────────────
# publish-well-known.sh와 같은 이유다. 이미지는 웹 번들과 수명이 다르다 - 캐릭터가 바뀔 때만
# 바뀌고, 그때는 build.py로 다시 만든 파일을 이 스크립트로 올리면 끝이다 (AC - 이미지 교체는
# S3 업로드만으로 반영, 앱과 서버 배포 불필요). web-deploy.yml은 `sync --delete`를 쓰지 않고
# 배포 역할에 DeleteObject가 없어, 여기서 올린 `share/*`는 이후 웹 배포에 살아남는다.
#
# ── 캐시 ────────────────────────────────────────────────────────────────────
# cache-control은 1일이다. 웹 번들처럼 1년 immutable로 올리면 캐릭터 교체가 1년 걸리는 사고가
# 되고, 카카오와 브라우저가 매번 다시 받게 no-cache로 두면 공유 카드마다 원본을 친다. 교체
# 즉시 반영이 필요하면 아래 CloudFront 무효화가 그 몫이다 - 이 스크립트가 매번 한다.
#
# 필요한 권한은 대상 버킷의 `s3:PutObject`와 배포의 `cloudfront:CreateInvalidation`,
# `cloudfront:GetInvalidation`이다 (배포 역할이 갖고 있다. DeleteObject는 필요 없다).
set -euo pipefail

CURRENT_STEP="시작"
on_error() {
  local code=$?
  echo "" >&2
  echo "실패: $CURRENT_STEP (줄 ${BASH_LINENO[0]}, 종료 코드 $code)" >&2
  echo "  일부 등급만 올라간 채 남았을 수 있다. 원인을 고치고 같은 명령을 그대로 다시 돌려라 (덮어쓰기뿐이라 멱등)." >&2
  exit "$code"
}
trap on_error ERR

usage() {
  cat <<'USAGE'
사용법: scripts/publish-share-assets.sh <staging|prod>

  assets/share/<tier>.png 5장을 그 환경의 웹 S3 버킷 share/ 아래에 올리고,
  CloudFront를 무효화한 뒤 도메인으로 실제 응답(200, image/png, 내용 일치)을 확인한다.

환경 변수로 덮어쓸 수 있는 값 (없으면 terraform output에서 읽는다):
  WEB_BUCKET                 대상 S3 버킷 이름
  CLOUDFRONT_DISTRIBUTION_ID 무효화할 배포 ID

예:
  scripts/publish-share-assets.sh staging
  WEB_BUCKET=... CLOUDFRONT_DISTRIBUTION_ID=... scripts/publish-share-assets.sh prod
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
SRC_DIR="$REPO_ROOT/assets/share"
# 등급 code 소문자 = 파일명 (backend ScorePolicyRegistry.TIER_CODES, TierAssets.imageUrl)
TIERS=(outsider traveler wannabe honorary native)

for tier in "${TIERS[@]}"; do
  if [[ ! -f "$SRC_DIR/$tier.png" ]]; then
    echo "오류: $SRC_DIR/$tier.png 이(가) 없다. assets/characters/build.py로 만든다." >&2
    exit 1
  fi
done

CURRENT_STEP="필요한 명령 확인"
for cmd in aws curl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "오류: $cmd 가 필요하다." >&2
    exit 1
  fi
done

# 올리기 전에 PNG인지와 카카오 상한(5MB)을 본다 - 깨진 파일도 200으로 서빙되므로 curl만으로는 안 걸린다.
CURRENT_STEP="올릴 파일 검사"
for tier in "${TIERS[@]}"; do
  file="$SRC_DIR/$tier.png"
  if [[ "$(head -c 8 "$file" | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]]; then
    echo "오류: $file 이(가) PNG가 아니다. 올리지 않는다." >&2
    exit 1
  fi
  size=$(wc -c < "$file" | tr -d ' ')
  if (( size > 5 * 1024 * 1024 )); then
    echo "오류: $file 이(가) 5MB를 넘는다 ($size 바이트) - 카카오가 가져가지 않는다." >&2
    exit 1
  fi
done

CURRENT_STEP="대상 버킷·배포 확인 (terraform output)"
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

for tier in "${TIERS[@]}"; do
  CURRENT_STEP="S3 업로드: $tier.png"
  echo "업로드: share/$tier.png"
  aws s3 cp "$SRC_DIR/$tier.png" "s3://$WEB_BUCKET/share/$tier.png" \
    --content-type 'image/png' \
    --cache-control 'public, max-age=86400' \
    --no-progress
done

echo ""
CURRENT_STEP="CloudFront 무효화 생성 (5장 모두 업로드됨)"
echo "CloudFront 무효화"
invalidation_id=$(aws cloudfront create-invalidation \
  --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" \
  --paths '/share/*' \
  --query Invalidation.Id --output text)
CURRENT_STEP="CloudFront 무효화 $invalidation_id 완료 대기"
echo "  $invalidation_id 완료 대기"
aws cloudfront wait invalidation-completed \
  --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" --id "$invalidation_id"

echo ""
CURRENT_STEP="도메인으로 응답 확인"
echo "도메인으로 확인"
failed=0
for tier in "${TIERS[@]}"; do
  url="https://$DOMAIN/share/$tier.png"
  body="$(mktemp)"
  read -r status content_type < <(curl -s -o "$body" -w '%{http_code} %{content_type}\n' "$url")

  if [[ "$status" != "200" ]]; then
    echo "  ✗ $tier.png - HTTP $status" >&2
    failed=1
  elif [[ "$content_type" != image/png* ]]; then
    # text/html로 오면 SPA 재작성에 걸린 것이다 - 확장자가 있어 걸리지 않아야 정상이다.
    echo "  ✗ $tier.png - content-type이 '$content_type' (image/png 이어야 한다)" >&2
    failed=1
  elif ! cmp -s "$body" "$SRC_DIR/$tier.png"; then
    echo "  ✗ $tier.png - 받은 내용이 로컬 파일과 다르다 (캐시가 남았거나 다른 객체가 올라가 있다)" >&2
    failed=1
  else
    echo "  ✓ $tier.png - 200 image/png, 내용 일치"
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
다음 확인
─────────────────────────────────────────────────────────────────────
backend가 내려주는 값과 맞는지 - 완주한 세션의 /result:
  share.imageUrl == https://$DOMAIN/share/<등급 code 소문자>.png
  (SSM /accentury/$ENV_NAME/ACCENTURY_RESULT_ASSETBASEURL = https://$DOMAIN/share, 태스크 재배포 뒤 반영)

카카오 카드가 그려지려면 이 도메인($DOMAIN)이 카카오 개발자 콘솔의 플랫폼 도메인에
등록돼 있어야 한다 (assets/share/README.md) - AWS 밖 설정이라 이 스크립트가 확인하지 않는다.
NEXT
