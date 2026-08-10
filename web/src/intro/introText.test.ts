// IntroScreenTest.kt(네이티브 폐기분)의 테스트 4건을 웹으로 이관한 것이다.
import { describe, expect, it } from 'vitest'
import {
  compositionText,
  estimatedDurationText,
  ESTIMATED_MINUTES,
  VOCABULARY_ITEM_COUNT,
  VOICE_ITEM_COUNT,
} from './introText'

describe('introText', () => {
  it('문항 구성은 음성과 단어를 나눠 보여주고 합계까지 붙인다', () => {
    expect(compositionText(5, 5)).toBe('🎤 음성 5문항 + 📝 단어 5문항 (총 10문항)')
  })

  it('문항 수가 바뀌면 합계도 따라간다', () => {
    expect(compositionText(3, 7)).toContain('총 10문항')
    expect(compositionText(4, 4)).toContain('총 8문항')
  })

  it('예상 소요 시간은 어림값이라 약을 붙여 보여준다', () => {
    expect(estimatedDurationText(3)).toBe('예상 소요 시간 약 3분')
  })

  it('KAN-10 확정값인 음성 5 어휘 5 총 10문항을 상수로 들고 있다', () => {
    expect(VOICE_ITEM_COUNT).toBe(5)
    expect(VOCABULARY_ITEM_COUNT).toBe(5)
    expect(VOICE_ITEM_COUNT + VOCABULARY_ITEM_COUNT).toBe(10)
    expect(ESTIMATED_MINUTES).toBe(3)
  })
})
