/**
 * 응시 하나를 가리키는 익명 상관 키 (KAN-33, Codex 검증 지적 반영).
 *
 * ## 왜 필요한가
 *
 * AC 1은 "한 테스트 세션의 핵심 이벤트 순서를 분석할 수 있다"인데, 그러려면 이벤트들을
 * **응시 단위로 묶을 축**이 있어야 한다. GA4가 자동으로 붙이는 세션(`ga_session_id`)은
 * 앱·브라우저 사용 세션이라 30분 무활동 전까지 하나로 유지되고, 그 안에서 재응시하면
 * (`retest_started`, KAN-34) 두 번의 응시가 한 덩어리로 섞인다 — 재응시는 우리 제품이
 * 권하는 행동이라 실제로 일어난다.
 *
 * ## 왜 서버 세션 id를 쓰지 않는가
 *
 * 티켓이 금지한다 (AC 2 "세션 토큰·세션 id를 이벤트 속성에 포함하지 않는다"). 금지의 이유도
 * 분명하다: 서버 세션 id는 결과 조회·서버 로그와 이어진 값이라, 계측에 실으면 익명 집계와
 * 그쪽 세계가 한 줄로 연결된다. 이 키는 **계측 안에서만 의미가 있는 무작위 값**이고 어디에도
 * 다시 나타나지 않는다 — 사람을 가리키지 않고, 응시가 끝나면 쓸모가 없어진다.
 *
 * ## 수명
 *
 * `sessionStorage`에 **어느 세션의 것인지와 함께** 둔다. 세션 id가 바뀌면 새 응시라는 뜻이라
 * 키도 새로 발급한다 — 재응시가 정확히 그 경로다(네이티브가 새 세션을 만들어 인트로부터 다시
 * 연다). 저장소가 `sessionStorage`인 이유는 세션 토큰과 같다 (`session/webSession.ts`):
 * 탭과 함께 사라져야 하고, 문서 전환(인트로 → 문항 → 결과는 전부 리로드다)은 건너야 한다.
 *
 * 앱 안에서도 같은 규칙이 그대로 선다. 세션을 만드는 것은 네이티브지만 그 id는 WebView의
 * 진입 쿼리(`?sessionId=`)로 들어오고, 이 모듈은 그 값만 본다.
 */

/*
 * 무작위 값 생성은 멱등 키와 같은 함수를 쓴다. 쓰임은 다르지만 필요한 것이 같고(충돌하지 않는
 * 불투명한 값), 그 함수 주석의 제약이 여기에도 그대로 걸린다 — `crypto.randomUUID`는 보안
 * 컨텍스트 전용이라 개발 WebView(`http://10.0.2.2:5173`)에는 없어 직접 v4를 만든다.
 */
import { newIdempotencyKey } from '../net/idempotencyKey'

const TEST_ID_KEY = 'accentury:analytics:test'

interface StoredTestId {
  /** 이 키가 속한 세션. 값이 달라지면 새 응시다 */
  sessionId: string
  testId: string
}

/**
 * 이 세션의 상관 키를 확보한다 — 없거나 다른 세션의 것이면 새로 발급한다.
 *
 * 화면이 세션 id를 아는 자리에서 부른다 (`App.tsx`). [track]이 부르지 않는 이유는 그쪽이
 * 세션을 모르기 때문이고, 알게 만들면 계측 창구가 진입 쿼리 규칙을 함께 알아야 한다.
 *
 * 저장에 실패해도 발급한 값을 돌려준다. 그 문서 안에서는 이벤트가 같은 키로 묶이고, 다음
 * 문서에서 끊길 뿐이다 — 계측 하나 때문에 응시를 막을 이유가 없다는 규칙이 여기에도 걸린다.
 *
 * ## `issued`가 곧 "응시가 시작됐다"
 *
 * 키를 **처음 발급한 순간**은 이 세션으로 문항 화면에 처음 들어온 순간이다. 그래서 호출자가
 * 시작 계측(`referral_test_started`)을 그 신호에 건다 — 새 세션이면 한 번, 같은 세션으로
 * 화면을 다시 열면(백그라운드 복귀·리로드) 다시 세지 않는다.
 *
 * 시작을 세는 규칙이 실행마다 갈리지 않는 것이 요점이다. 세션을 만드는 주체는 실행에 따라
 * 다르지만(웹 단독은 이 문서가, 앱은 네이티브가) **문항 화면에 처음 도달했다**는 사실은 두
 * 실행에서 같은 자리에 있다.
 */
export function ensureTestId(sessionId: string): { testId: string; issued: boolean } | null {
  if (sessionId.trim() === '') return null

  const stored = readStored()
  if (stored !== null && stored.sessionId === sessionId) return { testId: stored.testId, issued: false }

  const testId = newIdempotencyKey()
  write({ sessionId, testId })
  return { testId, issued: true }
}

/**
 * 지금 응시의 상관 키. 없으면 null이다 — 인트로처럼 세션이 생기기 전의 이벤트가 그 자리이고,
 * 저장소를 쓸 수 없는 브라우저도 여기로 온다.
 */
export function currentTestId(): string | null {
  return readStored()?.testId ?? null
}

/**
 * 상관 키를 버린다 — **인트로가 뜰 때** 부른다 (`App.tsx`).
 *
 * 인트로는 어느 응시에도 속하지 않는 화면이고, 여기에 도달했다는 것은 직전 응시가 끝났다는
 * 뜻이다(결과 화면의 [다시 테스트하기]가 이 문서를 다시 연다). 지우지 않으면 다음 응시의
 * 유입 계측이 **직전 응시의 키를 달고** 나가, 순서 분석에서 A의 이벤트 목록 끝에 B의 시작이
 * 붙는다.
 */
export function clearTestId(): void {
  try {
    sessionStorage.removeItem(TEST_ID_KEY)
  } catch {
    /* 저장소를 못 쓰는 브라우저. 애초에 저장된 것도 없다 */
  }
}

/** 저장된 값. 형태가 어긋나면 없는 것으로 본다 — 옛 버전이 남긴 값도 여기서 걸린다 */
function readStored(): StoredTestId | null {
  try {
    const raw = sessionStorage.getItem(TEST_ID_KEY)
    if (raw === null) return null
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    const { sessionId, testId } = parsed as Record<string, unknown>
    if (typeof sessionId !== 'string' || typeof testId !== 'string') return null
    return { sessionId, testId }
  } catch {
    // 저장소를 못 쓰는 브라우저(사생활 보호 모드·쿠키 차단)와 깨진 값이 같은 자리다.
    return null
  }
}

function write(value: StoredTestId): void {
  try {
    sessionStorage.setItem(TEST_ID_KEY, JSON.stringify(value))
  } catch {
    /* 저장하지 못해도 이번 문서의 이벤트는 같은 키로 묶인다 ([ensureTestId] 주석). */
  }
}
