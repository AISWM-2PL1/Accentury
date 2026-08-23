/**
 * 오류 봉투(§2.3) 읽기 — 대기 화면이 쓰는 두 엔드포인트(`/analyses`, `/complete`)의 공용 부분
 * (KAN-14 Stage 2).
 *
 * ## 왜 세 번째 사본인가
 *
 * `submitVocabAnswer`와 `fetchResult`에 이미 같은 이름의 지역 함수가 있다. 그 둘은 봉투에서
 * `code`·`message`·`retryable` 셋만 읽는다 — 자기 화면이 그 셋으로 충분했기 때문이다.
 * 대기 화면은 여기에 **`retryAfterMs`(429 대기, 요구 6항)와 문항 목록 확장 필드**
 * (`pendingItems`·`retakeItems`·`missingItems`, §3.6)를 더 읽어야 한다.
 *
 * 그래서 셋을 지금 하나로 합치지 않는다. 합치려면 저쪽 둘의 반환 타입이 넓어지고, 그 화면들이
 * 읽지도 않는 필드가 계약에 들어온다 — `fetchResult`가 "이 티켓이 읽지 않는 것"이라고 명시적으로
 * 남겨 둔 값들이 바로 이 필드들이다. 통합은 세 화면이 실제로 같은 필드를 읽게 된 뒤에 할 일이고,
 * 그때는 이 파일이 가장 넓은 쪽이라 여기로 모으면 된다.
 */

/** 봉투의 클라이언트 관심 부분. `correlationId`는 읽지 않는다 — 서버 로그를 찾는 키라 화면이 쓸 곳이 없다 */
export interface ErrorEnvelope {
  code: string
  message: string
  retryable: boolean
  /** 429에서만 값이 있다. 그 외에는 null (§2.3) */
  retryAfterMs: number | null
  /** §3.6·§3.7의 확장 필드. 셋 중 채워지는 것은 항상 하나이고 나머지는 직렬화에서 빠진다 */
  itemIds: { pendingItems: string[] | null; retakeItems: string[] | null; missingItems: string[] | null }
}

/**
 * 대기 화면이 쓰는 API 실패 하나.
 *
 * 분기는 HTTP 상태가 아니라 `code`로 한다 — `/complete`의 409가 `RESULT_RETAKE_REQUIRED`
 * (재녹음 필요)일 수도 `SESSION_COMPLETED`(이미 완료)일 수도 있어서, 상태 코드로 갈라 놓으면
 * 두 경우에 같은 문구가 나간다 (`fetchResult`가 같은 이유로 세운 규칙).
 */
export class AnalysisApiError extends Error {
  /** 봉투를 못 읽었으면 null */
  readonly code: string | null
  readonly retryable: boolean
  /** 429가 지시한 대기(ms). 폴링 스케줄러에 그대로 넘긴다 */
  readonly retryAfterMs: number | null
  /** 재녹음 대상 문항 (409 `RESULT_RETAKE_REQUIRED`) */
  readonly retakeItems: string[]
  /** 미제출 문항 (422 `RESULT_INCOMPLETE`) */
  readonly missingItems: string[]

  constructor(
    message: string,
    options: {
      code?: string | null
      retryable?: boolean
      retryAfterMs?: number | null
      retakeItems?: string[]
      missingItems?: string[]
    } = {},
  ) {
    super(message)
    this.name = 'AnalysisApiError'
    this.code = options.code ?? null
    this.retryable = options.retryable ?? true
    this.retryAfterMs = options.retryAfterMs ?? null
    this.retakeItems = options.retakeItems ?? []
    this.missingItems = options.missingItems ?? []
  }

  /** 요청 제한 — 스케줄러가 `Retry-After`를 지켜야 하는 유일한 실패다 (요구 6항) */
  get rateLimited(): boolean {
    return this.retryAfterMs !== null
  }
}

/**
 * 실패 응답을 봉투로 읽어 본다. JSON이 아니거나 봉투 모양이 아니면 null — 호출자가 상태 코드
 * 폴백으로 간다.
 *
 * ## 429 대기 시간은 본문을 먼저 본다
 *
 * 서버는 같은 값을 두 곳에 싣는다: 본문의 `retryAfterMs`(ms)와 `Retry-After` 헤더(초, 올림).
 * 본문이 정본이다 — 헤더는 초 단위로 올림되면서 최대 999ms의 오차가 생긴다. 본문을 못 읽었을
 * 때만 헤더로 내려간다.
 *
 * 헤더 폴백을 남겨 두는 이유: 429는 프록시나 게이트웨이가 우리 본문 없이 돌려줄 수 있는
 * 유일한 상태다. 그때 봉투는 없지만 헤더는 있다.
 */
export function readErrorEnvelope(response: Response, body: unknown): ErrorEnvelope | null {
  if (typeof body !== 'object' || body === null) return null
  const record = body as Record<string, unknown>
  const { code, message, retryable } = record
  if (typeof code !== 'string' || typeof message !== 'string' || typeof retryable !== 'boolean') return null

  return {
    code,
    message,
    retryable,
    retryAfterMs: readRetryAfterMs(response, record.retryAfterMs),
    itemIds: {
      pendingItems: readItemIds(record.pendingItems),
      retakeItems: readItemIds(record.retakeItems),
      missingItems: readItemIds(record.missingItems),
    },
  }
}

/** 본문을 JSON으로 읽어 본다. 실패하면 undefined — 봉투 없는 응답이라는 뜻이다 */
export async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return undefined
  }
}

/**
 * 봉투가 없는 실패도 429면 헤더에서 대기 시간을 건진다.
 * 봉투를 못 읽어 상태 코드 폴백으로 가는 경로에서도 요구 6항은 지켜져야 한다.
 */
export function retryAfterMsFromHeader(response: Response): number | null {
  if (response.status !== 429) return null
  const raw = response.headers?.get?.('Retry-After')
  if (raw === null || raw === undefined || raw.trim() === '') return null
  const seconds = Number(raw)
  // HTTP-date 형식도 규격상 유효하지만 서버가 초만 쓴다 (GlobalExceptionHandler).
  // 숫자가 아니면 대기 지시가 없는 것으로 본다 — 날짜 파싱을 넣으면 시계 어긋남까지 떠안는다.
  return Number.isFinite(seconds) && seconds >= 0 ? Math.round(seconds * 1000) : null
}

function readRetryAfterMs(response: Response, value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value) && value >= 0) return value
  return retryAfterMsFromHeader(response)
}

/** 문자열 배열만 받아들인다. 아니면 null — 없는 것과 같이 다룬다 */
function readItemIds(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null
  return value.every((id) => typeof id === 'string') ? (value as string[]) : null
}
