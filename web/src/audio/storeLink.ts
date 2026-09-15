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
 * 이 빌드가 스토어 링크를 살려도 되는가 (사용자 요청 2026-09-15, KAN-211 범위 밖).
 *
 * ## 왜 필요한가
 *
 * 앱이 아직 Play 스토어에도 App Store에도 등록되지 않았다. 위 두 URL은 **등록된 뒤에야**
 * 뜻이 있는 주소라, 지금 [앱 다운로드]를 누르면 "앱을 찾을 수 없습니다"가 뜬다 — 설치를
 * 권해 놓고 없는 페이지로 보내는 것은 아무 출구도 주지 않느니만 못하다. 그래서 등록 전에는
 * 링크 대신 비활성 버튼과 [STORE_PENDING_CAPTION]을 세운다.
 *
 * ## 왜 빌드 변수인가
 *
 * 스토어에 올라가는 날 **코드를 고치지 않고** 켤 수 있어야 하기 때문이다. 등록은 배포와
 * 무관한 날짜에 일어나므로, 그날 필요한 것은 빌드 한 번이지 PR 한 벌이 아니다. 켜는 절차는
 * GitHub environment 변수 `STORE_LISTING_READY=true`를 prod·staging에 **각각** 등록하고
 * 재배포하는 것이다 (`.github/workflows/web-deploy.yml`, 두 환경은 변수를 공유하지 않는다).
 * 로컬에서 켜 보려면 `web/.env.local`에 `VITE_STORE_LISTING_READY=true`.
 *
 * ## 왜 `=== 'true'` 엄격 비교이고 기본이 꺼짐인가
 *
 * 비교 규칙은 [regions.ts]의 `isRegionSelectEnabled`와 같은 이유다 — 워크플로가 GitHub vars를
 * 그대로 넘기면 등록하지 않은 환경에서 `undefined`가 아니라 **빈 문자열**이 들어오고, 잘못
 * 잡은 값(`'1'`, `'false'`, `'TRUE'`)도 문자열이라 전부 truthy다. "정확히 `'true'`일 때만"이라야
 * 어떤 실수도 죽은 링크를 살리는 쪽으로 기울지 않는다.
 *
 * 기본값이 꺼짐인 것도 같은 방향이다. 스토어 등록 전이 지금의 **기본 상태**이고, 켜는 쪽이
 * 의도적인 행동이어야 한다 — 반대로 두면 변수를 빠뜨린 새 환경이 조용히 죽은 링크를 내보낸다.
 *
 * 매번 읽는 함수인 이유도 `regions.ts`와 같다: 모듈 상수로 잡으면 첫 import 시점의 값이 굳어
 * 테스트가 `vi.stubEnv`로 갈아끼울 자리가 없어진다.
 *
 * 꺼진 빌드가 링크 대신 내놓는 안내 문구는 `storeText.ts`에 있다 — 브라우저 E2E가 그 문구를
 * 단언하는데, 이 파일은 `import.meta.env` 때문에 스펙 쪽 타입 설정에서 검사되지 못한다.
 */
export function storeListingReady(): boolean {
  return (import.meta.env.VITE_STORE_LISTING_READY as string | undefined) === 'true'
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
