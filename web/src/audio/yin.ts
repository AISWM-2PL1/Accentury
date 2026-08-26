/**
 * YIN F0 추정기 — 앱 `YinPitchEstimator.kt`의 1:1 포팅 (KAN-56 Stage 5).
 *
 * 원 논문은 de Cheveigné & Kawahara, JASA 111(4), 2002. 선정 배경과 임계값 실측표는
 * `docs/wiki/ondevice-f0.md`에 있고, 이 파일은 **그 규칙을 브라우저에서 똑같이 재현**하는 것만
 * 맡는다. 같은 목소리를 앱에서 읽었을 때와 웹에서 읽었을 때 곡선이 다르면, 사용자에게는
 * 기기 탓으로 보이고 팀에게는 재현되지 않는 버그로 보인다 (`quality.ts` 헤더와 같은 논리).
 *
 * ## 알고리즘 한 문단
 *
 * 파형을 τ만큼 민 복사본과 겹쳐 놓고 오차 제곱합 d(τ)를 잰다. 한 주기만큼 밀면 파형이
 * 자기 자신과 겹치므로 d(τ)가 푹 꺼지고, 그 τ가 곧 주기다(F0 = 표본율/τ). 그냥 d(τ)만 보면
 * τ가 작을수록 값이 작아지는 편향 때문에 배음(주기의 1/2·1/3)을 주기로 착각하는데 — 그게
 * 옥타브 오류다 — d(τ)를 τ까지의 누적 평균으로 나눈 **CMNDF**가 그 편향을 지운다. 마지막으로
 * CMNDF가 임계값 아래로 처음 내려간 골을 찾고, 정수 τ 이웃 3점을 포물선으로 근사해
 * 실수 주기를 얻는다(양자화 오차 제거).
 *
 * ## 네이티브와 다른 곳: 진폭 스케일 하나뿐이다
 *
 * 앱은 `AudioRecord`가 주는 16-bit 정수(-32768..32767)를 그대로 넣고, 브라우저는
 * `AudioContext`가 주는 -1..+1 실수를 넣는다. CMNDF는 비율이라 스케일이 통째로 바뀌어도
 * 결과가 같지만, **에너지 게이트만은 절대값 비교라** 스케일을 되돌려야 한다
 * ([VOICED_MIN_RMS] 주석).
 */

import { FULL_SCALE, QUIET_RMS_THRESHOLD } from './quality'
import { TARGET_SAMPLE_RATE } from './pcm'

/** 사람 목소리 F0 탐색 대역. 대역 밖(예: 50Hz 험 노이즈)은 무성음 취급된다 */
export const MIN_F0_HZ = 80
export const MAX_F0_HZ = 400

/**
 * CMNDF 절대 임계값. 원 논문 권장은 0.1~0.2지만 0.25로 느슨하게 잡았다 — 우리 용도가
 * 정밀 F0 측정이 아니라 실시간 억양 곡선이라 판단 기준이 "끊김 < 약간의 오검출"이기 때문이다.
 * 임계값별 유성 판정률 실측표는 `ondevice-f0.md`에 있다.
 */
const CMNDF_THRESHOLD = 0.25

/**
 * 유성 판정을 시도할 최소 RMS. **정규화 전 16-bit 원 스케일 기준**이라 -1..+1 샘플의 RMS에
 * {@link FULL_SCALE}을 곱해 비교한다 (`quality.ts`의 `judge`가 TOO_QUIET을 가르는 식과 같다).
 *
 * 값을 여기서 다시 쓰지 않고 {@link QUIET_RMS_THRESHOLD}를 그대로 가져오는 것이 요점이다.
 * 두 문턱이 갈리면 "품질 판정은 통과했는데 곡선은 안 그려지는" 구간이 생기고, 그때 사용자에게
 * 설명할 방법이 없다 (`pitch-curve.md` §5의 앱 쪽 논거와 같다).
 */
export const VOICED_MIN_RMS = QUIET_RMS_THRESHOLD

/**
 * 한 조각의 F0(Hz)를 추정한다. 무성음이거나 판별 불가면 null.
 * 조각이 탐색에 필요한 최소 길이(τmax의 2배)보다 짧아도 null이다.
 *
 * @param chunk 16kHz -1..+1 실수 샘플 (모노)
 */
export function estimatePitchHz(chunk: Float32Array, sampleRate = TARGET_SAMPLE_RATE): number | null {
  const tauMin = Math.floor(sampleRate / MAX_F0_HZ) // 16kHz 기준 40샘플
  const tauMax = Math.floor(sampleRate / MIN_F0_HZ) // 16kHz 기준 200샘플
  const window = chunk.length - tauMax // 적분 창: x[j+τ]가 조각을 벗어나지 않는 범위
  if (window <= tauMax) return null

  // 에너지 게이트 - CMNDF 계산 앞에 둬서 무음 프레임은 O(W·τ) 연산을 아예 건너뛴다.
  if (rmsOf(chunk) * FULL_SCALE < VOICED_MIN_RMS) return null

  // 1단계 - 차분 함수 d(τ): 파형을 τ만큼 민 복사본과의 오차 제곱합.
  const d = new Float64Array(tauMax + 1)
  for (let tau = 1; tau <= tauMax; tau++) {
    let sum = 0
    for (let j = 0; j < window; j++) {
      const diff = chunk[j] - chunk[j + tau]
      sum += diff * diff
    }
    d[tau] = sum
  }

  // 2단계 - CMNDF: d(τ)를 τ까지의 누적 평균으로 나눠 정규화. 작은 τ 편향을 지운다.
  const cmndf = new Float64Array(tauMax + 1)
  cmndf[0] = 1
  let runningSum = 0
  for (let tau = 1; tau <= tauMax; tau++) {
    runningSum += d[tau]
    // 무음이면 runningSum이 0 - 나눗셈 방지 겸 무성음으로 흘려보낸다.
    cmndf[tau] = runningSum === 0 ? 1 : (d[tau] * tau) / runningSum
  }

  // 3단계 - 절대 임계값: 탐색 대역 안에서 임계값 아래로 처음 내려간 지점을 찾고,
  //         국소 최솟값까지 하강한다. 못 찾으면 무성음.
  let tau = tauMin
  while (tau <= tauMax && cmndf[tau] >= CMNDF_THRESHOLD) tau++
  if (tau > tauMax) return null
  while (tau + 1 <= tauMax && cmndf[tau + 1] < cmndf[tau]) tau++

  // 4단계 - 포물선 보간. 대역 경계 τ에서 보간이 대역을 살짝 벗어날 수 있어(예: τ=40 → 400Hz 초과)
  //         결과를 탐색 대역으로 clamp한다.
  const f0 = sampleRate / parabolicInterpolation(cmndf, tau)
  return Math.min(MAX_F0_HZ, Math.max(MIN_F0_HZ, f0))
}

/** -1..+1 샘플의 RMS. `quality.ts`의 `measure`가 쓰는 식과 같다(그쪽은 정수 스케일) */
function rmsOf(chunk: Float32Array): number {
  let squareSum = 0
  for (let i = 0; i < chunk.length; i++) squareSum += chunk[i] * chunk[i]
  return Math.sqrt(squareSum / chunk.length)
}

function parabolicInterpolation(cmndf: Float64Array, tau: number): number {
  if (tau <= 0 || tau >= cmndf.length - 1) return tau
  const s0 = cmndf[tau - 1]
  const s1 = cmndf[tau]
  const s2 = cmndf[tau + 1]
  const denom = 2 * (2 * s1 - s2 - s0)
  if (denom === 0) return tau
  return tau + (s2 - s0) / denom
}
