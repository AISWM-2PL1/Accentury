import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { VocabularyItemScreen } from './VocabularyItemScreen'
import { VocabSubmitError, type VocabSubmitResult } from './submitVocabAnswer'
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

type SubmitFn = (choiceId: string, idempotencyKey: string) => Promise<VocabSubmitResult>

function renderScreen(submitAnswer: SubmitFn = async () => ({ status: 'SAVED' })) {
  const submitSpy = vi.fn<SubmitFn>(submitAnswer)
  const onSubmitted = vi.fn<() => void>()
  render(<VocabularyItemScreen item={vocabularyItem()} submitAnswer={submitSpy} onSubmitted={onSubmitted} />)
  return { submitSpy, onSubmitted }
}

function choose(text: string) {
  fireEvent.click(screen.getByRole('radio', { name: text }))
}

function pressNext(name = '다음') {
  fireEvent.click(screen.getByRole('button', { name }))
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

  it('정오를 유추할 만한 표시가 없다 — 유형 배지·문제·선택지·[다음]이 전부다', () => {
    renderScreen()
    choose('부추')

    /*
     * 정답 표시·해설·점수 등 어떤 추가 문구도 나타나면 안 된다 (KAN-13 정오 미노출).
     * 전체 텍스트를 통째로 대조하는 이유: "무엇이 없는가"는 개별 단어를 찾아서는 증명할 수
     * 없다. 화면에 뭔가 새로 붙으면 그게 정오 정보든 아니든 여기서 걸리고, 걸린 사람이
     * 그게 정오 유추 경로인지 판단하게 된다.
     * 배지("📝 단어 문항")는 KAN-148에서 카드 안으로 들어왔다 - 유형 표시라 정답과 무관하다.
     */
    expect(document.body.textContent).toBe("📝 단어 문항'정구지'는 표준어로?부추미나리쑥갓시금치다음")
  })
})

describe('선택과 [다음]', () => {
  it('선택 전에는 [다음]이 비활성이고 아무 제출도 나가지 않는다', () => {
    const { submitSpy } = renderScreen()

    const next = screen.getByRole('button', { name: '다음' })
    expect(next).toBeDisabled()

    fireEvent.click(next)
    expect(submitSpy).not.toHaveBeenCalled()
  })

  it('한 개만 선택된다 — 다른 것을 고르면 이전 선택이 풀린다', () => {
    renderScreen()

    choose('부추')
    choose('시금치')

    expect(screen.getByRole('radio', { name: '시금치' })).toBeChecked()
    expect(screen.getByRole('radio', { name: '부추' })).not.toBeChecked()
    expect(screen.getAllByRole('radio').filter((radio) => (radio as HTMLInputElement).checked)).toHaveLength(1)
  })

  it('[다음]은 바꾼 뒤의 최종 선택을 제출한다 (제출 전 변경 허용)', async () => {
    const { submitSpy } = renderScreen()

    choose('부추')
    choose('미나리')
    pressNext()

    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(1))
    expect(submitSpy.mock.calls[0][0]).toBe('w1b')
  })
})

describe('제출 수명주기', () => {
  it('제출 성공 후에만 진행 통지가 나간다 (AC 2항)', async () => {
    const { onSubmitted } = renderScreen()

    choose('부추')
    expect(onSubmitted).not.toHaveBeenCalled()
    pressNext()

    await waitFor(() => expect(onSubmitted).toHaveBeenCalledTimes(1))
  })

  it('ALREADY_ANSWERED도 진행한다 — 답은 이미 서버에 있다', async () => {
    const { onSubmitted } = renderScreen(async () => ({ status: 'ALREADY_ANSWERED' }))

    choose('부추')
    pressNext()

    await waitFor(() => expect(onSubmitted).toHaveBeenCalledTimes(1))
  })

  it('성공한 뒤에도 잠금이 풀리지 않는다 — 호출자가 화면을 걷을 때까지 재제출 창이 없다', async () => {
    // 성공 후 setSubmitting(false)를 부르지 않는 것이 이 화면의 설계다. 호출자가 다음 문항으로
    // 넘겨 이 컴포넌트를 내리기까지 한 프레임이라도 잠금이 풀리면, [다음] 연타가 두 번째 제출을
    // 만든다. 그 편도 잠금을 여기서 못 박는다 — 실패 시에만 풀리는 코드가 회귀하면 깨진다.
    const { submitSpy, onSubmitted } = renderScreen()

    choose('부추')
    pressNext()
    await waitFor(() => expect(onSubmitted).toHaveBeenCalledTimes(1))

    // 호출자(상태 머신)가 화면을 갈아끼우지 않은 상태를 재현한다 — 실제로는 여기서 언마운트된다
    expect(screen.getByRole('button', { name: '제출 중…' })).toBeDisabled()
    screen.getAllByRole('radio').forEach((radio) => expect(radio).toBeDisabled())

    fireEvent.click(screen.getByRole('button', { name: '제출 중…' }))
    expect(submitSpy).toHaveBeenCalledTimes(1)
  })

  it('제출 중에는 보기와 버튼이 잠기고 "제출 중…"이 보인다', async () => {
    // 풀리지 않는 제출 — 진행 중 상태를 고정해 놓고 화면을 검사한다
    renderScreen(() => new Promise<VocabSubmitResult>(() => {}))

    choose('부추')
    pressNext()

    expect(await screen.findByRole('button', { name: '제출 중…' })).toBeDisabled()
    screen.getAllByRole('radio').forEach((radio) => expect(radio).toBeDisabled())
  })

  it('실패하면 서버 문구와 [다시 시도]가 보이고 진행하지 않는다', async () => {
    const { onSubmitted } = renderScreen(async () => {
      throw new VocabSubmitError('네트워크 오류로 답안을 제출하지 못했습니다', null, true)
    })

    choose('부추')
    pressNext()

    expect(await screen.findByRole('alert')).toHaveTextContent('네트워크 오류로 답안을 제출하지 못했습니다')
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()
    expect(onSubmitted).not.toHaveBeenCalled()
    // 실패 뒤에는 답을 바꿀 수 있어야 한다 — 보기 잠금이 풀린다
    screen.getAllByRole('radio').forEach((radio) => expect(radio).toBeEnabled())
  })

  it('같은 답의 재시도는 같은 멱등 키로 나간다 (AC 3항 — 중복 생성 없는 재시도)', async () => {
    const { submitSpy } = renderScreen(vi.fn<SubmitFn>()
      .mockRejectedValueOnce(new VocabSubmitError('일시적인 오류가 발생했습니다', null, true))
      .mockResolvedValue({ status: 'SAVED' }))

    choose('부추')
    pressNext()
    await screen.findByRole('button', { name: '다시 시도' })
    pressNext('다시 시도')

    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(2))
    const [firstChoice, firstKey] = submitSpy.mock.calls[0]
    const [secondChoice, secondKey] = submitSpy.mock.calls[1]
    expect(firstChoice).toBe('w1a')
    expect(secondChoice).toBe('w1a')
    expect(secondKey).toBe(firstKey)
  })

  it('실패 후 답을 바꾸면 새 멱등 키다 — 같은 키로 다른 답을 보내면 서버가 거절한다', async () => {
    const { submitSpy } = renderScreen(vi.fn<SubmitFn>()
      .mockRejectedValueOnce(new VocabSubmitError('일시적인 오류가 발생했습니다', null, true))
      .mockResolvedValue({ status: 'SAVED' }))

    choose('부추')
    pressNext()
    await screen.findByRole('button', { name: '다시 시도' })
    choose('미나리')
    pressNext('다시 시도')

    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(2))
    const [, firstKey] = submitSpy.mock.calls[0]
    const [secondChoice, secondKey] = submitSpy.mock.calls[1]
    expect(secondChoice).toBe('w1b')
    expect(secondKey).not.toBe(firstKey)
  })
})
