/**
 * 최종 결과 조회 (KAN-29 Stage 1) — `GET {apiBase}/v0/sessions/{sessionId}/result`
 * (KAN-25, API 명세서 §3.7).
 *
 * ## 분기는 HTTP 상태가 아니라 봉투의 `code`로 한다
 *
 * 이 엔드포인트는 한 상태 코드에 뜻이 둘인 경우가 있다 — 409가 "아직 분석 중"
 * (`RESULT_NOT_READY`)일 수도, "실패한 문항이 있어 재녹음 필요"(`RESULT_RETAKE_REQUIRED`)일
 * 수도 있다. 상태 코드로 갈라 놓으면 두 경우에 같은 문구가 나간다. 그래서 [submitVocabAnswer]와
 * 같이 봉투(§2.3)의 `code`·`message`·`retryable`을 그대로 실어 올리고, 화면이 그 값으로 판단한다.
 *
 * ## 이 티켓이 읽지 않는 것
 *
 * 봉투의 `pendingItems`·`retakeItems`·`missingItems`와 429의 `retryAfterMs`는 읽지 않는다.
 * 전부 복구 경로를 그리는 값이고, 복구 UX는 분석 대기 화면(KAN-14)과 다시 테스트(KAN-34)의
 * 몫이다. 지금 여기서 같이 읽어 두면 그 티켓들이 "이미 있는 값"에 맞춰 화면을 짜게 되는데,
 * 그 값들이 결과 화면 경로에서는 실제로 검증된 적이 없다.
 *
 * ## 재시도 로직을 넣지 않는 이유
 *
 * [fetchTestDefinition]과 같다 — 간격과 횟수는 폴링 상한(KAN-14)과 한자리에서 정해져야 하는
 * 값이라, 여기서 임의로 정하면 나중에 두 곳이 어긋난다. 실패는 그대로 올려서 화면이
 * [다시 시도]를 띄우게 한다.
 */

import type { FetchLike } from '../progress/fetchTestDefinition'
import type { TestResultView } from './testResult'

const browserFetch: FetchLike = (input, init) => globalThis.fetch(input, init)

/**
 * 보관 기간(24시간)이 지난 결과의 코드 (410, §3.7).
 *
 * 상수로 빼 두는 이유: 화면이 이 한 코드에서만 다른 길로 간다 — 다른 실패는 [다시 시도]지만
 * 만료는 재시도해도 영영 같은 응답이라 [다시 테스트하기]로 보내야 한다 (KAN-29 요구).
 * 화면 코드에 문자열을 직접 적으면 오타가 조용히 "그냥 실패" 분기로 흘러간다.
 */
export const RESULT_EXPIRED = 'RESULT_EXPIRED'

/** 조회에 필요한 전부. 출처 결정(토큰은 브리지, sessionId는 진입 쿼리)은 호출자 몫이다 */
export interface ResultQuery {
  apiBase: string
  sessionId: string
  /** 세션 토큰 (Bearer 없이 값만) */
  sessionToken: string
}

/** 봉투의 code·retryable을 실은 조회 실패. code는 봉투를 못 읽었으면 null이다 */
export class ResultFetchError extends Error {
  readonly code: string | null
  readonly retryable: boolean

  constructor(message: string, code: string | null, retryable: boolean) {
    super(message)
    this.name = 'ResultFetchError'
    this.code = code
    this.retryable = retryable
  }

  /** 보관 기간 만료 — 재시도가 아니라 다시 테스트로 보내야 하는 유일한 실패다 */
  get expired(): boolean {
    return this.code === RESULT_EXPIRED
  }
}

/**
 * 확정된 최종 결과를 받아온다.
 *
 * @throws ResultFetchError 네트워크 실패 / 봉투가 말한 오류 / 봉투를 못 읽은 HTTP 오류 /
 *   200인데 본문이 계약과 다른 경우
 */
export async function fetchResult(
  query: ResultQuery,
  fetchImpl: FetchLike = browserFetch,
): Promise<TestResultView> {
  const { apiBase, sessionId, sessionToken } = query

  // 빈 값이면 `/v0/sessions//result`나 토큰 없는 401로 나가, 원인이 값 누락이었다는 걸 화면에서
  // 알 수 없게 된다. 네트워크를 타기 전에 끊는다 (submitVocabAnswer의 같은 가드와 한 규칙).
  //
  // 사용자 문구와 개발자 진단을 나눈다 — 이건 앱이 값을 못 넘긴 상황이라 사용자가 할 수 있는 게
  // 없고, 필드 이름을 화면에 띄워 봐야 비난 없는 카피 톤(ux-ui.md)만 깨진다.
  for (const [name, value] of Object.entries({ sessionId, sessionToken })) {
    if (value.trim() === '') {
      console.error(`[result] 결과 조회에 필요한 값이 비어 있습니다: ${name}`)
      throw new ResultFetchError(
        '결과를 불러올 수 없어요. 앱을 다시 시작해 주세요',
        `CLIENT_MISSING_${name}`,
        false,
      )
    }
  }

  const url = `${apiBase.replace(/\/+$/, '')}/v0/sessions/${encodeURIComponent(sessionId)}/result`

  let response: Response
  try {
    response = await fetchImpl(url, {
      headers: { Accept: 'application/json', Authorization: `Bearer ${sessionToken}` },
    })
  } catch {
    // 조회는 부작용이 없는 GET이라 재시도가 언제나 무해하다.
    throw new ResultFetchError('네트워크 오류로 결과를 불러오지 못했습니다', null, true)
  }

  if (!response.ok) {
    const envelope = await readErrorEnvelope(response)
    if (envelope !== null) {
      throw new ResultFetchError(envelope.message, envelope.code, envelope.retryable)
    }
    throw new ResultFetchError(`결과를 불러오지 못했습니다 (HTTP ${response.status})`, null, true)
  }

  let parsed: unknown
  try {
    parsed = await response.json()
  } catch {
    throw new ResultFetchError('결과 응답을 해석할 수 없습니다 (JSON 아님)', null, true)
  }

  if (!isResultShape(parsed)) {
    // 형태가 다르면 화면이 점수 자리에 undefined를 그리게 된다. 점수는 이 화면의 존재 이유라
    // 일부만 그리는 것보다 실패로 끊는 편이 낫다.
    throw new ResultFetchError('결과 응답의 형태가 계약과 다릅니다', null, false)
  }
  return parsed
}

/**
 * **소비하는 필드는 전부 본다.** 검증의 경계는 "화면이나 동작이 이 값을 읽는가"다.
 *
 * 처음에는 점수와 등급만 봤는데, 그건 "없어도 화면이 죽지 않는다"는 전제 위에 있었고 그
 * 전제가 곧 깨졌다 — 공유 결선이 `result.share`를 구조 분해하면서, `share` 없는 200 응답이
 * 오면 공유 버튼을 누르는 순간 TypeError가 나는 상태가 됐다 (Codex 교차 검증, 2026-08-22).
 * 버전 꼬리표도 검증 없이 그려서 `undefined · undefined`가 화면에 나갈 수 있었다.
 *
 * `share.imageUrl`이 그 절차를 그대로 밟은 예다. KAN-29까지는 아무도 읽지 않아 빼 뒀는데,
 * 공유 카드 결선(KAN-30)이 브리지 payload에 그 값을 실으면서 소비 필드가 됐다 — 빠지면
 * 네이티브가 이미지 없는 카드를 그리거나 URL 자리에 `undefined`를 문자열로 받는다.
 *
 * 반대로 아무도 읽지 않는 필드는 계속 보지 않는다 — `status`(READY 하나뿐이라 분기가 없다),
 * `expiresAt`(만료 판정은 서버의 410이지 클라이언트의 시계가 아니다).
 *
 * 서버가 이 필드들을 항상 채운다는 것은 안다(TierAssets가 기동 시 강제한다). 여기서 막는
 * 것은 서버 버그가 아니라 배포 스큐다 — 계약이 바뀐 서버에 옛 웹이 붙는 경우가 실제 경로다.
 */
function isResultShape(value: unknown): value is TestResultView {
  if (typeof value !== 'object' || value === null) return false
  const { scores, tier, comment, share, testVersion, scoreVersion } = value as Record<string, unknown>

  if (!isNumberRecord(scores, ['intonation', 'vocabulary', 'overall'])) return false
  // rank·of는 "5개 등급 중 4번째" 표기가 읽는다. 빠지면 그 자리에 undefined가 나간다.
  if (!isStringRecord(tier, ['code', 'name']) || !isNumberRecord(tier, ['rank', 'of'])) return false
  // 셋 다 공유 결선(`share/shareResult`)이 브리지 payload로 옮겨 담는다 (KAN-30).
  if (!isStringRecord(share, ['imageUrl', 'text', 'webTestUrl'])) return false

  return typeof comment === 'string' && typeof testVersion === 'string' && typeof scoreVersion === 'string'
}

/** 객체인지 + 지정한 키가 전부 유한한 숫자인지 */
function isNumberRecord(value: unknown, keys: readonly string[]): boolean {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>
  return keys.every((key) => typeof record[key] === 'number' && Number.isFinite(record[key]))
}

/** 객체인지 + 지정한 키가 전부 문자열인지 */
function isStringRecord(value: unknown, keys: readonly string[]): boolean {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>
  return keys.every((key) => typeof record[key] === 'string')
}

/** 오류 봉투(§2.3)의 클라이언트 관심 부분 */
interface ErrorEnvelope {
  code: string
  message: string
  retryable: boolean
}

/** 본문을 봉투로 읽어 본다. JSON이 아니거나 봉투 모양이 아니면 null — 상태 코드 폴백으로 간다 */
async function readErrorEnvelope(response: Response): Promise<ErrorEnvelope | null> {
  let parsed: unknown
  try {
    parsed = await response.json()
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const { code, message, retryable } = parsed as Record<string, unknown>
  if (typeof code !== 'string' || typeof message !== 'string' || typeof retryable !== 'boolean') return null
  return { code, message, retryable }
}
