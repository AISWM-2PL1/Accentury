#!/bin/bash
# ai 상태 지표 -> CloudWatch 커스텀 지표 (KAN-36 health, KAN-38 임시파일 잔존).
# systemd 타이머가 1분마다 부른다.
#
# AI 호스트는 ALB 뒤가 아니라 대상 그룹 health가 없다. 그래서 호스트가 스스로 /internal/v0/health를
# 찔러 Healthy 0|1을 올리고, monitoring 모듈의 ai-unhealthy 경보가 3분 연속 0(또는 결측)에 선다.
# 워밍업 중(503 STARTING)도 0이다 - backend도 그 상태를 살아 있는 것으로 보지 않는다.
# 토큰 없이 두드린다 - health는 인증 예외다 (ai/app/auth.py).
#
# 여기에 임시 디렉터리 잔존 파일 수를 더한다 (KAN-38). AI는 BE와 달리 추론 라이브러리가 파형 파일을
# 읽으므로 오디오가 디스크를 한 번 거치고(ai/app/tempstore.py), 그 파일을 지우는 세 겹 중 마지막이
# 청소 잡이다. 잔존이 쌓인다는 것은 그 잡이 막혔다는 뜻이고, 원본 음성이 파기되지 않은 채 호스트에
# 남아 있다는 뜻이다 (NFR-PR-03) - 그래서 지표로 내고 경보(ai-temp-residue)가 본다.
#
# 그 값을 주는 /internal/v0/metrics는 health와 달리 <b>토큰이 필요하다</b>. SSM을 매분 다시 읽지 않고
# accentury-up.sh가 기동 때 만들어 둔 /run/accentury/ai.env에서 가져온다 - root 전용 tmpfs 파일이고
# 컨테이너에 들어가는 것과 같은 값이다. 그 파일이 아직 없거나(첫 부팅, compose 기동 전) 토큰이 비어
# 있으면 임시파일 지표만 건너뛴다 - health는 그것과 무관하게 계속 나가야 한다.
set -uo pipefail

# shellcheck source=/dev/null
. /etc/accentury/env.conf

AI_ENV_FILE=/run/accentury/ai.env

code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8000/internal/v0/health || echo 000)
value=0
if [ "$code" = 200 ]; then
  value=1
fi

metric_data="MetricName=Healthy,Dimensions=[{Name=env,Value=$ACCENTURY_ENV}],Value=$value,Unit=Count"

# JSON에서 이름 하나의 정수를 뽑는다. 이 응답의 값은 전부 정수다 (tempstore.metrics()는
# 건수와 반올림한 초만 담는다) - jq를 깔지 않으려고 sed 하나로 끝낸다.
json_int() {
  sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p"
}

temp_metrics=""
if [ "$value" = 1 ] && [ -r "$AI_ENV_FILE" ]; then
  # 값을 읽기만 한다 - source 하지 않는다. 이 파일은 docker의 env-file이지 셸 스크립트가 아니라
  # (accentury-up.sh가 KEY=VALUE로 쓴다) 따옴표 규칙이 없다. 지금 토큰은 영숫자 48자라
  # (config 모듈 random_password, special=false) source 해도 무사하지만, 나중에 이 파일에 공백이나
  # $가 든 값이 하나만 들어와도 셸이 그것을 해석한다.
  token=$(grep -m1 '^ACCENTURY_AI_INTERNAL_TOKEN=' "$AI_ENV_FILE" | cut -d= -f2-)
  if [ -n "$token" ]; then
    temp_metrics=$(curl -s --max-time 3 \
      -H "X-Accentury-Internal-Token: $token" \
      http://127.0.0.1:8000/internal/v0/metrics || true)
  fi
fi

temp_files=""
temp_oldest_age=""
temp_scan_failures=""
if [ -n "$temp_metrics" ]; then
  temp_files=$(printf '%s' "$temp_metrics" | json_int tempFiles)
  temp_oldest_age=$(printf '%s' "$temp_metrics" | json_int tempOldestAgeSeconds)
  temp_scan_failures=$(printf '%s' "$temp_metrics" | json_int tempScanFailures)
fi

# 값을 못 읽었으면 0을 올리지 않는다 - 0은 "깨끗하다"라서, 조회가 막힌 그 순간에 경보가
# 거꾸로 조용해진다 (tempstore.py의 scanned=false 처리와 같은 판단). 결측은 경보 쪽에서
# 다룬다 (ai-temp-residue는 notBreaching - 잔존을 모르는 것과 잔존이 쌓인 것은 다르다).
if [ -n "$temp_files" ]; then
  metric_data="$metric_data MetricName=TempFiles,Dimensions=[{Name=env,Value=$ACCENTURY_ENV}],Value=$temp_files,Unit=Count"
fi
# 최장 잔존 시간이 보존 기간(30분)을 넘었다면 삭제가 실패하고 있다는 뜻이다 - 건수는 처리 중인
# 파일로도 오르지만 이 값은 그렇지 않아, 청소 잡 고장의 정확한 신호다.
if [ -n "$temp_oldest_age" ]; then
  metric_data="$metric_data MetricName=TempOldestAge,Dimensions=[{Name=env,Value=$ACCENTURY_ENV}],Value=$temp_oldest_age,Unit=Seconds"
fi
if [ -n "$temp_scan_failures" ]; then
  metric_data="$metric_data MetricName=TempScanFailures,Dimensions=[{Name=env,Value=$ACCENTURY_ENV}],Value=$temp_scan_failures,Unit=Count"
fi

# 지표를 못 올려도(자격 증명 전파 전, API 일시 장애) 타이머의 다음 회차가 다시 시도한다.
# shellcheck disable=SC2086 -- metric_data는 우리가 만든 공백 구분 목록이라 분리되어야 한다.
aws cloudwatch put-metric-data \
  --region "$AWS_REGION" \
  --namespace "$METRIC_NAMESPACE" \
  --metric-data $metric_data \
  || echo "CloudWatch put-metric-data 실패 (health=$code) - 다음 회차에 재시도" >&2

echo "ai health=$code -> Healthy=$value, tempFiles=${temp_files:-미확인}, tempOldestAge=${temp_oldest_age:-미확인}, tempScanFailures=${temp_scan_failures:-미확인} ($METRIC_NAMESPACE env=$ACCENTURY_ENV)"
