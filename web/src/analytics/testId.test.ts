import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearTestId, currentTestId, ensureTestId } from './testId'

afterEach(() => {
  clearTestId()
  vi.restoreAllMocks()
})

describe('ensureTestId — 응시 하나를 가리키는 익명 키 (KAN-33 AC 1)', () => {
  it('같은 세션 안에서는 같은 키를 돌려준다 — 문서 전환을 건너 이벤트가 한 묶음이 된다', () => {
    const first = ensureTestId('s_1')

    expect(first).not.toBeNull()
    expect(ensureTestId('s_1')).toBe(first)
    expect(currentTestId()).toBe(first)
  })

  it('세션이 바뀌면 새 키다 — 재응시가 앞 응시와 섞이지 않는다', () => {
    const first = ensureTestId('s_1')

    const second = ensureTestId('s_2')

    expect(second).not.toBe(first)
    expect(currentTestId()).toBe(second)
  })

  it('세션 id가 없으면 발급하지 않는다 — 아직 어느 응시에도 속하지 않는다', () => {
    expect(ensureTestId('')).toBeNull()
    expect(ensureTestId('   ')).toBeNull()
    expect(currentTestId()).toBeNull()
  })

  it('서버 세션 id를 그대로 쓰지 않는다 (AC 2 — 세션 id는 이벤트에 실을 수 없다)', () => {
    const testId = ensureTestId('s_1')

    expect(testId).not.toBe('s_1')
    expect(testId).not.toContain('s_1')
  })

  it('인트로가 지우면 다음 유입은 키 없이 나간다', () => {
    ensureTestId('s_1')

    clearTestId()

    expect(currentTestId()).toBeNull()
  })
})

describe('저장소를 쓸 수 없는 브라우저', () => {
  it('저장에 실패해도 이 문서 안에서 쓸 키는 돌려준다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })

    // 다음 문서에서 끊길 뿐이다 — 계측 하나 때문에 응시를 막지 않는다
    expect(ensureTestId('s_1')).not.toBeNull()
  })

  it('읽기가 던져도 호출자에게 튀지 않는다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })

    expect(() => currentTestId()).not.toThrow()
    expect(currentTestId()).toBeNull()
  })

  it('저장된 값이 깨져 있으면 없는 것으로 본다', () => {
    sessionStorage.setItem('accentury:analytics:test', '{ not json')

    expect(currentTestId()).toBeNull()
  })
})
