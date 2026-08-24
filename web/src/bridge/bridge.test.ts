import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  appBridgeVersion,
  getSessionToken,
  installItemResultReceiver,
  installRetestFailedReceiver,
  isBridgeCompatible,
  REQUIRED_BRIDGE_VERSION,
  requestMicPermission,
  startRetest,
  startVoiceItem,
  type AccenturyBridge,
  type VoiceItemStart,
} from './bridge'
import type { ItemResult } from './itemResult'
import type { RetestFailure } from './retestFailure'

afterEach(() => {
  delete window.AccenturyBridge
  delete window.AccenturyWeb
})

/** 계약을 다 갖춘 브리지 대역. 각 테스트는 관심 있는 메서드만 갈아끼운다. */
function fakeBridge(overrides: Partial<AccenturyBridge> = {}): AccenturyBridge {
  return {
    requestMicPermission: vi.fn(),
    startVoiceItem: vi.fn(),
    getContractVersion: () => REQUIRED_BRIDGE_VERSION,
    ...overrides,
  }
}

const voiceStart: VoiceItemStart = {
  itemId: 'item_1',
  prompt: '마! 니 어데 가노?',
  itemNumber: 1,
  totalItems: 10,
  maxDurationMs: 15_000,
  // 무성 구간 null 포함 — JSON.stringify가 null을 그대로 실어 보내는지도 이 픽스처가 덮는다 (KAN-102)
  guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0.5, null, -1.2] },
}

const itemResult: ItemResult = {
  itemId: 'item_1',
  attemptId: 'at-1',
  analysisJobId: 'aj_1',
  durationMs: 4_200,
  qualityStatus: 'NORMAL',
}

const retestFailure: RetestFailure = {
  code: 'RATE_LIMITED',
  message: '접속이 몰리고 있어요 · 잠시 뒤에 다시 시도해 주세요',
  retryable: true,
  retryAfterMs: 5_000,
}

describe('appBridgeVersion — 앱이 URL로 실어 보낸 버전 파싱', () => {
  it('정수 버전을 읽는다', () => {
    expect(appBridgeVersion('?bridge=1&app=1.0')).toBe(1)
    expect(appBridgeVersion('?bridge=0')).toBe(0)
  })

  it('파라미터가 없으면 스큐 협상 이전 구버전 앱으로 본다 (null)', () => {
    expect(appBridgeVersion('')).toBeNull()
    expect(appBridgeVersion('?app=1.0')).toBeNull()
  })

  it('정수가 아닌 값은 신뢰하지 않는다 (null)', () => {
    expect(appBridgeVersion('?bridge=abc')).toBeNull()
    expect(appBridgeVersion('?bridge=1.5')).toBeNull()
    expect(appBridgeVersion('?bridge=-1')).toBeNull()
    expect(appBridgeVersion('?bridge=')).toBeNull()
  })
})

describe('isBridgeCompatible — 판단 주체는 웹 (§5)', () => {
  it('요구 버전 이상이면 호환이다', () => {
    expect(isBridgeCompatible(`?bridge=${REQUIRED_BRIDGE_VERSION}`)).toBe(true)
    expect(isBridgeCompatible(`?bridge=${REQUIRED_BRIDGE_VERSION + 1}`)).toBe(true)
  })

  it('요구 버전 미만이거나 버전이 없으면 호환 불가다', () => {
    expect(isBridgeCompatible(`?bridge=${REQUIRED_BRIDGE_VERSION - 1}`)).toBe(false)
    expect(isBridgeCompatible('')).toBe(false)
  })
})

describe('requestMicPermission — graceful degrade', () => {
  it('브리지가 있으면 호출하고 true를 돌려준다', () => {
    const fn = vi.fn()
    window.AccenturyBridge = fakeBridge({ requestMicPermission: fn })
    expect(requestMicPermission()).toBe(true)
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('브리지가 없으면(브라우저 단독 실행) 크래시 없이 false를 돌려준다', () => {
    expect(requestMicPermission()).toBe(false)
  })
})

describe('startVoiceItem — 문항 컨텍스트를 JSON으로 넘긴다', () => {
  it('브리지가 있으면 직렬화해 호출하고 true를 돌려준다', () => {
    const fn = vi.fn()
    window.AccenturyBridge = fakeBridge({ startVoiceItem: fn })

    expect(startVoiceItem(voiceStart)).toBe(true)
    expect(fn).toHaveBeenCalledTimes(1)
    expect(JSON.parse(fn.mock.calls[0][0])).toEqual(voiceStart)
  })

  it('브리지가 없으면 크래시 없이 false를 돌려준다', () => {
    expect(startVoiceItem(voiceStart)).toBe(false)
  })

  it('메서드만 없는 구버전 앱에서도 false다 (계약이 어긋나도 죽지 않는다)', () => {
    window.AccenturyBridge = {
      requestMicPermission: vi.fn(),
      getContractVersion: () => REQUIRED_BRIDGE_VERSION,
    } as unknown as AccenturyBridge

    expect(startVoiceItem(voiceStart)).toBe(false)
  })
})

describe('installItemResultReceiver — 네이티브 → 웹 수신 지점', () => {
  it('설치하면 네이티브 호출이 검증을 거쳐 handler로 온다', () => {
    const handler = vi.fn()
    installItemResultReceiver(handler)

    window.AccenturyWeb?.onItemResult?.(JSON.stringify(itemResult))

    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledWith(itemResult)
  })

  it('불량 payload는 조용히 버린다 — handler도, 예외도 없다', () => {
    const handler = vi.fn()
    installItemResultReceiver(handler)

    expect(() => window.AccenturyWeb?.onItemResult?.('{oops')).not.toThrow()
    expect(() => window.AccenturyWeb?.onItemResult?.('{"itemId":"item_1"}')).not.toThrow()
    expect(handler).not.toHaveBeenCalled()
  })

  it('해제하면 설치 전 상태로 돌아간다', () => {
    const handler = vi.fn()
    const dispose = installItemResultReceiver(handler)
    dispose()

    expect(window.AccenturyWeb).toBeUndefined()
    expect(handler).not.toHaveBeenCalled()
  })

  it('덧씌운 수신자를 해제하면 먼저 있던 수신자가 되살아난다', () => {
    const first = vi.fn()
    const second = vi.fn()
    installItemResultReceiver(first)
    const disposeSecond = installItemResultReceiver(second)

    window.AccenturyWeb?.onItemResult?.(JSON.stringify(itemResult))
    expect(second).toHaveBeenCalledTimes(1)
    expect(first).not.toHaveBeenCalled()

    disposeSecond()
    window.AccenturyWeb?.onItemResult?.(JSON.stringify(itemResult))
    expect(first).toHaveBeenCalledTimes(1)
  })
})

describe('startRetest — 재응시 요청 (KAN-34)', () => {
  it('브리지가 있으면 호출하고 true를 돌려준다', () => {
    const fn = vi.fn()
    window.AccenturyBridge = fakeBridge({ startRetest: fn })

    expect(startRetest()).toBe(true)
    expect(fn).toHaveBeenCalledTimes(1)
    // 무엇을 버릴지는 네이티브가 안다 — 웹이 토큰을 실어 보내지 않는 것이 계약이다.
    expect(fn).toHaveBeenCalledWith()
  })

  it('브리지가 없으면(브라우저 단독 실행) 크래시 없이 false를 돌려준다', () => {
    expect(startRetest()).toBe(false)
  })

  it('메서드만 없는 구버전 앱에서도 false다 (메서드 추가는 버전을 올리지 않는다)', () => {
    window.AccenturyBridge = fakeBridge() // startRetest 없음

    expect(startRetest()).toBe(false)
  })
})

describe('installRetestFailedReceiver — 재응시 실패 회신 (KAN-34)', () => {
  it('설치하면 네이티브 호출이 검증을 거쳐 handler로 온다', () => {
    const handler = vi.fn()
    installRetestFailedReceiver(handler)

    window.AccenturyWeb?.onRetestFailed?.(JSON.stringify(retestFailure))

    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledWith(retestFailure)
  })

  it('불량 payload는 조용히 버린다 — handler도, 예외도 없다', () => {
    const handler = vi.fn()
    installRetestFailedReceiver(handler)

    expect(() => window.AccenturyWeb?.onRetestFailed?.('{oops')).not.toThrow()
    expect(() => window.AccenturyWeb?.onRetestFailed?.('{"code":"RATE_LIMITED"}')).not.toThrow()
    expect(handler).not.toHaveBeenCalled()
  })

  it('해제하면 설치 전 상태로 돌아간다', () => {
    const dispose = installRetestFailedReceiver(vi.fn())
    dispose()

    expect(window.AccenturyWeb).toBeUndefined()
  })

  /*
   * 진행 화면(onItemResult)과 결과 화면(onRetestFailed)은 실제로는 동시에 뜨지 않는다.
   * 계약이 그 우연에 기대면 화면 하나가 늘어나는 순간 조용히 깨지므로 여기서 못박는다.
   */
  it('결과 수신자와 나란히 설치돼도 서로를 지우지 않는다', () => {
    const onResult = vi.fn()
    const onFailed = vi.fn()
    installItemResultReceiver(onResult)
    installRetestFailedReceiver(onFailed)

    window.AccenturyWeb?.onItemResult?.(JSON.stringify(itemResult))
    window.AccenturyWeb?.onRetestFailed?.(JSON.stringify(retestFailure))

    expect(onResult).toHaveBeenCalledTimes(1)
    expect(onFailed).toHaveBeenCalledTimes(1)
  })

  it('한쪽만 해제해도 남은 수신자는 계속 받는다', () => {
    const onResult = vi.fn()
    const onFailed = vi.fn()
    installItemResultReceiver(onResult)
    const disposeFailed = installRetestFailedReceiver(onFailed)

    disposeFailed()

    expect(window.AccenturyWeb?.onRetestFailed).toBeUndefined()
    window.AccenturyWeb?.onItemResult?.(JSON.stringify(itemResult))
    expect(onResult).toHaveBeenCalledTimes(1)
  })
})

describe('getSessionToken — 토큰 읽기 (KAN-13)', () => {
  it('브리지가 토큰을 주면 그대로 돌려준다', () => {
    window.AccenturyBridge = fakeBridge({ getSessionToken: () => 'token-1' })

    expect(getSessionToken()).toBe('token-1')
  })

  it('브리지가 없으면(브라우저 단독) null이다', () => {
    expect(getSessionToken()).toBeNull()
  })

  it('메서드가 없는 구버전 앱(계약 버전 1의 다른 조각)에서도 null이다', () => {
    window.AccenturyBridge = fakeBridge() // getSessionToken 없음

    expect(getSessionToken()).toBeNull()
  })

  it('네이티브의 origin 거부 신호(빈 문자열)는 null로 정규화한다', () => {
    window.AccenturyBridge = fakeBridge({ getSessionToken: () => '' })

    expect(getSessionToken()).toBeNull()
  })
})
