import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { FeedbackSheet, type FeedbackSheetProps } from './FeedbackSheet'
import {
  FEEDBACK_ALREADY,
  FEEDBACK_CLOSE,
  FEEDBACK_DONE_BODY,
  FEEDBACK_DONE_TITLE,
  FEEDBACK_EXPIRED,
  FEEDBACK_GUIDE,
  FEEDBACK_RETRY,
  FEEDBACK_SENDING,
  FEEDBACK_SUBMIT,
  FEEDBACK_TITLE,
} from './feedbackText'
import { FeedbackApiError, type SendFeedbackResult } from './sendFeedback'

function renderSheet(overrides: Partial<FeedbackSheetProps> = {}) {
  const props: FeedbackSheetProps = {
    open: true,
    onClose: vi.fn(),
    submit: vi.fn(async () => ({ status: 'saved' }) as SendFeedbackResult),
    ...overrides,
  }
  const view = render(<FeedbackSheet {...props} />)
  return { ...view, props }
}

/** 본문 입력칸. 이름표는 위 안내 문장이다 (`aria-labelledby`) */
function bodyField() {
  return screen.getByLabelText(FEEDBACK_GUIDE)
}

function write(text: string) {
  fireEvent.change(bodyField(), { target: { value: text } })
}

function submitButton() {
  return screen.getByRole('button', { name: FEEDBACK_SUBMIT })
}

describe('FeedbackSheet — 뼈대와 접근성 (KAN-211)', () => {
  it('제목이 달린 모달 대화상자다', () => {
    renderSheet()

    const dialog = screen.getByRole('dialog', { name: FEEDBACK_TITLE })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // 결과 화면의 h1(등급명)을 빼앗지 않는다 — 시트는 화면 위에 덮인 것이지 화면이 아니다
    expect(screen.getByRole('heading', { level: 2, name: FEEDBACK_TITLE })).toBeInTheDocument()
  })

  it('닫혀 있으면 아무것도 그리지 않는다', () => {
    renderSheet({ open: false })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('열리는 순간 초점이 본문 입력칸에 선다', () => {
    renderSheet()

    expect(bodyField()).toHaveFocus()
  })

  it('방침 링크를 함께 둔다 — 수집 항목을 여기서 읽을 수 있어야 한다', () => {
    renderSheet()

    expect(screen.getByRole('link', { name: '개인정보처리방침' })).toBeInTheDocument()
  })
})

describe('FeedbackSheet — 입력 검증', () => {
  it('빈 본문이면 보내기가 비활성이고, 나무라는 문구도 없다', () => {
    renderSheet()

    expect(submitButton()).toBeDisabled()
    // 아직 아무 일도 일어나지 않았다 — 열자마자 빨간 줄이 뜨면 시트가 사용자를 나무란다
    expect(screen.queryByText('후기 내용을 적어 주세요.')).not.toBeInTheDocument()
  })

  it('한 글자라도 적으면 보낼 수 있다', () => {
    renderSheet()

    write('좋아요')

    expect(submitButton()).toBeEnabled()
  })

  it('500자를 넘기면 문구를 띄우고 보내기를 막는다', () => {
    renderSheet()

    write('ㄱ'.repeat(501))

    expect(screen.getByText('후기는 500자까지 적을 수 있어요.')).toBeInTheDocument()
    expect(submitButton()).toBeDisabled()
  })

  it('이메일 형식이 틀리면 보내기를 막는다 — 서버에 물어보기 전에 안다', () => {
    renderSheet()

    write('좋아요')
    fireEvent.change(screen.getByLabelText('답변 받을 이메일(선택)'), { target: { value: 'nope' } })

    expect(screen.getByText('이메일 주소를 다시 확인해 주세요.')).toBeInTheDocument()
    expect(submitButton()).toBeDisabled()
  })

  it('쓴 글자 수를 센다', () => {
    renderSheet()

    write('열한 글자를 적어본다')

    expect(screen.getByText('11/500')).toBeInTheDocument()
  })

  it('별을 누르면 그 점수의 라디오가 선택된다', () => {
    renderSheet()

    fireEvent.click(screen.getByRole('radio', { name: '3점' }))

    expect(screen.getByRole('radio', { name: '3점' })).toBeChecked()
    expect(screen.getByRole('radio', { name: '4점' })).not.toBeChecked()
  })
})

describe('FeedbackSheet — 보내기', () => {
  it('적은 것만 넘긴다 — 본문은 trim하고 안 고른 값은 null이다', async () => {
    const submit = vi.fn(async () => ({ status: 'saved' }) as SendFeedbackResult)
    renderSheet({ submit })

    write('  화면이 예뻐요  ')
    fireEvent.click(submitButton())

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1))
    expect(submit).toHaveBeenCalledWith({ rating: null, body: '화면이 예뻐요', contactEmail: null })
  })

  it('별점과 이메일을 적었으면 함께 넘긴다', async () => {
    const submit = vi.fn(async () => ({ status: 'saved' }) as SendFeedbackResult)
    renderSheet({ submit })

    fireEvent.click(screen.getByRole('radio', { name: '5점' }))
    write('최고예요')
    fireEvent.change(screen.getByLabelText('답변 받을 이메일(선택)'), { target: { value: 'me@example.com' } })
    fireEvent.click(submitButton())

    await waitFor(() =>
      expect(submit).toHaveBeenCalledWith({ rating: 5, body: '최고예요', contactEmail: 'me@example.com' }),
    )
  })

  it('보내는 동안 버튼이 죽고 문구가 바뀐다', async () => {
    renderSheet({ submit: vi.fn(() => new Promise<SendFeedbackResult>(() => {})) })

    write('좋아요')
    fireEvent.click(submitButton())

    expect(await screen.findByRole('button', { name: FEEDBACK_SENDING })).toBeDisabled()
    expect(screen.getByRole('button', { name: FEEDBACK_CLOSE })).toBeDisabled()
  })

  it('저장되면 완료 화면으로 바뀌고 onSubmitted가 불린다', async () => {
    const onSubmitted = vi.fn()
    renderSheet({ onSubmitted })

    write('좋아요')
    fireEvent.click(submitButton())

    expect(await screen.findByRole('heading', { name: FEEDBACK_DONE_TITLE })).toBeInTheDocument()
    expect(screen.getByText(FEEDBACK_DONE_BODY)).toBeInTheDocument()
    // 끝난 자리라 다시 쓸 칸을 남기지 않는다
    expect(screen.queryByLabelText(FEEDBACK_GUIDE)).not.toBeInTheDocument()
    expect(onSubmitted).toHaveBeenCalledWith({ status: 'saved' }, {
      rating: null,
      body: '좋아요',
      contactEmail: null,
    })
  })

  it('이미 보낸 결과면 실패가 아니라 그렇게 말한다', async () => {
    const onSubmitted = vi.fn()
    renderSheet({ submit: vi.fn(async () => ({ status: 'already' }) as SendFeedbackResult), onSubmitted })

    write('좋아요')
    fireEvent.click(submitButton())

    expect(await screen.findByText(FEEDBACK_ALREADY)).toBeInTheDocument()
    // 부모가 진입 버튼을 치울 수 있도록 끝났다는 사실은 그대로 올린다
    expect(onSubmitted).toHaveBeenCalledWith({ status: 'already' }, expect.anything())
  })
})

describe('FeedbackSheet — 실패 갈래', () => {
  function failWith(error: FeedbackApiError) {
    return vi.fn(async () => {
      throw error
    })
  }

  it('세션이 만료되면 다시 보낼 길을 주지 않는다 (401)', async () => {
    renderSheet({
      submit: failWith(new FeedbackApiError('세션이 만료되었습니다.', { code: 'SESSION_EXPIRED', retryable: false })),
    })

    write('좋아요')
    fireEvent.click(submitButton())

    expect(await screen.findByText(FEEDBACK_EXPIRED)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: FEEDBACK_RETRY })).not.toBeInTheDocument()
  })

  it('403도 같은 문구다 — 사용자가 할 수 있는 일이 같다', async () => {
    renderSheet({
      submit: failWith(new FeedbackApiError('이 세션의 결과가 아닙니다.', { code: 'SESSION_FORBIDDEN', retryable: false })),
    })

    write('좋아요')
    fireEvent.click(submitButton())

    expect(await screen.findByText(FEEDBACK_EXPIRED)).toBeInTheDocument()
  })

  it('재시도 가능한 실패는 입력을 그대로 두고 [다시 보내기]를 준다', async () => {
    const submit = vi
      .fn<() => Promise<SendFeedbackResult>>()
      .mockRejectedValueOnce(new FeedbackApiError('잠시 후 다시 시도해 주세요.', { retryable: true }))
      .mockResolvedValueOnce({ status: 'saved' })
    renderSheet({ submit })

    write('좋아요')
    fireEvent.click(submitButton())

    // 실패 문구는 스스로 읽어 준다 — 시트가 이미 떠 있는 채로 나중에 나타나는 실패다
    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.')
    // 방금 쓴 글이 실패 한 번에 사라지면 두 번 쓰지 않는다
    expect(bodyField()).toHaveValue('좋아요')

    fireEvent.click(screen.getByRole('button', { name: FEEDBACK_RETRY }))

    expect(await screen.findByRole('heading', { name: FEEDBACK_DONE_TITLE })).toBeInTheDocument()
    expect(submit).toHaveBeenCalledTimes(2)
    expect(submit).toHaveBeenNthCalledWith(2, { rating: null, body: '좋아요', contactEmail: null })
  })

  it('재시도해도 달라지지 않는 실패에는 재시도 버튼이 없다', async () => {
    renderSheet({
      submit: failWith(new FeedbackApiError('후기 내용을 확인해 주세요.', { code: 'VALIDATION_FAILED', retryable: false })),
    })

    write('좋아요')
    fireEvent.click(submitButton())

    expect(await screen.findByText('후기 내용을 확인해 주세요.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: FEEDBACK_RETRY })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: FEEDBACK_CLOSE })).toBeEnabled()
  })

  it('429는 남은 초를 적는다', async () => {
    renderSheet({
      submit: failWith(
        new FeedbackApiError('요청이 많아요.', { code: 'RATE_LIMITED', retryable: true, retryAfterMs: 6500 }),
      ),
    })

    write('좋아요')
    fireEvent.click(submitButton())

    expect(await screen.findByText('7초 후 다시 보낼 수 있어요')).toBeInTheDocument()
  })
})

describe('FeedbackSheet — 닫기', () => {
  it('[닫기]·Escape·배경 탭 셋 다 닫는다 — 안 쓰고 나가는 것이 정상 행동이다', () => {
    const { container, props } = renderSheet()

    fireEvent.click(screen.getByRole('button', { name: FEEDBACK_CLOSE }))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    fireEvent.click(container.querySelector('.feedback-sheet')!)

    expect(props.onClose).toHaveBeenCalledTimes(3)
  })

  it('패널 안을 눌러도 닫히지 않는다 — 글을 쓰다 누른 자리다', () => {
    const { props } = renderSheet()

    fireEvent.click(screen.getByRole('dialog'))

    expect(props.onClose).not.toHaveBeenCalled()
  })

  it('보내는 중에는 어느 길로도 닫히지 않는다', async () => {
    const { container, props } = renderSheet({ submit: vi.fn(() => new Promise<SendFeedbackResult>(() => {})) })

    write('좋아요')
    fireEvent.click(submitButton())
    await screen.findByRole('button', { name: FEEDBACK_SENDING })

    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    fireEvent.click(container.querySelector('.feedback-sheet')!)
    fireEvent.click(screen.getByRole('button', { name: FEEDBACK_CLOSE }))

    // 요청이 서버에 닿았는지 모르는 채로 화면이 사라지면 후기가 갔는지 알 수 없다
    expect(props.onClose).not.toHaveBeenCalled()
  })
})
