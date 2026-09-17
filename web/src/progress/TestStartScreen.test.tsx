/**
 * 시작 대기 화면의 단위 회귀 테스트 (KAN-216).
 *
 * 시계(3→2→1)와 첫 문항 전환은 `TestFlowScreen.test.tsx`의 "시작 대기 카운트다운" describe가
 * 본다. 여기서는 이 화면이 **받은 초를 어떻게 그리고 읽히는가**만 확인한다 — 팀 결정으로
 * 잠근 값(문구·3초 고정)과 접근성 구조(읽히는 것 하나, 숨기는 것 하나), 링 계산식이다.
 */

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { START_COUNTDOWN_SECONDS, START_SCREEN_SUBTITLE, TestStartScreen } from './TestStartScreen'

/** 링 둘레. 컴포넌트와 같은 반지름(54, `score-donut`과 같은 viewBox)으로 여기서 다시 계산한다 */
const CIRCUMFERENCE = 2 * Math.PI * 54

/** 링의 채움 원(`.test-start__value`)이 지금 얼마나 비어 있는지 — strokeDashoffset 값 */
function ringOffset(container: HTMLElement): number {
  const value = container.querySelector('.test-start__value')
  expect(value).not.toBeNull()
  return Number(value!.getAttribute('stroke-dashoffset'))
}

describe('TestStartScreen — 시작 대기 화면 (KAN-216)', () => {
  it('카운트다운 길이는 3초로 고정이다 — "3초 고정·스킵 없음"은 팀 결정이다', () => {
    /*
     * 값을 바꾸는 것 자체를 막으려는 게 아니라, 바꿀 때 이 단언이 깨져 "팀장 확정(2026-09-17)"
     * 을 한 번 되짚게 하려는 것이다. 시계(`TestFlowScreen`)와 링의 분모가 같은 상수를 보므로
     * 여기 하나만 잠그면 둘 다 잠긴다.
     */
    expect(START_COUNTDOWN_SECONDS).toBe(3)
  })

  it('제목이 h1 "곧 시작합니다"이고 status 문구가 확정 카피와 정확히 같다', () => {
    render(<TestStartScreen secondsLeft={3} />)

    // 이 화면에는 다른 제목이 없다 — 히어로 문구가 곧 화면 이름이라 h1으로 서야 한다
    expect(screen.getByRole('heading', { level: 1, name: '곧 시작합니다' })).toBeInTheDocument()
    /*
     * 문구는 상수를 참조해 대조한다 — 복붙하면 카피가 바뀔 때 테스트가 낡은 문구를 지키는
     * 꼴이 된다. 이 테스트가 잠그는 것은 "status로 한 번 읽히는 문구가 확정 카피 그 자체"
     * 라는 구조다.
     */
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent(START_SCREEN_SUBTITLE)
    expect(status).toHaveAttribute('aria-live', 'polite')
  })

  it('링과 숫자 블록은 통째로 aria-hidden이고, 숫자는 받은 초 그대로다', () => {
    const { container } = render(<TestStartScreen secondsLeft={2} />)

    /*
     * 매초 바뀌는 숫자를 스크린 리더가 읽으면 "3, 2, 1"이 소음이다 — 읽히는 것은 status 문구
     * 하나뿐이어야 한다. 숨김이 블록 단위라 링 SVG도 함께 빠진다.
     */
    const dial = container.querySelector('.test-start__dial')
    expect(dial).toHaveAttribute('aria-hidden', 'true')
    expect(dial!.querySelector('.test-start__count')).toHaveTextContent('2')
  })

  it('링은 3에서 꽉 차고 0에서 비어 있다 — 남은 몫 = secondsLeft / 3', () => {
    const { container, rerender } = render(<TestStartScreen secondsLeft={START_COUNTDOWN_SECONDS} />)
    // 시작: 남은 몫 1 → offset 0 (한 바퀴 전부 그려진다)
    expect(ringOffset(container)).toBeCloseTo(0)

    rerender(<TestStartScreen secondsLeft={1} />)
    // 1초 남음: 남은 몫 1/3 → 둘레의 2/3만큼 비어 있다
    expect(ringOffset(container)).toBeCloseTo(CIRCUMFERENCE * (2 / 3))

    rerender(<TestStartScreen secondsLeft={0} />)
    // 끝: 남은 몫 0 → offset = 둘레 (아무것도 안 그려진다)
    expect(ringOffset(container)).toBeCloseTo(CIRCUMFERENCE)
  })

  it('범위 밖 값은 0~1로 갇힌다 — 원호가 한 바퀴를 넘거나 음수로 그려지지 않는다', () => {
    /*
     * 부모가 이런 값을 주지는 않지만, 갇히지 않으면 음수 offset은 원호가 한 바퀴를 넘어
     * 겹쳐 그려지고 둘레 초과 offset은 방향이 뒤집힌다 — 어느 쪽도 "어디가 잘못됐는지"
     * 화면에서 읽을 수 없다.
     */
    const { container, rerender } = render(<TestStartScreen secondsLeft={5} />)
    expect(ringOffset(container)).toBeCloseTo(0)

    rerender(<TestStartScreen secondsLeft={-1} />)
    expect(ringOffset(container)).toBeCloseTo(CIRCUMFERENCE)
  })
})
