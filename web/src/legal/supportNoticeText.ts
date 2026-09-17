/**
 * AI·SW마에스트로 과정 지원 표기.
 *
 * AI·SW마에스트로 운영 매뉴얼 제5장 보칙 제18조(성과물의 귀속 등) 2항이 근거다 — 성과물을
 * 게재·홍보하거나 전시회에 출품하는 등 **대외에 발표할 때** 과기정통부 AI·SW마에스트로 과정
 * 지원으로 개발된 결과물임을 표시해야 한다. 스토어에 올라가는 앱은 그 자체가 대외 발표라
 * 표기를 붙일 자리가 필요하다.
 *
 * ## 문구를 여기 상수로 못 박은 이유
 *
 * 아래 두 문장은 **공식 템플릿 그대로**이고, 원본의 연도 자리(`0000`)만 2026으로 채웠다.
 * 조사 하나를 다듬거나 「결과물임」을 「결과물입니다」로 고치는 순간 규정이 요구하는 문구가
 * 아니게 된다 — 읽기 좋게 만드는 것이 이 줄의 목적이 아니다. **문구를 임의로 수정하지 말 것.**
 * 바꿔야 할 일이 생기면 운영 매뉴얼의 최신 템플릿을 먼저 확인하고 여기 한 곳만 고친다.
 *
 * 영문은 지금 화면 어디에도 쓰지 않는다 (앱은 국문만 띄운다). 그래도 함께 두는 이유는
 * 같은 규정이 요구하는 한 쌍이라서다 — 논문·포스터·영문 스토어 등록 정보처럼 영문이 필요한
 * 산출물이 생겼을 때 문구를 다시 찾아 옮겨 적지 않게 한다.
 *
 * 파일 이름이 `SupportNotice.tsx`와 대소문자만 다른 `supportNotice.ts`가 아닌 이유는 macOS의
 * 대소문자 구분 없는 파일시스템이다 — 그러면 `./SupportNotice`가 이 상수 파일로 먼저 풀려
 * 컴포넌트를 못 찾는다(TS1149). 리눅스 CI에서만 통과하고 로컬에선 깨지는 종류의 함정이라
 * 이름으로 피한다. `adConsentText.ts`·`introText.ts`·`storeText.ts`가 문구 상수를 담는 이름꼴과도 같다.
 */

/** 국문 표기. 앱 화면에 실제로 뜨는 것은 이 줄이다 */
export const SUPPORT_NOTICE_KO =
  '이 성과는 2026년도 과학기술정보통신부의 재원으로 정보통신기획평가원의 지원을 받아 수행된 결과물임 (IITP-2026-AI·SW마에스트로과정)'

/** 영문 표기. 영문 산출물용 정본이며 앱 화면에는 쓰지 않는다 */
export const SUPPORT_NOTICE_EN =
  'This work was supported by the Institute of Information & Communications Technology Planning & Evaluation(IITP) grant funded by the Ministry of Science and ICT(MSIT) (IITP-2026-AI·SW Maestro training course)'
