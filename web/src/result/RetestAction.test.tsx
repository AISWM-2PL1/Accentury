import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { REQUIRED_BRIDGE_VERSION, type AccenturyBridge } from '../bridge/bridge'
import { RetestAction } from './RetestAction'
import type { RetestControl } from './useRetest'

afterEach(() => {
  delete window.AccenturyBridge
})

function retestControl(overrides: Partial<RetestControl> = {}): RetestControl {
  return {
    onRetest: vi.fn(),
    disabled: false,
    pending: false,
    message: null,
    retryAfterSec: 0,
    ...overrides,
  }
}

function bridge(overrides: Partial<AccenturyBridge> = {}): AccenturyBridge {
  return {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => REQUIRED_BRIDGE_VERSION,
    startRetest: vi.fn(),
    ...overrides,
  }
}

/*
 * 라벨 규칙만 본다 (KAN-196). 잠금·실패 문구·대기 초는 결과 화면과 대기 화면 테스트가
 * 이미 두 자리에서 덮는다 — 여기서 되풀이하면 같은 검사가 세 벌이 된다.
 */
describe('RetestAction — 버튼 라벨 (KAN-196)', () => {
  it.each(['granted', 'denied', 'unknown'])(
    '광고를 아는 앱(getAdConsent → %s)에서는 광고를 본다고 미리 말한다',
    (consent) => {
      window.AccenturyBridge = bridge({ getAdConsent: () => consent })

      render(<RetestAction retest={retestControl()} />)

      expect(screen.getByRole('button', { name: '광고 보고 다시 테스트하기' })).toBeInTheDocument()
    },
  )

  it('브라우저 단독 실행에는 광고가 없으니 예전 라벨 그대로다', () => {
    render(<RetestAction retest={retestControl()} />)

    expect(screen.getByRole('button', { name: '다시 테스트하기' })).toBeInTheDocument()
  })

  it('광고 동의를 모르는 구버전 앱도 예전 라벨이다 — startRetest가 있어도 광고의 신호가 아니다', () => {
    window.AccenturyBridge = bridge() // getAdConsent 없음, startRetest 있음

    render(<RetestAction retest={retestControl()} />)

    expect(screen.getByRole('button', { name: '다시 테스트하기' })).toBeInTheDocument()
  })

  it('진행 중이면 라벨과 무관하게 「준비 중…」이다', () => {
    window.AccenturyBridge = bridge({ getAdConsent: () => 'granted' })

    render(<RetestAction retest={retestControl({ pending: true, disabled: true })} />)

    expect(screen.getByRole('button', { name: '준비 중…' })).toBeDisabled()
  })

  it('광고를 중간에 닫은 회신(AD_DISMISSED)의 문구를 네이티브 것 그대로 그린다', () => {
    window.AccenturyBridge = bridge({ getAdConsent: () => 'granted' })

    render(
      <RetestAction
        retest={retestControl({ message: '광고를 끝까지 보시면 다시 테스트할 수 있어요' })}
      />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('광고를 끝까지 보시면 다시 테스트할 수 있어요')
    // retryable이라 버튼은 열려 있다 — 다시 누르면 광고부터 다시 본다
    expect(screen.getByRole('button', { name: '광고 보고 다시 테스트하기' })).toBeEnabled()
  })
})
