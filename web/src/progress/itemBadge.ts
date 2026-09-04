import type { TestItem } from './testDefinition'

/**
 * 문항 카드 맨 위 캡션의 뒷부분 — **지금 무엇을 하라는 것인지** 한 마디 (KAN-161 3단계).
 *
 * 유형 배지("🎤 음성 문항")였는데 지시문으로 바뀌었다. 배지는 사용자가 이미 아는 것(음성인지
 * 단어인지는 화면에 녹음 버튼이 있는지 선택지가 있는지로 이미 보인다)을 한 번 더 말하는 줄이라,
 * 카드에서 가장 위 자리를 쓰면서 아무것도 알려 주지 않았다. 시안(`Vocab.dc.html`·`Voice.dc.html`)은
 * 그 자리에 문항 번호와 할 일을 둔다 — "7 / 10 · 이 말은 무슨 뜻일까요?"
 *
 * 이모지를 뺀 것도 같은 이식의 일부다: 이모지는 시스템이 자기 색으로 그려 잉크 한 색 화면에
 * 유일한 색조로 남고, 기기마다 다른 그림이 나온다 (정본 §8).
 *
 * 화면 파일이 아니라 여기 있는 이유: 음성·어휘 두 화면이 각자 자기 카드 안에 그리는데,
 * 한쪽에 두고 다른 쪽이 가져다 쓰면 화면끼리 import가 얽힌다.
 */
export const TYPE_PROMPT: Record<TestItem['type'], string> = {
  VOICE: '이 문장을 읽어주세요',
  VOCABULARY: '이 말은 무슨 뜻일까요?',
}

/**
 * 카드 캡션 한 줄. "7 / 10 · 이 말은 무슨 뜻일까요?"
 *
 * 번호를 여기서 세지 않고 받는다 — 순번은 **전체 문항 기준**이어야 하고(네이티브 녹음 화면이
 * 그리는 "7 / 10"과 같은 값), 그 값을 아는 것은 진행 상태 머신을 든 호출자다.
 */
export function itemCaption(type: TestItem['type'], itemNumber: number, totalItems: number): string {
  return `${itemNumber} / ${totalItems} · ${TYPE_PROMPT[type]}`
}
