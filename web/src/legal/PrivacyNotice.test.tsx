import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AccenturyBridge } from '../bridge/bridge'
import { PrivacyNotice } from './PrivacyNotice'
import { DEFAULT_PRIVACY_POLICY_URL } from './privacyPolicy'

afterEach(() => {
  delete window.AccenturyBridge
})

/** 계약을 갖춘 브리지 대역. `bridge.test.ts`의 것과 같은 규칙이다 */
function fakeBridge(overrides: Partial<AccenturyBridge> = {}): AccenturyBridge {
  return {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => 1,
    ...overrides,
  }
}

function link() {
  return screen.getByRole('link', { name: '개인정보처리방침' })
}

describe('PrivacyNotice — 인트로 고지 한 줄 (KAN-177)', () => {
  it('정책 문서로 가는 링크를 건다', () => {
    render(<PrivacyNotice />)

    expect(link()).toHaveAttribute('href', DEFAULT_PRIVACY_POLICY_URL)
  })

  it('음성을 어떻게 다루는지 같은 줄에서 알린다', () => {
    render(<PrivacyNotice />)

    // 문구가 아니라 사실을 붙든다 — 권한 요청 직전에 "지운다"는 말이 있어야 고지가 된다
    expect(screen.getByText(/분석이 끝나면 바로 지워요/)).toBeInTheDocument()
  })

  it('브라우저 단독 실행에서는 새 탭으로 연다 — 응시하려던 화면을 정책 문서로 덮지 않는다', () => {
    render(<PrivacyNotice />)

    expect(link()).toHaveAttribute('target', '_blank')
    // `noopener`가 빠지면 열린 문서가 `window.opener`로 이 페이지를 조작할 수 있다
    expect(link()).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('앱 안에서는 네이티브에 넘기고 링크의 기본 동작을 막는다', () => {
    const open = vi.fn()
    window.AccenturyBridge = fakeBridge({ openExternalUrl: open })

    render(<PrivacyNotice />)
    /*
     * 기본 동작을 막았는지는 이벤트의 `defaultPrevented`로 본다. jsdom은 `<a>`를 눌러도
     * 실제로 이동하지 않아서 "이동하지 않았다"로는 확인할 수 없다.
     */
    const prevented = !fireEvent.click(link())

    expect(open).toHaveBeenCalledWith(DEFAULT_PRIVACY_POLICY_URL)
    expect(prevented).toBe(true)
  })

  it('브리지가 없으면 기본 동작을 막지 않는다 — 브라우저가 알아서 연다', () => {
    render(<PrivacyNotice />)

    expect(fireEvent.click(link())).toBe(true)
  })
})

describe('PrivacyNotice — 「맞춤형 광고 설정」 (KAN-196)', () => {
  it('핸들러를 받으면 방침 링크 옆에 버튼으로 그리고, 누르면 그 핸들러가 불린다', () => {
    const onAdConsentSettings = vi.fn()
    render(<PrivacyNotice onAdConsentSettings={onAdConsentSettings} />)

    // 링크가 아니라 버튼이다 — 바깥으로 나가는 이동이 아니라 앱 안에서 시트를 여는 동작이다
    const button = screen.getByRole('button', { name: '맞춤형 광고 설정' })
    expect(screen.queryByRole('link', { name: '맞춤형 광고 설정' })).not.toBeInTheDocument()
    fireEvent.click(button)

    expect(onAdConsentSettings).toHaveBeenCalledTimes(1)
    // 방침 링크는 그대로 있다
    expect(link()).toBeInTheDocument()
  })

  it('핸들러가 없으면 그리지 않는다 — 광고 동의가 없는 실행에 죽은 링크를 두지 않는다', () => {
    render(<PrivacyNotice />)

    expect(screen.queryByRole('button', { name: '맞춤형 광고 설정' })).not.toBeInTheDocument()
  })
})
