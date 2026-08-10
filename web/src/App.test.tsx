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

describe('IntroScreen — [시작하기] 결선', () => {
  it('탭하면 네이티브 권한 게이트 브리지를 호출한다 (AC 3)', () => {
    setSearch(`?bridge=${REQUIRED_BRIDGE_VERSION}&app=1.0`)
    const fn = vi.fn()
    window.AccenturyBridge = { requestMicPermission: fn, getContractVersion: () => 1 }
    render(<App />)
    screen.getByRole('button', { name: '시작하기' }).click()
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
