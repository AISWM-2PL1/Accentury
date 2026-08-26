import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createFakeCapture, sineChunk, type FakeCapture } from '../audio/testing/fakeCapture'
import { VOICE_CHECK_MAX_DURATION_MS, VoiceCheckScreen } from './VoiceCheckScreen'

/**
 * 중심이 잠기려면 유성 프레임 8개가 필요하다 — 32ms 간격이니 약 250ms다. 600ms를 쓰는 것은
 * 첫 프레임이 창(2048샘플 = 128ms) 하나를 채운 뒤에야 나오기 때문이다.
 */
const SPEECH_MS = 600

/** 발화의 음높이. 그대로 잠긴 중심이 되어야 한다 (80~400Hz 탐색 대역 안) */
const SPEECH_HZ = 200

interface Harness {
  capture: FakeCapture
  onDone: ReturnType<typeof vi.fn<(centerHz: number) => void>>
  fetchStub: ReturnType<typeof vi.fn>
}

function renderScreen(): Harness {
  const capture = createFakeCapture()
  const onDone = vi.fn<(centerHz: number) => void>()
  // 점검은 전부 브라우저 안에서 끝난다 — 네트워크를 타면 그 자체가 회귀다
  const fetchStub = vi.fn()
  vi.stubGlobal('fetch', fetchStub)
  render(<VoiceCheckScreen onDone={onDone} capture={capture.factory} />)
  return { capture, onDone, fetchStub }
}

/** 캡처 시작(비동기)까지 기다린다 — 화면은 마운트 즉시 듣기 시작한다 */
async function settle() {
  await act(async () => {})
  await act(async () => {})
}

async function speak(capture: FakeCapture, durationMs = SPEECH_MS, amplitude = 0.5) {
  await act(async () => {
    capture.emit(
      sineChunk(durationMs, { sampleRate: capture.sampleRate, frequency: SPEECH_HZ, amplitude }),
    )
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('VoiceCheckScreen — 듣는 중', () => {
  it('진입하자마자 듣기 시작하고 말해 달라고만 한다 — 누를 버튼이 없다', async () => {
    renderScreen()
    await settle()

    expect(screen.getByText('목소리를 확인할게요')).toBeInTheDocument()
    expect(screen.getByText('안녕하세요')).toBeInTheDocument()
    expect(screen.getByText("'안녕하세요'라고 말해 주세요")).toBeInTheDocument()
    // 듣는 중에 누를 것을 주면 말하기를 멈추고 그걸 누른다
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('저장하지도 보내지도 않는다고 약속하고, 실제로 네트워크를 타지 않는다', async () => {
    const { capture, fetchStub } = renderScreen()
    await settle()
    await speak(capture)

    expect(screen.getByText('이 소리는 저장하거나 보내지 않아요')).toBeInTheDocument()
    expect(fetchStub).not.toHaveBeenCalled()
  })

  it('레벨 바는 값이 아니라 뜻만 읽힌다', async () => {
    renderScreen()
    await settle()

    expect(screen.getByRole('img', { name: '입력 레벨' })).toBeInTheDocument()
  })
})

describe('VoiceCheckScreen — 준비', () => {
  it('충분히 말하면 중심이 잠기고 [다음]이 열린다', async () => {
    const { capture, onDone } = renderScreen()
    await settle()

    await speak(capture)

    expect(screen.getByText('좋아요, 목소리가 잘 들려요')).toBeInTheDocument()
    // 판정이 끝나면 마이크를 놓는다 — 더 들어 봐야 결과가 달라지지 않는다
    expect(capture.stop).toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(onDone).toHaveBeenCalledTimes(1)
    // 잠긴 중심은 실제로 말한 음높이다 (YIN 추정 오차 범위)
    expect(onDone.mock.calls[0][0]).toBeCloseTo(SPEECH_HZ, -1)
  })

  it('준비 뒤 도착한 잔여 조각은 판정을 흔들지 못한다', async () => {
    const { capture, onDone } = renderScreen()
    await settle()
    await speak(capture)

    // 정지 요청과 실제 정지 사이에 워클릿 잔여분이 온다. 이번엔 한 옥타브 위다
    await act(async () => {
      capture.emit(sineChunk(300, { sampleRate: capture.sampleRate, frequency: SPEECH_HZ * 2 }))
    })

    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(onDone.mock.calls[0][0]).toBeCloseTo(SPEECH_HZ, -1)
  })

  it('세션 생성이 막히면 문구가 붙고 [다음]이 재시도 버튼으로 남는다', async () => {
    const capture = createFakeCapture()
    const onDone = vi.fn<(centerHz: number) => void>()
    const { rerender } = render(<VoiceCheckScreen onDone={onDone} capture={capture.factory} />)
    await settle()
    await speak(capture)

    rerender(
      <VoiceCheckScreen onDone={onDone} capture={capture.factory} startFailure="30초 후 다시 시도할 수 있어요" />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('30초 후 다시 시도할 수 있어요')
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(onDone).toHaveBeenCalledTimes(1)
  })
})

describe('VoiceCheckScreen — 시간 초과', () => {
  /** 상한(10초)을 넘긴 무음 — 훅이 스스로 캡처를 멈춘다 */
  const silence = (capture: FakeCapture) =>
    new Float32Array(Math.round(((VOICE_CHECK_MAX_DURATION_MS + 500) * capture.sampleRate) / 1000))

  it('상한까지 목소리가 안 잡히면 무엇이 모자랐는지 말하고 [다시 시도]를 준다', async () => {
    const { capture } = renderScreen()
    await settle()

    await act(async () => {
      capture.emit(silence(capture))
    })
    await settle()

    expect(screen.getByRole('alert')).toHaveTextContent('목소리가 잡히지 않았어요')
    expect(screen.getByRole('alert')).toHaveTextContent("'안녕하세요'라고 말해 주세요")
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('[다시 시도]는 마이크를 다시 열고, 이번엔 통과한다', async () => {
    const { capture, onDone } = renderScreen()
    await settle()
    await act(async () => {
      capture.emit(silence(capture))
    })
    await settle()

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await settle()

    // 앞 시도의 프레임·볼륨 기록은 버려졌으므로 다시 처음부터다
    expect(screen.getByText("'안녕하세요'라고 말해 주세요")).toBeInTheDocument()

    await speak(capture)
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(onDone).toHaveBeenCalledTimes(1)
  })
})
