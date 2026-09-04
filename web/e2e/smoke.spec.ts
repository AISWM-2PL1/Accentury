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
 * 게이트를 걷는 절차 자체는 [startTest]가 갖는다 (KAN-181 2단계에 완주 스펙이 붙으면서
 * 같은 길을 두 번 적게 됐다). 이 스펙에 남은 것은 **인트로 화면 고유의 확인**이다 —
 * 게이트 통과만 보는 것이라면 완주 스펙이 이미 매번 지나간다.
 */

import { expect, test } from '@playwright/test'
import {
  ESTIMATED_MINUTES,
  VOCABULARY_ITEM_COUNT,
  VOICE_ITEM_COUNT,
} from '../src/intro/introText'
import { startTest } from './helpers/testFlow'

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
   * 인트로의 숫자 카드는 게이트를 걷기 **전에** 본다 — [시작하기]를 누르면 화면이 갈리므로
   * 나중에는 확인할 수 없다.
   *
   * 값을 적어 두지 않고 상수에서 조립하는 이유: KAN-10 연동으로 문항 수가 서버 값으로
   * 바뀌면 이 스펙도 같이 따라가야 하는데, 여기에 10을 적어 두면 그때 화면과 스펙이 조용히
   * 갈라진다.
   */
  await page.goto('/')
  await expect(
    page.getByText(`${VOICE_ITEM_COUNT + VOCABULARY_ITEM_COUNT}문항`),
  ).toBeVisible()
  await expect(page.getByText(`~${ESTIMATED_MINUTES}분`)).toBeVisible()

  const startedAt = Date.now()
  await startTest(page)
  console.log(`시작 게이트 통과까지 ${Date.now() - startedAt}ms`)
})
