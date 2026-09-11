/**
 * 맞춤형 광고 동의 시트의 문구 (KAN-196). `intro/introText.ts`처럼 상수로 뺀다 — 화면과
 * 테스트가 같은 값을 보게 하고, 문구를 고치는 사람이 JSX를 뒤지지 않게 하기 위해서다.
 *
 * ## 네 가지를 반드시 말한다
 *
 * 개인정보위 「온라인 맞춤형 광고 개인정보보호 가이드라인」이 요구하는 고지 요소다 —
 * ① 누가(사업자) ② 무엇을 모으고(수집 항목) ③ 왜(목적) ④ 거부하면 어떻게 되는지(영향).
 * 문장을 줄이더라도 이 넷 중 하나를 빼면 고지가 아니다. 아래 상수 하나가 한 요소를 맡는
 * 식으로 나누지는 않았다 — 한 문단으로 읽혀야 시트가 약관처럼 보이지 않는다 — 대신 각 문장이
 * 어느 요소인지 주석으로 짚어 둔다.
 *
 * 말투는 앱의 다른 카피와 같다 (ux-ui.md 비난 없는 카피, "~해요"체). 「허용하지 않으셔도
 * 광고는 나오지만」이 ④인데, 거부의 결과를 숨기지 않으면서도 거부를 나쁜 선택처럼 읽히게
 * 하지 않는 것이 이 문장의 일이다.
 */

export const AD_CONSENT_TITLE = '맞춤형 광고 안내'

/** ① 사업자(Google AdMob) + 광고가 나오는 이유 */
export const AD_CONSENT_WHY = 'Accentury는 무료 서비스라 Google AdMob 광고가 나와요.'

/** ② 수집 항목(기기 광고 식별자) + ③ 목적(관심사에 맞는 광고) + ④ 거부 시 영향(일반 광고) */
export const AD_CONSENT_EFFECT =
  '허용하시면 기기의 광고 식별자로 관심사에 맞는 광고를 보여 드리고, 허용하지 않으셔도 광고는 나오지만 맞춤형이 아닌 일반 광고만 나와요.'

/** 철회·재동의 경로. 링크 이름([AD_CONSENT_SETTINGS_LINK])을 그대로 적어 찾을 수 있게 한다 */
export const AD_CONSENT_CHANGE_HINT = '선택은 첫 화면 아래 「맞춤형 광고 설정」에서 언제든 바꿀 수 있어요.'

/** 방침 링크 앞의 말. 링크 자체는 `legal/PrivacyPolicyLink`가 그린다 */
export const AD_CONSENT_DETAIL_LEAD = '자세한 내용은'
export const AD_CONSENT_DETAIL_TAIL = '을 봐 주세요.'

export const AD_CONSENT_ALLOW = '맞춤형 광고 허용'
export const AD_CONSENT_DENY = '일반 광고만 보기'

/** 인트로 하단, 방침 링크 옆의 철회·재동의 링크 */
export const AD_CONSENT_SETTINGS_LINK = '맞춤형 광고 설정'

/**
 * 다시 열었을 때 지금 상태를 알리는 한 줄. 첫 실행(`unknown`)에는 없다 — 아직 고른 것이
 * 없는데 "지금은 ~"라고 말할 수 없다.
 */
export const AD_CONSENT_CURRENT = {
  granted: '지금은 맞춤형 광고를 허용한 상태예요.',
  denied: '지금은 일반 광고만 보는 상태예요.',
} as const
