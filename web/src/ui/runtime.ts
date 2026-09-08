/**
 * 런타임 표식 — 이 문서가 앱 안(WebView)인지 그냥 브라우저인지를 `<html data-runtime>`에 심는다
 * (KAN-199 #3).
 *
 * 판정 자체는 `bridge.ts`의 [isStandaloneWeb]이 하고 여기서는 옮겨 적기만 한다. 근거를 두 곳에
 * 두면 "브리지는 없는데 앱으로 그려진 화면" 같은 조합이 만들어진다.
 *
 * ## 왜 CSS가 읽을 수 있는 자리에 심는가
 *
 * 런타임에 따라 갈리는 것이 배치 값 하나(`--screen-padding-top`)뿐이라 컴포넌트가 분기를
 * 들 이유가 없다. 속성 하나를 문서에 심어 두면 그 값을 쓰는 규칙이 늘어도 CSS 안에서 끝나고,
 * 화면 코드는 자기가 어느 런타임에서 도는지 몰라도 된다.
 *
 * ## 렌더보다 먼저 부르는 이유
 *
 * 표식이 첫 페인트 뒤에 붙으면 앱 기준 여백(64)으로 한 프레임 그려진 화면이 사용자 눈에
 * 보였다가 24로 튄다. `main.tsx`가 `createRoot(...).render` 앞에서 부른다.
 */

import { isStandaloneWeb } from '../bridge/bridge'

/** `<html>`에 심는 속성 이름. CSS 선택자(`:root[data-runtime='browser']`)와 짝이다 */
export type Runtime = 'browser' | 'app'

/**
 * 현재 런타임을 [root]에 심는다.
 *
 * 앱 안이면 `app`, 브라우저 단독이면 `browser`다. 브리지가 없고 `?bridge=`도 없을 때만
 * 브라우저로 보는 것은 [isStandaloneWeb]의 판정 그대로다 — 구버전 앱(객체는 있는데 버전이
 * 없는 경우)을 브라우저로 오인하면 앱 화면의 여백이 조용히 바뀐다.
 */
export function markRuntime(
  search: string,
  root: HTMLElement = document.documentElement,
): Runtime {
  const runtime: Runtime = isStandaloneWeb(search) ? 'browser' : 'app'
  root.dataset.runtime = runtime
  return runtime
}
