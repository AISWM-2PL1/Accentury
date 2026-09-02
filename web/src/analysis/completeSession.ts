/**
 * 테스트 완료 확정 (KAN-14 Stage 2) —
 * `POST {apiBase}/v0/sessions/{sessionId}/complete` (KAN-16, API 명세서 §3.6).
 *
 * ## 대기 화면이 두 엔드포인트를 다 부르는 이유
 *
 * `/analyses`는 **음성 문항이 어떻게 되고 있는지**를 준다 — 화면에 문항별 상태를 그리는 값이다.
 * `/complete`는 **이 테스트가 끝났는지**를 판정한다 — 어휘 5문항까지 포함한 완주 검증과 결과
 * 확정이 여기서 일어난다 (§5.6·§5.7). 둘은 답하는 질문이 다르므로 한쪽으로 다른 쪽을 대신할 수
 * 없다: `/analyses`의 음성 5개가 전부 COMPLETED여도 어휘가 비면 테스트는 끝난 게 아니고,
 * 반대로 `/complete`는 어느 문항이 왜 늦는지를 화면에 그릴 만큼 말해 주지 않는다.
 *
 * 폴링 회차마다 둘을 순서대로 부른다. 요구 7항("문항별 개별 폴링 금지")이 막는 것은 문항 수에
 * 비례해 늘어나는 요청이고, 이 둘은 문항 수와 무관한 상수 2회다.
 *
 * ## 점수는 여기에도 없다
 *
 * READY 응답은 `status` 하나뿐이다. 점수와 등급은 `/result`(KAN-25)가 공개하는 유일한
 * 곳이라, 대기 화면이 결과를 미리 보여줄 방법 자체가 없다 (AC "대기 화면에 점수가 노출되지
 * 않는다"를 서버 계약이 이미 보증한다).
 */

import { isRetryableStatus } from '../net/retryableStatus'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { newIdempotencyKey } from '../progress/submitVocabAnswer'
import { AnalysisApiError, readErrorEnvelope, readJson, retryAfterMsFromHeader } from './errorEnvelope'

const browserFetch: FetchLike = (input, init) => globalThis.fetch(input, init)

/**
 * 재녹음이 필요하다는 뜻의 코드 (409, §3.6).
 *
 * 상수로 빼는 이유는 `RESULT_EXPIRED`와 같다 — 화면이 이 한 코드에서만 다른 길로 간다.
 * 다른 실패는 폴링을 이어 가거나 [다시 시도]지만, 이 코드는 **폴링을 멈추고** 실패한 문항의
 * 재녹음을 띄워야 한다. 아무리 두드려도 사용자가 다시 녹음하기 전에는 상태가 바뀌지 않는다.
 */
export const RESULT_RETAKE_REQUIRED = 'RESULT_RETAKE_REQUIRED'

/** 아직 제출되지 않은 문항이 있다는 뜻의 코드 (422, §3.6) */
export const RESULT_INCOMPLETE = 'RESULT_INCOMPLETE'

/**
 * 완료 시도 결과.
 * - `READY`: 결과가 확정됐다 = 결과 화면으로 이동한다
 * - `PROCESSING`: 아직 분석 중이다 = 다음 폴링 회차로 간다
 */
export type CompleteResult =
  | { status: 'READY' }
  | { status: 'PROCESSING'; pendingItems: string[]; pollAfterMs: number | null }

export interface CompleteRequest {
  apiBase: string
  sessionId: string
  /** 세션 토큰 (Bearer 없이 값만) */
  sessionToken: string
  /**
   * 멱등 키. 완료는 자연 멱등이라(§3.6) 어떤 키로 재시도해도 결과가 중복 생성되지 않지만,
   * 키 자체는 계약상 필수다(§2.2 — 비용 발생 POST). 생략하면 [newIdempotencyKey]로 만든다.
   *
   * 화면은 대기 화면 한 번의 수명 동안 **같은 키를 재사용한다** — 회차마다 새 키를 뽑아도
   * 서버는 같은 답을 주지만, 그러면 서버 로그에서 "한 사용자의 완료 폴링 20회"가 서로 무관한
   * 요청 20건으로 보인다. 같은 키면 그게 한 줄기로 남는다.
   */
  idempotencyKey?: string
}

/**
 * 완주를 검증하고 결과 확정을 시도한다.
 *
 * @throws AnalysisApiError 네트워크 실패 / 봉투가 말한 오류(409 재녹음·422 미제출·429 제한
 *   포함) / 봉투를 못 읽은 HTTP 오류 / 200인데 본문이 계약과 다른 경우
 */
export async function completeSession(
  request: CompleteRequest,
  fetchImpl: FetchLike = browserFetch,
): Promise<CompleteResult> {
  const { apiBase, sessionId, sessionToken, idempotencyKey = newIdempotencyKey() } = request

  for (const [name, value] of Object.entries({ sessionId, sessionToken, idempotencyKey })) {
    if (value.trim() === '') {
      console.error(`[analysis] 완료 요청에 필요한 값이 비어 있습니다: ${name}`)
      throw new AnalysisApiError('테스트를 마칠 수 없어요. 앱을 다시 시작해 주세요', {
        code: `CLIENT_MISSING_${name}`,
        retryable: false,
      })
    }
  }

  const url = `${apiBase.replace(/\/+$/, '')}/v0/sessions/${encodeURIComponent(sessionId)}/complete`

  let response: Response
  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${sessionToken}`,
        'Idempotency-Key': idempotencyKey,
      },
    })
  } catch {
    // 요청이 서버에 닿았는지조차 모르는 상태다. 완료는 자연 멱등이라 같은 키의 재시도가
    // 저장됐어도 안 됐어도 안전하다 — 다음 폴링 회차가 다시 두드린다.
    throw new AnalysisApiError('네트워크 오류로 테스트를 마치지 못했습니다', { retryable: true })
  }

  if (!response.ok) throw await toApiError(response)

  const body = await readJson(response)
  if (body === undefined) {
    throw new AnalysisApiError('완료 응답을 해석할 수 없습니다 (JSON 아님)', { retryable: true })
  }

  const parsed = parseComplete(body)
  if (parsed === null) {
    // 형태를 못 읽으면 READY를 놓쳐 영원히 대기하거나, 반대로 준비 안 된 결과 화면으로
    // 보내게 된다. 배포 스큐라 재시도로 고쳐지지 않는다.
    throw new AnalysisApiError('완료 응답의 형태가 계약과 다릅니다', { retryable: false })
  }
  return parsed
}

/**
 * 실패 응답을 오류로 바꾼다. 409·422의 문항 목록을 함께 실어 화면이 어느 문항을 다시
 * 녹음시켜야 하는지 알 수 있게 한다. 봉투가 없으면 재시도 여부는 상태 코드
 * (`net/retryableStatus`)로 판정한다.
 */
async function toApiError(response: Response): Promise<AnalysisApiError> {
  const envelope = readErrorEnvelope(response, await readJson(response))
  if (envelope !== null) {
    return new AnalysisApiError(envelope.message, {
      code: envelope.code,
      retryable: envelope.retryable,
      retryAfterMs: envelope.retryAfterMs,
      retakeItems: envelope.itemIds.retakeItems ?? [],
      missingItems: envelope.itemIds.missingItems ?? [],
    })
  }
  return new AnalysisApiError(`테스트를 마치지 못했습니다 (HTTP ${response.status})`, {
    retryable: isRetryableStatus(response.status),
    retryAfterMs: retryAfterMsFromHeader(response),
  })
}

/**
 * 소비하는 필드는 전부 본다. `status`는 두 값의 집합인지까지 확인한다 — 모르는 값을 통과시키면
 * READY도 PROCESSING도 아닌 상태가 되어 화면이 어느 분기로도 가지 않는다.
 *
 * `pendingItems`와 `pollAfterMs`는 PROCESSING에서만 오는 값이라, 없으면 없는 대로 받는다.
 * `pollAfterMs`가 null이면 스케줄러가 백오프 사다리만으로 간격을 정한다 — 그 폴백이 이미
 * 있으니 여기서 응답을 거절할 이유가 없다.
 */
function parseComplete(body: unknown): CompleteResult | null {
  if (typeof body !== 'object' || body === null) return null
  const { status, pendingItems, pollAfterMs } = body as Record<string, unknown>

  if (status === 'READY') return { status: 'READY' }
  if (status !== 'PROCESSING') return null

  return {
    status: 'PROCESSING',
    pendingItems:
      Array.isArray(pendingItems) && pendingItems.every((id) => typeof id === 'string')
        ? (pendingItems as string[])
        : [],
    pollAfterMs: typeof pollAfterMs === 'number' && Number.isFinite(pollAfterMs) ? pollAfterMs : null,
  }
}
