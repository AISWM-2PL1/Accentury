/**
 * 계측 이벤트 스키마 — **앱·웹 공통 정본** (KAN-33).
 *
 * 이 파일이 이벤트명과 파라미터의 유일한 목록이다. 안드로이드(`analytics/AppEvents.kt`)와
 * iOS도 같은 이름·같은 파라미터를 쓰고, 세 곳이 갈리면 GA4에서 같은 사건이 이름이 다른 두
 * 지표로 쌓인다 (KAN-33 AC "앱과 웹의 같은 이벤트가 동일 스키마로 집계된다").
 *
 * 이름·파라미터를 snake_case로 두는 이유는 GA4 규약이다 — 이벤트명 40자, 파라미터 25개,
 * 파라미터명 40자가 상한이고 대소문자를 구분한다. 값도 마찬가지로 snake_case를 쓴다
 * ([channelParam]과 같은 판단이다: 집계 축의 이름은 코드 사정으로 바뀌면 안 된다).
 *
 * ## 무엇을 싣지 않는가
 *
 * 세션 id·세션 토큰·문항 내용·원본 음성·점수 원값은 파라미터에 없다 (AC 2항). 개인을 특정할
 * 수 있는 값이 하나라도 섞이면 "익명 계측"이라는 전제가 깨지고, 그 값들이 계측 서버에 남을
 * 이유도 없다.
 *
 * 두 값만 설명이 필요하다.
 * - `campaign`은 공유 링크가 실어 온 **공용 상수**(`kko_share` 같은 값)라 사람을 가리키지 않고,
 *   세션 생성과 같은 규칙으로 걸러진 값만 싣는다 (`session/campaign.ts`).
 * - `tier_code`·`overall_bucket`은 [tier_assigned]에만 있다. 등급 코드는 5개뿐인 집계 축이고
 *   종합 점수는 [overallBucket]이 10점 단위로 뭉개 개인을 특정할 수 없는 값으로 바꾼다
 *   (FR-AN-09 익명 집계 카운터 범위). 이 둘이 없으면 KAN-21의 "등급 분포 편향" 트리거를
 *   판단할 계기판이 아예 없다 — 트리거만 있고 측정값이 없는 상태가 지금이다.
 *
 * ## 앱인지 웹인지는 파라미터로 싣지 않는다
 *
 * Firebase가 데이터 스트림으로 나눠 준다 — 같은 GA4 속성 아래 Android·iOS 앱 스트림과 웹
 * 스트림이 따로 있어 `플랫폼` 축은 자동으로 붙는다. 파라미터로 한 번 더 실으면 두 축이
 * 어긋날 여지만 생긴다.
 */

import type { StorePlatform } from '../audio/storeLink'
import type { ItemType } from '../progress/testDefinition'
import type { ShareChannel } from '../share/shareResult'

/**
 * 재녹음이 일어난 이유 (KAN-28 임계치 튜닝 근거).
 *
 * 셋을 나누는 것이 요점이다. `QUALITY`는 서버가 품질을 이유로 되돌려보낸 것, `FAILED`는 분석
 * 자체가 실패한 것, `USER`는 아무 문제 없이 사용자가 다시 하기로 한 것이다 — 뭉뚱그리면
 * "재녹음이 많다"까지만 알 수 있고, 품질 임계치를 낮춰야 하는지 GPU를 늘려야 하는지는
 * 갈리지 않는다.
 */
export type RetakeReason = 'QUALITY' | 'FAILED' | 'USER'

/**
 * 문항 분석의 최종 상태 (`AnalysisItemStatus`의 종결 상태 셋).
 *
 * `NOT_SUBMITTED`·`PROCESSING`은 여기 없다 — 지나가는 상태라 "최종 분포"에 세면 폴링 회차가
 * 많은 세션일수록 표본이 부풀어 오른다.
 */
export type TerminalItemStatus = 'COMPLETED' | 'RETRYABLE_FAILED' | 'FAILED'

/**
 * 보내는 이벤트 전부.
 *
 * 유니온으로 두는 이유는 KAN-31 때와 같다: 이벤트마다 실을 수 있는 값이 다르다
 * (`platform`은 다운로드 탭에만, `item_seq`는 문항 이벤트에만 있다). 하나의 넓은 타입으로
 * 두면 "이 이벤트에 이 값이 왜 있지"를 컴파일러가 못 잡는다.
 *
 * 크게 둘로 나뉜다 — **퍼널**(사람이 어디서 빠지는가)과 **운영 지표**(KAN-24·KAN-21 재검토
 * 트리거의 계기판). 둘을 한 유니온에 두는 것은 전송 경로가 같기 때문이다 (`track.ts`).
 */
export type AnalyticsEvent =
  // ── 퍼널 ────────────────────────────────────────────────────────────────
  /**
   * 인트로 화면을 열었다. 공유 링크를 타고 온 경우 `campaign`이 붙는다.
   *
   * 앱 안에서도 같은 이름으로 센다 — 인트로를 그리는 것이 앱에서도 이 WebView라 사건이 같다.
   * 앱의 정상 실행은 `campaign`이 null이고, 앱 링크(KAN-32)로 들어온 실행만 값이 붙는다.
   */
  | { name: 'referral_opened'; campaign: string | null }
  /** 세션이 실제로 만들어졌다 = 응시를 시작했다 */
  | { name: 'referral_test_started'; campaign: string | null }
  /** 문항 하나가 화면에 떴다. `item_seq`는 사람이 읽는 1-기반 순번이다 */
  | { name: 'item_shown'; item_seq: number; item_type: ItemType }
  /** 그 문항의 답(녹음 업로드 또는 어휘 선택)을 제출했다 */
  | { name: 'item_submitted'; item_seq: number; item_type: ItemType }
  /** 같은 문항을 다시 녹음했다 ([RetakeReason] 참고) */
  | { name: 'recording_retake'; item_seq: number; reason: RetakeReason }
  /** 마지막 문항까지 끝나 결과가 확정됐다 */
  | { name: 'test_completed'; campaign: string | null }
  /** 결과 화면을 실제로 봤다 (분석 대기를 통과한 자리) */
  | { name: 'result_viewed'; campaign: string | null }
  /** 결과 화면의 [친구에게 공유하기]를 눌렀다 (FR-SH-06) */
  | { name: 'share_clicked'; campaign: string | null; channel: ShareChannel }
  /** 결과 화면의 [앱 다운로드]를 눌렀다 (웹 단독 실행에만 있는 지점) */
  | { name: 'app_download_clicked'; campaign: string | null; platform: StorePlatform }
  /** 결과 화면의 [다시 테스트하기]를 눌렀다 */
  | { name: 'retest_started' }
  // ── 운영 지표 (재검토 트리거의 계기판) ──────────────────────────────────
  /**
   * 대기 화면 진입 → 결과 화면 전환까지 걸린 시간.
   *
   * **KAN-24 트리거 "대기 화면 평균 체류 10초 초과"의 측정값이다.** 이 값이 없으면 그 트리거는
   * 정의만 있고 판단할 근거가 없다.
   */
  | { name: 'analysis_wait_duration'; duration_ms: number; pending_item_count: number }
  /**
   * 세션당 폴링 횟수. **폴링 규칙(KAN-14)이 지켜지는지 검증하는 값이다** — 문항 진행 중에도
   * 폴링하는 코드가 생기면 이 값이 수십 배로 튄다.
   */
  | { name: 'analysis_poll_count'; count: number; total_elapsed_ms: number }
  /** 60초 상한에 걸려 자동 폴링을 접었다. GPU 밀림의 조기 신호다 */
  | { name: 'poll_abandoned'; elapsed_ms: number; pending_item_count: number }
  /** 문항 하나가 종결 상태에 도달했다. 어떤 품질 오류가 많은지의 분포가 된다 */
  | { name: 'analysis_item_terminal'; status: TerminalItemStatus; error_code: string | null }
  /**
   * 등급이 확정됐다. **KAN-21 "등급 분포 편향" 추적의 계기판이다** — 한 등급이 40%를 넘으면
   * 점수 경계값 재보정에 착수한다.
   */
  | {
      name: 'tier_assigned'
      tier_code: string
      score_version: string
      /** 종합 점수를 10점 단위로 묶은 값 ([overallBucket]) */
      overall_bucket: number
    }

/**
 * 종합 점수를 10점 단위 버킷으로 묶는다 (0, 10, ..., 100).
 *
 * 원값을 그대로 보내지 않는 이유는 익명성이다. 0~100 정수를 등급 코드·시각과 함께 보내면
 * 한 사람의 결과를 되짚을 수 있는 조합이 생기는데, 우리가 알아야 하는 것은 "등급 경계 근처에
 * 얼마나 몰려 있는가"라 10점 눈금이면 충분하다 (FR-AN-09).
 *
 * 100은 100 버킷에 그대로 둔다 — 내림만 하면 100 하나짜리 버킷이 생기는 것이 맞다.
 * 범위 밖 값은 0~100으로 자른다: 계측값 하나 때문에 집계 축에 -10이나 110이 생기면 그
 * 대시보드는 사람이 다시 읽어야 한다.
 */
export function overallBucket(overall: number): number {
  if (!Number.isFinite(overall)) return 0
  const clamped = Math.min(100, Math.max(0, overall))
  return Math.floor(clamped / 10) * 10
}
