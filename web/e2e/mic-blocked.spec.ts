/**
 * 마이크 권한 게이트가 막혔을 때 (KAN-181 3단계) — 인트로에서 [시작하기]를 눌렀는데
 * 마이크를 못 얻는 갈래다.
 *
 * 권한이 없으면 테스트를 시작할 수 없다(API 명세서 §5.6). 그래서 인트로에 오류 문구만 붙이는
 * 것이 아니라 화면 자체를 [MicBlockedScreen]으로 갈아치우는데, 이 스펙이 확인하는 것은
 * 그 교체가 실제 브라우저에서 일어나는가와 **거기서 멈추는가** 둘이다.
 *
 * ## 왜 브라우저까지 와서 보는가
 *
 * 화면 분기 자체는 vitest가 이미 본다(`IntroScreen.test.tsx`). 여기서만 볼 수 있는 것은
 * "막혔을 때 서버에 아무 일도 일어나지 않는다"이다 — 세션 생성은 게이트 **뒤**에 있고
 * (`App.tsx`의 `IntroRoute`), 그 순서가 어긋나면 시작도 못 한 사용자 몫으로 고아 세션이
 * 쌓이고 IP 분당 제한(§2.5) 한 칸씩을 축낸다. 요청이 나갔는지 아닌지는 실제 네트워크가
 * 있는 곳에서만 셀 수 있다.
 *
 * ## 왜 Chromium 플래그가 아니라 `getUserMedia`를 갈아 끼우는가
 *
 * 가짜 마이크 플래그를 빼면(`test.use({ launchOptions: { args: [] } })`) 확실히 막히기는
 * 한다. 그런데 헤드리스 Chromium이 그때 던지는 것은 `NotAllowedError`가 아니라
 * **`NotSupportedError: Not supported`**라(실측), `classifyMediaError`의 `DENIED_ERROR_NAMES`에
 * 걸리지 않고 `unavailable`로 접힌다. 즉 그 방법으로는 "사용자가 거부했다"를 만들 수 없다.
 *
 * 게다가 그건 실사용자에게 일어나는 원인(권한 거부, 다른 앱이 마이크 점유)이 아니라 헤드리스
 * 빌드의 사정이다. 우리가 확인하려는 것은 `getUserMedia`가 **무엇을 던졌을 때** 어느 화면이
 * 뜨는가이므로, 던지는 값을 직접 정하는 편이 정확하고 브라우저를 새로 띄우지도 않는다.
 */

import { expect, test, type Page } from '@playwright/test'

/**
 * `getUserMedia`가 주어진 이름으로 거절하게 만든다.
 *
 * `addInitScript`라 **문서가 로드되기 전에** 심긴다 — 화면 전환마다 문서를 다시 읽는
 * 구조라(`App.tsx`), 로드 뒤에 심으면 다음 문서에서 원래 함수가 되살아난다.
 *
 * 이름만 갈아 끼우면 판정이 갈린다: `NotAllowedError`는 "사용자가 막았다"(denied),
 * 그 밖은 전부 "지금은 쓸 수 없다"(unavailable)다 (`microphone.ts`의 `classifyMediaError`).
 */
async function rejectMicrophoneWith(page: Page, errorName: string): Promise<void> {
  await page.addInitScript((name: string) => {
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
      configurable: true,
      value: () => Promise.reject(new DOMException('e2e', name)),
    })
  }, errorName)
}

/**
 * `POST /v0/sessions`가 나갔는지 센다. `page.on('request')`는 응답이 아니라 **요청**을 보므로,
 * 서버가 거절했는지와 무관하게 "보내기는 했다"를 잡는다 — 여기서 확인하려는 것이 정확히 그것이다.
 */
function countSessionRequests(page: Page): () => number {
  let count = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/v0/sessions')) count++
  })
  return () => count
}

test('마이크 거부 - 권한 안내 화면으로 갈리고 세션은 만들지 않는다', async ({ page }) => {
  await rejectMicrophoneWith(page, 'NotAllowedError')
  const sessionRequests = countSessionRequests(page)

  await page.goto('/')
  await page.getByRole('button', { name: '시작하기', exact: true }).click()

  // 인트로가 통째로 갈렸다. 문구는 `MicBlockedScreen`의 COPY.denied다.
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('마이크 권한이 필요해요')
  await expect(
    page.getByText('브라우저 설정에서 마이크를 허용한 뒤 다시 시작해 주세요', { exact: false }),
  ).toBeVisible()

  /*
   * 스토어 링크는 버튼이 아니라 `<a>`다 — 이동이라 링크의 기본 동작(새 탭·길게 눌러 복사·
   * 스크린 리더의 "링크" 안내)이 전부 의미를 갖는다는 것이 그 화면의 판단이고, 역할로 잡으면
   * 그 판단이 지켜지는지까지 함께 확인된다.
   */
  await expect(page.getByRole('link', { name: '앱으로 테스트하기' })).toBeVisible()

  /*
   * [다시 시도]는 `denied`·`unavailable`에만 있다. 눌러도 같은 화면으로 돌아오는
   * `unsupported`에는 주지 않는다 (`IntroScreen`의 `onRetry` 분기).
   */
  const retry = page.getByRole('button', { name: '다시 시도', exact: true })
  await expect(retry).toBeVisible()

  /*
   * [시작하기]는 사라졌다. 권한 없이 진행할 수 없는데 버튼이 남아 있으면 사용자는 될 때까지
   * 누르게 된다 — 화면을 갈아치우는 이유가 그것이다.
   */
  await expect(page.getByRole('button', { name: '시작하기', exact: true })).toHaveCount(0)

  /*
   * 다시 눌러도 마찬가지다. 스텁이 계속 거절하므로 같은 안내로 돌아와야 하고, 그 사이에도
   * 세션은 생기지 않는다.
   */
  await retry.click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('마이크 권한이 필요해요')

  /*
   * 이 스펙의 핵심. 게이트가 막힌 동안 세션 생성 요청은 **한 번도** 나가지 않아야 한다 —
   * 나갔다면 시작하지 못한 사용자 몫의 고아 세션이 서버에 남고 IP 분당 제한을 축낸다.
   */
  expect(sessionRequests()).toBe(0)
})

test('마이크 점유 - 거부와 다른 안내로 갈린다', async ({ page }) => {
  /*
   * `NotAllowedError`가 아닌 실패는 전부 `unavailable`이다. 갈래를 따로 보는 이유는 두 화면이
   * **사용자에게 시키는 일이 다르기 때문**이다 — 거부는 브라우저 설정을 고치라는 것이고,
   * 이쪽은 마이크를 쓰고 있는 다른 앱을 끄라는 것이다. 한쪽 문구만 확인하면 판정이 뒤집혀도
   * (모든 실패가 한 화면으로 접혀도) 스펙은 통과한다.
   */
  await rejectMicrophoneWith(page, 'NotReadableError')

  await page.goto('/')
  await page.getByRole('button', { name: '시작하기', exact: true }).click()

  await expect(page.getByRole('heading', { level: 1 })).toHaveText('마이크를 사용할 수 없어요')
  await expect(
    page.getByText('다른 앱이 마이크를 쓰고 있지 않은지 확인해 주세요', { exact: false }),
  ).toBeVisible()
})
