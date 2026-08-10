/**
 * 테스트 정의 조회 (KAN-99 Stage 3) — `GET {apiBase}/v0/tests/{testVersion}` (KAN-10, API 명세서 §3.2).
 *
 * **열린 질문 — `apiBase`와 `testVersion`의 출처는 아직 계약 미확정이다.**
 * `testVersion`은 세션 생성(KAN-9) 응답에 들어 있는데, 그 응답을 누가 받고 웹에 어떻게 넘기는지가
 * 아직 정해지지 않았다(로드 URL 쿼리에 실어 보내기 / 브리지 메서드로 건네기 — KAN-100 몫).
 * 그래서 이 함수는 둘 다 **인자로만** 받는다. 출처를 여기서 정해 버리면 계약이 확정될 때
 * 네트워크 코드까지 같이 뜯어야 하므로, 출처 결정은 호출자(App 결선)에 위임한다.
 *
 * 재시도 로직은 넣지 않는다. 재시도 횟수·간격은 KAN-14의 폴링 상한(최대 60초)과 같은 자리에서
 * 정해져야 하는 값이고, 지금 임의로 정하면 나중에 두 곳이 어긋난다. 실패는 그대로 올려서
 * 화면이 [다시 시도]를 띄우게 한다 — 사용자가 재시도 주체가 되는 편이 정책 미확정 구간에서 안전하다.
 *
 * 304/ETag(§3.2 immutable 캐시)는 별도로 다루지 않는다. 조건부 요청과 캐시 재사용은 브라우저가
 * 알아서 하고, 이 함수는 그 결과인 200 본문만 본다.
 */

import type { TestDefinition } from './testDefinition'

/**
 * fetch 주입 지점. 테스트에서 실물 네트워크 없이 응답을 만들기 위해 쓰는 만큼만 좁혀 받는다.
 */
export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

/**
 * 기본 구현. `globalThis.fetch`를 기본값으로 그대로 두면 참조가 분리돼
 * 일부 환경에서 illegal invocation이 나고, 테스트의 전역 스텁도 늦게 반영되지 않는다.
 * 감싸 두면 호출 시점에 전역을 읽으므로 둘 다 해결된다.
 */
const browserFetch: FetchLike = (input, init) => globalThis.fetch(input, init)

/**
 * 테스트 정의를 받아온다. 실패는 모두 명확한 메시지를 가진 Error다.
 *
 * @param apiBase 백엔드 오리진 (끝 슬래시는 있어도 된다)
 * @param testVersion 세션에 고정된 정의 버전 (예: `gn-2026.08.1`)
 * @param fetchImpl 주입용 fetch. 기본값은 전역 fetch
 * @throws Error 빈 testVersion / HTTP 오류 / JSON 아님 / 계약과 다른 형태
 */
export async function fetchTestDefinition(
  apiBase: string,
  testVersion: string,
  fetchImpl: FetchLike = browserFetch,
): Promise<TestDefinition> {
  // 빈 값이면 `/v0/tests/`로 요청이 나가 404·405가 돌아온다. 원인이 URL 조립 실패였다는 걸
  // 화면에서 알 수 없게 되므로, 네트워크를 타기 전에 여기서 끊는다.
  if (testVersion.trim() === '') {
    throw new Error('testVersion이 없어 테스트 정의를 조회할 수 없습니다')
  }

  const url = `${apiBase.replace(/\/+$/, '')}/v0/tests/${encodeURIComponent(testVersion)}`
  const response = await fetchImpl(url, { headers: { Accept: 'application/json' } })

  if (!response.ok) {
    throw new Error(`테스트 정의를 불러오지 못했습니다 (HTTP ${response.status})`)
  }

  let parsed: unknown
  try {
    parsed = await response.json()
  } catch {
    throw new Error('테스트 정의 응답을 해석할 수 없습니다 (JSON 아님)')
  }

  if (!isTestDefinitionShape(parsed)) {
    throw new Error('테스트 정의 응답의 형태가 계약과 다릅니다')
  }
  return parsed
}

/**
 * 계약의 뼈대만 본다 — "문항 배열이 있는 정의인가"까지.
 *
 * 필드를 하나하나 검증하지 않는 이유: 진행을 실제로 망가뜨리는 손상(문항 0개·seq 중복·itemId 중복)은
 * `createProgressState`가 이미 막는다. 여기서 겹쳐 검증하면 계약이 바뀔 때 고칠 곳만 늘어난다.
 * 여기서 거르는 건 그 가드에 닿기도 전에 TypeError로 죽는 형태(HTML 오류 페이지가 JSON으로
 * 파싱됐다거나, 에러 응답 본문이 200으로 온 경우)뿐이다.
 */
function isTestDefinitionShape(value: unknown): value is TestDefinition {
  if (typeof value !== 'object' || value === null) return false
  const { testVersion, items } = value as Record<string, unknown>
  return typeof testVersion === 'string' && Array.isArray(items)
}
