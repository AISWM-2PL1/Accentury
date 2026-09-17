import { describe, expect, it } from 'vitest'
import { FULL_SCALE } from './quality'
import { TARGET_SAMPLE_RATE } from './pcm'
import { MAX_F0_HZ, VOICED_MIN_RMS, estimatePitchHz } from './yin'
import { WINDOW_SIZE } from './overlappedFramer'

/**
 * 앱 `YinPitchEstimatorTest`의 사인파 생성기와 같다. 진폭만 -1..+1 스케일로 옮겼다 —
 * 네이티브의 진폭 8000은 여기서 8000/32768이다.
 */
function sine(freqHz: number, { amplitude = 8000 / FULL_SCALE, size = WINDOW_SIZE } = {}): Float32Array {
  const chunk = new Float32Array(size)
  for (let i = 0; i < size; i++) {
    chunk[i] = amplitude * Math.sin((2 * Math.PI * freqHz * i) / TARGET_SAMPLE_RATE)
  }
  return chunk
}

/** 기음과 배음을 섞은 신호. `amplitudes[k]`가 (k+1)배음의 원 스케일 진폭이다 */
function harmonics(f0Hz: number, amplitudes: number[], size = WINDOW_SIZE): Float32Array {
  const chunk = new Float32Array(size)
  for (let i = 0; i < size; i++) {
    const t = (2 * Math.PI * f0Hz * i) / TARGET_SAMPLE_RATE
    let sum = 0
    amplitudes.forEach((amplitude, k) => (sum += amplitude * Math.sin((k + 1) * t)))
    chunk[i] = sum / FULL_SCALE
  }
  return chunk
}

/** -1..+1 샘플의 RMS를 원 스케일로 되돌린 값. 게이트 테스트가 전제를 확인하는 데 쓴다 */
function rawRms(chunk: Float32Array): number {
  let sum = 0
  for (let i = 0; i < chunk.length; i++) sum += chunk[i] * chunk[i]
  return Math.sqrt(sum / chunk.length) * FULL_SCALE
}

/** 결정적 난수. 무성음 판정 결과가 실행마다 흔들리면 실패를 재현할 수 없다 */
function seededNoise(size: number, seed = 42): Float32Array {
  let state = seed
  const chunk = new Float32Array(size)
  for (let i = 0; i < size; i++) {
    state = (state * 1103515245 + 12345) % 2147483648
    chunk[i] = ((state / 2147483648) * 16000 - 8000) / FULL_SCALE
  }
  return chunk
}

describe('YIN F0 추정 (앱 YinPitchEstimatorTest 이식)', () => {
  it('220Hz 사인파의 F0를 추정한다', () => {
    const f0 = estimatePitchHz(sine(220))
    expect(f0).not.toBeNull()
    expect(Math.abs(f0! - 220)).toBeLessThanOrEqual(3)
  })

  it('저음 경계 근처 100Hz를 추정한다', () => {
    expect(Math.abs(estimatePitchHz(sine(100))! - 100)).toBeLessThanOrEqual(3)
  })

  it('고음 350Hz를 추정한다', () => {
    expect(Math.abs(estimatePitchHz(sine(350))! - 350)).toBeLessThanOrEqual(4)
  })

  it('배음이 섞여도 기본 주파수를 잡는다 - 옥타브 오류 없음', () => {
    // 실제 목소리처럼 2, 3배음 포함. 단순 autocorrelation이 배음(240Hz)으로 튀던 케이스.
    expect(Math.abs(estimatePitchHz(harmonics(120, [5000, 3000, 2000]))! - 120)).toBeLessThanOrEqual(3)
  })

  it('대역 상한 경계 790Hz도 800Hz를 넘기지 않는다', () => {
    const f0 = estimatePitchHz(sine(790))!
    expect(Math.abs(f0 - 790)).toBeLessThanOrEqual(8)
    expect(f0).toBeLessThanOrEqual(MAX_F0_HZ)
  })

  it('대역 밖 820Hz는 800Hz 초과 값을 반환하지 않는다', () => {
    // τmin=20 경계에서 보간이 대역 밖으로 새는지 확인. null 또는 clamp된 값만 허용.
    const f0 = estimatePitchHz(sine(820))
    expect(f0 === null || f0 <= MAX_F0_HZ).toBe(true)
  })

  it('옛 상한(400Hz) 위의 고음은 옥타브 아래로 뒤집히지 않는다 - 곡선이 천장으로 간다', () => {
    // 80~400Hz 대역에서는 450Hz가 2주기 골(τ=71)에 잡혀 225Hz로 나왔다. 감탄·고성이 올라가야
    // 할 선을 아래로 꺾던 원인이다.
    for (const hz of [450, 520, 600]) {
      expect(Math.abs(estimatePitchHz(harmonics(hz, [5000, 3000, 2000]))! - hz)).toBeLessThanOrEqual(hz * 0.02)
    }
  })

  it('2배음이 기음보다 강해도 옥타브 위로 튀지 않는다 - 새 대역에서 노출된 반주기 골을 2τ 검사가 잡는다', () => {
    // 기음 3000, 2배음 8000. 반주기 골의 CMNDF가 0.25 아래로 내려와 τ 탐색이 거기서 멈추면
    // 250Hz가 503Hz로 읽힌다. 옛 대역(τmin=40)은 기음 200Hz 이상의 반주기가 탐색 밖이라 우연히
    // 보호됐고, 120Hz 같은 남성 음역은 옛 코드도 같은 오류가 있었다.
    for (const hz of [120, 250, 350]) {
      expect(Math.abs(estimatePitchHz(harmonics(hz, [3000, 8000]))! - hz)).toBeLessThanOrEqual(hz * 0.02)
    }
  })

  it('2·4배음 우세 프로파일도 기음을 잡는다', () => {
    for (const hz of [100, 200, 300]) {
      expect(Math.abs(estimatePitchHz(harmonics(hz, [2000, 6000, 1500, 5000]))! - hz)).toBeLessThanOrEqual(
        hz * 0.02,
      )
    }
  })

  it('진짜 고음은 2τ 검사에 뒤집히지 않는다', () => {
    // 기음 우세 고음은 2τ 골도 ≈0이라 첫 골과의 차이가 마진(0.1)을 못 넘는다.
    for (const hz of [450, 600, 780]) {
      expect(Math.abs(estimatePitchHz(harmonics(hz, [5000, 3000, 2000]))! - hz)).toBeLessThanOrEqual(hz * 0.02)
    }
  })

  it('옛 하한(80Hz) 아래의 저음도 유성으로 잡는다 - 곡선이 바닥에 남는다', () => {
    // 80~400Hz 대역에서는 70Hz가 τmax=200 밖이라 무성(null)이 되어 선이 끊겼다.
    expect(Math.abs(estimatePitchHz(sine(70))! - 70)).toBeLessThanOrEqual(2)
    expect(Math.abs(estimatePitchHz(sine(62))! - 62)).toBeLessThanOrEqual(2)
  })

  it('무음은 무성음으로 판정한다', () => {
    expect(estimatePitchHz(new Float32Array(WINDOW_SIZE))).toBeNull()
  })

  it('백색잡음은 무성음으로 판정한다', () => {
    const noise = seededNoise(WINDOW_SIZE)
    // 잡음 RMS는 에너지 게이트를 한참 넘는다. 게이트가 아니라 CMNDF가 무성음으로
    // 판정해야 이 테스트에 의미가 있다.
    expect(rawRms(noise)).toBeGreaterThan(VOICED_MIN_RMS)
    expect(estimatePitchHz(noise)).toBeNull()
  })

  it('탐색 대역 밖 저주파는 무성음으로 판정한다', () => {
    // 50Hz(주기 320샘플)는 τmax=266 안에서 겹치는 지점이 없다.
    expect(estimatePitchHz(sine(50))).toBeNull()
  })

  it('탐색에 필요한 길이보다 짧은 조각은 무성음으로 판정한다', () => {
    expect(estimatePitchHz(sine(220, { size: 256 }))).toBeNull()
  })

  it('에너지 게이트 아래의 작은 사인파는 무성음으로 판정한다', () => {
    // 원 스케일 진폭 50이면 RMS는 약 35 - 게이트(100) 아래라 CMNDF와 무관하게 판정하지 않는다.
    expect(estimatePitchHz(sine(220, { amplitude: 50 / FULL_SCALE }))).toBeNull()
  })

  it('에너지 게이트 위면 작은 진폭이어도 F0를 추정한다', () => {
    const f0 = estimatePitchHz(sine(220, { amplitude: 3000 / FULL_SCALE }))!
    expect(Math.abs(f0 - 220)).toBeLessThanOrEqual(3)
  })

  it('에너지 게이트 경계를 사이에 두고 판정이 갈린다', () => {
    // 사인파 RMS = 진폭/√2. 원 스케일 진폭 138이면 약 97.6(게이트 아래), 160이면 약 113(위).
    expect(estimatePitchHz(sine(220, { amplitude: 138 / FULL_SCALE }))).toBeNull()

    const f0 = estimatePitchHz(sine(220, { amplitude: 160 / FULL_SCALE }))!
    expect(Math.abs(f0 - 220)).toBeLessThanOrEqual(3)
  })

  it('게이트 값이 품질 판정의 문턱과 같다', () => {
    // 두 값이 갈리면 "품질은 통과했는데 곡선은 안 나온다"가 생긴다 (pitch-curve.md §5)
    expect(VOICED_MIN_RMS).toBe(100)
  })
})
