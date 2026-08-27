/**
 * 분석 대기 히어로 — 계단을 오르는 사람과 깃발 (KAN-161 3단계, 아트보드 `Waiting.dc.html`).
 *
 * 대기 화면의 그림이 계단인 것은 이 화면이 하는 말과 같다: 끝난 것이 아니라 **올라가는
 * 중**이고 꼭대기가 보인다. 스피너는 시간이 흐른다는 것만 말하지 어디쯤인지는 말하지 않는다.
 *
 * viewBox의 y가 -14인 이유는 사람의 머리가 계단보다 위로 올라가기 때문이다 — 0에서 시작하면
 * 머리 위쪽이 잘린다. width/height는 시안 그대로 240×176.
 */

import { useId } from 'react'
import { HalftonePattern, ILLO_INK, PAPER_FILTER, inkFill, inkStroke, paperFill, halftoneFill } from './paper'

export function WaitingHero() {
  const patternId = `illo-waiting-${useId().replace(/:/g, '')}`

  return (
    <svg
      width="240"
      height="176"
      viewBox="0 -14 240 176"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ filter: PAPER_FILTER }}
      aria-hidden="true"
    >
      <HalftonePattern id={patternId} />
      <path {...paperFill} d="M8 156 L8 112 L84 112 L84 156 Z" />
      <path {...halftoneFill(patternId)} d="M84 156 L84 84 L160 84 L160 156 Z" />
      <path {...paperFill} d="M160 156 L160 56 L236 56 L236 156 Z" />
      <path {...inkStroke} d="M208 54 L208 12" />
      <path {...inkFill} d="M208 14 L232 24 L208 34 Z" />
      <path {...paperFill} d="M110 36 C110 28 116 24 124 24 C132 24 138 28 138 36 L138 62 L110 62 Z" />
      <path {...paperFill} d="M138 40 L158 32 L160 42 L140 50 Z" />
      <path {...paperFill} d="M114 62 L104 82 L114 86 L126 66 Z M132 62 L146 56 L164 58 L162 68 L148 68 L138 76 Z" />
      <circle {...paperFill} cx="124" cy="10" r="14" />
      <path {...inkFill} d="M110 6 C112 -4 118 -6 124 -4 C130 -6 136 -4 138 6 C132 2 116 2 110 6 Z" />
      <circle cx="129" cy="12" r="2" fill={ILLO_INK} />
    </svg>
  )
}
