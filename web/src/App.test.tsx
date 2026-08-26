import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { REQUIRED_BRIDGE_VERSION } from './bridge/bridge'
import { snapshotKey } from './progress/progressSnapshot'

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

afterEach(() => {
  delete window.AccenturyBridge
  delete window.AccenturyWeb
  setSearch('')
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

  it('브리지 버전이 없으면(구버전 앱) 업데이트 안내를 렌더한다 (§5)', () => {
    setSearch('')
    render(<App />)
    expect(screen.getByText('앱 업데이트가 필요해요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '시작하기' })).not.toBeInTheDocument()
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
    stubDefinitionFetch(VOCAB_ITEM)
    const stored = stubLocalStorage()

    render(<App />)
    await answerVocabulary()

    expect([...stored.keys()]).toEqual([snapshotKey('sess-1')])
  })

  it('sessionId가 없으면 세션 없는 키로 떨어진다 (KAN-9 결선 전 과도기)', async () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0&screen=test&testVersion=gn-2026.08.1`)
    stubDefinitionFetch(VOCAB_ITEM)
    const stored = stubLocalStorage()

    render(<App />)
    await answerVocabulary()

    expect([...stored.keys()]).toEqual([snapshotKey()])
  })

  it('screen 파라미터가 없으면 기존대로 인트로다', () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0`)
    render(<App />)
    expect(screen.getByRole('button', { name: '시작하기' })).toBeInTheDocument()
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
