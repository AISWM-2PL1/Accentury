import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AD_CONSENT_ALLOW, AD_CONSENT_DENY, AD_CONSENT_SETTINGS_LINK, AD_CONSENT_TITLE } from '../ads/adConsentText'
import type { MicPermission } from '../audio/microphone'
import { REQUIRED_BRIDGE_VERSION, type AccenturyBridge } from '../bridge/bridge'
import { IntroScreen } from './IntroScreen'

const ANDROID_UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36'
const IPHONE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'

/** jsdom의 userAgent는 읽기 전용 getter라 정의로 덮는다. 되돌리는 함수를 돌려준다 */
function withUserAgent(userAgent: string): () => void {
  const original = Object.getOwnPropertyDescriptor(Navigator.prototype, 'userAgent')
  Object.defineProperty(navigator, 'userAgent', { configurable: true, value: userAgent })
  return () => {
    delete (navigator as { userAgent?: unknown }).userAgent
    if (original !== undefined) Object.defineProperty(Navigator.prototype, 'userAgent', original)
  }
}

/** 권한 결과를 순서대로 돌려주는 대역. 마지막 값은 계속 반복한다 */
function permissionStub(...results: MicPermission[]) {
  let call = 0
  return vi.fn(async () => results[Math.min(call++, results.length - 1)])
}

/**
 * fireEvent를 쓰는 이유: 이 클릭의 **동기적 결과**(버튼이 잠기는 것)를 바로 확인하는
 * 테스트가 있는데, DOM의 `.click()`은 act 밖이라 리액트가 그 갱신을 미룬다.
 */
function clickStart() {
  fireEvent.click(screen.getByRole('button', { name: '내 억양 테스트하기' }))
}

afterEach(() => {
  delete window.AccenturyBridge
})

describe('IntroScreen — 마이크 게이트 (KAN-56)', () => {
  it('앱 안에서는 네이티브 게이트만 부르고 웹 권한 요청은 하지 않는다', () => {
    const requestMicPermission = vi.fn()
    window.AccenturyBridge = {
      requestMicPermission,
      startVoiceItem: vi.fn(),
      getContractVersion: () => 1,
    }
    const requestWebPermission = permissionStub('granted')

    render(<IntroScreen requestWebPermission={requestWebPermission} />)
    clickStart()

    expect(requestMicPermission).toHaveBeenCalledTimes(1)
    // 두 경로가 겹치면 권한 대화상자가 두 번 뜬다
    expect(requestWebPermission).not.toHaveBeenCalled()
  })

  it('브리지가 없으면 웹 권한을 받고 통과 시 다음 단계로 넘긴다', async () => {
    const onWebStart = vi.fn()
    render(
      <IntroScreen requestWebPermission={permissionStub('granted')} onWebStart={onWebStart} />,
    )

    clickStart()
    // 프롬프트가 떠 있는 동안 버튼이 잠긴다
    expect(screen.getByRole('button', { name: '마이크 확인 중…' })).toBeDisabled()

    await waitFor(() => expect(onWebStart).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: '내 억양 테스트하기' })).toBeEnabled()
  })

  it('권한이 거부되면 안내 화면으로 갈아치우고 스토어 링크를 준다', async () => {
    const restoreUa = withUserAgent(ANDROID_UA)
    const onWebStart = vi.fn()
    render(<IntroScreen requestWebPermission={permissionStub('denied')} onWebStart={onWebStart} />)

    clickStart()

    expect(await screen.findByText('마이크 권한이 필요해요')).toBeInTheDocument()
    // 권한 없이는 테스트를 시작할 수 없다 (§5.6) — 인트로가 남아 있으면 안 된다
    expect(screen.queryByRole('button', { name: '내 억양 테스트하기' })).not.toBeInTheDocument()
    expect(onWebStart).not.toHaveBeenCalled()

    const storeLink = screen.getByRole('link', { name: '앱으로 테스트하기' })
    expect(storeLink).toHaveAttribute('href', expect.stringContaining('play.google.com'))
    restoreUa()
  })

  it('아이폰에서는 앱스토어로 보낸다', async () => {
    const restoreUa = withUserAgent(IPHONE_UA)
    render(<IntroScreen requestWebPermission={permissionStub('unavailable')} />)

    clickStart()

    expect(await screen.findByText('마이크를 사용할 수 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '앱으로 테스트하기' })).toHaveAttribute(
      'href',
      expect.stringContaining('apps.apple.com'),
    )
    restoreUa()
  })

  it('지원되지 않는 브라우저에는 재시도를 주지 않는다', async () => {
    render(<IntroScreen requestWebPermission={permissionStub('unsupported')} />)

    clickStart()

    expect(await screen.findByText('이 브라우저에서는 녹음을 지원하지 않아요')).toBeInTheDocument()
    // 눌러도 같은 화면으로 돌아올 뿐이다
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('[다시 시도]는 권한을 다시 요청하고, 허용되면 다음 단계로 넘어간다', async () => {
    const requestWebPermission = permissionStub('denied', 'granted')
    const onWebStart = vi.fn()
    render(<IntroScreen requestWebPermission={requestWebPermission} onWebStart={onWebStart} />)

    clickStart()
    const retry = await screen.findByRole('button', { name: '다시 시도' })
    fireEvent.click(retry)

    await waitFor(() => expect(onWebStart).toHaveBeenCalledTimes(1))
    expect(requestWebPermission).toHaveBeenCalledTimes(2)
    // 통과했으므로 안내 화면이 걷힌다
    expect(screen.getByRole('button', { name: '내 억양 테스트하기' })).toBeInTheDocument()
  })
})

describe('IntroScreen — 인트로 히어로', () => {
  it('큰 제목만 이 화면의 h1이고 설명은 부제와 프롬프트로 남는다', () => {
    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    // 화면 이름을 말하는 것이 히어로뿐이라 장식으로 두면 인트로가 접근 가능한 이름을 잃는다
    expect(screen.getByRole('heading', { level: 1, name: '사투리 좀 치나?' })).toBeInTheDocument()
    expect(screen.getByText('내 목소리로 확인하는 사투리 억양')).toBeInTheDocument()
    expect(screen.getByText('사투리 좀 치는지, 지금 확인해봐요.')).toBeInTheDocument()
    // 제목 자리를 넘겨받은 것이지 하나 더 생긴 것이 아니다 — h1은 여전히 하나다
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })
})

describe('IntroScreen — 맞춤형 광고 동의 (KAN-196)', () => {
  /** 광고 동의를 아는 앱의 브리지 대역. 읽기 값은 인자, 쓰기는 기록만 한다 */
  function adBridge(consent: string, setAdConsent = vi.fn()): AccenturyBridge {
    return {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => REQUIRED_BRIDGE_VERSION,
      getAdConsent: () => consent,
      setAdConsent,
    }
  }

  const dialog = () => screen.queryByRole('dialog', { name: AD_CONSENT_TITLE })
  const settingsLink = () => screen.queryByRole('button', { name: AD_CONSENT_SETTINGS_LINK })

  it('아직 묻지 않았으면(unknown) 시트를 띄운 채 시작한다', () => {
    window.AccenturyBridge = adBridge('unknown')

    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    expect(dialog()).toBeInTheDocument()
    // 인트로는 그 아래 그대로 있다 — 시트가 화면을 갈아치우는 것이 아니다
    expect(screen.getByRole('button', { name: '내 억양 테스트하기' })).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it.each(['granted', 'denied'] as const)('이미 골랐으면(%s) 시트가 뜨지 않고 링크만 있다', (consent) => {
    window.AccenturyBridge = adBridge(consent)

    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    expect(dialog()).not.toBeInTheDocument()
    expect(settingsLink()).toBeInTheDocument()
  })

  it('광고 동의를 모르는 실행(브리지 없음)에는 시트도 링크도 없다 — 웹 단독은 KAN-197 범위다', () => {
    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    expect(dialog()).not.toBeInTheDocument()
    expect(settingsLink()).not.toBeInTheDocument()
  })

  it('메서드를 모르는 구버전 앱에도 시트도 링크도 없다', () => {
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      startVoiceItem: vi.fn(),
      getContractVersion: () => REQUIRED_BRIDGE_VERSION,
    }

    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    expect(dialog()).not.toBeInTheDocument()
    expect(settingsLink()).not.toBeInTheDocument()
  })

  it('[맞춤형 광고 허용]은 granted를 네이티브에 쓰고 시트를 닫는다', () => {
    const setAdConsent = vi.fn()
    window.AccenturyBridge = adBridge('unknown', setAdConsent)
    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    fireEvent.click(screen.getByRole('button', { name: AD_CONSENT_ALLOW }))

    expect(setAdConsent).toHaveBeenCalledWith('granted')
    expect(dialog()).not.toBeInTheDocument()
    // 고른 뒤에는 바꿀 길이 남는다
    expect(settingsLink()).toBeInTheDocument()
  })

  it('[일반 광고만 보기]는 denied를 쓰고 시트를 닫는다', () => {
    const setAdConsent = vi.fn()
    window.AccenturyBridge = adBridge('unknown', setAdConsent)
    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    fireEvent.click(screen.getByRole('button', { name: AD_CONSENT_DENY }))

    expect(setAdConsent).toHaveBeenCalledWith('denied')
    expect(dialog()).not.toBeInTheDocument()
  })

  it('「맞춤형 광고 설정」은 시트를 지금 상태와 함께 다시 연다', () => {
    const setAdConsent = vi.fn()
    window.AccenturyBridge = adBridge('denied', setAdConsent)
    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    fireEvent.click(settingsLink()!)

    expect(dialog()).toBeInTheDocument()
    expect(screen.getByText('지금은 일반 광고만 보는 상태예요.')).toBeInTheDocument()

    // 바꾸면 사본도 따라간다 — 다시 열었을 때 새 상태를 말한다
    fireEvent.click(screen.getByRole('button', { name: AD_CONSENT_ALLOW }))
    expect(setAdConsent).toHaveBeenCalledWith('granted')
    expect(dialog()).not.toBeInTheDocument()
    fireEvent.click(settingsLink()!)
    expect(screen.getByText('지금은 맞춤형 광고를 허용한 상태예요.')).toBeInTheDocument()
  })

  it('시트가 떠 있는 동안 고른 뒤에야 [시작하기]가 네이티브 게이트로 간다', () => {
    const requestMicPermission = vi.fn()
    window.AccenturyBridge = { ...adBridge('unknown'), requestMicPermission }
    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    // 시트는 막으로 손을 막는 것이지 버튼을 잠그는 것이 아니다 — 잠갔다면 스모크 구동기가
    // 인트로에서 멈춘다. 그래서 여기서 확인하는 것은 "고른 뒤 정상 경로가 그대로"까지다.
    fireEvent.click(screen.getByRole('button', { name: AD_CONSENT_DENY }))
    clickStart()

    expect(requestMicPermission).toHaveBeenCalledTimes(1)
  })
})
