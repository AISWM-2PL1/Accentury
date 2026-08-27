/**
 * 공통 컴포넌트의 접근성 최소선 회귀 테스트 (KAN-148).
 *
 * 색·크기 값 자체는 tools/check_tokens.py와 check_contrast.py가 본다. 여기서는 마크업이
 * 주는 것 — 의미론, 기본 타입, 통지 역할 — 만 확인한다. 두 검사가 서로 다른 층을 맡는다.
 */

import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { Button, ProgressIndicator, StatusBlock } from './index'

describe('Button', () => {
  it('기본 type이 button이다 — 폼 안에서 submit으로 새로고침되지 않는다', () => {
    render(<Button onClick={() => {}}>다음</Button>)
    expect(screen.getByRole('button', { name: '다음' })).toHaveAttribute('type', 'button')
  })

  it('변형마다 클래스가 붙는다 — 스타일은 CSS가 갖고 컴포넌트는 이름만 옮긴다', () => {
    const { rerender } = render(<Button variant="primary">가</Button>)
    expect(screen.getByRole('button')).toHaveClass('btn', 'btn--primary')

    rerender(<Button variant="secondary">가</Button>)
    expect(screen.getByRole('button')).toHaveClass('btn--secondary')

    rerender(<Button variant="text">가</Button>)
    expect(screen.getByRole('button')).toHaveClass('btn--text')
  })

  it('disabled면 클릭이 전달되지 않는다', () => {
    const onClick = vi.fn()
    render(
      <Button disabled onClick={onClick}>
        다음
      </Button>,
    )
    fireEvent.click(screen.getByRole('button'))
    expect(onClick).not.toHaveBeenCalled()
  })
})

describe('ProgressIndicator', () => {
  it('progressbar 의미론과 값을 그대로 노출한다', () => {
    render(<ProgressIndicator current={3} total={10} />)
    const bar = screen.getByRole('progressbar', { name: '문항 진행률' })
    expect(bar).toHaveAttribute('aria-valuenow', '3')
    expect(bar).toHaveAttribute('aria-valuemax', '10')
  })

  it('문항 수만큼 도트를 그리고 완료·현재·미완료를 속성으로 가른다', () => {
    const { container } = render(<ProgressIndicator current={3} total={10} />)
    const states = Array.from(container.querySelectorAll('.progress-indicator__dot')).map((dot) =>
      dot.getAttribute('data-state'),
    )

    expect(states).toHaveLength(10)
    // 색이 아니라 형태로 가르는 세 상태다 (정본 §8) - CSS가 이 속성을 보고 채움을 정한다
    expect(states.slice(0, 2)).toEqual(['done', 'done'])
    expect(states[2]).toBe('current')
    expect(new Set(states.slice(3))).toEqual(new Set(['todo']))
  })

  it('도트 하나하나는 읽히지 않는다 — 값을 말하는 것은 progressbar 한 줄이다', () => {
    const { container } = render(<ProgressIndicator current={3} total={10} />)
    const dots = container.querySelectorAll('.progress-indicator__dot')

    expect(dots.length).toBeGreaterThan(0)
    dots.forEach((dot) => expect(dot).toHaveAttribute('aria-hidden', 'true'))
  })

  it('숫자 표기가 도트와 같은 값을 쓴다 — 둘이 갈라지지 않는다', () => {
    render(<ProgressIndicator current={7} total={10} />)
    expect(screen.getByText('7 / 10')).toBeInTheDocument()
  })

  it('note를 주면 숫자 뒤에 한 마디가 붙는다', () => {
    const { container } = render(<ProgressIndicator current={3} total={10} note="음성" />)
    expect(container.querySelector('.progress-indicator__count')).toHaveTextContent('3 / 10 · 음성')
  })
})

describe('StatusBlock', () => {
  it('오류는 role=alert다 — 이미 떠 있는 화면에서 나타나므로 스스로 읽혀야 한다', () => {
    render(<StatusBlock tone="error" message="문항을 불러오지 못했어요" detail="timeout" />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('문항을 불러오지 못했어요')
    expect(alert).toHaveTextContent('timeout')
  })

  it('대기는 alert가 아니다 — 곧 바뀔 상태를 매번 읽으면 소음이 된다', () => {
    render(<StatusBlock tone="waiting" message="문항을 불러오는 중…" />)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByText('문항을 불러오는 중…')).toBeInTheDocument()
  })

  it('detail이 없으면 부연 줄을 그리지 않는다', () => {
    const { container } = render(<StatusBlock tone="waiting" message="여는 중…" />)
    expect(container.querySelectorAll('p')).toHaveLength(1)
  })

  it('action을 받으면 복구 동작을 함께 놓는다', () => {
    render(
      <StatusBlock tone="error" message="실패했어요" action={<Button>다시 시도</Button>} />,
    )
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })
})
