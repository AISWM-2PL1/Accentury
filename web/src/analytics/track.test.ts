import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { track } from './track'

beforeEach(() => {
  // DEV 빌드의 진단 로그를 잠재운다 — 확인 대상은 큐에 무엇이 쌓이는가이지 로그가 아니다
  vi.spyOn(console, 'debug').mockImplementation(() => {})
})

afterEach(() => {
  delete window.dataLayer
  vi.restoreAllMocks()
})

describe('track — 퍼널 이벤트 (KAN-31 3단계)', () => {
  it('큐가 있으면 이벤트명과 파라미터를 gtag 모양으로 밀어 넣는다', () => {
    window.dataLayer = []

    track({ name: 'referral_opened', campaign: 'kko_share' })

    expect(window.dataLayer).toEqual([{ event: 'referral_opened', campaign: 'kko_share' }])
  })

  it('지점별 파라미터가 그대로 실린다 — 다운로드에는 스토어가 붙는다', () => {
    window.dataLayer = []

    track({ name: 'app_download_clicked', campaign: null, platform: 'ios' })

    expect(window.dataLayer[0]).toEqual({
      event: 'app_download_clicked',
      campaign: null,
      platform: 'ios',
    })
  })

  it('큐가 없으면(태그 미설치 빌드) 아무 일도 하지 않는다', () => {
    // KAN-33 이전의 지금이 이 상태다. 광고 차단기가 스니펫을 막은 브라우저도 같은 자리다.
    expect(() => track({ name: 'test_completed', campaign: 'kko_share' })).not.toThrow()
    expect(window.dataLayer).toBeUndefined()
  })

  it('큐가 배열이 아니면 손대지 않는다', () => {
    // 태그 스니펫보다 먼저 도는 코드가 전역을 다른 값으로 잡아 둔 경우
    window.dataLayer = { push: vi.fn() } as unknown as unknown[]

    track({ name: 'referral_test_started', campaign: null })

    expect((window.dataLayer as unknown as { push: ReturnType<typeof vi.fn> }).push).not.toHaveBeenCalled()
  })

  it('push가 던져도 호출자에게 튀지 않는다 — 계측 실패가 흐름을 끊지 않는다', () => {
    const queue: unknown[] = []
    queue.push = () => {
      throw new Error('tag exploded')
    }
    window.dataLayer = queue

    expect(() => track({ name: 'referral_opened', campaign: 'kko_share' })).not.toThrow()
  })
})
