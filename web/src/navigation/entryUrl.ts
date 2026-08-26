/**
 * 진입 쿼리 조립 (KAN-31 Stage 1). 화면을 옮길 때 다음 URL의 **쿼리 부분**을 만든다.
 *
 * ## 왜 순수 함수로 떼어냈나
 *
 * 이동 자체(`window.location.href` 대입)는 jsdom이 구현하지 않아 테스트에서 확인할 수 없다.
 * 확인하고 싶은 것은 이동이 아니라 **어떤 파라미터가 남고 어떤 것이 지워지는가**라, 그 규칙만
 * 문자열 → 문자열 함수로 꺼내면 이동 지점을 가로채지 않고도 그대로 검사할 수 있다.
 *
 * ## 남기는 규칙이 하나뿐인 이유
 *
 * 세 함수 모두 **자기가 손대는 파라미터 말고는 전부 그대로 둔다**. 앱이 실어 보낸
 * `bridge`·`app`이 빠지면 스큐 판정(§5)이 구버전 앱으로 보고 업데이트 안내를 띄우고,
 * 공유 링크의 `c`가 빠지면 유입 계측이 화면 전환 한 번에 끊긴다. 어느 쪽이든 "지운 적 없는데
 * 사라진" 값이라, 지울 것만 이름으로 지우는 편이 안전하다.
 *
 * 경로(`window.location.pathname`)는 여기서 다루지 않는다. 웹 단독 진입 경로가 `/t`라
 * 이동할 때마다 경로가 유지돼야 하는데, 그건 호출자가 지금 있는 경로를 그대로 붙이면 되는
 * 일이고 이 규칙과 섞을 이유가 없다.
 */

/** 문항 진행 화면 (`?screen=test&testVersion=...&sessionId=...`) */
export function buildTestUrl(search: string, session: { testVersion: string; sessionId: string }): string {
  const params = new URLSearchParams(search)
  params.set('screen', 'test')
  params.set('testVersion', session.testVersion)
  params.set('sessionId', session.sessionId)
  return toQuery(params)
}

/**
 * 결과 화면 (`?screen=result&sessionId=...`).
 * `testVersion`은 지운다 — 결과 화면이 읽지 않는 값이고, 남겨 두면 이 URL을 다시 연 사람이
 * 끝난 세션의 정의 버전을 물고 다닌다.
 */
export function buildResultUrl(search: string, sessionId: string): string {
  const params = new URLSearchParams(search)
  params.set('screen', 'result')
  params.set('sessionId', sessionId)
  params.delete('testVersion')
  return toQuery(params)
}

/** 인트로 — 화면 지정만 걷어낸다 */
export function buildIntroUrl(search: string): string {
  const params = new URLSearchParams(search)
  params.delete('screen')
  params.delete('sessionId')
  params.delete('testVersion')
  return toQuery(params)
}

/** 남은 파라미터가 없으면 물음표도 붙이지 않는다 — `/t?`로 끝나는 주소를 만들지 않기 위해서다 */
function toQuery(params: URLSearchParams): string {
  const query = params.toString()
  return query === '' ? '' : `?${query}`
}
