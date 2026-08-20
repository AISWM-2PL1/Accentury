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

/** 정의를 돌려주는 fetch 대역 (Response 대역은 fetchTestDefinition.test.ts와 같은 방식) */
function okFetch(): ReturnType<typeof vi.fn<FetchLike>> {
  return vi.fn<FetchLike>(async () => ({
    ok: true,
    status: 200,
    json: async () => tenItemDefinition(),
  }) as Response)
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
}

function renderScreen(fetchImpl: FetchLike, { storage, sessionId, strict }: RenderOptions = {}) {
  return render(
    <TestFlowScreen
      apiBase={API_BASE}
      testVersion={TEST_VERSION}
      sessionId={sessionId}
      storage={storage ?? memoryStorage()}
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

  it('마지막 문항을 제출하면 분석 대기 자리 표시로 넘어간다', async () => {
    renderScreen(okFetch())
    await findDevSubmit()

    for (let i = 0; i < 10; i += 1) {
      advance()
      // 어휘 제출은 비동기다(개발용 통로도 Promise) — 다음 문항이 뜨기 전에 다음 advance가
      // 돌지 않도록 microtask를 비운다
      await act(async () => {})
    }

    expect(screen.getByText('분석 대기 화면 (KAN-14 예정)')).toBeInTheDocument()
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
