/**
 * 브라우저 단독 실행의 맞춤형 광고 동의 저장소 (KAN-197 2단계).
 *
 * 앱에서는 동의의 정본이 네이티브 저장소이고 웹은 `getAdConsent`·`setAdConsent`로 읽고 쓸
 * 뿐이다 (`webview-bridge.md` §8.1). 브리지가 없는 브라우저 단독 실행에는 그 길이 아예 없어서,
 * 방침 10항이 「고르신 선택은 이용자의 브라우저 저장소에 둡니다」라고 약속한 자리가 이
 * 모듈이다 (`docs/wiki/ads-web-adsense.md` §3).
 *
 * ## 진행 스냅샷의 저장소 가드를 빌려 오지 않는다
 *
 * 쿠키를 막은 브라우저는 `window.localStorage` **프로퍼티 접근 자체**가 던진다 —
 * `progress/progressSnapshot.ts`의 [defaultSnapshotStorage]가 한 겹 막아 두는 것이 그 이유이고,
 * 여기도 같은 이유로 같은 모양의 guard를 둔다. 그런데도 그 모듈을 import하지 않는 것은 의존
 * 방향 때문이다: 광고 동의가 진행 상태 모듈에 묶일 이유가 없고, 묶어 두면 진행 쪽이 요구하는
 * 저장소 타입(`key`·`length`까지 갖춘 열거 가능 저장소)이 바뀔 때 광고가 따라 흔들린다.
 * 여기서 만지는 키는 하나이므로 요구하는 것도 [WebAdConsentStorage] 두 메서드뿐이다.
 *
 * ## 메모리 사본이 저장소보다 앞선다
 *
 * [writeWebAdConsent]는 **항상** 모듈 안의 사본에 먼저 쓰고 그다음에 localStorage를 시도한다.
 * 그래서 돌려주는 값도 늘 true다 — 앱 쪽 [writeAdConsent]의 true가 "네이티브에 닿았다"인 것과
 * 대칭이 아니라, 이쪽은 "메모리에는 반드시 닿는다"는 뜻이다.
 *
 * 사본을 두는 이유는 저장소가 없는 환경(사생활 모드·쿼터 초과·쿠키 차단)이다. 저장이
 * 실패했다고 false를 돌려주면 시트가 닫히지 않고 「맞춤형 광고 설정」 링크도 생기지 않아,
 * 사용자는 고르기를 반복하는데 화면은 꿈쩍도 하지 않는다. 적어도 **이번 방문 안에서는**
 * 선택이 지켜지는 편이 맞다. 다음 방문에 다시 묻게 되는 것은 방침이 약속한 범위 그대로다 —
 * 저장 위치가 브라우저 저장소라고 적었으니, 그 저장소가 없으면 기억도 없다.
 */

import type { AdConsent, AdConsentChoice } from '../bridge/bridge'

/**
 * 저장 키. 접두어(`accentury:`)는 진행 스냅샷(`accentury:progress`)과 같은 것을 쓴다 —
 * 같은 오리진에 다른 기능이 쓰는 키와 섞이지 않게 하는 규칙이 하나뿐이어야 한다.
 */
export const WEB_AD_CONSENT_KEY = 'accentury:adConsent'

/**
 * 이 모듈이 저장소에 요구하는 최소 인터페이스. `Storage` 전체가 아니라 쓰는 두 메서드만
 * 받는다 — 테스트 대역을 가볍게 만들기 위해서다 (`SnapshotStorage`와 같은 판단).
 */
export interface WebAdConsentStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

/** 저장소가 아예 없거나 닿을 수 없는 환경에서 쓰는 빈 저장소. 선택은 메모리 사본으로만 산다 */
const NO_STORAGE: WebAdConsentStorage = {
  getItem: () => null,
  setItem: () => {},
}

/**
 * 브라우저에서 쓰는 기본 저장소. 프로퍼티 접근이 던지는 경우(쿠키 차단)와 전역 자체가 없는
 * 경우(localStorage 없이 도는 런타임) 둘 다 [NO_STORAGE]로 접는다.
 */
function defaultWebAdConsentStorage(): WebAdConsentStorage {
  try {
    return window.localStorage ?? NO_STORAGE
  } catch {
    return NO_STORAGE
  }
}

/**
 * 이번 방문의 사본. null이면 아직 이 실행에서 고른 적이 없다는 뜻이고, 그때만 저장소를 본다.
 */
let memory: AdConsent | null = null

/**
 * 저장된 선택을 읽는다. 고른 적이 없거나 읽을 수 없으면 `unknown` — 곧 "물어야 한다"다.
 *
 * ## 계약 밖 문자열을 `unknown`으로 접는다 (브리지와 다른 규칙)
 *
 * 브리지 쪽 [readAdConsent]는 모르는 문자열을 null로 접는다. 계약이 어긋난 앱이라면 쓰기도
 * 어긋나 있을 가능성이 높아, 시트를 띄워 봐야 사용자가 고른 값이 저장되지 않고 실행마다 다시
 * 묻게 되기 때문이다. 웹 저장소의 깨진 값은 사정이 다르다 — 우리 자신이 남긴 옛 값이거나
 * 사용자가 개발자 도구로 만진 것이고, 쓰기 경로는 멀쩡하다. 다시 물으면 그다음부터 제대로
 * 된 값이 들어가므로 `unknown`으로 접는 편이 맞다.
 */
export function readWebAdConsent(storage: WebAdConsentStorage = defaultWebAdConsentStorage()): AdConsent {
  if (memory !== null) return memory
  try {
    const raw = storage.getItem(WEB_AD_CONSENT_KEY)
    return raw === 'granted' || raw === 'denied' ? raw : 'unknown'
  } catch {
    return 'unknown'
  }
}

/**
 * 고른 값을 저장한다. 항상 true다 — 헤더 주석의 「메모리 사본이 저장소보다 앞선다」 참고.
 *
 * 반환값을 없애고 void로 두지 않은 이유는 호출처(`useAdConsent.choose`)가 앱 경로와 같은
 * 모양으로 읽히게 하기 위해서다. 쓰기가 닿았으면 사본을 갱신한다는 규칙 하나가 두 경로에
 * 같이 서 있는 편이, 경로마다 다른 문장을 읽는 것보다 낫다.
 */
export function writeWebAdConsent(
  state: AdConsentChoice,
  storage: WebAdConsentStorage = defaultWebAdConsentStorage(),
): boolean {
  memory = state
  try {
    storage.setItem(WEB_AD_CONSENT_KEY, state)
  } catch {
    // 저장소가 막힌 환경(사생활 모드·쿼터 초과). 이번 방문은 사본으로 이어진다.
  }
  return true
}

/**
 * 메모리 사본을 비운다. **테스트 전용**이다 — 모듈 변수라 테스트 사이에 그대로 남아, 앞
 * 테스트가 고른 값이 다음 테스트의 첫 화면을 바꾼다.
 *
 * 이름에 `__` 같은 접두를 붙이는 대신 용도를 이름으로 말한다. 제품 코드에서 부를 일은 없지만,
 * 부르더라도 하는 일은 "저장소를 다시 보게 한다"뿐이라 위험하지 않다.
 */
export function resetWebAdConsentMemory(): void {
  memory = null
}
