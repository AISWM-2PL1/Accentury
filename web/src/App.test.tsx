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
    expect(screen.getByText('🎤 음성 5문항 + 📝 단어 5문항 (총 10문항)')).toBeInTheDocument()
    expect(screen.getByText('예상 소요 시간 약 3분')).toBeInTheDocument()
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
    expect(screen.getByText('1/1')).toBeInTheDocument()
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
