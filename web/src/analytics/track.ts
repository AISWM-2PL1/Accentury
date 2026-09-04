/**
 * 유입 퍼널 계측 자리 (KAN-31 3단계).
 *
 * 공유 링크를 탄 사람이 **어디서 빠지는지**를 익명으로 세는 것이 목적이다 —
 * 유입 → 테스트 시작 → 완주 → 다운로드 클릭, 네 지점뿐이다 (KAN-31 AC).
 *
 * 여기에 공유 클릭(KAN-30 3단계, FR-SH-06)이 한 지점 더 붙는다. 퍼널의 끝이 아니라 **다음
 * 퍼널의 시작**이라 따로 세는 값이다 — 이 클릭이 만든 링크를 타고 들어오는 것이 위의
 * `referral_opened`이므로, 둘을 나란히 놓아야 "공유 한 번이 유입 몇 건을 만드는가"가 나온다.
 *
 * ## 이 모듈은 계측을 "보내지" 않는다
 *
 * 실제 전송(GA4 태그 설치, 이벤트 매핑, 동의 처리)은 **KAN-33**이다. 여기서 하는 일은
 * 화면이 "이 일이 일어났다"고 말할 창구를 하나 만들어 두는 것까지다. 그렇게 나눈 이유는
 * KAN-33이 붙을 때 **화면 코드를 한 줄도 건드리지 않게** 하기 위해서다 — 계측 도구는
 * 바뀌지만(GA4 → 다른 것) 퍼널의 네 지점은 그대로다.
 *
 * 웹뷰(앱 안) 실행은 여기로 오지 않는다. 앱 안 이벤트는 네이티브 Firebase가 보내는 것이
 * KAN-33의 결정이라, 같은 사건이 두 경로로 두 번 세어지면 안 된다 — 호출자(App)가 웹 단독
 * 실행일 때만 부른다.
 *
 * ## 무엇을 싣지 않는가
 *
 * 세션 id·세션 토큰·점수는 파라미터에 없다. 개인을 특정할 수 있는 값이 하나라도 섞이면
 * "익명 계측"이라는 전제가 깨지고, 그 값들은 계측 서버에 남을 이유도 없다. `campaign`은
 * 공유 링크가 실어 온 **공용 상수**(`kko_share` 같은 값)라 사람을 가리키지 않는다.
 */

import type { StorePlatform } from '../audio/storeLink'
import type { ShareChannel } from '../share/shareResult'

/**
 * 퍼널의 다섯 지점. 이름은 KAN-33이 GA4에 심을 이벤트명과 같다.
 *
 * 유니온으로 둔 이유: 지점마다 실을 수 있는 값이 다르다 (`platform`은 다운로드 탭에만,
 * `channel`은 공유 탭에만 있다). 하나의 넓은 타입으로 두면 "이 이벤트에 이 값이 왜 있지"를
 * 컴파일러가 못 잡는다.
 */
export type FunnelEvent =
  /** 공유 링크로 웹 테스트를 열었다 */
  | { name: 'referral_opened'; campaign: string | null }
  /** [시작하기]로 세션이 만들어졌다 */
  | { name: 'referral_test_started'; campaign: string | null }
  /** 마지막 문항까지 끝나 결과가 나왔다 */
  | { name: 'test_completed'; campaign: string | null }
  /** 결과 화면의 [앱 다운로드]를 눌렀다 */
  | { name: 'app_download_clicked'; campaign: string | null; platform: StorePlatform }
  /**
   * 결과 화면의 [친구에게 공유하기]를 눌렀다 (FR-SH-06 "공유 버튼 탭"의 **웹 단독 몫**).
   *
   * 앱 안의 같은 탭은 네이티브가 센다 (`analytics/AppEvents.kt`) — 같은 사건을 두 경로로 세면
   * 안 된다는 이 모듈의 규칙이 여기에도 그대로 걸린다. 그래서 호출자는 `standalone`일 때만
   * 부른다.
   *
   * `channel`이 붙는 이유: 브라우저에서 공유 시트로 나간 클릭과 링크 복사로 새는 클릭은 다른
   * 사실이다. 뭉뚱그리면 폴백을 더 깔아야 하는지를 볼 수 없다 (`shareResult`의 [ShareChannel]).
   */
  | { name: 'share_clicked'; campaign: string | null; channel: ShareChannel }

declare global {
  interface Window {
    /**
     * GA4(gtag) 태그가 읽는 큐. **우리가 만들지 않는다** — 태그 스니펫이 만들고, 없으면
     * 계측이 붙지 않은 빌드라는 뜻이다 (KAN-33 이전의 지금이 그 상태다).
     *
     * 브리지 객체를 `Window`에 선언하는 방식(`bridge.ts`)과 같다: 남이 심어 주는 전역이라
     * optional이고, 읽는 쪽이 없을 때를 정상 경로로 다룬다.
     */
    dataLayer?: unknown[]
  }
}

/**
 * 퍼널 이벤트 하나를 흘려보낸다.
 *
 * **절대 던지지 않는다.** 호출자는 전부 사용자 흐름의 한복판이다 — 인트로 렌더, 세션 생성
 * 직후, 결과 도착, 다운로드 링크 탭. 계측 코드의 예외가 그 자리에서 튀면 계측값 하나 때문에
 * 응시나 설치 전환이 끊긴다. 계측은 실패해도 되는 일이고 나머지는 아니다 (유입 코드가
 * 망가졌을 때 400으로 막지 않는 `sanitizeCampaignToken`과 같은 판단이다).
 *
 * 큐가 없으면 아무 일도 하지 않는다. 태그가 없는 빌드(지금)와 광고 차단기가 스니펫을 막은
 * 브라우저가 같은 자리인데, 둘 다 "보낼 곳이 없다"는 같은 사실이고 대응도 같다.
 */
export function track(event: FunnelEvent): void {
  try {
    if (import.meta.env.DEV) {
      // 개발에서 퍼널이 실제로 도는지 눈으로 확인할 유일한 통로다 — 태그가 없어 큐에는
      // 아무것도 쌓이지 않는다.
      console.debug('[track]', event)
    }

    const queue = window.dataLayer
    if (!Array.isArray(queue)) return

    const { name, ...params } = event
    // gtag 규약: 큐에 밀어 넣은 객체의 `event` 키가 이벤트명이고 나머지가 파라미터다.
    queue.push({ event: name, ...params })
  } catch {
    /*
     * 큐가 배열 흉내만 내는 무언가로 바뀌어 있거나(광고 차단기), push가 태그 내부에서
     * 던지는 경우다. 어느 쪽도 화면이 할 수 있는 일이 없고, 알려서 좋을 사람도 없다.
     */
  }
}
