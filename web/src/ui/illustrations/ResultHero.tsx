/**
 * 결과 히어로 — 정상에 깃발을 꽂은 사람 (KAN-161 3단계, 아트보드 `Result.dc.html`).
 *
 * 등급이 무엇으로 나오든 같은 그림이다. 등급별로 그림을 바꾸면 낮은 등급에 "졌다"는 그림을
 * 주게 되는데, 이 테스트는 사투리 유사도를 재는 것이지 잘잘못을 가리는 것이 아니다
 * (KAN-29: 레벨업으로 읽히는 표현을 쓰지 않는다).
 */

import { useId } from 'react'
import { HalftonePattern, ILLO_INK, PAPER_FILTER, inkFill, inkStroke, paperFill, halftoneFill } from './paper'

export function ResultHero() {
  const patternId = `illo-result-${useId().replace(/:/g, '')}`

  return (
    <svg
      width="192"
      height="168"
      viewBox="0 0 192 168"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ filter: PAPER_FILTER }}
      aria-hidden="true"
    >
      <HalftonePattern id={patternId} />
      <path {...halftoneFill(patternId)} d="M96 72 L172 166 L20 166 Z" />
      <path {...paperFill} d="M96 72 L120 102 L72 102 Z" />
      <path {...inkStroke} d="M132 10 L132 108" />
      <path {...inkFill} d="M132 12 L166 24 L132 36 Z" />
      <path {...paperFill} d="M80 46 C80 36 86 32 96 32 C106 32 112 36 112 46 L112 74 L80 74 Z" />
      <path {...paperFill} d="M112 44 L130 40 L132 52 L114 56 Z" />
      <path {...paperFill} d="M84 74 L82 96 L92 96 L94 74 Z M100 74 L100 96 L110 96 L110 74 Z" />
      <circle {...paperFill} cx="96" cy="16" r="16" />
      <path {...inkFill} d="M80 12 C82 2 90 -2 96 0 C102 -2 110 2 112 12 C106 8 86 8 80 12 Z" />
      <circle cx="102" cy="18" r="2.2" fill={ILLO_INK} />
      <path {...inkStroke} d="M104 26 C102 29 98 29 96 27" />
    </svg>
  )
}
