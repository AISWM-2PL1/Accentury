import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MicPermission } from '../audio/microphone'
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
  fireEvent.click(screen.getByRole('button', { name: '시작하기' }))
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
    expect(screen.getByRole('button', { name: '시작하기' })).toBeEnabled()
  })

  it('권한이 거부되면 안내 화면으로 갈아치우고 스토어 링크를 준다', async () => {
    const restoreUa = withUserAgent(ANDROID_UA)
    const onWebStart = vi.fn()
    render(<IntroScreen requestWebPermission={permissionStub('denied')} onWebStart={onWebStart} />)

    clickStart()

    expect(await screen.findByText('마이크 권한이 필요해요')).toBeInTheDocument()
    // 권한 없이는 테스트를 시작할 수 없다 (§5.6) — 인트로가 남아 있으면 안 된다
    expect(screen.queryByRole('button', { name: '시작하기' })).not.toBeInTheDocument()
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
    expect(screen.getByRole('button', { name: '시작하기' })).toBeInTheDocument()
  })
})

describe('IntroScreen — 텍스트 히어로 (KAN-178)', () => {
  it('그림 대신 "사투리 좀 치나?"가 서고, 제목은 그대로 남는다', () => {
    render(<IntroScreen requestWebPermission={permissionStub('granted')} />)

    expect(screen.getByText('사투리 좀 치나?')).toBeInTheDocument()
    // 히어로는 장식이라 제목을 대신하지 않는다 — 화면 이름은 여전히 h1이 말한다
    expect(screen.getByRole('heading', { level: 1, name: '사투리 억양 테스트' })).toBeInTheDocument()
  })
})
