/**
 * Float32 PCM → 16-bit 정수 PCM 변환 (KAN-56 Stage 1).
 *
 * 브라우저 `AudioContext`가 주는 샘플은 -1.0 ~ +1.0 실수인데, 서버가 받는 WAV는 16비트 정수다.
 * 그 사이를 잇는 한 단계만 여기 둔다 — 이 파일에는 DOM도 AudioContext도 없어서 vitest가
 * 그냥 함수로 부를 수 있다.
 */

/** 서버가 요구하는 샘플레이트 (API 명세서 §3.3). 리샘플러·WAV 인코더의 목표값이다 */
export const TARGET_SAMPLE_RATE = 16000

/**
 * 16-bit 정수로 올릴 때 쓰는 배율.
 *
 * 16비트의 실제 범위는 -32768 ~ +32767로 **음수 쪽이 한 칸 넓다.** 32768을 곱하면 -1.0은
 * -32768로 정확히 떨어지지만 +1.0이 +32768이 되어 범위를 넘고, `Int16Array`가 이를 감싸며
 * -32768로 뒤집는다 — 최대 진폭 구간이 통째로 부호가 뒤집히는 최악의 왜곡이다. 그래서
 * 양쪽 모두 32767로 잡는다. 대가는 최대 진폭이 0.0003 dB 낮아지는 것뿐이라 들리지 않는다.
 */
export const FULL_SCALE_INT16 = 32767

/**
 * -1..+1 실수 샘플을 16-bit 정수 PCM으로 바꾼다.
 *
 * 범위를 벗어난 값은 던지지 않고 **잘라낸다(clamp).** 웹오디오 그래프에 게인이 하나만 끼어도
 * 1.0을 살짝 넘는 샘플이 나오는데, 그때 녹음 전체를 실패시키는 것보다 그 샘플만 최대치로
 * 눕히는 편이 낫다. 넘친 샘플이 많으면 [ClientQuality]의 `clipped`가 대신 알려 준다.
 */
export function floatToInt16(samples: Float32Array): Int16Array {
  const pcm = new Int16Array(samples.length)
  for (let i = 0; i < samples.length; i++) {
    const clamped = Math.max(-1, Math.min(1, samples[i]))
    // Math.round는 .5를 항상 위(+∞)로 올린다. 부호에 따라 반올림 방향이 갈리지만 1 LSB
    // 차이라 −90 dBFS 수준이고, 어느 쪽으로 통일하든 들리는 차이가 없어 표준 동작에 맡긴다.
    pcm[i] = Math.round(clamped * FULL_SCALE_INT16)
  }
  return pcm
}
