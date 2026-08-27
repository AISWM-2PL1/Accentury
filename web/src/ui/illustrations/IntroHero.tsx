/**
 * 인트로 히어로 — 확성기를 든 사람 (KAN-161 3단계, 시안 아트보드 `Main.dc.html`).
 *
 * 이모지 히어로(🎯⭐✨)를 대신한다. 이모지는 시스템이 색을 갖고 그리기 때문에 잉크 한 색
 * 화면에서 유일한 색조로 남았고, 기기마다 다른 그림이 나온다 — 시안의 히어로는 손으로 오린
 * 종이라 색도 모양도 우리가 정해야 한다 (정본 §8 "일러스트 자산은 화면 이식 몫").
 *
 * `aria-hidden`인 이유: 옆에 있는 h1이 이미 "사투리 억양 테스트"라고 말한다. 그림에 라벨을
 * 붙이면 스크린 리더가 같은 화면을 두 번 소개하게 된다.
 */

import { useId } from 'react'
import { HalftonePattern, ILLO_INK, PAPER_FILTER, inkFill, inkStroke, paperFill, halftoneFill } from './paper'

export function IntroHero() {
  /*
   * 망점 격자의 id. 한 문서에 이 그림이 둘 이상 설 수 있고(전환 중 두 화면이 겹치는 순간),
   * id가 같으면 나중에 붙은 <pattern>이 앞의 것을 덮어 두 그림이 한 격자를 나눠 쓴다.
   * useId 값의 콜론은 지운다 — `url(#:r0:)`은 CSS 선택자 문법에서 유효하지 않다.
   */
  const patternId = `illo-intro-${useId().replace(/:/g, '')}`

  return (
    <svg
      width="208"
      height="192"
      viewBox="0 0 208 192"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ filter: PAPER_FILTER }}
      aria-hidden="true"
    >
      <HalftonePattern id={patternId} />
      <path
        {...halftoneFill(patternId)}
        d="M56 118 C52 100 66 92 84 92 L104 92 C120 92 130 102 130 116 L130 174 C130 184 122 190 112 190 L72 190 C62 190 56 184 56 174 Z"
      />
      <path {...paperFill} d="M100 110 C118 100 136 94 152 92 L158 106 C140 110 126 118 114 128 Z" />
      <path {...inkStroke} d="M84 92 L86 80 M102 92 L100 80" />
      <circle {...paperFill} cx="92" cy="58" r="26" />
      <path {...inkFill} d="M66 54 C66 34 78 28 92 30 C108 28 120 36 118 50 C108 42 96 42 88 48 C80 44 72 48 66 54 Z" />
      <circle cx="100" cy="58" r="2.4" fill={ILLO_INK} />
      <path {...inkStroke} d="M106 68 C110 70 110 76 106 78" />
      <path {...paperFill} d="M152 88 L186 70 L186 126 L152 108 Z" />
      <ellipse {...paperFill} cx="186" cy="98" rx="7" ry="28" />
      <path {...inkStroke} d="M198 84 C204 80 210 84 214 78 M198 112 C204 116 210 112 214 118" />
    </svg>
  )
}
