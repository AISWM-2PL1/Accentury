/**
 * 웹 녹음 파이프라인의 순수 함수 계층 (KAN-56 Stage 1).
 *
 * 캡처(AudioContext)와 업로드(fetch)는 여기 없다. 이 계층은 "이미 손에 든 샘플 배열"을
 * 서버가 받는 모양으로 바꾸는 계산만 한다 — 그래서 브라우저 없이 테스트된다.
 *
 * 흐름: 캡처된 Float32 → {@link resampleTo16k} → {@link floatToInt16} →
 * {@link encodeWav16kMono}(업로드 본문) + {@link measure}·{@link judge}(meta·화면 안내).
 *
 * Stage 2에서 그 위에 캡처 계층이 올라왔다 — 게이트({@link requestMicrophonePermission}),
 * 누적({@link RecordingBuffer}), 브라우저 결선({@link webAudioCapture}), 상태 기계
 * ({@link useRecorder}). 브라우저 API를 실제로 만지는 곳은 `capture.ts` 하나뿐이다.
 *
 * Stage 3의 업로드(`uploadRecording.ts`)는 **여기서 재수출하지 않는다.** 그쪽은 fetch 대역과
 * 오류 봉투를 쓰느라 `progress`·`analysis`를 참조하는데, 이 배럴이 그것을 실어 나르면 오디오
 * 계층을 import하는 것만으로 네트워크 계층이 딸려 온다. 필요한 곳에서 직접 가져간다.
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
export {
  browserEnvironment,
  classifyMediaError,
  microphoneSupport,
  requestMicrophonePermission,
  type MicEnvironment,
  type MicPermission,
  type MicSupport,
} from './microphone'
export {
  DEFAULT_APP_STORE_URL,
  DEFAULT_PLAY_STORE_URL,
  detectStorePlatform,
  storeUrlFor,
  type StorePlatform,
} from './storeLink'
export { RecordingBuffer, type Recording } from './recordingBuffer'
export {
  CaptureError,
  webAudioCapture,
  type Capture,
  type CaptureFactory,
  type CaptureFailure,
} from './capture'
export {
  useRecorder,
  type RecorderState,
  type UseRecorderOptions,
  type UseRecorderResult,
} from './useRecorder'
/*
 * Stage 5의 실시간 F0 분석. 업로드 경로(resample → wav)와 나란한 두 번째 소비자라 같은 배럴에
 * 둔다 — 곡선 그리기 규칙 자체는 오디오가 아니라 화면의 일이라 `src/recording/`에 있다.
 */
export { StreamingResampler } from './streamingResampler'
export { OverlappedFramer, HOP_SIZE, WINDOW_SIZE, type AnalysisFrame } from './overlappedFramer'
export { MAX_F0_HZ, MIN_F0_HZ, VOICED_MIN_RMS, estimatePitchHz } from './yin'
export { PitchTracker, type PitchFrame } from './pitchTracker'
