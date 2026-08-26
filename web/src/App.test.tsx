import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { REQUIRED_BRIDGE_VERSION } from './bridge/bridge'
import { snapshotKey } from './progress/progressSnapshot'
import { clearWebSession, loadWebSession, saveWebSession } from './session/webSession'

function setSearch(search: string) {
  window.history.replaceState(null, '', `/${search}`)
}

const VOICE_ITEM = {
  itemId: 'item-1',
  seq: 1,
  type: 'VOICE',
  prompt: '어서 오이소',
  maxDurationMs: 10_000,
  guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
}

/**
 * 어휘 문항 대역. 스냅샷 키만 보는 테스트가 이걸 쓴다 — 브라우저 단독 실행에서 한 칸 진행하는
 * 데 브라우저 API가 필요 없는 유일한 유형이기 때문이다. 음성 문항은 KAN-56 이후 웹이 직접
 * 녹음하는데, App은 캡처를 주입받지 않아(주입 지점은 테스트 전용이다) jsdom에서 마이크를
 * 열 수 없다. 이 두 테스트가 확인하는 것은 sessionId가 진행 화면까지 닿는지이고 문항 유형은
 * 그와 무관하다.
 */
const VOCAB_ITEM = {
  itemId: 'item-1',
  seq: 1,
  type: 'VOCABULARY',
  prompt: '어서 오이소',
  choices: [{ choiceId: 'c1', text: '보기1' }],
}

/** 문항 하나짜리 정의를 돌려주는 fetch 스텁. 진행 화면 분기에서만 쓴다 */
function stubDefinitionFetch(item: unknown = VOICE_ITEM) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({
        testVersion: 'gn-2026.08.1',
        scoreVersion: 'sv-0.3',
        dialect: 'GYEONGNAM',
        estimatedDurationSec: 180,
        items: [item],
      }),
    })),
  )
}

/** 어휘 문항을 한 칸 민다 — 보기를 고르고 [다음]으로 확정 */
async function answerVocabulary() {
  fireEvent.click(await screen.findByRole('radio', { name: '보기1' }))
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  // 제출은 비동기다(브라우저 단독 통로도 Promise) — 스냅샷이 쓰이기까지 microtask를 비운다
  await act(async () => {})
}

/**
 * App은 저장소를 주입받지 않고 훅 기본값(window.localStorage)을 쓴다. 이 환경의 jsdom에는
 * localStorage가 없어(진행 훅은 그 경우 조용히 메모리로만 진행한다) 스냅샷 저장을 관찰할 수
 * 없으므로, 대역을 심어 어떤 키로 나가는지 본다.
 */
function stubLocalStorage(): Map<string, string> {
  const map = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => void map.set(key, value),
    removeItem: (key: string) => void map.delete(key),
  })
  return map
}

/**
 * 토큰을 주는 브리지 대역 (앱 안 실행). 진행 화면의 어휘 제출이 실제로 서버로 나가므로
 * (KAN-31에서 개발용 통로를 지웠다) 토큰 없이는 문항이 한 칸도 밀리지 않는다.
 */
function stubBridge(token = 'bridge-token') {
  window.AccenturyBridge = {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => REQUIRED_BRIDGE_VERSION,
    getSessionToken: () => token,
  }
}

/**
 * 웹 마이크 게이트가 통과하는 환경을 만든다 (KAN-56). jsdom에는 셋 다 없어서 그냥 두면
 * 인트로 [시작하기]가 "지원 안 됨" 안내로 빠지고 세션 생성까지 가지 못한다.
 */
function stubMicrophone() {
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('AudioContext', class {})
  vi.stubGlobal('AudioWorkletNode', class {})
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia: async () => ({ getTracks: () => [] }) as unknown as MediaStream },
  })
}

afterEach(() => {
  delete window.AccenturyBridge
  delete window.AccenturyWeb
  setSearch('')
  // 웹 단독 세션은 실물 sessionStorage에 남는다 — 다음 테스트로 토큰이 새지 않게 지운다
  clearWebSession()
  delete (navigator as { mediaDevices?: unknown }).mediaDevices
  // 진행 화면 분기 테스트가 fetch·localStorage를 스텁한다. 실패로 중단돼도 다음 테스트에
  // 새지 않게 여기서 되돌린다
  vi.unstubAllGlobals()
})

describe('App — 스큐 판정 분기', () => {
  it('호환 버전이면 인트로가 뜨고 문항 구성·예상 시간이 정확히 표시된다 (AC 1)', () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0`)
    render(<App />)
    expect(screen.getByText('사투리 억양 테스트')).toBeInTheDocument()
    // KAN-148에서 한 문장이던 표기가 숫자 칸으로 갈렸다 - 확인하는 값은 그대로다
    expect(screen.getByText('10문항')).toBeInTheDocument()
    expect(screen.getByText('~3분')).toBeInTheDocument()
    expect(screen.getByText('🎤 음성 5문항')).toBeInTheDocument()
    expect(screen.getByText('📝 단어 5문항')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '시작하기' })).toBeInTheDocument()
  })

  /*
   * 구버전 앱 판정의 근거가 KAN-31에서 바뀌었다. 예전에는 "`?bridge=`가 없으면 구버전 앱"
   * 이었는데, 그 조합은 앱 없이 공유 링크를 연 브라우저와 구분되지 않는다. 지금은 **브리지
   * 객체**를 본다 — 네이티브가 페이지 스크립트보다 먼저 심어 두는 값이라 있으면 앱이 확실하다.
   */
  it('브리지 객체는 있는데 버전이 없으면(구버전 앱) 업데이트 안내를 렌더한다 (§5)', () => {
    setSearch('')
    stubBridge()
    render(<App />)
    expect(screen.getByText('앱 업데이트가 필요해요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '시작하기' })).not.toBeInTheDocument()
  })

  it('브리지 객체도 쿼리도 없으면(웹 단독 실행) 인트로가 뜬다 (KAN-31)', () => {
    // 공유 링크를 앱 없이 그대로 연 사람이 이 경로다
    setSearch('?c=kko_share')
    render(<App />)
    expect(screen.getByRole('button', { name: '시작하기' })).toBeInTheDocument()
    expect(screen.queryByText('앱 업데이트가 필요해요')).not.toBeInTheDocument()
  })
})

describe('App — 문항 진행 화면 진입 쿼리 (KAN-100: 네이티브가 권한 게이트 통과 후 여는 경로)', () => {
  it('?screen=test면 정의를 조회해 문항 진행 화면을 띄운다', async () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0&screen=test&testVersion=gn-2026.08.1`)
    stubDefinitionFetch()

    render(<App />)

    expect(await screen.findByText('어서 오이소')).toBeInTheDocument()
    expect(screen.getByText('1 / 1')).toBeInTheDocument()
  })

  it('sessionId 쿼리가 진행 화면까지 전달돼 그 세션 키에 저장된다', async () => {
    setSearch(
      `?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0&screen=test&testVersion=gn-2026.08.1&sessionId=sess-1`,
    )
    stubBridge()
    stubDefinitionFetch(VOCAB_ITEM)
    const stored = stubLocalStorage()

    render(<App />)
    await answerVocabulary()

    expect([...stored.keys()]).toEqual([snapshotKey('sess-1')])
  })

  /*
   * sessionId 없이 들어오면 어디까지 가는가. KAN-31에서 어휘 제출의 개발용 통로를 지웠기
   * 때문에 이제 답안이 실제로 나가려 하고, 세션 없는 요청은 네트워크를 타기 전에 가드가
   * 막는다 — 진행이 멈추므로 스냅샷도 남지 않는다. 세션 없는 스냅샷 키 자체는
   * `progressSnapshot.test.ts`가 덮는다.
   */
  it('sessionId가 없으면 답안이 가드에 막혀 진행이 멈춘다', async () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0&screen=test&testVersion=gn-2026.08.1`)
    stubBridge()
    stubDefinitionFetch(VOCAB_ITEM)
    const stored = stubLocalStorage()
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(<App />)
    await answerVocabulary()

    expect(screen.getByText('답안을 보낼 수 없어요. 앱을 다시 시작해 주세요')).toBeInTheDocument()
    expect([...stored.keys()]).toEqual([])
    consoleError.mockRestore()
  })

  it('screen 파라미터가 없으면 기존대로 인트로다', () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0`)
    render(<App />)
    expect(screen.getByRole('button', { name: '시작하기' })).toBeInTheDocument()
  })
})

describe('App — 웹 단독 실행 (KAN-31)', () => {
  /** §3.1 201 응답을 돌려주는 fetch 스텁 */
  function stubSessionFetch(body: Record<string, unknown> = {}) {
    const fetchStub = vi.fn(async () => ({
      ok: true,
      status: 201,
      headers: { get: () => null },
      json: async () => ({
        sessionId: 's_web',
        sessionToken: 'st_web',
        testVersion: 'gn-2026.08.1',
        scoreVersion: 'sv-0.3',
        expiresAt: '2026-08-26T03:30:00Z',
        ...body,
      }),
    }))
    vi.stubGlobal('fetch', fetchStub)
    return fetchStub
  }

  /** 인트로 [시작하기] — 웹 마이크 게이트가 비동기라 microtask를 비운다 */
  async function tapStart() {
    fireEvent.click(screen.getByRole('button', { name: '시작하기' }))
    await act(async () => {})
    await act(async () => {})
  }

  it('[시작하기]가 공유 링크의 유입 코드를 실어 세션을 만들고 문항 화면으로 넘긴다', async () => {
    setSearch('?c=kko_share')
    stubMicrophone()
    const fetchStub = stubSessionFetch()
    const navigate = vi.fn()

    render(<App navigate={navigate} />)
    await tapStart()

    const [url, init] = fetchStub.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toMatch(/\/v0\/sessions$/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toMatchObject({
      campaignToken: 'kko_share',
      client: { platform: 'WEB' },
    })

    // 진입 경로(pathname)와 나머지 쿼리는 그대로 두고 화면 지정만 얹는다
    expect(navigate).toHaveBeenCalledTimes(1)
    const next = new URLSearchParams(navigate.mock.calls[0][0].split('?')[1] ?? '')
    expect(next.get('screen')).toBe('test')
    expect(next.get('testVersion')).toBe('gn-2026.08.1')
    expect(next.get('sessionId')).toBe('s_web')
    // 유입 계측이 화면 전환 한 번에 끊기지 않는다
    expect(next.get('c')).toBe('kko_share')
  })

  it('세션 생성이 429로 막히면 화면을 옮기지 않고 대기 안내를 띄운다', async () => {
    setSearch('')
    stubMicrophone()
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 429,
        headers: { get: () => null },
        json: async () => ({
          code: 'RATE_LIMITED',
          message: '요청이 많아요. 잠시 후 다시 시도해 주세요.',
          retryable: true,
          retryAfterMs: 30_000,
        }),
      })),
    )
    const navigate = vi.fn()

    render(<App navigate={navigate} />)
    await tapStart()

    expect(screen.getByRole('alert')).toHaveTextContent('30초 후 다시 시도할 수 있어요')
    // 세션 없이 문항 화면에 들어가면 이후 요청이 전부 401로 막힌다
    expect(navigate).not.toHaveBeenCalled()
    // [시작하기]가 그대로 재시도 버튼으로 남는다
    expect(screen.getByRole('button', { name: '시작하기' })).toBeEnabled()
  })

  it('문항 화면은 저장된 웹 세션 토큰으로 답안을 제출한다 — URL에는 토큰이 없다', async () => {
    setSearch('?c=kko_share&screen=test&testVersion=gn-2026.08.1&sessionId=s_web')
    saveWebSession({
      sessionId: 's_web',
      sessionToken: 'st_web',
      testVersion: 'gn-2026.08.1',
      expiresAt: '2026-08-26T03:30:00Z',
    })
    stubDefinitionFetch(VOCAB_ITEM)

    render(<App />)
    await answerVocabulary()

    // 정의 조회(1) + 답안 제출(2)
    const [url, init] = vi.mocked(globalThis.fetch).mock.calls[1] as unknown as [string, RequestInit]
    expect(url).toMatch(/\/vocab-items\/item-1\/answer$/)
    expect(init.headers).toMatchObject({ Authorization: 'Bearer st_web' })
    expect(window.location.search).not.toContain('st_web')
  })
})

describe('App — 결과 화면 진입 쿼리 (KAN-29)', () => {
  const RESULT_SEARCH = `?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0&screen=result&sessionId=sess-1`

  /** §3.7 200 본문을 돌려주는 fetch 스텁 */
  function stubResultFetch() {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => ({
          status: 'READY',
          scores: { intonation: 78, vocabulary: 60, overall: 72 },
          tier: { code: 'HONORARY', name: '명예주민', rank: 4, of: 5 },
          comment: '억양은 거의 토박이인데 단어에서 들켰습니다.',
          share: {
            imageUrl: 'https://static.accentury.app/tier/honorary.png',
            text: '나는 명예주민! 너도 시도해볼래?',
            webTestUrl: 'https://accentury.app/t?c=kko_share',
          },
          testVersion: 'gn-2026.08.1',
          scoreVersion: 'sv-0.3',
          expiresAt: '2026-08-22T03:00:00Z',
        }),
      })),
    )
  }

  /**
   * 토큰을 주는 브리지 대역. 결과 조회는 세션 토큰이 있어야 네트워크를 탄다.
   *
   * `startRetest`는 일부러 빼 둔다 — 메서드 추가는 계약 버전을 올리지 않으므로(§5) 이
   * 조합(토큰은 주는데 재응시는 모르는 앱)이 실재한다. 재응시 결선 테스트는 따로 심는다.
   */
  function stubBridgeWithToken(token = 'token-1') {
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => 1,
      getSessionToken: () => token,
    }
  }

  /** 재응시까지 아는 브리지 대역 (KAN-34 결선 후의 정상 앱) */
  function stubBridgeWithRetest(): ReturnType<typeof vi.fn> {
    const startRetest = vi.fn()
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => 1,
      getSessionToken: () => 'token-1',
      startRetest,
    }
    return startRetest
  }

  /** `navigator.share`는 jsdom에 없다. 정의했다가 되돌리는 자리를 한 곳에 모은다 */
  function withNavigatorShare(share: (data: ShareData) => Promise<void>): () => void {
    Object.defineProperty(navigator, 'share', { configurable: true, writable: true, value: share })
    return () => {
      delete (navigator as { share?: unknown }).share
    }
  }

  it('?screen=result면 결과를 조회해 결과 화면을 띄운다', async () => {
    setSearch(RESULT_SEARCH)
    stubBridgeWithToken()
    stubResultFetch()

    render(<App />)

    expect(await screen.findByRole('heading', { name: '명예주민' })).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '억양 점수' })).toHaveValue(78)
    expect(screen.getByRole('progressbar', { name: '단어 점수' })).toHaveValue(60)
  })

  it('세션 토큰은 쿼리가 아니라 브리지에서 읽는다 — URL에 토큰이 남지 않는다', async () => {
    setSearch(RESULT_SEARCH)
    stubBridgeWithToken('bridge-token')
    stubResultFetch()

    render(<App />)

    await screen.findByRole('heading', { name: '명예주민' })
    const [url, init] = vi.mocked(globalThis.fetch).mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toContain('/v0/sessions/sess-1/result')
    expect(url).not.toContain('bridge-token')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer bridge-token' })
  })

  it('브리지가 없으면(브라우저 단독) 네트워크를 타지 않고 안내를 띄운다', async () => {
    setSearch(RESULT_SEARCH)
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const fetchStub = vi.fn()
    vi.stubGlobal('fetch', fetchStub)

    render(<App />)

    expect(await screen.findByText('결과를 불러오지 못했어요')).toBeInTheDocument()
    expect(fetchStub).not.toHaveBeenCalled()
    consoleError.mockRestore()
  })

  it('[친구에게 공유하기]는 점수 없이 등급 문구와 캠페인 URL만 넘긴다', async () => {
    setSearch(RESULT_SEARCH)
    stubBridgeWithToken()
    stubResultFetch()
    const share = vi.fn(async () => {})
    const restore = withNavigatorShare(share)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '친구에게 공유하기' }))

    expect(share).toHaveBeenCalledWith({
      text: '나는 명예주민! 너도 시도해볼래?',
      url: 'https://accentury.app/t?c=kko_share',
    })
    // 개인 결과가 공유 payload로 새어 나가지 않는다 (KAN-30 요구)
    expect(JSON.stringify(share.mock.calls[0])).not.toContain('78')
    restore()
  })

  it('공유 시트가 없는 환경(개발 http)에서도 화면이 깨지지 않는다', async () => {
    setSearch(RESULT_SEARCH)
    stubBridgeWithToken()
    stubResultFetch()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '친구에게 공유하기' }))

    expect(warn).toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: '명예주민' })).toBeInTheDocument()
    warn.mockRestore()
  })

  it('[다시 테스트하기]는 브리지가 없으면 폴백으로 화면 지정만 걷고 bridge·app은 남긴다', async () => {
    setSearch(RESULT_SEARCH)
    stubBridgeWithToken()
    stubResultFetch()

    // jsdom은 location.href 대입으로 이동하지 않고 "Not implemented" 경고만 낸다.
    // 어디로 보내려 했는지가 확인 대상이라 setter를 가로채고, 끝나면 원래대로 되돌린다.
    const original = Object.getOwnPropertyDescriptor(window, 'location')!
    const assigned: string[] = []
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { pathname: '/', search: RESULT_SEARCH, set href(value: string) { assigned.push(value) } },
    })

    try {
      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))
    } finally {
      Object.defineProperty(window, 'location', original)
    }

    expect(assigned).toHaveLength(1)
    const next = new URLSearchParams(assigned[0].split('?')[1] ?? '')
    expect(next.get('bridge')).toBe(String(REQUIRED_BRIDGE_VERSION))
    expect(next.get('app')).toBe('1.0')
    expect(next.get('screen')).toBeNull()
    expect(next.get('sessionId')).toBeNull()
  })

  /**
   * 재응시 결선 (KAN-34 3단계). 여기서 확인하는 것은 왕복 자체가 아니라 **결선**이다 —
   * 결과 화면의 버튼이 브리지에 닿는가, 회신이 화면까지 내려오는가. 상태 전이의 갈래는
   * `useRetest.test.ts`가 덮는다.
   */
  describe('[다시 테스트하기] 재응시 결선', () => {
    /** 네이티브가 실패를 회신하는 자리. 실제 경로와 같게 JSON 문자열로 넣는다 */
    function deliverFailure(payload: Record<string, unknown>) {
      act(() => {
        window.AccenturyWeb?.onRetestFailed?.(JSON.stringify(payload))
      })
    }

    it('탭하면 네이티브 재응시가 호출되고 버튼이 준비 중으로 잠긴다', async () => {
      setSearch(RESULT_SEARCH)
      const startRetest = stubBridgeWithRetest()
      stubResultFetch()

      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))

      expect(startRetest).toHaveBeenCalledTimes(1)
      // 성공하면 네이티브가 페이지를 통째로 갈아치운다 — 웹이 스스로 이동하지 않는다
      expect(screen.getByRole('button', { name: '준비 중…' })).toBeDisabled()
    })

    it('잠긴 버튼을 다시 눌러도 두 번째 요청이 나가지 않는다', async () => {
      setSearch(RESULT_SEARCH)
      const startRetest = stubBridgeWithRetest()
      stubResultFetch()

      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))
      fireEvent.click(screen.getByRole('button', { name: '준비 중…' }))

      expect(startRetest).toHaveBeenCalledTimes(1)
    })

    it('실패 회신이 오면 네이티브 문구가 뜨고 버튼이 다시 열린다', async () => {
      setSearch(RESULT_SEARCH)
      const startRetest = stubBridgeWithRetest()
      stubResultFetch()

      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))
      deliverFailure({
        code: 'INTERNAL_ERROR',
        message: '다시 시작하지 못했어요. 잠시 후 다시 시도해 주세요.',
        retryable: true,
        retryAfterMs: null,
      })

      expect(screen.getByText('다시 시작하지 못했어요. 잠시 후 다시 시도해 주세요.')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '다시 테스트하기' }))
      expect(startRetest).toHaveBeenCalledTimes(2)
    })

    it('429 회신이면 남은 대기 시간을 적고 버튼을 잠근 채 둔다', async () => {
      setSearch(RESULT_SEARCH)
      stubBridgeWithRetest()
      stubResultFetch()

      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))
      deliverFailure({
        code: 'RATE_LIMITED',
        message: '요청이 많아요. 잠시 후 다시 시도해 주세요.',
        retryable: true,
        retryAfterMs: 30_000,
      })

      expect(screen.getByText('30초 후 다시 시도할 수 있어요')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '다시 테스트하기' })).toBeDisabled()
    })

    it('다시 눌러도 소용없는 실패면 버튼이 잠긴 채로 남는다', async () => {
      setSearch(RESULT_SEARCH)
      stubBridgeWithRetest()
      stubResultFetch()

      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))
      deliverFailure({
        code: 'FORBIDDEN',
        message: '지금은 테스트를 시작할 수 없어요.',
        retryable: false,
        retryAfterMs: null,
      })

      expect(screen.getByText('지금은 테스트를 시작할 수 없어요.')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '다시 테스트하기' })).toBeDisabled()
    })

    it('만료(410) 화면의 버튼도 같은 재응시 경로를 탄다 (KAN-29 재응시 유도)', async () => {
      setSearch(RESULT_SEARCH)
      const startRetest = stubBridgeWithRetest()
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => ({
          ok: false,
          status: 410,
          json: async () => ({
            code: 'RESULT_EXPIRED',
            message: '결과 보관 기간(24시간)이 지났습니다.',
            retryable: false,
            retryAfterMs: null,
            correlationId: 'c_test',
          }),
        })),
      )

      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))

      expect(startRetest).toHaveBeenCalledTimes(1)
      expect(screen.getByRole('button', { name: '준비 중…' })).toBeDisabled()
    })

    it('결과 화면을 떠나면 수신 지점이 설치 전으로 되돌아간다', async () => {
      setSearch(RESULT_SEARCH)
      stubBridgeWithRetest()
      stubResultFetch()

      const { unmount } = render(<App />)
      await screen.findByRole('heading', { name: '명예주민' })
      expect(typeof window.AccenturyWeb?.onRetestFailed).toBe('function')

      unmount()
      expect(window.AccenturyWeb).toBeUndefined()
    })
  })
})

describe('IntroScreen — [시작하기] 결선', () => {
  it('탭하면 네이티브 권한 게이트 브리지를 호출한다 (AC 3)', () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0`)
    const fn = vi.fn()
    window.AccenturyBridge = {
      requestMicPermission: fn,
      startVoiceItem: vi.fn(),
      getContractVersion: () => 1,
    }
    render(<App />)
    screen.getByRole('button', { name: '시작하기' }).click()
    expect(fn).toHaveBeenCalledTimes(1)
  })
})

describe('App — 웹 단독 결과 화면 (KAN-31 2단계)', () => {
  const ANDROID_UA =
    'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36'
  const IPHONE_UA =
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'

  /** 공유 링크로 들어와 브라우저에서 응시를 끝낸 사람의 URL — 브리지도 `?bridge=`도 없다 */
  const WEB_RESULT_SEARCH = '?c=kko_x&screen=result&sessionId=s_web'

  const WEB_SESSION = {
    sessionId: 's_web',
    sessionToken: 'st_web',
    testVersion: 'gn-2026.08.1',
    expiresAt: '2026-08-26T03:30:00Z',
  }

  /** jsdom의 userAgent는 읽기 전용 getter라 정의로 덮는다. 되돌리는 함수를 돌려준다 */
  function withUserAgent(userAgent: string): () => void {
    const original = Object.getOwnPropertyDescriptor(Navigator.prototype, 'userAgent')
    Object.defineProperty(navigator, 'userAgent', { configurable: true, value: userAgent })
    return () => {
      delete (navigator as { userAgent?: unknown }).userAgent
      if (original !== undefined) Object.defineProperty(Navigator.prototype, 'userAgent', original)
    }
  }

  /** §3.7 200 본문을 돌려주는 fetch 스텁 */
  function stubResultFetch() {
    const fetchStub = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({
        status: 'READY',
        scores: { intonation: 78, vocabulary: 60, overall: 72 },
        tier: { code: 'HONORARY', name: '명예주민', rank: 4, of: 5 },
        comment: '억양은 거의 토박이인데 단어에서 들켰습니다.',
        share: {
          imageUrl: 'https://static.accentury.app/tier/honorary.png',
          text: '나는 명예주민! 너도 시도해볼래?',
          webTestUrl: 'https://accentury.app/t?c=kko_share',
        },
        testVersion: 'gn-2026.08.1',
        scoreVersion: 'sv-0.3',
        expiresAt: '2026-08-22T03:00:00Z',
      }),
    }))
    vi.stubGlobal('fetch', fetchStub)
    return fetchStub
  }

  it('결과를 브리지가 아니라 저장된 웹 세션 토큰으로 조회한다 — URL에는 토큰이 없다', async () => {
    setSearch(WEB_RESULT_SEARCH)
    saveWebSession(WEB_SESSION)
    const fetchStub = stubResultFetch()

    render(<App />)

    await screen.findByRole('heading', { name: '명예주민' })
    const [url, init] = fetchStub.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toContain('/v0/sessions/s_web/result')
    expect(url).not.toContain('st_web')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer st_web' })
    expect(window.location.search).not.toContain('st_web')
  })

  it('안드로이드 브라우저에서는 [앱 다운로드]가 플레이스토어를 가리킨다', async () => {
    const restoreUa = withUserAgent(ANDROID_UA)
    setSearch(WEB_RESULT_SEARCH)
    saveWebSession(WEB_SESSION)
    stubResultFetch()

    try {
      render(<App />)

      const download = await screen.findByRole('link', { name: '앱 다운로드' })
      expect(download).toHaveAttribute('href', expect.stringContaining('play.google.com'))
      expect(screen.getByText('Play 스토어로 이동해요')).toBeInTheDocument()
    } finally {
      restoreUa()
    }
  })

  it('아이폰 브라우저에서는 앱스토어를 가리킨다', async () => {
    const restoreUa = withUserAgent(IPHONE_UA)
    setSearch(WEB_RESULT_SEARCH)
    saveWebSession(WEB_SESSION)
    stubResultFetch()

    try {
      render(<App />)

      expect(await screen.findByRole('link', { name: '앱 다운로드' })).toHaveAttribute(
        'href',
        expect.stringContaining('apps.apple.com'),
      )
    } finally {
      restoreUa()
    }
  })

  it('앱 안 결과 화면에는 다운로드 CTA가 없다 — 실행 판정으로만 갈린다', async () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0&screen=result&sessionId=sess-1`)
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => REQUIRED_BRIDGE_VERSION,
      getSessionToken: () => 'token-1',
    }
    stubResultFetch()

    render(<App />)

    await screen.findByRole('heading', { name: '명예주민' })
    expect(screen.queryByRole('link', { name: '앱 다운로드' })).not.toBeInTheDocument()
  })

  it('[다시 테스트하기]는 유입 코드를 그대로 든 인트로로 되돌리고 세션은 남긴다', async () => {
    setSearch(WEB_RESULT_SEARCH)
    saveWebSession(WEB_SESSION)
    stubResultFetch()
    const navigate = vi.fn()

    render(<App navigate={navigate} />)
    fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))

    // 브리지가 없으므로 네이티브 왕복 없이 곧바로 인트로 복귀다 — 잠금 UI가 뜨지 않는다
    expect(screen.queryByRole('button', { name: '준비 중…' })).not.toBeInTheDocument()

    expect(navigate).toHaveBeenCalledTimes(1)
    const next = new URLSearchParams(navigate.mock.calls[0][0].split('?')[1] ?? '')
    // 유입 계측이 재응시 한 번에 끊기지 않는다
    expect(next.get('c')).toBe('kko_x')
    expect(next.get('screen')).toBeNull()
    expect(next.get('sessionId')).toBeNull()

    /*
     * 세션을 지우지 않는다. 이 토큰이 다음 [시작하기]의 Bearer로 나가야 서버가 옛 세션을
     * 폐기한다 (§3.1) — 여기서 지우면 보낼 토큰이 없어 옛 세션이 고아로 남는다.
     */
    expect(loadWebSession()?.sessionToken).toBe('st_web')
  })

  it('만료된 결과에서도 다운로드와 재응시를 함께 준다', async () => {
    const restoreUa = withUserAgent(ANDROID_UA)
    setSearch(WEB_RESULT_SEARCH)
    saveWebSession(WEB_SESSION)
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 410,
        json: async () => ({
          code: 'RESULT_EXPIRED',
          message: '결과 보관 기간(24시간)이 지났습니다.',
          retryable: false,
          retryAfterMs: null,
          correlationId: 'c_test',
        }),
      })),
    )

    try {
      render(<App />)

      await screen.findByText('결과 보관 기간이 지났어요')
      expect(screen.getByRole('link', { name: '앱 다운로드' })).toHaveAttribute(
        'href',
        expect.stringContaining('play.google.com'),
      )
      expect(screen.getByRole('button', { name: '다시 테스트하기' })).toBeInTheDocument()
    } finally {
      restoreUa()
    }
  })
})
