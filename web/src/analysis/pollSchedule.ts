/**
 * 분석 상태 폴링 간격 계산 (KAN-14 Stage 1).
 *
 * 이 모듈에는 **타이머도 fetch도 없다.** 다음 조회까지 몇 ms를 기다릴지, 혹은 이제 그만둘지를
 * 결정하는 순수 함수뿐이다. 그렇게 나눈 이유는 두 가지다:
 *
 * - 폴링 규칙(요구 1·3·5·6항)은 "간격을 어떻게 정하는가"에 대한 규칙이라, 타이머와 섞이면
 *   테스트가 가짜 시계에 의존하게 된다. 여기서는 값 하나 넣고 값 하나 받는 검증으로 끝난다.
 *   실제 대기와 시각 측정은 훅(Stage 3)이 맡는다.
 * - `progressMachine`이 "문항 진행 중에는 네트워크가 없다"를 구조로 보증했듯, 이쪽은 반대로
 *   "간격을 화면이 임의로 정하지 않는다"(요구 1항)를 구조로 보증한다. 화면이 setTimeout에
 *   숫자를 직접 적을 수 있으면 규칙은 언젠가 두 벌이 된다.
 *
 * ## 요구 1항과 3항이 서로 다른 값을 지시한다
 *
 * 1항은 "서버가 준 `pollAfterMs`를 반드시 따르고 클라이언트가 간격을 임의로 고정하지 않는다",
 * 3항은 "800ms → 1.2s → 2s → 3s, 상한 5s"다. 서버가 200ms를 주면 두 규칙이 충돌한다.
 *
 * **`max(서버 값, 백오프 값)`으로 푼다.** 두 값은 서로 다른 것을 막는 하한이다 —
 * 서버 값은 *서버가 혼잡할 때* 요청을 밀어내는 하한(1항의 목적)이고, 백오프는 *분석이 길어질 때*
 * 사용자당 요청 수가 300회로 뛰는 것을 막는 하한(3항의 목적)이다. 둘 중 큰 값을 쓰면 두 목적이
 * 모두 지켜지고, 어느 쪽도 상대를 무력화하지 못한다. 서버가 5s를 주면 그 값이 이기므로
 * "클라이언트가 간격을 고정한다"는 1항 위반도 아니다.
 */

/**
 * 백오프 사다리 (요구 3항). 인덱스는 지금까지 마친 조회 횟수다 —
 * 첫 조회 직후가 `[0]`이라 800ms를 쓴다.
 */
export const BACKOFF_MS = [800, 1200, 2000, 3000] as const

/** 사다리를 넘어선 회차의 상한 (요구 3항) */
export const BACKOFF_CEILING_MS = 5000

/** 자동 폴링 누적 상한 (요구 5항). 넘으면 [다시 시도] 버튼으로 전환한다 */
export const POLL_BUDGET_MS = 60_000

/**
 * 지터 폭 ±20% (요구 3항). 동시에 테스트를 시작한 사용자들의 요청이 같은 순간에 몰리는 것을
 * 흩는 장치라, 폭 자체보다 "매 회차 새로 뽑는다"가 핵심이다.
 */
export const JITTER_RATIO = 0.2

/** 난수 공급자. 테스트가 지터를 고정하기 위해 주입한다 (기본은 Math.random) */
export type Random = () => number

/** 다음 간격을 정하는 데 필요한 전부. 상태를 이 모듈이 들고 있지 않은 이유는 훅이 정본이라서다 */
export interface PollInput {
  /** 지금까지 **마친** 조회 횟수. 첫 조회 전이면 0이다 */
  round: number
  /**
   * 대기 화면에 들어온 뒤 흐른 실제 시간(ms). 훅이 시계로 잰 값을 넘긴다 —
   * 간격의 합이 아니라 실제 경과여야 한다. 응답이 느리면 둘이 벌어지는데, 사용자가 체감하는
   * 것은 화면 앞에서 흐른 시간이지 우리가 기다리기로 한 시간이 아니다.
   */
  elapsedMs: number
  /**
   * 서버가 준 `pollAfterMs` (요구 1항). 아직 한 번도 못 받았으면 null —
   * 그때는 백오프 사다리만으로 정한다.
   */
  serverPollAfterMs: number | null
  /**
   * 429가 지시한 대기(ms) (요구 6항). null이면 429가 아니었다는 뜻이다.
   * 이 값이 있으면 다른 모든 계산을 덮는다.
   */
  retryAfterMs: number | null
}

/**
 * 다음 행동. `EXHAUSTED`는 실패가 아니라 "자동 폴링을 멈추고 사용자에게 [다시 시도]를 준다"는
 * 뜻이다 — 분석은 여전히 서버에서 돌고 있을 수 있다.
 */
export type PollPlan =
  | { kind: 'WAIT'; delayMs: number }
  | { kind: 'EXHAUSTED' }

/**
 * 다음 조회까지 기다릴 시간을 정한다.
 *
 * ## 예산을 넘길 대기는 시작하지 않는다
 *
 * 경과 55초에 5초를 기다리면 60초 정확히 끝나지만, 경과 58초에 5초는 63초다. 후자를 시작하면
 * "누적 60초 상한"(요구 5항)이 지켜지지 않는다. 그래서 **간격을 먼저 계산하고, 그 대기가
 * 예산 안에 착지하는지 보고 나서** 기다린다. 예산을 넘길 대기는 아예 시작하지 않고 바로
 * 사용자에게 통제권을 넘긴다 — 기다리게 해 놓고 어차피 버릴 요청을 보내는 것보다 낫다.
 *
 * 이 규칙 때문에 긴 `Retry-After`(예: 30초)를 후반에 받으면 즉시 EXHAUSTED가 된다.
 * 의도한 동작이다: 서버가 30초 뒤에 오라고 했는데 자동 폴링 예산이 10초밖에 안 남았다면,
 * 화면이 조용히 기다리는 것보다 [다시 시도]를 보여 주는 편이 정직하다.
 *
 * @param random 지터용 난수 (0 이상 1 미만). 테스트에서 고정한다
 */
export function planNextPoll(input: PollInput, random: Random = Math.random): PollPlan {
  const delayMs = nextDelayMs(input, random)
  if (input.elapsedMs + delayMs > POLL_BUDGET_MS) {
    return { kind: 'EXHAUSTED' }
  }
  return { kind: 'WAIT', delayMs }
}

/**
 * 지터까지 적용한 다음 간격. [planNextPoll]이 예산 판정에 쓰는 값이며, 예산과 무관하게
 * 간격만 보고 싶을 때(진단·테스트)도 쓴다.
 */
export function nextDelayMs(input: PollInput, random: Random = Math.random): number {
  const { round, serverPollAfterMs, retryAfterMs } = input

  /*
   * 429는 다른 규칙을 전부 덮는다 (요구 6항). "Retry-After 전까지 요청하지 않는다"가 계약이므로
   * 지터도 **늘리는 쪽으로만** 준다 — 양방향 지터를 주면 20% 확률 구간에서 서버가 금지한
   * 시각보다 먼저 요청하게 된다. 몰림 방지는 늘리는 쪽만으로도 충분하다.
   */
  if (retryAfterMs !== null) {
    return Math.round(nonNegative(retryAfterMs) * (1 + JITTER_RATIO * random()))
  }

  const base = Math.max(backoffMs(round), nonNegative(serverPollAfterMs ?? 0))
  return Math.round(base * (1 + JITTER_RATIO * (random() * 2 - 1)))
}

/** 회차별 백오프 사다리 값. 사다리를 넘어서면 상한으로 고정된다 */
export function backoffMs(round: number): number {
  if (round < 0) return BACKOFF_MS[0]
  return BACKOFF_MS[round] ?? BACKOFF_CEILING_MS
}

/**
 * 서버가 준 값이 음수거나 NaN이면 0으로 본다. 그런 값은 계약 위반이지만, 여기서 던지면
 * 응답 하나 때문에 대기 화면이 통째로 죽는다 — 0으로 접으면 백오프 사다리가 하한을 지키므로
 * 최악이라도 "서버 지시를 무시하고 백오프대로 도는" 상태에 머문다.
 */
function nonNegative(value: number): number {
  return Number.isFinite(value) && value > 0 ? value : 0
}
