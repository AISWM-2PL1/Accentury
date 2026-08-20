// KAN-10 활성 테스트 정의 API 연동 시 서버가 내려주는 값으로 교체된다.
// 그때까지는 KAN-10 확정값(음성 5 + 어휘 5 = 10문항)을 상수로 둔다.
export const VOICE_ITEM_COUNT = 5
export const VOCABULARY_ITEM_COUNT = 5

// ux-ui.md "진입→결과 3분 이내" 목표에서 온 값. 이것도 KAN-10 연동 시 교체 대상이다.
export const ESTIMATED_MINUTES = 3
