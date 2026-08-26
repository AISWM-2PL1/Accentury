/**
 * 진입 쿼리 조립 (KAN-31 Stage 1). 검사의 축은 하나다 — **지울 것만 지우는가**.
 *
 * `bridge`·`app`이 빠지면 스큐 판정이 구버전 앱으로 오해하고, `c`가 빠지면 유입 계측이 화면
 * 전환 한 번에 끊긴다. 둘 다 "지운 적 없는데 사라진" 증상이라 화면에서는 원인이 보이지 않는다.
 */

import { describe, expect, it } from 'vitest'
import { buildIntroUrl, buildResultUrl, buildTestUrl } from './entryUrl'

/** 앱 진입(bridge·app)과 공유 링크 진입(c)이 한꺼번에 들어 있는 최악의 조합 */
const ENTRY = '?bridge=1&app=1.0&c=kko_share'

const paramsOf = (query: string) => new URLSearchParams(query)

describe('buildTestUrl — 문항 진행 화면', () => {
  it('화면·정의 버전·세션을 얹는다', () => {
    const next = paramsOf(buildTestUrl('?c=kko_share', { testVersion: 'gn-2026.08.1', sessionId: 's_1' }))

    expect(next.get('screen')).toBe('test')
    expect(next.get('testVersion')).toBe('gn-2026.08.1')
    expect(next.get('sessionId')).toBe('s_1')
  })

  it('기존 진입 파라미터는 그대로 남는다', () => {
    const next = paramsOf(buildTestUrl(ENTRY, { testVersion: 'gn-2026.08.1', sessionId: 's_1' }))

    expect(next.get('bridge')).toBe('1')
    expect(next.get('app')).toBe('1.0')
    expect(next.get('c')).toBe('kko_share')
  })
})

describe('buildResultUrl — 결과 화면', () => {
  it('화면과 세션을 얹고 정의 버전은 지운다', () => {
    const next = paramsOf(buildResultUrl(`${ENTRY}&screen=test&testVersion=gn-2026.08.1`, 's_1'))

    expect(next.get('screen')).toBe('result')
    expect(next.get('sessionId')).toBe('s_1')
    expect(next.get('testVersion')).toBeNull()
  })

  it('기존 진입 파라미터는 그대로 남는다', () => {
    const next = paramsOf(buildResultUrl(ENTRY, 's_1'))

    expect(next.get('bridge')).toBe('1')
    expect(next.get('app')).toBe('1.0')
    expect(next.get('c')).toBe('kko_share')
  })
})

describe('buildIntroUrl — 인트로 복귀', () => {
  it('화면 지정만 걷어낸다', () => {
    const next = paramsOf(buildIntroUrl(`${ENTRY}&screen=result&sessionId=s_1&testVersion=gn-2026.08.1`))

    expect(next.get('screen')).toBeNull()
    expect(next.get('sessionId')).toBeNull()
    expect(next.get('testVersion')).toBeNull()
    expect(next.get('bridge')).toBe('1')
    expect(next.get('app')).toBe('1.0')
    expect(next.get('c')).toBe('kko_share')
  })

  it('남을 파라미터가 없으면 물음표도 붙이지 않는다', () => {
    expect(buildIntroUrl('?screen=result&sessionId=s_1')).toBe('')
  })
})
