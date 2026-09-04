/**
 * 웹 단독 세션 (KAN-31 Stage 1) — `POST {apiBase}/v0/sessions` (API 명세서 §3.1).
 *
 * 앱 없이 브라우저로 공유 링크를 연 사람에게도 세션이 있어야 한다. 앱 안에서는 네이티브가
 * 세션을 만들어(KAN-9) 토큰을 브리지로 건네지만, 브라우저에는 그 경로가 없다 — 그래서 이
 * 모듈이 **브라우저 실행의 세션 생성 주체**다. 인증이 필요 없는 유일한 엔드포인트라(§2.1)
 * 웹이 직접 부를 수 있다.
 *
 * ## 재응시도 같은 호출이다 (§3.1, KAN-107)
 *
 * 이전 세션의 토큰을 `Authorization: Bearer`로 함께 보내면 서버가 그 세션을 폐기하고 새
 * 세션을 준다. 만료됐거나 없는 토큰은 조용히 무시되므로, "이전 세션이 있으면 실어 보낸다"는
 * 규칙 하나로 첫 응시와 재응시를 같은 코드가 처리한다 — 갈래를 만들면 만료 판정을 클라이언트가
 * 떠안게 되고, 그 판정은 서버 시계와 어긋난다.
 *
 * ## `Idempotency-Key`는 싣지 않는다
 *
 * 멱등 키가 필요한 것은 중복 접수가 비용이 되는 POST뿐이다 (§2.2 — 녹음 업로드, 답안 제출,
 * 완료 요청). 세션 생성은 중복 호출이 고아 세션 하나를 남길 뿐이고, 그 방어는 IP 분당 제한
 * (§2.5)이 이미 하고 있다. 명세가 이 엔드포인트에 키를 요구하지 않는 이유도 같다.
 */

import { readErrorEnvelope, readJson } from '../analysis/errorEnvelope'
import { newIdempotencyKey } from '../net/idempotencyKey'
import { isRetryableStatus } from '../net/retryableStatus'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { sanitizeCampaignToken } from './campaign'

const browserFetch: FetchLike = (input, init) => globalThis.fetch(input, init)

/**
 * 익명 집계에 실리는 이 빌드의 버전 (§3.1 `client.appVersion`, 최대 32자).
 * 배포 파이프라인이 값을 주지 않는 개발 빌드에서는 `web-dev`로 남는다 — 집계에서 개발
 * 트래픽이 버전 이름만으로 갈라진다.
 */
const APP_VERSION = ((import.meta.env.VITE_APP_VERSION as string | undefined) ?? 'web-dev').slice(0, 32)

/** 저장소 키. 오리진 안에서 우리 것임을 알아볼 수 있게 네임스페이스를 붙인다 */
const WEB_SESSION_KEY = 'accentury.webSession'

/** 저장 가능 여부를 재는 시험용 키. 세션 키와 같은 네임스페이스를 쓰고 잰 자리에서 바로 지운다 */
const PROBE_KEY = 'accentury.webSession.probe'

/**
 * 발급받은 세션 중 웹이 쓰는 부분.
 *
 * `scoreVersion`은 담지 않는다 — 서버가 세션에 고정해 두는 값이라 채점도 결과 조회도 서버가
 * 알아서 하고, 웹 화면 중 이 값을 읽는 곳이 없다. 안 쓰는 값을 저장소에 남기면 나중에 읽는
 * 사람이 어딘가에서 쓰는 줄 안다 (`fetchResult`가 같은 이유로 읽지 않는 필드를 명시한다).
 */
export interface WebSession {
  sessionId: string
  /** `Authorization: Bearer`로 보낼 불투명 토큰. 응답에서 딱 한 번 오고 서버에는 해시만 남는다 */
  sessionToken: string
  /** 이 세션에 고정된 정의 버전. 문항 조회(`GET /v0/tests/{testVersion}`)에 그대로 넣는다 */
  testVersion: string
  /** 토큰 만료 시각 (UTC ISO-8601). 기본 30분 */
  expiresAt: string
  /**
   * 목소리 점검이 잰 이 화자의 중심 음높이 (Hz, KAN-31 4단계). 문항 화면의 '내 억양' 곡선이
   * y축 중심으로 쓴다 (`userCurve.ts`).
   *
   * **서버가 주는 값이 아니라 웹이 재서 붙이는 값이라 선택적이다.** `createWebSession`의 201
   * 본문에는 없고, [saveWebSession] 직전에 [VoiceCheckScreen]이 넘긴 값을 얹는다. 값이 없어도
   * 세션은 성립한다 — 곡선은 그 녹음에서 중심을 다시 잡는 폴백으로 내려간다.
   *
   * 세션과 함께 저장하는 이유는 수명이 같기 때문이다. 화면 전환이 문서를 다시 로드하므로
   * 중심도 저장소를 거쳐야 문항 화면에 닿고, 재응시로 세션이 바뀌면 그때 다시 잰 값이 같은
   * 키를 덮어쓴다 — 따로 두면 새 세션에 옛 화자의 중심이 붙어 있을 수 있다.
   */
  userCurveCenterHz?: number
}

/** 세션 생성 하나에 필요한 선택 입력. 둘 다 없으면 평범한 첫 응시다 */
export interface CreateWebSessionOptions {
  /** 공유 링크가 실어 온 유입 코드 (`?c=`). 형태가 어긋나면 싣지 않는다 */
  campaignToken?: string | null
  /** 재응시라면 폐기할 이전 세션의 토큰 (§3.1) */
  previousToken?: string | null
}

/** 봉투의 code·retryable·retryAfterMs를 실은 세션 생성 실패 (`UploadError`와 같은 모양이다) */
export class WebSessionError extends Error {
  readonly code: string | null
  readonly retryable: boolean
  /** 429가 지시한 대기(ms). 그 외에는 null */
  readonly retryAfterMs: number | null

  constructor(message: string, code: string | null, retryable: boolean, retryAfterMs: number | null = null) {
    super(message)
    this.name = 'WebSessionError'
    this.code = code
    this.retryable = retryable
    this.retryAfterMs = retryAfterMs
  }
}

/**
 * 익명 세션을 만든다.
 *
 * @throws WebSessionError 네트워크 실패 / 봉투가 말한 오류(429 `RATE_LIMITED` 포함) /
 *   봉투 없는 HTTP 오류 / 201인데 본문이 계약과 다름
 */
export async function createWebSession(
  apiBase: string,
  options: CreateWebSessionOptions = {},
  fetchImpl: FetchLike = browserFetch,
): Promise<WebSession> {
  const campaignToken = sanitizeCampaignToken(options.campaignToken)
  const previousToken = options.previousToken?.trim() ?? ''

  let response: Response
  try {
    response = await fetchImpl(`${apiBase.replace(/\/+$/, '')}/v0/sessions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // 서버 로그와 이 요청을 잇는 값 (녹음 업로드와 같은 규칙). 실패 제보를 추적하는 실이다.
        'X-Correlation-Id': newIdempotencyKey(),
        // 이전 세션이 있을 때만 붙인다. 빈 Bearer를 보내면 서버가 형식 위반으로 볼 수 있다.
        ...(previousToken === '' ? {} : { Authorization: `Bearer ${previousToken}` }),
      },
      body: JSON.stringify({
        // 형태가 어긋난 코드는 필드째 빼고 보낸다 (campaign.ts 참고).
        ...(campaignToken === null ? {} : { campaignToken }),
        client: { platform: 'WEB', appVersion: APP_VERSION },
      }),
    })
  } catch {
    throw new WebSessionError('네트워크 오류로 테스트를 시작하지 못했어요', null, true)
  }

  const parsed = await readJson(response)

  if (response.ok) {
    const session = readSession(parsed)
    if (session !== null) return session
    /*
     * 세션은 만들어졌는데 토큰을 읽지 못했다. 재시도 가능으로 둔다 — 이 응답의 세션은 우리가
     * 쓸 수 없으니 사실상 없는 것과 같고, 다시 부르면 새 세션이 온다. 성공으로 처리하면 빈
     * 토큰을 든 채 문항 화면에 들어가 모든 요청이 401로 막힌다.
     */
    throw new WebSessionError('테스트를 시작했지만 확인을 받지 못했어요', null, true)
  }

  const envelope = readErrorEnvelope(response, parsed)
  if (envelope !== null) {
    throw new WebSessionError(envelope.message, envelope.code, envelope.retryable, envelope.retryAfterMs)
  }
  // 봉투가 없으면 서버가 재시도 여부를 알려주지 않은 것이다 — 상태 코드로 판단한다
  // (`net/retryableStatus`가 그 판정의 유일한 정의다).
  throw new WebSessionError(
    `테스트를 시작하지 못했어요 (HTTP ${response.status})`,
    null,
    isRetryableStatus(response.status),
  )
}

/**
 * 이 브라우저에서 세션 저장소를 실제로 쓸 수 있는가 (KAN-31).
 *
 * `typeof sessionStorage`나 존재 여부로는 판정할 수 없다. 사생활 보호 모드·쿠키 전면 차단·
 * 일부 인앱 브라우저는 **API는 그대로 노출한 채** 접근에서 던지거나, 던지지 않고 쓰기를
 * 조용히 버린다. 그래서 값을 하나 써 보고 **되읽어 같은 값인지**까지 확인한다 — 뒤쪽 경우를
 * 잡아내는 것이 되읽기다.
 *
 * 판정이 필요한 이유는 이 흐름이 화면 전환마다 문서를 다시 로드하기 때문이다 (`saveWebSession`
 * 주석). 토큰이 리로드를 넘지 못하면 다음 문서의 요청이 전부 토큰 없이 나간다.
 */
export function isWebSessionStorageAvailable(): boolean {
  try {
    sessionStorage.setItem(PROBE_KEY, PROBE_KEY)
    const echoed = sessionStorage.getItem(PROBE_KEY)
    sessionStorage.removeItem(PROBE_KEY)
    return echoed === PROBE_KEY
  } catch {
    // 접근 자체가 거부된 저장소다. 쓸 수 없다는 결론은 조용히 버려지는 경우와 같다.
    return false
  }
}

/**
 * 발급받은 세션을 저장한다.
 *
 * ## `sessionStorage`인 이유
 *
 * 토큰은 탭과 함께 사라져야 한다. `localStorage`에 두면 브라우저를 껐다 켜도 남아, 공용 기기의
 * 다음 사용자가 남의 세션 토큰으로 남의 결과를 열 수 있다. 서버가 결과를 24시간만 보관하는 것도
 * 같은 방향의 결정이라, 클라이언트가 그보다 오래 토큰을 들고 있을 이유가 없다.
 *
 * 그렇다고 메모리에만 둘 수도 없다. 화면 전환(`goToResult`·`goToIntro`)이 **같은 문서를 다시
 * 로드**하는 방식이라 자바스크립트 상태가 매번 통째로 사라진다 — 리로드를 견디면서 탭 수명은
 * 넘지 않는 저장소가 정확히 `sessionStorage`다.
 *
 * **URL에는 절대 싣지 않는다.** 쿼리에 넣으면 토큰이 브라우저 히스토리·서버 액세스 로그·
 * Referer 헤더에 남는다 (결과 화면이 토큰을 브리지에서만 읽는 것과 같은 규칙).
 */
export function saveWebSession(session: WebSession): void {
  try {
    sessionStorage.setItem(WEB_SESSION_KEY, JSON.stringify(session))
  } catch {
    /*
     * 사생활 보호 모드 등에서는 접근 자체가 던진다. 저장에 실패해도 이번 테스트는 그대로
     * 진행된다 — 리로드를 건너뛰는 화면 안에서는 이 반환값 없이도 토큰이 살아 있기 때문이다.
     * 여기서 던지면 저장소가 없다는 이유로 응시 자체가 막힌다.
     */
  }
}

/** 저장된 세션. 없거나 읽을 수 없으면 null */
export function loadWebSession(): WebSession | null {
  try {
    const raw = sessionStorage.getItem(WEB_SESSION_KEY)
    return raw === null ? null : readSession(JSON.parse(raw))
  } catch {
    // 접근 거부(사생활 보호 모드)와 깨진 JSON을 같이 다룬다 — 어느 쪽이든 쓸 세션이 없다.
    return null
  }
}

/** 저장된 세션을 지운다. 지울 것이 없어도 오류가 아니다 */
export function clearWebSession(): void {
  try {
    sessionStorage.removeItem(WEB_SESSION_KEY)
  } catch {
    // 저장하지 못한 것을 지우지 못하는 것도 같은 상황이다. 할 일이 없다.
  }
}

/**
 * 저장된 세션 토큰. 없으면 빈 문자열이다.
 *
 * 함수인 채로 화면에 넘기는 이유: 요청 시점마다 읽어야 한다. 미리 잡아 두면 재응시로 세션이
 * 바뀐 뒤에도 낡은 토큰을 계속 쓴다 (진행 화면이 브리지 토큰에 같은 규칙을 적용한다).
 */
export function getWebSessionToken(): string {
  return loadWebSession()?.sessionToken ?? ''
}

/** 201 본문(또는 저장된 JSON)을 세션으로 읽는다. 네 값이 다 있는 문자열이 아니면 null */
function readSession(body: unknown): WebSession | null {
  if (typeof body !== 'object' || body === null) return null
  const { sessionId, sessionToken, testVersion, expiresAt, userCurveCenterHz } = body as Record<
    string,
    unknown
  >
  if (![sessionId, sessionToken, testVersion, expiresAt].every(isFilledString)) return null
  return {
    sessionId: sessionId as string,
    sessionToken: sessionToken as string,
    testVersion: testVersion as string,
    expiresAt: expiresAt as string,
    /*
     * 중심은 있으면 싣고 없으면 뺀다 — 서버 응답에는 애초에 없는 필드이고(웹이 붙인다),
     * 저장된 값이 깨졌으면 없는 것과 같이 다뤄야 한다. 여기서 걸러야 0이나 NaN이 y축 중심으로
     * 내려가 곡선이 통째로 사라지는 일이 없다 (`userCurveDisplayPoints`는 그런 중심을 버린다).
     */
    ...(isPositiveNumber(userCurveCenterHz) ? { userCurveCenterHz: userCurveCenterHz as number } : {}),
  }
}

function isPositiveNumber(value: unknown): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

function isFilledString(value: unknown): boolean {
  return typeof value === 'string' && value.trim() !== ''
}
