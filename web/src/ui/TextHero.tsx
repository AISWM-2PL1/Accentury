/**
 * 텍스트 히어로 — 그림 대신 서는 한 마디 (KAN-178).
 *
 * 인트로·분석 대기 화면을 열던 종이 일러스트(`illustrations/IntroHero`·`WaitingHero`)를
 * 걷어낸 자리다. 오려 낸 사람 그림은 예쁘긴 해도 화면이 무엇을 하는 곳인지는 말하지
 * 않았고, 스토어 스크린샷처럼 축소된 자리에서는 선이 뭉개져 무엇을 그린 것인지도 읽히지
 * 않았다 — 같은 자리에 큰 글자 한 줄을 세우면 화면을 열자마자 문장이 먼저 온다.
 *
 * ## 장식이 기본이고 제목은 선택인 이유
 *
 * 두 화면이 히어로를 같은 자리에 세우지만 **접근성 트리에서 맡는 역할은 반대다**.
 *
 * - 분석 대기 화면(기본값): 아래 h1이 진행 상태("분석 중입니다 · N%")를 실어 나른다.
 *   상태가 바뀔 때마다 갱신되는 그 제목이 화면 이름이라, 히어로까지 라벨을 가지면
 *   스크린 리더가 같은 화면을 두 번 소개한다 — 그래서 `<p aria-hidden>`으로 남긴다.
 * - 인트로 화면(`heading`): 중복이던 h1("사투리 억양 테스트")을 걷어냈다. 이제 화면
 *   이름을 말하는 것이 히어로 문구뿐이므로 `<h1>`으로 서야 한다. 여기서 `aria-hidden`을
 *   달아 두면 인트로에 제목이 하나도 남지 않아 접근 가능한 이름을 잃는다.
 *
 * 두 갈래를 boolean 하나로 가른 것은 의도다 — `aria-hidden`과 태그를 따로 받으면
 * "h1인데 숨겨진 히어로" 같은 조합이 만들어지고, 그게 정확히 막으려는 상태다.
 */

import type { ReactNode } from 'react'

export interface TextHeroProps {
  children: ReactNode
  /**
   * 이 문구가 곧 화면 이름일 때만 true — `<h1>`으로 서고 `aria-hidden`이 걷힌다.
   * 기본값(false)은 장식이라는 뜻이고, 화면 이름을 말할 제목이 따로 있다는 전제가 붙는다.
   */
  heading?: boolean
}

export function TextHero({ children, heading = false }: TextHeroProps) {
  if (heading) {
    return <h1 className="text-hero">{children}</h1>
  }
  return (
    <p className="text-hero" aria-hidden>
      {children}
    </p>
  )
}
