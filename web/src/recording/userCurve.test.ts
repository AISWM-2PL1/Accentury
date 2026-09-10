import { describe, expect, it } from 'vitest'
import type { PitchFrame } from '../audio/pitchTracker'
import { REAL_GUIDE_F0 } from './guideF0Fixture'
import {
  CENTER_MIN_VOICED_FRAMES,
  HOLD_MAX_GAP_MS,
  USER_CURVE_EMA_ALPHA,
  USER_CURVE_SPAN_SEMITONE,
  fillShortGaps,
  guideDurationMs,
  reviewWindow,
  userCurveCenterHz,
  userCurveDisplayPoints,
  userCurveWindowMs,
} from './userCurve'

const FRAME_MS = 32
const CENTER_HZ = 200
const WINDOW_MS = 2000
const LONG_GAP_MS = 500

/**
 * 창 길이 검사가 쓰는 녹음 상한. 서버가 VOICE 문항에 붙이는 고정값과 같다
 * (`TestDefinition.VOICE_MAX_DURATION_MS`). 이 값보다 짧은 창은 상한과 무관하게 그대로다.
 */
const MAX_DURATION_MS = 10_000

/** Review 검사가 쓰는 가이드. 10ms 간격 101값 = 1000ms라 바닥이 딱 떨어진다 */
const GUIDE_INTERVAL = 10
const GUIDE_COUNT = 101
const GUIDE_MS = 1000

const frame = (timestampMs: number, pitchHz: number | null): PitchFrame => ({ timestampMs, pitchHz })

/** 실제 파이프라인과 같은 32ms 간격으로 프레임을 만든다. null은 무성 프레임이다 */
function frames(hz: (number | null)[], startMs = 0): PitchFrame[] {
  return hz.map((value, i) => frame(startMs + i * FRAME_MS, value))
}

/** 중심 잠금에 필요한 최소 유성 프레임. 전부 같은 값이라 중심이 곧 [hz]다 */
function centerFrames(hz = CENTER_HZ): PitchFrame[] {
  return Array.from({ length: CENTER_MIN_VOICED_FRAMES }, (_, i) => frame(i * FRAME_MS, hz))
}

/** 중심에서 [st] semitone 떨어진 Hz */
const semitone = (st: number, center = CENTER_HZ) => center * 2 ** (st / 12)

/** 중심 프레임 다음에 오는 프레임의 시각 */
const after = (gapMs: number) => (CENTER_MIN_VOICED_FRAMES - 1) * FRAME_MS + gapMs

const gapThenJump = (gapMs: number) => [...centerFrames(), frame(after(gapMs), semitone(7))]

/**
 * 앞 10프레임·뒤 10프레임이 침묵인 100프레임 녹음 (KAN-195 Review 검사용).
 * 실제 녹음이 이 꼴이다 — [녹음]을 누른 뒤 입을 떼기까지, 다 읽고 [정지]를 누르기까지가 비어 있다.
 */
const SILENCE_PADDED = frames([
  ...Array.from({ length: 10 }, () => null),
  ...Array.from({ length: 80 }, () => CENTER_HZ),
  ...Array.from({ length: 10 }, () => null),
])
const FIRST_VOICED_MS = 10 * FRAME_MS
const LAST_VOICED_MS = 89 * FRAME_MS
const VOICED_SPAN_MS = LAST_VOICED_MS - FIRST_VOICED_MS

/** 중심 프레임 뒤 [gapMs] 만큼 떨어진 곳에 7 semitone 점프를 두었을 때, 남은 옛 값의 비율 */
function residualAfterGap(gapMs: number): number {
  const jumpSt = 7
  const segments = userCurveDisplayPoints(gapThenJump(gapMs), WINDOW_MS)
  const points = segments[segments.length - 1]
  const onScreenSt = (0.5 - points[points.length - 1].y) * USER_CURVE_SPAN_SEMITONE
  // 옛 값이 0 semitone(중심)이었으므로, 목표에 못 미친 몫이 곧 옛 값의 잔존 비율이다
  return (jumpSt - onScreenSt) / jumpSt
}

/**
 * 앞뒤 가장자리가 무성이고, 32ms(200Hz)와 224ms(800Hz) 사이에 192ms짜리 구멍이 있다.
 * 두 옥타브 차이라 한가운데의 기하평균이 400Hz로 딱 떨어진다.
 */
const HOLE_192MS: PitchFrame[] = [
  frame(0, null),
  frame(32, 200),
  frame(64, null),
  frame(96, null),
  frame(128, null),
  frame(160, null),
  frame(192, null),
  frame(224, 800),
  frame(256, null),
]

describe('창 길이', () => {
  it('가이드 길이는 간격 곱하기 구간 수고 알 수 없으면 0이다', () => {
    expect(guideDurationMs(10, 101)).toBe(1000)
    expect(guideDurationMs(32, 11)).toBe(320)
    expect(guideDurationMs(null, null)).toBe(0)
    expect(guideDurationMs(10, 1)).toBe(0)
    expect(guideDurationMs(0, 101)).toBe(0)
  })

  it('창 길이는 가이드 길이의 두 배다', () => {
    expect(userCurveWindowMs(10, 101, MAX_DURATION_MS)).toBe(2000)
    expect(userCurveWindowMs(32, 11, MAX_DURATION_MS)).toBe(640)
  })

  it('발행본 실문항의 창은 그 문항 길이의 두 배다', () => {
    // 16ms 간격 240점이라 가이드가 3.824초, 창은 그 두 배인 7.648초다 (KAN-194)
    const { frameIntervalMs, values } = REAL_GUIDE_F0
    expect(guideDurationMs(frameIntervalMs, values.length)).toBe(3824)
    expect(userCurveWindowMs(frameIntervalMs, values.length, MAX_DURATION_MS)).toBe(7648)
  })

  it('두 배가 녹음 상한을 넘으면 상한에서 자른다 (KAN-195)', () => {
    // 가이드 5.98초 = 발행본 최장 문항(v63). 두 배면 11.96초라 10초 상한을 넘는다
    expect(userCurveWindowMs(20, 300, MAX_DURATION_MS)).toBe(MAX_DURATION_MS)
    // 상한과 정확히 같은 창은 자를 것이 없다
    expect(userCurveWindowMs(10, 501, MAX_DURATION_MS)).toBe(MAX_DURATION_MS)
    // 상한에 못 미치는 창은 상한이 있어도 그대로다
    expect(userCurveWindowMs(10, 500, MAX_DURATION_MS)).toBe(9980)
  })

  it('상한을 알 수 없으면 자르지 않는다 - 레인이 사라지는 것보다 넘치는 편이 낫다', () => {
    expect(userCurveWindowMs(20, 300, 0)).toBe(11960)
    expect(userCurveWindowMs(20, 300, -1)).toBe(11960)
    expect(userCurveWindowMs(20, 300, Number.NaN)).toBe(11960)
    expect(userCurveWindowMs(20, 300, Number.POSITIVE_INFINITY)).toBe(11960)
  })

  it('가이드를 쓸 수 없으면 창 길이는 폴백 1초의 두 배다', () => {
    expect(userCurveWindowMs(null, null, MAX_DURATION_MS)).toBe(2000)
    expect(userCurveWindowMs(10, 1, MAX_DURATION_MS)).toBe(2000)
    expect(userCurveWindowMs(10, 0, MAX_DURATION_MS)).toBe(2000)
    expect(userCurveWindowMs(0, 101, MAX_DURATION_MS)).toBe(2000)
    expect(userCurveWindowMs(-5, 101, MAX_DURATION_MS)).toBe(2000)
  })

  it('Review 창은 발화 구간과 같고, 앞뒤 침묵은 잘려 나간다 (KAN-195)', () => {
    // 앞 10프레임·뒤 10프레임이 침묵인 100프레임 녹음. 발화는 320ms부터 2848ms까지다
    const { frames: trimmed, windowMs } = reviewWindow(SILENCE_PADDED, GUIDE_INTERVAL, GUIDE_COUNT)

    expect(windowMs).toBe(VOICED_SPAN_MS)
    expect(trimmed.length).toBe(80)
    expect(trimmed[0].timestampMs).toBe(FIRST_VOICED_MS)
    expect(trimmed[trimmed.length - 1].timestampMs).toBe(LAST_VOICED_MS)
  })

  it('Review로 그리면 곡선이 레인 양끝에 닿는다 (KAN-195)', () => {
    // 가이드 레인이 `x = i / lastIndex`로 0~1을 쓰는 것과 같은 규칙이다 (guideCurve.ts)
    const { frames: trimmed, windowMs } = reviewWindow(SILENCE_PADDED, GUIDE_INTERVAL, GUIDE_COUNT)
    const points = userCurveDisplayPoints(trimmed, windowMs)[0]

    expect(points.length).toBe(80)
    expect(points[0].x).toBe(0)
    expect(points[points.length - 1].x).toBe(1)
  })

  it('발화가 가이드보다 짧으면 창은 가이드 길이고 곡선이 레인을 다 쓰지 않는다', () => {
    /*
     * 세 음절만 웅얼거리고 끝낸 녹음까지 레인을 꽉 채우면, 위 가이드 레인과 나란히 놓였을 때
     * 비슷한 분량을 말한 것처럼 보인다. 덜 말했다는 사실이 폭으로 남아야 한다.
     */
    const short = centerFrames() // 8프레임 = 224ms 발화
    const { frames: trimmed, windowMs } = reviewWindow(short, GUIDE_INTERVAL, GUIDE_COUNT)

    expect(windowMs).toBe(GUIDE_MS)
    const points = userCurveDisplayPoints(trimmed, windowMs)[0]
    expect(points[points.length - 1].x - points[0].x).toBeCloseTo(224 / GUIDE_MS, 5)
  })

  it('발화가 바닥보다 짧고 뒤 침묵이 길면 곡선이 레인 오른쪽에 붙는다 (KAN-195)', () => {
    /*
     * `마지막 유성 >= 바닥`이라 windowStart가 0보다 커지는 경로다. 10초 자동 종료로 뒤에
     * 6초가 남아 있어도 유성 구간은 창 안에 그대로 들어와야 한다.
     */
    const lateShort = frames(
      Array.from({ length: 313 }, (_, i) => (i >= 100 && i <= 115 ? CENTER_HZ : null)),
    )
    const { frames: trimmed, windowMs } = reviewWindow(lateShort, GUIDE_INTERVAL, GUIDE_COUNT)

    expect(trimmed.length).toBe(16)
    expect(windowMs).toBe(GUIDE_MS) // 발화 480ms < 바닥 1000ms
    const points = userCurveDisplayPoints(trimmed, windowMs)[0]
    expect(points[0].x).toBeCloseTo(0.52, 5) // (3200 - (3680-1000)) / 1000
    expect(points[points.length - 1].x).toBeCloseTo(1, 5)
  })

  it('유성이 하나뿐이고 뒤에 침묵이 길어도 그 점이 창 안에 남는다 (KAN-195 리뷰 P2)', () => {
    /*
     * 기침 한 번처럼 유성이 하나뿐인 녹음이 10초 자동 종료로 끝나면, 자르지 않을 때
     * 창의 오른쪽 끝이 침묵의 끝(10초)에 붙어 그 점이 x<0으로 밀려 사라졌다.
     */
    const oneVoiced = frames(
      Array.from({ length: 313 }, (_, i) => (i === 6 ? CENTER_HZ : null)),
    )
    expect(oneVoiced[oneVoiced.length - 1].timestampMs).toBe(9984) // 전제: 10초 자동 종료
    const { frames: trimmed, windowMs } = reviewWindow(oneVoiced, GUIDE_INTERVAL, GUIDE_COUNT)

    expect(trimmed.length).toBe(1)
    expect(trimmed[0].timestampMs).toBe(6 * FRAME_MS)
    expect(windowMs).toBe(GUIDE_MS)
    // 유성이 하나면 중심을 못 잡아(CENTER_MIN_VOICED_FRAMES 미달) 점은 안 그려지지만,
    // 창 자체는 그 점을 담고 있어야 한다 — 창 밖으로 밀리면 프레임이 더 와도 못 살린다.
    expect(trimmed[0].timestampMs).toBeGreaterThanOrEqual(Math.max(0, 6 * FRAME_MS - windowMs))
  })

  it('유성 프레임이 없으면 창은 가이드 길이고 프레임은 원본 그대로다', () => {
    const silence = frames([null, null, null])
    const { frames: kept, windowMs } = reviewWindow(silence, GUIDE_INTERVAL, GUIDE_COUNT)

    expect(windowMs).toBe(GUIDE_MS)
    expect(kept).toBe(silence)
    expect(reviewWindow([], GUIDE_INTERVAL, GUIDE_COUNT).windowMs).toBe(GUIDE_MS)
  })

  it('가이드를 쓸 수 없으면 바닥은 폴백 1초다', () => {
    expect(reviewWindow(centerFrames(), null, null).windowMs).toBe(1000)
  })
})

describe('그릴 게 없는 경우', () => {
  it('그릴 프레임이 없으면 빈 목록이다', () => {
    expect(userCurveDisplayPoints([], WINDOW_MS)).toEqual([])
  })

  it('전부 무성이면 그릴 점이 없다', () => {
    expect(userCurveDisplayPoints(frames([null, null, null]), WINDOW_MS)).toEqual([])
  })

  it('창 길이가 0 이하면 그리지 않는다', () => {
    expect(userCurveDisplayPoints(centerFrames(), 0)).toEqual([])
  })
})

describe('중심 잠금', () => {
  it('유성 프레임이 모자라면 축이 없어 그리지 않는다', () => {
    const notEnough = Array.from({ length: CENTER_MIN_VOICED_FRAMES - 1 }, (_, i) =>
      frame(i * FRAME_MS, CENTER_HZ),
    )
    expect(userCurveCenterHz(notEnough)).toBeNull()
    expect(userCurveDisplayPoints(notEnough, WINDOW_MS)).toEqual([])
  })

  it('유성 프레임이 채워지는 순간부터 그려진다', () => {
    const enough = centerFrames()
    expect(userCurveCenterHz(enough)!).toBeCloseTo(CENTER_HZ, 3)
    const segments = userCurveDisplayPoints(enough, WINDOW_MS)
    expect(segments.length).toBe(1)
    expect(segments[0].length).toBe(CENTER_MIN_VOICED_FRAMES)
  })

  it('중심은 처음 여덟 프레임으로 잠긴다 - 뒤에 뭐가 와도 안 변한다', () => {
    const locked = userCurveCenterHz(centerFrames())!
    const more = [...centerFrames(), ...frames([400, 400, 400, 400], 8 * FRAME_MS)]
    expect(userCurveCenterHz(more)!).toBeCloseTo(locked, 3)
  })

  it('중앙값이라 옥타브 오류 한 프레임에 중심이 안 밀린다', () => {
    // 여덟 중 하나가 두 배로 튄 경우 - 평균이면 12퍼센트 넘게 밀리지만 중앙값은 그대로다
    const withOctaveError = frames([
      CENTER_HZ,
      CENTER_HZ,
      CENTER_HZ,
      CENTER_HZ * 2,
      CENTER_HZ,
      CENTER_HZ,
      CENTER_HZ,
      CENTER_HZ,
    ])
    expect(userCurveCenterHz(withOctaveError)!).toBeCloseTo(CENTER_HZ, 3)
  })

  it('무성 프레임은 중심 계산에서 세지 않는다', () => {
    const sparse = frames([
      CENTER_HZ, null, CENTER_HZ, null, CENTER_HZ, null, CENTER_HZ, null,
      CENTER_HZ, null, CENTER_HZ, null, CENTER_HZ, null, CENTER_HZ,
    ])
    expect(userCurveCenterHz(sparse)!).toBeCloseTo(CENTER_HZ, 3)
  })
})

describe('y축 스케일', () => {
  it('중심 음높이는 레인 한가운데다', () => {
    userCurveDisplayPoints(centerFrames(), WINDOW_MS)[0].forEach((point) => {
      expect(point.y).toBeCloseTo(0.5, 4)
    })
  })

  it('중심에서 위아래 7 semitone이 레인 끝이다', () => {
    // 긴 구멍 뒤에 두어 EMA가 초기화되게 한다 - 스무딩이 섞이지 않은 순수 좌표를 본다
    const up = [...centerFrames(), frame(after(LONG_GAP_MS), semitone(7))]
    expect(lastSinglePoint(up).y).toBeCloseTo(0, 4)

    const down = [...centerFrames(), frame(after(LONG_GAP_MS), semitone(-7))]
    expect(lastSinglePoint(down).y).toBeCloseTo(1, 4)
  })

  it('창을 벗어난 값은 레인 안으로 눌러 담는다', () => {
    const up = [...centerFrames(), frame(after(LONG_GAP_MS), semitone(20))]
    expect(lastSinglePoint(up).y).toBeCloseTo(0, 4)

    const down = [...centerFrames(), frame(after(LONG_GAP_MS), semitone(-20))]
    expect(lastSinglePoint(down).y).toBeCloseTo(1, 4)
  })

  it('높은 음이 위로 간다 - Hz가 클수록 y가 작다', () => {
    const rising = [
      ...centerFrames(),
      ...frames(
        [semitone(1), semitone(3), semitone(6)],
        CENTER_MIN_VOICED_FRAMES * FRAME_MS,
      ),
    ]
    const points = userCurveDisplayPoints(rising, WINDOW_MS)[0]
    const tail = points.slice(-3)
    for (let k = 0; k < tail.length - 1; k++) {
      expect(tail[k].y).toBeGreaterThan(tail[k + 1].y)
    }
  })

  it('centerHz를 주면 자동 계산을 쓰지 않는다', () => {
    // 유성 프레임이 셋뿐이라 자동 계산은 null인데, 중심을 받았으니 그려진다
    const short = frames([CENTER_HZ, CENTER_HZ, CENTER_HZ])
    expect(userCurveCenterHz(short)).toBeNull()
    const segments = userCurveDisplayPoints(short, WINDOW_MS, CENTER_HZ)
    expect(segments[0].length).toBe(3)
    segments[0].forEach((point) => expect(point.y).toBeCloseTo(0.5, 4))

    // 중심을 위로 올려 주면 같은 프레임이 레인 아래쪽에 놓인다
    const higherCenter = userCurveDisplayPoints(short, WINDOW_MS, semitone(7))
    expect(higherCenter[0][0].y).toBeCloseTo(1, 4)
  })
})

describe('EMA 스무딩', () => {
  it('EMA는 튀는 한 프레임을 알파배로 눌러 준다', () => {
    const spike = [...centerFrames(), frame(after(FRAME_MS), semitone(7))]
    const points = userCurveDisplayPoints(spike, WINDOW_MS)[0]
    // 스무딩이 없었다면 y=0(레인 끝)이라 중앙에서 0.5만큼 움직였을 값이다
    const displacement = 0.5 - points[points.length - 1].y
    expect(displacement).toBeCloseTo(0.5 * USER_CURVE_EMA_ALPHA, 3)
  })

  it('선분 첫 프레임은 지연 없이 제 값 그대로다', () => {
    // 첫 프레임부터 중심에서 떨어져 있어도 0에서 끌려오지 않는다
    const offset = Array.from({ length: CENTER_MIN_VOICED_FRAMES }, (_, i) =>
      frame(i * FRAME_MS, semitone(3.5)),
    )
    // 중심이 곧 이 값이므로 자동 계산에서는 항상 0.5다 - 중심을 명시해 상대 위치를 본다
    const points = userCurveDisplayPoints(offset, WINDOW_MS, CENTER_HZ)[0]
    expect(points[0].y).toBeCloseTo(0.5 - 3.5 / USER_CURVE_SPAN_SEMITONE, 3)
  })
})

describe('무성 구간', () => {
  it('짧은 구멍은 직전 값을 유지해 선이 이어진다', () => {
    const gapMs = HOLD_MAX_GAP_MS // 경계값 포함
    const withHole = [
      ...centerFrames(),
      frame(after(FRAME_MS), null),
      frame(after(gapMs), semitone(2)),
    ]
    const segments = userCurveDisplayPoints(withHole, WINDOW_MS)
    expect(segments.length).toBe(1) // 구멍이 짧으면 선분이 갈라지지 않는다

    const points = segments[0]
    // 구멍 자리에도 점이 있다 - 프레임 수만큼 점이 나온다
    expect(points.length).toBe(CENTER_MIN_VOICED_FRAMES + 2)
    const held = points[CENTER_MIN_VOICED_FRAMES]
    expect(held.y).toBeCloseTo(points[CENTER_MIN_VOICED_FRAMES - 1].y, 6)
  })

  it('긴 구멍은 선을 끊고 EMA를 초기화한다', () => {
    const withPause = [...centerFrames(), frame(after(LONG_GAP_MS), semitone(7))]
    const segments = userCurveDisplayPoints(withPause, WINDOW_MS)
    expect(segments.length).toBe(2)
    // 새 선분 첫 점은 직전 선분의 값(중앙 0.5)에 끌리지 않고 제 값(레인 끝)에서 시작한다
    expect(segments[1][0].y).toBeCloseTo(0, 4)
  })

  it('유지는 직전 유성 프레임 기준이라 구멍이 길어지면 멈춘다', () => {
    // 무성이 계속되면 HOLD_MAX_GAP_MS를 넘는 순간부터는 점을 두지 않는다
    const longHole = [
      ...centerFrames(),
      ...frames(Array(10).fill(null), CENTER_MIN_VOICED_FRAMES * FRAME_MS),
    ]
    const points = userCurveDisplayPoints(longHole, WINDOW_MS)[0]
    const heldCount = points.length - CENTER_MIN_VOICED_FRAMES
    // 마지막 유성 시각에서 32ms씩 떨어진 프레임 중 250ms 이하인 일곱(32~224ms)만 유지된다
    expect(heldCount).toBe(Math.floor(HOLD_MAX_GAP_MS / FRAME_MS))
    expect(heldCount).toBe(7)
  })
})

describe('시간 가중 EMA', () => {
  it('구멍이 길수록 옛 값의 몫이 줄어든다', () => {
    // 100ms 구멍은 프레임 3개어치라 0.7^3 = 34%가 남는다
    expect(residualAfterGap(100)).toBeCloseTo(0.343, 2)
    // 250ms(유지 한계)는 프레임 8개어치라 0.7^8 = 6%다 - 옛 값에 끌려가지 않는다
    expect(residualAfterGap(250)).toBeCloseTo(0.058, 2)
  })

  it('유지 한계 안의 구멍은 선분을 가르지 않는다', () => {
    expect(userCurveDisplayPoints(gapThenJump(250), WINDOW_MS).length).toBe(1)
  })

  it('연속 프레임의 EMA는 시간 가중 전후가 같다', () => {
    // gapFrames=1이면 retain=0.7이라 `직전*0.7 + 현재*0.3`과 정확히 같다
    expect(residualAfterGap(FRAME_MS)).toBeCloseTo(1 - USER_CURVE_EMA_ALPHA, 3)
  })

  it('유지 한계를 넘는 구멍은 선을 끊고 새 값 그대로 시작한다', () => {
    const jumpSt = 3.5
    const over = [...centerFrames(), frame(after(300), semitone(jumpSt))]
    const segments = userCurveDisplayPoints(over, WINDOW_MS)
    expect(segments.length).toBe(2) // 300ms는 HOLD_MAX_GAP_MS를 넘어 선분이 갈린다
    expect(segments[1][0].y).toBeCloseTo(0.5 - jumpSt / USER_CURVE_SPAN_SEMITONE, 4)
  })
})

describe('실시간성', () => {
  it('프레임이 더 쌓여도 이미 그린 점은 그대로다', () => {
    const all = [
      ...centerFrames(),
      ...frames(
        [
          semitone(1), semitone(4), semitone(-2), semitone(5),
          semitone(2), semitone(-3), semitone(6), semitone(0),
        ],
        CENTER_MIN_VOICED_FRAMES * FRAME_MS,
      ),
    ]
    const earlier = userCurveDisplayPoints(all.slice(0, 12), WINDOW_MS)[0]
    const later = userCurveDisplayPoints(all, WINDOW_MS)[0]

    expect(later.length).toBeGreaterThan(earlier.length)
    earlier.forEach((point, i) => {
      expect(later[i].x).toBeCloseTo(point.x, 6)
      expect(later[i].y).toBeCloseTo(point.y, 6)
    })
  })

  it('창이 차기 전에는 왼쪽부터 자란다', () => {
    // 최신이 창의 절반쯤이면 곡선도 절반까지만 그려진다
    const points = userCurveDisplayPoints(centerFrames(), WINDOW_MS)[0]
    expect(points[0].x).toBeCloseTo(0, 5)
    const lastMs = (CENTER_MIN_VOICED_FRAMES - 1) * FRAME_MS
    expect(points[points.length - 1].x).toBeCloseTo(lastMs / WINDOW_MS, 5)
  })

  it('창 길이를 넘기면 창이 미끄러지고 밀려난 프레임은 버린다', () => {
    const total = 40
    const long = Array.from({ length: total }, (_, i) => frame(i * FRAME_MS, CENTER_HZ))
    const windowMs = 1000
    const points = userCurveDisplayPoints(long, windowMs)[0]

    const windowStartMs = (total - 1) * FRAME_MS - windowMs
    const expected = long.filter((f) => f.timestampMs >= windowStartMs).length
    expect(points.length).toBe(expected)
    expect(points[0].x).toBeLessThan(0.05) // 가장 오래된 점은 창 왼쪽에 붙는다
    expect(points[points.length - 1].x).toBeCloseTo(1, 5) // 최신 점은 오른쪽 끝이다
  })
})

describe('Review 구멍 보간', () => {
  it('짧은 구멍은 semitone 선형으로 메워진다', () => {
    const filled = fillShortGaps(HOLE_192MS)

    expect(filled.length).toBe(HOLE_192MS.length) // 개수가 보존된다
    expect(filled.map((f) => f.timestampMs)).toEqual(HOLE_192MS.map((f) => f.timestampMs))
    // 양 끝 유성 값은 그대로다
    expect(filled[1].pitchHz!).toBeCloseTo(200, 3)
    expect(filled[7].pitchHz!).toBeCloseTo(800, 3)
    // 구멍 한가운데(t=128, 비율 0.5)는 산술평균 500이 아니라 기하평균 400이다
    expect(filled[4].pitchHz!).toBeCloseTo(400, 2)
    // 나머지 구멍 자리도 전부 채워졌고, 단조 증가한다
    const inside = [2, 3, 4, 5, 6].map((i) => filled[i].pitchHz!)
    inside.forEach((hz) => expect(hz).toBeGreaterThan(0))
    for (let k = 0; k < inside.length - 1; k++) {
      expect(inside[k]).toBeLessThan(inside[k + 1])
    }
  })

  it('앞뒤 가장자리 구멍은 그대로 둔다', () => {
    const filled = fillShortGaps(HOLE_192MS)
    expect(filled[0].pitchHz).toBeNull() // 녹음 시작 전 무성
    expect(filled[filled.length - 1].pitchHz).toBeNull() // 녹음이 끝난 뒤 무성
  })

  it('긴 구멍은 진짜 쉼이라 메우지 않는다', () => {
    // 유성 두 개 사이가 608ms라 REVIEW_FILL_MAX_GAP_MS(500)를 넘는다
    const hole = [
      frame(0, 200),
      ...Array.from({ length: 18 }, (_, i) => frame((i + 1) * FRAME_MS, null)),
      frame(19 * FRAME_MS, 800),
    ]
    const filled = fillShortGaps(hole)

    expect(filled).toEqual(hole)
    expect(filled.slice(1, 19).every((f) => f.pitchHz === null)).toBe(true)
  })

  it('메울 짝이 없으면 원본 그대로다', () => {
    expect(fillShortGaps([])).toEqual([])
    const allUnvoiced = frames([null, null, null])
    expect(fillShortGaps(allUnvoiced)).toEqual(allUnvoiced)
    const onlyOneVoiced = frames([null, 200, null])
    expect(fillShortGaps(onlyOneVoiced)).toEqual(onlyOneVoiced)
  })

  it('메운 프레임은 곡선을 한 선분으로 잇는다', () => {
    // 608ms 구멍은 실시간 곡선이라면 선분을 가르지만, 한계를 늘려 메우면 한 선분이 된다
    const hole = [
      ...centerFrames(),
      ...Array.from({ length: 18 }, (_, i) => frame(after((i + 1) * FRAME_MS), null)),
      frame(after(19 * FRAME_MS), semitone(3)),
    ]
    expect(userCurveDisplayPoints(hole, WINDOW_MS).length).toBe(2)
    const filled = fillShortGaps(hole, 1000)
    expect(userCurveDisplayPoints(filled, WINDOW_MS).length).toBe(1)
  })
})

/** 긴 구멍 뒤에 점 하나만 놓은 케이스의 그 점 */
function lastSinglePoint(list: PitchFrame[]) {
  const segments = userCurveDisplayPoints(list, WINDOW_MS)
  const last = segments[segments.length - 1]
  return last[0]
}
