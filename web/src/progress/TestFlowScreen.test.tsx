import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { TestFlowScreen } from './TestFlowScreen'
import type { FetchLike } from './fetchTestDefinition'
import { PROGRESS_SNAPSHOT_KEY, type SnapshotStorage } from './progressSnapshot'
import type { TestDefinition, TestItem } from './testDefinition'

const TEST_VERSION = 'gn-2026.08.1'
const API_BASE = 'http://localhost:8080'

function item(seq: number): TestItem {
  if (seq % 2 === 1) {
    return {
      itemId: `item-${seq}`,
      seq,
      type: 'VOICE',
      prompt: `음성 문항 ${seq}`,
      maxDurationMs: 10_000,
      guideF0: { unit: 'semitone', frameIntervalMs: 10, values: [0, 1] },
    }
  }
  return {
    itemId: `item-${seq}`,
    seq,
    type: 'VOCABULARY',
    prompt: `어휘 문항 ${seq}`,
    choices: [{ choiceId: 'c1', text: '보기1' }],
  }
}

function tenItemDefinition(): TestDefinition {
  return {
    testVersion: TEST_VERSION,
    scoreVersion: 'sv-0.3',
    dialect: 'GYEONGNAM',
    estimatedDurationSec: 180,
    items: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(item),
  }
}

/** 정의를 돌려주는 fetch 대역 (Response 대역은 fetchTestDefinition.test.ts와 같은 방식) */
function okFetch(): ReturnType<typeof vi.fn<FetchLike>> {
  return vi.fn<FetchLike>(async () => ({
    ok: true,
    status: 200,
    json: async () => tenItemDefinition(),
  }) as Response)
}

function memoryStorage(): SnapshotStorage {
  const map = new Map<string, string>()
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  }
}

function renderScreen(fetchImpl: FetchLike, storage: SnapshotStorage = memoryStorage()) {
  return render(
    <TestFlowScreen apiBase={API_BASE} testVersion={TEST_VERSION} storage={storage} fetchImpl={fetchImpl} />,
  )
}

/**
 * 현재 문항의 임시 [다음] 버튼을 누른다.
 * `element.click()`이 아니라 fireEvent를 쓰는 이유: fireEvent는 act로 감싸 리렌더까지
 * 흘려보내므로, 클릭 직후 동기적으로 다음 문항을 단언할 수 있다.
 */
function tapNext() {
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
}

describe('정의 로딩', () => {
  it('로딩 중 안내를 보이다가 첫 문항으로 전환된다', async () => {
    const fetchImpl = okFetch()
    renderScreen(fetchImpl)

    expect(screen.getByText('문항을 불러오는 중…')).toBeInTheDocument()

    expect(await screen.findByText('음성 문항 1')).toBeInTheDocument()
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls[0][0]).toBe(`${API_BASE}/v0/tests/${TEST_VERSION}`)
  })

  it('로딩에 실패하면 안내와 [다시 시도]를 보이고, 재시도가 성공하면 진행으로 넘어간다', async () => {
    const fetchImpl = vi
      .fn<FetchLike>()
      .mockImplementationOnce(async () => ({ ok: false, status: 503, json: async () => ({}) }) as Response)
      .mockImplementationOnce(
        async () => ({ ok: true, status: 200, json: async () => tenItemDefinition() }) as Response,
      )
    renderScreen(fetchImpl)

    expect(await screen.findByText('문항을 불러오지 못했어요')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 503/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('음성 문항 1')).toBeInTheDocument()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })
})

describe('문항 진행', () => {
  it('진행바가 첫 문항을 1/10으로 보여준다 (endowed progress)', async () => {
    renderScreen(okFetch())
    await screen.findByText('음성 문항 1')

    const bar = screen.getByRole('progressbar', { name: '문항 진행률' })
    expect(bar).toHaveAttribute('value', '1')
    expect(bar).toHaveAttribute('max', '10')
    expect(screen.getByText('1/10')).toBeInTheDocument()
  })

  it('유형 뱃지가 문항 유형을 따라간다', async () => {
    renderScreen(okFetch())
    await screen.findByText('음성 문항 1')
    expect(screen.getByText('🎤 음성 문항')).toBeInTheDocument()

    tapNext()

    expect(await screen.findByText('어휘 문항 2')).toBeInTheDocument()
    expect(screen.getByText('📝 단어 문항')).toBeInTheDocument()
  })

  it('[다음]을 누르면 다음 문항과 2/10이 된다', async () => {
    renderScreen(okFetch())
    await screen.findByText('음성 문항 1')

    tapNext()

    expect(screen.getByText('어휘 문항 2')).toBeInTheDocument()
    expect(screen.getByText('2/10')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '문항 진행률' })).toHaveAttribute('value', '2')
  })

  it('마지막 문항을 제출하면 분석 대기 자리 표시로 넘어간다', async () => {
    renderScreen(okFetch())
    await screen.findByText('음성 문항 1')

    for (let i = 0; i < 10; i += 1) tapNext()

    expect(screen.getByText('분석 대기 화면 (KAN-14 예정)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument()
  })

  it('진행 중 저장된 스냅샷으로 다시 열면 그 문항에서 이어진다', async () => {
    const storage = memoryStorage()
    const { unmount } = renderScreen(okFetch(), storage)
    await screen.findByText('음성 문항 1')
    tapNext()
    tapNext()
    unmount()

    expect(JSON.parse(storage.getItem(PROGRESS_SNAPSHOT_KEY)!).submittedItemIds).toEqual([
      'item-1',
      'item-2',
    ])

    renderScreen(okFetch(), storage)

    expect(await screen.findByText('음성 문항 3')).toBeInTheDocument()
    expect(screen.getByText('3/10')).toBeInTheDocument()
  })
})

describe('폴링 부재 — 문항 진행 중에는 요청이 없다 (KAN-14 규칙 2항)', () => {
  it('정의 조회 1회 외에 추가 요청이 발생하지 않는다', async () => {
    const fetchImpl = okFetch()
    const globalFetch = vi.fn()
    vi.stubGlobal('fetch', globalFetch)
    try {
      renderScreen(fetchImpl)
      await screen.findByText('음성 문항 1')

      for (let i = 0; i < 10; i += 1) tapNext()

      expect(fetchImpl).toHaveBeenCalledTimes(1)
      expect(globalFetch).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
