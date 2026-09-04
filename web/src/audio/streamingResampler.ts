/**
 * 분석 경로 전용 스트리밍 16kHz 리샘플러 (KAN-56 Stage 5).
 *
 * ## 왜 배치 리샘플러를 조각마다 부르면 안 되는가
 *
 * {@link resampleTo16k}는 **녹음 한 건 전체**를 한 번에 받는 함수다. 그 함수는 배열 밖의
 * 샘플을 0으로 간주하는데(그래야 녹음 시작·끝이 "무음이 이어진다"로 해석돼 딸깍 소리가
 * 안 난다), 조각마다 부르면 그 규칙이 **조각 경계마다** 걸린다. 128탭 커널의 절반인 64샘플이
 * 매 조각의 앞뒤에서 0쪽으로 끌려가, 85ms짜리 조각을 이어 붙였을 때 1.3ms짜리 페이드가
 * 초당 12번 찍힌 신호가 된다. YIN이 보는 창(2048샘플)에 그런 계단이 두어 개씩 들어가면
 * 차분 함수 d(τ)의 극소가 흐려져 무성 판정이 늘고, 곡선이 조각난다.
 *
 * 그래서 이 클래스는 **입력 이력과 출력 위치를 조각 사이에 걸쳐 들고 있는다.** 출력 n번째
 * 샘플의 입력축 위치(`n × inputRate/16000`)는 녹음 시작부터 이어지는 전역 좌표이고, 그
 * 위치의 커널 평가에 필요한 앞뒤 64샘플을 이력 버퍼가 항상 쥐고 있다. 조각 경계는 계산에
 * 아무 흔적도 남기지 않는다 — 어디서 잘라 넣든 결과가 배치와 같다(`streamingResampler.test.ts`).
 *
 * 커널·차단 주파수·가중치 정규화는 전부 `resample.ts`에서 가져다 쓴다. 두 경로가 다른
 * 필터를 쓰면 서버가 받은 오디오와 사용자가 화면에서 본 곡선이 다른 신호에서 나온다.
 *
 * ## 꼬리는 [flush]에서만 나온다
 *
 * 출력 하나를 확정하려면 그 위치의 **오른쪽 64샘플까지** 들어와 있어야 한다. 아직 안 들어온
 * 입력을 0으로 치고 내보내면 그게 곧 위에서 없앤 조각 경계 페이드다. 그래서 [push]는 오른쪽
 * 지지 구간이 다 찬 출력만 내보내고, 남은 꼬리(입력 64샘플 = 48kHz에서 1.3ms, 출력 약 21샘플)는
 * 다음 조각이 오거나 [flush]를 부를 때까지 쥐고 있다. 실시간 곡선은 프레임 하나가 32ms라
 * 이 지연을 볼 방법이 없어 [flush]를 부르지 않고, 배치와의 동치를 검사하는 테스트만 부른다.
 */

import { TARGET_SAMPLE_RATE } from './pcm'
import { TAP_COUNT, kernelAt, kernelFor, outputLength } from './resample'

export class StreamingResampler {
  /** 이미 목표 레이트면 필터를 태우지 않는다 ({@link resampleTo16k}와 같은 판정) */
  private readonly passthrough: boolean
  private readonly kernel: Float32Array
  /** 출력 한 칸이 입력축에서 차지하는 폭 */
  private readonly step: number
  private readonly half = TAP_COUNT / 2

  /** 아직 필요할 수 있는 입력 샘플. `history[0]`의 전역 위치가 [historyStart]다 */
  private history = new Float32Array(0)
  private historyLength = 0
  private historyStart = 0

  /** 지금까지 들어온 입력 샘플 총수 = 다음 입력의 전역 위치 */
  private totalInput = 0
  /** 다음에 내보낼 출력 샘플의 전역 번호 */
  private outputIndex = 0

  constructor(readonly inputRate: number) {
    if (!Number.isFinite(inputRate) || inputRate <= 0) {
      throw new RangeError(`입력 샘플레이트가 유효하지 않다: ${inputRate}`)
    }
    this.passthrough = inputRate === TARGET_SAMPLE_RATE
    // 통과 모드에서도 필드는 채워 둔다 — 분기마다 null 검사를 다는 것보다 싸다.
    this.kernel = this.passthrough ? new Float32Array(0) : kernelFor(inputRate)
    this.step = inputRate / TARGET_SAMPLE_RATE
  }

  /**
   * 조각 하나를 밀어넣고 **이번에 확정된 16kHz 샘플**을 돌려준다. 확정된 것이 없으면 빈 배열.
   * 원본 조각은 건드리지 않는다.
   */
  push(chunk: Float32Array): Float32Array {
    if (this.passthrough) {
      this.totalInput += chunk.length
      this.outputIndex += chunk.length
      return chunk.slice()
    }
    if (chunk.length > 0) this.append(chunk)
    // 오른쪽 지지 구간(center + half)이 다 들어온 출력까지만 확정한다.
    return this.emitUntil((center) => Math.floor(center + this.half) < this.totalInput)
  }

  /**
   * 남은 꼬리를 마저 내보낸다. 입력이 끝났다고 보고 배열 밖을 0으로 채우므로,
   * 전체 출력 길이가 {@link resampleTo16k}와 같아진다. 한 번 부른 뒤 [push]를 이어 부르면
   * 그 경계에 배치와 같은 페이드가 남으므로, **녹음이 끝난 뒤 한 번만** 부른다.
   */
  flush(): Float32Array {
    if (this.passthrough) return new Float32Array(0)
    const total = outputLength(this.totalInput, this.inputRate)
    return this.emitUntil(() => this.outputIndex < total)
  }

  /** 조건이 참인 동안 출력을 하나씩 확정한다. 확정된 뒤에는 더 볼 일 없는 이력을 버린다 */
  private emitUntil(ready: (center: number) => boolean): Float32Array {
    const out: number[] = []
    for (;;) {
      const center = this.outputIndex * this.step
      if (!ready(center)) break
      out.push(this.sampleAt(center))
      this.outputIndex++
    }
    this.trimHistory()
    return Float32Array.from(out)
  }

  /**
   * 입력축 [center] 지점의 대역제한 보간값. 식은 {@link resampleTo16k}의 루프와 같다 —
   * 범위 밖 탭도 gain에는 더해, 녹음 앞머리가 "0으로 채워진 무음이 이어진다"로 해석되게 한다.
   */
  private sampleAt(center: number): number {
    const first = Math.ceil(center - this.half)
    const last = Math.floor(center + this.half)
    let sum = 0
    let gain = 0
    for (let k = first; k <= last; k++) {
      const weight = kernelAt(this.kernel, center - k)
      gain += weight
      if (k >= 0 && k < this.totalInput) {
        const local = k - this.historyStart
        if (local >= 0 && local < this.historyLength) sum += weight * this.history[local]
      }
    }
    return gain === 0 ? 0 : sum / gain
  }

  private append(chunk: Float32Array) {
    const needed = this.historyLength + chunk.length
    if (needed > this.history.length) {
      // 조각 크기가 오르내려도 재할당이 잦지 않게 두 배씩 키운다.
      let size = Math.max(this.history.length, TAP_COUNT * 2)
      while (size < needed) size *= 2
      const grown = new Float32Array(size)
      grown.set(this.history.subarray(0, this.historyLength))
      this.history = grown
    }
    this.history.set(chunk, this.historyLength)
    this.historyLength = needed
    this.totalInput += chunk.length
  }

  /**
   * 다음 출력이 볼 수 있는 가장 왼쪽 탭보다 앞은 버린다. 이걸 안 하면 이력이 녹음 길이만큼
   * 자라 10초 녹음에 48만 샘플을 이중으로 들고 있게 된다.
   */
  private trimHistory() {
    const firstNeeded = Math.max(0, Math.ceil(this.outputIndex * this.step - this.half))
    const drop = firstNeeded - this.historyStart
    if (drop <= 0) return
    const keep = Math.max(0, this.historyLength - drop)
    this.history.copyWithin(0, drop, this.historyLength)
    this.historyLength = keep
    this.historyStart = firstNeeded
  }
}
