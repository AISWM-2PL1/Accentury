/**
 * 스토어 폴백 링크 (KAN-56 Stage 2).
 *
 * 브라우저에서 녹음이 막힌 사용자에게 남는 유일한 출구가 앱이라, 그 링크를 고르는 규칙을
 * 한 곳에 둔다. **KAN-31 결과 화면의 [앱 다운로드] CTA도 이 함수를 써야 한다** — 스토어 URL이
 * 두 군데 하드코딩되면 앱 패키지명이 바뀌는 날 한쪽만 고쳐진다.
 */

export type StorePlatform = 'android' | 'ios' | 'unknown'

/**
 * 안드로이드 스토어 URL 기본값. 패키지명은 앱 모듈의 `applicationId`와 같아야 한다.
 * 배포에서는 `VITE_PLAY_STORE_URL`로 덮어쓴다.
 */
export const DEFAULT_PLAY_STORE_URL = 'https://play.google.com/store/apps/details?id=com.accentury.app'

/**
 * iOS 스토어 URL 기본값 — **아직 앱 ID가 없어 스토어 첫 화면을 가리키는 자리표시자다.**
 * iOS 앱이 등록되면 `VITE_APP_STORE_URL`(또는 이 상수)을 `.../app/idXXXXXXXXX`로 바꾼다.
 */
export const DEFAULT_APP_STORE_URL = 'https://apps.apple.com/'

/**
 * User-Agent로 스토어 대상을 고른다.
 *
 * @param maxTouchPoints `navigator.maxTouchPoints`. iPadOS 13부터 사파리가 **데스크톱 맥과
 *   똑같은 UA("Macintosh…")** 를 보내기 때문에 UA 문자열만으로는 아이패드를 구분할 수 없다.
 *   맥에는 터치 스크린이 없다는 사실이 남은 유일한 단서라 이 값을 같이 본다 — 애플이 공식
 *   문서에서 안내하는 판별법이기도 하다.
 */
export function detectStorePlatform(userAgent: string, maxTouchPoints = 0): StorePlatform {
  // 안드로이드를 먼저 본다. 안드로이드 UA에도 "Linux"·"Mobile" 같은 공통 토큰이 많아
  // 다른 조건이 먼저 걸리면 오판이 생긴다.
  if (/Android/i.test(userAgent)) return 'android'
  if (/iPhone|iPad|iPod/i.test(userAgent)) return 'ios'
  if (/Macintosh/i.test(userAgent) && maxTouchPoints > 1) return 'ios'
  return 'unknown'
}

/**
 * 플랫폼별 스토어 URL.
 *
 * `unknown`은 플레이스토어로 보낸다. 이 화면에 닿는 사용자는 대부분 모바일 브라우저이고,
 * iOS가 아닌 모바일은 사실상 전부 안드로이드다. 데스크톱 사용자는 애초에 대상이 아니라
 * (테스트는 모바일 전제다) 어느 쪽으로 보내도 크게 다르지 않다 — 다수를 맞히는 쪽을 고른다.
 */
export function storeUrlFor(platform: StorePlatform): string {
  const play = (import.meta.env.VITE_PLAY_STORE_URL as string | undefined) ?? DEFAULT_PLAY_STORE_URL
  const app = (import.meta.env.VITE_APP_STORE_URL as string | undefined) ?? DEFAULT_APP_STORE_URL
  return platform === 'ios' ? app : play
}

/**
 * 스토어 이름 — 링크 아래 "어디로 가는지"를 적는 한 줄에 쓴다.
 *
 * URL과 같은 자리에 두는 이유가 URL을 여기 둔 이유와 같다. 이름과 링크가 갈리면 아이폰에서
 * "Play 스토어로 이동해요"라고 적어 놓고 App Store를 여는 화면이 만들어진다 —
 * `unknown`을 플레이스토어로 보내는 [storeUrlFor]의 판단이 이 함수에도 그대로 걸린다.
 */
export function storeLabelFor(platform: StorePlatform): string {
  return platform === 'ios' ? 'App Store' : 'Play 스토어'
}
