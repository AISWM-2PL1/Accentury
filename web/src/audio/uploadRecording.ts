/**
 * 녹음 업로드 (KAN-56 Stage 3) —
 * `POST {apiBase}/v0/sessions/{sessionId}/voice-items/{itemId}/recording` (API 명세서 §3.3).
 *
 * 정본은 네이티브의 `UploadClient.kt`다. 파트 이름(`audio`·`meta`), 파일명(`recording.wav`),
 * 헤더 세 개, 2xx 본문 해석, 봉투 없는 실패의 재시도 판정까지 **의도적으로 같은 규칙**을
 * 옮겨 왔다 — 같은 녹음을 앱에서는 받고 웹에서는 415로 돌려보내면 사용자에게는 기기 탓으로
 * 보인다 (`quality.ts`가 임계값을 앱과 1:1로 맞춘 것과 같은 이유).
 *
 * ## Content-Type을 우리가 정하지 않는다
 *
 * multipart 요청의 `Content-Type`에는 **경계 문자열(boundary)** 이 들어간다 —
 * `multipart/form-data; boundary=----WebKitFormBoundaryXyz`. 그 경계는 브라우저가 본문을
 * 조립하면서 정하는 값이라, 헤더를 우리가 직접 써 넣으면 boundary가 빠지거나 본문의 실제
 * 경계와 어긋난다. 서버는 파트를 하나도 못 찾고 400·415로 튕긴다. `FormData`를 body로
 * 주고 헤더는 손대지 않는 것이 유일하게 맞는 방법이다. 어휘 제출이 `Content-Type:
 * application/json`을 직접 다는 것과 갈리는 지점이다 — 그쪽은 경계가 없는 단일 본문이다.
 *
 * ## 재시도는 같은 attemptId로 (§5.1·§5.2)
 *
 * `Idempotency-Key`는 시도(attempt) 식별자 그 자체다. 전송 실패 후 **같은 녹음을 다시
 * 보내는 것**은 같은 시도의 재전송이라 같은 키를 쓰고, 서버는 중복 접수를 만들지 않는다.
 * 반대로 [재녹음]으로 만든 새 녹음은 새 시도라 새 키다 — 키를 물려주면 서버가 첫 녹음의
 * 접수 결과를 그대로 돌려줘, 사용자가 다시 읽은 음성이 채점에서 사라진다.
 * 키의 수명은 화면(WebVoiceRecorder)이 소유하고 이 함수는 받은 값을 싣기만 한다.
 */

import { readErrorEnvelope, readJson } from '../analysis/errorEnvelope'
import { newIdempotencyKey } from '../net/idempotencyKey'
import type { FetchLike } from '../progress/fetchTestDefinition'
import type { Recording } from './recordingBuffer'

const browserFetch: FetchLike = (input, init) => globalThis.fetch(input, init)

/** multipart 파트 이름·파일명. 서버 계약(§3.3)이라 네이티브 상수와 같은 값이다 */
const PART_AUDIO = 'audio'
const PART_META = 'meta'
const AUDIO_FILE_NAME = 'recording.wav'
const AUDIO_MEDIA_TYPE = 'audio/wav'

/** 업로드 한 건에 필요한 전부. 출처 결정(토큰·세션)은 호출자 몫이다 */
export interface RecordingUpload {
  apiBase: string
  sessionId: string
  itemId: string
  /** 세션 토큰 (Bearer 없이 값만) */
  sessionToken: string
  /** 이 시도의 식별자이자 멱등 키. 같은 녹음의 재시도는 반드시 같은 값이다 */
  attemptId: string
  recording: Recording
}

/** 접수됨. 이 id로 분석 상태를 폴링한다 (KAN-14) */
export interface UploadAccepted {
  analysisJobId: string
}

/** 봉투의 code·retryable·retryAfterMs를 실은 업로드 실패. 봉투를 못 읽었으면 code는 null이다 */
export class UploadError extends Error {
  readonly code: string | null
  readonly retryable: boolean
  /** 429가 지시한 대기(ms). 그 외에는 null */
  readonly retryAfterMs: number | null

  constructor(message: string, code: string | null, retryable: boolean, retryAfterMs: number | null = null) {
    super(message)
    this.name = 'UploadError'
    this.code = code
    this.retryable = retryable
    this.retryAfterMs = retryAfterMs
  }
}

/**
 * 녹음을 올린다.
 *
 * @throws UploadError 값이 비어 있음(재시도 불가) / 네트워크 실패(재시도 가능) /
 *   봉투가 말한 오류(봉투의 retryable) / 봉투 없는 HTTP 오류(상태 코드로 판정)
 */
export async function uploadRecording(
  upload: RecordingUpload,
  fetchImpl: FetchLike = browserFetch,
): Promise<UploadAccepted> {
  const { apiBase, sessionId, itemId, sessionToken, attemptId, recording } = upload

  // 빈 값이면 요청이 엉뚱한 URL이나 401로 나가 원인을 화면에서 알 수 없게 된다.
  // 사용자에게 보이는 문구와 개발자가 볼 진단을 나누는 방식은 어휘 제출과 같다 — 이 실패는
  // 앱이 값을 못 넘긴 상황이라 사용자가 할 수 있는 게 없고, 필드 이름을 화면에 띄워 봐야
  // 비난 없는 톤(ux-ui.md)만 깨진다.
  for (const [name, value] of Object.entries({ sessionId, itemId, sessionToken, attemptId })) {
    if (value.trim() === '') {
      console.error(`[upload] 녹음 업로드에 필요한 값이 비어 있습니다: ${name}`)
      throw new UploadError(
        '녹음을 보낼 수 없어요. 처음부터 다시 시작해 주세요',
        `CLIENT_MISSING_${name}`,
        false,
      )
    }
  }

  const url =
    `${apiBase.replace(/\/+$/, '')}/v0/sessions/${encodeURIComponent(sessionId)}` +
    `/voice-items/${encodeURIComponent(itemId)}/recording`

  const body = new FormData()
  /*
   * 파일 파트 — 세 번째 인자가 파일명이다. 서버는 `@RequestPart("audio") MultipartFile`로 받고
   * 파일명 확장자로 형식을 1차 판정하므로 `recording.wav`를 반드시 실어야 한다.
   *
   * 좁히기(`Uint8Array<ArrayBuffer>`)가 붙은 이유: TS는 `Uint8Array`의 기본 버퍼를
   * `ArrayBufferLike`(= ArrayBuffer | SharedArrayBuffer)로 보는데 `BlobPart`는
   * SharedArrayBuffer를 받지 않는다. 우리 WAV는 `encodeWav16kMono`가 `new Uint8Array(길이)`로
   * 만든 평범한 버퍼 위에 있으므로 그 사실을 알려 주는 것이고, 런타임 동작은 그대로다.
   */
  const audio = new Blob([recording.wav as Uint8Array<ArrayBuffer>], { type: AUDIO_MEDIA_TYPE })
  body.append(PART_AUDIO, audio, AUDIO_FILE_NAME)
  /*
   * meta 파트는 **파일명 없는 평문 문자열**로 붙인다. 서버가 `@RequestPart("meta") String`으로
   * 받기 때문이다 — Blob으로 붙이면 파일 파트가 되어 String 바인딩이 실패한다.
   * 실리는 값은 둘 다 같은 오디오에서 나온 것이다 (`RecordingBuffer.finish`): 길이는 벽시계가
   * 아니라 샘플 수에서, 품질은 16kHz로 변환한 뒤에 잰 값이라 서버가 파일에서 다시 계산해도
   * 어긋나지 않는다.
   */
  body.append(PART_META, JSON.stringify({ durationMs: recording.durationMs, clientQuality: recording.quality }))

  let response: Response
  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${sessionToken}`,
        // 비용이 발생하는 POST라 중복 접수를 막는다 (§2.2). 재시도해도 같은 attemptId를 쓴다.
        'Idempotency-Key': attemptId,
        // 서버 로그와 이 요청을 잇는 값. 실패 제보를 추적하는 유일한 실이라 매 요청 새로 만든다
        // (재시도는 별개의 요청이므로 attemptId와 달리 물려주지 않는다).
        'X-Correlation-Id': newIdempotencyKey(),
      },
      // Content-Type을 여기 두지 않는 이유는 파일 상단 주석 참고 — boundary는 브라우저가 정한다.
      body,
    })
  } catch {
    // 응답이 아예 오지 않았다. 요청이 서버에 닿았는지조차 모르는 상태라 멱등 키가 있는 것이고,
    // 같은 키의 재시도는 접수됐어도 같은 결과, 안 됐어도 첫 접수라 항상 안전하다.
    throw new UploadError('네트워크 오류로 녹음을 보내지 못했어요', null, true)
  }

  const parsed = await readJson(response)

  if (response.ok) {
    const analysisJobId = readAnalysisJobId(parsed)
    if (analysisJobId !== null) return { analysisJobId }
    /*
     * 접수는 됐는데 폴링할 id가 없다. 네이티브와 같은 판정으로 **재시도 가능**하게 둔다 —
     * 멱등 키가 중복 접수를 막아주므로 같은 키로 다시 보내면 서버가 첫 접수의 id를 다시 준다.
     * 여기서 성공으로 처리하면 빈 jobId를 든 채 대기 화면으로 넘어가 영영 끝나지 않는다.
     */
    throw new UploadError('녹음을 보냈지만 확인을 받지 못했어요. 다시 시도해 주세요', null, true)
  }

  const envelope = readErrorEnvelope(response, parsed)
  if (envelope !== null) {
    throw new UploadError(envelope.message, envelope.code, envelope.retryable, envelope.retryAfterMs)
  }
  // 봉투가 없으면 서버가 재시도 여부를 알려주지 않은 것이다 — 상태 코드로 판단한다
  // (네이티브 `isRetryableStatus`와 같은 규칙).
  throw new UploadError(
    `녹음을 보내지 못했어요 (HTTP ${response.status})`,
    null,
    isRetryableStatus(response.status),
    null,
  )
}

/** 2xx 본문에서 `analysisJobId`를 건진다. 없거나 빈 문자열이면 null */
function readAnalysisJobId(body: unknown): string | null {
  if (typeof body !== 'object' || body === null) return null
  const { analysisJobId } = body as Record<string, unknown>
  if (typeof analysisJobId !== 'string' || analysisJobId.trim() === '') return null
  return analysisJobId
}

/** 408·429·5xx는 잠시 뒤 같은 요청이 통할 수 있는 실패다. 4xx 나머지는 다시 보내도 같다 */
function isRetryableStatus(status: number): boolean {
  return status >= 500 || status === 408 || status === 429
}
