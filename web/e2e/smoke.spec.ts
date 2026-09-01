/**
 * 시작 게이트 스모크 (KAN-181 1단계) — 인트로에서 문항 진행 화면까지 실브라우저로 걸어간다.
 *
 * 여기서만 확인할 수 있는 것이 셋이다.
 *
 * 1. **가짜 마이크가 진짜 오디오 경로를 탄다.** vitest는 `getUserMedia`도 `AudioContext`도
 *    없어 캡처를 주입으로 갈아 끼우는데(`VoiceCheckScreenProps.capture`), 그러면 워클릿·
 *    리샘플러·YIN이 실제로 이어지는지는 아무도 보지 않는다. Chromium의 합성 장치가 2.5초짜리
 *    "안녕하세요"를 흘려 넣으면 그 사슬 전체가 한 번에 걸린다.
 * 2. **문서를 다시 로드하는 화면 전환이 성립한다.** 세션 생성 뒤의 이동은 `location.href`
 *    대입이라 jsdom이 구현하지 않아, 단위 테스트는 주입한 `navigate`가 받은 문자열만 본다.
 *    브라우저에서는 그 URL로 실제 새 문서가 뜨고 진입 분기(App)가 다시 돈다.
 * 3. **백엔드가 실제로 세션을 준다.** `POST /v0/sessions`가 `/v0` 프록시를 거쳐 로컬
 *    백엔드로 나가고, 받은 토큰이 저장소를 건너 다음 문서까지 닿아야 이 스펙이 통과한다.
 *
 * 전 구간(문항 응답 → 분석 대기 → 결과)은 2단계 몫이다.
 */

import { expect, test } from '@playwright/test'
import {
  ESTIMATED_MINUTES,
  VOCABULARY_ITEM_COUNT,
  VOICE_ITEM_COUNT,
} from '../src/intro/introText'

/**
 * 목소리 점검에 줄 여유. 화면 자체의 듣기 상한은 10초이고
 * (`VOICE_CHECK_MAX_DURATION_MS`), 그 뒤 [다시 시도]로 한 번 더 들을 수 있으므로
 * 한 번의 시도가 실패해도 스펙이 곧바로 죽지는 않게 잡았다.
 */
const VOICE_CHECK_TIMEOUT_MS = 25_000

test('웹 단독 진입 - 인트로에서 가짜 마이크로 목소리 점검을 통과해 문항 화면까지 간다', async ({
  page,
}) => {
  /*
   * 브라우저 콘솔을 그대로 흘려보낸다. 오디오 경로의 실패는 화면에 "목소리가 잡히지
   * 않았어요" 한 줄로만 나타나서, 무엇이 왜 안 됐는지는 콘솔에만 남는다.
   */
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      console.log(`[browser:${message.type()}] ${message.text()}`)
    }
  })

  /*
   * `bridge` 파라미터 없이 연다 — 그러면 `window.AccenturyBridge`도 없으므로 웹 단독 실행이
   * 되고(`bridge.ts`의 `isStandaloneWeb`), 스큐 게이트를 건너뛰고 인트로가 뜬다.
   */
  await page.goto('/')

  // 인트로. 제목은 역할로 잡는다 — 문구는 카피 조정으로 바뀌지만 "h1이 하나 있다"는 배치의 약속이다.
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  /*
   * 숫자 카드는 상수에서 조립한다. KAN-10 연동으로 문항 수가 서버 값으로 바뀌면 이 스펙도
   * 같이 따라가야 하는데, 값을 여기에 적어 두면 그때 화면과 스펙이 조용히 갈라진다.
   */
  await expect(
    page.getByText(`${VOICE_ITEM_COUNT + VOCABULARY_ITEM_COUNT}문항`),
  ).toBeVisible()
  await expect(page.getByText(`~${ESTIMATED_MINUTES}분`)).toBeVisible()

  /*
   * 버튼 이름만 문자열 그대로다. 라벨이 컴포넌트 JSX 안에 있어 상수로 꺼내 있지 않은데,
   * 그것을 읽으려고 화면 모듈을 import하면 React와 그 모듈이 딸고 오는 것들이 전부 이
   * 테스트 프로세스로 들어온다 — 상수 하나 얻자고 치를 값이 아니다. 카피가 바뀌면 이 줄이
   * 실패하고, 그때 같이 고치는 것이 맞다.
   */
  const start = page.getByRole('button', { name: '시작하기' })
  await expect(start).toBeEnabled()

  /*
   * [시작하기]가 곧 마이크 권한 요청이다. `--use-fake-ui-for-media-stream`이 대화상자를
   * 자동 승인하므로 여기서 멈추지 않고, 승인되면 App이 목소리 점검 화면으로 갈아 끼운다
   * (문서 리로드가 아니라 같은 문서 안의 상태 전환이다 — 리로드하면 방금 받은 권한을
   * 다시 물어야 한다, `App.tsx`의 `IntroRoute`).
   */
  const startedAt = Date.now()
  await start.click()

  await expect(page.getByRole('heading', { name: '목소리를 확인할게요' })).toBeVisible()

  /*
   * 여기가 이 스펙의 심장이다. [다음] 버튼은 판정기가 `ready`가 됐을 때만 그려지므로
   * (`VoiceCheckScreen`), 이 버튼이 보인다는 것은 가짜 마이크의 소리로 **중심 음높이가
   * 잠기고 볼륨 조건까지 통과했다**는 뜻이다 — 화면이 떴다는 것과는 다른 사실이다.
   */
  const next = page.getByRole('button', { name: '다음' })
  await expect(next).toBeVisible({ timeout: VOICE_CHECK_TIMEOUT_MS })
  console.log(`목소리 점검 통과까지 ${Date.now() - startedAt}ms`)

  /*
   * [다음]이 세션 생성(`POST /v0/sessions`)과 화면 전환을 함께 건다. 응답을 기다렸다가
   * 이동을 확인하는 이유는 실패를 갈라 보기 위해서다 — 세션이 201을 냈는데 URL이 안 바뀌면
   * 이동 쪽 문제이고, 201 자체가 안 오면 백엔드나 프록시 쪽이다.
   */
  const session = page.waitForResponse(
    (response) => response.url().includes('/v0/sessions') && response.request().method() === 'POST',
  )
  await next.click()
  expect((await session).status()).toBe(201)

  // 문항 진행 화면의 정식 진입 쿼리 (`entryUrl.ts`의 `buildTestUrl`).
  await expect(page).toHaveURL(/screen=test/)
  await expect(page).toHaveURL(/sessionId=/)
})
