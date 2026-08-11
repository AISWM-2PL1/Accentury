/**
 * 앱(네이티브) ↔ 웹 브리지 래퍼 (webview-layer.md §5·§8).
 *
 * 네이티브가 `addJavascriptInterface`로 심어주는 `window.AccenturyBridge`를 감싼다.
 * 브리지 호출은 반드시 이 모듈을 거친다 — 존재·타입 확인(graceful degrade)을 한 곳에 모으기 위해서다.
 * 반대 방향(네이티브 → 웹)은 `window.AccenturyWeb` 수신 지점으로 들어오며, 그 설치도 여기서 한다.
 */

import { parseItemResult, type ItemResult } from './itemResult'

export interface AccenturyBridge {
  /** [시작하기] → 네이티브 마이크 권한 게이트 호출 */
  requestMicPermission(): void
  /** VOICE 문항 진입 → 네이티브 녹음 화면 전환 (KAN-100). 인자는 [VoiceItemStart]의 JSON */
  startVoiceItem(payloadJson: string): void
  /** 앱이 보유한 브리지 계약 버전 */
  getContractVersion(): number
}

/**
 * 네이티브가 그릴 녹음 화면에 필요한 문항 컨텍스트 (KAN-100).
 * 정의(KAN-10)를 받는 주체는 웹이므로, 네이티브는 화면을 그릴 만큼만 여기서 건네받는다.
 *
 * @property itemNumber 진행 표기용 순번. 1부터 시작한다 (정의의 `seq`가 아니라 사람이 읽는 번호다)
 * @property maxDurationMs 최대 녹음 길이. VOICE 문항 정의가 들고 있는 값 그대로다
 */
export interface VoiceItemStart {
  itemId: string
  prompt: string
  itemNumber: number
  totalItems: number
  maxDurationMs: number
}

/** 네이티브 → 웹 수신 지점. 네이티브가 evaluateJavascript로 직접 부른다 */
export interface AccenturyWeb {
  onItemResult(payloadJson: string): void
}

declare global {
  interface Window {
    AccenturyBridge?: AccenturyBridge
    AccenturyWeb?: AccenturyWeb
  }
}

/**
 * 이 웹 빌드가 요구하는 최소 브리지 계약 버전.
 * 계약 규칙(§5): 필드·메서드 추가는 하위호환(버전 유지), 삭제·의미 변경은 버전 증가.
 * KAN-100의 `startVoiceItem`·`onItemResult`는 둘 다 추가라서 1을 유지한다.
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

/**
 * VOICE 문항 진입을 네이티브에 알린다 (KAN-100). 성공 여부는 requestMicPermission과 같은 규칙이다 —
 * 브리지가 없는 브라우저 단독 실행에서 false를 돌려줄 뿐 크래시하지 않는다.
 *
 * @JavascriptInterface는 문자열만 주고받으므로 구조체는 JSON으로 직렬화해 넘긴다.
 */
export function startVoiceItem(start: VoiceItemStart): boolean {
  const bridge = window.AccenturyBridge
  if (typeof bridge?.startVoiceItem !== 'function') return false
  bridge.startVoiceItem(JSON.stringify(start))
  return true
}

/**
 * 네이티브가 문항 결과를 돌려줄 수신 지점을 설치한다. 반환값은 해제 함수다.
 *
 * 설치 전에 네이티브가 먼저 부를 수 있다(화면 전환 타이밍상 결과가 빨리 나오는 경우).
 * 그래도 무해하다 — 네이티브 쪽은 `window.AccenturyWeb?.onItemResult?.(...)`처럼
 * optional chaining으로 호출하므로 수신자가 없으면 아무 일도 일어나지 않는다.
 *
 * 들어온 문자열은 신뢰 경계 밖이라 [parseItemResult]를 통과한 것만 handler로 넘긴다.
 * 불량 payload는 조용히 버린다 — 여기서 throw하면 예외가 네이티브의 evaluateJavascript
 * 콜백까지 거슬러 올라가는데, 그 자리엔 사용자에게 보여줄 화면이 없다.
 */
export function installItemResultReceiver(handler: (result: ItemResult) => void): () => void {
  const previous = window.AccenturyWeb
  window.AccenturyWeb = {
    onItemResult(payloadJson: string) {
      const result = parseItemResult(payloadJson)
      if (result !== null) handler(result)
    },
  }
  // 해제는 설치 전 값으로 되돌린다. 단순히 지우면, 이미 다른 수신자가 있는데 덧씌운 뒤
  // 해제한 경우 원래 수신자까지 같이 사라진다.
  return () => {
    window.AccenturyWeb = previous
  }
}
