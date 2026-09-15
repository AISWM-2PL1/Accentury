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
import { AD_CONSENT_DENY, AD_CONSENT_TITLE } from '../../src/ads/adConsentText'
import { STORE_PENDING_CAPTION } from '../../src/audio/storeText'
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
 * 지역 화면에서 고르는 지역 (KAN-202). 어느 것이든 상관없지만, 세션 본문에 실린 값을 단언할 때
 * 같은 코드를 봐야 하므로 라벨과 코드를 한 자리에 묶어 둔다 (`regions.ts`의 표와 같은 짝).
 */
const E2E_REGION = { label: '경남', code: 'GYEONGNAM' } as const

/**
 * 맞춤형 광고 동의 시트가 떠 있으면 「일반 광고만 보기」로 지나간다 (KAN-197 2단계).
 *
 * **광고 ID가 들어간 빌드에서만 뜬다** (팀 결정 2026-09-13, PR #109 리뷰). `VITE_ADSENSE_*`가
 * 빈 판에서는 광고가 나가지 않으므로 동의를 묻지도 않는다 (`ads/adConsent.ts`의
 * `resolveAdConsentSource`) — 그런 판에서 이 헬퍼가 아무 일도 하지 않고 지나가는 것이 정상이다.
 *
 * ID가 있는 빌드의 첫 방문에는 인트로 위에 이 시트가 덮인다. 막이 화면 전체를 가리므로
 * (`.ad-consent-sheet`가 `position: fixed; inset: 0`) 지나치지 않으면 [내 억양 테스트하기]가
 * 다른 요소에 가려진 상태로 남고, Playwright의 actionability 검사가 클릭을 기다리다 시간
 * 초과로 죽는다 — vitest의 `fireEvent`는 hit-testing이 없어 막을 뚫고 닿지만 실브라우저는 아니다.
 *
 * ## 왜 거부를 고르는가
 *
 * 3단계 AC가 「동의 거부 시 비맞춤 광고만」이라, E2E가 도는 동안 맞춤형 광고 요청이 나가면
 * 안 된다. 고른 값은 브라우저 저장소에 남지만(`ads/webAdConsentStore.ts`) Playwright는 테스트마다
 * 새 컨텍스트라 저장소가 비어 있고, 그래서 매번 다시 뜬다 — 한 번 고르면 그만인 헬퍼가 아니다.
 *
 * ## 왜 유무를 보고 가는가
 *
 * 지역 화면과 같은 원칙이다(아래 [startTest]의 「지역 화면은…」 절, `docs/wiki/browser-e2e.md`) —
 * 스펙은 자기가 어떤 판을 열었는지 모른다. 앱 WebView로 열리거나 ID 없는 빌드이거나 이미 고른
 * 컨텍스트에서는 시트가 없으므로, 빌드 변수가 아니라 **화면에 뜬 것**을 보고 지나간다.
 *
 * ## 인트로가 그려지기를 여기서 기다린다 (PR #109 리뷰 (2026-09-13))
 *
 * `isVisible()`은 기다리지 않는 즉답이라, `page.goto('/')` 직후에 부르면 React가 시트를 그리기
 * 전의 빈 순간을 「시트 없음」으로 읽는다. 그러면 헬퍼는 그냥 지나가고 다음 클릭이 뒤늦게 덮인
 * 막 밑에서 actionability를 기다리다 시간 초과로 죽는다 — 호출부 세 곳(`smoke`, `mic-blocked`
 * 둘) 중 하나라도 빠뜨리면 플레이키가 되므로, 호출자에게 맡기지 않고 이 함수가 인트로의 h1을
 * 먼저 기다린다. 시트는 그 h1과 같은 렌더에서 함께 그려지므로(`IntroScreen`이 둘을 한 번에
 * 반환한다) h1이 보인 뒤에는 기다릴 빈 순간이 없다.
 *
 * 셀렉터는 화면의 상수를 import한다 (셀렉터 규칙). 문안이 바뀌면 헬퍼가 조용히 못 찾는 대신
 * 컴파일이 따라온다.
 */
export async function passAdConsentIfShown(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

  const sheet = page.getByRole('dialog', { name: AD_CONSENT_TITLE })
  if (!(await sheet.isVisible())) return

  await sheet.getByRole('button', { name: AD_CONSENT_DENY, exact: true }).click()
  // 닫힘까지 확인한다 — 막이 걷히기 전에 다음 클릭으로 넘어가면 같은 시간 초과를 다시 만난다
  await expect(sheet).toBeHidden()
}

/**
 * 웹 단독 진입 → 시작 게이트 통과 → 문항 진행 화면.
 *
 * `bridge` 파라미터 없이 열면 `window.AccenturyBridge`도 없으므로 웹 단독 실행이 되고
 * (`bridge.ts`의 `isStandaloneWeb`), 스큐 게이트를 건너뛰고 인트로가 뜬다.
 *
 * 세션 응답을 URL 확인과 갈라서 보는 이유는 실패를 가르기 위해서다 — 201이 왔는데 URL이 안
 * 바뀌면 이동 쪽 문제이고, 201 자체가 안 오면 백엔드나 `/v0` 프록시 쪽이다.
 *
 * ## 지역 화면은 있을 수도, 없을 수도 있다 (KAN-202)
 *
 * 출신 지역 선택 화면은 빌드 변수 `VITE_REGION_SELECT`가 정확히 `'true'`인 번들에만 있다
 * (`regions.ts`의 `isRegionSelectEnabled`) — staging은 켜고 prod는 변수 자체가 없다. 그런데
 * 스펙은 **어느 빌드를 열었는지 모른다.** `E2E_BASE_URL`로 배포 환경을 겨눌 때는 화면이
 * 이미 굳은 번들이고, 로컬은 `playwright.config.ts`가 셸의 값을 개발 서버에 넘긴 대로다.
 * 스펙에 주소가 하나도 없어 같은 스펙이 로컬·staging 양쪽을 도는 것과 같은 원칙으로, 이
 * 헬퍼도 변수를 읽지 않고 **화면에 뜬 것을 보고** 간다 — 지역 화면이 떴으면 고르고 지나가고,
 * 점검 화면이 바로 떴으면 그대로 간다. 그래서 스펙 파일을 켠 판·끈 판으로 나누지 않고 한
 * 벌이 양쪽에서 돈다.
 *
 * 다만 "지나갔다"로 끝내지 않고 세션 생성 **요청 본문**까지 본다. 켜진 빌드는 고른 코드가
 * `region`으로 실려야 하고(AC "지역 선택을 거쳐 결과까지"), 꺼진 빌드는 키 자체가 없어야
 * 한다(AC "변수 없는 빌드는 본문에 region 없음", `createWebSession`이 null이면 필드째 뺀다).
 * 201 응답만 보면 서버가 받아 줬다는 것뿐이고 무엇을 보냈는지는 모른다 — 요청은
 * `waitForRequest`로 따로 잡아야 본문이 보인다.
 */
export async function startTest(page: Page): Promise<void> {
  await page.goto('/')

  /*
   * 인트로가 그려지기를 기다리는 일도 헬퍼가 맡는다 (PR #109 리뷰 (2026-09-13)). 첫 방문이면
   * 인트로 위에 동의 시트가 덮여 있고, 걷어내야 [시작하기]에 손이 닿는다.
   */
  await passAdConsentIfShown(page)

  /*
   * [시작하기]가 곧 마이크 권한 요청이다. `--use-fake-ui-for-media-stream`이 대화상자를
   * 자동 승인하므로 여기서 멈추지 않고, 승인되면 App이 지역 화면(켜진 빌드) 또는 목소리
   * 점검 화면으로 갈아 끼운다.
   */
  await page.getByRole('button', { name: '내 억양 테스트하기', exact: true }).click()

  /*
   * 둘 중 하나가 뜨기를 먼저 기다린 뒤에 어느 쪽인지 본다. 기다리지 않고 `isVisible()`부터
   * 부르면 권한 승인이 끝나기 전의 빈 순간을 "지역 화면 없음"으로 읽는다 — `isVisible()`은
   * 기다리지 않는 즉답이다.
   */
  const regionHeading = page.getByRole('heading', {
    level: 1,
    name: '출신 지역이 어디신가요?',
  })
  const voiceHeading = page.getByRole('heading', { name: '목소리를 확인할게요' })
  await expect(regionHeading.or(voiceHeading)).toBeVisible()

  const regionShown = await regionHeading.isVisible()
  if (regionShown) {
    /*
     * 라디오는 어휘 문항과 같은 `.choice__radio`라 1px + clip-path로 눈에서만 지워져 있다
     * (`answerVocabularyItem`의 주석). 같은 이유로 `force`. [다음]은 고르기 전에는 disabled라
     * 고른 뒤에만 눌린다.
     */
    await page.getByRole('radio', { name: E2E_REGION.label }).check({ force: true })
    await page.getByRole('button', { name: '다음', exact: true }).click()
  }
  await expect(voiceHeading).toBeVisible()

  /*
   * [다음]은 판정기가 `ready`일 때만 그려진다 — 이 버튼이 보인다는 것은 가짜 마이크의
   * 소리로 중심 음높이가 잠기고 볼륨 조건까지 통과했다는 뜻이다 (`VoiceCheckScreen`).
   */
  const next = page.getByRole('button', { name: '다음', exact: true })
  await expect(next).toBeVisible({ timeout: VOICE_CHECK_TIMEOUT_MS })

  const isSessionPost = (url: string, method: string) =>
    url.includes('/v0/sessions') && method === 'POST'
  const sessionRequest = page.waitForRequest((request) =>
    isSessionPost(request.url(), request.method()),
  )
  const session = page.waitForResponse((response) =>
    isSessionPost(response.url(), response.request().method()),
  )
  await next.click()
  expect((await session).status()).toBe(201)

  /*
   * 본문 단언. 지역 화면을 봤는지는 위에서 화면으로 판정한 값이라, 빌드 변수를 읽지 않고도
   * 두 빌드에서 각각 맞는 쪽을 검사한다.
   */
  const body = (await sessionRequest).postDataJSON() as Record<string, unknown>
  if (regionShown) {
    expect(body.region).toBe(E2E_REGION.code)
  } else {
    expect('region' in body).toBe(false)
  }

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

/**
 * 이 판이 **스토어 등록을 켠 빌드**를 보는가 (사용자 요청 2026-09-15).
 *
 * 앱이 아직 Play 스토어에도 App Store에도 없어, 기본 빌드의 [앱 다운로드]는 링크가 아니라
 * 비활성 버튼이다 (`src/audio/storeLink.ts`의 `storeListingReady`). 결과 화면을 보는 스펙은
 * 그 둘을 갈라 단언해야 하므로, 판정을 여기 한 곳에 둔다 — 스펙마다 `process.env`를 읽으면
 * 켜는 조건이 엇갈렸을 때 한쪽만 통과하고 그 사실이 실패 메시지에 남지 않는다.
 *
 * 값은 `playwright.config.ts`가 개발 서버에 넘긴 것과 **같은 환경 변수**를 읽는다. 그 설정이
 * 셸에 값이 없으면 빈 문자열로 못 박으므로(`.env.local`이 조용히 끼어들지 못한다), 여기서 본
 * 값과 브라우저가 받은 빌드가 언제나 같다.
 */
export const STORE_LISTING_READY = process.env.VITE_STORE_LISTING_READY === 'true'

/**
 * 결과 화면 푸터에 **있어야 할 버튼 이름 전부**. 후기 완료처럼 버튼이 줄어드는 자리는
 * `extra`를 비워 부른다.
 *
 * 개수를 상수로 적지 않고 이름에서 세는 이유가 이 티켓에서 드러났다. 예전에는 `toHaveCount(3)`
 * 처럼 숫자를 박아 뒀는데, 등록 전 [앱 다운로드]가 링크에서 **버튼**으로 바뀌면서 그 숫자가
 * 빌드마다 달라졌다 — 숫자만 고치면 켠 빌드가 깨지고, 조건을 숫자에 심으면 무엇이 늘었는지가
 * 사라진다. 이름 목록이면 개수와 정체가 같은 자리에서 나온다.
 */
export function resultFooterButtonNames(extra: string[] = []): string[] {
  return [
    // 등록 전에는 비활성 CTA가 버튼이라 이 목록에 낀다. 켜지면 <a>가 되어 빠진다
    ...(STORE_LISTING_READY ? [] : ['앱 다운로드']),
    '친구에게 공유하기',
    '다시 테스트하기',
    ...extra,
  ]
}

/**
 * 결과 화면 푸터의 버튼이 정확히 [resultFooterButtonNames] 그대로인지 본다.
 *
 * 개수까지 세는 이유는 예전 그대로다 — 「라벨은 맞는데 광고 버튼이 하나 늘었다」를 잡는다
 * (KAN-197). 다만 기대 개수를 목록에서 뽑으므로 빌드가 갈려도 스펙 한 벌로 돈다.
 */
export async function expectResultFooterButtons(page: Page, extra: string[] = []): Promise<void> {
  const names = resultFooterButtonNames(extra)
  await expect(page.getByRole('button')).toHaveCount(names.length)
  for (const name of names) {
    await expect(page.getByRole('button', { name, exact: true })).toBeVisible()
  }
}

/**
 * [앱 다운로드] CTA 한 벌 — 이 빌드가 내놓아야 할 모양인지 본다 (2026-09-15).
 *
 * 양쪽 다 **반대쪽이 없다**는 것까지 본다. 링크와 비활성 버튼이 한 화면에 같이 서면 어느
 * 쪽을 눌러야 하는지가 사라지고, 무엇보다 등록 전에 링크가 남아 있으면 이 변경이 막으려던
 * 죽은 스토어 페이지로 가는 길이 그대로라는 뜻이다.
 */
export async function expectAppDownloadCta(page: Page): Promise<void> {
  const link = page.getByRole('link', { name: '앱 다운로드', exact: true })
  const pending = page.getByRole('button', { name: '앱 다운로드', exact: true })

  if (STORE_LISTING_READY) {
    await expect(link).toBeVisible()
    await expect(pending).toHaveCount(0)
    return
  }

  await expect(pending).toBeVisible()
  await expect(pending).toBeDisabled()
  await expect(page.getByText(STORE_PENDING_CAPTION, { exact: true })).toBeVisible()
  await expect(link).toHaveCount(0)
}

/**
 * 마이크 차단 화면의 [앱으로 테스트하기] 한 벌 (2026-09-15).
 *
 * [expectAppDownloadCta]와 같은 규칙이되 이 화면이 더 예민하다 — 사유 셋 중 둘은 브라우저
 * 안에서 할 수 있는 일이 없어 이 CTA가 **유일한 출구**다 (`src/intro/MicBlockedScreen.tsx`).
 * 등록 전에 링크가 남아 있으면 그 유일한 출구가 없는 스토어 페이지로 끝난다.
 */
export async function expectAppTestCta(page: Page): Promise<void> {
  const link = page.getByRole('link', { name: '앱으로 테스트하기', exact: true })
  const pending = page.getByRole('button', { name: '앱으로 테스트하기', exact: true })

  if (STORE_LISTING_READY) {
    await expect(link).toBeVisible()
    await expect(pending).toHaveCount(0)
    return
  }

  await expect(pending).toBeVisible()
  await expect(pending).toBeDisabled()
  await expect(page.getByText(STORE_PENDING_CAPTION, { exact: true })).toBeVisible()
  await expect(link).toHaveCount(0)
}
