/**
 * 어휘 문항 화면 (KAN-13 Stage 1 — 선택 UI).
 *
 * 정의가 준 prompt와 choices를 서버 순서 그대로 그리고, 하나를 골라 [다음]을 누르면
 * 고른 choiceId를 호출자에게 넘긴다. 진행을 움직이는 건 호출자(상태 머신)다.
 *
 * 선택지는 네이티브 라디오(input type="radio")다. 진행바가 `<progress>`를 쓰는 것과 같은
 * 이유 — 단일 선택 보장·화살표 키 이동·radiogroup 의미론을 브라우저가 전부 주므로,
 * 버튼 배열에 aria를 손으로 채워 같은 것을 재현할 이유가 없다 (KAN-13 AC: 접근성 라벨).
 *
 * **정오 정보는 이 화면 어디에도 없다.** 정의(testDefinition)에 정답 필드 자체가 없어
 * 화면이 실수로도 노출할 수 없다 — 채점은 서버가 하고 점수는 /result에서 한 번에 공개된다.
 * 서버 제출(POST .../answer) 결선은 Stage 3에서 [다음]과 진행 통지 사이에 끼워 넣는다.
 */

import { useState } from 'react'
import type { VocabularyItem } from './testDefinition'

export interface VocabularyItemScreenProps {
  item: VocabularyItem
  /** [다음]으로 확정한 답의 통지. 진행을 움직이는 건 호출자(상태 머신)다 */
  onSubmit: (choiceId: string) => void
}

export function VocabularyItemScreen({ item, onSubmit }: VocabularyItemScreenProps) {
  // 고른 선택지. [다음]을 누르기 전까지는 자유롭게 바꿀 수 있다 — 라디오의 기본 동작 그대로라
  // "제출 전 변경 허용"에 별도 코드가 없다. 제출 후 잠금은 진행 자체가 다음 문항으로 넘어가
  // 이 컴포넌트가 내려가는 것으로 성립한다 (호출자가 itemId를 key로 준다).
  const [selected, setSelected] = useState<string | null>(null)

  return (
    <>
      <h1 id="vocab-prompt" style={{ fontSize: '20px', fontWeight: 600, margin: 0 }}>
        {item.prompt}
      </h1>
      {/* 문제 문구가 곧 이 라디오 그룹의 이름이다 — 스크린 리더가 "그룹 진입"에서 문제를 읽는다 */}
      <div
        role="radiogroup"
        aria-labelledby="vocab-prompt"
        style={{ display: 'flex', flexDirection: 'column', gap: '8px', width: '100%', maxWidth: '320px' }}
      >
        {/* 정의의 choices 배열 순서 = 화면 순서. 정렬·섞기를 하지 않는 것이 요구사항이다 */}
        {item.choices.map((choice) => {
          const checked = selected === choice.choiceId
          return (
            <label
              key={choice.choiceId}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                // ux-ui.md 최소선: 터치 타겟 48dp 이상 — 글자가 아니라 label 전체가 탭 영역이다
                minHeight: '48px',
                padding: '0 12px',
                fontSize: '16px',
                cursor: 'pointer',
                border: checked ? '2px solid #333' : '1px solid #ccc',
                borderRadius: '8px',
                textAlign: 'left',
              }}
            >
              <input
                type="radio"
                name="vocab-choice"
                value={choice.choiceId}
                checked={checked}
                onChange={() => setSelected(choice.choiceId)}
              />
              {choice.text}
            </label>
          )
        })}
      </div>
      {/*
        선택 전 비활성이 AC 1항이다. disabled면 onClick이 아예 안 불리므로 selected가 null인
        채로 아래 onSubmit에 닿는 경로가 없다 — non-null 단언 대신 가드로 그 사실을 코드에 남긴다.
      */}
      <button
        type="button"
        disabled={selected === null}
        onClick={() => {
          if (selected !== null) onSubmit(selected)
        }}
        style={{ minHeight: '48px', minWidth: '200px', fontSize: '16px', cursor: 'pointer' }}
      >
        다음
      </button>
    </>
  )
}
