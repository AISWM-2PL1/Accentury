#!/usr/bin/env bash
# 릴리스 서명 키스토어를 딱 한 번 만들어 두는 도우미 (KAN-163).
#
#   scripts/make-release-keystore.sh ~/keys/accentury-release.jks [alias]
#
# ── 왜 조심해야 하는가 ────────────────────────────────────────────────────────
# 스토어는 "같은 키로 서명됐는가"만 보고 업데이트를 받아들인다. 그래서 이 키스토어나
# 비밀번호를 잃으면 이미 올라간 앱의 업데이트를 **영원히** 올릴 수 없다. 패키지명을 바꿔
# 새 앱으로 다시 시작하는 것 말고는 방법이 없고, 설치 기반과 리뷰는 그대로 버려진다.
# (Play App Signing은 2021년 8월 이후 새 앱에 필수다. 거기서 이 키를 업로드 키로만 쓰면
# 구글이 앱 서명 키를 대신 들고 있어 재발급 경로가 생긴다. 하지만 이 키스토어를 앱 서명 키로
# 올리는 쪽을 권하고 있어서 - docs/wiki/android-release-signing.md §6 - 이 파일은 복구 경로가
# 없는 것으로 다룬다.)
#
# 그러니 만든 다음:
#   1. 레포 밖 안전한 곳에 둔다 (*.jks / *.keystore는 .gitignore에 있지만 믿지 말 것).
#   2. 비밀번호는 팀 비밀번호 관리자에.
#   3. **보관 위치와 담당자를 팀 위키에 적는다.** 파일만 남고 어디 있는지 아무도 모르는 게
#      실제로 가장 흔한 사고다.
#
# ── 비밀번호를 인자로 받지 않는 이유 ─────────────────────────────────────────
# 이 스크립트는 비밀번호를 인자로도 환경변수로도 받지 않고 keytool이 직접 묻게 둔다.
# 명령줄 인자는 셸 히스토리(~/.zsh_history)와 같은 머신의 `ps` 출력에 그대로 남고,
# 환경변수는 자식 프로세스 전부에 상속된다. app/build.gradle.kts의 releaseSigning()이
# gradle -P 단계를 만들지 않은 것과 같은 판단이다.
set -euo pipefail

usage() {
  cat <<'USAGE'
사용법: scripts/make-release-keystore.sh <출력.jks> [alias]

  <출력.jks>  만들 키스토어 경로. 레포 밖을 권장한다. 이미 있으면 덮지 않고 멈춘다.
  [alias]     키 alias. 기본값 accentury.

예:
  scripts/make-release-keystore.sh ~/keys/accentury-release.jks
  scripts/make-release-keystore.sh ~/keys/accentury-release.jks accentury
USAGE
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage >&2
  exit 2
fi
if [[ "$1" == "-h" || "$1" == "--help" ]]; then
  usage
  exit 0
fi

OUT="$1"
ALIAS="${2:-accentury}"

# keytool 찾기. 이 맥에는 시스템 자바가 없고 Android Studio가 들고 있는 JBR만 있다
# (accentury-app의 gradlew도 JAVA_HOME을 그쪽으로 잡아 돌린다).
JBR_KEYTOOL="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
elif [[ -x "$JBR_KEYTOOL" ]]; then
  KEYTOOL="$JBR_KEYTOOL"
elif command -v keytool >/dev/null 2>&1; then
  KEYTOOL="$(command -v keytool)"
else
  echo "오류: keytool을 찾을 수 없다." >&2
  echo "  JAVA_HOME을 잡거나(예: export JAVA_HOME=\"/Applications/Android Studio.app/Contents/jbr/Contents/Home\")" >&2
  echo "  Android Studio를 설치해라." >&2
  exit 1
fi

if [[ -e "$OUT" ]]; then
  echo "오류: $OUT 이(가) 이미 있다. 덮어쓰지 않는다." >&2
  echo "  기존 키스토어를 덮으면 그 키로 서명된 앱의 업데이트 경로가 끊긴다." >&2
  echo "  정말 새로 만들 거면 기존 파일을 직접 옮기거나 지운 뒤 다시 실행해라." >&2
  exit 1
fi

OUT_DIR="$(dirname "$OUT")"
if [[ ! -d "$OUT_DIR" ]]; then
  echo "오류: 디렉터리가 없다: $OUT_DIR" >&2
  exit 1
fi
OUT_ABS="$(cd "$OUT_DIR" && pwd)/$(basename "$OUT")"

# 레포 안에 만들려 하면 경고한다. .gitignore에 *.jks가 있어 실수로 커밋되지는 않지만,
# 레포를 통째로 지우거나 새로 클론하면 같이 사라지는 자리라 애초에 두면 안 되는 곳이다.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$OUT_ABS" == "$REPO_ROOT"/* ]]; then
  echo "경고: 출력 경로가 레포 안이다 - $OUT_ABS" >&2
  echo "  .gitignore가 *.jks를 막고 있어 커밋되지는 않지만, 레포를 지우면 키도 사라진다." >&2
  echo "  ~/keys/ 같은 레포 밖 경로를 권한다." >&2
  echo "" >&2
fi

echo "키스토어를 만든다: $OUT_ABS"
echo "  alias: $ALIAS"
echo "  keytool: $KEYTOOL"
echo ""
echo "이제 keytool이 비밀번호와 이름·조직 정보를 묻는다."
echo "  - 키스토어 비밀번호와 키 비밀번호는 같게 두는 편이 낫다 (CI 설정이 단순해진다)."
echo "  - 이름(CN)은 팀·서비스 이름으로. 스토어 표시에 쓰이지는 않는다."
echo ""

# -validity 10000일(약 27년). 인증서가 만료되면 그 키로 서명한 새 업데이트를 올릴 수 없어서
# 릴리스 키는 관행적으로 길게 잡는다 (구글도 2033-10-22 이후까지 유효할 것을 요구한다).
"$KEYTOOL" -genkeypair \
  -keystore "$OUT_ABS" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

chmod 600 "$OUT_ABS"

echo ""
echo "생성 완료: $OUT_ABS"
echo ""
echo "─────────────────────────────────────────────────────────────────────"
echo "1) SHA-256 인증서 지문 - App Links(KAN-32)의 assetlinks.json에 넣을 값"
echo "─────────────────────────────────────────────────────────────────────"
echo "(키스토어 비밀번호를 다시 묻는다. keytool이 읽을 때마다 묻는 것이라 정상이다.)"
"$KEYTOOL" -list -v -keystore "$OUT_ABS" -alias "$ALIAS" | grep -i "SHA256:" || {
  echo "지문을 읽지 못했다. 비밀번호를 잘못 입력했을 수 있다." >&2
}

echo ""
echo "─────────────────────────────────────────────────────────────────────"
echo "2) 카카오 키 해시 - 카카오 개발자 콘솔에 등록할 값 (KAN-30)"
echo "   base64(sha1(인증서 DER))"
echo "─────────────────────────────────────────────────────────────────────"
"$KEYTOOL" -exportcert -alias "$ALIAS" -keystore "$OUT_ABS" \
  | openssl sha1 -binary \
  | openssl base64

echo ""
echo "─────────────────────────────────────────────────────────────────────"
echo "3) GitHub 시크릿 등록 (릴리스 워크플로용)"
echo "─────────────────────────────────────────────────────────────────────"
echo "키스토어를 base64 한 줄로 바꾸는 명령이다. 값이 터미널 스크롤백에 남지 않도록"
echo "출력하지 않고 명령만 적는다 - 아래를 그대로 실행해 클립보드로 받아라."
echo ""
echo "  base64 -i \"$OUT_ABS\" | tr -d '\\n' | pbcopy"
echo ""
echo "(클립보드 없이 값을 보고 싶으면 | pbcopy 를 빼면 된다. 스크롤백에 남는다는 것만 알고 써라.)"
echo ""
echo "등록할 시크릿 이름 (4개):"
echo "  RELEASE_KEYSTORE_BASE64    - 위 명령의 출력"
echo "  RELEASE_KEYSTORE_PASSWORD  - 키스토어 비밀번호"
echo "  RELEASE_KEY_PASSWORD       - 키 비밀번호"
echo "  KAKAO_NATIVE_APP_KEY       - 카카오 네이티브 앱 키 (KAN-30)"
echo ""
echo "키 alias($ALIAS)는 시크릿이 아니다. .github/workflows/app-release.yml의 릴리스 빌드 스텝에"
echo "env 상수로 박혀 있다 - 시크릿으로 두면 GitHub가 로그에서 그 문자열을 전부 가려 패키지명·경로까지"
echo "***가 되기 때문이다."
if [[ "$ALIAS" != "accentury" ]]; then
  echo ""
  echo "경고: alias를 기본값(accentury)과 다르게 줬다. app-release.yml의 RELEASE_KEY_ALIAS 상수를"
  echo "  '$ALIAS'로 바꿔야 CI 서명이 붙는다."
fi
echo ""
echo "로컬에서 서명 빌드를 해 보려면 local.properties에:"
echo "  releaseKeystorePath=$OUT_ABS"
echo "  releaseKeystorePassword=..."
echo "  releaseKeyAlias=$ALIAS"
echo "  releaseKeyPassword=..."
echo ""
echo "마지막으로: 이 파일의 보관 위치와 담당자를 팀 위키에 적어라."
