/**
 * 어휘 문항 화면 (KAN-13).
 *
 * 정의가 준 prompt와 choices를 서버 순서 그대로 그리고, 하나를 골라 [다음]을 누르면
 * 답안을 서버에 제출한 뒤([submitAnswer]) 성공했을 때만 진행 통지([onSubmitted])를 보낸다.
 * 진행을 움직이는 건 호출자(상태 머신)다.
 *
 * 선택지는 네이티브 라디오(input type="radio")다. 진행바가 `<progress>`를 쓰는 것과 같은
 * 이유 — 단일 선택 보장·화살표 키 이동·radiogroup 의미론을 브라우저가 전부 주므로,
 * 버튼 배열에 aria를 손으로 채워 같은 것을 재현할 이유가 없다 (KAN-13 AC: 접근성 라벨).
 *
 * ## 멱등 키의 수명 (AC: 중복 생성 없는 재시도)
 *
 * 키는 "지금 고른 답" 단위로 산다. 같은 답의 재시도는 같은 키로 나가고(서버가 재전송으로
 * 알아본다), 실패 후 답을 바꾸면 새 키다 — 같은 키로 다른 답을 보내면 서버가 400으로
 * 거절하기 때문이다(§3.5). 이 규칙이 선택 상태와 함께 움직여서 키가 이 컴포넌트 소유다.
 *
 * **정오 정보는 이 화면 어디에도 없다.** 정의(testDefinition)에 정답 필드 자체가 없어
 * 화면이 실수로도 노출할 수 없다 — 채점은 서버가 하고 점수는 /result에서 한 번에 공개된다.
 */

import { useRef, useState } from 'react'
import type { VocabularyItem } from './testDefinition'
import { newIdempotencyKey, type VocabSubmitResult } from './submitVocabAnswer'
import { Button } from '../ui'
import { CheckIcon } from '../ui/icons'
import { itemCaption } from './itemBadge'

export interface VocabularyItemScreenProps {
  item: VocabularyItem
  /**
   * 카드 캡션에 그릴 1-기반 순번. **전체 문항 기준**이다 (음성 문항 화면과 같은 규칙) —
   * 어휘 안에서의 1~5로 부르면 음성과 어휘를 오갈 때 번호가 뒤로 돌아간 것처럼 보인다.
   *
   * 세지 않고 받는 이유는 이 화면이 진행을 모르기 때문이다. 진행을 움직이는 것도, 지금이
   * 몇 번째인지 아는 것도 상태 머신을 든 호출자다.
   */
  itemNumber: number
  totalItems: number
  /** 답안 제출. 결과가 SAVED든 ALREADY_ANSWERED든 "서버에 답이 있다"는 뜻이다 */
  submitAnswer: (choiceId: string, idempotencyKey: string) => Promise<VocabSubmitResult>
  /** 제출이 성공한 뒤의 진행 통지. 진행을 움직이는 건 호출자(상태 머신)다 */
  onSubmitted: () => void
}

export function VocabularyItemScreen({
  item,
  itemNumber,
  totalItems,
  submitAnswer,
  onSubmitted,
}: VocabularyItemScreenProps) {
  // 고른 선택지. 제출 중이 아니라면 자유롭게 바꿀 수 있다 — 실패 후에도 바꿔서 다시 낼 수 있다.
  const [selected, setSelected] = useState<string | null>(null)
  // 제출 진행 중 = 화면 잠금. 성공하면 풀지 않는다 — 호출자가 다음 문항으로 넘겨 이 컴포넌트가
  // 내려가므로, 그 사이에 풀면 [다음] 연타가 두 번째 제출을 만들 틈이 생긴다.
  const [submitting, setSubmitting] = useState(false)
  // 직전 제출 실패의 사용자 문구 (서버 봉투의 한국어 message 그대로)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  // 답 → 멱등 키. 상태가 아니라 ref인 이유: 키는 렌더에 안 보이고, 제출 시점에만 읽고 쓴다
  const keyForChoice = useRef<{ choiceId: string; key: string } | null>(null)

  const submit = async () => {
    // disabled 가드와 겹치지만 남겨 둔다 — 비동기 제출 도중 들어오는 호출은 disabled로 못 막는다
    if (selected === null || submitting) return

    setSubmitting(true)
    setErrorMessage(null)
    // 키 생성까지 try 안이다 — 여기서 동기로 터지면 rejection이 아무 데도 안 잡혀 버튼이
    // "눌러도 아무 일 없는" 상태가 된다 (crypto.randomUUID 부재로 실제 발생했던 증상)
    try {
      // 같은 답의 재시도면 키 재사용, 답이 바뀌었으면 새 키 (헤더 주석의 수명 규칙)
      if (keyForChoice.current?.choiceId !== selected) {
        keyForChoice.current = { choiceId: selected, key: newIdempotencyKey() }
      }
      await submitAnswer(selected, keyForChoice.current.key)
      onSubmitted()
    } catch (error: unknown) {
      setErrorMessage(error instanceof Error ? error.message : String(error))
      setSubmitting(false)
    }
  }

  return (
    <>
      {/*
        질문 카드. 음성 문항의 대사 카드와 같은 규격이다(시안) - 두 문항 유형이 번갈아
        나오는데 카드 크기가 다르면 전환마다 화면이 들썩인다.
      */}
      <div className="prompt-card">
        {/* "7 / 10 · 이 말은 무슨 뜻일까요?" — 자리와 할 일을 한 줄로 (아트보드 `Vocab.dc.html`) */}
        <span className="type-caption prompt-card__badge">
          {itemCaption('VOCABULARY', itemNumber, totalItems)}
        </span>
        <h1 id="vocab-prompt" className="type-title">
          {item.prompt}
        </h1>
      </div>

      {/* 문제 문구가 곧 이 라디오 그룹의 이름이다 — 스크린 리더가 "그룹 진입"에서 문제를 읽는다 */}
      <div className="choice-list" role="radiogroup" aria-labelledby="vocab-prompt">
        {/* 정의의 choices 배열 순서 = 화면 순서. 정렬·섞기를 하지 않는 것이 요구사항이다 */}
        {item.choices.map((choice) => {
          const checked = selected === choice.choiceId
          const classes = [
            'choice',
            checked ? 'choice--selected' : '',
            submitting ? 'choice--locked' : '',
          ]
            .filter(Boolean)
            .join(' ')
          return (
            <label key={choice.choiceId} className={classes}>
              <input
                className="choice__radio"
                type="radio"
                name="vocab-choice"
                value={choice.choiceId}
                checked={checked}
                // 제출 중 잠금 — 요청이 나간 답과 화면의 답이 달라지는 순간을 만들지 않는다
                disabled={submitting}
                onChange={() => setSelected(choice.choiceId)}
              />
              <span>{choice.text}</span>
              {/*
                고른 것을 색 말고도 알린다. 표식(라디오)을 눈에서 지우고 나면 선택/미선택의
                차이가 색상뿐인데, 두 상태의 명도 차이는 1.2 정도라 색각 이상에서는 구분이
                어렵다 (WCAG 1.4.1). 시안이 오른쪽에 아이콘을 두던 자리를 그대로 쓴다.
                정답이 아니라 "내가 고른 것" 표시라 정오 미노출(KAN-13)과는 무관하다.
              */}
              {checked && (
                <span className="choice__check">
                  <CheckIcon />
                </span>
              )}
            </label>
          )
        })}
      </div>

      <div className="item-screen__footer">
        {errorMessage !== null && (
          // 서버 봉투의 한국어 message 그대로. 비난 없는 카피 톤은 봉투(ErrorCode) 쪽 책임이다
          <p
            role="alert"
            className="type-caption"
            style={{
              color: 'var(--color-destructive-on-surface)',
              textAlign: 'center',
              marginBottom: 'var(--space-3)',
            }}
          >
            {errorMessage}
          </p>
        )}
        {/*
          선택 전 비활성이 AC 1항이다. disabled면 onClick이 아예 안 불리므로 selected가 null인
          채로 submit에 닿는 경로가 없다 — 그래도 submit 안의 가드를 남기는 이유는 위 주석 참조.
        */}
        <Button
          disabled={selected === null || submitting}
          onClick={() => void submit()}
          style={{ width: '100%' }}
        >
          {submitting ? '제출 중…' : errorMessage !== null ? '다시 시도' : '다음'}
        </Button>
      </div>
    </>
  )
}
