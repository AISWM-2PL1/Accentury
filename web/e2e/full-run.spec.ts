/**
 * 정상 시나리오 완주 (KAN-181 2단계) — 인트로에서 결과 화면까지 한 번에 걸어간다.
 *
 * 스모크(`smoke.spec.ts`)가 시작 게이트까지 본다면 여기는 **그 뒤 전부**다. 이 스펙만이
 * 확인할 수 있는 것을 추려 보면 왜 한 판을 통째로 도는지가 드러난다.
 *
 * - **문항 사이의 상태가 이어진다.** 진행 스냅샷·세션 토큰·중심 음높이가 열 문항을 건너
 *   살아남아야 하는데, 단위 테스트는 문항 하나짜리 화면만 세우므로 이 이어짐을 못 본다.
 * - **녹음 열 개가 실제로 서버에 쌓인다.** 업로드는 WAV 인코딩·멀티파트·1MB 상한이 걸린
 *   경로라 jsdom에서는 흉내만 낼 수 있다.
 * - **분석 대기 화면이 실제로 뜬다.** AI 스텁이 문항마다 지연을 주므로, 마지막 제출 직후
 *   결과가 준비돼 있지 않다 — 폴링이 READY를 볼 때까지 기다리는 구간이 여기서만 생긴다.
 * - **점수와 등급이 서버에서 온 값으로 그려진다.**
 *
 * 실패 갈래(마이크 거부, 분석 실패 후 재녹음)는 3단계 몫이다.
 */

import { expect, test } from '@playwright/test'
import { answerAllItems, startTest, TOTAL_ITEMS } from './helpers/testFlow'

/**
 * 한 판을 도는 데 걸리는 시간의 상한.
 *
 * 실측 완주가 약 21초다(녹음 5건 × 3초 + 업로드 + 어휘 5건 + 분석 대기 2.3초, 2026-09-01
 * 로컬). Playwright 기본 30초에 아슬아슬해 명시적으로 올리되, CI 러너가 로컬보다 몇 배
 * 느린 것까지 감안해 120초에서 끊는다 — 상한을 무한정 늘리면 어딘가 멈춰 있는 스펙이 CI를
 * 통째로 붙들고 있게 된다.
 */
test.setTimeout(120_000)

/**
 * 등급 이름. **서버가 정하는 값이다** — 화면은 `tier.name`을 그대로 그리고 클라이언트에는
 * 등급 표가 없다 (`tierAssets.ts` 헤더의 KAN-29 결정).
 *
 * 그래서 여기 적힌 다섯은 화면의 계약이 아니라 지금 점수 정책
 * (`backend/src/main/resources/score-versions/sv-0.3.json`)이 담고 있는 값의 사본이다.
 * AI 스텁이 correlationId를 해시해 점수를 고르게 흩으므로(KAN-136) 세션마다 다른 등급이
 * 나오고, 어느 하나를 콕 집을 수는 없다.
 */
const TIER_NAMES = ['외지인', '여행객', '사투리 호소인', '명예주민', '경남 토박이']

test('웹 단독 완주 - 10문항을 풀고 분석을 기다려 결과 등급·점수까지 본다', async ({ page }) => {
  page.on('console', (message) => {
    if (message.type() === 'error') console.log(`[browser:error] ${message.text()}`)
  })

  const startedAt = Date.now()
  await startTest(page)

  const seen = await answerAllItems(page)
  expect(seen).toHaveLength(TOTAL_ITEMS)
  /*
   * 유형 구성만 확인하고 순서는 보지 않는다. 지금 정의는 음성·어휘가 번갈아 나오지만 그건
   * 정의의 사정이라(`gn-2026.08.1`), 순서를 박아 두면 문항을 재배치하는 날 이 스펙이
   * "완주가 깨졌다"고 거짓 신호를 낸다. 반대로 개수는 계약이다 — 인트로가 상수로 약속한
   * 음성 5 + 어휘 5가 그대로 나와야 한다 (`introText.ts`).
   */
  expect(seen.filter((type) => type === 'VOICE')).toHaveLength(5)
  expect(seen.filter((type) => type === 'VOCABULARY')).toHaveLength(5)
  const submittedAt = Date.now()

  /*
   * 분석 대기 화면 (KAN-14). 마지막 문항이 제출된 **뒤에야** 폴링이 시작되므로, 이 막대가
   * 보인다는 것은 진행 화면이 대기 화면으로 갈렸다는 뜻이다.
   *
   * 진행률 막대를 문구 대신 잡는 이유: "결과를 만들고 있어요"는 READY가 되는 순간
   * "결과 화면으로 이동합니다"로 바뀌어, 빠른 판에서는 문구를 못 보고 지나칠 수 있다.
   * 막대는 두 상태 모두에 있다.
   */
  await expect(page.getByRole('progressbar', { name: '분석 진행률' })).toBeVisible()

  // 결과 화면으로는 스스로 넘어간다 — 폴링이 READY를 보면 대기 화면이 호출자에게 알린다.
  await expect(page).toHaveURL(/screen=result/, { timeout: 60_000 })
  const finishedAt = Date.now()

  /*
   * 등급 이름이 이 화면에서 가장 큰 글자이자 유일한 h1이다 (`ResultScreen`). 이름 자체보다
   * 순위 줄이 더 단단한 확인이라 둘 다 본다 — 순위는 "5개 등급 중 몇 번째"라는 구조라
   * 이름이 바뀌어도 살아남는다.
   */
  const tier = page.getByRole('heading', { level: 1 })
  await expect(tier).toHaveText(new RegExp(`^(${TIER_NAMES.join('|')})$`))
  await expect(page.getByText(/^\d+개 등급 중 \d+번째$/)).toBeVisible()

  /*
   * 점수 셋 — 종합·억양·단어. 종합은 도넛 가운데 숫자이고 나머지 둘은 `<progress>`라
   * 역할로 잡힌다 (`ScoreRow`의 `aria-label`이 "억양 점수"·"단어 점수"다).
   *
   * 값은 범위로만 본다. 스텁이 correlationId 해시로 점수를 고르므로 세션마다 다르고
   * (KAN-136), 특정 숫자를 기대하면 스펙이 스텁의 구현에 묶인다.
   */
  await expect(page.getByText(/^\d{1,3}점$/).first()).toBeVisible()
  for (const label of ['억양', '단어']) {
    const bar = page.getByRole('progressbar', { name: `${label} 점수` })
    await expect(bar).toBeVisible()
    const score = Number(await bar.getAttribute('value'))
    expect(score).toBeGreaterThanOrEqual(0)
    expect(score).toBeLessThanOrEqual(100)
  }

  console.log(
    `완주 ${finishedAt - startedAt}ms (문항 ${submittedAt - startedAt}ms + 분석 대기 ${finishedAt - submittedAt}ms)`,
  )
})
