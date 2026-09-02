/**
 * 음성 문항 분석 상태 일괄 조회 (KAN-14 Stage 2) —
 * `GET {apiBase}/v0/sessions/{sessionId}/analyses` (KAN-24, API 명세서 §3.4).
 *
 * ## 일괄 조회 하나뿐이다 (요구 7항)
 *
 * 서버에는 시도 1건짜리 조회(`/analyses/{jobId}`)도 있지만 이 모듈은 그쪽을 부르지 않는다.
 * 문항별로 부르면 대기 화면 한 번에 요청이 5배가 되고, "전체 문항 상태를 1회 요청으로
 * 조회한다"는 AC가 깨진다. 부를 수 있는 함수가 없으면 실수로도 깨지지 않는다 —
 * `progressMachine`이 fetch를 아예 갖지 않는 것과 같은 방식이다.
 *
 * ## 점수는 여기 없다
 *
 * 응답에 점수 필드가 없는 것은 서버 계약이고(§3.4), 이 모듈도 점수를 만들어내지 않는다.
 * 대기 화면에 중간 점수가 새는 경로는 이 파일에서 시작할 수 없다 (KAN-12).
 */

import { isRetryableStatus } from '../net/retryableStatus'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { AnalysisApiError, readErrorEnvelope, readJson, retryAfterMsFromHeader } from './errorEnvelope'

const browserFetch: FetchLike = (input, init) => globalThis.fetch(input, init)

/** 문항 단위 대표 상태 (§3.4). 시도가 여럿이면 서버가 하나로 접어서 준다 */
export type AnalysisItemStatus =
  | 'NOT_SUBMITTED'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'RETRYABLE_FAILED'
  | 'FAILED'

const ITEM_STATUSES: readonly string[] = [
  'NOT_SUBMITTED',
  'PROCESSING',
  'COMPLETED',
  'RETRYABLE_FAILED',
  'FAILED',
]

/** 실패 사유. `retryable`이 true면 재녹음이 도움이 된다 = 화면이 [다시 녹음]을 준다 */
export interface AnalysisItemError {
  code: string
  retryable: boolean
}

/**
 * 문항 하나의 상태.
 *
 * @property quality COMPLETED일 때만 값이 있다. 대기 화면이 품질 표시에 읽는다
 * @property error 실패 상태일 때만 값이 있다
 */
export interface AnalysisItem {
  itemId: string
  status: AnalysisItemStatus
  quality: string | null
  error: AnalysisItemError | null
}

/** 일괄 조회 응답 */
export interface AnalysisStatuses {
  /** 다음 조회까지 기다릴 시간. 스케줄러의 `serverPollAfterMs`로 그대로 넘긴다 (요구 1항) */
  pollAfterMs: number
  /** 음성 문항 전부. seq 오름차순이 서버 계약이라 정렬을 다시 하지 않는다 */
  items: AnalysisItem[]
}

/** 조회에 필요한 전부 */
export interface AnalysisStatusQuery {
  apiBase: string
  sessionId: string
  /** 세션 토큰 (Bearer 없이 값만) */
  sessionToken: string
}

/**
 * 전체 음성 문항의 분석 상태를 한 번에 받아온다.
 *
 * @throws AnalysisApiError 네트워크 실패 / 봉투가 말한 오류 / 봉투를 못 읽은 HTTP 오류 /
 *   200인데 본문이 계약과 다른 경우
 */
export async function fetchAnalysisStatuses(
  query: AnalysisStatusQuery,
  fetchImpl: FetchLike = browserFetch,
): Promise<AnalysisStatuses> {
  const { apiBase, sessionId, sessionToken } = query

  // 값이 비면 `/v0/sessions//analyses`나 토큰 없는 401로 나가, 원인이 값 누락이었다는 걸
  // 화면에서 알 수 없게 된다 (fetchResult·submitVocabAnswer의 같은 가드와 한 규칙).
  // 폴링은 이 실패를 재시도하지 않아야 하므로 retryable을 false로 준다 — 값이 빈 채로
  // 60초를 두드려 봐야 결과가 같다.
  for (const [name, value] of Object.entries({ sessionId, sessionToken })) {
    if (value.trim() === '') {
      console.error(`[analysis] 상태 조회에 필요한 값이 비어 있습니다: ${name}`)
      throw new AnalysisApiError('분석 상태를 확인할 수 없어요. 앱을 다시 시작해 주세요', {
        code: `CLIENT_MISSING_${name}`,
        retryable: false,
      })
    }
  }

  const url = `${apiBase.replace(/\/+$/, '')}/v0/sessions/${encodeURIComponent(sessionId)}/analyses`

  let response: Response
  try {
    response = await fetchImpl(url, {
      headers: { Accept: 'application/json', Authorization: `Bearer ${sessionToken}` },
    })
  } catch {
    // 부작용 없는 GET이라 재시도가 언제나 무해하다. 폴링이 다음 회차에 다시 두드린다.
    throw new AnalysisApiError('네트워크 오류로 분석 상태를 확인하지 못했습니다', { retryable: true })
  }

  if (!response.ok) throw await toApiError(response)

  const body = await readJson(response)
  if (body === undefined) {
    throw new AnalysisApiError('분석 상태 응답을 해석할 수 없습니다 (JSON 아님)', { retryable: true })
  }

  const parsed = parseStatuses(body)
  if (parsed === null) {
    // 형태가 다르면 화면이 상태 자리에 undefined를 그리거나, 더 나쁘게는 "아직 안 끝났다"로
    // 오독해 영원히 돈다. 재시도로 고쳐질 문제가 아니라(배포 스큐) retryable을 false로 준다.
    throw new AnalysisApiError('분석 상태 응답의 형태가 계약과 다릅니다', { retryable: false })
  }
  return parsed
}

/**
 * 실패 응답을 봉투로 읽어 오류로 바꾼다. 봉투가 없으면 재시도 여부는 상태 코드
 * (`net/retryableStatus`)로, 429의 대기 시간은 `Retry-After` 헤더로 판정한다.
 */
async function toApiError(response: Response): Promise<AnalysisApiError> {
  const envelope = readErrorEnvelope(response, await readJson(response))
  if (envelope !== null) {
    return new AnalysisApiError(envelope.message, {
      code: envelope.code,
      retryable: envelope.retryable,
      retryAfterMs: envelope.retryAfterMs,
    })
  }
  return new AnalysisApiError(`분석 상태를 확인하지 못했습니다 (HTTP ${response.status})`, {
    retryable: isRetryableStatus(response.status),
    retryAfterMs: retryAfterMsFromHeader(response),
  })
}

/**
 * **소비하는 필드는 전부 본다** (KAN-29가 Codex 교차 검증에서 배운 규칙).
 *
 * 대기 화면이 읽는 것은 `pollAfterMs`·`items[].itemId`·`status`·`quality`·`error.retryable`
 * 전부다. 특히 `status`는 문자열이기만 하면 통과시키면 안 된다 — 모르는 값이 오면 화면이
 * 어느 분기로도 가지 않고, 그 문항은 영영 "대기 중"으로 남아 폴링 상한까지 돌게 된다.
 * 그래서 다섯 값의 집합인지까지 본다.
 *
 * 반대로 서버가 나중에 더할 필드는 보지 않는다 — 모르는 필드 무시가 계약이다 (§2.3).
 */
function parseStatuses(body: unknown): AnalysisStatuses | null {
  if (typeof body !== 'object' || body === null) return null
  const { pollAfterMs, items } = body as Record<string, unknown>

  if (typeof pollAfterMs !== 'number' || !Number.isFinite(pollAfterMs)) return null
  if (!Array.isArray(items)) return null

  const parsed: AnalysisItem[] = []
  for (const raw of items) {
    const item = parseItem(raw)
    if (item === null) return null
    parsed.push(item)
  }
  return { pollAfterMs, items: parsed }
}

function parseItem(raw: unknown): AnalysisItem | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { itemId, status, quality, error } = raw as Record<string, unknown>

  if (typeof itemId !== 'string' || itemId === '') return null
  if (typeof status !== 'string' || !ITEM_STATUSES.includes(status)) return null

  // quality는 COMPLETED에서만 오는 선택 필드다. 없으면 null, 있는데 문자열이 아니면 계약 위반이다.
  if (quality !== undefined && quality !== null && typeof quality !== 'string') return null

  return {
    itemId,
    status: status as AnalysisItemStatus,
    quality: typeof quality === 'string' ? quality : null,
    error: parseItemError(error),
  }
}

/**
 * 실패 사유. 형태가 이상하면 null로 접는다 — 여기서 응답 전체를 거절하지 않는 이유는,
 * `error`가 없어도 `status`만으로 화면이 실패를 그릴 수 있기 때문이다. 재녹음 가능 여부는
 * `RETRYABLE_FAILED`라는 상태 자체가 이미 말한다.
 */
function parseItemError(value: unknown): AnalysisItemError | null {
  if (typeof value !== 'object' || value === null) return null
  const { code, retryable } = value as Record<string, unknown>
  if (typeof code !== 'string' || typeof retryable !== 'boolean') return null
  return { code, retryable }
}
