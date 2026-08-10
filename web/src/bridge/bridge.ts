/**
 * 앱(네이티브) ↔ 웹 브리지 래퍼 (webview-layer.md §5·§8).
 *
 * 네이티브가 `addJavascriptInterface`로 심어주는 `window.AccenturyBridge`를 감싼다.
 * 브리지 호출은 반드시 이 모듈을 거친다 — 존재·타입 확인(graceful degrade)을 한 곳에 모으기 위해서다.
 */

export interface AccenturyBridge {
  /** [시작하기] → 네이티브 마이크 권한 게이트 호출 */
  requestMicPermission(): void
  /** 앱이 보유한 브리지 계약 버전 */
  getContractVersion(): number
}

declare global {
  interface Window {
    AccenturyBridge?: AccenturyBridge
  }
}

/**
 * 이 웹 빌드가 요구하는 최소 브리지 계약 버전.
 * 계약 규칙(§5): 필드·메서드 추가는 하위호환(버전 유지), 삭제·의미 변경은 버전 증가.
 */
export const REQUIRED_BRIDGE_VERSION = 1

/**
 * 앱이 로드 URL에 실어 보낸 브리지 버전(`?bridge=<n>`)을 읽는다.
 * 없거나 정수가 아니면 null — 스큐 협상 이전의 구버전 앱이라는 뜻이다.
 */
export function appBridgeVersion(search: string): number | null {
  const raw = new URLSearchParams(search).get('bridge')
  if (raw === null || raw.trim() === '') return null
  const n = Number(raw)
  return Number.isInteger(n) && n >= 0 ? n : null
}

/**
 * 버전 스큐 판정 — 판단 주체는 웹이다 (§5).
 * 앱이 보낸 버전이 요구 버전보다 낮으면(또는 아예 없으면) 호환 불가로 본다.
 * 앱은 그대로 두면 되므로 구버전 앱에서도 이 판정 자체는 항상 동작한다.
 */
export function isBridgeCompatible(search: string): boolean {
  const version = appBridgeVersion(search)
  return version !== null && version >= REQUIRED_BRIDGE_VERSION
}

/**
 * 마이크 권한 게이트 호출. 브리지가 없거나 메서드가 아니면 false를 돌려준다
 * (§5 graceful degrade — 브라우저 단독 실행이나 계약이 어긋난 앱에서 크래시하지 않기 위함).
 */
export function requestMicPermission(): boolean {
  const bridge = window.AccenturyBridge
  if (typeof bridge?.requestMicPermission !== 'function') return false
  bridge.requestMicPermission()
  return true
}
