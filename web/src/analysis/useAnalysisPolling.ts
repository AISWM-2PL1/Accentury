/**
 * 분석 상태 폴링 훅 (KAN-14 Stage 3) — 대기 화면의 유일한 폴링 주체다.
 *
 * `pollSchedule`(간격)과 두 클라이언트(요청)를 React 수명주기에 결선한다. 규칙을 여기서
 * 다시 정의하지 않는다 — 이 훅이 새로 책임지는 것은 **언제 멈추고 언제 다시 시작하는가**뿐이다.
 *
 * ## 이 훅이 존재하는 화면은 하나여야 한다 (요구 2항)
 *
 * 문항 진행 화면(`useTestProgress`)에는 fetch도 타이머도 없다. 폴링을 할 수 있는 코드가
 * 대기 화면이 쓰는 이 훅 하나뿐이면, "문항 진행 중에는 폴링하지 않는다"가 규율이 아니라
 * 구조가 된다.
 *
 * ## 예산은 포그라운드 시간만 센다
 *
 * 요구 5항의 누적 60초는 "자동 폴링을 얼마나 오래 돌릴 것인가"의 상한이다. 백그라운드에서는
 * 폴링이 멈춰 있으므로(요구 4항) 그 시간은 예산을 쓰지 않는다 — 벽시계로 재면 알림을 보고
 * 5분 뒤 돌아온 사용자가 화면을 보자마자 [다시 시도]를 만나게 되는데, 그동안 우리는 요청을
 * 한 번도 보내지 않았다. 상한이 막으려는 것은 요청 폭증이지 사용자의 부재가 아니다.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { newIdempotencyKey } from '../progress/submitVocabAnswer'
import { completeSession, RESULT_INCOMPLETE, RESULT_RETAKE_REQUIRED } from './completeSession'
import { AnalysisApiError } from './errorEnvelope'
import { fetchAnalysisStatuses, type AnalysisItem } from './fetchAnalysisStatuses'
import { planNextPoll, type Random } from './pollSchedule'

/**
 * 대기 화면이 그릴 수 있는 상태 전부.
 *
 * `POLLING` 말고는 전부 **폴링이 멈춘** 상태다 — 멈춘 이유가 다르므로 화면이 주는 출구도 다르다:
 * - `EXHAUSTED`: 시간이 오래 걸린다. 서버는 아직 돌고 있을 수 있으니 [다시 시도]를 준다
 * - `ACTION_REQUIRED`: 사용자가 녹음하기 전에는 아무것도 바뀌지 않는다. 재녹음을 준다
 * - `FAILED`: 재시도로 고쳐지지 않는다(세션 만료·배포 스큐). 문구만 준다
 */
export type WaitingStatus =
  | { kind: 'POLLING' }
  | { kind: 'READY' }
  | { kind: 'EXHAUSTED' }
  | { kind: 'ACTION_REQUIRED'; reason: 'RETAKE' | 'MISSING'; itemIds: string[] }
  | { kind: 'FAILED'; message: string }

export interface UseAnalysisPollingOptions {
  apiBase: string
  sessionId: string
  /** 세션 토큰 (Bearer 없이 값만). 브리지에서 읽은 값을 화면이 넘긴다 */
  sessionToken: string
  /** 주입용 fetch (테스트용) */
  fetchImpl?: FetchLike
  /** 주입용 시계 (테스트용). 훅 인스턴스당 한 번만 고정된다 */
  now?: () => number
  /** 주입용 난수 (테스트용). 훅 인스턴스당 한 번만 고정된다 */
  random?: Random
}

export interface UseAnalysisPollingResult {
  status: WaitingStatus
  /** 마지막으로 성공한 조회의 문항 상태. 조회가 실패해도 직전 값을 유지한다 */
  items: AnalysisItem[]
  /**
   * 재시도 가능한 일시 오류의 문구. 폴링은 계속되고 있다는 뜻이라 화면을 갈아엎지 않고
   * 부연으로만 보여 준다 — 네트워크가 한 번 끊겼다고 문항 상태를 지우면 사용자는 진행이
   * 날아간 줄 안다.
   */
  lastError: string | null
  /**
   * 폴링을 처음부터 다시 시작한다. 예산·회차·오류가 전부 초기화된다.
   *
   * [다시 시도] 버튼과 재녹음 결과 수신이 같은 함수를 쓴다. 둘 다 "사용자가 방금 무언가
   * 했으니 이제부터 다시 60초를 본다"는 같은 사건이라, 나눠 두면 한쪽만 예산을 리셋하는
   * 식으로 어긋난다.
   */
  restart: () => void
}

export function useAnalysisPolling(options: UseAnalysisPollingOptions): UseAnalysisPollingResult {
  const { apiBase, sessionId, sessionToken, fetchImpl } = options

  const [status, setStatus] = useState<WaitingStatus>({ kind: 'POLLING' })
  const [items, setItems] = useState<AnalysisItem[]>([])
  const [lastError, setLastError] = useState<string | null>(null)
  // 이 값이 바뀔 때마다 폴링 루프가 통째로 새로 선다. restart의 구현이다.
  const [generation, setGeneration] = useState(0)

  // 시계와 난수는 훅 인스턴스당 한 번만 고정한다. 매 렌더 새 함수를 만들면 그게 이펙트
  // 의존성이 되어 폴링 루프가 렌더마다 다시 서고, 예산이 영원히 0에 머문다.
  const clockRef = useRef<(() => number) | null>(null)
  if (clockRef.current === null) clockRef.current = options.now ?? (() => Date.now())
  const diceRef = useRef<Random | null>(null)
  if (diceRef.current === null) diceRef.current = options.random ?? Math.random

  /*
   * 멱등 키는 대기 화면 한 번의 수명 동안 하나다 (completeSession 주석 참고).
   * restart로 루프가 다시 서도 키는 유지한다 — 같은 세션의 같은 완료 시도라, 서버 로그에서
   * 한 줄기로 남는 편이 진단에 낫다.
   */
  const keyRef = useRef('')
  if (keyRef.current === '') keyRef.current = newIdempotencyKey()

  const restart = useCallback(() => setGeneration((n) => n + 1), [])

  useEffect(() => {
    const now = clockRef.current!
    const random = diceRef.current!

    let cancelled = false
    let paused = false
    let inFlight = false
    let timer: ReturnType<typeof setTimeout> | undefined

    /** 지금까지 마친 조회 횟수 */
    let round = 0
    /** 포그라운드 누적 시간 (백그라운드로 나갈 때마다 확정해 더한다) */
    let activeMs = 0
    /** 현재 포그라운드 구간이 시작한 시각 */
    let segmentStart = now()
    /** 서버가 마지막으로 준 pollAfterMs (요구 1항) */
    let serverPollAfterMs: number | null = null
    /** 429가 지시한 대기. 한 번 반영하면 소진한다 (요구 6항) */
    let retryAfterMs: number | null = null

    setStatus({ kind: 'POLLING' })
    setLastError(null)

    const elapsedMs = () => activeMs + (paused ? 0 : now() - segmentStart)

    function clearTimer() {
      if (timer !== undefined) {
        clearTimeout(timer)
        timer = undefined
      }
    }

    /** 백그라운드 진입 — 폴링을 멈추고 예산 시계도 멈춘다 (요구 4항) */
    function pause() {
      if (paused || cancelled) return
      activeMs += now() - segmentStart
      paused = true
      clearTimer()
    }

    /** 포그라운드 복귀 — 1회 즉시 조회하고 거기서부터 재개한다 (요구 4항) */
    function resume() {
      if (!paused || cancelled) return
      paused = false
      segmentStart = now()
      void tick()
    }

    function schedule() {
      if (cancelled || paused) return
      const plan = planNextPoll(
        { round, elapsedMs: elapsedMs(), serverPollAfterMs, retryAfterMs },
        random,
      )
      // 429 지시는 한 회차만 유효하다. 남겨 두면 제한이 풀린 뒤에도 계속 그 간격으로 돈다.
      retryAfterMs = null

      if (plan.kind === 'EXHAUSTED') {
        setStatus({ kind: 'EXHAUSTED' })
        return
      }
      round += 1
      timer = setTimeout(() => void tick(), plan.delayMs)
    }

    /**
     * 실패 하나를 처리한다. 폴링을 이어 가야 하면 true, 멈췄으면 false.
     *
     * **코드를 재시도 가능 여부보다 먼저 본다.** `RESULT_RETAKE_REQUIRED`는 서버가
     * `retryable=true`로 주지만(다시 녹음하면 성공한다는 뜻이다) 그 재시도의 주체는
     * 사용자다 — 우리가 계속 두드려 봐야 사용자가 녹음하기 전에는 같은 409가 돌아온다.
     */
    function handleError(error: unknown): boolean {
      const apiError = error instanceof AnalysisApiError ? error : null

      if (apiError?.code === RESULT_RETAKE_REQUIRED) {
        setStatus({ kind: 'ACTION_REQUIRED', reason: 'RETAKE', itemIds: apiError.retakeItems })
        return false
      }
      if (apiError?.code === RESULT_INCOMPLETE) {
        setStatus({ kind: 'ACTION_REQUIRED', reason: 'MISSING', itemIds: apiError.missingItems })
        return false
      }
      if (apiError !== null && !apiError.retryable) {
        setStatus({ kind: 'FAILED', message: apiError.message })
        return false
      }

      if (apiError?.retryAfterMs != null) retryAfterMs = apiError.retryAfterMs
      setLastError(apiError?.message ?? '분석 상태를 확인하는 중 문제가 생겼어요')
      return true
    }

    /**
     * 한 회차. `/analyses`로 화면에 그릴 상태를 받고, `/complete`로 끝났는지 판정한다.
     *
     * 순서가 중요하다: 상태를 먼저 받아야 `/complete`가 409를 주는 순간에도 **어느 문항이
     * 왜 실패했는지**가 이미 화면에 있다. 반대로 하면 재녹음 안내와 문항 목록이 한 회차 어긋난다.
     */
    async function tick() {
      if (cancelled || inFlight) return
      clearTimer()
      inFlight = true
      try {
        let statusesFailed = false
        try {
          const statuses = await fetchAnalysisStatuses({ apiBase, sessionId, sessionToken }, fetchImpl)
          if (cancelled) return
          setItems(statuses.items)
          serverPollAfterMs = statuses.pollAfterMs
          setLastError(null)
        } catch (error) {
          if (cancelled) return
          if (!handleError(error)) return
          statusesFailed = true
          /*
           * 요청 제한을 받았으면 이 회차의 `/complete`도 보내지 않는다 (요구 6항).
           *
           * 실제로 두 엔드포인트의 한도는 따로 세어지지만(서버의 제한 축에 상태 조회가 없다),
           * "Retry-After 전까지 요청하지 않는다"는 계약을 엔드포인트별로 해석하면 제한을 건
           * 쪽이 프록시나 게이트웨이일 때 어긋난다 — 그쪽은 우리 축을 모르고 오리진 단위로 센다.
           * 한 회차를 통째로 미루는 편이 계약을 문자 그대로 지킨다.
           */
          if (error instanceof AnalysisApiError && error.rateLimited) {
            schedule()
            return
          }
        }

        try {
          const result = await completeSession(
            { apiBase, sessionId, sessionToken, idempotencyKey: keyRef.current },
            fetchImpl,
          )
          if (cancelled) return
          if (result.status === 'READY') {
            setStatus({ kind: 'READY' })
            return
          }
          // 두 응답이 서로 다른 간격을 주면 느린 쪽을 따른다 — 둘 다 "이만큼 기다려라"는
          // 하한이라, 짧은 쪽을 고르면 다른 한쪽의 지시를 어긴다.
          if (result.pollAfterMs !== null) {
            serverPollAfterMs = Math.max(serverPollAfterMs ?? 0, result.pollAfterMs)
          }
          if (!statusesFailed) setLastError(null)
        } catch (error) {
          if (cancelled) return
          if (!handleError(error)) return
        }

        schedule()
      } finally {
        inFlight = false
      }
    }

    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') pause()
      else resume()
    }
    document.addEventListener('visibilitychange', onVisibilityChange)

    // 대기 화면에 들어오자마자 1회 조회한다. 첫 간격을 기다리면 이미 끝난 분석에도
    // 최소 800ms의 빈 화면이 생긴다.
    void tick()

    return () => {
      cancelled = true
      clearTimer()
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [apiBase, sessionId, sessionToken, fetchImpl, generation])

  return { status, items, lastError, restart }
}
