/**
 * 브라우저 녹음 패널 (KAN-56 Stage 3). 음성 문항 화면의 하단 자리에서 녹음 → 검토 → 업로드를
 * 맡는다 — 앱이 아닌 곳(브라우저 단독, KAN-31 웹 단독 응시)에서 유일한 녹음 통로다.
 *
 * 단계와 전이는 전부 [useRecorder]가 가지고, 이 컴포넌트가 더하는 상태는 **업로드 한 겹**뿐이다.
 * 화면 흐름은 API 명세서 §5.7 그대로다: [녹음] → 녹음 중… → [정지] → 확인 [재녹음]/[다음].
 * 재생은 없다 — 그래서 검토 단계가 들고 있는 것도 재생용 오디오가 아니라 업로드용 [Recording]
 * 하나다.
 *
 * ## 업로드는 [다음]에서만 일어난다 (§3.3·§5.7)
 *
 * 정지한다고 올리지 않는다. 로컬 [재녹음]은 횟수 제한이 없고 **시도(attempt)를 만들지 않는다** —
 * 마음에 들 때까지 다시 읽는 것은 사용자의 일이지 서버가 셀 일이 아니다. 서버에 시도가 생기는
 * 순간은 사용자가 [다음]으로 이 녹음을 확정한 때뿐이다.
 *
 * ## 품질 게이트를 클라이언트가 먼저 본다 (FR-AD-08)
 *
 * 너무 짧거나·조용하거나·찢어진 녹음은 [다음] 자체를 그리지 않는다. 서버도 같은 판정을 다시
 * 하지만(권한은 서버에 있다), 왕복을 기다렸다가 "다시 읽어 주세요"를 듣는 것과 정지 직후
 * 바로 듣는 것은 사용자에게 다른 경험이다 — 특히 저속망에서 320KB를 올린 뒤 거절당하면
 * 데이터도 시간도 버린다. 판정 기준이 앱·웹·서버에서 같은 값인 이유가 이것이다
 * (`quality.ts` 헤더).
 */

import { useCallback, useRef, useState } from 'react'
import { useRecorder, type CaptureFactory, type QualityStatus, type Recording } from '../audio'
import { UploadError, type UploadAccepted } from '../audio/uploadRecording'
import type { ItemResult } from '../bridge/itemResult'
import { newIdempotencyKey } from '../net/idempotencyKey'
import { Button, StatusBlock } from '../ui'
import type { VoiceItem } from './testDefinition'

export interface WebVoiceRecorderProps {
  item: VoiceItem
  /** 녹음 한 건을 서버로 올린다. 같은 attemptId로 다시 부르는 것이 재시도다 */
  upload: (recording: Recording, attemptId: string) => Promise<UploadAccepted>
  /** 접수됨 — 브리지 경로의 `onItemResult`와 같은 모양으로 알린다 */
  onUploaded: (result: ItemResult) => void
  /** 주입용 캡처 (테스트용). 기본값은 실제 Web Audio 캡처다 */
  capture?: CaptureFactory
}

type UploadState =
  | { kind: 'idle' }
  | { kind: 'uploading' }
  | { kind: 'failed'; message: string; retryable: boolean }

/**
 * 품질 판정별 안내. 전부 **다음 행동**을 말한다 — "실패했습니다"는 사용자가 할 일을 알려주지
 * 않아서 같은 실패를 반복하게 만든다 (ux-ui.md 비난 없는 카피).
 */
const QUALITY_MESSAGE: Record<Exclude<QualityStatus, 'NORMAL'>, string> = {
  TOO_SHORT: '녹음이 너무 짧아요. 1초 이상 읽어 주세요',
  TOO_QUIET: '목소리가 잘 들리지 않아요. 조금 더 크게 읽어 주세요',
  CLIPPED: '소리가 너무 커요. 마이크에서 조금 떨어져 주세요',
}

export function WebVoiceRecorder({ item, upload, onUploaded, capture }: WebVoiceRecorderProps) {
  const { state, start, stop, discard } = useRecorder({ maxDurationMs: item.maxDurationMs, capture })
  const [uploadState, setUploadState] = useState<UploadState>({ kind: 'idle' })

  /*
   * 이 녹음의 시도 식별자. [다음]을 처음 누를 때 만들고 재시도에서 그대로 다시 쓴다 (§5.1·§5.2).
   *
   * 상태가 아니라 ref인 이유는 렌더에 보이지 않는 값이라서다 — 어휘 문항이 선택지별 멱등 키를
   * ref로 든 것과 같은 판정이다. 수명은 **녹음 하나**다: [재녹음]으로 다른 녹음이 되는 순간
   * 비우고, 그 다음 [다음]은 새 키를 만든다. 물려주면 서버가 첫 녹음의 접수 결과를 그대로
   * 돌려줘 다시 읽은 음성이 채점에서 사라진다.
   */
  const attemptIdRef = useRef<string | null>(null)
  /*
   * 업로드 진행 중 표시의 ref 사본. setState는 비동기라 [다음] 연타의 두 번째 클릭이
   * `uploadState`가 'uploading'으로 바뀌기 전에 핸들러에 닿을 수 있다 — 그 순간 판정에 쓸 값은
   * ref다 (`useRecorder`가 자동 정지 판정에 phaseRef를 쓰는 것과 같은 이유).
   */
  const uploadingRef = useRef(false)

  /** [재녹음] — 녹음도 시도 식별자도 버리고 처음으로 돌아간다. 서버에는 아무 일도 없었다 */
  const retake = useCallback(() => {
    attemptIdRef.current = null
    setUploadState({ kind: 'idle' })
    discard()
  }, [discard])

  const send = useCallback(
    async (recording: Recording) => {
      if (uploadingRef.current) return
      uploadingRef.current = true
      setUploadState({ kind: 'uploading' })

      // 키 생성까지 try 안이다 — 여기서 동기로 터지면 rejection이 아무 데도 안 잡혀 버튼이
      // "눌러도 아무 일 없는" 상태가 된다 (crypto.randomUUID 부재로 실제 발생했던 증상).
      try {
        attemptIdRef.current ??= newIdempotencyKey()
        const attemptId = attemptIdRef.current
        const { analysisJobId } = await upload(recording, attemptId)
        /*
         * 접수됨. 부모는 이 통지를 받아 다음 문항으로 넘기므로 이 컴포넌트는 곧 언마운트된다.
         * 여기서 시도 식별자를 놓는 것이 FR-AD-04(오디오를 필요 이상으로 들고 있지 않는다)의
         * 이 화면 몫이다 — [Recording] 자체는 언마운트 전까지 훅 상태에 남지만, 언마운트가
         * 곧바로 따라오고 훅의 cleanup이 버퍼까지 놓는다.
         */
        attemptIdRef.current = null
        onUploaded({
          itemId: item.itemId,
          attemptId,
          analysisJobId,
          durationMs: recording.durationMs,
          qualityStatus: recording.status,
        })
      } catch (error: unknown) {
        // 재시도 가능 여부는 서버가 봉투로 말해 준다. 그 판정이 없는 실패(UploadError가 아닌 값)는
        // 막지 않는다 — 재시도는 같은 키라 무해하고, 여기서 잠가 버리면 사용자가 문항에 갇힌다.
        setUploadState({
          kind: 'failed',
          message: error instanceof Error ? error.message : String(error),
          retryable: error instanceof UploadError ? error.retryable : true,
        })
      } finally {
        uploadingRef.current = false
      }
    },
    [item.itemId, onUploaded, upload],
  )

  if (state.phase === 'error') {
    return (
      <StatusBlock
        tone="error"
        message={state.message}
        action={
          <Button onClick={retake} style={{ width: '100%' }}>
            다시 시도
          </Button>
        }
      />
    )
  }

  if (state.phase === 'review') {
    return (
      <ReviewPanel
        recording={state.recording}
        uploadState={uploadState}
        onRetake={retake}
        onSend={() => void send(state.recording)}
      />
    )
  }

  if (state.phase === 'recording') {
    const ratio = Math.min(1, state.elapsedMs / item.maxDurationMs)
    return (
      <>
        {/*
          경과 시간은 벽시계가 아니라 담긴 샘플 수에서 온다 (RecordingBuffer.durationMs) —
          사용자가 보는 숫자와 서버가 파일에서 재는 길이가 같아야 한다.
          상한에 닿으면 훅이 스스로 멈추므로 [정지]를 못 눌러도 녹음이 잘리지 않는다 (FR-RC-02).
        */}
        <p className="type-label record-elapsed">
          {(state.elapsedMs / 1000).toFixed(1)}초 / {Math.round(item.maxDurationMs / 1000)}초
        </p>
        <div className="record-meter" aria-hidden="true">
          <div className="record-meter__fill" style={{ width: `${ratio * 100}%` }} />
        </div>
        <Button onClick={() => void stop()} style={{ width: '100%' }}>
          정지
        </Button>
      </>
    )
  }

  if (state.phase === 'starting') {
    // 권한 프롬프트·컨텍스트 생성 구간. 비활성 버튼을 남기는 이유는 자리를 지키기 위해서다 —
    // 버튼이 사라졌다 나타나면 그 사이에 아래 내용이 위로 올라왔다 내려간다.
    return (
      <Button disabled style={{ width: '100%' }}>
        준비 중…
      </Button>
    )
  }

  return (
    <>
      <p className="type-caption record-hint">버튼을 누르고 문장을 읽어 주세요</p>
      <Button onClick={() => void start()} style={{ width: '100%' }}>
        녹음
      </Button>
    </>
  )
}

/**
 * 정지 뒤의 확인 단계. 세 갈래다 — 보내는 중 / 실패 / 확인.
 *
 * 갈래를 따로 뽑은 이유는 위 컴포넌트의 단계 분기와 성격이 다르기 때문이다. 저쪽은 녹음
 * 상태 기계의 단계이고 여기는 **한 단계 안의 화면 상태**라, 한 함수에 섞어 두면 "review인데
 * uploading이고 failed는 아닌" 조합을 읽는 사람이 매번 머릿속에서 풀어야 한다.
 */
function ReviewPanel({
  recording,
  uploadState,
  onRetake,
  onSend,
}: {
  recording: Recording
  uploadState: UploadState
  onRetake: () => void
  onSend: () => void
}) {
  if (uploadState.kind === 'uploading') {
    // 버튼을 아예 그리지 않는다 — 비활성 버튼을 남기면 눌러 보고 안 눌리는 것을 확인하게 된다.
    return <StatusBlock tone="waiting" message="보내는 중…" />
  }

  if (uploadState.kind === 'failed') {
    return (
      <StatusBlock
        tone="error"
        message={uploadState.message}
        action={
          <div className="record-actions">
            {/* 재시도는 **같은 녹음을 같은 키로** 다시 보낸다 (§5.2) — 중복 접수가 생기지 않는다 */}
            {uploadState.retryable && <Button onClick={onSend}>다시 시도</Button>}
            <Button variant={uploadState.retryable ? 'text' : 'primary'} onClick={onRetake}>
              재녹음
            </Button>
          </div>
        }
      />
    )
  }

  if (recording.status !== 'NORMAL') {
    /*
     * 품질 게이트 (FR-AD-08). [다음]을 비활성으로 두지 않고 **렌더 자체를 하지 않는다** —
     * 비활성 버튼은 "언젠가 눌릴 수 있다"는 신호라, 사용자가 눌러 보다가 왜 안 되는지 묻게 된다.
     * 여기서 나갈 길은 다시 읽는 것 하나뿐이므로 그 하나만 남긴다.
     */
    return (
      <StatusBlock
        tone="error"
        message={QUALITY_MESSAGE[recording.status]}
        action={
          <Button onClick={onRetake} style={{ width: '100%' }}>
            재녹음
          </Button>
        }
      />
    )
  }

  return (
    <>
      {/* 재생이 없으므로 "들어보세요"라고 말하지 않는다 (§5.7) */}
      <p className="type-caption record-hint">녹음이 끝났어요. 다시 녹음하거나 다음으로 넘어가세요</p>
      <div className="record-actions">
        <Button variant="text" onClick={onRetake}>
          재녹음
        </Button>
        <Button onClick={onSend}>다음</Button>
      </div>
    </>
  )
}
