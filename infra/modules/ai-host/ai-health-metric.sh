#!/bin/bash
# ai 상태 지표 -> CloudWatch 커스텀 지표 (KAN-36 health와 호스트 디스크와 메모리, KAN-38 임시파일 잔존, KAN-204 단계별 지연).
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

# 호스트 디스크와 메모리 사용률 (KAN-36 B단계). 이 호스트는 CloudWatch agent를 깔지 않는다 - 이 타이머가
# 이미 같은 네임스페이스로 지표를 올리고 있어 경로 하나로 끝난다. 둘 다 health와 무관하게 매분 나간다 -
# 컨테이너가 죽어 있어도 디스크가 차는 것(이미지 pull 실패의 원인)은 봐야 한다.
#
# 디스크는 루트 파일시스템 하나다. ai 이미지(7GB)와 docker 사본 로그, 임시 오디오가 전부 루트 볼륨에 있고
# 다른 마운트는 없다. df의 Use%는 이미 예약 블록을 뺀 정수 백분율이라 그대로 쓴다.
#
# 메모리는 호스트 전체 대비 (total - available) / total 이다. 컨테이너 mem_limit(7GiB) 대비가 아니라 호스트
# 대비인 이유는 티켓 결정 그대로다 - 경보가 지키려는 것은 "호스트 OOM 킬러가 SSM 에이전트나 docker를
# 고르는" 상황이고 그 기준은 호스트 메모리다. available(page cache 회수분 포함)을 쓰므로 캐시로 찬 메모리는
# 사용으로 세지 않는다. 값은 정수로 내림한다.
root_disk_used=$(df --output=pcent / 2>/dev/null | tail -1 | tr -dc '0-9')
mem_used=$(awk '/^MemTotal:/ {t=$2} /^MemAvailable:/ {a=$2} END {if (t > 0) printf "%d", (t - a) * 100 / t}' /proc/meminfo 2>/dev/null)
# 값을 못 읽었으면 올리지 않는다 - 0은 "여유 있다"라서 조회가 막힌 순간에 경보가 거꾸로 조용해진다
# (아래 임시파일 지표와 같은 판단). 결측은 경보의 notBreaching이 다룬다.
if [ -n "$root_disk_used" ]; then
  metric_data="$metric_data MetricName=RootDiskUsedPercent,Dimensions=[{Name=env,Value=$ACCENTURY_ENV}],Value=$root_disk_used,Unit=Percent"
fi
if [ -n "$mem_used" ]; then
  metric_data="$metric_data MetricName=MemoryUsedPercent,Dimensions=[{Name=env,Value=$ACCENTURY_ENV}],Value=$mem_used,Unit=Percent"
fi

# JSON에서 이름 하나의 정수를 뽑는다. 이 응답의 값은 전부 정수다 (tempstore.metrics()는
# 건수와 반올림한 초만 담는다) - jq를 깔지 않으려고 sed 하나로 끝낸다.
json_int() {
  sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p"
}

token=""
if [ "$value" = 1 ] && [ -r "$AI_ENV_FILE" ]; then
  # 값을 읽기만 한다 - source 하지 않는다. 이 파일은 docker의 env-file이지 셸 스크립트가 아니라
  # (accentury-up.sh가 KEY=VALUE로 쓴다) 따옴표 규칙이 없다. 지금 토큰은 영숫자 48자라
  # (config 모듈 random_password, special=false) source 해도 무사하지만, 나중에 이 파일에 공백이나
  # $가 든 값이 하나만 들어와도 셸이 그것을 해석한다.
  token=$(grep -m1 '^ACCENTURY_AI_INTERNAL_TOKEN=' "$AI_ENV_FILE" | cut -d= -f2-)
fi

# 토큰이 필요한 내부 조회 (health와 달리 인증 예외가 아니다).
ask() {
  curl -s --max-time 3 -H "X-Accentury-Internal-Token: $token" "http://127.0.0.1:8000$1" || true
}

temp_metrics=""
stage_samples=""
if [ -n "$token" ]; then
  temp_metrics=$(ask /internal/v0/metrics)
  # 추론 단계별 소요 시간 표본 (KAN-204). **읽으면 비워진다** - 아래 put-metric-data가 실패하면
  # 그 회차의 표본은 사라진다. 지표는 최선 노력이고, 재시도 큐를 두면 CloudWatch가 오래 막힌
  # 동안 추론 프로세스가 그 대가를 메모리로 치른다 (ai/app/stages.py).
  stage_samples=$(ask /internal/v0/metrics/stages)
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

# 단계별 소요 시간 (KAN-204). 한 줄이 계열 하나이고 모양은 `<단계> <콜드|웜> <ms,ms,...>`다.
# 관측값을 낱개로 올린다(Value가 아니라 Values) - 평균이나 합만 올리면 CloudWatch가 p50과 p95를
# 계산할 수 없고, 그 백분위가 이 티켓이 필요로 하는 값이다. 앱이 표본을 150개에서 끊으므로
# (Values 하나의 API 상한) 여기서 다시 세지 않는다.
stage_series=0
while read -r stage warm values; do
  [ -n "$values" ] || continue
  # 앱이 만든 값이지만 그대로 명령줄에 들어가므로 모양을 확인한다 - 지표 하나 때문에 이
  # 스크립트가 임의의 문자열을 실행하는 경로가 생기지 않게 한다. 단계 이름에 숫자를 허용하는
  # 것이 중요하다 - f0이 그 이름이라, 글자만 받으면 F0 추출 표본이 통째로 버려진다.
  case "$stage" in "" | *[!A-Za-z0-9]*) continue ;; esac
  case "$warm" in warm | cold) ;; *) continue ;; esac
  case "$values" in *[!0-9,]*) continue ;; esac
  metric_data="$metric_data MetricName=StageDuration,Dimensions=[{Name=env,Value=$ACCENTURY_ENV},{Name=stage,Value=$stage},{Name=warm,Value=$warm}],Values=[$values],Unit=Milliseconds"
  stage_series=$((stage_series + 1))
done <<EOF
$stage_samples
EOF

# 지표를 못 올려도(자격 증명 전파 전, API 일시 장애) 타이머의 다음 회차가 다시 시도한다.
# shellcheck disable=SC2086 -- metric_data는 우리가 만든 공백 구분 목록이라 분리되어야 한다.
aws cloudwatch put-metric-data \
  --region "$AWS_REGION" \
  --namespace "$METRIC_NAMESPACE" \
  --metric-data $metric_data \
  || echo "CloudWatch put-metric-data 실패 (health=$code) - 다음 회차에 재시도" >&2

echo "ai health=$code -> Healthy=$value, rootDisk=${root_disk_used:-미확인}%, mem=${mem_used:-미확인}%, tempFiles=${temp_files:-미확인}, tempOldestAge=${temp_oldest_age:-미확인}, tempScanFailures=${temp_scan_failures:-미확인}, stageSeries=$stage_series ($METRIC_NAMESPACE env=$ACCENTURY_ENV)"
