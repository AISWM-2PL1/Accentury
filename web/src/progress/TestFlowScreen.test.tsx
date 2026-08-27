import { act, fireEvent, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createFakeCapture, sineChunk, type FakeCapture } from '../audio/testing/fakeCapture'
import { TestFlowScreen } from './TestFlowScreen'
import type { FetchLike } from './fetchTestDefinition'
import { snapshotKey, type SnapshotStorage } from './progressSnapshot'
import type { TestDefinition, TestItem } from './testDefinition'

const TEST_VERSION = 'gn-2026.08.1'
const API_BASE = 'http://localhost:8080'
/** 웹 단독 실행의 세션 토큰. 실물 출처는 `session/webSession`이고 여기서는 값만 흉내 낸다 */
const WEB_TOKEN = 'web-token'
/** 브라우저 녹음이 담는 길이. 품질 게이트(1초)를 넉넉히 넘긴다 */
const VOICE_DURATION_MS = 2_000

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
    // 브리지가 없는 실행에서는 음성 문항이 웹에서 녹음돼 이 경로로 올라간다 (KAN-56 Stage 3)
    if (url.endsWith('/recording')) {
      return {
        ok: true,
        status: 202,
        headers: { get: () => null },
        json: async () => ({ analysisJobId: 'job-web' }),
      } as unknown as Response
    }
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

/** 대역이 실제로 받은 URL들. 요청 "종류"를 세는 단언에 쓴다 */
function urls(fetchImpl: ReturnType<typeof vi.fn<FetchLike>>): string[] {
  return fetchImpl.mock.calls.map(([input]) => String(input))
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
  /**
   * 기본값이 빈 문자열이 아니라 실제 세션인 이유: 브라우저 녹음 업로드가 세션 없이는 가드에
   * 걸린다 (`uploadRecording`의 CLIENT_MISSING_sessionId). 세션 키 분리를 검사하는 테스트만
   * 자기 값을 명시한다.
   */
  sessionId?: string
  /** StrictMode로 감쌀지. 마운트 effect 이중 실행을 재현할 때만 켠다 */
  strict?: boolean
  /** 분석 대기 화면이 결과 확정을 알릴 자리 (KAN-14) */
  onAnalysisReady?: () => void
  /**
   * 웹 단독 실행의 세션 토큰. 주지 않으면 App과 같은 규칙으로 정한다 — 브리지가 있으면
   * 주입하지 않고(앱은 브리지에서 읽는다), 없으면 [WEB_TOKEN]을 준다. 빈 값을 명시하면
   * 토큰이 아예 없는 실행(가드가 막는 경로)을 재현한다.
   */
  webSessionToken?: () => string
  /** 목소리 점검이 잰 중심 음높이 (KAN-31 4단계). 주지 않으면 곡선이 녹음에서 직접 잡는다 */
  userCurveCenterHz?: number | null
}

function renderScreen(
  fetchImpl: FetchLike,
  {
    storage,
    sessionId = 'sess-1',
    strict,
    onAnalysisReady,
    webSessionToken,
    userCurveCenterHz,
  }: RenderOptions = {},
) {
  // 브라우저 녹음 경로가 실제로 도는 대역. 앱(브리지 있음) 경로에서는 쓰이지 않는다.
  const capture = createFakeCapture()
  const view = render(
    <TestFlowScreen
      apiBase={API_BASE}
      testVersion={TEST_VERSION}
      sessionId={sessionId}
      storage={storage ?? memoryStorage()}
      onAnalysisReady={onAnalysisReady}
      webSessionToken={
        webSessionToken ?? (window.AccenturyBridge === undefined ? () => WEB_TOKEN : undefined)
      }
      capture={capture.factory}
      userCurveCenterHz={userCurveCenterHz}
      fetchImpl={fetchImpl}
    />,
    strict === true ? { wrapper: StrictMode } : undefined,
  )
  return { ...view, capture }
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
/** 브리지가 없는 실행의 동기화 지점 — 웹 녹음 패널이 서면 판정이 끝난 것이다 */
const findRecordButton = () => screen.findByRole('button', { name: '녹음' })

/** 네이티브가 녹음을 마치고 결과를 돌려주는 상황 */
function deliverResult(itemId: string) {
  act(() => {
    window.AccenturyWeb!.onItemResult!(
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
 * 브라우저 단독 실행에서 음성 문항 하나를 녹음해 올린다 (KAN-56 Stage 3) —
 * [녹음] → 발화 조각 → [정지] → [다음]. 업로드는 마지막 [다음]에서만 일어난다 (§5.7).
 */
async function recordAndSend(capture: FakeCapture) {
  fireEvent.click(screen.getByRole('button', { name: '녹음' }))
  await act(async () => {})
  await act(async () => {
    capture.emit(sineChunk(VOICE_DURATION_MS, { sampleRate: capture.sampleRate }))
  })
  fireEvent.click(screen.getByRole('button', { name: '정지' }))
  await act(async () => {})
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  await act(async () => {})
}

/**
 * 브리지가 없는 환경에서 현재 문항을 한 칸 진행시킨다 — 음성은 웹 녹음 + 업로드,
 * 어휘는 보기 선택 + [다음]이다. fireEvent를 쓰는 이유는 act로 감싸 리렌더까지 흘려보내기 위해서다.
 */
async function advance(capture: FakeCapture) {
  if (screen.queryByRole('button', { name: '녹음' }) !== null) {
    await recordAndSend(capture)
    return
  }
  answerVocabulary()
  // 어휘 제출은 비동기다(브라우저 단독 통로도 Promise) — 다음 문항이 뜨기 전에 다음 advance가
  // 돌지 않도록 microtask를 비운다
  await act(async () => {})
}

/** 브리지 없는 환경에서 10문항을 끝까지 민다 */
async function finishAllItems(capture: FakeCapture) {
  for (let i = 0; i < 10; i += 1) await advance(capture)

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
    if (url.endsWith('/recording')) return ok({ analysisJobId: 'job-web' })
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
    expect(bar).toHaveAttribute('aria-valuenow', '1')
    expect(bar).toHaveAttribute('aria-valuemax', '10')
    expect(screen.getByText('1 / 10')).toBeInTheDocument()
  })

  it('유형 뱃지가 문항 유형을 따라간다', async () => {
    const { capture } = renderScreen(okFetch())
    await findRecordButton()
    expect(screen.getByText('🎤 음성 문항')).toBeInTheDocument()

    await advance(capture)

    expect(await screen.findByText('어휘 문항 2')).toBeInTheDocument()
    expect(screen.getByText('📝 단어 문항')).toBeInTheDocument()
  })

  it('제출을 통지하면 다음 문항과 2/10이 된다', async () => {
    const { capture } = renderScreen(okFetch())
    await findRecordButton()

    await advance(capture)

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    expect(screen.getByText('2 / 10')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '문항 진행률' })).toHaveAttribute('aria-valuenow', '2')
  })

  it('마지막 문항을 제출하면 분석 대기 화면으로 넘어간다 (KAN-14)', async () => {
    const { capture } = renderScreen(okFetch())
    await findRecordButton()

    await finishAllItems(capture)

    // 진행률이 분석 기준으로 바뀌고 음성 5문항이 목록으로 선다.
    // 번호는 전체 10문항 기준이라 홀수 자리(정의가 음성·어휘를 번갈아 둔다)로 나온다
    expect(screen.getByRole('progressbar', { name: '분석 진행률' })).toBeInTheDocument()
    expect(screen.getByText('1번 문항')).toBeInTheDocument()
    expect(screen.getByText('9번 문항')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '녹음' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: '보기1' })).not.toBeInTheDocument()
  })

  it('진행 중 저장된 스냅샷으로 다시 열면 그 문항에서 이어진다', async () => {
    const storage = memoryStorage()
    const { unmount, capture } = renderScreen(okFetch(), { storage })
    await findRecordButton()
    await advance(capture)
    await advance(capture)
    unmount()

    expect(JSON.parse(storage.getItem(snapshotKey('sess-1'))!).submittedItemIds).toEqual([
      'item-1',
      'item-2',
    ])

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

  it('[녹음 화면 다시 열기]로 같은 문항 전환을 다시 요청할 수 있다 (결과 없이 돌려보내진 뒤의 복구)', async () => {
    // 네이티브가 결과 없이 돌려보내는 것(PCM 없는 제출 등)은 웹에 통지되지 않는다 — 대기 뷰의
    // 재진입 버튼이 유일한 복구 통로다. ([나가기] 이탈은 KAN-147에서 버튼째 사라졌다.)
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

  it('브리지가 없으면(브라우저 단독) 웹 녹음 패널이 그 자리를 맡는다 (KAN-56 Stage 3)', async () => {
    const { capture } = renderScreen(okFetch())
    await findRecordButton()

    // 녹음 패널과 대기 뷰는 배타적이다 — 대기 문구도, 재진입 버튼도 남으면 안 된다
    expect(screen.queryByText('잠시만요…')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '녹음 화면 다시 열기' })).not.toBeInTheDocument()

    await advance(capture)

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

describe('VOICE 문항 — 목소리 점검이 잰 중심 (KAN-31 4단계)', () => {
  /**
   * '내 억양' 레인이 실제로 그린 y좌표들 (레인 높이 100px 기준).
   *
   * 곡선 좌표는 `M x y` / `Q cx cy x y`라 숫자가 늘 (x, y) 쌍이다 — 홀수 번째가 y다.
   * 중심이 어디로 갔는지는 이 값 말고는 화면에서 볼 수 없다: 축은 그려지지 않는다.
   */
  function userCurveYs(): number[] {
    const lane = screen.getByRole('img', { name: '내 억양 곡선' })
    const fromPaths = Array.from(lane.querySelectorAll('path')).flatMap((path) => {
      const numbers = (path.getAttribute('d') ?? '').match(/-?[\d.]+/g) ?? []
      return numbers.filter((_, index) => index % 2 === 1).map(Number)
    })
    // 점이 하나뿐인 선분은 선이 아니라 점으로 그려진다. 이 정의의 가이드가 10ms짜리라
    // 사용자 창이 20ms고(가이드의 2배), 그 안에 들어오는 프레임이 하나뿐이다.
    const fromDots = Array.from(lane.querySelectorAll('circle')).map((dot) =>
      Number(dot.getAttribute('cy')),
    )
    return [...fromPaths, ...fromDots]
  }

  /** [녹음] → 발화 한 조각. 정지하지 않으므로 실시간 곡선 그대로다 */
  async function recordOnly(capture: FakeCapture) {
    fireEvent.click(screen.getByRole('button', { name: '녹음' }))
    await act(async () => {})
    await act(async () => {
      capture.emit(sineChunk(VOICE_DURATION_MS, { sampleRate: capture.sampleRate }))
    })
  }

  it('받은 중심이 곡선의 y축 중심이 된다', async () => {
    // 발화(200Hz)가 중심보다 한 옥타브(12 semitone) 위다. 레인이 담는 폭은 ±7이라
    // 곡선이 위 끝에 눌린다(y=0) — 중심이 실제로 쓰였을 때만 나오는 그림이다.
    const { capture } = renderScreen(okFetch(), { userCurveCenterHz: 100 })
    await findRecordButton()

    await recordOnly(capture)

    const ys = userCurveYs()
    expect(ys.length).toBeGreaterThan(0)
    expect(Math.max(...ys)).toBe(0)
  })

  it('중심이 없으면 이 녹음에서 잡는 폴백으로 내려간다 — 곡선이 사라지지 않는다', async () => {
    const { capture } = renderScreen(okFetch())
    await findRecordButton()

    await recordOnly(capture)

    // 중심을 이 녹음에서 잡으면 발화가 곧 중심이라 곡선이 레인 한가운데에 눕는다
    const ys = userCurveYs()
    expect(ys.length).toBeGreaterThan(0)
    expect(Math.min(...ys)).toBeGreaterThan(0)
  })
})

describe('VOICE 문항 — 브라우저 녹음 업로드 (KAN-56 Stage 3)', () => {
  it('[다음]에서 녹음이 계약대로 올라가고 다음 문항으로 넘어간다', async () => {
    const fetchImpl = okFetch()
    const { capture } = renderScreen(fetchImpl)
    await findRecordButton()

    await recordAndSend(capture)

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()

    const uploads = fetchImpl.mock.calls.filter(([url]) => String(url).endsWith('/recording'))
    expect(uploads).toHaveLength(1)
    const [url, init] = uploads[0]
    expect(url).toBe(`${API_BASE}/v0/sessions/sess-1/voice-items/item-1/recording`)
    expect(init?.method).toBe('POST')

    const headers = init?.headers as Record<string, string>
    // 토큰은 브리지가 아니라 웹 세션 통로에서 온다 (KAN-31 전까지는 App의 DEV localStorage)
    expect(headers.Authorization).toBe(`Bearer ${WEB_TOKEN}`)
    expect(headers['Idempotency-Key']).toBeTruthy()

    const body = init?.body
    expect(body).toBeInstanceOf(FormData)
    const form = body as FormData
    expect(form.get('audio')).toBeInstanceOf(File)
    const meta = JSON.parse(form.get('meta') as string)
    // 길이는 담긴 샘플 수에서 나온 값이라 우리가 흘려보낸 조각과 정확히 같다
    expect(meta.durationMs).toBe(VOICE_DURATION_MS)
    expect(Object.keys(meta.clientQuality).sort()).toEqual(['clipped', 'peak', 'rms', 'silenceRatio'])
  })

  it('업로드 전에는 [다음]을 눌러도 진행이 움직이지 않는다 — 정지만으로는 시도가 없다', async () => {
    const fetchImpl = okFetch()
    const { capture } = renderScreen(fetchImpl)
    await findRecordButton()

    fireEvent.click(screen.getByRole('button', { name: '녹음' }))
    await act(async () => {})
    await act(async () => {
      capture.emit(sineChunk(VOICE_DURATION_MS, { sampleRate: capture.sampleRate }))
    })
    fireEvent.click(screen.getByRole('button', { name: '정지' }))
    await act(async () => {})

    expect(screen.getByText('음성 문항 1')).toBeInTheDocument()
    expect(screen.getByText('1 / 10')).toBeInTheDocument()
    expect(fetchImpl.mock.calls.filter(([url]) => String(url).endsWith('/recording'))).toHaveLength(0)
  })

  it('업로드가 실패하면 문항에 남아 재시도를 받는다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async (input) => {
      const url = String(input)
      if (url.endsWith('/recording')) {
        return {
          ok: false,
          status: 503,
          headers: { get: () => null },
          json: async () => {
            throw new SyntaxError('본문이 JSON이 아니다')
          },
        } as unknown as Response
      }
      return { ok: true, status: 200, json: async () => tenItemDefinition() } as Response
    })
    const { capture } = renderScreen(fetchImpl)
    await findRecordButton()

    await recordAndSend(capture)

    // 진행은 그대로다 — 서버에 시도가 남았다는 확인 없이 다음 문항으로 넘기면 그 문항이
    // 채점에서 조용히 빠진다 (어휘 제출이 성공 후에만 진행을 미는 것과 같은 규칙)
    expect(screen.getByText('음성 문항 1')).toBeInTheDocument()
    expect(screen.getByText('녹음을 보내지 못했어요 (HTTP 503)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
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

  /*
   * KAN-31 이전에는 브리지가 없으면 답안이 서버로 나가지 않고 진행만 밀었다(개발용 통로).
   * 웹 단독 실행이 정식 경로가 된 지금 그 통로는 곧 어휘 5문항이 채점에서 통째로 빠지는
   * 길이라 지웠다 — 그 사실을 여기서 못 박는다.
   */
  it('웹 단독 실행에서도 답안이 웹 세션 토큰을 싣고 서버로 제출된다 (KAN-31)', async () => {
    const fetchImpl = okFetch()
    const { capture } = renderScreen(fetchImpl)
    await findRecordButton()
    await advance(capture) // 음성 문항 1 — 녹음 업로드

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    answerVocabulary()

    expect(await screen.findByText('음성 문항 3')).toBeInTheDocument()
    const answers = fetchImpl.mock.calls.filter(([url]) => String(url).endsWith('/answer'))
    expect(answers).toHaveLength(1)
    expect(answers[0][0]).toBe(`${API_BASE}/v0/sessions/sess-1/vocab-items/item-2/answer`)
    expect(answers[0][1]?.headers).toMatchObject({ Authorization: `Bearer ${WEB_TOKEN}` })
    expect(urls(fetchImpl).filter((url) => url.endsWith('/recording'))).toHaveLength(1)
  })
})

describe('세션 격리 — 다른 세션의 진행을 이어받지 않는다', () => {
  it('sessionId를 주면 그 세션 키에 저장한다', async () => {
    const storage = memoryStorage()
    const { capture } = renderScreen(okFetch(), { storage, sessionId: 'sess-1' })
    await findRecordButton()

    await advance(capture)

    expect(storage.getItem(snapshotKey('sess-1'))).not.toBeNull()
    expect(storage.getItem(snapshotKey())).toBeNull()
  })

  it('다른 세션의 스냅샷이 남아 있어도 처음부터 시작한다', async () => {
    const storage = memoryStorage()
    const { unmount, capture } = renderScreen(okFetch(), { storage, sessionId: 'sess-1' })
    await findRecordButton()
    await advance(capture)
    await advance(capture)
    unmount()

    renderScreen(okFetch(), { storage, sessionId: 'sess-2' })

    expect(await screen.findByText('음성 문항 1')).toBeInTheDocument()
    expect(screen.getByText('1 / 10')).toBeInTheDocument()
    // 세션 1의 기록은 지워지지 않는다 — 남의 진행을 폐기할 권리가 없다는 것이 키 분리의 이유다
    expect(storage.getItem(snapshotKey('sess-1'))).not.toBeNull()
  })
})

describe('폴링 부재 — 문항 진행 중에는 요청이 없다 (KAN-14 규칙 2항)', () => {
  /*
   * 규칙은 "요청이 하나도 없다"가 아니라 **주기 요청이 없다**는 것이다. KAN-56 이후 브라우저
   * 경로에는 음성 문항마다 업로드 POST가 하나씩 생기는데, 그것은 사용자가 [다음]을 눌러
   * 일어나는 일회성 요청이라 타이머가 도는 폴링과 성격이 다르다. 그래서 총 호출 수 대신
   * **요청의 종류**를 센다 — 그래야 나중에 진짜 폴링이 끼어들었을 때 여기서 걸린다.
   */
  it('정의 조회와 [다음]이 부른 업로드 말고는 요청이 없다', async () => {
    const fetchImpl = okFetch()
    const globalFetch = vi.fn()
    vi.stubGlobal('fetch', globalFetch)
    try {
      const { capture } = renderScreen(fetchImpl)
      await findRecordButton()

      // 마지막 한 문항을 남긴다 — 열 번째를 제출하면 분석 대기 화면이 서고, 그 화면의 폴링은
      // 여기서 재는 대상이 아니다 (KAN-31 이후 웹 단독 실행도 폴링이 실제로 돈다)
      for (let i = 0; i < 9; i += 1) await advance(capture)

      const requested = urls(fetchImpl)
      expect(requested.filter((url) => url.endsWith(`/v0/tests/${TEST_VERSION}`))).toHaveLength(1)
      // 음성 5문항 × 업로드 1건, 어휘 4문항 × 답안 1건. 둘 다 [다음]이 부른 일회성 요청이다
      expect(requested.filter((url) => url.endsWith('/recording'))).toHaveLength(5)
      expect(requested.filter((url) => url.endsWith('/answer'))).toHaveLength(4)
      expect(requested).toHaveLength(10)
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
    const view = renderScreen(
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
    const { capture } = view
    await findRecordButton()

    await finishAllItems(capture)

    expect(screen.queryByRole('button', { name: '다시 녹음' })).not.toBeInTheDocument()
  })

  /*
   * KAN-31 전까지 대기 화면의 토큰은 브리지에서만 왔고, 그래서 브라우저 단독 실행은 문항을
   * 다 밀어도 폴링 앞에서 막혔다. 이제 세 요청(업로드·답안·폴링)이 같은 토큰 읽기를 거치므로
   * 웹 단독 실행도 끝까지 간다.
   */
  it('웹 단독 실행도 웹 세션 토큰으로 폴링한다 (KAN-31)', async () => {
    const fetchImpl = waitingFetch({})
    const { capture } = renderScreen(fetchImpl, { sessionId: 'sess-1' })
    await findRecordButton()

    await finishAllItems(capture)

    const analyses = fetchImpl.mock.calls.filter(([url]) => String(url).endsWith('/analyses'))
    expect(analyses.length).toBeGreaterThan(0)
    expect(analyses[0][1]?.headers).toMatchObject({ Authorization: `Bearer ${WEB_TOKEN}` })
  })

  /*
   * 토큰이 아예 없는 실행은 여기서 재현하지 않는다. 이제 업로드·답안·폴링이 같은 토큰을 쓰므로
   * 토큰을 비우면 첫 음성 문항의 업로드부터 막혀 대기 화면까지 가지도 못한다 — 빈 토큰 가드
   * 자체는 `fetchAnalysisStatuses.test.ts`가 요청 층위에서 덮는다.
   */

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
