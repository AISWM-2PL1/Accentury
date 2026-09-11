/**
 * 맞춤형 광고 동의 상태 훅 (KAN-196).
 *
 * 브리지 래퍼([readAdConsent]·[writeAdConsent])는 `bridge/bridge.ts`에 있다 — 브리지 호출은
 * 반드시 그 모듈을 거친다는 규칙 때문이다. 여기서 하는 일은 그 값을 화면이 쓸 수 있는
 * 상태로 들고 있는 것뿐이다.
 *
 * ## 정본은 네이티브, 이 훅은 사본
 *
 * 동의는 네이티브 저장소(SharedPreferences·UserDefaults)에 있고 SDK 초기화가 거기서 읽는다
 * (`AccenturyBridge.getAdConsent` 주석). 이 훅은 마운트 때 한 번 읽어 두고, 사용자가 고르면
 * 쓰기가 닿은 경우에만 사본을 갱신한다 — 쓰기가 안 닿았는데(메서드가 없는 앱) 사본만 바꾸면
 * 화면은 "허용됨"인데 SDK는 모르는 상태가 된다. 다시 읽지 않는 이유는 쓰기가 동기라 되읽어도
 * 같은 값이고, 되읽기가 실패하는 경우(계약 밖 문자열)에 사본이 null로 떨어져 방금 고른 시트가
 * 링크째 사라지는 것이 더 이상하기 때문이다.
 *
 * ## null은 상태가 아니라 부재다
 *
 * `consent === null`이면 이 실행에는 광고 동의라는 개념이 없다 — 웹 단독 실행(KAN-197 범위),
 * 이 메서드를 모르는 앱. 화면은 시트도 링크도 그리지 않는다. `unknown`과 다르다: 그쪽은
 * "물어야 한다"는 뜻이다.
 */

import { useCallback, useState } from 'react'
import { readAdConsent, writeAdConsent, type AdConsent, type AdConsentChoice } from '../bridge/bridge'

export interface AdConsentControl {
  /** 지금 상태. null이면 이 실행에 광고 동의 개념이 없다 (시트도 링크도 없다) */
  consent: AdConsent | null
  /** 사용자가 골랐다. 네이티브에 쓰고, 닿았으면 [consent]도 그 값이 된다 */
  choose: (state: AdConsentChoice) => void
}

export function useAdConsent(): AdConsentControl {
  // 초기값 함수로 읽는다 — 렌더마다 브리지를 부를 이유가 없고, 값이 바뀌는 경로는 [choose]뿐이다.
  const [consent, setConsent] = useState<AdConsent | null>(() => readAdConsent())

  const choose = useCallback((state: AdConsentChoice) => {
    if (writeAdConsent(state)) setConsent(state)
  }, [])

  return { consent, choose }
}
