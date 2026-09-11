/**
 * 지역 코드 표 (KAN-202). 확인하는 것은 둘이다 — **표가 백엔드 `Region.java`와 같은 열 개인가**와
 * **빌드 스위치가 정확히 `'true'`에만 켜지는가**.
 *
 * 표 검사는 코드 하나하나가 아니라 개수·중복·양 끝 순서를 본다. 코드를 전부 다시 적으면 표를
 * 복사한 것일 뿐이라 실수를 잡지 못한다 — 백엔드와의 실제 대조는 E2E(3단계)가 한다.
 */

import { afterEach, describe, expect, it, vi } from 'vitest'
import { isRegionCode, isRegionSelectEnabled, REGIONS } from './regions'

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('REGIONS — 백엔드 Region.java의 거울', () => {
  it('서버가 받아 주는 코드는 열 개이고 겹치지 않는다', () => {
    const codes = REGIONS.map((region) => region.code)

    expect(codes).toHaveLength(10)
    expect(new Set(codes).size).toBe(10)
  })

  it('순서가 KAN-202 표를 따른다 — 화면 나열 순서가 이 배열이다', () => {
    expect(REGIONS[0].code).toBe('SEOUL')
    expect(REGIONS.at(-1)?.code).toBe('JEJU')
  })

  it('모든 항목에 화면에 보일 한글 표기가 있다', () => {
    for (const region of REGIONS) {
      expect(region.label.trim()).not.toBe('')
    }
  })
})

describe('isRegionCode — 서버가 400으로 돌려줄 값을 미리 거른다', () => {
  it('표의 코드는 통과한다', () => {
    expect(isRegionCode('SEOUL')).toBe(true)
    expect(isRegionCode('JEJU')).toBe(true)
  })

  it('대소문자가 다르면 코드가 아니다 — 서버는 name().equals로 비교한다', () => {
    expect(isRegionCode('seoul')).toBe(false)
  })

  it('UNKNOWN은 서버 저장 전용이라 요청 코드가 아니다 — 실어 보내면 400이다', () => {
    expect(isRegionCode('UNKNOWN')).toBe(false)
  })

  it('빈 문자열과 문자열 아닌 값은 코드가 아니다', () => {
    expect(isRegionCode('')).toBe(false)
    expect(isRegionCode(null)).toBe(false)
    expect(isRegionCode(undefined)).toBe(false)
    expect(isRegionCode(0)).toBe(false)
  })
})

describe('isRegionSelectEnabled — 빌드 변수가 정확히 true일 때만', () => {
  it("VITE_REGION_SELECT='true'면 켜진다 (staging 빌드)", () => {
    vi.stubEnv('VITE_REGION_SELECT', 'true')

    expect(isRegionSelectEnabled()).toBe(true)
  })

  it("'false'는 꺼진다", () => {
    vi.stubEnv('VITE_REGION_SELECT', 'false')

    expect(isRegionSelectEnabled()).toBe(false)
  })

  // GitHub vars가 정의되지 않은 환경에서 빈 문자열로 주입되는 경우 - truthy 판정이면 여기서 새어 나간다
  it('빈 문자열은 꺼진다', () => {
    vi.stubEnv('VITE_REGION_SELECT', '')

    expect(isRegionSelectEnabled()).toBe(false)
  })

  it("'1' 같은 다른 truthy 문자열도 꺼진다", () => {
    vi.stubEnv('VITE_REGION_SELECT', '1')

    expect(isRegionSelectEnabled()).toBe(false)
  })

  it('변수가 없으면 꺼진다 (prod·로컬 기본)', () => {
    vi.stubEnv('VITE_REGION_SELECT', undefined)

    expect(isRegionSelectEnabled()).toBe(false)
  })
})
