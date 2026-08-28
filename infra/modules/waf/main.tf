# CloudFront 앞단 WAF 웹 ACL (KAN-149, 2026-08-19 멘토링 지시).
#
# 이 모듈은 반드시 us-east-1 프로바이더로 호출한다 (envs가 `providers = { aws = aws.us_east_1 }`로
# 넘긴다). CLOUDFRONT 스코프 웹 ACL은 us-east-1에만 만들 수 있고, 서울 리전에 만들면 CloudFront
# 배포의 WAF 선택 목록에 나타나지도 않고 오류도 없다 (ACM 인증서와 같은 함정). 로그 그룹도 같은
# 리전이어야 WAF가 쓸 수 있다.
#
# 규칙은 셋이다. 우선순위 숫자가 작을수록 먼저 평가된다.
#   10  rate-limit-costly-posts   IP당 5분 창 요청 수 제한. 세션 생성과 음성 업로드만 센다.
#   20  aws-common                AWSManagedRulesCommonRuleSet. 음성 업로드 경로는 검사에서 뺀다.
#   30  aws-known-bad-inputs      AWSManagedRulesKnownBadInputsRuleSet (Log4j, Java 역직렬화 등).
#
# 전부 var.enforce 하나로 Count(기록만) / Block(차단)을 오간다. 티켓 요구사항대로 Count로 시작해
# 로그를 며칠 관찰한 뒤 tfvars의 waf_enforce만 true로 바꿔 apply한다. 코드 변경 없이 전환된다.

locals {
  name = "accentury-${var.env}"

  # 세션 생성과 음성 업로드 경로. UploadClient.kt의 PATH_* 상수와 SessionClient.kt가 만드는 경로다.
  #   POST /v0/sessions                                             (KAN-9, 재응시 KAN-107)
  #   POST /v0/sessions/{sessionId}/voice-items/{itemId}/recording  (KAN-10, multipart 음성)
  # rate 집계는 POST만 센다. GET, HEAD, OPTIONS는 backend가 405로 싸게 끝내는데 집계에 들어가면
  # 같은 공유 IP의 정상 세션 생성과 업로드까지 밀어낸다 (Codex 2차 P2, 2026-08-28). CommonRuleSet
  # 예외는 메서드를 보지 않는다 - 업로드 경로로 오는 요청은 메서드와 무관하게 본문 규칙에 걸릴
  # 이유가 없고, 조건을 좁힐수록 예외가 빠지는 경우만 는다.
  #
  # 경로는 backend의 MVC 라우팅과 같은 정규화로 비교해야 한다. backend의 UploadRateLimitFilter가
  # 같은 이유로 raw URI 정규식을 버리고 파싱된 경로로 매칭한다 (%72ecording, ;x=1 변형이
  # 컨트롤러에는 닿으면서 제한만 비껴간다). WAF에는 파싱된 경로가 없으므로 세 가지로 맞춘다.
  #   - URL_DECODE: %73essions -> sessions (Tomcat이 세그먼트를 디코드해 라우팅한다)
  #   - NORMALIZE_PATH: /v0/./sessions -> /v0/sessions
  #   - 정규식의 (;[^/]*)?: Spring PathPatternParser가 세그먼트별 matrix 파라미터(;x=1)를 떼고
  #     매칭하므로 각 세그먼트 뒤에 그것을 허용한다
  # (Codex 1차 P1, 3차 P2, 2026-08-28)
  session_create_path_regex = "^/v0(;[^/]*)?/sessions(;[^/]*)?$"
  recording_path_regex      = "/recording(;[^/]*)?$"

  # 차단 응답 본문. backend GlobalExceptionHandler가 내는 429 봉투(ErrorCode.RATE_LIMITED)와 같은
  # 모양이라 앱(UploadClient.toResult, SessionClient.toResult)과 웹(errorEnvelope.ts)이 backend
  # 429와 똑같이 "잠시 후 재시도"로 처리한다. 봉투 없는 403이 나가면 앱은 retryable=false로
  # 판정해 재시도 버튼을 지운다 (UploadStatusBar.kt) - 그래서 기본 403 응답을 쓰지 않는다.
  # 대기 시간은 평가 창(300초)과 같게 둔다. 한 번 넘긴 IP는 그 요청들이 5분 창에서 빠질 때까지
  # 차단이 이어지므로, 60초라고 알리면 앱이 60초 뒤 재시도해 다시 429를 받는다 (Codex 1차 P2).
  rate_limited_retry_after_seconds = local.rate_limit_window_seconds
  rate_limit_window_seconds        = 300
  rate_limited_body = jsonencode({
    code         = "RATE_LIMITED"
    message      = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
    retryable    = true
    retryAfterMs = local.rate_limited_retry_after_seconds * 1000
  })
}

resource "aws_wafv2_web_acl" "this" {
  name = "${local.name}-cloudfront"
  # WAF description은 괄호를 허용하지 않는다 (허용 문자: 영숫자, 공백, +=:#@/-,. - Codex 4차 P1).
  description = "accentury ${var.env} CloudFront web ACL, KAN-149"
  scope       = "CLOUDFRONT"

  default_action {
    allow {}
  }

  custom_response_body {
    key          = "rate-limited"
    content      = local.rate_limited_body
    content_type = "APPLICATION_JSON"
  }

  # ---- 10. rate-based rule ----
  #
  # 세는 대상을 세션 생성과 음성 업로드로 좁힌 이유: 웹이 분석 대기 화면에서 /complete를
  # pollAfterMs(800ms)마다 POST로 폴링한다 (useAnalysisPolling.ts). POST /v0/* 전체를 세면 정상
  # 사용자 1명이 5분에 POST 375건을 내 임계값 산정이 불가능하다. 반면 세션 생성과 업로드는
  # 실모델이 붙는 순간 요청 1건이 GPU 비용이 되는 유일한 경로다.
  #
  # 임계값(var.rate_limit)은 backend의 IP당 제한(세션 생성, 업로드 각각 분당 30 = 5분 150,
  # application.yml)보다 위에 둔다. 보통의 초과는 backend가 먼저 잡아 정식 429 봉투를 내고,
  # WAF는 backend가 multipart를 받느라 지치기 전에 홍수만 엣지에서 자른다. 공유 Wi-Fi(학교,
  # 시연장)는 IP가 하나로 잡히므로 "동시 응시자 x 업로드 10건"이 임계값 안에 들어야 한다.
  rule {
    name     = "rate-limit-costly-posts"
    priority = 10

    action {
      dynamic "block" {
        for_each = var.enforce ? [1] : []
        content {
          custom_response {
            response_code            = 429
            custom_response_body_key = "rate-limited"

            response_header {
              name  = "Retry-After"
              value = tostring(local.rate_limited_retry_after_seconds)
            }
          }
        }
      }

      dynamic "count" {
        for_each = var.enforce ? [] : [1]
        content {}
      }
    }

    statement {
      rate_based_statement {
        limit                 = var.rate_limit
        evaluation_window_sec = local.rate_limit_window_seconds
        aggregate_key_type    = "IP"

        scope_down_statement {
          and_statement {
            statement {
              byte_match_statement {
                search_string         = "POST"
                positional_constraint = "EXACTLY"

                field_to_match {
                  method {}
                }

                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }

            statement {
              or_statement {
                statement {
                  regex_match_statement {
                    regex_string = local.session_create_path_regex

                    field_to_match {
                      uri_path {}
                    }

                    text_transformation {
                      priority = 0
                      type     = "URL_DECODE"
                    }

                    text_transformation {
                      priority = 1
                      type     = "NORMALIZE_PATH"
                    }
                  }
                }

                statement {
                  regex_match_statement {
                    regex_string = local.recording_path_regex

                    field_to_match {
                      uri_path {}
                    }

                    text_transformation {
                      priority = 0
                      type     = "URL_DECODE"
                    }

                    text_transformation {
                      priority = 1
                      type     = "NORMALIZE_PATH"
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-rate-limit"
      sampled_requests_enabled   = false
    }
  }

  # ---- 20. AWSManagedRulesCommonRuleSet ----
  #
  # 이 그룹의 SizeRestrictions_BODY는 본문 8KB 초과를 차단한다. 음성 업로드는 multipart 수백 KB
  # 에서 수 MB라 전부 걸린다. 같은 이유로 CrossSiteScripting_BODY, GenericLFI_BODY, GenericRFI_BODY,
  # EC2MetaDataSSRF_BODY도 바이너리 오디오를 오탐할 수 있다. 화면은 멀쩡한데 녹음만 실패하는
  # 형태라 원인을 찾기 어렵다 (티켓 본문). SQLi 규칙은 이 그룹에 없다 - 별도 그룹
  # AWSManagedRulesSQLiRuleSet이고 이 웹 ACL은 그것을 넣지 않았다 (2026-08-28 AWS 문서 확인).
  #
  # 대응은 스코프 다운이다: 업로드 경로(…/recording)는 이 그룹 전체를 건너뛴다. 대안인
  # rule_action_override(본문 규칙 5개를 Count로)는 모든 경로에서 본문 검사가 꺼진다 - 어휘 답안,
  # 세션 생성 같은 JSON 본문 경로의 XSS/LFI/RFI 검사는 남기고 싶어 스코프 다운을 골랐다
  # (2026-08-28, KAN-149 코멘트). 대가는 업로드 경로가 이 그룹의 다른 검사(경로 조작, 나쁜 UA 등)도 함께
  # 빠지는 것인데, 그 경로는 rate-based rule과 backend 검증(멱등 키, meta 필수, 크기 상한)이 받친다.
  rule {
    name     = "aws-common"
    priority = 20

    override_action {
      dynamic "none" {
        for_each = var.enforce ? [1] : []
        content {}
      }

      dynamic "count" {
        for_each = var.enforce ? [] : [1]
        content {}
      }
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"

        scope_down_statement {
          not_statement {
            statement {
              regex_match_statement {
                regex_string = local.recording_path_regex

                field_to_match {
                  uri_path {}
                }

                text_transformation {
                  priority = 0
                  type     = "URL_DECODE"
                }

                text_transformation {
                  priority = 1
                  type     = "NORMALIZE_PATH"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-aws-common"
      sampled_requests_enabled   = false
    }
  }

  # ---- 30. AWSManagedRulesKnownBadInputsRuleSet ----
  # Log4j JNDI, Java 역직렬화 페이로드, 잘못된 Host 헤더 같은 알려진 악성 입력. backend가 Java라
  # 이 그룹은 실제로 유효하다. 본문 크기 규칙이 없어 업로드 경로 예외가 필요 없다.
  rule {
    name     = "aws-known-bad-inputs"
    priority = 30

    override_action {
      dynamic "none" {
        for_each = var.enforce ? [1] : []
        content {}
      }

      dynamic "count" {
        for_each = var.enforce ? [] : [1]
        content {}
      }
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-aws-known-bad-inputs"
      sampled_requests_enabled   = false
    }
  }

  # 샘플 요청 저장은 끈다. 샘플에는 요청 헤더가 그대로 담기는데, 아래 로깅의 redacted_fields가
  # 샘플까지 가린다는 보장을 AWS 문서에서 확인하지 못했다. 세션 Bearer 토큰이 콘솔에 노출될
  # 여지를 남기지 않는다. 오탐 판정은 로그로 충분하다.
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-cloudfront"
    sampled_requests_enabled   = false
  }
}

# ---- 로깅 ----
#
# 차단(또는 Count 매치)된 요청을 볼 수 없으면 오탐 판정이 불가능하다 (티켓 요구사항). CloudWatch
# Logs 로그 그룹 이름은 aws-waf-logs- 접두사가 필수다. WAF가 계정 공용 리소스 정책(AWSWAF-LOGS)을
# 스스로 만들어 쓴다 - 웹 ACL이 환경당 1개라 정책 크기 상한과는 거리가 멀다.

resource "aws_cloudwatch_log_group" "waf" {
  name              = "aws-waf-logs-${local.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_wafv2_web_acl_logging_configuration" "this" {
  resource_arn            = aws_wafv2_web_acl.this.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]

  # WAF 로그는 요청 헤더를 통째로 남긴다. 세션 토큰(Authorization: Bearer)이 평문으로 쌓이지
  # 않게 가린다. X-Admin-Token(관리자 API, 명세서 §6)도 같은 이유로 가린다.
  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  redacted_fields {
    single_header {
      name = "x-admin-token"
    }
  }

  # 기본 Allow까지 전부 남기면 정적 자산 요청이 대부분이라 비용만 든다. 규칙에 매치된 요청
  # (Count 관찰 단계의 COUNT, 전환 뒤의 BLOCK)만 남긴다.
  logging_filter {
    default_behavior = "DROP"

    filter {
      behavior    = "KEEP"
      requirement = "MEETS_ANY"

      condition {
        action_condition {
          action = "COUNT"
        }
      }

      condition {
        action_condition {
          action = "BLOCK"
        }
      }
    }
  }
}
