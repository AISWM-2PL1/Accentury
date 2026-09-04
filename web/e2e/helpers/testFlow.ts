/**
 * 브라우저 E2E 공통 동작 (KAN-181 2단계).
 *
 * 스펙 셋이 같은 길을 걷는다 — 스모크는 시작 게이트까지, 완주 스펙은 결과 화면까지, 3단계의
 * 실패 갈래는 중간까지 걷다가 일부러 넘어진다. 그 길을 스펙마다 따로 적으면 화면 문구가
 * 하나 바뀔 때 고칠 자리가 셋이 되므로, **한 문항을 처리하는 단위**로 잘라 여기에 둔다.
 *
 * 여기 있는 함수는 전부 "성공적으로 지나갔다"까지 확인한다 — 클릭만 하고 결과를 안 보면
 * 실패가 다음 단계의 엉뚱한 곳에서 터져 원인을 찾기 어려워진다.
 */

import { expect, type Locator, type Page } from '@playwright/test'
import { itemCaption } from '../../src/progress/itemBadge'

/** 정의가 내려주는 문항 수 (음성 5 + 어휘 5). 진행 캡션의 분모이기도 하다 */
export const TOTAL_ITEMS = 10

/**
 * 녹음 길이. 품질 게이트의 하한이 1초이고(`quality.ts`의 `MIN_DURATION_MS`) 문항 상한이
 * 10초라(`maxDurationMs`), 그 사이에서 넉넉히 떨어진 값이다.
 *
 * 벽시계로 기다리지 않고 **화면의 경과 표기**가 이 값에 닿기를 기다린다. 그 숫자는 담긴
 * 샘플 수에서 오므로(`WebVoiceRecorder`의 주석 - 사용자가 보는 값과 서버가 파일에서 재는
 * 길이가 같다), `waitForTimeout(3000)`과 달리 "정말 3초어치 소리가 들어왔다"를 보증한다.
 * 느린 기계에서 실제 오디오가 덜 찼는데 시간만 흘러 TOO_SHORT로 거절당하는 일이 없다.
 */
const RECORD_ELAPSED_MARK = '00:03'

/** 목소리 점검이 통과하기를 기다리는 상한. 화면의 듣기 상한 10초 + [다시 시도] 한 번의 여유 */
const VOICE_CHECK_TIMEOUT_MS = 25_000

/**
 * 웹 단독 진입 → 시작 게이트 통과 → 문항 진행 화면.
 *
 * `bridge` 파라미터 없이 열면 `window.AccenturyBridge`도 없으므로 웹 단독 실행이 되고
 * (`bridge.ts`의 `isStandaloneWeb`), 스큐 게이트를 건너뛰고 인트로가 뜬다.
 *
 * 세션 응답을 URL 확인과 갈라서 보는 이유는 실패를 가르기 위해서다 — 201이 왔는데 URL이 안
 * 바뀌면 이동 쪽 문제이고, 201 자체가 안 오면 백엔드나 `/v0` 프록시 쪽이다.
 */
export async function startTest(page: Page): Promise<void> {
  await page.goto('/')

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

  /*
   * [시작하기]가 곧 마이크 권한 요청이다. `--use-fake-ui-for-media-stream`이 대화상자를
   * 자동 승인하므로 여기서 멈추지 않고, 승인되면 App이 목소리 점검 화면으로 갈아 끼운다.
   */
  await page.getByRole('button', { name: '시작하기', exact: true }).click()
  await expect(page.getByRole('heading', { name: '목소리를 확인할게요' })).toBeVisible()

  /*
   * [다음]은 판정기가 `ready`일 때만 그려진다 — 이 버튼이 보인다는 것은 가짜 마이크의
   * 소리로 중심 음높이가 잠기고 볼륨 조건까지 통과했다는 뜻이다 (`VoiceCheckScreen`).
   */
  const next = page.getByRole('button', { name: '다음', exact: true })
  await expect(next).toBeVisible({ timeout: VOICE_CHECK_TIMEOUT_MS })

  const session = page.waitForResponse(
    (response) => response.url().includes('/v0/sessions') && response.request().method() === 'POST',
  )
  await next.click()
  expect((await session).status()).toBe(201)

  await expect(page).toHaveURL(/screen=test/)
  await expect(page).toHaveURL(/sessionId=/)
}

/**
 * `n`번째 문항 카드가 떴는지 확인하고, 그것이 음성인지 어휘인지 알려준다.
 *
 * 순서를 미리 알고 가지 않는 이유: 지금 정의(`gn-2026.08.1`)는 음성·어휘가 번갈아 나오지만
 * 그건 **정의의 사정**이고 계약이 아니다. 서버가 순서를 바꾸면 스펙이 "음성인데 선택지가
 * 떴다"로 깨지는 대신, 화면에 있는 것을 보고 갈라야 정의가 바뀌어도 완주는 완주로 남는다.
 *
 * 캡션(`itemBadge.ts`의 `itemCaption`)을 먼저 기다리는 것이 요점이다. 앞 문항의 화면이
 * 아직 남아 있는 순간에 유형을 판정하면 방금 지나온 문항을 한 번 더 풀게 된다.
 *
 * 캡션을 정규식이 아니라 [itemCaption]이 지은 **문자열 전체**로 잡는다. 화면 위쪽 진행
 * 표시(`ProgressIndicator`)도 "1 / 10 · 음성"이라는 닮은 줄을 그려서, 앞부분만 보는 정규식은
 * 둘을 한꺼번에 집는다. 어차피 유형까지 알아야 하므로 캡션 두 개를 만들어 어느 쪽이 떴는지
 * 보는 편이 판정과 대기를 한 번에 끝낸다.
 */
export async function awaitItem(page: Page, n: number): Promise<'VOICE' | 'VOCABULARY'> {
  const voice = page.getByText(itemCaption('VOICE', n, TOTAL_ITEMS), { exact: true })
  const vocabulary = page.getByText(itemCaption('VOCABULARY', n, TOTAL_ITEMS), { exact: true })
  await expect(voice.or(vocabulary)).toBeVisible()
  return (await vocabulary.count()) > 0 ? 'VOCABULARY' : 'VOICE'
}

/**
 * 음성 문항 한 건: [녹음] → (3초) → [정지] → [다음] → 업로드 접수.
 *
 * `exact: true`가 붙은 이유는 '재녹음'이 '녹음'을 품기 때문이다 — Playwright의 접근성 이름
 * 대조는 기본이 부분 일치라, 빼면 확인 단계의 [재녹음]까지 같이 잡혀 셀렉터가 둘을 가리킨다.
 *
 * 업로드 응답을 직접 확인한다. 화면에는 "보내는 중…"이 잠깐 스쳤다 다음 문항으로 넘어갈
 * 뿐이라, 접수가 실제로 200이었는지는 응답을 봐야만 알 수 있다.
 */
export async function answerVoiceItem(page: Page): Promise<void> {
  await page.getByRole('button', { name: '녹음', exact: true }).click()

  /*
   * 경과 표기가 3초에 닿을 때까지. 상한(10초)에 닿으면 훅이 스스로 멈추므로 여기서 늦어도
   * 녹음이 잘리지는 않지만, 그러면 [정지]를 거치지 않는 다른 경로를 타게 된다.
   */
  await expect(page.getByText(RECORD_ELAPSED_MARK)).toBeVisible({ timeout: 10_000 })
  await page.getByRole('button', { name: '정지', exact: true }).click()

  /*
   * 확인 단계. [다음]이 보인다는 것은 품질 게이트(FR-AD-08)를 통과했다는 뜻이다 — 짧거나
   * 조용하거나 찢어진 녹음이면 이 버튼 대신 [재녹음]만 그려진다 (`ReviewPanel`).
   */
  const send = page.getByRole('button', { name: '다음', exact: true })
  await expect(send).toBeVisible()

  const upload = page.waitForResponse(
    (response) =>
      /\/v0\/sessions\/[^/]+\/voice-items\/[^/]+\/recording$/.test(response.url()) &&
      response.request().method() === 'POST',
  )
  await send.click()
  expect((await upload).ok()).toBe(true)
}

/**
 * 어휘 문항 한 건: 선택지 하나를 고르고 제출.
 *
 * 어느 선택지든 상관없다 — 이 스펙이 보는 것은 정답 여부가 아니라 제출이 접수되는가이고,
 * 애초에 화면은 정오를 알려주지 않는다 (KAN-13).
 *
 * 라디오는 눈에서만 지워져 있고(`components.css`의 `.choice__radio` - 1px + clip-path)
 * 접근성 트리에는 그대로 남아 있어 역할로 잡힌다. 다만 1px로 잘려 있어 보통의 클릭
 * 판정에는 걸리지 않으므로 `force`로 넘긴다 — 클릭은 감싼 `<label>`이 받아 어차피 같은
 * 선택으로 이어진다.
 *
 * 제출 버튼을 이름이 아니라 "이 화면의 유일한 버튼"으로 잡는 이유는 라벨이 상태를 따라
 * `다음`/`제출 중…`/`다시 시도`로 바뀌기 때문이다 (`VocabularyItemScreen`). 이름으로 잡으면
 * 재시도 갈래에서 조용히 못 찾는 셀렉터가 된다.
 */
export async function answerVocabularyItem(page: Page): Promise<void> {
  await page.getByRole('radiogroup').getByRole('radio').first().check({ force: true })

  const submit = submitButton(page)
  const answer = page.waitForResponse(
    (response) =>
      /\/v0\/sessions\/[^/]+\/vocab-items\/[^/]+\/answer$/.test(response.url()) &&
      response.request().method() === 'POST',
  )
  await submit.click()
  expect((await answer).ok()).toBe(true)
}

/** 어휘 화면의 제출 버튼. 이 화면에 버튼은 이것 하나뿐이다 (진행 표시는 progressbar다) */
function submitButton(page: Page): Locator {
  return page.getByRole('button')
}

/**
 * 10문항 전부. 화면에 뜬 것을 보고 갈라 가며 끝까지 간다.
 *
 * @returns 실제로 지나온 문항의 유형 (순서 검증용 - 정의가 바뀌면 이 값이 달라진다)
 */
export async function answerAllItems(page: Page): Promise<Array<'VOICE' | 'VOCABULARY'>> {
  const seen: Array<'VOICE' | 'VOCABULARY'> = []
  for (let n = 1; n <= TOTAL_ITEMS; n++) {
    const type = await awaitItem(page, n)
    seen.push(type)
    if (type === 'VOICE') {
      await answerVoiceItem(page)
    } else {
      await answerVocabularyItem(page)
    }
  }
  return seen
}
