import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  appBridgeVersion,
  isBridgeCompatible,
  REQUIRED_BRIDGE_VERSION,
  requestMicPermission,
} from './bridge'

afterEach(() => {
  delete window.AccenturyBridge
})

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
    window.AccenturyBridge = { requestMicPermission: fn, getContractVersion: () => 1 }
    expect(requestMicPermission()).toBe(true)
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('브리지가 없으면(브라우저 단독 실행) 크래시 없이 false를 돌려준다', () => {
    expect(requestMicPermission()).toBe(false)
  })
})
