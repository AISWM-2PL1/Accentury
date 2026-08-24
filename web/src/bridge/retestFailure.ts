/**
 * 재응시 실패 회신 계약 (KAN-34 2단계) 미러 타입.
 *
 * 정본은 네이티브의 `RetestFailure.kt`다 — 이 파일은 그 payload를 웹에서 읽기 위한 사본일 뿐이며,
 * 계약이 바뀌면 정본을 따라 여기를 고친다 (itemResult.ts가 `ItemResult.kt`를 미러하는 것과 같은 방식).
 *
 * 성공은 이 통로로 오지 않는다. 재응시가 성공하면 네이티브가 WebView를 인트로 URL로 다시 로드하므로
 * 회신을 받을 페이지 자체가 사라진다 — 결과 화면이 알아야 하는 것은 "안 됐다"뿐이다.
 */

/**
 * 결과 화면이 [다시 테스트하기] 실패에 대해 돌려받는 전부. 공통 오류 봉투(§2.3)의 부분집합이다.
 *
 * @property code 서버 오류 봉투의 코드. 봉투를 못 읽은 응답에서는 null이다 — 네이티브는 자기
 *   판정을 코드처럼 지어내지 않는다 (RetestFailure.kt)
 * @property message 사용자에게 그대로 보여줄 수 있는 문구. 갈래별 카피를 웹이 따로 들면 같은
 *   판정에 두 벌이 생겨 앱과 웹이 다른 말을 하게 된다
 * @property retryable 다시 눌러 볼 값어치가 있는가
 * @property retryAfterMs 429가 알려준 대기 시간 (§2.5). 그 외에는 null
 */
export interface RetestFailure {
  code: string | null
  message: string
  retryable: boolean
  retryAfterMs: number | null
}

/**
 * 네이티브가 넘긴 JSON 문자열을 계약 타입으로 좁힌다. 신뢰할 수 없으면 null이다.
 *
 * 브리지 반대편은 웹에서 보면 신뢰 경계 밖이다 — `evaluateJavascript`로 들어오는 문자열은
 * 이론적으로 무엇이든 될 수 있으므로 `unknown`으로 받아 필드마다 확인한다 (parseItemResult와 같은 방침).
 *
 * 모르는 필드가 섞여 있어도 통과시킨다: 계약 규칙(§5)상 필드 추가는 하위호환이다.
 *
 * **[retryAfterMs]만 다르게 다룬다 — 형태가 이상하면 그 값만 버리고 나머지는 살린다.** 이 값은
 * 화면에 대기 시간을 덧붙이는 부가 정보인데, 그것 하나 때문에 payload째 버리면 사용자는 왜 아무
 * 일도 일어나지 않았는지 영영 듣지 못한다. 실패를 알리지 못하는 쪽이 대기 안내를 못 하는 쪽보다
 * 나쁘다 (bridge.ts의 guideF0 관대 파싱과 같은 판단).
 */
export function parseRetestFailure(raw: string): RetestFailure | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }

  if (typeof parsed !== 'object' || parsed === null) return null
  const { code, message, retryable, retryAfterMs } = parsed as Record<keyof RetestFailure, unknown>

  // 봉투를 못 읽은 응답은 code가 없다 — 빠진 것과 null을 같게 본다.
  if (code !== null && code !== undefined && typeof code !== 'string') return null
  // 보여줄 문구가 없으면 이 회신으로 화면이 할 수 있는 말이 없다.
  if (typeof message !== 'string' || message.trim() === '') return null
  if (typeof retryable !== 'boolean') return null

  return {
    code: typeof code === 'string' ? code : null,
    message,
    retryable,
    retryAfterMs: toWaitMillis(retryAfterMs),
  }
}

/** 대기 시간으로 쓸 수 있는 값일 때만 그대로, 아니면 없는 것으로 본다. */
function toWaitMillis(value: unknown): number | null {
  if (typeof value !== 'number') return null
  // NaN·Infinity·음수 대기는 어느 것도 대기 안내가 될 수 없다.
  return Number.isFinite(value) && value >= 0 ? value : null
}
