/**
 * 클라이언트 측 녹음 품질 판정 (KAN-56 Stage 1).
 *
 * ## 상수와 판정 순서를 앱과 1:1로 맞춘 이유
 *
 * 이 파일의 임계값은 전부 네이티브 앱 `AudioQuality.kt`에서 그대로 옮겨 왔다. 같은 녹음을
 * 웹에서는 통과시키고 앱에서는 "너무 조용합니다"라고 돌려보내면, 사용자에게는 기기 탓으로
 * 보이고 팀에게는 재현되지 않는 버그로 보인다. 판정 기준은 플랫폼이 아니라 제품의 규칙이라
 * **한쪽을 고치면 반드시 다른 쪽도 같이 고쳐야 한다.**
 *
 * 판정 순서(TOO_SHORT → CLIPPED → TOO_QUIET → NORMAL)도 앱과 같다. 순서가 곧 우선순위다 —
 * 클리핑된 녹음은 큰 소리라 RMS가 높게 나오므로, 클리핑을 먼저 걸러내지 않으면 "찢어진 녹음"이
 * 조용한지 아닌지를 따지는 무의미한 판단으로 넘어간다.
 */

/** 판정 결과. 앱 `QualityStatus` enum과 같은 이름을 쓴다 */
export type QualityStatus = 'NORMAL' | 'TOO_SHORT' | 'TOO_QUIET' | 'CLIPPED'

/**
 * 업로드 meta 파트에 실리는 클라이언트 측 품질 지표 (API 명세서 §3.3).
 * rms·peak·silenceRatio는 모두 0..1로 정규화된 실수다.
 *
 * **필드 이름은 그대로 JSON 키가 되므로 서버 계약이다.** 바꾸려면 앱·서버와 같이 바꿔야 한다.
 */
export interface ClientQuality {
  rms: number
  peak: number
  silenceRatio: number
  clipped: boolean
}

/** 이보다 짧은 녹음은 분석하지 않는다 (ms) */
export const MIN_DURATION_MS = 1000

/** 이 아래면 발화로 보지 않는 RMS. **정규화 전 원 스케일** 기준이다 */
export const QUIET_RMS_THRESHOLD = 100

/** 이 진폭 이상이면 그 샘플은 천장에 닿은 것으로 본다 */
export const CLIP_SAMPLE_THRESHOLD = 32000

/** 천장에 닿은 샘플이 전체의 이 비율을 넘으면 클리핑으로 판정한다 */
export const CLIP_RATIO_THRESHOLD = 0.01

/** 16-bit PCM 전체 스케일. 정규화(0..1) 분모로 쓴다 */
export const FULL_SCALE = 32768

/**
 * 무음으로 볼 진폭 상한. 전체 스케일의 1%(= -40 dBFS)로,
 * 조용한 실내 잡음은 걸러내면서 실제 발화는 남기는 수준이다.
 */
export const SILENCE_SAMPLE_THRESHOLD = 328

/**
 * 녹음을 받아들일지 판정한다.
 *
 * @param durationMs 녹음 길이. PCM 길이에서 되계산하지 않고 받는 이유는, 캡처가 중간에 끊기면
 *   실제 흐른 시간과 확보한 샘플 수가 어긋나는데 사용자가 체감한 것은 흐른 시간이라서다.
 */
export function judge(pcm: Int16Array, durationMs: number): QualityStatus {
  if (durationMs < MIN_DURATION_MS) return 'TOO_SHORT'
  if (pcm.length === 0) return 'TOO_SHORT'

  const quality = measure(pcm)
  if (quality.clipped) return 'CLIPPED'
  // QUIET_RMS_THRESHOLD는 정규화 전 원 스케일 기준이라 되돌려서 비교한다.
  if (quality.rms * FULL_SCALE < QUIET_RMS_THRESHOLD) return 'TOO_QUIET'

  return 'NORMAL'
}

/** 서버로 보낼 품질 지표를 한 번의 순회로 계산한다. 빈 배열은 전부 0으로 본다 */
export function measure(pcm: Int16Array): ClientQuality {
  if (pcm.length === 0) return { rms: 0, peak: 0, silenceRatio: 0, clipped: false }

  let peak = 0
  let silentCount = 0
  let clippedCount = 0
  let squareSum = 0
  for (let i = 0; i < pcm.length; i++) {
    const magnitude = Math.abs(pcm[i])
    if (magnitude > peak) peak = magnitude
    if (magnitude < SILENCE_SAMPLE_THRESHOLD) silentCount++
    if (magnitude >= CLIP_SAMPLE_THRESHOLD) clippedCount++
    squareSum += pcm[i] * pcm[i]
  }

  return {
    rms: Math.sqrt(squareSum / pcm.length) / FULL_SCALE,
    peak: peak / FULL_SCALE,
    silenceRatio: silentCount / pcm.length,
    clipped: clippedCount / pcm.length > CLIP_RATIO_THRESHOLD,
  }
}
