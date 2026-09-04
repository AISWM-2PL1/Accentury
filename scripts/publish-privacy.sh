#!/usr/bin/env bash
# 개인정보처리방침 페이지를 환경의 웹 버킷에 올린다 (KAN-133).
#
#   scripts/publish-privacy.sh staging
#   scripts/publish-privacy.sh prod
#
# ── 왜 웹 배포가 아니라 별도 스크립트인가 ────────────────────────────────────
# 이 티켓의 AC가 "본문 교체가 배포 없이 S3 업로드만으로 가능하다"이다. 페이지를 web/public 아래에
# 두면 문구 한 줄을 고칠 때마다 웹 번들을 다시 빌드해 배포해야 하고, 그러면 AC가 깨진다. 게다가
# 본문의 정본은 코드가 아니라 팀이 확정하는 문서다 (KAN-176). 바뀌는 시점과 바꾸는 사람이 웹
# 번들과 다르므로 게시 경로도 나눈다. `.well-known`이 같은 이유로 이미 이 모양이다
# (scripts/publish-well-known.sh).
#
# 웹 배포와 서로를 지우지 않는다: web-deploy.yml은 `aws s3 sync`를 `--delete` 없이 쓰고, 배포
# 역할에는 `s3:DeleteObject` 자체가 없다 (infra/modules/deploy/main.tf). 그래서 여기서 올린
# privacy.html은 이후의 웹 배포에 살아남고, 이 스크립트는 그 키 하나만 `cp`하므로 웹 번들을
# 건드리지 않는다. 몇 번을 돌려도 같은 결과다 (멱등).
#
# ── 왜 확장자가 붙은 /privacy.html인가 ───────────────────────────────────────
# 확장자 없는 `/privacy`는 CloudFront SPA 재작성 함수(infra/modules/edge/spa-rewrite.js)가
# `/index.html`로 돌려 앱 화면이 뜬다. 마지막 경로 조각에 점이 있으면 그 규칙을 지나가므로
# 함수를 고치지 않고 정적 페이지를 서빙할 수 있다 (2026-09-01 KAN-133 정정). 이 전제는
# infra/modules/edge/spa-rewrite.test.mjs가 붙들고 있다.
#
# content-type을 명시하는 이유: S3는 확장자로 타입을 추론하므로 .html이면 text/html이 맞게
# 붙지만, 이 페이지가 HTML로 렌더링되는 것이 AC라서 추론에 맡기지 않고 박아 둔다.
# cache-control은 5분으로 짧게 잡는다. 문서 교체가 언제 일어날지 모르는데 길게 잡으면 "고쳐
# 올렸는데 옛 문서가 보인다"가 되고, 이 페이지는 트래픽이 적어 캐시로 아낄 것도 없다.
set -euo pipefail

CURRENT_STEP="시작"
on_error() {
  local code=$?
  echo "" >&2
  echo "실패: $CURRENT_STEP (줄 ${BASH_LINENO[0]}, 종료 코드 $code)" >&2
  echo "  원인을 고치고 같은 명령을 그대로 다시 돌려라. 덮어쓰기뿐이라 몇 번을 돌려도 된다 (멱등)." >&2
  exit "$code"
}
trap on_error ERR

usage() {
  cat <<'USAGE'
사용법: scripts/publish-privacy.sh <staging|prod>

  infra/privacy/privacy.html을 그 환경의 웹 S3 버킷 루트에 올리고, CloudFront를 무효화한 뒤
  도메인으로 실제 응답을 받아 상태·타입·내용을 대조한다.

환경 변수로 덮어쓸 수 있는 값 (없으면 terraform output에서 읽는다):
  WEB_BUCKET                 대상 S3 버킷 이름
  CLOUDFRONT_DISTRIBUTION_ID 무효화할 배포 ID

예:
  scripts/publish-privacy.sh staging
  WEB_BUCKET=... CLOUDFRONT_DISTRIBUTION_ID=... scripts/publish-privacy.sh prod
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
SRC="$REPO_ROOT/infra/privacy/privacy.html"
KEY="privacy.html"

if [[ ! -f "$SRC" ]]; then
  echo "오류: $SRC 이(가) 없다." >&2
  exit 1
fi

CURRENT_STEP="필요한 명령 확인"
for cmd in aws curl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "오류: $cmd 가 필요하다." >&2
    exit 1
  fi
done

CURRENT_STEP="대상 버킷·배포 확인 (terraform output)"
tf_output() {
  terraform -chdir="$REPO_ROOT/infra/envs/$ENV_NAME" output -raw "$1" 2>/dev/null
}

if [[ -z "${WEB_BUCKET:-}" || -z "${CLOUDFRONT_DISTRIBUTION_ID:-}" ]]; then
  if ! command -v terraform >/dev/null 2>&1; then
    echo "오류: WEB_BUCKET 또는 CLOUDFRONT_DISTRIBUTION_ID가 없고 terraform도 없다. 환경변수로 넘겨라." >&2
    exit 1
  fi
fi
if [[ -z "${WEB_BUCKET:-}" ]]; then
  WEB_BUCKET="$(tf_output web_bucket || true)"
fi
if [[ -z "${CLOUDFRONT_DISTRIBUTION_ID:-}" ]]; then
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

CURRENT_STEP="S3 업로드: $KEY"
echo "업로드: $KEY"
aws s3 cp "$SRC" "s3://$WEB_BUCKET/$KEY" \
  --content-type 'text/html; charset=utf-8' \
  --cache-control 'public, max-age=300' \
  --no-progress

echo ""
CURRENT_STEP="CloudFront 무효화 생성"
echo "CloudFront 무효화"
invalidation_id=$(aws cloudfront create-invalidation \
  --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" \
  --paths "/$KEY" \
  --query Invalidation.Id --output text)
CURRENT_STEP="CloudFront 무효화 $invalidation_id 완료 대기"
echo "  $invalidation_id 완료 대기"
aws cloudfront wait invalidation-completed \
  --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" --id "$invalidation_id"

echo ""
CURRENT_STEP="도메인으로 응답 확인"
echo "도메인으로 확인"
url="https://$DOMAIN/$KEY"
body="$(mktemp)"
# --fail을 쓰지 않는 이유는 4xx일 때도 본문을 봐야 원인(SPA 재작성에 걸린 index.html인지,
# 객체가 없어 OAC가 돌려준 S3 AccessDenied XML인지)이 보이기 때문이다.
read -r status content_type < <(curl -s -o "$body" -w '%{http_code} %{content_type}\n' "$url")

failed=0
if [[ "$status" != "200" ]]; then
  echo "  ✗ HTTP $status" >&2
  failed=1
elif [[ "$content_type" != text/html* ]]; then
  echo "  ✗ content-type이 '$content_type' (text/html 이어야 한다)" >&2
  failed=1
elif ! cmp -s "$body" "$SRC"; then
  # 내용이 다르면 캐시가 남았거나, SPA 재작성이 index.html을 돌려준 것이다.
  echo "  ✗ 받은 내용이 로컬 파일과 다르다 (캐시가 남았거나 SPA 재작성에 걸렸다)" >&2
  echo "      재작성 규칙을 확인해라: infra/modules/edge/spa-rewrite.js" >&2
  failed=1
else
  echo "  ✓ $url - 200 text/html, 내용 일치"
fi
rm -f "$body"

if [[ "$failed" -ne 0 ]]; then
  echo "" >&2
  echo "게시가 끝나지 않았다. 위 항목을 고치고 다시 돌려라 (멱등이라 몇 번 돌려도 된다)." >&2
  exit 1
fi

cat <<NEXT

─────────────────────────────────────────────────────────────────────
확정 URL
─────────────────────────────────────────────────────────────────────
  $url

스토어 등록 정보(KAN-174, KAN-175)와 앱 내 링크(KAN-177)가 참조하는 주소다.
본문을 갈아 끼울 때는 infra/privacy/privacy.html을 고치고 이 스크립트를 다시 돌린다
(웹 배포와 무관하다).
NEXT
