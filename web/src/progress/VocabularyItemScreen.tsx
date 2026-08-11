/**
 * 어휘 문항 화면 (KAN-100 Stage 2 — 진입점만 잡아 둔다).
 *
 * 이 파일은 KAN-13(어휘 문항)이 정식 구현으로 갈아끼울 자리다. 지금 하는 일은 정의가 준
 * prompt와 choices를 그대로 그리고, 하나가 선택되면 진행을 통지하는 것뿐이다.
 *
 * **고른 답은 아직 아무 데도 가지 않는다.** 선택 답의 서버 제출과 채점 결선은 KAN-13 몫이라
 * 여기서는 choiceId를 받아 버린다. 정의에 정오 정보가 없는 것도 같은 이유다 (KAN-13 정오 미노출).
 */

import type { VocabularyItem } from './testDefinition'

export interface VocabularyItemScreenProps {
  item: VocabularyItem
  /** 이 문항을 마쳤다는 통지. 진행을 움직이는 건 호출자(상태 머신)다 */
  onSubmitted: () => void
}

export function VocabularyItemScreen({ item, onSubmitted }: VocabularyItemScreenProps) {
  return (
    <>
      <h1 style={{ fontSize: '20px', fontWeight: 600, margin: 0 }}>{item.prompt}</h1>
      {item.choices.map((choice) => (
        <button
          key={choice.choiceId}
          type="button"
          onClick={() => {
            // choice.choiceId를 여기서 쓰지 않는 것이 지금 단계의 계약이다 — 답을 어디로 보낼지는
            // KAN-13이 정한다. 진행만 앞으로 민다.
            onSubmitted()
          }}
          // ux-ui.md 최소선: 터치 타겟 48dp 이상
          style={{ minHeight: '48px', minWidth: '200px', fontSize: '16px', cursor: 'pointer' }}
        >
          {choice.text}
        </button>
      ))}
    </>
  )
}
