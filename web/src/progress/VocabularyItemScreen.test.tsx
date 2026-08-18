import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { VocabularyItemScreen } from './VocabularyItemScreen'
import type { VocabularyItem } from './testDefinition'

/** 더미 확정본(KAN-13 댓글, 2026-08-05)의 1번 문항 모양 그대로 */
function vocabularyItem(): VocabularyItem {
  return {
    itemId: 'w1',
    seq: 6,
    type: 'VOCABULARY',
    prompt: "'정구지'는 표준어로?",
    choices: [
      { choiceId: 'w1a', text: '부추' },
      { choiceId: 'w1b', text: '미나리' },
      { choiceId: 'w1c', text: '쑥갓' },
      { choiceId: 'w1d', text: '시금치' },
    ],
  }
}

function renderScreen() {
  const onSubmit = vi.fn<(choiceId: string) => void>()
  render(<VocabularyItemScreen item={vocabularyItem()} onSubmit={onSubmit} />)
  return onSubmit
}

describe('표시', () => {
  it('문제 문구를 이름으로 갖는 라디오 그룹에 선택지를 서버 순서대로 그린다', () => {
    renderScreen()

    const group = screen.getByRole('radiogroup', { name: "'정구지'는 표준어로?" })
    expect(group).toBeInTheDocument()

    // getAllByRole은 DOM 순서로 돌려주므로 이 대조가 곧 "서버 정의 순서 유지" 검증이다
    const labels = screen.getAllByRole('radio').map((radio) => radio.closest('label')?.textContent)
    expect(labels).toEqual(['부추', '미나리', '쑥갓', '시금치'])
  })

  it('정오를 유추할 만한 표시가 없다 — 선택지 문구 외 텍스트는 문제와 [다음]뿐이다', () => {
    renderScreen()
    fireEvent.click(screen.getByRole('radio', { name: '부추' }))

    // 정답 표시·해설·점수 등 어떤 추가 문구도 나타나면 안 된다 (KAN-13 정오 미노출)
    expect(document.body.textContent).toBe("'정구지'는 표준어로?부추미나리쑥갓시금치다음")
  })
})

describe('선택과 [다음]', () => {
  it('선택 전에는 [다음]이 비활성이고 아무 통지도 나가지 않는다', () => {
    const onSubmit = renderScreen()

    const next = screen.getByRole('button', { name: '다음' })
    expect(next).toBeDisabled()

    fireEvent.click(next)
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('하나를 고르면 [다음]이 활성화된다', () => {
    renderScreen()

    fireEvent.click(screen.getByRole('radio', { name: '미나리' }))

    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled()
  })

  it('한 개만 선택된다 — 다른 것을 고르면 이전 선택이 풀린다', () => {
    renderScreen()

    fireEvent.click(screen.getByRole('radio', { name: '부추' }))
    fireEvent.click(screen.getByRole('radio', { name: '시금치' }))

    expect(screen.getByRole('radio', { name: '시금치' })).toBeChecked()
    expect(screen.getByRole('radio', { name: '부추' })).not.toBeChecked()
    expect(screen.getAllByRole('radio').filter((radio) => (radio as HTMLInputElement).checked)).toHaveLength(1)
  })

  it('[다음]은 바꾼 뒤의 최종 선택을 통지한다 (제출 전 변경 허용)', () => {
    const onSubmit = renderScreen()

    fireEvent.click(screen.getByRole('radio', { name: '부추' }))
    fireEvent.click(screen.getByRole('radio', { name: '미나리' }))
    fireEvent.click(screen.getByRole('button', { name: '다음' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit).toHaveBeenCalledWith('w1b')
  })
})
