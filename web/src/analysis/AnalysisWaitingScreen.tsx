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

import { useEffect } from 'react'
import { Button, ProgressIndicator, StatusBlock } from '../ui'
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
  voiceItems: readonly VoiceItem[]
  /** 진행률 분모. 정의의 전체 문항 수(10)를 그대로 받는다 */
  totalItems: number
  /** 결과가 확정됐다. 화면 이동은 호출자 몫이다 (App의 진입 쿼리 계약을 화면이 알 필요가 없다) */
  onReady: () => void
  /**
   * 이 문항을 다시 녹음한다. 없으면 재녹음 버튼을 그리지 않는다 —
   * 네이티브 결선이 없는 브라우저 단독 실행에서 눌러도 아무 일 없는 버튼을 두지 않기 위해서다.
   */
  onRetake?: (itemId: string) => void
  fetchImpl?: FetchLike
}

/** 사용자에게 보이는 상태 문구. 코드 이름을 그대로 노출하지 않는다 */
const STATUS_LABEL: Record<AnalysisItemStatus, string> = {
  NOT_SUBMITTED: '녹음 필요',
  PROCESSING: '분석 중',
  COMPLETED: '완료',
  RETRYABLE_FAILED: '다시 녹음이 필요해요',
  FAILED: '분석 실패',
}

/** 재녹음이 도움이 되는 상태. 이 둘에만 [다시 녹음]을 준다 */
const RETAKEABLE: readonly AnalysisItemStatus[] = ['RETRYABLE_FAILED', 'NOT_SUBMITTED']

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

  // 음성 문항은 정의 순서(seq)가 정본이다. 서버도 같은 순서로 주지만, 순번 표기를 정의에서
  // 뽑아야 "3번째 문항"이 문항 진행 화면에서 본 번호와 같아진다.
  const rows = voiceItems.map((item, index) => ({
    item,
    /** 음성 문항 안에서의 순번. 전체 10문항 기준 번호가 아니라 "음성 5개 중 n번째"다 */
    voiceNumber: index + 1,
    status: items.find((analysis) => analysis.itemId === item.itemId) ?? null,
  }))

  const completedVoice = rows.filter((row) => row.status?.status === 'COMPLETED').length
  /*
   * 어휘 문항은 이 화면에 도달한 시점에 전부 저장돼 있다. 분모에서 음성 문항을 빼면 남는
   * 수가 곧 어휘 수이므로, 정의를 한 번 더 뒤지지 않고 그 차이를 완료로 센다.
   */
  const completed = Math.max(totalItems - voiceItems.length, 0) + completedVoice

  return (
    <main className="screen">
      <ProgressIndicator current={completed} total={totalItems} label="분석 진행률" />

      {status.kind === 'POLLING' && (
        <StatusBlock
          tone="waiting"
          message="결과를 만들고 있어요"
          detail={lastError ?? '잠시만 기다려 주세요'}
        />
      )}

      {status.kind === 'READY' && <StatusBlock tone="waiting" message="결과 화면으로 이동합니다" />}

      {status.kind === 'EXHAUSTED' && (
        <StatusBlock
          tone="error"
          message="분석이 예상보다 오래 걸리고 있어요"
          detail="잠시 후 다시 확인해 주세요"
          action={<Button onClick={restart}>다시 시도</Button>}
        />
      )}

      {status.kind === 'ACTION_REQUIRED' && (
        <StatusBlock
          tone="error"
          message={
            status.reason === 'RETAKE'
              ? '일부 문항을 다시 녹음해야 해요'
              : '아직 보내지 않은 문항이 있어요'
          }
          detail="아래 목록에서 해당 문항을 다시 녹음해 주세요"
        />
      )}

      {status.kind === 'FAILED' && <StatusBlock tone="error" message={status.message} />}

      <ul className="analysis-list">
        {rows.map(({ item, voiceNumber, status: analysis }) => (
          <li key={item.itemId} className="analysis-row">
            <span className="type-label analysis-row__name">음성 {voiceNumber}번</span>
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
    </main>
  )
}

/** 재녹음 대상 판정을 화면 바깥(결선·테스트)에서도 같은 규칙으로 쓰기 위해 열어 둔다 */
export function isRetakeable(item: AnalysisItem): boolean {
  return RETAKEABLE.includes(item.status)
}
