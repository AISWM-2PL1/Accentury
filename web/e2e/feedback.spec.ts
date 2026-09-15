/**
 * 결과 화면의 이용 후기 (KAN-211 4단계) — 실패 → 재시도 → 성공 → 409를 **한 완주 안에서** 본다.
 *
 * ## 왜 완주를 다시 걷는가
 *
 * 후기 시트는 결과 화면 위에만 뜨고, 결과 화면은 완주한 세션에만 있다 (`FeedbackService`의
 * 완료 가드가 미완주 세션을 409 `RESULT_NOT_READY`로 막는다). 세션을 지어 내는 지름길이 없고,
 * 있어도 쓰지 않는다 — 서버가 저장 시점에 결과 스냅샷(등급·테스트/점수 버전)을 복사하므로
 * (`V11__session_feedback.sql`) 진짜 결과가 없으면 이 경로의 절반이 검증되지 않는다.
 *
 * 그래서 완주는 비싸고(실측 21초, `browser-e2e.md`), 케이스마다 한 판씩 돌면 스펙 하나가 2분을
 * 넘는다. 대신 **케이스를 한 판 안에서 순서대로 걷는다.** 후기는 결과당 1건이라 상태가 한 방향으로만
 * 흐르고(작성 → 실패 → 재시도 → 저장 → 이미 있음), 그 순서가 곧 사용자가 실제로 겪는 순서다.
 *
 * ## 순서가 곧 설계다
 *
 * 1. 진입 버튼과 시트 열림 — 버튼이 셋으로 늘었다 (`full-run.spec.ts`의 개수 단언과 짝)
 * 2. 입력 검증 — 빈 본문과 형식이 틀린 이메일에서 [보내기]가 잠긴다
 * 3. **실패** — 라우트를 500으로 막아 [다시 보내기]와 **입력 보존**을 본다
 * 4. **재시도** — 라우트를 풀고 같은 버튼으로. 두 요청의 `Idempotency-Key`가 **같아야** 한다
 * 5. **완료** — 201, 완료 문구, 닫으면 진입 버튼이 캡션으로 바뀌고 버튼이 둘로 돌아온다
 * 6. **409** — 리로드 뒤 새 키로 다시 보내면 「이미 보냈어요」
 *
 * 3번이 4번 앞에 오는 것이 이 배치의 요점이다. 실패를 나중에 만들려면 이미 저장된 세션에
 * 두 번째 후기를 써야 하는데, 그 경로는 409라 500 갈래를 덮지 못한다 — 완주를 한 번 더
 * 도는 수밖에 없어진다.
 *
 * ## 멱등 키가 이 스펙의 핵심 단언이다
 *
 * [다시 보내기]가 **새 키로** 나가면, 첫 요청이 실제로는 저장됐던 경우(응답만 유실) 재시도가
 * 409를 받아 「이미 보냈어요」가 되고 사용자는 방금 쓴 글이 어디로 갔는지 알 수 없다. 키를
 * 시트가 아니라 부모(`ResultScreen`)가 쥐는 이유가 그것이고(`sendFeedback`의 `idempotencyKey`
 * 주석), 단위 테스트는 주입한 `submit`까지만 보므로 **같은 키가 두 요청에 실렸는가**는 진짜
 * 브라우저에서 두 번 나간 요청의 헤더를 대조해야만 알 수 있다. 그 자리가 여기다.
 */

import { expect, test, type Request } from '@playwright/test'
import {
  FEEDBACK_ALREADY,
  FEEDBACK_CLOSE,
  FEEDBACK_DONE_CAPTION,
  FEEDBACK_DONE_TITLE,
  FEEDBACK_EMAIL_LABEL,
  FEEDBACK_GUIDE,
  FEEDBACK_INVALID_EMAIL,
  FEEDBACK_OPEN,
  FEEDBACK_RETRY,
  FEEDBACK_SUBMIT,
  FEEDBACK_TITLE,
} from '../src/feedback/feedbackText'
import {
  answerAllItems,
  expectResultFooterButtons,
  startTest,
  TOTAL_ITEMS,
} from './helpers/testFlow'

/**
 * 완주 한 판 + 후기 왕복 셋. `full-run.spec.ts`의 120초에 후기 몫을 얹어 잡는다 — 후기 요청은
 * 분석과 달리 즉답이라 실제로 더 드는 시간은 시트를 여닫는 몇 초와 리로드 한 번이다.
 */
test.setTimeout(150_000)

/**
 * 완주가 성립하지 않는 스택에서는 건너뛴다 (`full-run.spec.ts`와 같은 조건).
 *
 * `E2E_FAIL_ITEM`이 켜져 있으면 가짜 AI가 그 문항을 반드시 실패시켜 결과 화면에 닿지 못하고,
 * 결과가 없으면 후기를 보낼 자리 자체가 없다.
 */
test.skip(
  process.env.E2E_FAIL_ITEM !== undefined && process.env.E2E_FAIL_ITEM !== '',
  'E2E_FAIL_ITEM이 켜진 스택에서는 완주할 수 없다 (후기는 결과 화면 위에만 있다)',
)

/** 시트에 적을 후기. 슬랙 메시지의 줄바꿈·이스케이프까지 겨누지 않는다 — 그건 단위 테스트 몫이다 */
const BODY = 'E2E 후기 - 녹음 화면이 편했어요'

/** 고를 별점. 계측에 실리는 유일한 값이라(`feedback_submitted`) 요청 본문에서 그대로 확인한다 */
const RATING = 4

/** 형식이 틀린 이메일. `@`가 없어 `validateFeedbackInput`의 정규식에 걸린다 */
const BAD_EMAIL = 'abc'

/** 후기 전송 요청인가. 경로 규칙은 `sendFeedback`이 만드는 URL과 같다 */
function isFeedbackPost(request: Request): boolean {
  return /\/v0\/sessions\/[^/]+\/feedback$/.test(request.url()) && request.method() === 'POST'
}

test('KAN-211 - 결과 화면에서 후기를 실패·재시도로 보내고, 같은 결과에 두 번은 보낼 수 없다', async ({
  page,
}) => {
  page.on('console', (message) => {
    if (message.type() === 'error') console.log(`[browser:error] ${message.text()}`)
  })

  /*
   * 나간 후기 요청을 전부 모은다. 재시도가 같은 키로 나갔는지는 **두 요청을 나란히 놓아야**
   * 보이므로, 단건을 기다리는 `waitForRequest`가 아니라 수집기를 둔다. 라우트로 막은 요청도
   * 여기에 잡힌다 — `page.route`의 fulfill은 요청이 나간 뒤에 응답을 지어내는 것이라
   * request 이벤트가 그대로 발생한다.
   */
  const posts: Request[] = []
  page.on('request', (request) => {
    if (isFeedbackPost(request)) posts.push(request)
  })

  await startTest(page)
  expect(await answerAllItems(page)).toHaveLength(TOTAL_ITEMS)

  /*
   * 결과 화면까지. `full-run.spec.ts`의 같은 두 줄을 헬퍼로 뽑지 않았다 — 저쪽 광고 스펙은
   * 이 둘 **사이에** 슬롯 단언을 끼워 넣으므로 헬퍼가 세 호출처 중 둘만 덮고, 두 줄을 아끼자고
   * 그 비대칭을 만들 값어치가 없다.
   */
  await expect(page.getByRole('progressbar', { name: '분석 진행률' })).toBeVisible()
  await expect(page).toHaveURL(/screen=result/, { timeout: 60_000 })

  /*
   * ── 1. 진입 버튼과 시트 ──
   *
   * 푸터의 버튼을 먼저 센다. 후기가 붙으면서 공유·재응시에 하나가 더해졌고, 그 사실을
   * `full-run.spec.ts`의 KAN-197 스펙도 같은 헬퍼로 붙들고 있다.
   *
   * 숫자를 박지 않는 이유는 [앱 다운로드] 때문이다 (2026-09-15). 스토어 등록 전에는 그 CTA가
   * 링크가 아니라 **비활성 버튼**이라 이 개수에 끼고, 등록되면 `<a>`로 돌아가 빠진다 —
   * 기대치를 `resultFooterButtonNames`가 빌드를 보고 만든다.
   */
  await expectResultFooterButtons(page, [FEEDBACK_OPEN])
  const open = page.getByRole('button', { name: FEEDBACK_OPEN, exact: true })
  await expect(open).toBeVisible()

  await open.click()

  /*
   * 시트를 **이름으로 잡지 않는다.** 이름은 `aria-labelledby`가 가리키는 h2에서 오는데, 그 h2가
   * 완료되는 순간 「고마워요, 잘 받았어요」로 갈린다 (`FeedbackSheet`) — 이름을 박은 로케이터는
   * 그때 조용히 아무것도 가리키지 않게 되고, `toBeHidden()` 같은 단언이 **사라져서가 아니라
   * 이름이 바뀌어서** 통과한다. 4단계 첫 실행에서 실제로 걸린 자리다.
   *
   * 그래서 잡는 것은 역할뿐이고(이 화면에 모달은 하나다), 이름은 여는 순간에 한 번만 대조한다.
   */
  await expect(page.getByRole('dialog', { name: FEEDBACK_TITLE })).toBeVisible()
  const sheet = page.getByRole('dialog')
  /*
   * 본문 칸의 이름표는 안내 문장이다 (`FeedbackSheet`의 `aria-labelledby={GUIDE_ID}`) — 칸
   * 위의 그 문장이 이미 무엇을 적는 칸인지 말하고 있어 이름표를 따로 두지 않았다. 그래서
   * 이메일 칸과 이름으로 갈린다.
   *
   * 열자마자 초점이 여기 서야 한다 (`autoFocus`). 시트를 열고 바로 타자를 치는 사람이 빈
   * 화면에 글을 쓰게 되는 것을 막는 자리이고, jsdom에서는 `autoFocus`가 실제 초점 이동으로
   * 이어지지 않아 단위 테스트가 보지 못한다.
   */
  const bodyInput = sheet.getByRole('textbox', { name: FEEDBACK_GUIDE })
  await expect(bodyInput).toBeFocused()

  /*
   * ── 2. 입력 검증 ──
   *
   * 빈 본문에서는 [보내기]가 잠겨 있다. 아직 아무것도 안 적은 사람에게 「후기 내용을 적어
   * 주세요」를 띄우지 않는 것이 시트의 판단이라(`touched`), 이 시점에 보이는 신호는 비활성
   * 버튼 하나뿐이다.
   */
  const submit = sheet.getByRole('button', { name: FEEDBACK_SUBMIT, exact: true })
  await expect(submit).toBeDisabled()

  await bodyInput.fill(BODY)
  await expect(submit).toBeEnabled()

  /*
   * 별점 라디오는 `.sr-only`로 눈에서만 지워져 있다 (1px + `clip-path: inset(50%)`) — 어휘
   * 선택지와 같은 규칙이라 `force`로 넘긴다. 접근성 이름은 ★가 아니라 「4점」이다: 별은
   * `aria-hidden`이고 소리로 읽히는 것은 옆의 `.sr-only` 텍스트다.
   */
  await sheet.getByRole('radio', { name: `${RATING}점`, exact: true }).check({ force: true })

  /*
   * 형식이 틀린 이메일에서는 **둘 다** 일어난다 — 버튼이 잠기고 문구가 뜬다. 구현이 한쪽만
   * 하는지 확인하고 그대로 적는 것이 이 단언의 규칙인데(4단계 지시), `FeedbackSheet`는
   * `invalid !== null`로 버튼을 잠그고 같은 값을 `touched`일 때 그려서 둘이 같이 온다.
   */
  const email = sheet.getByRole('textbox', { name: FEEDBACK_EMAIL_LABEL })
  await email.fill(BAD_EMAIL)
  await expect(submit).toBeDisabled()
  await expect(sheet.getByText(FEEDBACK_INVALID_EMAIL, { exact: true })).toBeVisible()

  // 지우면 미입력으로 돌아간다 — 선택 항목이므로 비어 있는 것이 정상 상태다.
  await email.fill('')
  await expect(submit).toBeEnabled()

  /*
   * ── 3. 실패 ──
   *
   * 봉투 없는 500을 만든다. `readErrorEnvelope`가 읽을 것이 없어 `FEEDBACK_HTTP_ERROR(500)`로
   * 떨어지고, 재시도 여부는 상태 코드가 정한다 (`isRetryableStatus` — 5xx는 재시도 가능).
   * 실제 서버를 500으로 만들 방법이 없어 라우트로 지어내지만, **요청 자체는 진짜로 나간다** —
   * 그래서 아래 키 대조가 성립한다.
   */
  await page.route('**/feedback', (route) => route.fulfill({ status: 500, body: '' }))
  await submit.click()

  const retry = sheet.getByRole('button', { name: FEEDBACK_RETRY, exact: true })
  await expect(retry).toBeVisible()
  /*
   * **입력이 남아 있어야 한다.** 방금 쓴 글이 실패 한 번에 사라지면 사람은 두 번 쓰지 않는다
   * (`SheetState`의 `failed`만 입력을 그대로 두는 이유).
   */
  await expect(bodyInput).toHaveValue(BODY)
  await expect(sheet.getByRole('radio', { name: `${RATING}점`, exact: true })).toBeChecked()

  /*
   * 후기가 실패해도 결과 화면은 그대로다 (`sendFeedback` 파일 헤더). 시트 뒤의 등급·공유·
   * 재응시가 살아 있는지 본다 — 후기 실패가 화면 전체를 오류 상태로 바꾸는 길이 이 경로에
   * 없다는 것이 이 티켓의 계약이다.
   */
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByRole('button', { name: '친구에게 공유하기', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '다시 테스트하기', exact: true })).toBeVisible()

  /*
   * ── 4. 재시도 ──
   *
   * 라우트를 풀면 다음 요청은 진짜 백엔드로 간다. 같은 버튼을 누르는 것이 요점이다 — 시트는
   * 키를 모르고 부모가 쥔 것을 다시 쓸 뿐이라, 화면에서 할 일이 「다시 보내기」 하나다.
   */
  await page.unroute('**/feedback')

  const saved = page.waitForResponse(
    (response) => isFeedbackPost(response.request()) && response.status() === 201,
  )
  await retry.click()
  await saved

  /*
   * 두 요청을 나란히 놓고 본다. 여기서만 확인되는 것이 셋이다.
   *
   * - **같은 멱등 키.** 재시도가 새 키로 나가면 첫 요청이 저장됐던 경우에 409가 되고, 사용자는
   *   쓴 글을 잃는다 (파일 헤더).
   * - **Bearer 토큰.** 세션 토큰은 URL이 아니라 헤더로 간다 (`App.tsx`의 `sessionToken` 주석 —
   *   쿼리에 실으면 히스토리·액세스 로그·Referer에 남는다).
   * - **`contactEmail` 키 자체가 없다.** 빈 문자열을 서버가 미입력으로 보긴 하지만 그건 서버
   *   쪽 관용이고, 우리가 기대고 있을 계약이 아니다 (`toPayload`). 이메일을 지웠으니 나가는
   *   본문에 그 키가 있어서는 안 된다 — 이 서비스가 받는 유일한 개인 식별 정보다.
   */
  expect(posts).toHaveLength(2)
  const [first, second] = posts
  const firstKey = first.headers()['idempotency-key']
  expect(firstKey, '첫 요청에 Idempotency-Key가 없다').toBeTruthy()
  expect(second.headers()['idempotency-key'], '재시도가 새 키로 나갔다').toBe(firstKey)
  expect(second.headers()['authorization']).toMatch(/^Bearer .+/)

  const payload = second.postDataJSON() as Record<string, unknown>
  expect(payload.rating).toBe(RATING)
  expect(payload.body).toBe(BODY)
  expect('contactEmail' in payload, '적지 않은 이메일이 본문에 실렸다').toBe(false)

  /*
   * ── 5. 완료 ──
   *
   * 제목까지 바뀐다 (`FEEDBACK_DONE_TITLE`). 완료는 되돌아갈 수 없는 끝이라 입력칸이 치워지고
   * [닫기]만 남는다.
   */
  await expect(sheet.getByRole('heading', { name: FEEDBACK_DONE_TITLE })).toBeVisible()
  await sheet.getByRole('button', { name: FEEDBACK_CLOSE, exact: true }).click()
  await expect(sheet).toBeHidden()

  /*
   * 진입 버튼이 한 줄 인사로 바뀐다. 버튼을 남겨 두면 눌러 본 사람이 「이미 보냈어요」를 받는데
   * 그건 실패로 읽힌다 (`ResultScreen`의 판단). 그래서 후기 하나가 목록에서 빠진다 —
   * `extra` 없이 부르는 것이 곧 그 단언이다.
   */
  await expect(page.getByText(FEEDBACK_DONE_CAPTION, { exact: true })).toBeVisible()
  await expectResultFooterButtons(page)

  /*
   * ── 6. 409 ──
   *
   * 리로드하면 이 문서의 상태(`feedbackDone`·멱등 키)가 통째로 사라지고 진입 버튼이 돌아온다.
   * 세션은 살아남는다 — 토큰과 세션 id가 탭 세션 저장소에 있어(`session/webSession.ts`) 문서를
   * 다시 읽어도 같은 결과 화면이 뜬다. **새로고침 뒤 다시 보낸 사람**이 정확히 이 자리이고,
   * 그때 새 키로 나가는 요청을 서버가 409 `FEEDBACK_ALREADY_SUBMITTED`로 돌려준다.
   *
   * 화면은 그것을 실패로 그리지 않는다 — 사용자가 하려던 일(후기가 개발팀에 닿는 것)은 이미
   * 이루어져 있다 (`sendFeedback` 파일 헤더).
   */
  await page.reload()
  await expect(page).toHaveURL(/screen=result/)

  const reopened = page.getByRole('button', { name: FEEDBACK_OPEN, exact: true })
  await expect(reopened).toBeVisible()
  await reopened.click()

  await expect(page.getByRole('dialog', { name: FEEDBACK_TITLE })).toBeVisible()
  const secondSheet = page.getByRole('dialog')
  await secondSheet.getByRole('textbox', { name: FEEDBACK_GUIDE }).fill('두 번째 후기')

  const conflict = page.waitForResponse(
    (response) => isFeedbackPost(response.request()) && response.status() === 409,
  )
  await secondSheet.getByRole('button', { name: FEEDBACK_SUBMIT, exact: true }).click()
  await conflict

  await expect(secondSheet.getByText(FEEDBACK_ALREADY, { exact: true })).toBeVisible()

  /*
   * 세 번째 요청은 **새 키**로 나갔어야 한다 — 리로드가 문서를 새로 읽어 `feedbackKey` ref가
   * 비어 있는 상태에서 시작하기 때문이다. 이 단언이 없으면 위 「같은 키」 단언이 사실은
   * 「키가 늘 같다」였을 가능성이 남는다.
   */
  expect(posts).toHaveLength(3)
  expect(posts[2].headers()['idempotency-key']).not.toBe(firstKey)
})
