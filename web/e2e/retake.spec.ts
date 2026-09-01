/**
 * 분석 실패 갈래 (KAN-181 3단계) — 음성 문항 하나가 판정에 실패했을 때 브라우저 단독
 * 응시자가 실제로 보는 화면.
 *
 * ## 이 스펙이 밝힌 것: 브라우저에는 복구가 없다
 *
 * 처음 겨눈 것은 "재녹음으로 복구해 완주한다"였는데, 그 길은 브라우저에 **없다**.
 * `TestFlowScreen.tsx:293`이 브리지가 없으면 `onRetake`를 넘기지 않는다 — 눌러도 네이티브
 * 녹음 화면이 열리지 않아 아무 일도 하지 않는 버튼이 되기 때문이다. 버튼이 없으니
 * [AnalysisWaitingScreen]의 `hasActionableRow`가 거짓이 되고, 서버가 "사용자가 움직여야
 * 한다"(409 RETAKE)고 말한 상황에 화면에는 누를 것이 하나도 없는 **막다른 길**이 된다.
 *
 * 그 막다름은 버그가 아니라 설계다. 화면은 그 사실을 숨기고 "다시 녹음해 주세요"라고 말하는
 * 대신 앱 재시작으로 안내한다 (`AnalysisWaitingScreen`의 `deadEnd` 주석). 그래서 이 스펙이
 * 확인하는 것은 복구가 아니라 **막다름이 제대로 안내되는가**다.
 *
 * 재녹음 복구 흐름 자체는 앱(WebView + 네이티브) 몫이라 브라우저 E2E의 범위 밖이다.
 * 설령 버튼이 있었어도 로컬에서는 복구되지 않는다: AI 스텁은 `itemId`만 보고 실패시키므로
 * (`ai/app/engine.py:355`) 같은 문항을 다시 올려도 같은 판정이 나온다. 복구를 보려면
 * 스텁을 실패 설정 없이 다시 띄워야 하는데, 그건 스펙이 할 일이 아니라 무대의 일이다.
 *
 * ## 왜 환경 변수로 건너뛰는가
 *
 * 이 스펙은 AI 스텁이 `ACCENTURY_AI_STUB_FAIL_ITEM`을 물고 떠 있어야만 의미가 있고,
 * `full-run.spec.ts`는 반대로 그 설정이 없어야 통과한다. 한 스택이 두 조건을 동시에 만족할
 * 수 없으므로 **대칭 스킵**으로 갈랐다 — 스택을 두 번 띄우고 각 상태에서 전체를 돌린다.
 *
 * 스택 상태를 스펙이 직접 알아내는 길도 찾아봤지만 없었다. AI의 `/internal/v0/health`는
 * `{"status":"UP"}`만 주고 스텁 설정을 노출하지 않는다(실측). 노출한다 해도 웹 스펙이 AI를
 * 직접 부르는 것은 구조에 어긋난다 — 그 서버는 BE만 호출하는 사설망 서비스이고
 * (`docker-compose.yml`), staging을 겨눌 때는 닿지도 않는다. 환경 변수는 로컬·CI·staging에서
 * 똑같이 동작하는 유일한 신호다. 이름을 새로 짓지 않고 `E2E_FAIL_ITEM`을 쓰는 이유는
 * 레포가 이미 그 이름을 쓰기 때문이다 (`docker-compose.yml`, `scripts/e2e-smoke-local.sh`).
 */

import { expect, test } from '@playwright/test'
import { answerAllItems, startTest } from './helpers/testFlow'

/** 실패시킬 음성 문항 id. 무대를 세운 쪽(compose)과 같은 값을 봐야 한다 */
const failItem = process.env.E2E_FAIL_ITEM

test.skip(
  failItem === undefined || failItem === '',
  'AI 스텁이 특정 문항을 실패시키도록 떠 있어야 한다: E2E_FAIL_ITEM=v3 docker compose up -d --no-deps --wait ai',
)

/**
 * 문항 10건(약 19초)에 `/complete`가 409로 굳을 때까지의 대기가 붙는다. 실측 19초의
 * 여섯 배에서 끊는다 — CI 러너가 느린 것을 감안했고, 폴링이 멎는 화면이라 잘못되면 영원히
 * 기다리게 되는 자리라 상한이 반드시 있어야 한다.
 */
test.setTimeout(120_000)

test('음성 문항 분석 실패 - 브라우저에서는 되돌릴 수 없다고 안내한다', async ({ page }) => {
  /*
   * 화면이 막다름을 감지하면 진단을 콘솔에 남긴다 (`AnalysisWaitingScreen`의 useEffect).
   * 그 한 줄이 이 스펙의 부검 자료라 흘려보낸다.
   */
  page.on('console', (message) => {
    if (message.type() === 'error') console.log(`[browser:error] ${message.text()}`)
  })

  /*
   * `/complete`의 409를 잡아 둔다. 화면 문구만 보면 "무언가 실패했다"까지만 알 수 있는데,
   * **우리가 심은 그 문항이** 실패했는지는 서버가 짚어 준 목록으로만 확인된다 — 다른 이유로
   * 실패해도 화면은 같은 안내를 내므로, 이것이 없으면 스펙이 엉뚱한 실패에도 통과한다.
   */
  const conflict = page.waitForResponse(
    (response) => response.url().includes('/complete') && response.status() === 409,
    { timeout: 90_000 },
  )

  await startTest(page)
  await answerAllItems(page)

  /*
   * 봉투에서 `retakeItems`는 **최상위 필드**다. 클라이언트가 읽고 나면 `itemIds` 아래로
   * 묶이지만(`errorEnvelope.ts`의 `readErrorEnvelope`) 그건 파싱 뒤의 모양이라, 원문을
   * 보는 여기서는 서버가 실제로 보낸 자리를 봐야 한다.
   */
  const envelope = await (await conflict).json()
  expect(envelope.code).toBe('RESULT_RETAKE_REQUIRED')
  expect(envelope.retakeItems).toEqual([failItem])

  /*
   * 막다른 길 안내. `여기서는 더 진행할 수 없어요`는 `deadEnd`일 때만 나온다 — 재녹음
   * 버튼이 있는 앱에서는 `일부 문항을 다시 녹음해야 해요`가 대신 뜬다. 즉 이 문구 자체가
   * "브라우저에는 복구 통로가 없다"의 증거다.
   */
  await expect(page.getByText('여기서는 더 진행할 수 없어요')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText('앱을 다시 시작해 테스트를 처음부터 진행해 주세요')).toBeVisible()

  /*
   * 목록에서 실패한 문항 하나만 재녹음 대상으로 표시된다 (`STATUS_LABEL.RETRYABLE_FAILED`).
   * 개수를 세는 이유: 하나여야 우리가 심은 실패이고, 여럿이면 스택이 다른 이유로 무너진 것이다.
   *
   * 몇 번 문항인지는 세지 않는다. 문항 번호는 정의의 배열 순서에서 나오는 값이라
   * (지금은 음성·어휘가 번갈아 나와 v3가 5번이다) 정의가 바뀌면 따라 움직이는데, 어느
   * 문항이 실패했는지는 위 봉투가 id로 이미 못 박았다.
   */
  await expect(page.getByText('다시 녹음이 필요해요')).toHaveCount(1)

  /*
   * 버튼이 하나도 없다는 것이 막다름의 정의다. [다시 녹음]도, 폴링을 되살릴 [다시 시도]도,
   * 이탈 버튼도(KAN-147 결정으로 걷어냈다) 없다.
   */
  await expect(page.getByRole('button')).toHaveCount(0)

  // 결과 화면으로는 끝내 넘어가지 않는다 — 완주하지 못한 세션이다.
  await expect(page).toHaveURL(/screen=test/)
})
