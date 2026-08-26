/**
 * 캡처 조각 → F0 프레임 파이프라인 (KAN-56 Stage 5). 앱 `RecordingEngine`의 곡선 부분과 같은 자리다.
 *
 * ```
 * 캡처 조각 (48kHz Float32)
 *   → StreamingResampler (16kHz)
 *   → OverlappedFramer (2048창 / 512hop)
 *   → estimatePitchHz (CMNDF 0.25, RMS 게이트 100)
 *   → PitchFrame (창 중앙 시각 + F0 또는 null)
 * ```
 *
 * 각 단계는 앞 단계가 뭘 하는지 모른다 (`pitch-curve.md` §1). 이 클래스는 셋을 잇고 프레임을
 * 쌓아 두는 일만 한다 — 곡선 모양을 정하는 규칙은 전부 `recording/userCurve.ts` 쪽이다.
 *
 * ## 메인 스레드에서 도는 이유
 *
 * 워클릿으로 내리지 않는다. 4096샘플 조각(48kHz에서 85ms)은 16kHz로 줄이면 1365샘플이라
 * hop 512 기준 프레임이 많아야 3개 나오고, YIN 한 번이 2048×160번의 곱셈이라 조각당 약 100만
 * 연산 — 85ms 예산에 비하면 미미하다. 워클릿으로 옮기면 그 대가로 메시지 경계·상태 이중화가
 * 생기는데, 얻는 것이 없다.
 *
 * ## 상태가 있으므로 녹음 1회당 1개다
 *
 * 리샘플러도 프레이머도 조각 사이에 걸친 이력을 들고 있다. 녹음을 다시 시작하면 새로 만들어야
 * 하고, 버리면 그 이력까지 같이 놓인다 (FR-AD-04).
 */

import { OverlappedFramer, WINDOW_SIZE } from './overlappedFramer'
import { TARGET_SAMPLE_RATE } from './pcm'
import { StreamingResampler } from './streamingResampler'
import { estimatePitchHz } from './yin'

/**
 * 분석 창 1개의 F0. 앱 `RecordingEngine.PitchFrame`과 같은 모양이다.
 *
 * **timestampMs는 창의 시작이 아니라 중앙 시각이다.** YIN이 낸 F0 하나는 창 전체(2048샘플 =
 * 128ms)를 대표하는 값이라 시간축 위의 대표 위치도 그 한가운데가 맞다. 시작 시각으로 찍으면
 * 곡선 전체가 실제보다 64ms 앞당겨 그려진다 — 프레임 간격(32ms)보다 큰 어긋남이다
 * (`pitch-curve.md` §1).
 */
export interface PitchFrame {
  timestampMs: number
  /** 무성음이면 null */
  pitchHz: number | null
}

export class PitchTracker {
  private readonly resampler: StreamingResampler
  private readonly framer = new OverlappedFramer()
  private readonly collected: PitchFrame[] = []

  /** @param inputRate 캡처 레이트(`AudioContext.sampleRate`). 16kHz면 리샘플이 통과 모드가 된다 */
  constructor(inputRate: number) {
    this.resampler = new StreamingResampler(inputRate)
  }

  /** 녹음 시작부터 지금까지의 프레임. 시각 순이고, 내부 배열을 그대로 준다(읽기 전용으로 쓴다) */
  get frames(): PitchFrame[] {
    return this.collected
  }

  /** 캡처 조각 하나를 흘려넣고 **이번에 새로 완성된** 프레임을 돌려준다 */
  push(chunk: Float32Array): PitchFrame[] {
    const resampled = this.resampler.push(chunk)
    if (resampled.length === 0) return []

    const fresh = this.framer.push(resampled).map((frame) => ({
      // 정수 나눗셈이 필요 없다 - startSampleIndex가 hop(512)의 배수라 항상 딱 떨어진다.
      timestampMs: Math.floor(
        ((frame.startSampleIndex + WINDOW_SIZE / 2) * 1000) / TARGET_SAMPLE_RATE,
      ),
      pitchHz: estimatePitchHz(frame.samples),
    }))
    this.collected.push(...fresh)
    return fresh
  }
}
