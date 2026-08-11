import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { REQUIRED_BRIDGE_VERSION } from './bridge/bridge'

function setSearch(search: string) {
  window.history.replaceState(null, '', `/${search}`)
}

afterEach(() => {
  delete window.AccenturyBridge
  setSearch('')
  // 진행 화면 분기 테스트가 fetch를 스텁한다. 실패로 중단돼도 다음 테스트에 새지 않게 여기서 되돌린다
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

describe('App — 문항 진행 화면 개발용 통로 (KAN-99, 정식 결선은 KAN-100)', () => {
  it('?screen=test면 정의를 조회해 문항 진행 화면을 띄운다', async () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0&screen=test&testVersion=gn-2026.08.1`)
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

    render(<App />)

    expect(await screen.findByText('어서 오이소')).toBeInTheDocument()
    expect(screen.getByText('1/1')).toBeInTheDocument()
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
