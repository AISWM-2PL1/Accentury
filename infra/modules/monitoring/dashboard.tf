# 운영 대시보드 1개 (KAN-38).
#
# "화면은 대시보드 1개로 한정한다"가 티켓의 제약이다. 3인 프로토타입에서 화면이 여럿이면 어느
# 것도 습관적으로 보지 않게 되고, 무엇보다 KAN-24 재검토 트리거("/analyses가 전체 요청의 30%를
# 넘는가", "동시 응시자가 만 명대인가")는 <b>한 화면에서 함께 봐야</b> 판단이 되는 값들이다.
#
# 위젯 배치의 규칙은 "위에서 아래로 사용자에게서 멀어진다"이다.
#   1행 사용자가 겪는 것      - API 지연 P95, 오류율, 분석 지연 P95(NFR-PF-01)
#   2행 부하의 모양           - /analyses 비율, 동시 활성 세션, 폴링과 429
#   3행 파이프라인 내부       - 진행 중 건수와 회로, 타임아웃과 혼잡 발동, 임시파일 잔존
#
# 지표 이름은 backend의 ServiceMetrics가 정본이고, 여기 문자열은 그 사본이다. Micrometer의
# CloudWatch 레지스트리가 종류마다 접미사를 붙이므로 이름 뒤가 .value(게이지), .count(카운터),
# .percentile.value(백분위 게이지)로 갈린다 - 그 규칙도 ServiceMetrics javadoc에 있다.
#
# 백분위가 .percentile.value라는 <b>별도 게이지</b>인 것은 레지스트리가 Timer의 백분위를 내보내지
# 않기 때문이다. .avg에 CloudWatch의 p95 통계를 걸면 "분당 평균들의 p95"라 지연 P95가 아니다.
#
# 그 백분위는 <b>태스크별로 계산된 값</b>이고, 여기서 Maximum으로 접는다. 태스크가 여럿이면
# (KAN-168, 최대 3) 같은 env 차원으로 값이 여럿 올라오는데, 그것을 서비스 전체의 P95로 되돌릴
# 방법이 없다 - CloudWatch가 백분위를 계산하려면 관측값이 낱개로 올라와야 하는데 레지스트리는
# 분당 집계값 하나만 올린다. 그래서 이 그래프는 "가장 나쁜 태스크의 P95"다.
#
# 그 정의를 택한 이유는 NFR-PF-01 판단에 안전한 쪽이기 때문이다 - 평균을 쓰면 태스크 하나가
# 3초를 넘겨도 나머지에 희석되어 준수로 보인다. 대신 <b>읽을 때 주의</b>가 하나 있다: 막 뜬
# 태스크처럼 요청이 몇 건뿐인 태스크의 P95는 표본이 작아 크게 튄다. 그래서 API 지연 위젯에는
# 평균을, 분석 지연 위젯에는 avg와 max를 함께 그려 "한 태스크만 튄 것인가"를 가를 수 있게 한다.
# 이 한계와 읽는 법은 docs/wiki/observability.md에 적어 두었다.

data "aws_region" "current" {}

locals {
  backend_dimensions = ["env", var.env]

  # 백분위 게이지의 차원. phi는 Micrometer가 붙이는 태그이고 값의 철자는 소수점 표기 그대로다.
  percentile_dimensions = concat(local.backend_dimensions, ["phi", "0.95"])

  dashboard_widgets = [
    # ---- 1행: 사용자가 겪는 것 ----
    {
      type   = "metric"
      x      = 0
      y      = 0
      width  = 8
      height = 6
      properties = {
        title  = "API 지연 P95 (ms)"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Maximum"
        period = 60
        metrics = [
          concat([var.backend_metric_namespace, "accentury.http.requests.percentile.value"],
          local.percentile_dimensions, [{ label = "P95 (태스크 중 최댓값)" }]),
          concat([var.backend_metric_namespace, "accentury.http.requests.avg"],
          local.backend_dimensions, [{ label = "평균", stat = "Average" }]),
        ]
        yAxis = { left = { min = 0, label = "ms", showUnits = false } }
      }
    },
    {
      type   = "metric"
      x      = 8
      y      = 0
      width  = 8
      height = 6
      properties = {
        title  = "오류율 (%)"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Sum"
        period = 60
        metrics = [
          [{ expression = "100 * (client + server) / total", label = "전체 오류율", id = "rate" }],
          [{ expression = "100 * server / total", label = "5xx만", id = "serverRate" }],
          concat([var.backend_metric_namespace, "accentury.http.errors.count"],
          local.backend_dimensions, ["status", "4xx"], [{ id = "client", visible = false }]),
          concat([var.backend_metric_namespace, "accentury.http.errors.count"],
          local.backend_dimensions, ["status", "5xx"], [{ id = "server", visible = false }]),
          concat([var.backend_metric_namespace, "accentury.http.requests.count"],
          local.backend_dimensions, [{ id = "total", visible = false }]),
        ]
        yAxis = { left = { min = 0, label = "%", showUnits = false } }
      }
    },
    {
      type   = "metric"
      x      = 16
      y      = 0
      width  = 8
      height = 6
      properties = {
        title  = "분석 지연 P95 (ms) - NFR-PF-01 3초"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Maximum"
        period = 60
        metrics = [
          concat([var.backend_metric_namespace, "accentury.analysis.duration.percentile.value"],
          local.percentile_dimensions, [{ label = "P95 (태스크 중 최댓값)" }]),
          concat([var.backend_metric_namespace, "accentury.analysis.duration.avg"],
          local.backend_dimensions, [{ label = "평균", stat = "Average" }]),
          concat([var.backend_metric_namespace, "accentury.analysis.duration.max"],
          local.backend_dimensions, [{ label = "최댓값" }]),
        ]
        yAxis = { left = { min = 0, label = "ms", showUnits = false } }
        annotations = {
          horizontal = [{ label = "NFR-PF-01 3초", value = 3000 }]
        }
      }
    },

    # ---- 2행: 부하의 모양 (KAN-24 재검토 트리거) ----
    {
      type   = "metric"
      x      = 0
      y      = 6
      width  = 8
      height = 6
      properties = {
        title  = "/analyses 요청 비율 (%) - KAN-24 트리거 30%"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Sum"
        period = 60
        metrics = [
          [{ expression = "100 * analyses / total", label = "/analyses 비율", id = "ratio" }],
          concat([var.backend_metric_namespace, "accentury.http.polling.count"],
          local.backend_dimensions, ["endpoint", "analyses"], [{ id = "analyses", visible = false }]),
          concat([var.backend_metric_namespace, "accentury.http.requests.count"],
          local.backend_dimensions, [{ id = "total", visible = false }]),
        ]
        yAxis = { left = { min = 0, max = 100, label = "%", showUnits = false } }
        annotations = {
          horizontal = [{ label = "long polling 전환 검토 (KAN-24)", value = 30 }]
        }
      }
    },
    {
      type   = "metric"
      x      = 8
      y      = 6
      width  = 8
      height = 6
      properties = {
        title  = "동시 활성 세션 수 - KAN-24 트리거 만 명대"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Maximum"
        period = 60
        metrics = [
          concat([var.backend_metric_namespace, "accentury.sessions.active.value"],
          local.backend_dimensions, [{ label = "만료 전 세션 행" }]),
        ]
        yAxis = { left = { min = 0, showUnits = false } }
      }
    },
    {
      type   = "metric"
      x      = 16
      y      = 6
      width  = 8
      height = 6
      properties = {
        title  = "폴링 요청과 429 (분당 건수)"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Sum"
        period = 60
        metrics = [
          concat([var.backend_metric_namespace, "accentury.http.polling.count"],
          local.backend_dimensions, ["endpoint", "analyses"], [{ label = "폴링 /analyses" }]),
          concat([var.backend_metric_namespace, "accentury.http.polling.count"],
          local.backend_dimensions, ["endpoint", "complete"], [{ label = "폴링 /complete" }]),
          # 429는 축(IP인가 세션인가)으로 나눠 본다 - KAN-38 AC. 축이 다르면 올릴 임계치도 다르다.
          [{ expression = "SUM(SEARCH('{${var.backend_metric_namespace},axis,env,scope} MetricName=\"accentury.ratelimit.rejected.count\" axis=\"ip\" env=\"${var.env}\"', 'Sum', 60))", label = "429 (IP 축)", id = "rateLimitedIp" }],
          [{ expression = "SUM(SEARCH('{${var.backend_metric_namespace},axis,env,scope} MetricName=\"accentury.ratelimit.rejected.count\" axis=\"session\" env=\"${var.env}\"', 'Sum', 60))", label = "429 (세션 축)", id = "rateLimitedSession" }],
        ]
        yAxis = { left = { min = 0, showUnits = false } }
      }
    },

    # ---- 3행: 파이프라인 내부 ----
    {
      type   = "metric"
      x      = 0
      y      = 12
      width  = 8
      height = 6
      properties = {
        title  = "AI 진행 중 건수와 회로 상태"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Maximum"
        period = 60
        metrics = [
          concat([var.backend_metric_namespace, "accentury.analysis.processing.value"],
          local.backend_dimensions, [{ label = "전 인스턴스 진행 중" }]),
          concat([var.backend_metric_namespace, "accentury.analysis.inflight.value"],
          local.backend_dimensions, [{ label = "이 태스크가 붙든 건수" }]),
          concat([var.backend_metric_namespace, "accentury.ai.circuit.state.value"],
          local.backend_dimensions, [{ label = "회로 (0 닫힘 / 1 반열림 / 2 열림)", yAxis = "right" }]),
        ]
        yAxis = {
          left  = { min = 0, label = "건", showUnits = false }
          right = { min = 0, max = 2, label = "회로", showUnits = false }
        }
        annotations = {
          horizontal = [{ label = "경보 임계치", value = var.analysis_backlog_threshold }]
        }
      }
    },
    {
      type   = "metric"
      x      = 8
      y      = 12
      width  = 8
      height = 6
      properties = {
        title  = "분석 타임아웃과 혼잡 폴링 발동 비율"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Sum"
        period = 60
        metrics = [
          concat([var.backend_metric_namespace, "accentury.analysis.timeouts.count"],
          local.backend_dimensions, ["reason", "stuck"], [{ label = "타임아웃 (실행 잔류)" }]),
          concat([var.backend_metric_namespace, "accentury.analysis.timeouts.count"],
          local.backend_dimensions, ["reason", "lost"], [{ label = "타임아웃 (큐 유실)" }]),
          [{ expression = "100 * congested / (congested + normal)", label = "혼잡 pollAfterMs 발동 비율(%)", id = "congestionRate", yAxis = "right" }],
          concat([var.backend_metric_namespace, "accentury.analysis.poll.count"],
          local.backend_dimensions, ["congested", "true"], [{ id = "congested", visible = false }]),
          concat([var.backend_metric_namespace, "accentury.analysis.poll.count"],
          local.backend_dimensions, ["congested", "false"], [{ id = "normal", visible = false }]),
        ]
        yAxis = {
          left  = { min = 0, label = "건", showUnits = false }
          right = { min = 0, max = 100, label = "%", showUnits = false }
        }
      }
    },
    {
      type   = "metric"
      x      = 16
      y      = 12
      width  = 8
      height = 6
      properties = {
        title  = "임시 디렉터리 잔존 파일 (KAN-27 청소 잡)"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Maximum"
        period = 300
        metrics = [
          concat([var.ai_metric_namespace, "TempFiles"], local.backend_dimensions,
          [{ label = "AI 호스트" }]),
          concat([var.ai_metric_namespace, "TempOldestAge"], local.backend_dimensions,
          [{ label = "AI 최장 잔존(초)", yAxis = "right" }]),
          concat([var.ai_metric_namespace, "TempScanFailures"], local.backend_dimensions,
          [{ label = "AI 훑기 실패 누적" }]),
          concat([var.backend_metric_namespace, "accentury.upload.temp.files.value"],
          local.backend_dimensions, [{ label = "backend" }]),
          concat([var.backend_metric_namespace, "accentury.upload.temp.scan.failures.count"],
          local.backend_dimensions, [{ label = "backend 훑기 실패", stat = "Sum" }]),
        ]
        yAxis = {
          left  = { min = 0, label = "건", showUnits = false }
          right = { min = 0, label = "초", showUnits = false }
        }
        annotations = {
          horizontal = [{ label = "AI 잔존 경보 임계치", value = var.ai_temp_residue_threshold }]
        }
      }
    },
  ]
}

resource "aws_cloudwatch_dashboard" "ops" {
  dashboard_name = "${local.name}-ops"
  dashboard_body = jsonencode({ widgets = local.dashboard_widgets })
}
