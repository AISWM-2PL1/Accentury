/**
 * 브라우저 없이 녹음 흐름을 돌리기 위한 캡처 대역 (KAN-56).
 *
 * jsdom에는 `AudioContext`가 없어 진짜 캡처(`capture.ts`)를 테스트에서 쓸 수 없다. 실제
 * 워클릿이 하는 일 — 조각을 던지고 정지 요청에 응하는 것 — 두 가지만 흉내 낸다.
 *
 * `useRecorder.test.ts` 안에 있던 것을 꺼내 왔다: 화면 테스트(`WebVoiceRecorder`,
 * `TestFlowScreen`)도 같은 대역이 필요해졌기 때문이다. 대역이 파일마다 갈라지면 "훅 테스트는
 * 통과하는데 화면 테스트만 깨진다"가 대역 차이인지 코드 차이인지 알 수 없게 된다.
 *
 * 이 파일은 테스트 전용이라 앱 번들에 들어가지 않는다 — 어디서도 import하지 않기 때문이다.
 */

import { vi } from 'vitest'
import type { Capture, CaptureFactory } from '../capture'

/** 하드웨어 캡처 레이트의 대표값. 실기(안드로이드·iOS) 대부분이 이 값이다 */
export const FAKE_SAMPLE_RATE = 48000

export interface FakeCapture {
  /** `useRecorder`·화면에 주입할 캡처 팩토리 */
  factory: CaptureFactory
  /** 캡처 종료 spy. 마이크를 놓았는지 확인하는 데 쓴다 (FR-AD-04) */
  stop: ReturnType<typeof vi.fn>
  /** 워클릿이 조각 하나를 보낸 상황 */
  emit: (chunk: Float32Array) => void
  /** 시작된 캡처의 샘플레이트 */
  sampleRate: number
}

export function createFakeCapture(sampleRate = FAKE_SAMPLE_RATE): FakeCapture {
  let deliver: ((chunk: Float32Array) => void) | null = null
  const stop = vi.fn(async (): Promise<void> => {})
  const factory: CaptureFactory = async (onChunk) => {
    deliver = onChunk
    return { sampleRate, stop } satisfies Capture
  }
  return {
    factory,
    stop,
    sampleRate,
    emit(chunk: Float32Array) {
      if (deliver === null) throw new Error('캡처가 아직 시작되지 않았다')
      deliver(chunk)
    },
  }
}

/**
 * 품질 게이트를 통과하는 발화 대역 — 지정한 길이의 사인파.
 *
 * `new Float32Array(n)`(전부 0)은 담기기는 해도 `TOO_QUIET`으로 판정돼 [다음]이 막힌다
 * (`quality.ts`의 RMS 게이트). NORMAL 경로를 밟는 테스트는 실제로 소리가 있어야 한다.
 *
 * 기본 진폭 0.5는 두 임계값 사이를 넉넉히 지나간다: RMS는 0.5/√2 ≈ 0.354(전 스케일 11585 ≫
 * 100)이고, 피크는 16384로 클리핑 임계(32000)의 절반이다.
 */
export function sineChunk(
  durationMs: number,
  { sampleRate = FAKE_SAMPLE_RATE, frequency = 200, amplitude = 0.5 } = {},
): Float32Array {
  const samples = Math.round((durationMs * sampleRate) / 1000)
  const chunk = new Float32Array(samples)
  for (let i = 0; i < samples; i += 1) {
    chunk[i] = amplitude * Math.sin((2 * Math.PI * frequency * i) / sampleRate)
  }
  return chunk
}
