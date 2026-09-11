/**
 * 전면(interstitial) 광고를 **세션당 한 번만** 요청한다 (KAN-196).
 *
 * 브리지 래퍼([showInterstitialAd])는 부를 수 있으면 부른다까지만 맡고, 횟수는 여기서 센다.
 * 한 번이어야 하는 이유는 광고 정책이 아니라 화면 사정이다 — 분석 대기 화면은 한 세션 안에서
 * 여러 번 마운트될 수 있다.
 *
 * - React StrictMode가 개발 빌드에서 effect를 두 번 돌린다 (마운트 → 정리 → 마운트)
 * - 재녹음 뒤 돌아오거나 폴링 상태가 바뀌며 화면이 다시 그려진다
 * - 진행 화면이 `AWAITING_ANALYSIS`를 벗어났다 돌아오면 컴포넌트가 새로 선다
 *
 * ## 왜 ref가 아니라 모듈 상태인가
 *
 * `useRef`는 컴포넌트 인스턴스와 수명이 같아 StrictMode 이중 실행은 막지만 위 셋째 경우
 * (언마운트 뒤 재마운트)는 못 막는다. 세션 id를 키로 모듈에 두면 문서가 살아 있는 동안
 * 같은 세션은 한 번이다. 문서가 리로드되면(재응시 → 새 세션) 상태도 함께 비는데, 그때는
 * 세션도 새것이라 다시 한 번이 맞다.
 *
 * `sessionStorage`에 두지 않은 이유: 프로세스 복원으로 같은 세션의 대기 화면이 다시 열리는
 * 드문 경우까지 막을 수 있지만, 저장소가 없는 환경(사생활 보호 모드)의 예외 처리가 붙는
 * 대가에 비해 막는 것이 작다. 그 경우 광고가 한 번 더 나오는 것은 감수한다.
 *
 * ## 나가지 못한 요청은 세지 않는다
 *
 * 래퍼가 false를 주면(브라우저 단독·광고를 모르는 앱) 기록하지 않는다. 다음 마운트에 다시
 * 시도해도 결과는 같은 false라 무해하고, "요청이 나간 세션"만 기록하는 편이 이 집합의 뜻과
 * 맞는다.
 */

import { showInterstitialAd } from '../bridge/bridge'

/** 전면 광고 요청이 실제로 나간 세션 id들 */
const requestedSessions = new Set<string>()

/**
 * 이 세션에 아직 요청한 적이 없으면 전면 광고를 요청한다. 이번 호출로 요청이 나갔으면 true다 —
 * 이미 나갔거나(두 번째 마운트) 갈 수 없으면(브리지 없음) false.
 */
export function showInterstitialAdOnce(sessionId: string): boolean {
  if (requestedSessions.has(sessionId)) return false
  if (!showInterstitialAd()) return false
  requestedSessions.add(sessionId)
  return true
}
