import { describe, expect, it } from 'vitest'
import { parseItemResult, type ItemResult } from './itemResult'

const valid: ItemResult = {
  itemId: 'item_1',
  attemptId: 'at-1',
  analysisJobId: 'aj_1',
  durationMs: 4_200,
  qualityStatus: 'NORMAL',
}

/** 유효 payload에서 한 필드만 비틀어 본다 — 거부 사유가 그 필드 때문임을 분명히 하기 위해서다. */
function payloadWith(patch: Record<string, unknown>): string {
  return JSON.stringify({ ...valid, ...patch })
}

describe('parseItemResult — 계약대로인 payload', () => {
  it('5필드가 모두 맞으면 그대로 통과시킨다', () => {
    expect(parseItemResult(JSON.stringify(valid))).toEqual(valid)
  })

  it('네 가지 qualityStatus를 모두 받는다', () => {
    for (const status of ['NORMAL', 'TOO_SHORT', 'TOO_QUIET', 'CLIPPED'] as const) {
      expect(parseItemResult(payloadWith({ qualityStatus: status }))?.qualityStatus).toBe(status)
    }
  })

  it('모르는 필드가 섞여 있어도 통과시킨다 (필드 추가는 하위호환)', () => {
    const result = parseItemResult(payloadWith({ futureField: 'whatever' }))
    expect(result).toEqual(valid)
  })

  it('durationMs 0은 유효하다 (음수만 거부한다)', () => {
    expect(parseItemResult(payloadWith({ durationMs: 0 }))?.durationMs).toBe(0)
  })
})

describe('parseItemResult — 신뢰 경계 밖 입력은 거부한다', () => {
  it('JSON이 아니면 null', () => {
    expect(parseItemResult('')).toBeNull()
    expect(parseItemResult('{oops')).toBeNull()
    expect(parseItemResult('<html>error</html>')).toBeNull()
  })

  it('객체가 아니면 null', () => {
    expect(parseItemResult('null')).toBeNull()
    expect(parseItemResult('42')).toBeNull()
    expect(parseItemResult('"item_1"')).toBeNull()
    expect(parseItemResult('[]')).toBeNull()
  })

  it('필드가 하나라도 빠지면 null', () => {
    for (const key of Object.keys(valid)) {
      const partial: Record<string, unknown> = { ...valid }
      delete partial[key]
      expect(parseItemResult(JSON.stringify(partial))).toBeNull()
    }
  })

  it('문자열 필드에 다른 타입이 오면 null', () => {
    expect(parseItemResult(payloadWith({ itemId: 1 }))).toBeNull()
    expect(parseItemResult(payloadWith({ attemptId: null }))).toBeNull()
    expect(parseItemResult(payloadWith({ analysisJobId: { id: 'aj_1' } }))).toBeNull()
  })

  it('durationMs가 수가 아니거나 유한하지 않거나 음수면 null', () => {
    expect(parseItemResult(payloadWith({ durationMs: '4200' }))).toBeNull()
    expect(parseItemResult(payloadWith({ durationMs: -1 }))).toBeNull()
    expect(parseItemResult('{"itemId":"i","attemptId":"a","analysisJobId":"j","durationMs":null,"qualityStatus":"NORMAL"}')).toBeNull()
  })

  it('모르는 qualityStatus는 거부한다 (enum 값 추가는 의미 변경이라 버전 증가 대상)', () => {
    expect(parseItemResult(payloadWith({ qualityStatus: 'UNKNOWN_FUTURE' }))).toBeNull()
    expect(parseItemResult(payloadWith({ qualityStatus: 'normal' }))).toBeNull()
    expect(parseItemResult(payloadWith({ qualityStatus: 0 }))).toBeNull()
  })
})
