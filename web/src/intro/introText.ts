// KAN-10 활성 테스트 정의 API 연동 시 서버가 내려주는 값으로 교체된다.
// 그때까지는 KAN-10 확정값(음성 5 + 어휘 5 = 10문항)을 상수로 둔다.
export const VOICE_ITEM_COUNT = 5
export const VOCABULARY_ITEM_COUNT = 5

// ux-ui.md "진입→결과 3분 이내" 목표에서 온 값. 이것도 KAN-10 연동 시 교체 대상이다.
export const ESTIMATED_MINUTES = 3

/**
 * 시작이 막혔을 때의 기본 문구 (KAN-31). 세션 생성 실패는 사용자가 잘못한 것이 없는 실패라
 * 원인을 묻지 않고 다시 해 보라고만 한다 (ux-ui.md 비난 없는 카피).
 *
 * 서버가 봉투(§2.3)로 자기 문구를 준 경우에는 그쪽이 더 구체적이라 그것을 쓰고, 이 문구는
 * 문구 없는 실패(스크립트 오류 등)의 마지막 안전망이다.
 */
export const START_FAILED_MESSAGE = '지금은 시작할 수 없어요. 잠시 후 다시 시도해 주세요.'
