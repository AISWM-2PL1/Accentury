/**
 * [다시 테스트하기] 재응시 왕복 (KAN-34 3단계).
 *
 * ## 성공은 회신이 오지 않는다
 *
 * 재응시가 성공하면 네이티브가 WebView를 인트로 URL로 통째로 다시 로드한다 — 회신을 받을
 * 페이지 자체가 사라지므로 "됐다"는 신호가 존재하지 않는다. [startRetest]가 돌려주는 true는
 * **브리지 호출이 성사됐다**는 뜻이지 새 세션을 받았다는 뜻이 아니다. 그래서 호출 뒤 create
 * 왕복이 끝날 때까지 화면에 아무 일도 일어나지 않는 구간이 정상적으로 존재하고, 이 훅이
 * 그동안 버튼을 잠근다.
 *
 * 잠금 해제 조건이 **실패 회신 도착뿐**인 것도 같은 이유다. 시간으로 풀면 느린 망에서 아직
 * 살아 있는 요청 위에 두 번째 요청이 겹치는데, 그때 첫 요청이 만든 세션이 곧바로 고아가 된다.
 *
 * ## 왜 잠금이 웹 몫인가
 *
 * KAN-107이 서버 측 멱등 장치를 두지 않기로 확정하면서 더블탭 방지가 클라이언트 책임이 됐다.
 * 네이티브에도 in-flight 가드(SessionGateController.retestInFlight)가 있지만 그것은 이중
 * 방어이고, "눌러도 되는 상태인가"를 사용자에게 보여 주는 일은 화면 몫이다.
 *
 * ## 왜 훅인가 (수신자 설치 위치)
 *
 * 수신 설치는 부모가 하고 자식에게는 값의 변화로 알린다 (webview-layer.md §8). 결과 화면이
 * 스스로 `onRetestFailed`를 걸면, 화면이 하나 더 끼어드는 순간 자식 effect가 먼저 도는 React
 * 마운트 순서상 부모 수신자가 나중에 설치돼 덮어쓴다 — 진행 화면의 `onItemResult`가 같은
 * 이유로 부모에 있다.
 */

import { useCallback, useEffect, useState } from 'react'
import { installRetestFailedReceiver, startRetest } from '../bridge/bridge'

/** 남은 대기 시간을 다시 세는 간격. 화면에 초 단위로만 적으므로 1초면 충분하다 */
const TICK_MS = 1000

/**
 * 결과 화면이 [다시 테스트하기] 버튼을 그리는 데 필요한 전부.
 *
 * 상태를 갈래별 boolean 여러 개가 아니라 이 한 덩이로 내리는 이유: 버튼이 두 자리(만료 화면,
 * 하단)에 있어서 조합 규칙이 화면 쪽에 흩어지면 한쪽만 잠기는 상태가 만들어진다.
 */
export interface RetestControl {
  /** 버튼 탭 */
  onRetest: () => void
  /** 지금 눌러도 소용없다 — 진행 중이거나, 다시 눌러도 같은 실패거나, 대기 시간이 남았다 */
  disabled: boolean
  /** 요청이 네이티브에 닿았고 아직 아무 회신도 없다 */
  pending: boolean
  /** 네이티브가 준 실패 문구 그대로. 없으면 null */
  message: string | null
  /** 429 대기 잔여 초(올림). 대기 중이 아니면 0 */
  retryAfterSec: number
}

/**
 * 재응시 요청의 세 상태.
 *
 * `failed`가 실패 payload를 통째로 들지 않고 필요한 세 값만 펼쳐 담는다 — `code`는 화면이
 * 쓰지 않는다(갈래별 카피는 네이티브가 정본이라 웹이 코드로 문구를 고르지 않는다).
 */
type Phase =
  | { status: 'idle' }
  | { status: 'pending' }
  | { status: 'failed'; message: string; retryable: boolean; retryAt: number | null }

/**
 * @param fallback 브리지로 갈 수 없을 때 대신 탈 길. 브라우저 단독 실행과, 메서드가 없는
 *   계약 버전 1 구버전 앱이 여기로 떨어진다 (§5 graceful degrade — 두 경우 모두 크래시가
 *   아니라 예전 동작인 인트로 복귀로 내려간다)
 */
export function useRetest(fallback: () => void): RetestControl {
  const [phase, setPhase] = useState<Phase>({ status: 'idle' })
  const [retryAfterSec, setRetryAfterSec] = useState(0)

  useEffect(
    () =>
      installRetestFailedReceiver((failure) => {
        setPhase({
          status: 'failed',
          message: failure.message,
          retryable: failure.retryable,
          /*
           * 대기 시간은 다시 눌러 볼 값어치가 있는 실패에만 센다. 영영 안 되는 실패에
           * 남은 초를 띄우면 "기다리면 된다"는 뜻이 되어 사실과 어긋난다.
           *
           * 받은 순간을 기준으로 마감 시각을 박아 두고 매 틱마다 남은 시간을 다시 계산한다 —
           * 1초씩 빼 나가면 백그라운드로 갔다 온 사이 타이머가 밀린 만큼 그대로 어긋난다.
           */
          retryAt:
            failure.retryable && failure.retryAfterMs !== null
              ? Date.now() + failure.retryAfterMs
              : null,
        })
      }),
    [],
  )

  const retryAt = phase.status === 'failed' ? phase.retryAt : null

  useEffect(() => {
    if (retryAt === null) {
      setRetryAfterSec(0)
      return
    }

    // 첫 값은 타이머를 기다리지 않고 바로 적는다 — 1초 동안 "0초 후"가 떠 있으면 안 된다.
    setRetryAfterSec(secondsUntil(retryAt))
    const timer = setInterval(() => {
      const left = secondsUntil(retryAt)
      setRetryAfterSec(left)
      // 다 센 뒤에도 계속 돌면 아무것도 바꾸지 않는 렌더가 1초마다 쌓인다.
      if (left === 0) clearInterval(timer)
    }, TICK_MS)
    return () => clearInterval(timer)
  }, [retryAt])

  const disabled =
    phase.status === 'pending' ||
    (phase.status === 'failed' && (!phase.retryable || retryAfterSec > 0))

  const onRetest = useCallback(() => {
    // 버튼의 `disabled`가 1차 방어지만 상태 쪽에도 같은 문을 둔다 — 더블탭 방지가 클라이언트
    // 책임(KAN-107)이라, 잠금이 한 겹뿐이면 그 한 겹을 우회하는 순간 세션이 고아가 된다.
    if (disabled) return

    if (!startRetest()) {
      fallback()
      return
    }
    setPhase({ status: 'pending' })
  }, [disabled, fallback])

  return {
    onRetest,
    disabled,
    pending: phase.status === 'pending',
    message: phase.status === 'failed' ? phase.message : null,
    retryAfterSec,
  }
}

/**
 * 남은 대기를 초로 **올림**한다 — 서버의 Retry-After 규칙과 같은 방향이다 (§2.5).
 * 내림하면 0.4초가 남았는데 화면은 "0초"라고 적고, 그 말을 믿고 누른 사용자가 다시 429를 맞는다.
 */
function secondsUntil(deadline: number): number {
  return Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
}
