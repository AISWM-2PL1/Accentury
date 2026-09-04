/**
 * GA4 태그 설치 (KAN-33) — **웹 단독 실행의 전송 경로**다.
 *
 * 앱 안(WebView)에서는 설치하지 않는다. 앱 안 이벤트는 브리지를 건너 네이티브 Firebase가
 * 보내므로(`track.ts`), 여기서도 태그를 깔면 같은 사건이 두 경로로 두 번 세어진다 —
 * 호출자(`main.tsx`)가 웹 단독 실행일 때만 부르는 이유다.
 *
 * ## 측정 ID가 없는 것이 정상 상태다
 *
 * ID는 빌드 시점에 `VITE_GA4_MEASUREMENT_ID`로 들어오고, 없으면 이 함수는 아무것도 하지 않는다.
 * 카카오 앱 키(`app/build.gradle.kts`)와 같은 판단이다 — ID를 모르는 로컬·CI 빌드도 그대로
 * 빌드되고 동작해야 한다. 측정 ID 자체는 페이지 소스에 박히는 공개 값이라 비밀은 아니지만,
 * 레포에 박아 두면 개발·테스트 트래픽이 실사용 집계에 섞인다.
 *
 * ## 왜 gtag.js인가
 *
 * GA4에 이벤트를 직접 보내는 공식 경로다. GTM(태그 매니저)을 쓰면 이벤트 매핑이 코드 밖
 * 콘솔 설정으로 옮겨 가는데, 그러면 이 레포의 이벤트 스키마(`events.ts`)와 실제로 GA4에
 * 도착하는 것 사이에 우리가 볼 수 없는 한 겹이 생긴다.
 */

/** gtag 큐 함수. 인자 모양이 호출마다 다르다(`js`/`config`/`event`)라 가변 인자로 받는다 */
type GtagFn = (...args: unknown[]) => void

declare global {
  interface Window {
    /**
     * gtag가 읽는 큐. 태그 스크립트가 로드되기 전의 호출도 여기 쌓였다가 로드 뒤에 처리된다 —
     * 그래서 스크립트가 늦게 와도(또는 광고 차단기가 막아도) 호출자는 아무것도 신경 쓰지 않는다.
     */
    dataLayer?: unknown[]
    /** [installGa4Tag]가 심는다. 없으면 계측이 붙지 않은 실행이라는 뜻이다 (`track.ts`) */
    gtag?: GtagFn
  }
}

const TAG_ORIGIN = 'https://www.googletagmanager.com/gtag/js?id='

/** 빌드에 박힌 측정 ID (`G-XXXXXXX`). 없으면 undefined다 */
function measurementIdFromEnv(): string | undefined {
  return import.meta.env.VITE_GA4_MEASUREMENT_ID as string | undefined
}

/**
 * gtag 큐를 세우고 태그 스크립트를 붙인다. 실제로 설치했으면 true.
 *
 * false가 돌아오는 경우가 둘이다 — 측정 ID가 없는 빌드, 그리고 이미 설치된 경우(StrictMode의
 * 이펙트 재실행이나 실수로 두 번 부른 경우)다. 둘 다 오류가 아니라 흔한 상태라 던지지 않는다.
 *
 * @param measurementId 기본값은 빌드에 박힌 값. 테스트가 갈아끼울 자리다
 * @param doc 스크립트를 붙일 문서. 같은 이유로 주입 지점을 둔다
 */
export function installGa4Tag(
  measurementId: string | undefined = measurementIdFromEnv(),
  doc: Document = document,
): boolean {
  const id = measurementId?.trim() ?? ''
  if (id === '') return false
  if (typeof window.gtag === 'function') return false

  const queue: unknown[] = window.dataLayer ?? []
  window.dataLayer = queue

  /*
   * 공식 스니펫 그대로 `arguments`를 밀어 넣는다. 화살표 함수나 rest 인자로 배열을 넣는 변형이
   * 도는 경우도 있지만, 태그가 읽는 것은 arguments 객체라 그 변형은 태그 버전에 따라 조용히
   * 무시될 수 있다 — 계측이 안 붙는 실패는 화면에 아무 흔적도 남기지 않으므로 공식 모양을 지킨다.
   */
  const gtag: GtagFn = function () {
    queue.push(arguments)
  }
  window.gtag = gtag

  gtag('js', new Date())
  gtag('config', id, {
    /*
     * 광고 식별자와 구글 신호를 끈다 (KAN-33 요구 "사용자 ID와 광고 식별자를 설정하지 않는다").
     * 기본값은 켜짐이라 명시하지 않으면 리마케팅 목적의 식별자가 함께 수집된다.
     */
    allow_google_signals: false,
    allow_ad_personalization_signals: false,
  })

  const script = doc.createElement('script')
  script.async = true
  script.src = TAG_ORIGIN + encodeURIComponent(id)
  doc.head.appendChild(script)
  return true
}
