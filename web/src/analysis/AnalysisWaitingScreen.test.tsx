import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import type { VoiceItem } from '../progress/testDefinition'
import { AnalysisWaitingScreen, type AnalysisWaitingScreenProps } from './AnalysisWaitingScreen'

function voiceItem(seq: number): VoiceItem {
  return {
    itemId: `v${seq}`,
    seq,
    type: 'VOICE',
    prompt: `문항 ${seq}`,
    maxDurationMs: 10_000,
    guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
  }
}

/**
 * 음성 5문항. 순번은 전체 10문항 기준이다 — 정의가 음성·어휘를 번갈아 두므로 홀수 자리가
 * 음성이고, 대기 화면 목록도 그 번호로 부른다 (네이티브 녹음 화면의 "n / 10"과 같은 값).
 */
const VOICE_ITEMS = [1, 2, 3, 4, 5].map((seq) => ({
  item: voiceItem(seq),
  itemNumber: seq * 2 - 1,
}))

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => (name === '' ? '' : null) },
    json: async () => body,
  } as Response
}

function envelope(code: string, message: string, retryable: boolean, extra: Record<string, unknown> = {}) {
  return { code, message, retryable, retryAfterMs: null, correlationId: 'c_test', ...extra }
}

/** 음성 5문항 상태. 인자로 준 상태를 seq 순서로 싣는다 */
function statusesBody(statuses: string[], quality: (string | undefined)[] = []) {
  return {
    pollAfterMs: 800,
    items: statuses.map((status, index) => ({
      itemId: `v${index + 1}`,
      status,
      ...(quality[index] !== undefined ? { quality: quality[index] } : {}),
      ...(status === 'RETRYABLE_FAILED'
        ? { error: { code: 'AUDIO_TOO_QUIET', retryable: true } }
        : {}),
    })),
  }
}

function fetchFor(handlers: { analyses?: () => Response; complete?: () => Response }): FetchLike {
  return async (input) => {
    const url = String(input)
    if (url.endsWith('/analyses')) {
      return (handlers.analyses ?? (() => jsonResponse(200, statusesBody(Array(5).fill('PROCESSING')))))()
    }
    if (url.endsWith('/complete')) {
      return (handlers.complete ?? (() => jsonResponse(200, { status: 'PROCESSING' })))()
    }
    throw new Error(`예상 못한 요청: ${url}`)
  }
}

function props(overrides: Partial<AnalysisWaitingScreenProps> = {}): AnalysisWaitingScreenProps {
  return {
    apiBase: 'http://localhost:8080',
    sessionId: 'sess-1',
    sessionToken: 'token-1',
    voiceItems: VOICE_ITEMS,
    totalItems: 10,
    onReady: vi.fn(),
    fetchImpl: fetchFor({}),
    ...overrides,
  }
}

/** 화면을 띄우고 첫 회차 응답까지 흘려보낸다 */
async function renderScreen(overrides: Partial<AnalysisWaitingScreenProps> = {}) {
  const view = render(<AnalysisWaitingScreen {...props(overrides)} />)
  await act(async () => {})
  return view
}

beforeEach(() => {
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('진행률 — 분모는 10이다', () => {
  it('어휘 5문항을 완료로 세고 음성 완료를 더한다', async () => {
    await renderScreen({
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(200, statusesBody(['COMPLETED', 'COMPLETED', 'PROCESSING', 'PROCESSING', 'PROCESSING'])),
      }),
    })

    const bar = screen.getByRole('progressbar', { name: '분석 진행률' })
    expect(bar).toHaveAttribute('max', '10')
    // 어휘 5 + 음성 완료 2
    expect(bar).toHaveAttribute('value', '7')
    expect(screen.getByText('7 / 10')).toBeInTheDocument()
  })

  it('음성이 전부 실패해도 분모는 10을 유지한다 — 시도 수와 무관하다', async () => {
    await renderScreen({
      fetchImpl: fetchFor({ analyses: () => jsonResponse(200, statusesBody(Array(5).fill('RETRYABLE_FAILED'))) }),
    })

    const bar = screen.getByRole('progressbar', { name: '분석 진행률' })
    expect(bar).toHaveAttribute('max', '10')
    expect(bar).toHaveAttribute('value', '5')
  })
})

describe('문항별 상태', () => {
  it('음성 5문항을 전체 기준 순번과 함께 세운다 — 네이티브 녹음 화면의 번호와 같다', async () => {
    await renderScreen()

    // 전체 문항 기준 번호로 부른다 — 음성 안에서의 1~5가 아니다
    for (const number of [1, 3, 5, 7, 9]) {
      expect(screen.getByText(`${number}번 문항`)).toBeInTheDocument()
    }
  })

  it('다섯 상태를 사용자 문구로 그린다 — 코드 이름을 그대로 내보내지 않는다', async () => {
    await renderScreen({
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(
            200,
            statusesBody(['COMPLETED', 'PROCESSING', 'RETRYABLE_FAILED', 'FAILED', 'NOT_SUBMITTED']),
          ),
      }),
    })

    expect(screen.getByText('완료')).toBeInTheDocument()
    expect(screen.getByText('분석 중')).toBeInTheDocument()
    expect(screen.getByText('다시 녹음이 필요해요')).toBeInTheDocument()
    expect(screen.getByText('분석 실패')).toBeInTheDocument()
    expect(screen.getByText('녹음 필요')).toBeInTheDocument()
    expect(screen.queryByText(/RETRYABLE_FAILED/)).not.toBeInTheDocument()
  })

  it('품질이 정상이면 적지 않는다 — 전부 "OK"인 목록은 정보가 아니다', async () => {
    await renderScreen({
      fetchImpl: fetchFor({
        analyses: () => jsonResponse(200, statusesBody(Array(5).fill('COMPLETED'), Array(5).fill('OK'))),
      }),
    })

    expect(screen.queryByText(/OK/)).not.toBeInTheDocument()
  })

  it('품질 판정이 정상이 아니면 부연으로 붙인다', async () => {
    await renderScreen({
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(200, statusesBody(['COMPLETED', 'PROCESSING', 'PROCESSING', 'PROCESSING', 'PROCESSING'], ['NOISY'])),
      }),
    })

    expect(screen.getByText(/NOISY/)).toBeInTheDocument()
  })
})

describe('점수 미노출 (KAN-12)', () => {
  it('서버가 점수를 보내도 그릴 자리가 없다 — 파서가 버린다', async () => {
    const body = {
      ...statusesBody(Array(5).fill('COMPLETED')),
      items: statusesBody(Array(5).fill('COMPLETED')).items.map((item) => ({ ...item, score: 88 })),
    }
    const { container } = await renderScreen({
      fetchImpl: fetchFor({ analyses: () => jsonResponse(200, body) }),
    })

    expect(container.textContent).not.toMatch(/88/)
  })
})

describe('결과 확정', () => {
  it('READY면 onReady를 부른다', async () => {
    const onReady = vi.fn()
    await renderScreen({
      onReady,
      fetchImpl: fetchFor({ complete: () => jsonResponse(200, { status: 'READY' }) }),
    })

    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('대기 중에는 부르지 않는다', async () => {
    const onReady = vi.fn()
    await renderScreen({ onReady })

    expect(onReady).not.toHaveBeenCalled()
  })
})

describe('재녹음', () => {
  it('재녹음이 도움이 되는 문항에만 버튼을 준다', async () => {
    await renderScreen({
      onRetake: vi.fn(),
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(
            200,
            statusesBody(['COMPLETED', 'PROCESSING', 'RETRYABLE_FAILED', 'FAILED', 'NOT_SUBMITTED']),
          ),
      }),
    })

    /*
     * COMPLETED·PROCESSING만 빠진다. FAILED에도 버튼을 주는 것은 서버 결정을 따른 것이다 —
     * CompletionJudge가 FAILED를 retakeItems로 묶으므로(2026-08-13), 여기서 버튼을 빼면
     * "다시 녹음해라"라는 409에 대상이 없는 막다른 길이 된다.
     */
    expect(screen.getAllByRole('button', { name: '다시 녹음' })).toHaveLength(3)
  })

  it('FAILED에도 버튼을 준다 — 새 시도가 세션 안의 유일한 복구 경로다 (CompletionJudge 2026-08-13)', async () => {
    const onRetake = vi.fn()
    await renderScreen({
      onRetake,
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(200, statusesBody(['COMPLETED', 'COMPLETED', 'FAILED', 'COMPLETED', 'COMPLETED'])),
      }),
    })

    fireEvent.click(screen.getByRole('button', { name: '다시 녹음' }))

    expect(onRetake).toHaveBeenCalledWith('v3')
  })

  it('누르면 그 문항의 itemId로 호출한다', async () => {
    const onRetake = vi.fn()
    await renderScreen({
      onRetake,
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(200, statusesBody(['COMPLETED', 'COMPLETED', 'RETRYABLE_FAILED', 'COMPLETED', 'COMPLETED'])),
      }),
    })

    fireEvent.click(screen.getByRole('button', { name: '다시 녹음' }))

    expect(onRetake).toHaveBeenCalledWith('v3')
  })

  it('onRetake가 없으면 버튼을 그리지 않는다 — 눌러도 아무 일 없는 버튼을 두지 않는다', async () => {
    await renderScreen({
      fetchImpl: fetchFor({ analyses: () => jsonResponse(200, statusesBody(Array(5).fill('RETRYABLE_FAILED'))) }),
    })

    expect(screen.queryByRole('button', { name: '다시 녹음' })).not.toBeInTheDocument()
  })
})

describe('멈춘 상태의 출구', () => {
  it('409 재녹음 필요면 안내와 함께 목록을 남긴다', async () => {
    await renderScreen({
      onRetake: vi.fn(),
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(200, statusesBody(['COMPLETED', 'COMPLETED', 'RETRYABLE_FAILED', 'COMPLETED', 'COMPLETED'])),
        complete: () =>
          jsonResponse(
            409,
            envelope('RESULT_RETAKE_REQUIRED', '실패한 문항이 있습니다.', true, { retakeItems: ['v3'] }),
          ),
      }),
    })

    expect(screen.getByText('일부 문항을 다시 녹음해야 해요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 녹음' })).toBeInTheDocument()
  })

  it('422 미제출이면 다른 문구를 준다', async () => {
    await renderScreen({
      onRetake: vi.fn(),
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(200, statusesBody(['COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'NOT_SUBMITTED'])),
        complete: () =>
          jsonResponse(
            422,
            envelope('RESULT_INCOMPLETE', '아직 완료하지 않은 문항이 있습니다.', false, {
              missingItems: ['v5'],
            }),
          ),
      }),
    })

    expect(screen.getByText('아직 보내지 않은 문항이 있어요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 녹음' })).toBeInTheDocument()
  })

  it('409인데 FAILED 문항이면 버튼이 있다 — 막다른 길이 되지 않는다 (PR #41 리뷰)', async () => {
    await renderScreen({
      onRetake: vi.fn(),
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(200, statusesBody(['COMPLETED', 'COMPLETED', 'FAILED', 'COMPLETED', 'COMPLETED'])),
        complete: () =>
          jsonResponse(
            409,
            envelope('RESULT_RETAKE_REQUIRED', '실패한 문항이 있습니다.', true, { retakeItems: ['v3'] }),
          ),
      }),
    })

    expect(screen.getByText('일부 문항을 다시 녹음해야 해요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 녹음' })).toBeInTheDocument()
  })

  it('손댈 문항이 목록에 없으면 앱 재시작으로 안내한다 — 어휘 미제출이 그 경로다', async () => {
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    await renderScreen({
      onRetake: vi.fn(),
      // 음성은 전부 끝났는데 서버는 어휘(w5) 미제출로 422를 준다. 이 목록에 w5는 없다
      fetchImpl: fetchFor({
        analyses: () => jsonResponse(200, statusesBody(Array(5).fill('COMPLETED'))),
        complete: () =>
          jsonResponse(
            422,
            envelope('RESULT_INCOMPLETE', '아직 완료하지 않은 문항이 있습니다.', false, {
              missingItems: ['w5'],
            }),
          ),
      }),
    })

    expect(screen.getByText('여기서는 더 진행할 수 없어요')).toBeInTheDocument()
    expect(screen.getByText('앱을 다시 시작해 테스트를 처음부터 진행해 주세요')).toBeInTheDocument()
    // "다시 녹음해 주세요"라고 말해 놓고 대상이 없는 상태를 만들지 않는다
    expect(screen.queryByText('아래 목록에서 해당 문항을 다시 녹음해 주세요')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 녹음' })).not.toBeInTheDocument()
    // 서버가 짚은 문항을 진단에 남긴다 — 이 목록과 어긋난 원인 추적의 시작점이다
    expect(errorLog).toHaveBeenCalledWith(
      '[analysis] 사용자가 손댈 수 있는 문항이 없습니다',
      expect.objectContaining({ itemIds: ['w5'] }),
    )
    errorLog.mockRestore()
  })

  it('브리지가 없어 버튼을 못 그리는 경우도 같은 안내로 간다', async () => {
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    await renderScreen({
      // onRetake 없음 = 브라우저 단독 실행
      fetchImpl: fetchFor({
        analyses: () => jsonResponse(200, statusesBody(Array(5).fill('RETRYABLE_FAILED'))),
        complete: () =>
          jsonResponse(
            409,
            envelope('RESULT_RETAKE_REQUIRED', '실패한 문항이 있습니다.', true, {
              retakeItems: ['v1', 'v2', 'v3', 'v4', 'v5'],
            }),
          ),
      }),
    })

    expect(screen.getByText('여기서는 더 진행할 수 없어요')).toBeInTheDocument()
    errorLog.mockRestore()
  })

  it('세션이 만료되면 봉투 문구를 그대로 보여 준다', async () => {
    await renderScreen({
      fetchImpl: fetchFor({
        analyses: () =>
          jsonResponse(401, envelope('SESSION_EXPIRED', '세션이 만료되었습니다. 테스트를 다시 시작해 주세요.', false)),
      }),
    })

    expect(screen.getByText('세션이 만료되었습니다. 테스트를 다시 시작해 주세요.')).toBeInTheDocument()
  })

  it('폴링 상한을 넘기면 [다시 시도]가 나오고, 누르면 다시 돈다', async () => {
    vi.useFakeTimers()
    let analysesCalls = 0
    render(
      <AnalysisWaitingScreen
        {...props({
          fetchImpl: fetchFor({
            analyses: () => {
              analysesCalls += 1
              return jsonResponse(200, statusesBody(Array(5).fill('PROCESSING')))
            },
          }),
        })}
      />,
    )
    await act(async () => {})

    await act(async () => {
      await vi.advanceTimersByTimeAsync(70_000)
    })
    expect(screen.getByText('분석이 예상보다 오래 걸리고 있어요')).toBeInTheDocument()

    const before = analysesCalls
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    })
    await act(async () => {})

    expect(analysesCalls).toBe(before + 1)
    expect(screen.queryByText('분석이 예상보다 오래 걸리고 있어요')).not.toBeInTheDocument()
  })

  it('[테스트 종료]를 주지 않는다 — KAN-147의 이탈 버튼 제거 결정을 따른다', async () => {
    vi.useFakeTimers()
    render(<AnalysisWaitingScreen {...props()} />)
    await act(async () => {})
    await act(async () => {
      await vi.advanceTimersByTimeAsync(70_000)
    })

    expect(screen.queryByRole('button', { name: /테스트 종료|나가기|그만/ })).not.toBeInTheDocument()
  })
})

describe('일시적 오류', () => {
  it('네트워크가 끊겨도 문항 목록을 지우지 않고 부연으로만 알린다', async () => {
    let healthy = true
    await renderScreen({
      fetchImpl: async (input) => {
        const url = String(input)
        if (url.endsWith('/analyses')) {
          if (!healthy) throw new TypeError('Failed to fetch')
          healthy = false
          return jsonResponse(200, statusesBody(Array(5).fill('PROCESSING')))
        }
        return jsonResponse(200, { status: 'PROCESSING' })
      },
    })

    expect(screen.getAllByText('분석 중')).toHaveLength(5)
    expect(screen.getByText('결과를 만들고 있어요')).toBeInTheDocument()
  })
})
