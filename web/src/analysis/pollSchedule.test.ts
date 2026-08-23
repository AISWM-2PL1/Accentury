import { describe, expect, it } from 'vitest'
import {
  BACKOFF_CEILING_MS,
  BACKOFF_MS,
  JITTER_RATIO,
  POLL_BUDGET_MS,
  backoffMs,
  nextDelayMs,
  planNextPoll,
  type PollInput,
} from './pollSchedule'

/** 지터 중앙값(변동 0). random() = 0.5면 양방향 지터가 1배가 된다 */
const noJitter = () => 0.5
/** 지터 하한 (-20%) */
const minJitter = () => 0
/** 지터 상한에 가까운 값 (+20%에 근접) */
const maxJitter = () => 1

function input(overrides: Partial<PollInput> = {}): PollInput {
  return { round: 0, elapsedMs: 0, serverPollAfterMs: null, retryAfterMs: null, ...overrides }
}

describe('backoffMs', () => {
  it('사다리를 회차 순서대로 오른다', () => {
    expect(BACKOFF_MS.map((_, round) => backoffMs(round))).toEqual([800, 1200, 2000, 3000])
  })

  it('사다리를 넘어선 회차는 상한으로 고정된다', () => {
    expect(backoffMs(BACKOFF_MS.length)).toBe(BACKOFF_CEILING_MS)
    expect(backoffMs(50)).toBe(BACKOFF_CEILING_MS)
  })
})

describe('nextDelayMs — 서버 값과 백오프 중 큰 쪽', () => {
  it('서버 값이 없으면 백오프 사다리를 쓴다', () => {
    expect(nextDelayMs(input({ round: 1 }), noJitter)).toBe(1200)
  })

  it('서버가 백오프보다 큰 값을 주면 서버 값이 이긴다 (혼잡 시 밀어내기)', () => {
    expect(nextDelayMs(input({ round: 0, serverPollAfterMs: 4000 }), noJitter)).toBe(4000)
  })

  it('서버가 백오프보다 작은 값을 줘도 백오프가 하한을 지킨다', () => {
    expect(nextDelayMs(input({ round: 2, serverPollAfterMs: 200 }), noJitter)).toBe(2000)
  })

  it('서버가 음수·NaN을 줘도 죽지 않고 백오프로 접힌다', () => {
    expect(nextDelayMs(input({ round: 0, serverPollAfterMs: -5000 }), noJitter)).toBe(800)
    expect(nextDelayMs(input({ round: 0, serverPollAfterMs: Number.NaN }), noJitter)).toBe(800)
  })
})

describe('nextDelayMs — 지터', () => {
  it('평상시 지터는 ±20% 양방향이다', () => {
    expect(nextDelayMs(input({ round: 0 }), minJitter)).toBe(800 * (1 - JITTER_RATIO))
    expect(nextDelayMs(input({ round: 0 }), maxJitter)).toBe(800 * (1 + JITTER_RATIO))
  })

  it('회차마다 다른 난수를 뽑아 요청 몰림을 흩는다', () => {
    const draws = [0.1, 0.9]
    let i = 0
    const random = () => draws[i++]
    const first = nextDelayMs(input({ round: 0 }), random)
    const second = nextDelayMs(input({ round: 0 }), random)
    expect(first).not.toBe(second)
  })
})

describe('nextDelayMs — 429 Retry-After', () => {
  it('Retry-After가 있으면 서버 pollAfterMs도 백오프도 덮는다', () => {
    const delay = nextDelayMs(
      input({ round: 0, serverPollAfterMs: 500, retryAfterMs: 10_000 }),
      noJitter,
    )
    expect(delay).toBeGreaterThanOrEqual(10_000)
  })

  it('지터가 Retry-After를 앞당기지 않는다 — 늘리는 쪽으로만 준다', () => {
    const retryAfterMs = 10_000
    for (const random of [minJitter, noJitter, maxJitter]) {
      const delay = nextDelayMs(input({ retryAfterMs }), random)
      expect(delay).toBeGreaterThanOrEqual(retryAfterMs)
      expect(delay).toBeLessThanOrEqual(retryAfterMs * (1 + JITTER_RATIO))
    }
  })
})

describe('planNextPoll — 누적 60초 예산', () => {
  it('예산이 넉넉하면 기다린다', () => {
    expect(planNextPoll(input({ round: 0, elapsedMs: 0 }), noJitter)).toEqual({
      kind: 'WAIT',
      delayMs: 800,
    })
  })

  it('예산 안에 정확히 착지하는 대기는 허용한다', () => {
    const plan = planNextPoll(
      input({ round: 99, elapsedMs: POLL_BUDGET_MS - BACKOFF_CEILING_MS }),
      noJitter,
    )
    expect(plan).toEqual({ kind: 'WAIT', delayMs: BACKOFF_CEILING_MS })
  })

  it('예산을 넘길 대기는 시작하지 않는다', () => {
    const plan = planNextPoll(
      input({ round: 99, elapsedMs: POLL_BUDGET_MS - BACKOFF_CEILING_MS + 1 }),
      noJitter,
    )
    expect(plan).toEqual({ kind: 'EXHAUSTED' })
  })

  it('예산을 이미 다 쓴 뒤에는 무조건 멈춘다', () => {
    expect(planNextPoll(input({ elapsedMs: POLL_BUDGET_MS }), noJitter)).toEqual({
      kind: 'EXHAUSTED',
    })
  })

  it('남은 예산보다 긴 Retry-After는 즉시 사용자에게 통제권을 넘긴다', () => {
    const plan = planNextPoll(
      input({ elapsedMs: POLL_BUDGET_MS - 10_000, retryAfterMs: 30_000 }),
      noJitter,
    )
    expect(plan).toEqual({ kind: 'EXHAUSTED' })
  })

  it('사다리를 끝까지 밟아도 20회 안팎에서 예산이 끝난다 (요구 5항 "약 20회")', () => {
    let elapsedMs = 0
    let round = 0
    while (true) {
      const plan = planNextPoll(input({ round, elapsedMs }), noJitter)
      if (plan.kind === 'EXHAUSTED') break
      elapsedMs += plan.delayMs
      round += 1
    }
    expect(round).toBeGreaterThanOrEqual(12)
    expect(round).toBeLessThanOrEqual(20)
    expect(elapsedMs).toBeLessThanOrEqual(POLL_BUDGET_MS)
  })
})
