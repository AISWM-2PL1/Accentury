/**
 * 어휘 답안 제출 (KAN-13 Stage 2) — `POST {apiBase}/v0/sessions/{sessionId}/vocab-items/{itemId}/answer`
 * (KAN-15, API 명세서 §3.5).
 *
 * ## 중복 생성 없는 재시도 (AC 3항)의 뼈대
 *
 * 서버는 `Idempotency-Key`가 같으면 같은 결과를 돌려주고(재전송 무해), **다른 키의 재제출은
 * 409 `ITEM_ALREADY_ANSWERED`로 거절**한다 — 문항당 답안은 하나뿐이다. 그래서:
 *
 * - 재시도는 **같은 키로** 보낸다. 키 생성·보관은 호출자(화면) 몫이고, 이 함수는 받은 키를
 *   실어 보내기만 한다 — 키의 수명(답을 바꾸면 새 키)은 화면의 선택 상태와 함께 움직이는
 *   값이라 여기 두면 상태가 두 곳으로 갈라진다.
 * - `ITEM_ALREADY_ANSWERED`는 **오류가 아니라 성공의 다른 얼굴**로 돌려준다. 이 코드가
 *   나오는 경로는 "첫 요청이 실제로는 저장됐는데 응답만 유실돼, 새 키로 다시 제출한 경우"다
 *   (스냅샷 유실 복원 포함). 답안은 서버에 있으므로 진행을 막으면 사용자가 갇힌다.
 *
 * 실패는 전부 [VocabSubmitError]다. 분기는 HTTP 상태가 아니라 오류 봉투(§2.3)의 `code`로
 * 한다 — 봉투의 한국어 `message`와 `retryable`을 그대로 실어, 화면이 재시도 버튼을 보일지
 * 판단할 수 있게 한다.
 */

import type { FetchLike } from './fetchTestDefinition'

const browserFetch: FetchLike = (input, init) => globalThis.fetch(input, init)

/** 제출에 필요한 전부. 출처 결정(토큰은 브리지, 나머지는 진입 쿼리)은 호출자 몫이다 */
export interface VocabSubmission {
  apiBase: string
  sessionId: string
  itemId: string
  /** 사용자가 [다음]으로 확정한 선택지 */
  choiceId: string
  /** 세션 토큰 (Bearer 없이 값만) */
  sessionToken: string
  /** 같은 답의 재전송을 묶는 키. 재시도 시 반드시 같은 값을 다시 넘겨야 한다 */
  idempotencyKey: string
}

/**
 * 제출 결과. 둘 다 "서버에 답안이 있다"는 뜻이라 화면은 다음 문항으로 진행하면 된다.
 * 구분을 남기는 이유: ALREADY_ANSWERED는 정상 경로에서 나올 수 없는 신호라(응답 유실
 * 복구·스냅샷 어긋남) 관측할 가치가 있다.
 */
export type VocabSubmitResult = { status: 'SAVED' } | { status: 'ALREADY_ANSWERED' }

/** 봉투의 code·retryable을 실은 제출 실패. code는 봉투를 못 읽었으면 null이다 */
export class VocabSubmitError extends Error {
  readonly code: string | null
  readonly retryable: boolean

  constructor(message: string, code: string | null, retryable: boolean) {
    super(message)
    this.name = 'VocabSubmitError'
    this.code = code
    this.retryable = retryable
  }
}

/**
 * 답안을 제출한다.
 *
 * @throws VocabSubmitError 네트워크 실패(retryable) / 봉투가 말한 오류(봉투의 retryable) /
 *   봉투조차 못 읽은 HTTP 오류(retryable — 키가 멱등이라 재시도가 무해하다)
 */
export async function submitVocabAnswer(
  submission: VocabSubmission,
  fetchImpl: FetchLike = browserFetch,
): Promise<VocabSubmitResult> {
  const { apiBase, sessionId, itemId, choiceId, sessionToken, idempotencyKey } = submission

  // 빈 값이면 요청이 엉뚱한 URL이나 401로 나가 원인을 화면에서 알 수 없게 된다.
  // 네트워크를 타기 전에 끊는다 (fetchTestDefinition의 빈 testVersion 가드와 같은 이유).
  //
  // 사용자에게 보이는 문구와 개발자가 볼 진단을 나눈다 — 이 실패는 앱이 값을 못 넘긴 상황이라
  // 사용자가 할 수 있는 게 없고, 필드 이름을 화면에 띄워 봐야 "비난 없는 카피"(ux-ui.md) 톤만
  // 깨진다. 어느 값이 비었는지는 code와 콘솔에 남겨 진단 경로를 잃지 않는다.
  for (const [name, value] of Object.entries({ sessionId, itemId, choiceId, sessionToken, idempotencyKey })) {
    if (value.trim() === '') {
      console.error(`[vocab] 답안 제출에 필요한 값이 비어 있습니다: ${name}`)
      throw new VocabSubmitError('답안을 보낼 수 없어요. 앱을 다시 시작해 주세요', `CLIENT_MISSING_${name}`, false)
    }
  }

  const url = `${apiBase.replace(/\/+$/, '')}/v0/sessions/${encodeURIComponent(sessionId)}` +
    `/vocab-items/${encodeURIComponent(itemId)}/answer`

  let response: Response
  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${sessionToken}`,
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ choiceId }),
    })
  } catch {
    // fetch 거부는 요청이 서버에 닿았는지조차 모르는 상태다 — 그래서 멱등 키가 있다.
    // 같은 키의 재시도는 저장됐어도 같은 결과, 안 됐어도 첫 저장이라 항상 안전하다.
    throw new VocabSubmitError('네트워크 오류로 답안을 제출하지 못했습니다', null, true)
  }

  if (response.ok) {
    // 200이면 저장된 것이다. 본문(accepted·answeredCount·totalCount)은 지금 쓸 곳이 없어
    // 파싱하지 않는다 — 진행률은 상태 머신이 세고, 본문 해석 실패로 성공을 실패로 뒤집는
    // 경로를 만들지 않기 위해서다.
    return { status: 'SAVED' }
  }

  const envelope = await readErrorEnvelope(response)

  if (envelope?.code === 'ITEM_ALREADY_ANSWERED') {
    return { status: 'ALREADY_ANSWERED' }
  }

  if (envelope !== null) {
    throw new VocabSubmitError(envelope.message, envelope.code, envelope.retryable)
  }
  throw new VocabSubmitError(`답안을 제출하지 못했습니다 (HTTP ${response.status})`, null, true)
}

/**
 * 새 멱등 키 (UUID v4 형태).
 *
 * `crypto.randomUUID()`를 그대로 쓰지 않는 이유: 그 함수는 **보안 컨텍스트(HTTPS·localhost)
 * 전용**이라, 개발 WebView가 로드하는 `http://10.0.2.2:5173`에는 존재하지 않는다 —
 * 에뮬레이터 실기 확인에서 TypeError로 실증됐다 (2026-08-18). `getRandomValues`는 컨텍스트
 * 구분 없이 제공되므로 그걸로 v4를 직접 만든다. 키는 서버가 형태를 해석하지 않는 불투명
 * 값이라(§3.5, 상한 길이만 검사) 어느 쪽 산출물이든 동등하다.
 */
export function newIdempotencyKey(): string {
  const c = globalThis.crypto
  if (typeof c.randomUUID === 'function') return c.randomUUID()
  const bytes = new Uint8Array(16)
  c.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 10xx
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 오류 봉투(§2.3)의 클라이언트 관심 부분. correlationId 등 나머지는 읽지 않는다 */
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
