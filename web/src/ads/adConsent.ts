/**
 * 맞춤형 광고 동의 상태 훅 (KAN-196, 웹 갈래는 KAN-197 2단계).
 *
 * 브리지 래퍼([readAdConsent]·[writeAdConsent])는 `bridge/bridge.ts`에 있다 — 브리지 호출은
 * 반드시 그 모듈을 거친다는 규칙 때문이다. 여기서 하는 일은 그 값을 화면이 쓸 수 있는
 * 상태로 들고 있는 것과, **어느 저장소에 물을지 고르는 것**이다.
 *
 * ## 저장소가 실행 환경마다 다르다
 *
 * 앱 안이면 정본이 네이티브 저장소(SharedPreferences·UserDefaults)이고 SDK 초기화가 거기서
 * 읽는다 (`AccenturyBridge.getAdConsent` 주석). 브라우저 단독 실행이면 닿을 네이티브가 없어
 * 브라우저 저장소가 정본이다 (`ads/webAdConsentStore.ts`, `webview-bridge.md` §8.1). 갈리는
 * 자리는 [resolveAdConsentSource] 한 곳이고, 사업자 문안(`AdVendor`)도 같은 판정을 따라간다 —
 * 네이티브 SDK는 AdMob, 브라우저는 AdSense다 (`docs/wiki/ads-web-adsense.md`).
 *
 * 어느 쪽이든 이 훅이 들고 있는 것은 마운트 때 한 번 읽어 둔 **사본**이다. 사용자가 고르면
 * 쓰기가 닿은 경우에만 사본을 갱신한다 — 쓰기가 안 닿았는데(메서드가 없는 앱) 사본만 바꾸면
 * 화면은 "허용됨"인데 SDK는 모르는 상태가 된다. 다시 읽지 않는 이유는 쓰기가 동기라 되읽어도
 * 같은 값이고, 되읽기가 실패하는 경우(계약 밖 문자열)에 사본이 null로 떨어져 방금 고른 시트가
 * 링크째 사라지는 것이 더 이상하기 때문이다.
 *
 * ## null은 상태가 아니라 부재다
 *
 * `consent === null`이면 이 실행에는 광고 동의라는 개념이 없다 — 화면은 시트도 링크도 그리지
 * 않는다. `unknown`과 다르다: 그쪽은 "물어야 한다"는 뜻이다.
 *
 * 부재의 범위가 KAN-197 2단계에 좁아졌다. 브라우저 단독 실행은 이제 부재가 아니라 웹 저장소에
 * 묻는 경로다. 남은 부재는 [resolveAdConsentSource]의 `'none'`뿐이다 — 앱 WebView로 보이는데
 * (`?bridge=`가 있거나 객체가 있다) 광고 동의 메서드를 모르는 실행, 곧 구버전 앱이다.
 */

import { useCallback, useState } from 'react'
import {
  isStandaloneWeb,
  readAdConsent,
  writeAdConsent,
  type AccenturyBridge,
  type AdConsent,
  type AdConsentChoice,
} from '../bridge/bridge'
import type { AdVendor } from './adConsentText'
import { applyAdConsentToAdSense } from './adsense'
import { readWebAdConsent, writeWebAdConsent } from './webAdConsentStore'

/**
 * 동의를 어디에 묻고 어디에 둘 것인가.
 *
 * - `bridge`: 네이티브 저장소. 앱 안이고 광고 동의 메서드를 아는 실행이다
 * - `web`: 브라우저 저장소. 앱이 아닌 실행이다 (KAN-197)
 * - `none`: 물을 곳이 없다. 앱 WebView인데 메서드를 모르는 구버전 앱이다
 */
export type AdConsentSource = 'bridge' | 'web' | 'none'

/**
 * 실행 환경을 보고 저장소를 고른다. 훅 밖의 순수 함수인 이유는 세 갈래가 각각 어떤 실행을
 * 뜻하는지가 렌더링과 무관한 판정이라, 렌더 없이 그대로 확인할 수 있어야 하기 때문이다.
 *
 * **메서드의 유무를 객체의 유무보다 먼저 본다.** 브리지 객체가 있고 `getAdConsent`도 있으면
 * 그 앱이 정본을 들고 있는 것이 확실하다 — 웹 저장소를 볼 이유가 없다. 그다음이
 * [isStandaloneWeb]인데, 그 판정은 객체도 `?bridge=`도 없을 때만 참이라
 * (`bridge.ts` 주석) 나머지 조합은 전부 `'none'`으로 떨어진다: `?bridge=`는 있는데 객체가
 * 없는 WebView, 객체는 있는데 메서드를 모르는 구버전 앱 둘이다. 그 둘을 웹으로 보내면
 * 앱 안에서 브라우저 저장소에 동의를 적게 되고, 정작 SDK를 세우는 네이티브는 그 값을 모른다.
 */
export function resolveAdConsentSource(
  search: string = window.location.search,
  bridge: AccenturyBridge | undefined = window.AccenturyBridge,
): AdConsentSource {
  if (typeof bridge?.getAdConsent === 'function') return 'bridge'
  return isStandaloneWeb(search, bridge) ? 'web' : 'none'
}

export interface AdConsentControl {
  /** 지금 상태. null이면 이 실행에 광고 동의 개념이 없다 (시트도 링크도 없다) */
  consent: AdConsent | null
  /** 사용자가 골랐다. 이 실행의 저장소에 쓰고, 닿았으면 [consent]도 그 값이 된다 */
  choose: (state: AdConsentChoice) => void
  /** 시트 문안이 쓸 광고 사업자. 앱은 AdMob, 브라우저 단독 실행은 AdSense다 (KAN-197) */
  vendor: AdVendor
}

export function useAdConsent(): AdConsentControl {
  /*
   * 판정도 초기 읽기도 초기값 함수로 한 번만 한다 — 렌더마다 브리지나 저장소를 볼 이유가 없고,
   * 실행 환경은 이 컴포넌트가 사는 동안 바뀌지 않는다. 값이 바뀌는 경로는 [choose]뿐이다.
   */
  const [source] = useState(resolveAdConsentSource)
  const [consent, setConsent] = useState<AdConsent | null>(() => {
    if (source === 'bridge') return readAdConsent()
    return source === 'web' ? readWebAdConsent() : null
  })

  const choose = useCallback(
    (state: AdConsentChoice) => {
      const stored = source === 'web' ? writeWebAdConsent(state) : writeAdConsent(state)
      /*
       * 웹은 저장 말고 할 일이 하나 더 있다 (KAN-197 3단계). 앱은 네이티브가 `setAdConsent`를
       * 받아 SDK를 다시 세우지만, 웹에서 SDK에 해당하는 것은 이 문서에 이미 선 adsbygoogle 큐다 —
       * 그쪽 플래그를 갈아 끼우지 않으면 저장 값과 다음 광고 요청이 어긋난다. 태그가 아직 안
       * 선 인트로에서는 아무 일도 없다 (`applyAdConsentToAdSense`가 큐 없으면 no-op).
       */
      if (source === 'web') applyAdConsentToAdSense(state)
      if (stored) setConsent(state)
    },
    [source],
  )

  /*
   * `'none'`의 사업자는 의미가 없다 — consent가 null이라 시트가 그려지지 않는다. 그래도 앱
   * 기본값(admob)을 주는 것은 `AdConsentSheet`의 prop 기본값과 같은 값을 말하기 위해서다.
   */
  return { consent, choose, vendor: source === 'web' ? 'adsense' : 'admob' }
}
