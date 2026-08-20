/**
 * 인트로가 보여주는 숫자의 상수 (KAN-97).
 *
 * 문구 조립 함수(compositionText·estimatedDurationText)는 화면이 숫자를 문장에 섞어
 * 쓰던 시절의 것이라 KAN-148에서 함께 지웠다 — 지금은 숫자 칸이 값을 직접 그린다.
 * 남은 건 그 값들이고, 여기서 확인하는 건 "화면과 정의가 같은 수를 말하는가"뿐이다.
 */

import { describe, expect, it } from 'vitest'
import { ESTIMATED_MINUTES, VOCABULARY_ITEM_COUNT, VOICE_ITEM_COUNT } from './introText'

describe('인트로 상수', () => {
  it('음성과 어휘를 합치면 KAN-10 확정값 10문항이다', () => {
    expect(VOICE_ITEM_COUNT + VOCABULARY_ITEM_COUNT).toBe(10)
  })

  it('예상 시간은 ux-ui.md의 "진입→결과 3분" 목표와 같다', () => {
    expect(ESTIMATED_MINUTES).toBe(3)
  })
})
