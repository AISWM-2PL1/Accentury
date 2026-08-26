import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { ResultScreen, type ResultScreenProps } from './ResultScreen'
import type { RetestControl } from './useRetest'

const API_BASE = 'http://localhost:8080'

/** §3.7 200 본문 대역 */
function readyBody(overrides: Record<string, unknown> = {}) {
  return {
    status: 'READY',
    scores: { intonation: 78, vocabulary: 60, overall: 72 },
    tier: { code: 'HONORARY', name: '명예주민', rank: 4, of: 5 },
    comment: '억양은 거의 토박이인데 단어에서 들켰습니다.',
    share: {
      imageUrl: 'https://static.accentury.app/tier/honorary.png',
      text: '나는 명예주민! 너도 시도해볼래?',
      webTestUrl: 'https://accentury.app/t?c=kko_share',
    },
    testVersion: 'gn-2026.08.1',
    scoreVersion: 'sv-0.3',
    expiresAt: '2026-08-22T03:00:00Z',
    ...overrides,
  }
}

function envelope(code: string, message: string, retryable: boolean) {
  return { code, message, retryable, retryAfterMs: null, correlationId: 'c_test' }
}

function jsonFetch(status: number, body: unknown): FetchLike {
  return async () => ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response
}

/**
 * 재응시 상태 대역 (KAN-34). 기본값은 "누를 수 있고 아직 아무 일도 없었다" —
 * 실제 왕복은 [useRetest]가 소유하므로 이 화면 테스트는 받은 값을 어떻게 그리는지만 본다.
 */
function retestControl(overrides: Partial<RetestControl> = {}): RetestControl {
  return {
    onRetest: vi.fn(),
    disabled: false,
    pending: false,
    message: null,
    retryAfterSec: 0,
    ...overrides,
  }
}

function renderScreen(overrides: Partial<ResultScreenProps> = {}) {
  const props: ResultScreenProps = {
    apiBase: API_BASE,
    sessionId: 'sess-1',
    sessionToken: 'token-1',
    onShare: vi.fn(),
    retest: retestControl(),
    fetchImpl: jsonFetch(200, readyBody()),
    ...overrides,
  }
  render(<ResultScreen {...props} />)
  return props
}

describe('점수 표시', () => {
  it('서버가 준 세 점수를 그대로 그린다 — 재계산하지 않는다 (AC 1항)', async () => {
    // 종합 99는 (78 × 2 + 60) / 3 = 72와 다르다. 화면이 식을 다시 돌렸다면 72가 보인다.
    renderScreen({ fetchImpl: jsonFetch(200, readyBody({ scores: { intonation: 78, vocabulary: 60, overall: 99 } })) })

    expect(await screen.findByText('99')).toBeInTheDocument()
    expect(screen.getByText('78')).toBeInTheDocument()
    expect(screen.getByText('60')).toBeInTheDocument()
    expect(screen.queryByText('72')).not.toBeInTheDocument()
  })

  it('억양과 단어가 한 화면에 같은 형식으로 나온다 (AC 2항)', async () => {
    renderScreen()

    const intonation = await screen.findByRole('progressbar', { name: '억양 점수' })
    const vocabulary = screen.getByRole('progressbar', { name: '단어 점수' })

    // 같은 컴포넌트로 그렸다는 것을 구조로 확인한다 — 클래스와 max가 같아야 한다
    expect(intonation).toHaveAttribute('max', '100')
    expect(vocabulary).toHaveAttribute('max', '100')
    expect(intonation.className).toBe(vocabulary.className)
    expect(intonation).toHaveValue(78)
    expect(vocabulary).toHaveValue(60)
  })

  it('범위를 벗어난 점수가 와도 막대는 가두고 숫자는 그대로 보여준다', async () => {
    // 배포 사고로 벗어난 값이 오면 그림은 깨지지 않되 이상은 눈에 보여야 한다
    renderScreen({
      fetchImpl: jsonFetch(200, readyBody({ scores: { intonation: 140, vocabulary: -10, overall: 72 } })),
    })

    expect(await screen.findByRole('progressbar', { name: '억양 점수' })).toHaveValue(100)
    expect(screen.getByRole('progressbar', { name: '단어 점수' })).toHaveValue(0)
    expect(screen.getByText('140')).toBeInTheDocument()
    expect(screen.getByText('-10')).toBeInTheDocument()
  })

  it('발음·리듬 점수와 백분위는 없다 — MVP 범위 제외다', async () => {
    renderScreen()

    await screen.findByText('명예주민')
    expect(screen.queryByText(/발음/)).not.toBeInTheDocument()
    expect(screen.queryByText(/리듬/)).not.toBeInTheDocument()
    expect(screen.queryByText(/상위|백분위|%/)).not.toBeInTheDocument()
  })
})

describe('등급 표시', () => {
  it('서버가 준 등급명을 그대로 쓴다 — 클라이언트에 등급표가 없다', async () => {
    // 서버가 이름을 바꾸면 화면도 바뀐다. 화면에 표가 있었다면 옛 이름이 남는다.
    renderScreen({
      fetchImpl: jsonFetch(200, readyBody({ tier: { code: 'HONORARY', name: '명예 도민', rank: 4, of: 5 } })),
    })

    expect(await screen.findByRole('heading', { name: '명예 도민' })).toBeInTheDocument()
  })

  it('등급 코멘트와 순위를 함께 보여준다', async () => {
    renderScreen()

    expect(await screen.findByText('억양은 거의 토박이인데 단어에서 들켰습니다.')).toBeInTheDocument()
    expect(screen.getByText('5개 등급 중 4번째')).toBeInTheDocument()
  })

  it('학습 레벨·Lv 표기와 학습 시작 버튼이 없다', async () => {
    renderScreen()

    await screen.findByText('명예주민')
    expect(screen.queryByText(/\bLv\b/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/레벨/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /학습/ })).not.toBeInTheDocument()
  })

  it('결과에 테스트 정의 버전과 점수 버전이 실린다', async () => {
    renderScreen()

    expect(await screen.findByText('gn-2026.08.1 · sv-0.3')).toBeInTheDocument()
  })
})

describe('조회', () => {
  it('§3.7 그대로 요청한다 — GET, 세션 경로, Bearer', async () => {
    const fetchImpl = vi.fn<FetchLike>(jsonFetch(200, readyBody()))
    renderScreen({ fetchImpl })

    await screen.findByText('명예주민')
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/v0/sessions/sess-1/result')
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer token-1' })
  })

  it('조회 중에는 대기 문구를 보여준다', () => {
    renderScreen({ fetchImpl: () => new Promise(() => {}) })

    expect(screen.getByText('결과를 불러오는 중…')).toBeInTheDocument()
    // 대기 화면에 점수가 새어 나가지 않는다
    expect(screen.queryByRole('progressbar', { name: '억양 점수' })).not.toBeInTheDocument()
  })

  it('겹친 요청이 역순으로 돌아와도 뒤늦은 응답이 화면을 덮지 않는다', async () => {
    /*
     * 조회를 다시 태우는 길은 [다시 시도]만이 아니다 — sessionId 같은 입력이 바뀌면 이펙트가
     * 다시 돈다. 버튼은 로딩 상태에서 사라지므로 겹침이 안 생기지만, 이 경로는 앞 요청이 살아
     * 있는 채로 새 요청이 뜬다. 먼저 뜬 요청이 뒤늦게 실패로 돌아왔을 때 새 결과를 덮으면
     * 사용자는 멀쩡한 결과 대신 오류 화면을 본다.
     */
    const pending: Array<(r: Response) => void> = []
    const fetchImpl: FetchLike = () => new Promise<Response>((resolve) => void pending.push(resolve))
    const props: ResultScreenProps = {
      apiBase: API_BASE,
      sessionId: 'sess-1',
      sessionToken: 'token-1',
      onShare: vi.fn(),
      retest: retestControl(),
      fetchImpl,
    }

    const { rerender } = render(<ResultScreen {...props} />)
    rerender(<ResultScreen {...props} sessionId="sess-2" />)
    expect(pending).toHaveLength(2)

    // 나중 요청(sess-2)이 먼저 성공하고, 버려진 앞 요청(sess-1)이 뒤늦게 실패로 돌아온다.
    await act(async () => {
      pending[1]({ ok: true, status: 200, json: async () => readyBody() } as Response)
    })
    await act(async () => {
      pending[0]({
        ok: false,
        status: 500,
        json: async () => envelope('INTERNAL', '잠시 후 다시 시도해 주세요.', true),
      } as Response)
    })

    expect(screen.getByRole('heading', { name: '명예주민' })).toBeInTheDocument()
    expect(screen.queryByText('결과를 불러오지 못했어요')).not.toBeInTheDocument()
  })
})

describe('만료(410) 처리 — AC 4항', () => {
  it('만료는 [다시 시도]가 아니라 [다시 테스트하기]로 보낸다', async () => {
    const body = envelope('RESULT_EXPIRED', '결과 보관 기간(24시간)이 지났습니다. 다시 테스트해 주세요.', false)
    const props = renderScreen({ fetchImpl: jsonFetch(410, body) })

    expect(await screen.findByText('결과 보관 기간이 지났어요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '다시 테스트하기' }))
    expect(props.retest.onRetest).toHaveBeenCalledTimes(1)
  })

  it('만료 문구는 서버 안내를 그대로 싣는다', async () => {
    const body = envelope('RESULT_EXPIRED', '결과 보관 기간(24시간)이 지났습니다. 다시 테스트해 주세요.', false)
    renderScreen({ fetchImpl: jsonFetch(410, body) })

    expect(await screen.findByText(/24시간/)).toBeInTheDocument()
  })
})

describe('실패 처리 — AC 4항', () => {
  it('재시도로 달라질 수 있는 실패에는 [다시 시도]를 준다', async () => {
    const body = envelope('RESULT_NOT_READY', '결과를 준비하고 있습니다.', true)
    let attempt = 0
    const fetchImpl: FetchLike = async () => {
      attempt += 1
      return attempt === 1
        ? ({ ok: false, status: 409, json: async () => body }) as Response
        : ({ ok: true, status: 200, json: async () => readyBody() }) as Response
    }
    renderScreen({ fetchImpl })

    expect(await screen.findByText('결과를 불러오지 못했어요')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    // 두 번째 응답이 READY라 같은 화면이 결과로 바뀐다
    expect(await screen.findByRole('heading', { name: '명예주민' })).toBeInTheDocument()
  })

  it('재시도해도 달라지지 않는 실패는 [다시 테스트하기]만 준다', async () => {
    const body = envelope('SESSION_EXPIRED', '세션이 만료되었습니다. 테스트를 다시 시작해 주세요.', false)
    renderScreen({ fetchImpl: jsonFetch(401, body) })

    expect(await screen.findByText('결과를 불러오지 못했어요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 테스트하기' })).toBeInTheDocument()
  })

  it('실패 문구는 스크린 리더가 스스로 읽는다', async () => {
    renderScreen({ fetchImpl: jsonFetch(500, envelope('INTERNAL_ERROR', '잠시 후 다시 시도해 주세요.', true)) })

    expect(await screen.findByRole('alert')).toHaveTextContent('결과를 불러오지 못했어요')
  })

  it('점수가 빠진 200 응답을 결과로 그리지 않는다', async () => {
    const broken = { ...readyBody(), scores: { intonation: 78, vocabulary: 60 } }
    renderScreen({ fetchImpl: jsonFetch(200, broken) })

    expect(await screen.findByText('결과를 불러오지 못했어요')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '명예주민' })).not.toBeInTheDocument()
  })
})

describe('내보내는 길 — AC 5항', () => {
  it('[친구에게 공유하기]가 결과를 그대로 넘긴다 — 공유 자체는 KAN-30이다', async () => {
    const props = renderScreen()

    fireEvent.click(await screen.findByRole('button', { name: '친구에게 공유하기' }))

    expect(props.onShare).toHaveBeenCalledTimes(1)
    // 공유 카드 자산(imageUrl·text·webTestUrl)이 그대로 실려 나간다
    expect(vi.mocked(props.onShare).mock.calls[0][0].share).toEqual({
      imageUrl: 'https://static.accentury.app/tier/honorary.png',
      text: '나는 명예주민! 너도 시도해볼래?',
      webTestUrl: 'https://accentury.app/t?c=kko_share',
    })
  })

  it('[다시 테스트하기]를 정상 결과 화면에서도 제공한다', async () => {
    const props = renderScreen()

    fireEvent.click(await screen.findByRole('button', { name: '다시 테스트하기' }))

    expect(props.retest.onRetest).toHaveBeenCalledTimes(1)
  })

  it('공유 이미지를 화면에 띄우지 않는다 — 아직 없는 자산이라 로딩에 걸리면 안 된다', async () => {
    renderScreen()

    await screen.findByText('명예주민')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })
})

describe('빈 값 방어', () => {
  it('세션 토큰이 없으면 네트워크를 타지 않고 사용자용 문구를 보여준다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const fetchImpl = vi.fn<FetchLike>()
    renderScreen({ sessionToken: '', fetchImpl })

    expect(await screen.findByText('결과를 불러오지 못했어요')).toBeInTheDocument()
    expect(fetchImpl).not.toHaveBeenCalled()
    // 내부 필드 이름이 화면에 나가지 않는다
    await waitFor(() => expect(screen.queryByText(/sessionToken/)).not.toBeInTheDocument())
    vi.restoreAllMocks()
  })
})

describe('[다시 테스트하기] 잠금과 안내 — KAN-34', () => {
  /** 만료 화면과 하단, 버튼이 있는 두 자리를 같은 검사로 돌린다 */
  const SITES = [
    {
      name: '정상 결과 화면(하단 버튼)',
      fetchImpl: jsonFetch(200, readyBody()),
      settle: () => screen.findByRole('heading', { name: '명예주민' }),
    },
    {
      name: '만료 화면(410, KAN-29 재응시 유도)',
      fetchImpl: jsonFetch(410, envelope('RESULT_EXPIRED', '결과 보관 기간(24시간)이 지났습니다.', false)),
      settle: () => screen.findByText('결과 보관 기간이 지났어요'),
    },
  ]

  for (const site of SITES) {
    describe(site.name, () => {
      it('잠기면 버튼이 죽고 준비 중으로 바뀐다', async () => {
        renderScreen({ fetchImpl: site.fetchImpl, retest: retestControl({ pending: true, disabled: true }) })
        await site.settle()

        const button = screen.getByRole('button', { name: '준비 중…' })
        expect(button).toBeDisabled()
        expect(screen.queryByRole('button', { name: '다시 테스트하기' })).not.toBeInTheDocument()
      })

      it('잠기지 않았으면 눌러서 재응시로 넘어간다', async () => {
        const props = renderScreen({ fetchImpl: site.fetchImpl })
        await site.settle()

        fireEvent.click(screen.getByRole('button', { name: '다시 테스트하기' }))
        expect(props.retest.onRetest).toHaveBeenCalledTimes(1)
      })

      it('실패 문구는 네이티브가 준 그대로 나오고 스크린 리더가 읽는다', async () => {
        renderScreen({
          fetchImpl: site.fetchImpl,
          retest: retestControl({ message: '다시 시작하지 못했어요.', disabled: false }),
        })
        await site.settle()

        // 갈래별 카피를 웹이 따로 들지 않는다 — 받은 문자열이 그대로 화면에 있다
        const notice = screen.getByText('다시 시작하지 못했어요.')
        expect(notice).toBeInTheDocument()
        expect(notice).toHaveAttribute('role', 'alert')
      })

      it('429 대기 중에는 남은 초를 적고 버튼을 잠근다', async () => {
        renderScreen({
          fetchImpl: site.fetchImpl,
          retest: retestControl({
            message: '요청이 많아요. 잠시 후 다시 시도해 주세요.',
            retryAfterSec: 7,
            disabled: true,
          }),
        })
        await site.settle()

        expect(screen.getByText('7초 후 다시 시도할 수 있어요')).toBeInTheDocument()
        expect(screen.getByRole('button', { name: '다시 테스트하기' })).toBeDisabled()
      })

      it('대기가 없으면 카운트다운 줄 자체가 없다', async () => {
        renderScreen({ fetchImpl: site.fetchImpl, retest: retestControl({ message: '다시 시작하지 못했어요.' }) })
        await site.settle()

        expect(screen.queryByText(/초 후 다시 시도할 수 있어요/)).not.toBeInTheDocument()
      })
    })
  }

  it('재시도 가능한 실패 화면에는 [다시 시도]가 그대로 남는다 — 재응시 잠금과 무관하다', async () => {
    renderScreen({
      fetchImpl: jsonFetch(409, envelope('RESULT_NOT_READY', '결과를 준비하고 있습니다.', true)),
      retest: retestControl({ disabled: true, pending: true }),
    })

    expect(await screen.findByRole('button', { name: '다시 시도' })).toBeEnabled()
  })
})

describe('[앱 다운로드] CTA — KAN-31 2단계', () => {
  /** 만료 화면 대역. 브라우저에서 결과가 사라진 사람도 같은 CTA를 만나야 한다 */
  const EXPIRED = jsonFetch(410, envelope('RESULT_EXPIRED', '결과 보관 기간(24시간)이 지났습니다.', false))

  it('웹 단독 실행에서는 스토어로 가는 링크가 주버튼이고 공유가 보조로 내려간다', async () => {
    renderScreen({ storePlatform: 'android' })

    const download = await screen.findByRole('link', { name: '앱 다운로드' })
    // 버튼이 아니라 링크다 — 새 탭·길게 눌러 복사·스크린 리더의 "링크" 안내가 살아 있어야 한다
    expect(download).toHaveAttribute('href', expect.stringContaining('play.google.com'))
    expect(download.className).toContain('btn--primary')
    expect(screen.getByText('Play 스토어로 이동해요')).toBeInTheDocument()

    // 화면당 주버튼은 하나다 — 브라우저의 전환 목표는 설치라 공유가 자리를 내준다
    const share = screen.getByRole('button', { name: '친구에게 공유하기' })
    expect(share.className).toContain('btn--secondary')
    expect(share.className).not.toContain('btn--primary')
  })

  it('아이폰이면 앱스토어로 보내고 안내 문구도 같이 바뀐다', async () => {
    renderScreen({ storePlatform: 'ios' })

    expect(await screen.findByRole('link', { name: '앱 다운로드' })).toHaveAttribute(
      'href',
      expect.stringContaining('apps.apple.com'),
    )
    expect(screen.getByText('App Store로 이동해요')).toBeInTheDocument()
  })

  it('앱 안에서는 다운로드 CTA가 아예 없고 공유가 그대로 주버튼이다', async () => {
    // 이미 앱인 사람에게 앱을 받으라고 하지 않는다 — 그 실행의 전환 목표는 공유다 (KAN-30)
    renderScreen()

    await screen.findByText('명예주민')
    expect(screen.queryByRole('link', { name: '앱 다운로드' })).not.toBeInTheDocument()
    expect(screen.queryByText(/로 이동해요/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '친구에게 공유하기' }).className).toContain('btn--primary')
  })

  it('만료(410) 화면에서도 다운로드와 재응시를 함께 준다', async () => {
    renderScreen({ storePlatform: 'android', fetchImpl: EXPIRED })

    await screen.findByText('결과 보관 기간이 지났어요')
    expect(screen.getByRole('link', { name: '앱 다운로드' })).toHaveAttribute(
      'href',
      expect.stringContaining('play.google.com'),
    )
    // 재응시가 사라지지 않는다. 다만 주버튼 자리는 다운로드가 가져간다
    const retestButton = screen.getByRole('button', { name: '다시 테스트하기' })
    expect(retestButton.className).toContain('btn--secondary')
  })

  it('앱 안 만료 화면은 예전 그대로다 — 재응시가 주버튼이다', async () => {
    renderScreen({ fetchImpl: EXPIRED })

    await screen.findByText('결과 보관 기간이 지났어요')
    expect(screen.queryByRole('link', { name: '앱 다운로드' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 테스트하기' }).className).toContain('btn--primary')
  })

  it('탭하면 계측 훅이 불리고 링크의 이동은 그대로 남는다 (3단계 퍼널 자리)', async () => {
    const onDownloadClick = vi.fn()
    renderScreen({ storePlatform: 'android', onDownloadClick })

    const download = await screen.findByRole('link', { name: '앱 다운로드' })
    fireEvent.click(download)

    expect(onDownloadClick).toHaveBeenCalledTimes(1)
    // 계측이 이동을 대신하지 않는다 — href가 그대로라 클릭을 가로채지 않았다는 뜻이다
    expect(download).toHaveAttribute('href', expect.stringContaining('play.google.com'))
  })

  it('계측 훅을 주지 않아도 링크는 제 일을 한다', async () => {
    renderScreen({ storePlatform: 'unknown' })

    const download = await screen.findByRole('link', { name: '앱 다운로드' })
    fireEvent.click(download)
    // 알 수 없는 플랫폼은 플레이스토어로 보낸다 (storeUrlFor 규칙)
    expect(download).toHaveAttribute('href', expect.stringContaining('play.google.com'))
  })
})
