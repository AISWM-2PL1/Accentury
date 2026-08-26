/**
 * 브라우저 캡처 레이트 → 16kHz 리샘플링 (KAN-56 Stage 1).
 *
 * ## 왜 "3개 중 1개만 골라내기"로 끝내면 안 되는가
 *
 * 48000Hz를 16000Hz로 줄이는 일은 얼핏 샘플을 3개마다 하나씩 집으면 끝나 보인다. 실제로는
 * 그 순간 **에일리어싱(aliasing)** 이 생긴다. 초당 16000번밖에 안 보는 관찰자에게 8000Hz보다
 * 빠른 진동은 "빠른 진동"으로 보이지 않고 **느린 진동으로 둔갑한다.** 마차 바퀴가 너무 빨리
 * 돌면 영상에서 거꾸로 도는 것처럼 보이는 것과 같은 현상이다 — 초당 24장으로는 그 속도를
 * 표현할 수 없으니 카메라가 표현 가능한 다른 속도로 접어 버린다.
 *
 * 문제는 접힌 결과가 하필 **F0 대역에 내려앉는다**는 점이다. 9000Hz 잡음은 16000Hz 격자에서
 * 7000Hz로 접히고, 마이크 화이트노이즈나 치찰음처럼 넓게 퍼진 고역은 저역 전체에 골고루
 * 깔린다. 한 번 접힌 성분은 원래 무엇이었는지 정보가 남지 않아 **서버가 되돌릴 수 없다**
 * (지라 코멘트 2026-08-09). 그래서 반드시 *줄이기 전에* 고역을 미리 지워야 한다.
 *
 * ## 어떻게 지우는가 — 윈도우드 싱크(windowed sinc)
 *
 * "8000Hz 위는 전부 0, 아래는 전부 그대로"인 이상적인 저역통과 필터를 시간축으로 옮기면
 * `sinc(x) = sin(πx)/πx` 모양이 된다. 가운데가 가장 크고 양옆으로 물결이 영원히 이어지는
 * 곡선이다. 영원히 계산할 수는 없으니 앞뒤 {@link TAP_COUNT}개만 잘라 쓰는데, 그냥 자르면
 * 잘린 끝에서 생긴 급격한 단절이 주파수축에 잔물결(Gibbs 현상)로 되돌아와 차단 성능을 망친다.
 * 그래서 양 끝이 부드럽게 0으로 잦아드는 **블랙만 창(Blackman window)** 을 곱해 자른다.
 * 블랙만을 고른 이유는 카이저(Kaiser)와 달리 베셀 함수 구현이 필요 없으면서도 저지대역을
 * 약 -74 dB까지 눌러 주기 때문이다 — 요구치인 -40 dB에 30 dB 이상 여유가 있다.
 *
 * ## 정수비·비정수비를 한 코드로
 *
 * 48000→16000은 정확히 3:1이지만 44100→16000은 2.75625:1이라 "몇 개마다 하나"로 표현되지 않는다.
 * 그래서 데시메이션(솎아내기)이라는 개념 자체를 쓰지 않고, **출력 n번째 샘플이 입력축의
 * 어디에 해당하는지**(= `n × inputRate/16000`)를 먼저 구한 뒤 그 위치에서 위 필터를 직접
 * 평가한다(대역제한 보간). 정수 위치든 소수 위치든 계산이 똑같아 두 경우에 코드가 갈리지 않는다.
 */

import { TARGET_SAMPLE_RATE } from './pcm'

/**
 * 차단 주파수 비율. 두 레이트 중 **낮은 쪽** 나이퀴스트(= 레이트의 절반)의 90%,
 * 즉 16kHz 출력에서는 7200Hz를 6 dB 지점으로 잡는다.
 *
 * 8000Hz에 딱 붙이지 않는 이유는 유한한 탭 수로는 차단이 절벽이 아니라 비탈이기 때문이다.
 * 8000Hz를 6 dB 지점으로 삼으면 비탈의 위쪽 절반이 8000Hz를 넘어가 그대로 접혀 들어온다.
 * 7200Hz로 물리면 비탈 전체가 나이퀴스트 안쪽에 들어온다. 대가는 7kHz 부근이 비탈에 걸려
 * -3 dB쯤 낮아지는 것인데, F0(성인 남성 85~180Hz·여성 165~255Hz) 분석에도 음성 명료도에도
 * 쓰이지 않는 구간이라 실질 손실이 없다.
 */
export const CUTOFF_RATIO = 0.45

/**
 * FIR 탭 수 (입력 샘플 단위 폭). 블랙만 창의 천이 폭은 대략 `5.5/N × 레이트`라
 * 128탭이면 48kHz에서 약 2.1kHz — 7200Hz에서 시작한 비탈이 8.2kHz 전에 저지대역에 닿는다.
 * 64탭이면 비탈이 9.3kHz까지 끌려 나가 테스트 신호(9000Hz)가 천이 구간에 걸린다.
 *
 * 실측(`resample.test.ts`): 48kHz의 9000Hz가 -81 dB까지 눌린다(요구치 -40 dB). 6000Hz까지의
 * 통과대역 진폭 오차는 0.02% 미만이고, 480k 샘플(10초)이 Node에서 45ms에 끝난다.
 */
export const TAP_COUNT = 128

/**
 * 커널을 미리 계산해 둘 해상도 (입력 샘플 1칸을 몇 등분해 저장할지).
 *
 * 출력 위치가 소수라 커널을 임의의 지점에서 평가해야 하는데, 매번 `Math.sin`을 부르면
 * 10초 오디오에 2000만 번이 넘는다. 촘촘한 표를 한 번 만들어 두고 이웃 두 칸을 선형보간하면
 * 오차가 1e-6 수준(-120 dB)으로 저지대역보다 한참 아래라 성능만 얻고 품질은 잃지 않는다.
 */
export const KERNEL_RESOLUTION = 512

/** 입력 레이트마다 커널이 달라지므로(차단 주파수가 정규화 단위로 바뀐다) 레이트별로 캐시한다 */
const kernelCache = new Map<number, Float32Array>()

/**
 * 입력 PCM을 16kHz로 다시 샘플링한다. 원본은 건드리지 않고 새 배열을 돌려준다.
 *
 * @param input 브라우저가 캡처한 -1..+1 실수 샘플 (모노)
 * @param inputRate 캡처 레이트 (보통 48000 또는 44100)
 * @throws {RangeError} 레이트가 0 이하이거나 유한한 수가 아닐 때
 */
export function resampleTo16k(input: Float32Array, inputRate: number): Float32Array {
  if (!Number.isFinite(inputRate) || inputRate <= 0) {
    throw new RangeError(`입력 샘플레이트가 유효하지 않다: ${inputRate}`)
  }
  // 이미 목표 레이트면 필터를 태우지 않는다. 통과대역이라도 필터는 미세한 위상·진폭 변형을
  // 남기므로, 손댈 필요가 없을 때 손대지 않는 것이 가장 정확하다. 다만 호출자가 원본을
  // 이어서 쓰다 덮어쓰는 사고를 막으려 사본은 만든다.
  if (inputRate === TARGET_SAMPLE_RATE) return input.slice()
  if (input.length === 0) return new Float32Array(0)

  const kernel = kernelFor(inputRate)
  const half = TAP_COUNT / 2
  const step = inputRate / TARGET_SAMPLE_RATE
  const output = new Float32Array(outputLength(input.length, inputRate))

  for (let n = 0; n < output.length; n++) {
    const center = n * step
    const first = Math.ceil(center - half)
    const last = Math.floor(center + half)
    let sum = 0
    let gain = 0
    for (let k = first; k <= last; k++) {
      const weight = kernelAt(kernel, center - k)
      // 범위 밖 탭도 gain에는 더한다. 그래야 녹음 양 끝이 "0으로 채워진 무음이 이어진다"로
      // 해석된다 — 남은 탭만으로 정규화하면 첫 샘플이 두 배로 튀어 딸깍 소리가 된다.
      gain += weight
      if (k >= 0 && k < input.length) sum += weight * input[k]
    }
    output[n] = gain === 0 ? 0 : sum / gain
  }
  return output
}

/**
 * 리샘플 결과 길이. 정수비가 아니어도 재생 시간이 유지되도록 비율로 환산해 반올림한다
 * (48000×1초 → 16000, 44100×1초 → 16000).
 */
export function outputLength(inputLength: number, inputRate: number): number {
  return Math.round((inputLength * TARGET_SAMPLE_RATE) / inputRate)
}

/** 레이트별 커널을 캐시에서 꺼내거나 새로 만든다 */
function kernelFor(inputRate: number): Float32Array {
  const cached = kernelCache.get(inputRate)
  if (cached) return cached
  const built = buildKernel(inputRate)
  kernelCache.set(inputRate, built)
  return built
}

/**
 * 블랙만 창을 씌운 싱크 커널을 x ≥ 0 구간만 표로 만든다. 커널은 좌우대칭이라 절반만 있으면
 * 되고, {@link kernelAt}이 절댓값으로 접어 읽는다.
 */
function buildKernel(inputRate: number): Float32Array {
  const half = TAP_COUNT / 2
  // 차단 주파수를 "입력 샘플당 몇 주기"로 환산한다. 필터는 데시메이션 전, 입력 레이트에서 돈다.
  const cutoff = (CUTOFF_RATIO * Math.min(inputRate, TARGET_SAMPLE_RATE)) / inputRate
  const table = new Float32Array(half * KERNEL_RESOLUTION + 1)
  for (let i = 0; i < table.length; i++) {
    const x = i / KERNEL_RESOLUTION
    const t = 2 * cutoff * x
    const sinc = t === 0 ? 1 : Math.sin(Math.PI * t) / (Math.PI * t)
    // 중심 기준 블랙만 창. x = 0에서 1, x = half에서 정확히 0이 되어 끝이 매끄럽게 닫힌다.
    const window =
      0.42 + 0.5 * Math.cos((Math.PI * x) / half) + 0.08 * Math.cos((2 * Math.PI * x) / half)
    // 2×cutoff 배율은 이상적 저역통과의 DC 이득을 1로 맞추는 상수다.
    table[i] = 2 * cutoff * sinc * window
  }
  return table
}

/** 표에서 임의 지점의 커널 값을 선형보간으로 읽는다. 창 밖(|offset| ≥ half)은 0이다 */
function kernelAt(table: Float32Array, offset: number): number {
  const pos = Math.abs(offset) * KERNEL_RESOLUTION
  const i = Math.floor(pos)
  if (i >= table.length - 1) return 0
  return table[i] + (pos - i) * (table[i + 1] - table[i])
}
