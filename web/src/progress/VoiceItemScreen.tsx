/**
 * 음성 문항 화면 (KAN-100 Stage 2).
 *
 * 이 화면 자체는 녹음을 하지 않는다 — 마이크를 잡고 파형을 그리는 건 네이티브 녹음 화면(KAN-87)이고,
 * 여기가 하는 일은 "이 문항으로 넘어가라"고 브리지에 알린 뒤 결과가 돌아올 때까지 자리를 지키는 것뿐이다.
 * 결과 수신은 화면 전체(TestFlowScreen)가 받는다 — 문항마다 수신자를 갈아끼우면 전환 도중
 * 결과가 들어왔을 때 받을 사람이 없는 순간이 생긴다.
 */

import { useEffect, useRef, useState } from 'react'
import { startVoiceItem } from '../bridge/bridge'
import { Button, StatusBlock } from '../ui'
import { TYPE_BADGE } from './itemBadge'
import type { VoiceItem } from './testDefinition'

/*
 * 브리지 판정 전후로 나뉘지 않는 단일 대기 문구 (KAN-146).
 * 예전에는 "녹음 화면을 여는 중…" → "녹음 화면에서 진행 중…"으로 한 프레임 만에 문구가 갈아치워졌다.
 * 사용자에게 그 둘은 구분되지 않는 같은 상태("기다리는 중")인데 화면만 두 번 움직여, 음성·어휘가
 * 교차하는 진행에서 전환이 휙휙 바뀐다는 인상을 만들었다. 내부 구현(녹음 화면)을 굳이 이름 붙이지 않는
 * 중립 문구 하나로 고정한다 — 네이티브 [나가기]로 이탈해 돌아왔을 때도 어색하지 않다.
 */
const WAITING_MESSAGE = '잠시만요…'

export interface VoiceItemScreenProps {
  item: VoiceItem
  /** 진행 표기용 1-기반 순번. 네이티브 녹음 화면이 "3/10"을 그리는 데 쓴다 */
  itemNumber: number
  totalItems: number
  /** 브리지가 없는 환경(브라우저 단독 개발)에서만 쓰는 임시 제출 통로 */
  onDevSubmitted: () => void
}

export function VoiceItemScreen({ item, itemNumber, totalItems, onDevSubmitted }: VoiceItemScreenProps) {
  /*
   * 브리지 호출 결과. `null`은 아직 부르기 전이라는 뜻이다 — 호출은 effect에서 일어나므로
   * 첫 렌더에는 결과가 없다. `null`과 `true`는 화면상 같은 대기 문구를 쓰지만 상태로는 계속 구분한다:
   * 재진입 버튼은 브리지가 실제로 받아준 뒤에만 의미가 있고, `false`(브라우저 단독)는 아예 다른 화면이다.
   */
  const [bridgeAccepted, setBridgeAccepted] = useState<boolean | null>(null)
  /*
   * 호출은 문항당 한 번이다. 이 컴포넌트는 호출자가 itemId를 key로 주기 때문에 문항이 바뀔 때만
   * 새로 마운트되지만, StrictMode는 그 마운트 effect를 한 번 더 돌린다. 네이티브에 같은 문항을
   * 두 번 알릴 이유가 없어 막는다 — cleanup에서 되돌리지 않는 것이 이 ref의 요점이다
   * (네이티브 쪽 중복 방어는 Stage 3이 따로 갖는다).
   */
  const started = useRef(false)

  const requestRecording = () =>
    startVoiceItem({
      itemId: item.itemId,
      prompt: item.prompt,
      itemNumber,
      totalItems,
      maxDurationMs: item.maxDurationMs,
      // 네이티브 녹음 화면의 가이드 레인이 그릴 정적 곡선 (KAN-102). 정의 그대로 전달한다.
      guideF0: item.guideF0,
    })

  useEffect(() => {
    if (started.current) return
    started.current = true
    setBridgeAccepted(requestRecording())
    // requestRecording은 렌더마다 새로 만들어지지만 호출은 started로 1회 보장된다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <>
      {/*
        대사 카드. 웹 어휘 문항·네이티브 녹음 화면의 카드와 같은 규격이라 전환에서 튀지 않는다.
        type-headline(26px)은 ux-ui.md §5의 "대사 카드 24sp 이상"을 지키는 크기다.
      */}
      <div className="prompt-card">
        <span className="type-caption prompt-card__badge">{TYPE_BADGE.VOICE}</span>
        <h1 className="type-headline">{item.prompt}</h1>
        <p className="type-label prompt-card__sub">이 문장을 따라 읽어주세요</p>
      </div>
      <div className="item-screen__footer">
        {bridgeAccepted === false ? (
          <>
            {/*
              브리지가 없는 = 브라우저 단독 실행. 에뮬레이터 Chrome으로 진행 화면을 확인하는
              검증 경로를 살려 두려고 개발용 제출 버튼을 남긴다. 앱에서는 이 분기에 오지 않는다.
            */}
            <StatusBlock
              tone="waiting"
              message="녹음 화면을 열 수 없어요 (앱 밖에서 실행 중)"
              action={<Button onClick={onDevSubmitted}>제출 (개발용)</Button>}
            />
          </>
        ) : (
          <>
            {/*
              호출 직전(`null`)과 호출이 받아들여진 뒤(`true`)가 같은 문구를 공유한다. 분기를 이렇게
              뒤집어 둔 이유는 이 <p>가 두 상태에서 같은 위치의 같은 노드로 남아야 React가 교체하지 않고,
              그래야 전환 도중 문구가 흔들리지 않기 때문이다.
              네이티브 녹음 화면이 이 WebView 위를 덮으므로, 이 뷰가 실제로 보이는 건 전환 순간·
              결과를 기다리는 복귀 직후·그리고 사용자가 녹음 화면에서 [나가기]로 이탈한 뒤다.
            */}
            <StatusBlock tone="waiting" message={WAITING_MESSAGE} />
            {bridgeAccepted === true && (
              /*
                [나가기] 이탈 뒤의 유일한 재진입 통로. 네이티브는 이탈을 웹에 알리지 않으므로(계약
                최소 표면) 웹은 이탈과 "결과 대기 중"을 구분할 수 없다 — 대신 이 버튼은 녹음 중엔
                네이티브 화면에 가려 누를 수 없고, 눌리더라도 네이티브 중복 방어(Stage 3)가 무시하므로
                항상 노출해도 안전하다. 이 버튼이 없으면 이탈한 사용자는 이 문항에서 빠져나올 수 없다.
              */
              <Button onClick={requestRecording} style={{ width: '100%' }}>
                녹음 화면 다시 열기
              </Button>
            )}
          </>
        )}
      </div>
    </>
  )
}
