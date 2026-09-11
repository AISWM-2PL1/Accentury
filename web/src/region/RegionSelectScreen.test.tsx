import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RegionCode } from './regions'
import { RegionSelectScreen } from './RegionSelectScreen'

/**
 * 화면 나열 순서 (KAN-202, 2026-09-11 팀장 결정). 2열 그리드의 행 우선 순서라 계약 표
 * `REGIONS`와 다르다 — 왼쪽 열이 서해안 축, 오른쪽 열이 동해안 축이다. 여기 그대로 적는 것은
 * 이 순서가 곧 기획 결정이라서다: 코드가 바뀌면 이 테스트가 그 결정을 되묻게 한다.
 */
const DISPLAY_LABELS = ['서울', '강원', '경기', '충북', '전북', '충남', '전남', '경북', '제주', '경남']

const TITLE = '어느 지역 말씨가 몸에 배어 있나요?'

function renderScreen() {
  const onDone = vi.fn<(region: RegionCode) => void>()
  render(<RegionSelectScreen onDone={onDone} />)
  return { onDone }
}

describe('RegionSelectScreen — 선택지', () => {
  it('지역 열 개가 화면 순서(2열 행 우선)로 나열되고 처음에는 아무것도 골라져 있지 않다', () => {
    renderScreen()

    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(10)
    expect(radios.map((radio) => (radio as HTMLInputElement).labels?.[0]?.textContent)).toEqual(
      DISPLAY_LABELS,
    )
    // 기본 선택이 있으면 그냥 [다음]을 누른 사람의 녹음이 엉뚱한 지역으로 쌓인다
    expect(radios.filter((radio) => (radio as HTMLInputElement).checked)).toHaveLength(0)
  })

  it('제목이 곧 라디오 그룹의 이름이다 — 스크린 리더가 그룹 진입에서 질문을 읽는다', () => {
    renderScreen()

    expect(screen.getByRole('radiogroup', { name: TITLE })).toBeInTheDocument()
  })

  it('지금 사는 곳이 아니라 자란 곳을 묻는다고 밝힌다 (AC: 출신 지역)', () => {
    renderScreen()

    expect(screen.getByText(/지금 사는 곳이 아니라/)).toBeInTheDocument()
    expect(screen.getByText(/결과에는 영향이 없어요/)).toBeInTheDocument()
  })
})

describe('RegionSelectScreen — [다음]', () => {
  it('고르기 전에는 잠겨 있고 눌러도 아무 일도 없다 — 건너뛰기가 없다', () => {
    const { onDone } = renderScreen()

    const next = screen.getByRole('button', { name: '다음' })
    expect(next).toBeDisabled()
    fireEvent.click(next)
    expect(onDone).not.toHaveBeenCalled()
  })

  it('하나를 고르면 열리고, 누르면 그 코드로 한 번 알린다', () => {
    const { onDone } = renderScreen()

    fireEvent.click(screen.getByRole('radio', { name: '제주' }))
    const next = screen.getByRole('button', { name: '다음' })
    expect(next).toBeEnabled()
    // 고른 것은 라디오 상태로도 남는다 — 화살표 키 이동의 출발점이다
    expect(screen.getByRole('radio', { name: '제주' })).toBeChecked()

    fireEvent.click(next)
    expect(onDone).toHaveBeenCalledTimes(1)
    expect(onDone).toHaveBeenCalledWith('JEJU')
  })

  it('다시 고르면 마지막 선택으로 알린다 — 고르기와 확정은 다른 동작이다', () => {
    const { onDone } = renderScreen()

    fireEvent.click(screen.getByRole('radio', { name: '서울' }))
    fireEvent.click(screen.getByRole('radio', { name: '경남' }))
    expect(screen.getByRole('radio', { name: '서울' })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: '경남' })).toBeChecked()

    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(onDone).toHaveBeenCalledTimes(1)
    expect(onDone).toHaveBeenCalledWith('GYEONGNAM')
  })
})
