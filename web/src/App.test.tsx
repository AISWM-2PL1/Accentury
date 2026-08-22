import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { REQUIRED_BRIDGE_VERSION } from './bridge/bridge'
import { snapshotKey } from './progress/progressSnapshot'

function setSearch(search: string) {
  window.history.replaceState(null, '', `/${search}`)
}

/** 문항 하나짜리 정의를 돌려주는 fetch 스텁. 진행 화면 분기에서만 쓴다 */
function stubDefinitionFetch() {
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
        items: [
          {
            itemId: 'item-1',
            seq: 1,
            type: 'VOICE',
            prompt: '어서 오이소',
            maxDurationMs: 10_000,
            guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
          },
        ],
      }),
    })),
  )
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
    stubDefinitionFetch()
    const stored = stubLocalStorage()

    render(<App />)
    // 브리지가 없는 환경이라 음성 문항은 개발용 제출 버튼으로 진행한다.
    // 문항 문구가 아니라 버튼을 기다리는 이유: 정의 로딩과 브리지 판정은 서로 다른 커밋이다
    fireEvent.click(await screen.findByRole('button', { name: '제출 (개발용)' }))

    expect([...stored.keys()]).toEqual([snapshotKey('sess-1')])
  })

  it('sessionId가 없으면 세션 없는 키로 떨어진다 (KAN-9 결선 전 과도기)', async () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0&screen=test&testVersion=gn-2026.08.1`)
    stubDefinitionFetch()
    const stored = stubLocalStorage()

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '제출 (개발용)' }))

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

  /** 토큰을 주는 브리지 대역. 결과 조회는 세션 토큰이 있어야 네트워크를 탄다 */
  function stubBridgeWithToken(token = 'token-1') {
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => 1,
      getSessionToken: () => token,
    }
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

  it('[다시 테스트하기]는 화면 지정만 걷고 bridge·app은 남긴다', async () => {
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
