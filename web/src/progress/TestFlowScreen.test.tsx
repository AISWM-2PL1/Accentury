import { act, fireEvent, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TestFlowScreen } from './TestFlowScreen'
import type { FetchLike } from './fetchTestDefinition'
import { snapshotKey, type SnapshotStorage } from './progressSnapshot'
import type { TestDefinition, TestItem } from './testDefinition'

const TEST_VERSION = 'gn-2026.08.1'
const API_BASE = 'http://localhost:8080'

function item(seq: number): TestItem {
  if (seq % 2 === 1) {
    return {
      itemId: `item-${seq}`,
      seq,
      type: 'VOICE',
      prompt: `음성 문항 ${seq}`,
      maxDurationMs: 10_000,
      guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
    }
  }
  return {
    itemId: `item-${seq}`,
    seq,
    type: 'VOCABULARY',
    prompt: `어휘 문항 ${seq}`,
    choices: [{ choiceId: 'c1', text: '보기1' }],
  }
}

function tenItemDefinition(): TestDefinition {
  return {
    testVersion: TEST_VERSION,
    scoreVersion: 'sv-0.3',
    dialect: 'GYEONGNAM',
    estimatedDurationSec: 180,
    items: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(item),
  }
}

/**
 * 정의를 돌려주는 fetch 대역 (Response 대역은 fetchTestDefinition.test.ts와 같은 방식).
 *
 * 분석 대기 화면(KAN-14)이 붙은 뒤로는 마지막 문항 제출 후 `/analyses`·`/complete`도
 * 이 대역을 탄다. 정의 본문을 그대로 돌려주면 대기 화면이 계약 위반으로 넘어가 화면 내용이
 * 바뀌므로, 두 URL은 각자 계약에 맞는 최소 응답을 준다. 분석은 끝나지 않은 상태로 둔다 —
 * 이 파일이 검증하는 것은 진행 흐름이지 폴링이 아니다.
 */
function okFetch(): ReturnType<typeof vi.fn<FetchLike>> {
  return vi.fn<FetchLike>(async (input) => {
    const url = String(input)
    if (url.endsWith('/analyses')) {
      return {
        ok: true,
        status: 200,
        headers: { get: (name: string) => (name === '' ? '' : null) },
        json: async () => ({
          pollAfterMs: 800,
          items: [1, 3, 5, 7, 9].map((seq) => ({ itemId: `item-${seq}`, status: 'PROCESSING' })),
        }),
      } as Response
    }
    if (url.endsWith('/complete')) {
      return {
        ok: true,
        status: 200,
        headers: { get: (name: string) => (name === '' ? '' : null) },
        json: async () => ({ status: 'PROCESSING' }),
      } as Response
    }
    return {
      ok: true,
      status: 200,
      json: async () => tenItemDefinition(),
    } as Response
  })
}

function memoryStorage(): SnapshotStorage {
  const map = new Map<string, string>()
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  }
}

interface RenderOptions {
  storage?: SnapshotStorage
  sessionId?: string
  /** StrictMode로 감쌀지. 마운트 effect 이중 실행을 재현할 때만 켠다 */
  strict?: boolean
  /** 분석 대기 화면이 결과 확정을 알릴 자리 (KAN-14) */
  onAnalysisReady?: () => void
}

function renderScreen(
  fetchImpl: FetchLike,
  { storage, sessionId, strict, onAnalysisReady }: RenderOptions = {},
) {
  return render(
    <TestFlowScreen
      apiBase={API_BASE}
      testVersion={TEST_VERSION}
      sessionId={sessionId}
      storage={storage ?? memoryStorage()}
      onAnalysisReady={onAnalysisReady}
      fetchImpl={fetchImpl}
    />,
    strict === true ? { wrapper: StrictMode } : undefined,
  )
}

/** 네이티브 브리지가 붙은 앱 환경. 돌려주는 spy가 startVoiceItem 호출을 받는다 */
function stubBridge() {
  const startVoiceItem = vi.fn()
  window.AccenturyBridge = {
    requestMicPermission: vi.fn(),
    startVoiceItem,
    getContractVersion: () => 1,
    getSessionToken: () => 'stub-token',
  }
  return startVoiceItem
}

/*
 * 첫 음성 문항이 브리지 판정까지 끝내기를 기다리는 두 입구.
 * 문항 문구만 기다리면 안 되는 이유: 정의 로딩과 전환 호출은 서로 다른 커밋이라, 문구가 뜬
 * 시점에는 아직 판정 결과(대기 뷰냐 폴백이냐)가 나오기 전이다.
 * 대기 문구가 아니라 재진입 버튼을 기다린다 — KAN-146으로 대기 문구가 판정 전후에 같아졌기 때문에,
 * 문구를 기다리면 판정이 나기 전 첫 프레임에서 이미 통과해 버려 동기화 지점 역할을 못 한다.
 */
const findRecordingWait = () => screen.findByRole('button', { name: '녹음 화면 다시 열기' })
const findDevSubmit = () => screen.findByRole('button', { name: '제출 (개발용)' })

/** 네이티브가 녹음을 마치고 결과를 돌려주는 상황 */
function deliverResult(itemId: string) {
  act(() => {
    window.AccenturyWeb!.onItemResult(
      JSON.stringify({
        itemId,
        attemptId: `attempt-${itemId}`,
        analysisJobId: `job-${itemId}`,
        durationMs: 3_200,
        qualityStatus: 'NORMAL',
      }),
    )
  })
}

/** 어휘 문항에 답한다 — 보기를 고르고 [다음]으로 확정 (KAN-13 선택 UI) */
function answerVocabulary() {
  fireEvent.click(screen.getByRole('radio', { name: '보기1' }))
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
}

/**
 * 브리지가 없는 환경에서 현재 문항을 한 칸 진행시킨다 — 음성은 개발용 제출 버튼,
 * 어휘는 보기 선택 + [다음]이다. fireEvent를 쓰는 이유는 act로 감싸 리렌더까지 흘려보내기 위해서다.
 */
function advance() {
  const devSubmit = screen.queryByRole('button', { name: '제출 (개발용)' })
  if (devSubmit) {
    fireEvent.click(devSubmit)
  } else {
    answerVocabulary()
  }
}

/** 브리지 없는 환경에서 10문항을 끝까지 민다 */
async function finishAllItems() {
  for (let i = 0; i < 10; i += 1) {
    advance()
    // 어휘 제출은 비동기다(개발용 통로도 Promise) — 다음 문항이 뜨기 전에 다음 advance가
    // 돌지 않도록 microtask를 비운다
    await act(async () => {})
  }

  // 마지막 제출 뒤 대기 화면이 마운트되고 첫 회차(analyses → complete)가 순서대로 돈다.
  // 두 요청이 직렬이라 microtask를 한 번 더 비워야 완료 판정까지 반영된다.
  await act(async () => {})
  await act(async () => {})
}

/** 브리지가 붙은 환경에서 10문항을 끝까지 민다 — 음성은 네이티브 결과 수신, 어휘는 선택 + [다음] */
async function finishAllItemsWithBridge() {
  for (let seq = 1; seq <= 10; seq += 1) {
    if (seq % 2 === 1) deliverResult(`item-${seq}`)
    else answerVocabulary()
    await act(async () => {})
  }

  // 마지막 제출 뒤 대기 화면이 마운트되고 첫 회차(analyses → complete)가 순서대로 돈다.
  // 두 요청이 직렬이라 microtask를 한 번 더 비워야 완료 판정까지 반영된다.
  await act(async () => {})
  await act(async () => {})
}

/** 정의 + 대기 화면 두 엔드포인트를 갈아 끼울 수 있는 fetch 대역 (KAN-14 결선 검증용) */
function waitingFetch(handlers: {
  analyses?: () => unknown
  complete?: () => unknown
}): ReturnType<typeof vi.fn<FetchLike>> {
  const ok = (body: unknown) =>
    ({
      ok: true,
      status: 200,
      headers: { get: (name: string) => (name === '' ? '' : null) },
      json: async () => body,
    }) as Response

  return vi.fn<FetchLike>(async (input) => {
    const url = String(input)
    if (url.endsWith('/analyses')) {
      return ok(
        handlers.analyses?.() ?? {
          pollAfterMs: 800,
          items: [1, 3, 5, 7, 9].map((seq) => ({ itemId: `item-${seq}`, status: 'PROCESSING' })),
        },
      )
    }
    if (url.endsWith('/complete')) return ok(handlers.complete?.() ?? { status: 'PROCESSING' })
    // 브리지가 붙은 경로에서는 어휘 답안이 실제로 서버로 나간다 (KAN-13)
    if (url.endsWith('/answer')) return ok({ accepted: true })
    return ok(tenItemDefinition())
  })
}

afterEach(() => {
  delete window.AccenturyBridge
  delete window.AccenturyWeb
})

describe('정의 로딩', () => {
  it('로딩 중 안내를 보이다가 첫 문항으로 전환된다', async () => {
    const fetchImpl = okFetch()
    renderScreen(fetchImpl)

    expect(screen.getByText('문항을 불러오는 중…')).toBeInTheDocument()

    expect(await screen.findByText('음성 문항 1')).toBeInTheDocument()
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls[0][0]).toBe(`${API_BASE}/v0/tests/${TEST_VERSION}`)
  })

  it('로딩에 실패하면 안내와 [다시 시도]를 보이고, 재시도가 성공하면 진행으로 넘어간다', async () => {
    const fetchImpl = vi
      .fn<FetchLike>()
      .mockImplementationOnce(async () => ({ ok: false, status: 503, json: async () => ({}) }) as Response)
      .mockImplementationOnce(
        async () => ({ ok: true, status: 200, json: async () => tenItemDefinition() }) as Response,
      )
    renderScreen(fetchImpl)

    expect(await screen.findByText('문항을 불러오지 못했어요')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 503/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('음성 문항 1')).toBeInTheDocument()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })
})

describe('문항 진행', () => {
  it('진행바가 첫 문항을 1/10으로 보여준다 (endowed progress)', async () => {
    renderScreen(okFetch())
    await screen.findByText('음성 문항 1')

    const bar = screen.getByRole('progressbar', { name: '문항 진행률' })
    expect(bar).toHaveAttribute('value', '1')
    expect(bar).toHaveAttribute('max', '10')
    expect(screen.getByText('1 / 10')).toBeInTheDocument()
  })

  it('유형 뱃지가 문항 유형을 따라간다', async () => {
    renderScreen(okFetch())
    await findDevSubmit()
    expect(screen.getByText('🎤 음성 문항')).toBeInTheDocument()

    advance()

    expect(await screen.findByText('어휘 문항 2')).toBeInTheDocument()
    expect(screen.getByText('📝 단어 문항')).toBeInTheDocument()
  })

  it('제출을 통지하면 다음 문항과 2/10이 된다', async () => {
    renderScreen(okFetch())
    await findDevSubmit()

    advance()

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    expect(screen.getByText('2 / 10')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '문항 진행률' })).toHaveAttribute('value', '2')
  })

  it('마지막 문항을 제출하면 분석 대기 화면으로 넘어간다 (KAN-14)', async () => {
    renderScreen(okFetch())
    await findDevSubmit()

    await finishAllItems()

    // 진행률이 분석 기준으로 바뀌고 음성 5문항이 목록으로 선다
    expect(screen.getByRole('progressbar', { name: '분석 진행률' })).toBeInTheDocument()
    expect(screen.getByText('음성 1번')).toBeInTheDocument()
    expect(screen.getByText('음성 5번')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '제출 (개발용)' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: '보기1' })).not.toBeInTheDocument()
  })

  it('진행 중 저장된 스냅샷으로 다시 열면 그 문항에서 이어진다', async () => {
    const storage = memoryStorage()
    const { unmount } = renderScreen(okFetch(), { storage })
    await findDevSubmit()
    advance()
    advance()
    // 어휘 제출(두 번째 advance)이 비동기라, 진행 통지가 스냅샷에 닿기 전에 내리면 안 된다
    await act(async () => {})
    unmount()

    expect(JSON.parse(storage.getItem(snapshotKey())!).submittedItemIds).toEqual(['item-1', 'item-2'])

    renderScreen(okFetch(), { storage })

    expect(await screen.findByText('음성 문항 3')).toBeInTheDocument()
    expect(screen.getByText('3 / 10')).toBeInTheDocument()
  })
})

describe('VOICE 문항 — 네이티브 녹음 화면 전환 (KAN-100)', () => {
  it('음성 문항에 들어가면 문항 컨텍스트를 실어 네이티브 전환을 호출한다', async () => {
    const startVoiceItem = stubBridge()
    renderScreen(okFetch())
    await findRecordingWait()

    expect(startVoiceItem).toHaveBeenCalledTimes(1)
    expect(JSON.parse(startVoiceItem.mock.calls[0][0])).toEqual({
      itemId: 'item-1',
      prompt: '음성 문항 1',
      itemNumber: 1,
      totalItems: 10,
      maxDurationMs: 10_000,
      // 가이드 곡선은 정의가 든 그대로 실려 간다 (KAN-102) — 가공되면 이 대조가 깨진다
      guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
    })
  })

  it('네이티브가 결과를 돌려주면 다음 문항으로 넘어간다', async () => {
    stubBridge()
    renderScreen(okFetch())
    await findRecordingWait()

    deliverResult('item-1')

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    expect(screen.getByText('2 / 10')).toBeInTheDocument()
  })

  it('다음 음성 문항에서는 그 문항의 순번으로 다시 호출한다', async () => {
    const startVoiceItem = stubBridge()
    // 브리지가 있으면 어휘 답안이 실제로 제출된다 — 세션 없이는 가드가 막으므로 실어 준다
    renderScreen(okFetch(), { sessionId: 'sess-1' })
    await findRecordingWait()

    deliverResult('item-1')
    answerVocabulary() // 어휘 문항 2

    expect(await screen.findByText('음성 문항 3')).toBeInTheDocument()
    expect(startVoiceItem).toHaveBeenCalledTimes(2)
    expect(JSON.parse(startVoiceItem.mock.calls[1][0])).toMatchObject({ itemId: 'item-3', itemNumber: 3 })
  })

  it('StrictMode에서도 같은 문항을 두 번 알리지 않는다', async () => {
    const startVoiceItem = stubBridge()
    renderScreen(okFetch(), { strict: true })
    await findRecordingWait()

    expect(startVoiceItem).toHaveBeenCalledTimes(1)
  })

  it('[녹음 화면 다시 열기]로 같은 문항 전환을 다시 요청할 수 있다 (나가기 이탈 복구)', async () => {
    // 네이티브 [나가기] 이탈은 웹에 통지되지 않는다 — 대기 뷰의 재진입 버튼이 유일한 복구 통로다.
    const startVoiceItem = stubBridge()
    renderScreen(okFetch())
    await findRecordingWait()

    fireEvent.click(screen.getByRole('button', { name: '녹음 화면 다시 열기' }))

    expect(startVoiceItem).toHaveBeenCalledTimes(2)
    expect(JSON.parse(startVoiceItem.mock.calls[1][0])).toMatchObject({ itemId: 'item-1' })
  })

  it('상태 머신이 거부하는 결과 통지는 진행을 움직이지 않는다', async () => {
    stubBridge()
    renderScreen(okFetch())
    await findRecordingWait()

    deliverResult('item-7') // 순서 위반
    deliverResult('item-1')
    deliverResult('item-1') // 중복

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    expect(screen.getByText('2 / 10')).toBeInTheDocument()
  })

  it('브리지가 없으면(브라우저 단독) 안내와 개발용 제출 버튼을 남긴다', async () => {
    renderScreen(okFetch())
    await findDevSubmit()

    expect(screen.getByText('녹음 화면을 열 수 없어요 (앱 밖에서 실행 중)')).toBeInTheDocument()
    // 폴백은 대기 뷰와 배타적이다 — 대기 문구도, 재진입 버튼도 남으면 안 된다
    expect(screen.queryByText('잠시만요…')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '녹음 화면 다시 열기' })).not.toBeInTheDocument()

    advance()

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
  })

  it('화면을 떠나면 결과 수신자를 해제한다', async () => {
    stubBridge()
    const { unmount } = renderScreen(okFetch())
    await findRecordingWait()
    expect(window.AccenturyWeb).toBeDefined()

    unmount()

    expect(window.AccenturyWeb).toBeUndefined()
  })
})

describe('VOCABULARY 문항 — 보기 선택 (KAN-13)', () => {
  it('보기를 고르고 [다음]을 누르면 다음 문항으로 넘어간다', async () => {
    stubBridge()
    // 브리지가 있으면 어휘 답안이 실제로 제출된다 — 세션 없이는 가드가 막으므로 실어 준다
    renderScreen(okFetch(), { sessionId: 'sess-1' })
    await findRecordingWait()
    deliverResult('item-1')

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    answerVocabulary()

    expect(await screen.findByText('음성 문항 3')).toBeInTheDocument()
    expect(screen.getByText('3 / 10')).toBeInTheDocument()
  })

  it('어휘 문항에서는 네이티브 전환을 부르지 않는다', async () => {
    const startVoiceItem = stubBridge()
    renderScreen(okFetch())
    await findRecordingWait()
    deliverResult('item-1')

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    expect(startVoiceItem).toHaveBeenCalledTimes(1) // 문항 1의 호출 그대로
  })

  it('앱(브리지 존재)에서는 답안이 브리지 토큰을 싣고 서버로 제출된다 (KAN-13)', async () => {
    stubBridge()
    const fetchImpl = okFetch()
    renderScreen(fetchImpl, { sessionId: 'sess-1' })
    await findRecordingWait()
    deliverResult('item-1')

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    answerVocabulary()

    // 정의 조회(1) + 답안 제출(2). 제출 성공 후에만 다음 문항으로 넘어간다 (AC 2항)
    expect(await screen.findByText('음성 문항 3')).toBeInTheDocument()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    const [url, init] = fetchImpl.mock.calls[1]
    expect(url).toBe(`${API_BASE}/v0/sessions/sess-1/vocab-items/item-2/answer`)
    expect(init?.method).toBe('POST')
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer stub-token' })
    expect((init?.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
    expect(JSON.parse(init?.body as string)).toEqual({ choiceId: 'c1' })
  })

  it('브리지가 없으면(브라우저 단독) 서버 제출 없이 진행만 민다 — 음성의 개발용 통로와 같다', async () => {
    const fetchImpl = okFetch()
    renderScreen(fetchImpl)
    await findDevSubmit()
    advance() // 음성 문항 1 (개발용 제출)

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    answerVocabulary()

    expect(await screen.findByText('음성 문항 3')).toBeInTheDocument()
    expect(fetchImpl).toHaveBeenCalledTimes(1) // 정의 조회뿐 — 답안 POST가 없다
  })
})

describe('세션 격리 — 다른 세션의 진행을 이어받지 않는다', () => {
  it('sessionId를 주면 그 세션 키에 저장한다', async () => {
    const storage = memoryStorage()
    renderScreen(okFetch(), { storage, sessionId: 'sess-1' })
    await findDevSubmit()

    advance()

    expect(storage.getItem(snapshotKey('sess-1'))).not.toBeNull()
    expect(storage.getItem(snapshotKey())).toBeNull()
  })

  it('다른 세션의 스냅샷이 남아 있어도 처음부터 시작한다', async () => {
    const storage = memoryStorage()
    const { unmount } = renderScreen(okFetch(), { storage, sessionId: 'sess-1' })
    await findDevSubmit()
    advance()
    advance()
    unmount()

    renderScreen(okFetch(), { storage, sessionId: 'sess-2' })

    expect(await screen.findByText('음성 문항 1')).toBeInTheDocument()
    expect(screen.getByText('1 / 10')).toBeInTheDocument()
    // 세션 1의 기록은 지워지지 않는다 — 남의 진행을 폐기할 권리가 없다는 것이 키 분리의 이유다
    expect(storage.getItem(snapshotKey('sess-1'))).not.toBeNull()
  })
})

describe('폴링 부재 — 문항 진행 중에는 요청이 없다 (KAN-14 규칙 2항)', () => {
  it('정의 조회 1회 외에 추가 요청이 발생하지 않는다', async () => {
    const fetchImpl = okFetch()
    const globalFetch = vi.fn()
    vi.stubGlobal('fetch', globalFetch)
    try {
      renderScreen(fetchImpl)
      await findDevSubmit()

      for (let i = 0; i < 10; i += 1) {
        advance()
        await act(async () => {}) // 어휘 제출(개발용 통로 포함)은 비동기 — microtask를 비운다
      }

      expect(fetchImpl).toHaveBeenCalledTimes(1)
      expect(globalFetch).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
})

describe('분석 대기 결선 (KAN-14)', () => {
  it('결과가 확정되면 onAnalysisReady로 알린다 — 화면 이동은 App이 한다', async () => {
    stubBridge()
    const onAnalysisReady = vi.fn()
    renderScreen(waitingFetch({ complete: () => ({ status: 'READY' }) }), {
      onAnalysisReady,
      sessionId: 'sess-1',
    })
    await findRecordingWait()

    await finishAllItemsWithBridge()

    expect(onAnalysisReady).toHaveBeenCalledTimes(1)
  })

  it('분석이 끝나지 않았으면 알리지 않는다', async () => {
    stubBridge()
    const onAnalysisReady = vi.fn()
    renderScreen(waitingFetch({}), { onAnalysisReady, sessionId: 'sess-1' })
    await findRecordingWait()

    await finishAllItemsWithBridge()

    expect(onAnalysisReady).not.toHaveBeenCalled()
  })

  it('브리지가 없으면 재녹음 버튼을 그리지 않는다 — 눌러도 녹음 화면이 열리지 않는다', async () => {
    renderScreen(
      waitingFetch({
        analyses: () => ({
          pollAfterMs: 800,
          items: [1, 3, 5, 7, 9].map((seq) => ({
            itemId: `item-${seq}`,
            status: 'RETRYABLE_FAILED',
            error: { code: 'AUDIO_TOO_QUIET', retryable: true },
          })),
        }),
      }),
      { sessionId: 'sess-1' },
    )
    await findDevSubmit()

    await finishAllItems()

    expect(screen.queryByRole('button', { name: '다시 녹음' })).not.toBeInTheDocument()
  })

  it('브리지 없는 실행은 폴링 전에 막힌다 — 세션 토큰이 없다 (결과 화면과 같은 규칙)', async () => {
    renderScreen(waitingFetch({}), { sessionId: 'sess-1' })
    await findDevSubmit()

    await finishAllItems()

    // 사용자가 할 수 있는 게 없는 실패라 비난 없는 문구만 남긴다. 진단은 콘솔로 간다
    expect(screen.getByText('분석 상태를 확인할 수 없어요. 앱을 다시 시작해 주세요')).toBeInTheDocument()
  })

  it('재녹음을 누르면 그 문항으로 녹음 화면을 다시 연다 — 브리지 계약은 그대로다', async () => {
    const startVoiceItem = stubBridge()
    renderScreen(
      waitingFetch({
        analyses: () => ({
          pollAfterMs: 800,
          items: [
            { itemId: 'item-1', status: 'COMPLETED', quality: 'OK' },
            {
              itemId: 'item-3',
              status: 'RETRYABLE_FAILED',
              error: { code: 'AUDIO_TOO_QUIET', retryable: true },
            },
            { itemId: 'item-5', status: 'COMPLETED', quality: 'OK' },
            { itemId: 'item-7', status: 'COMPLETED', quality: 'OK' },
            { itemId: 'item-9', status: 'COMPLETED', quality: 'OK' },
          ],
        }),
      }),
      { sessionId: 'sess-1' },
    )
    await findRecordingWait()
    await finishAllItemsWithBridge()

    startVoiceItem.mockClear()
    fireEvent.click(screen.getByRole('button', { name: '다시 녹음' }))

    expect(startVoiceItem).toHaveBeenCalledTimes(1)
    // 첫 녹음 때와 같은 순번을 준다 — 사용자가 "3번 문항"으로 기억한다
    expect(JSON.parse(startVoiceItem.mock.calls[0][0])).toMatchObject({
      itemId: 'item-3',
      itemNumber: 3,
      totalItems: 10,
    })
  })

  it('재녹음 결과가 돌아오면 폴링을 다시 세운다', async () => {
    stubBridge()
    const fetchImpl = waitingFetch({})
    renderScreen(fetchImpl, { sessionId: 'sess-1' })
    await findRecordingWait()
    await finishAllItemsWithBridge()

    const before = fetchImpl.mock.calls.filter(([url]) => String(url).endsWith('/analyses')).length
    deliverResult('item-3')
    await act(async () => {})

    const after = fetchImpl.mock.calls.filter(([url]) => String(url).endsWith('/analyses')).length
    expect(after).toBe(before + 1)
  })
})
