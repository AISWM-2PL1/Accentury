/**
 * 캡처된 조각을 모아 업로드 가능한 녹음 하나로 마무리한다 (KAN-56 Stage 2).
 *
 * 브라우저 API가 하나도 없는 순수 계층이다 — Stage 1의 변환 함수들과 같은 이유로, 캡처
 * 그래프 없이 vitest가 그냥 클래스로 부를 수 있어야 길이 제한·꼬리 처리 같은 규칙을 실제로
 * 검사할 수 있다. 이 클래스가 소유하는 규칙은 둘이다: **얼마나 모을 것인가(길이 상한)** 와
 * **모은 것을 어떤 순서로 변환할 것인가.**
 */

import { floatToInt16 } from './pcm'
import { judge, measure, type ClientQuality, type QualityStatus } from './quality'
import { resampleTo16k } from './resample'
import { encodeWav16kMono } from './wavEncoder'

/**
 * 업로드 한 건에 필요한 모든 것. 파일(`wav`)과 meta(`durationMs`·`quality`)가 **같은 오디오
 * 에서 나온 값**이라는 점이 이 타입의 요점이다 (API 명세서 §3.3).
 */
export interface Recording {
  /** 16kHz 모노 16-bit WAV 전체 바이트 (헤더 포함) */
  wav: Uint8Array
  durationMs: number
  /** 캡처 당시 하드웨어 레이트. 진단용으로 남긴다 — 파일 자체는 이미 16kHz다 */
  sourceSampleRate: number
  quality: ClientQuality
  status: QualityStatus
}

export class RecordingBuffer {
  /** 상한에 해당하는 입력 샘플 수. 레이트가 다르면 같은 10초라도 이 값이 달라진다 */
  readonly maxSamples: number

  private chunks: Float32Array[] = []
  private captured = 0
  private finished = false

  /**
   * @param sampleRate 캡처 레이트 (`AudioContext.sampleRate`). 하드웨어가 정하는 값이라
   *   48000일 수도 44100일 수도 있고, iOS는 마이크가 붙는 순간 바꾸기도 한다
   * @param maxDurationMs 문항 정의의 `maxDurationMs` (VOICE 문항 10초)
   */
  constructor(
    readonly sampleRate: number,
    readonly maxDurationMs: number,
  ) {
    if (!Number.isFinite(sampleRate) || sampleRate <= 0) {
      throw new RangeError(`캡처 샘플레이트가 유효하지 않다: ${sampleRate}`)
    }
    if (!Number.isFinite(maxDurationMs) || maxDurationMs <= 0) {
      throw new RangeError(`최대 녹음 길이가 유효하지 않다: ${maxDurationMs}`)
    }
    this.maxSamples = Math.round((maxDurationMs * sampleRate) / 1000)
  }

  /** 지금까지 확보한 입력 샘플 수. [finish] 뒤에도 값은 남는다 */
  get capturedSamples(): number {
    return this.captured
  }

  /**
   * 확보한 오디오의 길이 (ms).
   *
   * **벽시계가 아니라 샘플 수로 잰다.** 이 값이 곧 meta의 `durationMs`가 되는데, 서버는
   * 업로드된 WAV의 프레임 수에서 길이를 다시 계산해 대조한다 — 벽시계로 재면 캡처가 잠깐
   * 끊기거나 탭이 백그라운드로 내려간 구간만큼 파일과 meta가 어긋나 415로 튕긴다. 네이티브
   * 앱도 같은 방식(읽어들인 PCM 바이트 수)으로 계산하므로 두 플랫폼의 값이 같은 뜻을 갖는다.
   *
   * 화면의 진행 시간 표시도 이 값을 쓴다. 사용자가 보는 숫자와 서버가 받는 숫자가 다르면
   * "10초까지 찼는데 9.6초로 잘렸다" 같은 재현 안 되는 제보가 된다.
   */
  get durationMs(): number {
    return Math.round((this.captured / this.sampleRate) * 1000)
  }

  /**
   * 조각 하나를 담는다. **상한에 닿았으면 true** — 호출자는 이걸 보고 자동 정지한다.
   *
   * 마지막 조각은 상한에 맞춰 잘라 담는다. 조각 단위로 통째 버리거나 통째 담으면 실제 길이가
   * 상한에서 조각 크기(4096샘플 ≈ 85ms)만큼 들쭉날쭉해진다.
   */
  push(chunk: Float32Array): boolean {
    if (this.finished) throw new Error('이미 마무리된 버퍼')
    const room = this.maxSamples - this.captured
    // 이미 가득 찼다. 자동 정지가 진행 중인 동안 들어오는 조각이 여기 해당한다 — 오류가
    // 아니라 정상 경로라서 조용히 무시하고 "다 찼다"만 다시 알린다.
    if (room <= 0) return true

    const taken = chunk.length <= room ? chunk : chunk.subarray(0, room)
    /*
     * **반드시 복사한다.** AudioWorklet·ScriptProcessor가 주는 버퍼는 다음 블록에서 재사용될
     * 수 있어서, 참조만 들고 있으면 나중에 마무리할 때 전부 마지막 조각의 내용으로 덮여 있다.
     * 네이티브에서 `AudioRecord.read` 버퍼를 `copyOf(read)`로 떠 담는 것과 같은 이유다
     * (audio-capture.md). "지금은 전송(transfer)이라 안 겹친다"에 기대지 않는 이유는, 캡처
     * 구현이 바뀌는 순간 증상이 소리 자체가 뭉개지는 형태로만 나타나 추적이 어렵기 때문이다.
     */
    this.chunks.push(taken.slice())
    this.captured += taken.length
    return this.captured >= this.maxSamples
  }

  /**
   * 모은 조각을 업로드 형태로 마무리한다. 한 번만 부를 수 있다.
   *
   * 변환 순서는 Stage 1이 정한 그대로다: 이어붙이기 → 16kHz 리샘플 → 16-bit 정수 →
   * (WAV 인코딩 + 품질 판정). 품질 판정을 리샘플 **뒤** 에 하는 이유는 서버가 보는 것과 같은
   * 데이터를 봐야 하기 때문이다 — 원본 48kHz에서 잰 RMS로 통과시켰는데 서버가 16kHz 파일에서
   * 다시 재 되돌려보내면 클라이언트 게이트가 무의미해진다.
   *
   * 마무리 뒤에는 조각 참조를 버린다 (FR-AD-04: 오디오를 필요 이상으로 들고 있지 않는다).
   */
  finish(): Recording {
    if (this.finished) throw new Error('이미 마무리된 버퍼')
    this.finished = true

    const merged = new Float32Array(this.captured)
    let offset = 0
    for (const chunk of this.chunks) {
      merged.set(chunk, offset)
      offset += chunk.length
    }
    // 원본 조각은 여기서 놓는다. 아래 변환들이 각자 새 배열을 만들므로 더 들고 있을 이유가 없다.
    this.chunks = []

    const pcm = floatToInt16(resampleTo16k(merged, this.sampleRate))
    const durationMs = this.durationMs

    return {
      wav: encodeWav16kMono(pcm),
      durationMs,
      sourceSampleRate: this.sampleRate,
      quality: measure(pcm),
      status: judge(pcm, durationMs),
    }
  }
}
