/**
 * 텍스트 히어로 — 그림 대신 서는 한 마디 (KAN-178).
 *
 * 인트로·분석 대기 화면을 열던 종이 일러스트(`illustrations/IntroHero`·`WaitingHero`)를
 * 걷어낸 자리다. 오려 낸 사람 그림은 예쁘긴 해도 화면이 무엇을 하는 곳인지는 말하지
 * 않았고, 스토어 스크린샷처럼 축소된 자리에서는 선이 뭉개져 무엇을 그린 것인지도 읽히지
 * 않았다 — 같은 자리에 큰 글자 한 줄을 세우면 화면을 열자마자 문장이 먼저 온다.
 *
 * `aria-hidden`이 기본인 이유는 IntroHero와 같다: 옆에 있는 h1이 이미 화면 이름을 말한다.
 * 그림이든 글자든 장식에 라벨이 붙으면 스크린 리더가 같은 화면을 두 번 소개한다.
 */

import type { ReactNode } from 'react'

export interface TextHeroProps {
  children: ReactNode
  /** 이 문구를 대신 읽어 줄 제목이 옆에 없을 때만 false로 준다 */
  'aria-hidden'?: boolean
}

export function TextHero({ children, 'aria-hidden': ariaHidden = true }: TextHeroProps) {
  return (
    <p className="text-hero" aria-hidden={ariaHidden}>
      {children}
    </p>
  )
}
