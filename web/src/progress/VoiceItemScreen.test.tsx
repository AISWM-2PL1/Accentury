import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { VoiceItemScreen } from './VoiceItemScreen'
import type { VoiceItem } from './testDefinition'

/** 더미 확정본의 음성 문항 모양 그대로 (KAN-102 가이드 곡선 포함) */
function voiceItem(): VoiceItem {
  return {
    itemId: 'v1',
    seq: 1,
    type: 'VOICE',
    prompt: '"밥 뭇나?"를 평소 말투로 읽어 주세요',
    maxDurationMs: 10_000,
    guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
  }
}

/** 네이티브 브리지가 붙은 앱 환경. 돌려주는 spy가 startVoiceItem 호출을 받는다 */
function stubBridge() {
  const startVoiceItem = vi.fn()
  window.AccenturyBridge = {
    requestMicPermission: vi.fn(),
    startVoiceItem,
    getContractVersion: () => 1,
  }
  return startVoiceItem
}

function renderScreen() {
  const onDevSubmitted = vi.fn<() => void>()
  render(<VoiceItemScreen item={voiceItem()} itemNumber={1} totalItems={10} onDevSubmitted={onDevSubmitted} />)
  return { onDevSubmitted }
}

afterEach(() => {
  delete window.AccenturyBridge
})

describe('대기 문구 단일화 (KAN-146)', () => {
  it('브리지가 받아준 뒤에도 대기 문구는 중립 문구 하나뿐이다', () => {
    stubBridge()

    renderScreen()

    expect(screen.getByText('잠시만요…')).toBeInTheDocument()
    // 구현 이름을 노출하던 옛 2단계 문구는 어느 쪽도 남지 않는다
    expect(screen.queryByText('녹음 화면을 여는 중…')).not.toBeInTheDocument()
    expect(screen.queryByText('녹음 화면에서 진행 중…')).not.toBeInTheDocument()
  })

  /*
   * 이 티켓의 핵심 회귀 방어선이다. "문구가 두 개"인 것 자체보다, 마운트 직후 브리지 판정이
   * 들어오면서 화면의 글자가 한 번 갈아치워지는 것이 문제였다. 최종 화면만 보는 단언으로는
   * 그 중간 교체를 잡을 수 없으므로, 렌더가 만들어 낸 DOM 변경 기록을 직접 들여다본다.
   * 지워진 텍스트 노드나 덮어써진 글자가 하나라도 있으면 전환 도중 화면이 움직였다는 뜻이다.
   */
  it('브리지 판정 전후로 화면의 글자가 교체되지 않는다', () => {
    stubBridge()
    const observer = new MutationObserver(() => {})
    observer.observe(document.body, {
      subtree: true,
      childList: true,
      characterData: true,
      characterDataOldValue: true,
    })

    renderScreen()

    const replaced: string[] = []
    for (const record of observer.takeRecords()) {
      if (record.type === 'characterData') {
        replaced.push(record.oldValue ?? '')
        continue
      }
      record.removedNodes.forEach((node) => {
        const text = node.textContent?.trim()
        if (text) replaced.push(text)
      })
    }
    observer.disconnect()

    expect(replaced).toEqual([])
  })

  it('브리지가 받아주면 이탈 복구용 재진입 버튼을 남긴다', () => {
    const startVoiceItem = stubBridge()
    renderScreen()

    fireEvent.click(screen.getByRole('button', { name: '녹음 화면 다시 열기' }))

    expect(startVoiceItem).toHaveBeenCalledTimes(2)
  })

  it('브리지가 없으면(브라우저 단독) 대기 뷰 대신 폴백만 남는다', () => {
    const { onDevSubmitted } = renderScreen()

    expect(screen.getByText('녹음 화면을 열 수 없어요 (앱 밖에서 실행 중)')).toBeInTheDocument()
    // 대기 문구와 폴백은 배타적이다 — 둘이 겹치면 앱 밖 실행이 "기다리는 중"으로 보인다
    expect(screen.queryByText('잠시만요…')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '녹음 화면 다시 열기' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '제출 (개발용)' }))

    expect(onDevSubmitted).toHaveBeenCalledTimes(1)
  })
})
