/**
 * AdSense 태그 설치와 요청 플래그 (KAN-197 3단계) — **브라우저 단독 실행의 광고 경로**다.
 *
 * 앱 안(WebView)에서는 설치하지 않는다. GA4 태그(`analytics/ga4.ts`)와 같은 게이트
 * (`isStandaloneWeb`)를 쓰지만 이유는 다르다 — GA4는 같은 사건을 두 번 세지 않으려고 막고,
 * 이쪽은 **정책 때문에** 막는다: AdSense 광고 태그를 앱 WebView 안에서 돌리는 것은 허용되지
 * 않는다 (`docs/wiki/ads-web-adsense.md` §2). 앱 안의 광고는 AdMob SDK가 네이티브에서 띄운다.
 *
 * ## 태그를 언제 심는가 — `main.tsx`가 아니라 슬롯이 설 때다
 *
 * 지라는 "GA4와 같은 규칙, `main.tsx`"라고 적었지만 그 규칙의 본질은 **게이트**이지 호출
 * 위치가 아니다. 설치를 분석 대기 화면의 슬롯 마운트(`AdSlot.tsx`)로 늦춘 근거가 둘이다.
 *
 * - 인트로의 첫 렌더에 외부 스크립트가 끼지 않는다. 첫 화면의 체감 속도는 KAN-179가 손본
 *   자리이고, 광고 스크립트는 그 화면에서 아무것도 그리지 않으면서 네트워크만 먹는다
 * - **묻기 전에 광고 서버에 접속하는 경로가 구조적으로 없다.** 동의 시트는 인트로 위에 뜨므로
 *   (`intro/IntroScreen.tsx`), 대기 화면까지 온 사용자는 이미 고른 뒤다. 아래
 *   [adSenseRequestFlags]의 `pauseAdRequests`는 그래서 방어용으로만 남는다
 *
 * ## 두 ID가 다 있어야 한다
 *
 * `VITE_ADSENSE_CLIENT_ID`·`VITE_ADSENSE_SLOT_ID` 중 하나라도 비면 아무것도 하지 않는다 —
 * 태그도, 슬롯도 없다. GA4와 같은 규칙이고(`installGa4Tag`가 측정 ID 없이 false를 돌려준다),
 * AdMob이 "값이 없으면 테스트 ID로 켜 둔다"와 갈리는 근거는 위키 §6에 있다: AdSense는 승인된
 * 사이트에만 광고를 내려주므로 테스트 ID를 넣어도 얻는 것이 없다.
 *
 * ## 절대 던지지 않는다
 *
 * 이 모듈의 함수는 전부 성공 여부를 boolean으로 돌려줄 뿐 예외를 밖으로 내보내지 않는다.
 * 광고 로드 실패·DOM 예외·중복 push가 응시 흐름을 막으면 안 되기 때문이다 — 공유 모듈과 같은
 * 규칙이다. 호출자(`AdSlot`)는 돌아온 false로 아무것도 하지 않는다: 광고가 없는 화면은 광고가
 * 있는 화면과 똑같이 동작해야 한다.
 *
 * ## 검증은 요청 URL로 한다
 *
 * 거부한 방문의 광고 요청 URL에 `npa=1`이 붙는다 (개발자 도구 Network의 `googleads`/`pagead`).
 * `ppt=1`은 우리가 쓰지 않는 별개 플래그의 확인 값이라 찾아도 나오지 않는다 (위키 §4).
 *
 * 공식 문서 — 기억이 아니라 아래 페이지를 읽고 적었다.
 * - https://support.google.com/adsense/answer/9042142 (비맞춤 요청·요청 일시정지)
 * - https://support.google.com/adsense/answer/7670312 (`npa=1` 확인)
 * - https://support.google.com/adsense/answer/9007336 (거부해도 남는 쿠키 용도)
 */

import type { AdConsent } from '../bridge/bridge'

/** 태그 스크립트 주소. 뒤에 `client` 값이 붙는다 (AdSense 공식 스니펫) */
export const ADSENSE_SCRIPT_ORIGIN =
  'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client='

/**
 * adsbygoogle 큐. 배열이면서 플래그를 프로퍼티로 달고 다니는 것이 공식 스니펫의 모양이다 —
 * `(adsbygoogle = window.adsbygoogle || []).requestNonPersonalizedAds = 1`이 그 배열에 값을
 * 얹는다. 태그 스크립트가 로드되면 쌓인 항목을 처리하면서 이 플래그를 함께 읽는다.
 */
type AdSenseQueue = unknown[] & {
  requestNonPersonalizedAds?: 0 | 1
  pauseAdRequests?: 0 | 1
}

declare global {
  interface Window {
    /** [installAdSenseTag]가 세운다. 없으면 광고 태그가 붙지 않은 실행이라는 뜻이다 */
    adsbygoogle?: AdSenseQueue
  }
}

/** 빌드에 박힌 두 ID */
export interface AdSenseIds {
  /** 게시자 ID (`ca-pub-…`). 스크립트의 `client` 쿼리이자 `<ins>`의 `data-ad-client` */
  clientId: string
  /** 광고 단위 ID. `<ins>`의 `data-ad-slot` */
  slotId: string
}

/**
 * 빌드 변수에서 두 ID를 읽는다. 하나라도 비어 있으면 null — 파일 머리의 「두 ID가 다 있어야
 * 한다」 참고.
 *
 * 매번 읽는 함수로 두는 이유는 `regions.ts`의 [isRegionSelectEnabled]와 같다: 모듈 상수로
 * 잡으면 첫 import 시점의 값이 굳어 테스트가 `vi.stubEnv`로 갈아끼울 자리가 없어진다.
 */
export function adSenseIdsFromEnv(): AdSenseIds | null {
  const clientId = ((import.meta.env.VITE_ADSENSE_CLIENT_ID as string | undefined) ?? '').trim()
  const slotId = ((import.meta.env.VITE_ADSENSE_SLOT_ID as string | undefined) ?? '').trim()
  if (clientId === '' || slotId === '') return null
  return { clientId, slotId }
}

/** 큐에 얹는 두 플래그. 값이 0·1인 것은 공식 스니펫의 표기 그대로다 */
export interface AdSenseRequestFlags {
  requestNonPersonalizedAds: 0 | 1
  pauseAdRequests: 0 | 1
}

/**
 * 동의 상태를 요청 플래그로 옮긴다. 순수 함수로 따로 열어 둔 이유는 이 표가 곧 티켓의 AC라
 * 렌더도 DOM도 없이 그대로 확인할 수 있어야 하기 때문이다.
 *
 * | 동의 | `requestNonPersonalizedAds` | `pauseAdRequests` | 뜻 |
 * |---|---|---|---|
 * | `granted` | 0 | 0 | 맞춤 광고를 요청한다 |
 * | `denied` | 1 | 0 | 요청은 하되 비맞춤으로 — 요청 URL에 `npa=1`이 붙는다 |
 * | `unknown` | 1 | 1 | 요청 자체를 내보내지 않는다 |
 *
 * `unknown`에 `pauseAdRequests=1`을 두는 것은 AdMob의 "unknown이면 요청 없음"과 같은 원칙이다 —
 * 아직 묻지 않았는데 광고 서버에 접속하지 않는다. 같은 줄에서 `requestNonPersonalizedAds`도 1인
 * 이유는 두 플래그가 서로를 보장하지 않아서다: 어떤 경로로든 일시정지가 먼저 풀리면 그 순간
 * 나가는 요청은 남아 있는 npa 값을 따른다.
 *
 * **`pauseAdRequests=0`을 빠뜨리면 광고가 하나도 안 나온다.** 문서가 "Without making this call,
 * no ads will be shown"이라고 못 박았고, 증상이 「빈 슬롯」이라 승인 전이라 비어 있는 것과
 * 구분되지 않는다 (위키 §4). 그래서 허용·거부 두 갈래 모두 0을 거치는 것을 테스트가 붙든다.
 */
export function adSenseRequestFlags(consent: AdConsent): AdSenseRequestFlags {
  if (consent === 'granted') return { requestNonPersonalizedAds: 0, pauseAdRequests: 0 }
  if (consent === 'denied') return { requestNonPersonalizedAds: 1, pauseAdRequests: 0 }
  return { requestNonPersonalizedAds: 1, pauseAdRequests: 1 }
}

/** 큐에 플래그를 얹는다. 설치와 갱신 두 경로가 같은 한 줄을 쓰게 묶어 둔 것뿐이다 */
function writeFlags(queue: AdSenseQueue, consent: AdConsent): void {
  const flags = adSenseRequestFlags(consent)
  queue.requestNonPersonalizedAds = flags.requestNonPersonalizedAds
  queue.pauseAdRequests = flags.pauseAdRequests
}

/**
 * 이 문서에 태그를 이미 심었는가. `doc.head`에 같은 src의 스크립트가 있는지로 보지 않는 이유는
 * [installGa4Tag]가 `window.gtag`의 유무로 보는 것과 같다 — 우리가 심은 흔적을 우리가 아는
 * 자리에서 확인하는 편이, 남이 붙였을 수도 있는 DOM을 뒤지는 것보다 좁고 정확하다.
 */
let installed = false

/**
 * 큐를 세우고 플래그를 얹고 태그 스크립트를 붙인다. 실제로 붙였으면 true.
 *
 * false가 돌아오는 경우가 셋이다 — ID가 없는 빌드, 이미 설치된 경우(StrictMode의 이펙트
 * 재실행), DOM 조작이 던진 경우다. 셋 다 오류가 아니라 흔한 상태라 던지지 않는다.
 *
 * **순서가 계약이다.** 큐와 플래그를 스크립트보다 먼저 세운다. 문서의 말이 "이 줄은
 * `adsbygoogle.push({})`보다 먼저 와야 한다"이고, 스크립트가 로드되는 시점에 큐가 이미 플래그를
 * 달고 있어야 첫 요청부터 그 값을 따른다.
 *
 * @param consent 이 방문의 동의 상태. 플래그가 이 값에서 나온다 ([adSenseRequestFlags])
 * @param ids 기본값은 빌드에 박힌 값. 테스트가 갈아끼울 자리다
 * @param doc 스크립트를 붙일 문서. 같은 이유로 주입 지점을 둔다
 */
export function installAdSenseTag(
  consent: AdConsent,
  ids: AdSenseIds | null = adSenseIdsFromEnv(),
  doc: Document = document,
): boolean {
  if (ids === null) return false
  if (installed || window.adsbygoogle !== undefined) return false

  try {
    const queue: AdSenseQueue = []
    writeFlags(queue, consent)
    window.adsbygoogle = queue

    const script = doc.createElement('script')
    script.async = true
    script.src = ADSENSE_SCRIPT_ORIGIN + encodeURIComponent(ids.clientId)
    // 공식 스니펫 그대로다. 스크립트가 교차 출처라 이 속성이 없으면 오류 보고가 익명화된다
    script.crossOrigin = 'anonymous'
    doc.head.appendChild(script)
  } catch {
    /*
     * DOM이 막힌 환경. 큐가 이미 섰을 수는 있는데 그대로 둔다 — 스크립트 없는 큐는 아무 일도
     * 하지 않는 배열이고, 되돌리려고 `window.adsbygoogle`을 지우면 이 실패를 감춘 채 다음
     * 호출이 같은 일을 또 시도하게 된다.
     */
    return false
  }

  installed = true
  return true
}

/**
 * 이미 선 큐의 플래그만 갈아 끼운다. 큐가 없으면 아무 일도 없다.
 *
 * 시트에서 선택을 바꾼 순간 부른다 (`adConsent.ts`의 `choose`). 이미 나간 요청은 되돌릴 수
 * 없지만 **다음 요청부터는** 새 값을 따른다 — 방침 10항이 약속한 철회 경로가 코드에서 닿는
 * 자리가 여기다.
 *
 * 큐가 없으면 no-op이라는 것이 이 함수의 쓰임 절반이다. 시트는 인트로에 뜨고 태그는 대기
 * 화면에서 서므로, 실제로는 대부분의 호출이 아무 일도 하지 않는다. 그래도 부르는 이유는 "동의가
 * 바뀌면 SDK에 알린다"는 규칙이 앱(브리지 `setAdConsent`)과 웹에 같은 모양으로 서 있어야 하기
 * 때문이다.
 *
 * **쓰기가 던져도 삼킨다** (리뷰 P1-3, 2026-09-13). 이 파일 머리의 「절대 던지지 않는다」가
 * 이 함수에만 빠져 있었다. 큐는 남이 만들었을 수도 있는 배열이라 — 차단 확장이 동결해 두거나
 * 대역으로 갈아끼운 경우가 실제로 있다 — 프로퍼티 대입 한 줄이 TypeError를 낼 수 있다. 이
 * 함수를 부르는 자리가 시트의 `choose`라(`adConsent.ts`), 거기서 예외가 오르면 사용자가 선택을
 * 바꾼 순간 인트로가 통째로 날아간다. 동의를 저장하는 일과 큐에 알리는 일은 따로여야 한다.
 *
 * @returns 큐의 플래그를 실제로 갈아 끼웠으면 true. 큐가 없거나 쓰기가 막혔으면 false —
 *   호출자가 할 일은 없고(저장은 이미 끝났다) 보고 값에 가깝다.
 */
export function applyAdConsentToAdSense(consent: AdConsent): boolean {
  const queue = window.adsbygoogle
  if (queue === undefined) return false

  try {
    writeFlags(queue, consent)
  } catch {
    /*
     * 한쪽만 써지고 던졌을 수 있다. 되돌리지 않는 이유는 [installAdSenseTag]의 catch와 같다 —
     * 쓰기가 막힌 큐에 되돌리는 쓰기도 막히고, 어느 쪽이든 이 실행의 광고 요청은 우리가
     * 통제하지 못하는 상태다.
     */
    return false
  }

  return true
}

/**
 * 슬롯 하나를 요청한다. 큐에 밀어 넣었으면 true.
 *
 * 큐가 없으면 만들어서 넣는다 — 공식 스니펫의 `(adsbygoogle = window.adsbygoogle || []).push({})`
 * 그대로다. 태그가 없는 실행에서는 그 배열에 객체 하나가 쌓일 뿐 아무 일도 일어나지 않는다.
 *
 * try/catch가 필요한 이유는 태그가 **로드된 뒤의** push가 동기적으로 던지기 때문이다. 같은
 * `<ins>`에 두 번 밀어 넣으면 "adsbygoogle.push: All ins elements in the DOM with class=adsbygoogle
 * already have ads in them"이 올라온다 — StrictMode의 이펙트 이중 실행이 정확히 그 모양이라,
 * 개발 빌드에서 터지는 예외 하나로 대기 화면이 통째로 날아가면 안 된다.
 */
export function pushAdSlot(): boolean {
  try {
    const queue: AdSenseQueue = window.adsbygoogle ?? []
    window.adsbygoogle = queue
    queue.push({})
    return true
  } catch {
    return false
  }
}

/**
 * 설치 표식을 되돌린다. **테스트 전용**이다 — 모듈 변수라 테스트 사이에 그대로 남아, 앞
 * 테스트가 설치한 사실이 다음 테스트의 첫 호출을 false로 만든다.
 *
 * `resetWebAdConsentMemory`와 같은 판단으로 이름에 용도를 적는다. 제품 코드에서 부를 일은 없고,
 * 부르더라도 하는 일은 "한 번 더 심어 볼 수 있게 한다"뿐이다.
 */
export function resetAdSenseForTests(): void {
  installed = false
}
