import { describe, expect, it } from 'vitest'
import { SUPPORT_NOTICE_EN, SUPPORT_NOTICE_KO } from './supportNoticeText'

/*
 * 이 파일이 지키는 것은 동작이 아니라 **문구 자체**다. 지원 표기는 운영 매뉴얼 제18조 2항이
 * 요구하는 정해진 문장이라, 읽기 좋게 다듬는 순간 규정이 요구하는 표기가 아니게 된다.
 * 문구가 틀려도 화면은 멀쩡히 뜨고 테스트도 빌드도 통과하므로, 여기서 막지 않으면
 * 사업단 점검에서 처음 발견된다.
 *
 * 그래서 부분 문자열(`toContain`)이 아니라 **전체 문자열 비교**(`toBe`)로 못 박는다.
 * 부분 검사는 검사하지 않은 조사·어절·문장부호가 변형돼도 통과한다 — 「재원으로」가
 * 「재원을 받아」가 되거나 괄호·띄어쓰기가 흐트러져도 못 잡는다. 아래 기대 문자열은
 * 상수를 import해 자기 자신과 비교하는 것이 아니라 **매뉴얼 템플릿을 여기 그대로 옮겨 적은
 * 독립 리터럴**이다. 그래야 상수를 고치는 실수가 이 파일과 충돌해 드러난다.
 * 전체 문자열을 고정하는 것이 곧 템플릿 자리(`0000` 같은 미치환 연도) 잔존 방지이기도 하다 —
 * 별도의 `0000` 검사를 두지 않는 이유가 이것이다.
 *
 * 가운뎃점은 U+00B7(`·`)이다. 비슷하게 생긴 U+30FB(`・`)나 가운뎃점 없는 표기로 바뀌면
 * 전체 비교에서 걸린다.
 */
describe('지원 표기 문구 — 운영 매뉴얼 제18조 2항', () => {
  it('국문 표기가 공식 템플릿과 한 글자도 다르지 않다', () => {
    expect(SUPPORT_NOTICE_KO).toBe(
      '이 성과는 2026년도 과학기술정보통신부의 재원으로 정보통신기획평가원의 지원을 받아 수행된 결과물임 (IITP-2026-AI·SW마에스트로과정)',
    )
  })

  it('영문 표기가 공식 템플릿과 한 글자도 다르지 않다', () => {
    expect(SUPPORT_NOTICE_EN).toBe(
      'This work was supported by the Institute of Information & Communications Technology Planning & Evaluation(IITP) grant funded by the Ministry of Science and ICT(MSIT) (IITP-2026-AI·SW Maestro training course)',
    )
  })
})
