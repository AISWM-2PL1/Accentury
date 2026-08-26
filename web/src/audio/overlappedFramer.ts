/**
 * 겹침 프레이밍 — 앱 `OverlappedFramer.kt`의 1:1 포팅 (KAN-56 Stage 5).
 *
 * 캡처 조각은 크기가 제멋대로다(브라우저 워클릿은 4096샘플, 네이티브는 `AudioRecord.read()`가
 * 요청보다 짧게 돌려주기도 한다). 조각당 F0를 한 번 추정하면 갱신 주기가 조각 크기에 끌려다녀
 * 48kHz 4096샘플이면 85ms, 16kHz로 줄인 뒤 2048창이면 128ms가 된다 — NFR-PF-02(100ms 이하)를
 * 못 맞춘다. 창을 [HOP_SIZE]만큼만 밀면 **창 길이는 YIN 탐색에 필요한 2048샘플을 유지한 채
 * 갱신 주기만 hop 기준으로 낮아진다.** 기본값 2048/512는 75% 겹침 = 16kHz에서 32ms 주기다.
 *
 * 상태가 있으므로 녹음 1회당 1개를 쓴다.
 */

/** 분석 창 하나. samples는 항상 창 길이이고, startSampleIndex는 녹음 시작 기준 전역 위치다 */
export interface AnalysisFrame {
  samples: Float32Array
  startSampleIndex: number
}

/** 분석 창 길이. YIN이 80Hz(τ=200샘플)까지 보려면 그 두 배 이상이 필요하다 */
export const WINDOW_SIZE = 2048

/** 창을 미는 간격. 16kHz에서 32ms — 곡선 갱신 주기가 곧 이 값이다 */
export const HOP_SIZE = 512

export class OverlappedFramer {
  /** buffer[0..count)는 아직 창으로 소비되지 않은 샘플이고, buffer[0]의 전역 위치가 bufferStart다 */
  private buffer: Float32Array
  private count = 0
  private bufferStart = 0

  constructor(
    private readonly windowSize = WINDOW_SIZE,
    private readonly hopSize = HOP_SIZE,
  ) {
    if (windowSize <= 0 || hopSize <= 0) throw new RangeError('windowSize/hopSize는 양수여야 한다')
    if (hopSize > windowSize) throw new RangeError('hopSize는 windowSize 이하여야 한다')
    this.buffer = new Float32Array(windowSize * 2)
  }

  /** 조각을 밀어넣고 이번에 완성된 창들을 순서대로 돌려준다. 완성된 창이 없으면 빈 배열 */
  push(chunk: Float32Array): AnalysisFrame[] {
    if (chunk.length === 0) return []
    this.ensureCapacity(this.count + chunk.length)
    this.buffer.set(chunk, this.count)
    this.count += chunk.length

    const frames: AnalysisFrame[] = []
    let offset = 0
    while (this.count - offset >= this.windowSize) {
      frames.push({
        // slice로 복사한다 - subarray로 넘기면 아래 copyWithin이 남의 창 내용을 덮어쓴다.
        samples: this.buffer.slice(offset, offset + this.windowSize),
        startSampleIndex: this.bufferStart + offset,
      })
      offset += this.hopSize
    }
    if (offset > 0) {
      // 다음 창 시작점 앞은 다시 볼 일이 없다. 버려서 버퍼가 녹음 길이만큼 자라지 않게 한다.
      this.buffer.copyWithin(0, offset, this.count)
      this.count -= offset
      this.bufferStart += offset
    }
    return frames
  }

  private ensureCapacity(needed: number) {
    if (needed <= this.buffer.length) return
    let size = this.buffer.length
    while (size < needed) size *= 2
    const grown = new Float32Array(size)
    grown.set(this.buffer.subarray(0, this.count))
    this.buffer = grown
  }
}
