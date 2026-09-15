/**
 * 계측 이벤트 전송 (KAN-33) — 스키마는 `events.ts`, 여기는 **어디로 보내는가**만 안다.
 *
 * 보내는 곳이 실행에 따라 갈린다.
 *
 * | 실행 | 경로 | 이유 |
 * |---|---|---|
 * | 앱 안 (WebView) | 브리지 → 네이티브 Firebase | 앱 이벤트는 앱 스트림에 쌓여야 하고, 크래시·세션과 같은 SDK가 붙여 주는 축(기기·OS·앱 버전)을 웹이 만들 수 없다 |
 * | 웹 단독 실행 | gtag → GA4 웹 스트림 | 브라우저에는 브리지가 없다 |
 * | 계측을 모르는 구버전 앱 | 버림 | 웹 경로로 흘려보내면 앱 사용자가 웹 트래픽으로 세어진다 |
 * | 계측이 없는 빌드 | 아무것도 안 함 | 측정 ID를 모르는 로컬·CI 빌드가 정상 상태다 |
 *
 * **같은 사건이 두 경로로 가지 않는다**는 것이 이 분기의 요점이다. 브리지가 받아 갔으면
 * 거기서 끝내고, 앱 안에서는 GA4 태그 자체를 설치하지 않는다 (`main.tsx`).
 *
 * 화면 코드는 이 갈래를 몰라도 된다 — 호출자는 [track] 하나만 부른다. KAN-31이 이 창구를
 * 미리 세워 둔 이유가 그것이고, 그래서 이번 작업이 화면 코드를 거의 건드리지 않는다.
 */

import { isStandaloneWeb, logAnalyticsEvent } from '../bridge/bridge'
import type { AnalyticsEvent } from './events'
import { currentTestId } from './testId'

/**
 * 이벤트 하나를 흘려보낸다.
 *
 * **절대 던지지 않는다.** 호출자는 전부 사용자 흐름의 한복판이다 — 인트로 렌더, 세션 생성
 * 직후, 결과 도착, 공유 버튼 탭. 계측 코드의 예외가 그 자리에서 튀면 계측값 하나 때문에
 * 응시나 설치 전환이 끊긴다. 계측은 실패해도 되는 일이고 나머지는 아니다 (유입 코드가
 * 망가졌을 때 400으로 막지 않는 `sanitizeCampaignToken`과 같은 판단이다).
 */
export function track(event: AnalyticsEvent): void {
  try {
    if (import.meta.env.DEV) {
      // 개발에서 퍼널이 실제로 도는지 눈으로 확인할 통로. 태그도 브리지도 없는 로컬에서는
      // 이것이 유일한 흔적이다.
      console.debug('[track]', event)
    }

    const { name, ...eventParams } = event

    /*
     * 응시 상관 키를 공통으로 얹는다 (KAN-33 AC 1). 이벤트 유니온에 필드로 두지 않는 이유는
     * 지점마다 다시 적어야 하는 값이 아니기 때문이다 — 어느 응시에서 일어났는가는 모든
     * 이벤트에 똑같이 걸리는 사실이고, 지점이 하나 늘 때 그 필드만 빠뜨리면 그 이벤트는
     * 순서 분석에서 조용히 사라진다.
     *
     * 세션이 생기기 전(인트로 유입)에는 값이 없다. 그 이벤트는 아직 어느 응시에도 속하지
     * 않으므로 없는 것이 맞다 (`testId.ts`).
     */
    const testId = currentTestId()
    const params: Record<string, unknown> =
      testId === null ? eventParams : { ...eventParams, test_id: testId }

    // 앱 안이면 네이티브가 가져간다. 여기서 끝내는 것이 이중 집계를 막는 자리다.
    if (logAnalyticsEvent(name, params)) return

    /*
     * 브리지가 받아 가지 못했는데 앱 안이라면 **버린다.** `logEvent`를 모르는 구버전 앱이
     * 이 자리인데, 그 이벤트를 웹 경로로 흘려보내면 앱 사용자가 웹 트래픽으로 세어져 앱·웹
     * 집계가 섞인다 — 계측 하나를 잃는 편이 두 스트림을 오염시키는 것보다 낫다.
     *
     * 실물에서는 앱 안에 gtag 자체가 없어(`main.tsx`) 이 판정 없이도 같은 결과가 나오지만,
     * 그 사실은 다른 파일의 사정이다. 여기서 막아 두면 태그 설치 지점이 바뀌어도 규칙이 남는다.
     */
    if (!isStandaloneWeb(window.location.search)) return

    /*
     * 웹 단독 실행. 태그가 없는 빌드(측정 ID 미설정)와 광고 차단기가 스크립트를 막은
     * 브라우저가 같은 자리인데, 둘 다 "보낼 곳이 없다"는 같은 사실이고 대응도 같다.
     */
    window.gtag?.('event', name, params)
  } catch {
    /*
     * 브리지가 던지거나(구버전 앱의 이상 구현), gtag 큐가 태그 내부에서 던지는 경우다.
     * 어느 쪽도 화면이 할 수 있는 일이 없고, 알려서 좋을 사람도 없다.
     */
  }
}
