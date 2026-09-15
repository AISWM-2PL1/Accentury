import { afterEach, describe, expect, it } from 'vitest'
import { markRuntime } from './runtime'
import type { AccenturyBridge } from '../bridge/bridge'

afterEach(() => {
  delete window.AccenturyBridge
  document.documentElement.removeAttribute('data-runtime')
})

/** 판정에는 객체의 존재만 쓰이므로 메서드는 최소로 채운다 (`bridge.test.ts`의 대역과 같은 이유) */
function fakeBridge(): AccenturyBridge {
  return {
    requestMicPermission: () => {},
    startVoiceItem: () => {},
    getContractVersion: () => 1,
  }
}

describe('markRuntime — 런타임을 문서에 심는다 (KAN-199 #3)', () => {
  it('브리지도 파라미터도 없으면 browser다', () => {
    const root = document.createElement('html')
    expect(markRuntime('', root)).toBe('browser')
    expect(root.dataset.runtime).toBe('browser')
  })

  it('브리지 객체가 있으면 app이다 — 구버전 앱을 브라우저로 오인하지 않는다', () => {
    window.AccenturyBridge = fakeBridge()
    const root = document.createElement('html')
    expect(markRuntime('', root)).toBe('app')
    expect(root.dataset.runtime).toBe('app')
  })

  it('bridge 파라미터만 있어도 app이다', () => {
    const root = document.createElement('html')
    expect(markRuntime('?bridge=1&app=1.0', root)).toBe('app')
    expect(root.dataset.runtime).toBe('app')
  })

  it('기본 대상은 문서의 루트 요소다', () => {
    markRuntime('')
    expect(document.documentElement.dataset.runtime).toBe('browser')
  })
})
