#!/usr/bin/env bash
# 로컬 풀스택(BE + AI + DB)을 띄우고 전 구간 E2E 스모크를 돌린다 (KAN-138).
#
#   scripts/e2e-smoke-local.sh                # 띄우고, 돌리고, 내린다
#   KEEP=1 scripts/e2e-smoke-local.sh         # 끝나도 스택을 남긴다 (로그를 더 볼 때)
#   FAIL_ITEM=v1 scripts/e2e-smoke-local.sh   # 실패 갈래가 겨눌 음성 문항을 바꾼다
#   PIN=100 scripts/e2e-smoke-local.sh        # 억양 점수를 고정한다
#   PIN=0 VOCAB=2 scripts/e2e-smoke-local.sh  # 최하위 등급(외지인)까지 재현한다
#
# 이 스크립트가 하는 일은 스모크 자체가 아니라 **무대를 세우는 것**이다. 시나리오는 전부
# scripts/e2e_smoke.py에 있고, 여기서는 그 스크립트가 staging이나 prod에서는 이미 갖춰져 있는
# 두 가지를 로컬에 만들어 준다.
#
#   1. 스택이 떠 있고 healthy할 것.
#   2. 실패 갈래를 만들 수 있을 것 - AI가 ACCENTURY_AI_STUB_FAIL_ITEM을 물고 뜬 상태.
#
# docker compose의 --wait은 v2.1.1 이상이 필요하다. 없으면 healthy를 기다리지 않고 넘어가
# 첫 요청이 connection refused로 죽는다.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
# 실패 갈래가 겨눌 음성 문항. gn-2026.08.1의 음성 문항은 v1~v5다.
FAIL_ITEM="${FAIL_ITEM:-v3}"

# 합성 트래픽 표시용 관리자 시크릿 (KAN-138). docker-compose.yml이 backend에 주는 값과 같아야
# 한다 - 다르면 세션 생성이 401이다. 이 표시 덕에 스모크의 응시와 완주가 실사용자 통계와
# 다른 행에 쌓이고, 스크립트가 앞뒤 집계를 읽어 그 분리를 실제로 확인한다.
SYNTHETIC_KEY="${SYNTHETIC_KEY:-local-e2e-admin-token-0123456789abcdef}"

# 복구 명령. E2E_FAIL_ITEM을 비운 채 ai만 다시 띄워, 그 문항이 더는 실패하지 않게 한다.
# 값을 비우는 것을 명시하는 이유는 이 명령이 스모크 프로세스의 환경을 물려받기 때문이다 -
# 아래 compose up에 E2E_FAIL_ITEM을 export가 아니라 인라인으로 주는 것도 같은 이유다.
RECOVER_CMD="E2E_FAIL_ITEM= docker compose up -d --no-deps --wait ai"

passed=0
cleanup() {
  if [[ "$passed" != "1" ]]; then
    echo ""
    echo "==> 실패 - 최근 컨테이너 로그"
    docker compose logs --tail=80 || true
  fi
  if [[ "${KEEP:-0}" == "1" ]]; then
    echo "==> KEEP=1 - 스택을 남깁니다. 정리: docker compose down -v"
    return
  fi
  echo "==> 스택 정리"
  docker compose down -v > /dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> 스택 기동 (실패 갈래용으로 AI가 ${FAIL_ITEM}을 실패시키도록 띄웁니다)"
# 인라인으로 준다 - export하면 위 복구 명령까지 같은 값을 물려받아 복구가 성립하지 않는다.
E2E_FAIL_ITEM="$FAIL_ITEM" docker compose up -d --build --wait

SMOKE_ARGS=(
  --base-url "$BASE_URL"
  --fail-item "$FAIL_ITEM"
  --recover-cmd "$RECOVER_CMD"
  --synthetic-key "$SYNTHETIC_KEY"
  # 로컬 AI는 언제나 스텁이므로 억양 점수를 해시로 예측해 대조할 수 있다 (KAN-136).
  # BE가 X-Correlation-Id를 AI로 전파하지 않게 되면 여기서 잡힌다.
  --expect-stub-scores
)
# if로 쓰는 것은 취향이 아니다 - `[[ ... ]] && cmd`가 스크립트의 마지막 줄로 밀리면
# set -e 아래에서 조건이 거짓일 때 스크립트가 1로 죽는다 (macOS bash 3.2에서 확인).
if [[ -n "${PIN:-}" ]]; then
  SMOKE_ARGS+=(--pin-intonation "$PIN")
fi
# 어휘 선택지 인덱스. 단어 점수를 옮기는 손잡이라, PIN과 함께 써야 5등급을 다 볼 수 있다
# (scripts/e2e_smoke.py 머리말의 조합표 참고).
if [[ -n "${VOCAB:-}" ]]; then
  SMOKE_ARGS+=(--vocab-choice-index "$VOCAB")
fi
if [[ "${VERBOSE:-0}" == "1" ]]; then
  SMOKE_ARGS+=(--verbose)
fi

echo "==> 스모크 실행"
python3 scripts/e2e_smoke.py "${SMOKE_ARGS[@]}"

passed=1
echo "==> 로컬 E2E 스모크 통과"
