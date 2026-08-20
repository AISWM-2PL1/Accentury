import type { TestItem } from './testDefinition'

/**
 * 문항 유형 배지 문구. 인트로의 "🎤 음성 / 📝 단어" 표기와 같은 어휘를 쓴다 —
 * 사용자가 인트로에서 본 구성이 문항 화면에서 그대로 이어져야 "몇 개 중 몇 번째"가 읽힌다.
 *
 * 화면 파일이 아니라 여기 있는 이유: 음성·어휘 두 화면이 각자 자기 카드 안에 그리는데,
 * 한쪽에 두고 다른 쪽이 가져다 쓰면 화면끼리 import가 얽힌다.
 */
export const TYPE_BADGE: Record<TestItem['type'], string> = {
  VOICE: '🎤 음성 문항',
  VOCABULARY: '📝 단어 문항',
}
