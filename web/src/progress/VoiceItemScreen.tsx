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
import type { VoiceItem } from './testDefinition'

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
   * 첫 렌더에는 결과가 없다. 성공을 미리 가정하지 않는 이유: 그러면 "녹음 화면에서 진행 중"이
   * 아직 아무것도 알리지 않은 상태에서 먼저 나가고, 화면에 보이는 문구가 실제 상태보다 앞선다.
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
      <h1 style={{ fontSize: '20px', fontWeight: 600, margin: 0 }}>{item.prompt}</h1>
      {bridgeAccepted === null ? (
        // 호출 직전의 한 프레임. effect가 바로 뒤따르므로 사람 눈에는 걸리지 않는다
        <p style={{ fontSize: '14px', margin: 0 }}>녹음 화면을 여는 중…</p>
      ) : bridgeAccepted ? (
        <>
          {/*
            네이티브 녹음 화면이 이 WebView 위를 덮으므로, 이 뷰가 실제로 보이는 건 전환 순간·
            결과를 기다리는 복귀 직후·그리고 사용자가 녹음 화면에서 [나가기]로 이탈한 뒤다.
          */}
          <p style={{ fontSize: '14px', margin: 0 }}>녹음 화면에서 진행 중…</p>
          {/*
            [나가기] 이탈 뒤의 유일한 재진입 통로. 네이티브는 이탈을 웹에 알리지 않으므로(계약
            최소 표면) 웹은 이탈과 "결과 대기 중"을 구분할 수 없다 — 대신 이 버튼은 녹음 중엔
            네이티브 화면에 가려 누를 수 없고, 눌리더라도 네이티브 중복 방어(Stage 3)가 무시하므로
            항상 노출해도 안전하다. 이 버튼이 없으면 이탈한 사용자는 이 문항에서 빠져나올 수 없다.
          */}
          <button
            type="button"
            onClick={requestRecording}
            style={{ minHeight: '48px', minWidth: '160px', fontSize: '16px', cursor: 'pointer' }}
          >
            녹음 화면 다시 열기
          </button>
        </>
      ) : (
        <>
          {/*
            브리지가 없는 = 브라우저 단독 실행. 에뮬레이터 Chrome으로 진행 화면을 확인하는
            검증 경로를 살려 두려고 개발용 제출 버튼을 남긴다. 앱에서는 이 분기에 오지 않는다.
          */}
          <p style={{ fontSize: '13px', margin: 0, color: '#666' }}>
            녹음 화면을 열 수 없어요 (앱 밖에서 실행 중)
          </p>
          <button
            type="button"
            onClick={onDevSubmitted}
            style={{ minHeight: '48px', minWidth: '160px', fontSize: '16px', cursor: 'pointer' }}
          >
            제출 (개발용)
          </button>
        </>
      )}
    </>
  )
}
