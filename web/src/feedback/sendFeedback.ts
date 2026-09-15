/**
 * 이용 후기 전송 (KAN-211 3단계) —
 * `POST {apiBase}/v0/sessions/{sessionId}/feedback` (1단계에서 확정한 계약).
 *
 * ## 이 요청이 실패해도 결과 화면은 그대로여야 한다
 *
 * 후기는 결과를 다 본 사람의 부가 행동이다. 등급·공유·재검사는 이 모듈이 무슨 답을 받든
 * 그대로 서 있어야 하므로, 여기서 나가는 실패는 전부 [FeedbackApiError]로 좁혀서 던지고
 * 시트 안에서만 그린다 — 화면 전체를 오류 상태로 바꾸는 길이 이 경로에는 없다.
 *
 * ## 409는 둘로 갈린다
 *
 * 같은 상태 코드에 뜻이 둘이라 분기는 HTTP 상태가 아니라 봉투의 `code`로 한다 ([fetchResult]가
 * 같은 이유로 세운 규칙). [FEEDBACK_ALREADY_SUBMITTED]는 **예외가 아니라 정상 완료**다 —
 * 결과 화면을 새로고침한 뒤 다시 보낸 경우가 그 자리이고, 사용자가 하려던 일은 이미 되어
 * 있다. [RESULT_NOT_READY]는 진짜 실패라 그대로 오류로 올린다.
 *
 * ## 봉투 읽기를 `analysis/errorEnvelope`에서 가져오는 이유
 *
 * 레포에 봉투 판독기가 셋 있다 — `submitVocabAnswer`·`fetchResult`의 지역 함수와
 * `analysis/errorEnvelope`. 앞의 둘은 `code`·`message`·`retryable`만 읽어서 429의
 * `retryAfterMs`를 주지 못한다. 후기는 요청 제한(429)이 계약에 있고 그 대기 시간을 사용자에게
 * 알려야 하므로 **가장 넓은 쪽**을 쓴다. 그 파일이 "통합은 세 화면이 실제로 같은 필드를 읽게
 * 된 뒤에 할 일이고, 그때는 여기로 모으면 된다"고 남겨 둔 자리이기도 하다.
 */

import { readErrorEnvelope, readJson, retryAfterMsFromHeader } from '../analysis/errorEnvelope'
import { isRetryableStatus } from '../net/retryableStatus'
import type { FetchLike } from '../progress/fetchTestDefinition'
import {
  FEEDBACK_CLIENT_MISSING,
  FEEDBACK_HTTP_ERROR,
  FEEDBACK_INVALID_BODY_EMPTY,
  FEEDBACK_INVALID_BODY_LONG,
  FEEDBACK_INVALID_EMAIL,
  FEEDBACK_INVALID_EMAIL_LONG,
  FEEDBACK_INVALID_RATING,
  FEEDBACK_NETWORK_ERROR,
} from './feedbackText'

const browserFetch: FetchLike = (input, init) => globalThis.fetch(input, init)

/** 본문 상한 (서버와 같은 값). 글자 수 표시와 `maxLength`가 같이 읽는다 */
export const FEEDBACK_BODY_MAX = 500

/** 이메일 상한 (RFC 5321의 경로 상한). 서버와 같은 값이다 */
export const FEEDBACK_EMAIL_MAX = 254

/**
 * 같은 결과에 이미 후기가 있다는 뜻의 코드 (409).
 *
 * 상수로 빼는 이유는 [RESULT_EXPIRED]와 같다 — 이 한 코드에서만 다른 길로 간다. 문자열을
 * 화면 코드에 직접 적으면 오타가 조용히 "그냥 실패" 분기로 흘러간다.
 */
export const FEEDBACK_ALREADY_SUBMITTED = 'FEEDBACK_ALREADY_SUBMITTED'

/** 세션 토큰이 만료됐다 (401). 재시도해도 같은 답이라 시트가 다른 문구로 간다 */
export const FEEDBACK_SESSION_EXPIRED = 'SESSION_EXPIRED'

/** 이 세션의 결과가 아니다 (403). 만료와 사용자 입장이 같아 같은 문구를 쓴다 */
export const FEEDBACK_SESSION_FORBIDDEN = 'SESSION_FORBIDDEN'

/**
 * 사용자가 적은 것 전부.
 *
 * 선택 값이 `undefined`가 아니라 `null`인 이유: 시트의 상태가 "아직 안 고름"과 "지웠음"을
 * 구분하지 않고, 두 경우 모두 서버에 키를 빼고 보낸다. `undefined`를 섞으면 "빠뜨린 필드"와
 * "비운 필드"가 타입에서 같아 보이는데 여기에는 그 구분이 없다.
 */
export interface FeedbackInput {
  /** 별점 1~5. 고르지 않았으면 null */
  rating: number | null
  /** 서술 후기. 보내기 전에 trim한다 */
  body: string
  /** 답변 받을 주소. 적지 않았으면 null */
  contactEmail: string | null
}

/**
 * 전송 결과.
 * - `saved`: 이번 요청으로 저장됐다 (201) 또는 같은 키의 재전송이었다 (200)
 * - `already`: 같은 결과에 이미 다른 후기가 있다 (409) — 실패가 아니다 (파일 헤더)
 */
export type SendFeedbackResult = { status: 'saved' } | { status: 'already' }

/**
 * 후기 전송 실패 하나.
 *
 * 필드 구성은 [ResultFetchError]와 같은 자리를 맡되 `retryAfterMs`가 하나 더 있다 (429,
 * 파일 헤더). 그래서 생성자를 위치 인자가 아니라 옵션 객체로 받는다 — 선택 필드가 셋이면
 * `new FeedbackApiError('...', null, true, null)` 같은 호출이 무엇을 말하는지 읽히지 않는다
 * ([AnalysisApiError]가 같은 이유로 고른 꼴이다).
 */
export class FeedbackApiError extends Error {
  /** 봉투를 못 읽었으면 null */
  readonly code: string | null
  readonly retryable: boolean
  /** 429가 지시한 대기(ms). 그 외에는 null */
  readonly retryAfterMs: number | null

  constructor(
    message: string,
    options: { code?: string | null; retryable?: boolean; retryAfterMs?: number | null } = {},
  ) {
    super(message)
    this.name = 'FeedbackApiError'
    this.code = options.code ?? null
    this.retryable = options.retryable ?? true
    this.retryAfterMs = options.retryAfterMs ?? null
  }
}

/** 전송에 필요한 전부. 멱등 키를 **호출자가 만들어 넘긴다** — 아래 `@param` 참고 */
export interface SendFeedbackRequest {
  apiBase: string
  sessionId: string
  /** 세션 토큰 (Bearer 없이 값만) */
  sessionToken: string
  /**
   * 멱등 키 (§2.2 — 비용 발생 POST).
   *
   * 기본값을 두지 않는다. 재시도가 **같은 키로** 나가야 하는데, 여기서 만들면 부를 때마다
   * 새 키가 되어 그 규칙을 이 모듈이 깨뜨린다 — 첫 요청이 실제로는 저장됐던 경우(응답만
   * 유실) 새 키의 재시도는 409를 받아 "이미 보냈어요"가 되고, 사용자는 방금 쓴 글이
   * 어디로 갔는지 알 수 없게 된다. 키의 수명은 시트가 열려 있는 동안이고 그 수명을 아는
   * 것은 부모 화면이다.
   */
  idempotencyKey: string
  input: FeedbackInput
}

/**
 * 입력이 계약에 맞는지 본다. 맞으면 null, 아니면 **사용자에게 보여줄 문구**를 돌려준다.
 *
 * 서버가 같은 규칙으로 한 번 더 검사한다 (400 `VALIDATION_FAILED`). 그래도 여기서 먼저 보는
 * 이유는 왕복 때문이다 — 빈 본문이나 500자 초과는 보내 보지 않아도 아는 일이고, 그걸 서버에
 * 물어보면 사용자는 네트워크를 한 번 기다린 뒤에야 "적어 주세요"를 읽는다.
 *
 * 서버 판정을 대신하지는 않는다. 여기를 통과한 값도 400을 받을 수 있고(서버 규칙이 먼저
 * 바뀌는 경우), 그때는 서버가 준 한국어 문장이 그대로 화면에 나간다.
 */
export function validateFeedbackInput(input: FeedbackInput): string | null {
  const body = input.body.trim()
  if (body.length === 0) return FEEDBACK_INVALID_BODY_EMPTY
  if (body.length > FEEDBACK_BODY_MAX) return FEEDBACK_INVALID_BODY_LONG

  const { rating } = input
  if (rating !== null && (!Number.isInteger(rating) || rating < 1 || rating > 5)) {
    return FEEDBACK_INVALID_RATING
  }

  const email = input.contactEmail?.trim() ?? ''
  if (email !== '') {
    /*
     * 주소 형식을 엄밀히 재지 않는다. RFC 5322를 정규식으로 옮기면 읽을 수 없는 한 줄이
     * 되는데, 여기서 막으려는 것은 위조가 아니라 오타(@를 빼먹거나 도메인을 덜 적은 주소)다.
     * 진짜 판정은 답장이 가 닿는지이고 그건 클라이언트가 알 수 없다.
     */
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return FEEDBACK_INVALID_EMAIL
    if (email.length > FEEDBACK_EMAIL_MAX) return FEEDBACK_INVALID_EMAIL_LONG
  }

  return null
}

/**
 * 후기를 보낸다.
 *
 * @throws FeedbackApiError 빈 세션 값 / 클라이언트 검증 실패 / 네트워크 실패 / 봉투가 말한
 *   오류 / 봉투를 못 읽은 HTTP 오류 (재시도 여부는 상태 코드로 판정한다 —
 *   `net/retryableStatus`)
 */
export async function sendFeedback(
  request: SendFeedbackRequest,
  fetchImpl: FetchLike = browserFetch,
): Promise<SendFeedbackResult> {
  const { apiBase, sessionId, sessionToken, idempotencyKey, input } = request

  // 빈 값이면 `/v0/sessions//feedback`이나 토큰 없는 401로 나가, 원인이 값 누락이었다는 걸
  // 화면에서 알 수 없게 된다. 네트워크를 타기 전에 끊는다 ([fetchResult]의 같은 가드).
  for (const [name, value] of Object.entries({ sessionId, sessionToken, idempotencyKey })) {
    if (value.trim() === '') {
      console.error(`[feedback] 후기 전송에 필요한 값이 비어 있습니다: ${name}`)
      throw new FeedbackApiError(FEEDBACK_CLIENT_MISSING, {
        code: `CLIENT_MISSING_${name}`,
        retryable: false,
      })
    }
  }

  // 시트가 이미 막고 있지만(보내기 버튼 비활성) 이 모듈의 계약이기도 하다 — 다른 호출처가
  // 생겨도 규격 밖 본문이 서버로 나가지 않는다.
  const invalid = validateFeedbackInput(input)
  if (invalid !== null) {
    throw new FeedbackApiError(invalid, { code: 'CLIENT_VALIDATION_FAILED', retryable: false })
  }

  const url = `${apiBase.replace(/\/+$/, '')}/v0/sessions/${encodeURIComponent(sessionId)}/feedback`

  let response: Response
  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: `Bearer ${sessionToken}`,
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(toPayload(input)),
    })
  } catch {
    // 요청이 서버에 닿았는지조차 모르는 상태다. 같은 키로 다시 보내면 안전하다 —
    // 저장됐었다면 서버가 200으로 같은 답을 준다.
    throw new FeedbackApiError(FEEDBACK_NETWORK_ERROR, { retryable: true })
  }

  if (!response.ok) {
    const error = await toApiError(response)
    // 409 중 하나만 정상 완료로 접는다 (파일 헤더). 나머지 실패는 그대로 올린다 —
    // 같은 409라도 `RESULT_NOT_READY`는 아직 결과가 확정되지 않은 것이라 뜻이 정반대다.
    if (error.code === FEEDBACK_ALREADY_SUBMITTED) return { status: 'already' }
    throw error
  }

  /*
   * 200·201 본문(`{accepted: true}`)은 읽지 않는다. 검증의 경계는 "화면이나 동작이 이 값을
   * 읽는가"인데([fetchResult]의 규칙) `accepted`는 값이 하나뿐이라 분기가 없고, 저장됐다는
   * 사실은 상태 코드가 이미 말한다. 굳이 형태를 따지면 실제로는 저장된 후기에 대해
   * "실패"라고 말하는 길만 하나 생긴다.
   */
  return { status: 'saved' }
}

/**
 * 보낼 JSON 한 덩이. **선택 값은 키째 뺀다.**
 *
 * 빈 문자열 이메일을 서버가 미입력으로 보긴 하지만, 그건 서버 쪽 관용이지 우리가 기대고
 * 있어야 할 계약이 아니다. 적지 않은 것을 적지 않은 채로 보내는 편이 둘 사이에 해석이
 * 필요 없다.
 */
function toPayload(input: FeedbackInput): Record<string, unknown> {
  const payload: Record<string, unknown> = { body: input.body.trim() }
  if (input.rating !== null) payload.rating = input.rating
  const email = input.contactEmail?.trim() ?? ''
  if (email !== '') payload.contactEmail = email
  return payload
}

/**
 * 실패 응답을 오류로 바꾼다. 봉투가 없으면 재시도 여부는 상태 코드로 판정한다
 * (`net/retryableStatus`가 그 판정의 유일한 정의다).
 */
async function toApiError(response: Response): Promise<FeedbackApiError> {
  const envelope = readErrorEnvelope(response, await readJson(response))
  if (envelope !== null) {
    // 400 `VALIDATION_FAILED`의 `message`는 서버가 준 한국어 문장이다 — 그대로 싣는다.
    // 앱 배포 없이 안내를 바꿀 수 있어야 한다 (KAN-25의 결과 화면과 같은 규칙).
    return new FeedbackApiError(envelope.message, {
      code: envelope.code,
      retryable: envelope.retryable,
      retryAfterMs: envelope.retryAfterMs,
    })
  }
  return new FeedbackApiError(FEEDBACK_HTTP_ERROR(response.status), {
    retryable: isRetryableStatus(response.status),
    retryAfterMs: retryAfterMsFromHeader(response),
  })
}
