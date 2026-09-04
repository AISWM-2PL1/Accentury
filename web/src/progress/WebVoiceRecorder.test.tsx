import { act, fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { createFakeCapture, sineChunk, type FakeCapture } from '../audio/testing/fakeCapture'
import type { Recording } from '../audio'
import { UploadError, type UploadAccepted } from '../audio/uploadRecording'
import type { ItemResult } from '../bridge/itemResult'
import { WebVoiceRecorder } from './WebVoiceRecorder'
import type { VoiceItem } from './testDefinition'

const MAX_MS = 10_000

/**
 * 1초짜리 가이드 곡선(10ms × 101점). 실제 시드 문항과 같은 규격이라 사용자 창이 그 두 배인
 * 2초가 된다 — 곡선 테스트가 실제와 같은 창에서 돌아야 "창이 미끄러진다"를 검사할 수 있다.
 */
const GUIDE_VALUES: (number | null)[] = Array.from({ length: 101 }, (_, i) =>
  Math.sin((2 * Math.PI * i) / 100) * 3,
)

function voiceItem(overrides: Partial<VoiceItem> = {}): VoiceItem {
  return {
    itemId: 'v1',
    seq: 1,
    type: 'VOICE',
    prompt: '"밥 뭇나?"를 평소 말투로 읽어 주세요',
    maxDurationMs: MAX_MS,
    guideF0: { unit: 'semitone', frameIntervalMs: 10, values: GUIDE_VALUES },
    ...overrides,
  }
}

type UploadFn = (recording: Recording, attemptId: string) => Promise<UploadAccepted>
type UploadMock = ReturnType<typeof vi.fn<UploadFn>>

interface Harness {
  capture: FakeCapture
  upload: UploadMock
  onUploaded: ReturnType<typeof vi.fn<(result: ItemResult) => void>>
}

/** 항상 접수하는 업로드 대역. 인자 타입을 명시해 호출 기록을 그대로 읽을 수 있게 한다 */
function okUpload(analysisJobId = 'job-1'): UploadMock {
  return vi.fn<UploadFn>(async () => ({ analysisJobId }))
}

function renderRecorder(upload: UploadMock = okUpload(), item: VoiceItem = voiceItem()): Harness {
  const capture = createFakeCapture()
  const onUploaded = vi.fn<(result: ItemResult) => void>()
  render(
    <WebVoiceRecorder
      item={item}
      upload={upload}
      onUploaded={onUploaded}
      capture={capture.factory}
    />,
  )
  return { capture, upload, onUploaded }
}

const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }))

/** [녹음] → 지정한 길이만큼 발화 → [정지]. 검토 단계에서 돌아온다 */
async function recordFor(capture: FakeCapture, durationMs: number) {
  click('녹음')
  await act(async () => {})
  await act(async () => {
    capture.emit(sineChunk(durationMs, { sampleRate: capture.sampleRate }))
  })
  click('정지')
  await act(async () => {})
}

describe('녹음 단계 (§5.7)', () => {
  it('처음에는 안내와 [녹음]만 보인다', () => {
    renderRecorder()

    expect(screen.getByText('버튼을 누르고 문장을 읽어 주세요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '녹음' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument()
  })

  it('녹음 중에는 경과 시간과 [정지]를 보인다', async () => {
    const { capture } = renderRecorder()

    click('녹음')
    await act(async () => {})
    await act(async () => {
      capture.emit(sineChunk(2_000, { sampleRate: capture.sampleRate }))
    })

    // 상한은 문항 정의에서 온다 (10초). 경과는 담긴 샘플 수에서 계산된 값이다
    // 시계꼴 표기 `00:02` (KAN-161 3단계, 아트보드) — 같은 줄에 '초'가 두 번 나오지 않게 한다
    //
    // 한 줄이 요소 둘로 나뉘어 있어(KAN-161 4단계: 상한만 흐린 잉크) 기본 매처로는 안 잡힌다
    // — 기본 매처가 보는 것은 자식 요소를 뺀 그 노드의 글자라서다. 줄 전체를 확인해야 하므로
    // textContent로 직접 짚는다: 두 조각이 각각 맞아도 줄이 뒤집혀 있으면 사용자는 못 읽는다.
    expect(
      screen.getByText((_, node) => node?.textContent === '00:02 / 10초', {
        selector: '.record-elapsed',
      }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '정지' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '녹음' })).not.toBeInTheDocument()
  })

  it('정지하면 [재녹음]/[다음]이 나온다 — 재생은 없다', async () => {
    const { capture } = renderRecorder()

    await recordFor(capture, 2_000)

    expect(screen.getByRole('button', { name: '재녹음' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다음' })).toBeInTheDocument()
    expect(screen.getByText('녹음이 끝났어요. 다시 녹음하거나 다음으로 넘어가세요')).toBeInTheDocument()
    // 들어보라고 말하지 않는다 — 재생 기능 자체가 계약에 없다
    expect(screen.queryByRole('button', { name: '재생' })).not.toBeInTheDocument()
  })

  it('[재녹음]은 처음으로 되돌린다 — 서버에는 아무 일도 없다', async () => {
    const { capture, upload } = renderRecorder()

    await recordFor(capture, 2_000)
    click('재녹음')

    expect(screen.getByRole('button', { name: '녹음' })).toBeInTheDocument()
    expect(upload).not.toHaveBeenCalled()
  })
})

describe('품질 게이트 (FR-AD-08)', () => {
  it('너무 짧으면 [다음]을 아예 그리지 않는다', async () => {
    const { capture } = renderRecorder()

    await recordFor(capture, 500)

    expect(screen.getByText('녹음이 너무 짧아요. 1초 이상 읽어 주세요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '재녹음' })).toBeInTheDocument()
  })

  it('목소리가 없으면 [다음]이 막히고 더 크게 읽으라고 안내한다', async () => {
    const { capture } = renderRecorder()

    click('녹음')
    await act(async () => {})
    await act(async () => {
      // 전부 0인 조각 — 길이는 충분하지만 발화가 없다
      capture.emit(new Float32Array(capture.sampleRate * 2))
    })
    click('정지')
    await act(async () => {})

    expect(screen.getByText('목소리가 잘 들리지 않아요. 조금 더 크게 읽어 주세요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument()
  })

  it('소리가 찢어지면 마이크에서 떨어지라고 안내한다', async () => {
    const { capture } = renderRecorder()

    click('녹음')
    await act(async () => {})
    await act(async () => {
      // 진폭 1.0이면 피크가 전 스케일에 닿아 클리핑 비율이 임계를 넘는다
      capture.emit(sineChunk(2_000, { sampleRate: capture.sampleRate, amplitude: 1 }))
    })
    click('정지')
    await act(async () => {})

    expect(screen.getByText('소리가 너무 커요. 마이크에서 조금 떨어져 주세요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument()
  })
})

describe('업로드 (§3.3·§5.1)', () => {
  it('[다음]에서만 올라가고, 접수되면 문항 결과를 알린다', async () => {
    const upload = okUpload('job-9')
    const { capture, onUploaded } = renderRecorder(upload)

    await recordFor(capture, 2_000)
    expect(upload).not.toHaveBeenCalled() // 정지만으로는 시도가 생기지 않는다

    click('다음')
    await act(async () => {})

    expect(upload).toHaveBeenCalledTimes(1)
    const [recording, attemptId] = upload.mock.calls[0]
    expect(onUploaded).toHaveBeenCalledTimes(1)
    expect(onUploaded.mock.calls[0][0]).toEqual({
      itemId: 'v1',
      attemptId,
      analysisJobId: 'job-9',
      durationMs: 2_000,
      qualityStatus: 'NORMAL',
    })
    expect(recording.durationMs).toBe(2_000)
  })

  it('보내는 동안에는 버튼이 사라지고 진행 문구만 남는다', async () => {
    let release: (() => void) | undefined
    const upload = vi.fn<UploadFn>(
      () =>
        new Promise<UploadAccepted>((resolve) => {
          release = () => resolve({ analysisJobId: 'job-1' })
        }),
    )
    const { capture } = renderRecorder(upload)

    await recordFor(capture, 2_000)
    click('다음')
    await act(async () => {})

    expect(screen.getByText('보내는 중…')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '재녹음' })).not.toBeInTheDocument()

    await act(async () => {
      release?.()
    })
  })

  it('[다음]을 연타해도 한 번만 올라간다', async () => {
    const upload = okUpload()
    const { capture } = renderRecorder(upload)

    await recordFor(capture, 2_000)
    // 두 번째 클릭은 uploading 상태가 화면에 반영되기 전에 들어온다 — ref 가드가 막는 경로다
    const next = screen.getByRole('button', { name: '다음' })
    fireEvent.click(next)
    fireEvent.click(next)
    await act(async () => {})

    expect(upload).toHaveBeenCalledTimes(1)
  })

  it('재시도 가능한 실패는 같은 시도 식별자로 다시 보낸다 (§5.2)', async () => {
    const upload = vi
      .fn<UploadFn>()
      .mockRejectedValueOnce(new UploadError('네트워크 오류로 녹음을 보내지 못했어요', null, true))
      .mockResolvedValueOnce({ analysisJobId: 'job-1' })
    const { capture, onUploaded } = renderRecorder(upload)

    await recordFor(capture, 2_000)
    click('다음')
    await act(async () => {})

    expect(screen.getByText('네트워크 오류로 녹음을 보내지 못했어요')).toBeInTheDocument()
    click('다시 시도')
    await act(async () => {})

    expect(upload).toHaveBeenCalledTimes(2)
    // 같은 녹음의 재전송이므로 키가 같다 — 서버에 중복 접수가 생기지 않는다
    expect(upload.mock.calls[1][1]).toBe(upload.mock.calls[0][1])
    expect(onUploaded).toHaveBeenCalledTimes(1)
  })

  it('재시도 불가 실패에는 [재녹음]만 남긴다', async () => {
    const upload = vi
      .fn<UploadFn>()
      .mockRejectedValue(
        new UploadError('지원하지 않는 오디오 형식이에요', 'AUDIO_FORMAT_UNSUPPORTED', false),
      )
    const { capture } = renderRecorder(upload)

    await recordFor(capture, 2_000)
    click('다음')
    await act(async () => {})

    expect(screen.getByText('지원하지 않는 오디오 형식이에요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '재녹음' })).toBeInTheDocument()
  })

  it('[재녹음]으로 다시 읽으면 새 시도 식별자를 쓴다 — 앞 녹음을 덮어쓰지 않는다', async () => {
    const upload = vi
      .fn<UploadFn>()
      .mockRejectedValueOnce(new UploadError('네트워크 오류로 녹음을 보내지 못했어요', null, true))
      .mockResolvedValueOnce({ analysisJobId: 'job-1' })
    const { capture } = renderRecorder(upload)

    await recordFor(capture, 2_000)
    click('다음')
    await act(async () => {})

    // 실패 뒤 [다시 시도] 대신 [재녹음]을 골랐다 — 다시 읽은 음성은 **다른 시도**다.
    // 여기서 키를 물려주면 서버가 첫 녹음의 접수 결과를 그대로 돌려줘 새 음성이 사라진다.
    click('재녹음')
    await recordFor(capture, 3_000)
    click('다음')
    await act(async () => {})

    expect(upload).toHaveBeenCalledTimes(2)
    expect(upload.mock.calls[1][1]).not.toBe(upload.mock.calls[0][1])
    expect(upload.mock.calls[1][0].durationMs).toBe(3_000)
  })

  /*
   * 성공 뒤 화면을 풀지 않는 것은 의도다 (어휘 문항이 제출 성공 후 잠금을 유지하는 것과 같은
   * 규칙). 부모가 곧 다음 문항으로 넘겨 이 컴포넌트를 내리는데, 그 사이에 버튼이 되살아나면
   * 이미 접수된 문항에 두 번째 시도를 만들 틈이 생긴다.
   */
  it('접수된 뒤에는 버튼이 되살아나지 않는다', async () => {
    const { capture } = renderRecorder()

    await recordFor(capture, 2_000)
    click('다음')
    await act(async () => {})

    expect(screen.getByText('보내는 중…')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '재녹음' })).not.toBeInTheDocument()
  })
})

describe('캡처 실패', () => {
  it('마이크를 열지 못하면 사유와 [다시 시도]를 보인다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const onUploaded = vi.fn<(result: ItemResult) => void>()
    render(
      <WebVoiceRecorder
        item={voiceItem()}
        upload={okUpload()}
        onUploaded={onUploaded}
        capture={async () => {
          throw new Error('마이크 없음')
        }}
      />,
    )

    click('녹음')
    await act(async () => {})

    expect(screen.getByText('녹음을 시작하지 못했어요')).toBeInTheDocument()
    click('다시 시도')
    expect(screen.getByRole('button', { name: '녹음' })).toBeInTheDocument()
    warn.mockRestore()
  })
})

/**
 * 억양 곡선 카드 (KAN-56 Stage 5). 곡선 값 자체의 규칙은 `recording/*.test.ts`가 전부 덮으므로
 * 여기서는 **결선**만 본다 — 어느 레인이 무엇을 그리는가, 녹음이 실제로 곡선을 만드는가,
 * 단계에 따라 창이 바뀌는가.
 */
describe('억양 곡선 (KAN-56 Stage 5)', () => {
  const lane = (name: string) => screen.getByRole('img', { name })
  const lanePath = (name: string) => lane(name).querySelector('path')

  /** 곡선 명령 개수. 점이 하나 늘 때 명령도 하나 는다(`curvePath.ts`)이라 곧 점 개수다 */
  const commandCount = (name: string) =>
    (lanePath(name)?.getAttribute('d') ?? '').split(/(?=[MLQ] )/).filter(Boolean).length

  it('가이드 레인은 문항 정의의 곡선을 그린다', () => {
    renderRecorder()

    const d = lanePath('가이드 억양 곡선')!.getAttribute('d')!
    expect(d.startsWith('M ')).toBe(true)
    // 101점짜리 가이드라 곡선 조각(Q)이 그만큼 들어간다
    expect(d).toContain('Q ')
  })

  it('단위가 semitone이 아니면 가이드 레인을 비워 둔다', () => {
    // 다른 단위를 semitone 축에 그리면 조용히 틀린 그림이 된다 - 앱 RecordingScreen과 같은 판정
    renderRecorder(okUpload(), voiceItem({ guideF0: { unit: 'hz', frameIntervalMs: 10, values: [200, 220] } }))

    expect(lanePath('가이드 억양 곡선')).toBeNull()
  })

  it('녹음 전에는 내 억양 레인이 비어 있다', () => {
    renderRecorder()

    expect(lanePath('내 억양 곡선')).toBeNull()
  })

  it('녹음 중에는 발화에서 뽑은 곡선이 자란다', async () => {
    const { capture } = renderRecorder()

    click('녹음')
    await act(async () => {})
    await act(async () => {
      capture.emit(sineChunk(1_000, { sampleRate: capture.sampleRate, frequency: 220 }))
    })

    // 220Hz 사인파는 유성 판정을 통과하므로 중심이 잡히고 곡선이 그려진다
    const d = lanePath('내 억양 곡선')!.getAttribute('d')!
    expect(d.startsWith('M ')).toBe(true)
  })

  it('무음만 들어오면 곡선이 생기지 않는다 - 축이 잡히지 않는다', async () => {
    const { capture } = renderRecorder()

    click('녹음')
    await act(async () => {})
    await act(async () => {
      capture.emit(new Float32Array(capture.sampleRate))
    })

    expect(lanePath('내 억양 곡선')).toBeNull()
  })

  it('Review에서는 라이브 창을 넘긴 발화도 통째로 남는다', async () => {
    // 라이브 창은 가이드(1초)의 두 배인 2초라, 3초 발화는 녹음 중에 앞부분이 창 밖으로 밀린다.
    // 정지하면 창이 녹음 전체 길이로 늘어나 밀렸던 앞부분이 돌아온다 (pitch-curve.md §4).
    const { capture } = renderRecorder()

    click('녹음')
    await act(async () => {})
    await act(async () => {
      capture.emit(sineChunk(3_000, { sampleRate: capture.sampleRate, frequency: 220 }))
    })
    const live = commandCount('내 억양 곡선')

    click('정지')
    await act(async () => {})

    expect(commandCount('내 억양 곡선')).toBeGreaterThan(live)
  })

  it('[재녹음]은 앞 녹음의 곡선을 지운다', async () => {
    const { capture } = renderRecorder()

    await recordFor(capture, 2_000)
    expect(lanePath('내 억양 곡선')).not.toBeNull()

    click('재녹음')

    expect(lanePath('내 억양 곡선')).toBeNull()
    // 가이드는 문항의 것이라 그대로 남는다
    expect(lanePath('가이드 억양 곡선')).not.toBeNull()
  })

  it('곡선 카드는 조작부와 다른 자리에 있다', () => {
    // 카드가 하단 자리에 들어가면 버튼이 화면 밖으로 밀린다
    const { container } = render(
      <WebVoiceRecorder
        item={voiceItem()}
        upload={okUpload()}
        onUploaded={vi.fn()}
        capture={createFakeCapture().factory}
      />,
    )

    expect(container.querySelector('.item-screen__body .curve-card')).not.toBeNull()
    expect(container.querySelector('.item-screen__footer .curve-card')).toBeNull()
    expect(container.querySelector('.item-screen__footer .btn')).not.toBeNull()
  })
})
