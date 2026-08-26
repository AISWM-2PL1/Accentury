/**
 * 웹 녹음 파이프라인의 순수 함수 계층 (KAN-56 Stage 1).
 *
 * 캡처(AudioContext)와 업로드(fetch)는 여기 없다. 이 계층은 "이미 손에 든 샘플 배열"을
 * 서버가 받는 모양으로 바꾸는 계산만 한다 — 그래서 브라우저 없이 테스트된다.
 *
 * 흐름: 캡처된 Float32 → {@link resampleTo16k} → {@link floatToInt16} →
 * {@link encodeWav16kMono}(업로드 본문) + {@link measure}·{@link judge}(meta·화면 안내).
 */

export { TARGET_SAMPLE_RATE, FULL_SCALE_INT16, floatToInt16 } from './pcm'
export { CUTOFF_RATIO, TAP_COUNT, KERNEL_RESOLUTION, outputLength, resampleTo16k } from './resample'
export { WAV_HEADER_BYTES, encodeWav16kMono } from './wavEncoder'
export {
  CLIP_RATIO_THRESHOLD,
  CLIP_SAMPLE_THRESHOLD,
  FULL_SCALE,
  MIN_DURATION_MS,
  QUIET_RMS_THRESHOLD,
  SILENCE_SAMPLE_THRESHOLD,
  judge,
  measure,
  type ClientQuality,
  type QualityStatus,
} from './quality'
