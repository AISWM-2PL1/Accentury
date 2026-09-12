/**
 * 분석 대기 화면의 AdSense 배너 (KAN-197 3단계).
 *
 * ## 왜 대기 화면인가
 *
 * 웹의 슬롯은 이 하나뿐이다 (`docs/wiki/ads-web-adsense.md` §5). 자리를 여기로 정한 근거가
 * 둘이다 — 이 화면에는 사용자가 눌러야 할 CTA가 없고(시작·공유·앱 다운로드는 전부 다른
 * 화면이다), 폴링이 도는 동안 화면이 그대로 머문다. 광고가 흐름을 가로막지 않으면서 실제로
 * 보이는 시간이 있는 자리가 여기다. 앱의 전면 광고가 같은 화면에서 시작하는 것도 같은
 * 이유다 (`ads/interstitial.ts`).
 *
 * ## 그리지 않는 경우가 둘이다
 *
 * - **앱 WebView**: `isStandaloneWeb`이 거짓이면 `null`을 돌려준다. `<ins>`도 감싼 상자도
 *   없다 — 앱 안에서 AdSense 태그를 돌리는 것은 정책 위반이고(위키 §2), 앱의 광고는 AdMob
 *   SDK가 네이티브에서 띄운다
 * - **ID가 없는 빌드**: 로컬·CI·승인 전 빌드가 여기다. 빈 상자를 남기지 않는 이유는 CLS와
 *   같은 말이다 — 채워질 일이 없는 자리를 100px 비워 두면 단계 표시가 아래로 밀릴 뿐이다
 *
 * **승인 전에는 슬롯이 비는 것이 정상이다.** AdSense 사이트 심사가 끝나기 전에는 태그가
 * 붙어도 광고가 내려오지 않는다 (위키 §1). 판정은 눈이 아니라 개발자 도구의 요청으로 한다.
 *
 * ## 실패해도 화면은 그대로다
 *
 * 이펙트에서 부르는 것은 [installAdSenseTag]와 [pushAdSlot] 둘뿐이고, 둘 다 예외를 밖으로
 * 내보내지 않고 false만 돌려준다 (`adsense.ts` 헤더). 광고 스크립트가 차단기에 막히든 네트워크가
 * 죽든 이 화면은 폴링을 그대로 돌린다.
 */

import { useEffect } from 'react'
import { isStandaloneWeb } from '../bridge/bridge'
import { adSenseIdsFromEnv, installAdSenseTag, pushAdSlot } from './adsense'
import { readWebAdConsent } from './webAdConsentStore'

export function AdSlot() {
  /*
   * 판정에 필요한 것을 렌더에서 읽는다. 실행 환경도 빌드 변수도 이 컴포넌트가 사는 동안
   * 바뀌지 않으므로 값이 흔들릴 일은 없고, 이펙트 의존성에는 아래 `enabled` 불리언 하나만
   * 올린다 — `ids`는 호출마다 새 객체라 그대로 올리면 렌더마다 이펙트가 다시 돈다.
   */
  const ids = adSenseIdsFromEnv()
  const enabled = ids !== null && isStandaloneWeb(window.location.search)

  useEffect(() => {
    if (!enabled) return
    /*
     * 동의는 여기서 읽는다. 훅(`useAdConsent`)을 쓰지 않는 이유는 이 컴포넌트가 시트를 띄우지도
     * 선택을 바꾸지도 않기 때문이다 — 필요한 것은 마운트 시점의 값 하나이고, 그 뒤에 사용자가
     * 시트에서 바꾸면 `applyAdConsentToAdSense`가 이미 선 큐를 갱신한다.
     *
     * 마운트 1회. StrictMode가 이펙트를 두 번 돌려도 설치는 두 번째에 false로 떨어지고
     * (`installAdSenseTag`의 설치 표식), 두 번째 push는 태그가 던지는 중복 예외를
     * `pushAdSlot`이 삼킨다.
     */
    installAdSenseTag(readWebAdConsent())
    pushAdSlot()
  }, [enabled])

  if (!enabled || ids === null) return null

  return (
    /*
     * `role="complementary"`로 본문과 가른다. 이름을 「광고」로 붙이는 것은 고지이기도 하다 —
     * 스크린 리더 사용자가 이 영역을 건너뛸지 스스로 정할 수 있어야 한다. 안쪽 `<ins>`는
     * AdSense가 요구하는 모양 그대로라 우리가 붙일 속성이 없다.
     */
    <div className="ad-slot" role="complementary" aria-label="광고">
      <ins
        className="adsbygoogle"
        style={{ display: 'block' }}
        data-ad-client={ids.clientId}
        data-ad-slot={ids.slotId}
        data-ad-format="auto"
        data-full-width-responsive="true"
      />
    </div>
  )
}
