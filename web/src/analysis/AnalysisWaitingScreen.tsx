/**
 * 분석 대기 화면 (KAN-14 Stage 3). 마지막 문항 제출과 결과 화면 사이를 잇는다.
 *
 * ## 점수가 없는 대기 화면
 *
 * 이 화면은 진행률·품질·오류만 그린다. 점수를 숨기는 게 아니라 **가진 적이 없다** —
 * `/analyses`도 `/complete`도 점수를 주지 않는 서버 계약이라(§3.4·§3.6), 여기서 중간
 * 점수가 새는 경로 자체가 없다 (KAN-12).
 *
 * ## 진행률 분모는 10이다
 *
 * `/analyses`는 음성 5문항만 준다. 하지만 사용자가 방금 푼 것은 10문항이고, 대기 화면에서
 * 분모가 5로 바뀌면 진행이 되감긴 것처럼 보인다. 어휘 5문항은 이 화면에 도달한 시점에
 * 이미 서버에 저장돼 있으므로(그렇지 않으면 `/complete`가 422를 준다) 완료로 세고,
 * 분모는 문항 진행 화면과 같은 10을 쓴다 — 시도를 몇 번 했든 분모는 움직이지 않는다.
 *
 * ## [테스트 종료]를 주지 않는다 — AC와 다른 선택
 *
 * KAN-14 AC는 "타임아웃 시 재시도와 **테스트 종료** 선택을 제공한다"고 적혀 있다. 그러나
 * 그 뒤 KAN-147(2026-08-19)에서 **테스트 중 이탈 버튼을 전부 걷어내기로 결정**했고,
 * 정식 이탈·복구 UX는 KAN-39(업로드 상태 화면 디자인)에서 한 번에 설계하기로 했다.
 * 여기서만 이탈 버튼을 되살리면 방금 지운 것을 다시 심는 셈이라, 늦은 결정을 따라
 * [다시 시도]만 준다. 이 화면의 이탈 경로는 KAN-39에서 다른 화면들과 함께 붙는다.
 */

import { Fragment, useEffect, useRef } from 'react'
import { Button, StatusBlock } from '../ui'
import { CheckSmallIcon } from '../ui/icons'
import { WaitingHero } from '../ui/illustrations/WaitingHero'
import { ANALYSIS_STAGES, analysisStage } from './analysisStage'
import type { VoiceItem } from '../progress/testDefinition'
import type { FetchLike } from '../progress/fetchTestDefinition'
import type { AnalysisItem, AnalysisItemStatus } from './fetchAnalysisStatuses'
import { useAnalysisPolling } from './useAnalysisPolling'

export interface AnalysisWaitingScreenProps {
  apiBase: string
  sessionId: string
  /** 세션 토큰 (Bearer 없이 값만) */
  sessionToken: string
  /** 정의의 음성 문항. 순번 표기와 재녹음 대상 판정에 쓴다 */
  voiceItems: readonly WaitingVoiceItem[]
  /** 진행률 분모. 정의의 전체 문항 수(10)를 그대로 받는다 */
  totalItems: number
  /** 결과가 확정됐다. 화면 이동은 호출자 몫이다 (App의 진입 쿼리 계약을 화면이 알 필요가 없다) */
  onReady: () => void
  /**
   * 이 문항을 다시 녹음한다. 없으면 재녹음 버튼을 그리지 않는다 —
   * 네이티브 결선이 없는 브라우저 단독 실행에서 눌러도 아무 일 없는 버튼을 두지 않기 위해서다.
   */
  onRetake?: (itemId: string) => void
  /**
   * 값이 바뀌면 폴링을 처음부터 다시 시작한다. 재녹음 결과가 네이티브에서 돌아왔다는 신호다.
   *
   * 콜백이 아니라 숫자인 이유: 네이티브 결과 수신 지점(`installItemResultReceiver`)은 화면
   * 전체가 하나만 설치한다 — 이 화면이 자기 수신자를 따로 걸면, 마운트 순서상 부모 것이
   * 나중에 설치돼 덮어쓴다. 그래서 수신은 부모가 하고, 이 화면은 "무언가 돌아왔다"는 값의
   * 변화만 본다.
   */
  refreshNonce?: number
  fetchImpl?: FetchLike
}

/**
 * 대기 화면이 한 줄로 그리는 음성 문항.
 *
 * `itemNumber`를 문항 배열의 인덱스로 만들지 않고 받아 오는 이유: 이 번호는 **전체 10문항
 * 기준**이어야 한다. 네이티브 녹음 화면이 "7 / 10"을 그리므로, 목록이 "음성 4번"이라고
 * 부르던 문항을 눌렀을 때 다음 화면이 다른 숫자를 보이면 사용자는 다른 문항으로 간 줄 안다.
 * 음성 안에서의 순서(1~5)는 이 화면 안에서만 통하는 번호라 쓰지 않는다.
 */
export interface WaitingVoiceItem {
  item: VoiceItem
  /** 전체 문항 기준 1-기반 순번 */
  itemNumber: number
}

/** 사용자에게 보이는 상태 문구. 코드 이름을 그대로 노출하지 않는다 */
const STATUS_LABEL: Record<AnalysisItemStatus, string> = {
  NOT_SUBMITTED: '녹음 필요',
  PROCESSING: '분석 중',
  COMPLETED: '완료',
  RETRYABLE_FAILED: '다시 녹음이 필요해요',
  FAILED: '분석 실패',
}

/**
 * [다시 녹음]을 주는 상태.
 *
 * **FAILED가 여기 있는 것은 서버 결정을 따른 것이다.** 처음에는 "다시 녹음해도 같은 결과"라고
 * 보고 뺐는데, 그건 이 파일의 자체 추론이었고 명문화된 결정과 어긋났다 —
 * `CompletionJudge`는 FAILED도 재녹음 대상(`retakeItems`)으로 묶는다(2026-08-13 확정):
 * 새 시도가 세션 안에서 유일한 복구 경로이고, 무익한 재녹음이 반복되면 시도 상한(§2.5) →
 * 429 → 재응시(§3.1)로 수렴하기 때문이다.
 *
 * 뺀 채로 두면 `/complete`가 409로 "다시 녹음해라"라고 말하는데 그 문항에는 버튼이 없는
 * 막다른 길이 된다 — 폴링은 멎고 [다시 시도]도 [테스트 종료]도 없어 앱을 끄는 것 외에
 * 방법이 없다 (PR #41 리뷰, 2026-08-24).
 */
const RETAKEABLE: readonly AnalysisItemStatus[] = ['RETRYABLE_FAILED', 'FAILED', 'NOT_SUBMITTED']

/** 품질 판정이 정상일 때의 코드. 이 값이면 화면에 적지 않는다 — 전부 "OK"인 목록은 정보가 아니다 */
const QUALITY_OK = 'OK'

export function AnalysisWaitingScreen({
  apiBase,
  sessionId,
  sessionToken,
  voiceItems,
  totalItems,
  onReady,
  onRetake,
  refreshNonce = 0,
  fetchImpl,
}: AnalysisWaitingScreenProps) {
  const { status, items, lastError, restart } = useAnalysisPolling({
    apiBase,
    sessionId,
    sessionToken,
    fetchImpl,
  })

  // 결과 확정은 화면 전환으로 이어진다. 렌더 중이 아니라 이펙트에서 부르는 이유는,
  // 호출자가 이 콜백에서 상태를 바꾸거나 페이지를 옮기기 때문이다.
  useEffect(() => {
    if (status.kind === 'READY') onReady()
  }, [status.kind, onReady])

  /*
   * 재녹음 결과가 돌아왔다. 새 시도가 서버에서 돌기 시작했으므로 폴링을 처음부터 다시 세운다 —
   * 예산도 함께 초기화된다. 사용자가 방금 행동했는데 이전 대기에서 쓴 시간을 계속 물리면,
   * 재녹음하자마자 [다시 시도]를 만나는 일이 생긴다.
   *
   * 마운트 시점 값은 신호가 아니다. 문항 진행 중에도 결과 수신마다 이 값이 오르므로, 화면이
   * 처음 서는 순간의 값으로 폴링을 한 번 더 돌리면 첫 회차가 두 번 나간다.
   */
  const seenNonce = useRef(refreshNonce)
  useEffect(() => {
    if (refreshNonce === seenNonce.current) return
    seenNonce.current = refreshNonce
    restart()
  }, [refreshNonce, restart])

  // 음성 문항은 정의 순서(seq)가 정본이다. 서버도 같은 순서로 주지만, 순번 표기를 정의에서
  // 뽑아야 "7번 문항"이 문항 진행 화면·네이티브 녹음 화면에서 본 번호와 같아진다.
  const rows = voiceItems.map(({ item, itemNumber }) => ({
    item,
    itemNumber,
    status: items.find((analysis) => analysis.itemId === item.itemId) ?? null,
  }))

  const completedVoice = rows.filter((row) => row.status?.status === 'COMPLETED').length
  /*
   * 어휘 문항은 이 화면에 도달한 시점에 전부 저장돼 있다. 분모에서 음성 문항을 빼면 남는
   * 수가 곧 어휘 수이므로, 정의를 한 번 더 뒤지지 않고 그 차이를 완료로 센다.
   */
  const completed = Math.max(totalItems - voiceItems.length, 0) + completedVoice

  /*
   * 서버는 "사용자가 움직여야 한다"고 했는데 이 화면에는 누를 것이 하나도 없는 경우.
   *
   * 가장 현실적인 경로는 422 `RESULT_INCOMPLETE`의 `missingItems`에 **어휘 문항**만 들어오는
   * 때다(스냅샷 복원 어긋남 등). 이 목록은 음성 5문항만 그리므로 그 문항은 화면에 나타나지도
   * 않는다. 안내만 있고 대상이 없으면 사용자는 갇힌다 — 폴링은 멎었고, EXHAUSTED가 아니라
   * [다시 시도]도 없고, 이탈 버튼은 KAN-147 결정으로 없다.
   *
   * 이 경우 문구를 갈라 앱 재시작으로 안내한다. 되살릴 방법이 이 화면에 없다는 사실을 숨기고
   * "다시 녹음해 주세요"라고 말하는 것보다, 할 수 있는 일을 말해 주는 편이 낫다.
   */
  const actionRequired = status.kind === 'ACTION_REQUIRED'
  const hasActionableRow =
    onRetake !== undefined && rows.some((row) => row.status !== null && isRetakeable(row.status))
  const deadEnd = actionRequired && !hasActionableRow

  useEffect(() => {
    if (!deadEnd || status.kind !== 'ACTION_REQUIRED') return
    // 서버가 짚은 문항을 진단에 남긴다 — 이 목록과 화면에 그려진 음성 문항이 어긋난
    // 경우라, 무엇이 빠졌는지가 원인 추적의 시작점이다.
    console.error('[analysis] 사용자가 손댈 수 있는 문항이 없습니다', {
      reason: status.reason,
      itemIds: status.itemIds,
      onRetakeAvailable: onRetake !== undefined,
    })
  }, [deadEnd, status, onRetake])

  /*
   * 시안의 세 칸(곡선 추출 / 분포 비교 / 등급 계산). 값은 폴링이 이미 들고 있는 상태에서
   * 파생한다 — 폴링에 단계라는 개념을 새로 심지 않는다 (`analysisStage.ts` 헤더).
   */
  const stage = analysisStage({
    ready: status.kind === 'READY',
    completedVoice,
    totalVoice: voiceItems.length,
  })
  /*
   * 기다리는 중인가. 오류·행동 요구 상태에서는 그림과 단계 대신 무엇을 해야 하는지가 화면을
   * 차지해야 하므로, 이 값이 거짓이면 히어로 블록을 통째로 그리지 않는다.
   */
  const waiting = status.kind === 'POLLING' || status.kind === 'READY'

  return (
    <main className="screen">
      {/*
        화면 맨 위 진행 막대 (아트보드 `Waiting.dc.html`). 문항 진행(`.progress-indicator`)의
        도트가 아니라 막대인 이유는 세는 것이 다르기 때문이다 — 저쪽은 "내가 몇 문항 풀었나"라
        셀 수 있는 칸이고, 이쪽은 "서버가 얼마나 처리했나"라 내가 셀 수 있는 단위가 아니다.

        의미론은 예전 그대로다: `role="progressbar"` 한 줄이 값을 말하고 옆의 숫자는 시각
        전용이다. 분모가 10인 근거는 이 파일 헤더에 있다 (어휘 5문항도 완료로 센다).
      */}
      <div className="analysis-progress">
        <div
          className="analysis-progress__bar"
          role="progressbar"
          aria-label="분석 진행률"
          aria-valuemin={0}
          aria-valuemax={totalItems}
          aria-valuenow={completed}
        >
          <div
            className="analysis-progress__fill"
            style={{ width: `${totalItems > 0 ? (completed / totalItems) * 100 : 0}%` }}
          />
        </div>
        <p className="type-caption analysis-progress__count" aria-hidden="true">
          분석 중 {completed} / {totalItems}
        </p>
      </div>

      {/*
        진행 막대 아래의 모든 것 (KAN-161 3단계 리뷰). 상자를 하나 두는 이유는 정렬이 갈리기
        때문이다: `.screen`은 본문 하나짜리 화면(로딩·오류)을 세로 가운데 세우려고
        `justify-content: center`인데, 그 정렬이 진행 막대까지 함께 내려 화면 위쪽 절반이 비고
        막대가 한가운데 떴다. 이 상자가 `flex: 1`로 남는 높이를 전부 가져가면 바깥의 가운데
        정렬은 할 일이 없어지고(늘어나는 자식이 있으면 자유 공간이 0이다) 막대는 패딩 바로
        아래, 즉 상단에 남는다 — 아트보드 ④의 배치다.

        가운데 정렬은 이 상자 **안에서만** 한다. 기다리는 중에는 그림과 단계가 화면 가운데
        앉아야 하지만, 재녹음 분기는 제목과 목록이 위에서부터 흘러야 눈이 목록을 훑는다.
      */}
      <div className={waiting ? 'analysis-body analysis-body--centered' : 'analysis-body'}>
        {waiting && (
          <div className="analysis-waiting">
            <div className="illustration illustration--waiting">
              <WaitingHero />
            </div>
            {/*
              대기 문구가 StatusBlock의 본문에서 제목(h1)으로 올라왔다. 이 화면에는 다른 제목이
              없어 이 문장이 곧 화면의 이름이고, 시안도 히어로 아래 가장 큰 글자로 둔다.
              문구 자체는 그대로라 대기 화면을 기다리는 테스트가 그대로 통과한다.
            */}
            <h1 className="type-headline">
              {status.kind === 'READY' ? '결과 화면으로 이동합니다' : '결과를 만들고 있어요'}
            </h1>
            {/* 일시적 오류는 여기 부연으로만 알린다 — 문항 목록을 지우지 않는다 */}
            {status.kind === 'POLLING' && (
              <p className="type-caption status-block__detail">{lastError ?? '잠시만 기다려 주세요'}</p>
            )}
            <div className="analysis-steps">
              {ANALYSIS_STAGES.map((label, index) => {
                const step = index + 1
                const state = step < stage ? 'done' : step === stage ? 'current' : 'todo'
                return (
                  <Fragment key={label}>
                    {step > 1 && (
                      /* 칸 사이를 잇는 선. 지나온 구간만 잉크다 — 선 하나가 "여기까지 왔다"를 잇는다 */
                      <div
                        className="analysis-step-line"
                        data-state={step <= stage ? 'done' : 'todo'}
                        aria-hidden="true"
                      />
                    )}
                    {/*
                      단계는 눈으로만 읽는다 (`aria-hidden`). 값을 말하는 것은 위 progressbar 한
                      줄이고, 세 칸까지 읽히면 같은 진행을 두 벌로 듣게 된다 — 진행 도트에서 도트
                      하나하나를 의미론에서 뺀 것과 같은 판정이다. 상태는 `data-state`로 드러나
                      테스트가 색을 보지 않고 확인한다.
                    */}
                    <div className="analysis-step" data-state={state} aria-hidden="true">
                      <span className="analysis-step__dot">
                        {state === 'done' ? <CheckSmallIcon /> : step}
                      </span>
                      <span className="type-caption analysis-step__label">{label}</span>
                    </div>
                  </Fragment>
                )
              })}
            </div>
          </div>
        )}

        {status.kind === 'EXHAUSTED' && (
          <StatusBlock
            tone="error"
            message="분석이 예상보다 오래 걸리고 있어요"
            detail="잠시 후 다시 확인해 주세요"
            action={<Button onClick={restart}>다시 시도</Button>}
          />
        )}

        {actionRequired &&
          (deadEnd ? (
            <StatusBlock
              tone="error"
              message="여기서는 더 진행할 수 없어요"
              detail="앱을 다시 시작해 테스트를 처음부터 진행해 주세요"
            />
          ) : (
            <StatusBlock
              tone="error"
              message={
                status.kind === 'ACTION_REQUIRED' && status.reason === 'RETAKE'
                  ? '일부 문항을 다시 녹음해야 해요'
                  : '아직 보내지 않은 문항이 있어요'
              }
              detail="아래 목록에서 해당 문항을 다시 녹음해 주세요"
            />
          ))}

        {status.kind === 'FAILED' && <StatusBlock tone="error" message={status.message} />}

        <ul className="analysis-list">
          {rows.map(({ item, itemNumber, status: analysis }) => (
            <li key={item.itemId} className="analysis-row">
              <span className="type-label analysis-row__name">{itemNumber}번 문항</span>
              <span className="type-caption analysis-row__status">
                {analysis === null ? '확인 중' : STATUS_LABEL[analysis.status]}
                {analysis?.quality !== null && analysis?.quality !== undefined && analysis.quality !== QUALITY_OK
                  ? ` · ${analysis.quality}`
                  : ''}
              </span>
              {onRetake !== undefined && analysis !== null && RETAKEABLE.includes(analysis.status) && (
                <Button variant="secondary" onClick={() => onRetake(item.itemId)}>
                  다시 녹음
                </Button>
              )}
            </li>
          ))}
        </ul>
      </div>
    </main>
  )
}

/** 재녹음 대상 판정을 화면 바깥(결선·테스트)에서도 같은 규칙으로 쓰기 위해 열어 둔다 */
export function isRetakeable(item: AnalysisItem): boolean {
  return RETAKEABLE.includes(item.status)
}
