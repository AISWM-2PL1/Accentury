#!/bin/bash
# ai health 프로브 -> CloudWatch 커스텀 지표 (KAN-36). systemd 타이머가 1분마다 부른다.
#
# AI 호스트는 ALB 뒤가 아니라 대상 그룹 health가 없다. 그래서 호스트가 스스로 /internal/v0/health를
# 찔러 Healthy 0|1을 올리고, monitoring 모듈의 ai-unhealthy 경보가 3분 연속 0(또는 결측)에 선다.
# 워밍업 중(503 STARTING)도 0이다 - backend도 그 상태를 살아 있는 것으로 보지 않는다.
# 토큰 없이 두드린다 - health는 인증 예외다 (ai/app/auth.py).
set -uo pipefail

# shellcheck source=/dev/null
. /etc/accentury/env.conf

code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8000/internal/v0/health || echo 000)
value=0
if [ "$code" = 200 ]; then
  value=1
fi

# 지표를 못 올려도(자격 증명 전파 전, API 일시 장애) 타이머의 다음 회차가 다시 시도한다.
aws cloudwatch put-metric-data \
  --region "$AWS_REGION" \
  --namespace "$METRIC_NAMESPACE" \
  --metric-data "MetricName=Healthy,Dimensions=[{Name=env,Value=$ACCENTURY_ENV}],Value=$value,Unit=Count" \
  || echo "CloudWatch put-metric-data 실패 (health=$code) - 다음 회차에 재시도" >&2

echo "ai health=$code -> Healthy=$value ($METRIC_NAMESPACE env=$ACCENTURY_ENV)"
