/**
 * 녹음 중 쌓인 사용자 F0 프레임을 표시 좌표로 바꾼다 — 앱 `UserCurve.kt`의 1:1 포팅 (KAN-56 Stage 5).
 *
 * `guideCurve.ts`와 같은 자리의 순수 계산이다. 다른 점은 입력이 "완성된 배열"이 아니라
 * "지금까지 들어온 만큼"이라는 것 — 매 조각마다 다시 불리므로, 프레임이 늘어도 이미 그린
 * 부분이 흔들리지 않는 규칙이 필요하다. 아래 결정들이 그 요구에서 나왔다. 숫자를 그 값으로
 * 고른 근거는 `docs/wiki/pitch-curve.md` §2가 정본이고, 여기서는 규칙만 요약한다.
 *
 * - **사용자 창은 가이드 길이의 [USER_CURVE_WINDOW_SCALE]배다**([userCurveWindowMs], Review는
 *   녹음 전체 길이 [reviewWindowMs]). 시드 가이드보다 실제 발화가 길어서, 창을 가이드에
 *   맞춰 놓으면 발화 앞부분이 창 밖으로 밀린다. 녹음이 창 길이를 넘어가면 창이 미끄러져
 *   최신 프레임이 항상 오른쪽 끝에 있게 하고, 밀린 프레임은 버린다.
 * - **y축은 화자 중심 ±[USER_CURVE_SPAN_SEMITONE]/2 고정 폭 창이다.** 가이드처럼 자기 min/max를
 *   쓰면 새 최고점이 찍힐 때마다 이미 그린 곡선 전체가 위아래로 튄다. 그래서 폭은 고정하고
 *   중심만 화자에 맞춘다([userCurveCenterHz]). 로그(semitone)를 쓰는 이유는 같은 음정 간격이
 *   같은 화면 거리가 되게 하기 위해서다.
 * - **무성 구간은 길이로 갈라 다룬다.** 자음·무성음 같은 짧은 구멍은 직전 값을 유지해 선을
 *   잇고, 문장 사이 쉼처럼 긴 구멍은 선을 끊는다([HOLD_MAX_GAP_MS]).
 * - **EMA는 프레임 개수가 아니라 시간으로 감쇠한다.** 구멍을 건너뛴 뒤의 첫 유성 값이 옛 값에
 *   끌려가지 않게, 벌어진 간격만큼 옛 값의 몫을 줄인다([USER_CURVE_EMA_ALPHA]).
 *
 * y를 뒤집는 것(`1 - 정규화값`)은 가이드와 같다 — 화면 좌표는 아래로 갈수록 y가 커진다.
 *
 * 계산은 전부 **인과적(causal)**이다 — 각 점은 자기보다 앞선 프레임만 보고 정해지므로, 프레임이
 * 더 쌓여도 이미 계산된 점의 y는 변하지 않는다. 실시간 곡선에서 과거가 다시 그려지면
 * 사용자에게는 곡선이 저 혼자 꿈틀대는 것으로 보인다.
 *
 * ## 중심은 목소리 점검이 정하고, 여기는 폴백을 든다
 *
 * 시작 게이트의 목소리 점검이 "안녕하세요" 한 마디에서 중심 음높이를 확정해 모든 문항에
 * 물려준다 (`pitch-curve.md` §5). 앱은 `VoiceCheckController`가, 웹은 그것을 포팅한
 * `voicecheck/voiceCheckController.ts`가 재고, 잰 값은 웹 단독 실행에서 세션과 함께
 * 저장돼(`webSession.ts`) 문항 화면까지 내려온다 ([userCurveDisplayPoints]의 `centerHz`).
 *
 * 그래도 **이 녹음의 처음 8개 유성 프레임으로 잡는 폴백은 남는다.** 저장된 중심이 없는 경우가
 * 실제로 있기 때문이다 — 저장소를 못 쓰는 브라우저, 옛 문서로 돌아온 뒤로 가기, 앱 안에서
 * 웹이 그리는 경로. 그때 중심이 없다고 빈 레인을 두면 사용자에게는 녹음이 안 되는 것으로
 * 보인다. 축이 문항마다 조금씩 다른 것과 곡선이 아예 없는 것 중에서는 앞쪽이 낫다.
 */

import { HOP_SIZE } from '../audio/overlappedFramer'
import { TARGET_SAMPLE_RATE } from '../audio/pcm'
import type { PitchFrame } from '../audio/pitchTracker'
import type { CurvePoint } from './guideCurve'

/**
 * 가이드가 없거나 쓸 수 없을 때의 창 길이. 가이드 길이를 모르니 1초를 기준 길이로 삼고
 * [USER_CURVE_WINDOW_SCALE]을 똑같이 곱한다 — 창을 넓히는 이유는 가이드가 있든 없든 같다.
 */
const FALLBACK_GUIDE_MS = 1000

/**
 * 표시 창의 세로 폭 (semitone). 중심에서 위아래로 절반씩이므로 ±7 semitone이다.
 * 실측 근거는 `pitch-curve.md` §2 — 일상 발화 등락이 p5~p95 기준 6.6~8.0 semitone이라
 * 14면 평서문은 안 잘리고 감탄·고성만 레인 끝에 눌린다.
 */
export const USER_CURVE_SPAN_SEMITONE = 14

/**
 * 중심(화자 기준 음높이)을 정하는 데 필요한 유성 프레임 수. 32ms 간격 기준 약 250ms.
 * 이보다 적으면 첫 한두 음절의 음높이가 곧 화자의 중심이 되고, 크게 잡으면 축이 정해지기까지
 * 곡선이 안 나온다.
 */
export const CENTER_MIN_VOICED_FRAMES = 8

/**
 * EMA 스무딩 계수. `직전값*0.7 + 현재값*0.3`이 이 값이다. 시정수가 약 3.3프레임 ≈ 107ms로,
 * 부드러움과 반응성의 트레이드오프다.
 *
 * **적용은 프레임 개수가 아니라 시간 기준이다.** 무성 구멍을 사이에 두고 32ms짜리 α를 한 번만
 * 먹이면, 구멍이 얼마나 길든 새 값은 옛 값에 70% 끌려간다 — 구멍이 길수록 그 옛 값은 못 믿을
 * 값인데도 그렇다. 그래서 직전 유성 프레임과 벌어진 만큼을 프레임 수로 환산해
 * `retain = (1-α)^gapFrames`를 쓴다. 연속 프레임(gapFrames=1)이면 기존 식과 정확히 같고,
 * 구멍이 길어지면 옛 값의 몫이 저절로 사그라든다(100ms→34%, 250ms→6%, 500ms→0.3%).
 */
export const USER_CURVE_EMA_ALPHA = 0.3

/**
 * 이 길이 이하의 무성 구간은 선을 잇고(직전 값 유지), 넘으면 끊는다.
 *
 * 자음·무성음과 문장 사이 쉼을 가르는 선이다. 무조건 이으면 쉼 구간에 긴 가짜 평선이 생기고,
 * 무조건 끊으면 한 어절 안에서도 자음마다 선이 조각난다. 판정은 프레임 개수가 아니라
 * timestampMs 차이로 한다 — 조각 경계에서 프레임 수가 흔들려도 시각은 흔들리지 않는다.
 */
export const HOLD_MAX_GAP_MS = 250

/**
 * Review 화면에서 메워 주는 무성 구멍의 최대 길이. 이보다 긴 구멍은 진짜 쉼으로 보고 둔다.
 * 실시간 곡선의 [HOLD_MAX_GAP_MS]보다 넉넉한 이유는 [fillShortGaps]에 적었다.
 */
export const REVIEW_FILL_MAX_GAP_MS = 500

/**
 * 프레임 하나가 나오는 간격 (ms). `OverlappedFramer`의 hop이 512이고 표본율이 16kHz라 32ms다.
 * 숫자를 박아 두지 않고 두 상수에서 뽑는 건, 그것이 바뀌면 EMA의 시간 환산도 따라와야 하기 때문이다.
 */
const FRAME_INTERVAL_MS = (HOP_SIZE * 1000) / TARGET_SAMPLE_RATE

/** 창 길이를 가이드 길이의 몇 배로 잡을지. 실제 발화가 시드 가이드보다 1.5~2배 길다 */
export const USER_CURVE_WINDOW_SCALE = 2

/**
 * 가이드 곡선이 담는 시간. 프레임 간격 × 구간 수다. 길이를 알 수 없으면 0이다 —
 * 값이 1개뿐이면 구간이 0이고, 간격이 0 이하면 시간축 자체가 무의미하다.
 *
 * 인자를 GuideF0가 아니라 원시값으로 받는 건 그리기 계산이 서버 응답 타입을 알 필요가
 * 없기 때문이다.
 */
export function guideDurationMs(
  frameIntervalMs: number | null | undefined,
  valueCount: number | null | undefined,
): number {
  if (frameIntervalMs == null || frameIntervalMs <= 0) return 0
  if (valueCount == null || valueCount < 2) return 0
  return frameIntervalMs * (valueCount - 1)
}

/**
 * 사용자 레인 한 폭이 담을 시간. 가이드 길이의 [USER_CURVE_WINDOW_SCALE]배다.
 * 가이드가 없거나 길이를 계산할 수 없으면 [FALLBACK_GUIDE_MS]를 가이드 길이로 놓는다.
 */
export function userCurveWindowMs(
  frameIntervalMs: number | null | undefined,
  valueCount: number | null | undefined,
): number {
  const guideMs = guideDurationMs(frameIntervalMs, valueCount) || FALLBACK_GUIDE_MS
  return Math.round(guideMs * USER_CURVE_WINDOW_SCALE)
}

/**
 * 녹음이 끝난 Review 화면이 쓸 창 길이. 라이브 창과 "녹음 전체 길이" 중 긴 쪽이다.
 * 프레임이 없으면 라이브 창을 그대로 쓴다.
 *
 * 녹음 중에는 창이 미끄러져야 한다 — 지금 내 목소리가 오른쪽 끝에 붙어 있어야 방금 낸 소리와
 * 화면이 같이 움직인다. 녹음이 끝나면 볼 대상이 "방금 한 발화 전체"로 바뀌는데, 라이브 창을
 * 그대로 두면 창 길이를 넘긴 발화는 마지막 구간만 남아 정작 다시 볼 수 있게 된 시점에
 * 앞부분을 못 본다. 한 프레임 간격을 더 얹는 건 마지막 점이 오른쪽 모서리에 딱 붙지 않게
 * 하기 위해서다 — 그 프레임도 자기 몫의 폭을 차지한다.
 */
export function reviewWindowMs(frames: PitchFrame[], liveWindowMs: number): number {
  if (frames.length === 0) return liveWindowMs
  let lastMs = frames[0].timestampMs
  for (const frame of frames) {
    if (frame.timestampMs > lastMs) lastMs = frame.timestampMs
  }
  return Math.max(liveWindowMs, lastMs + Math.round(FRAME_INTERVAL_MS))
}

/**
 * 이 화자의 중심 음높이(Hz). 유성 프레임이 [CENTER_MIN_VOICED_FRAMES]개에 못 미치면 null이다.
 *
 * **처음** N개만 본다. 프레임이 더 쌓여도 값이 안 변하므로(잠금) 축이 한 번 정해지면 끝까지
 * 같고, 이미 그린 곡선이 나중 발화 때문에 위아래로 밀리는 일이 없다.
 *
 * 평균이 아니라 중앙값인 이유: YIN은 이따금 옥타브 오류(진짜 값의 2배나 절반)를 낸다.
 * 8개 중 하나만 2배로 튀어도 평균은 12% 넘게 밀리지만(≈2 semitone) 중앙값은 꿈쩍하지 않는다.
 */
export function userCurveCenterHz(frames: PitchFrame[]): number | null {
  const first: number[] = []
  for (const frame of frames) {
    const hz = voicedHz(frame)
    if (hz === null) continue
    first.push(hz)
    if (first.length === CENTER_MIN_VOICED_FRAMES) break
  }
  if (first.length < CENTER_MIN_VOICED_FRAMES) return null
  first.sort((a, b) => a - b)
  const mid = first.length / 2
  // 짝수개면 가운데 둘의 평균이 통상의 중앙값이다. 옥타브 오류는 정렬하면 끝으로 밀려나므로
  // 가운데 둘은 여전히 정상 값이다.
  return first.length % 2 === 0 ? (first[mid - 1] + first[mid]) / 2 : first[Math.floor(mid)]
}

/**
 * 지금까지 쌓인 프레임을 표시 좌표로 바꾼다. 반환은 **선분 목록**이다 — 긴 무성 구간에서
 * 곡선이 끊기므로 폴리라인 하나로는 표현할 수 없다. 빈 선분은 만들지 않고, 선분 하나가
 * 점 1개일 수도 있다(그 시각에 점만 찍는다).
 *
 * [frames]는 시각 순이고, [windowMs]는 [userCurveWindowMs]가 준 값이다.
 *
 * [centerHz]를 주면 그걸 y축 중심으로 쓰고, 없으면 [userCurveCenterHz]로 이 녹음에서 직접
 * 잡는다(목소리 점검이 잰 값이 닿지 않은 경우의 폴백 — 파일 헤더). **둘 다 없으면 빈 결과다** —
 * 중심이 정해지기 전에 임시 축으로 그려 두면, 축이 잠기는 순간 곡선 전체가 한 번 점프한다.
 *
 * 스무딩과 중심 계산은 **창을 자르기 전 전체 프레임**으로 한다. 창은 보여줄 구간을 고르는
 * 일일 뿐이라, 창이 미끄러졌다고 남아 있는 점의 y가 달라지면 안 된다.
 */
export function userCurveDisplayPoints(
  frames: PitchFrame[],
  windowMs: number,
  centerHz?: number | null,
): CurvePoint[][] {
  if (frames.length === 0 || windowMs <= 0) return []

  const given = centerHz != null && Number.isFinite(centerHz) && centerHz > 0 ? centerHz : null
  const center = given ?? userCurveCenterHz(frames)
  if (center === null || !Number.isFinite(center) || center <= 0) return []

  // 창의 오른쪽 끝은 항상 최신 프레임이다. 아직 창이 안 찼으면 0에서 시작해 곡선이
  // 왼쪽부터 자라고, 넘어서면 창이 통째로 미끄러진다.
  let newestMs = frames[0].timestampMs
  for (const frame of frames) {
    if (frame.timestampMs > newestMs) newestMs = frame.timestampMs
  }
  const windowStartMs = Math.max(0, newestMs - windowMs)

  const segments: CurvePoint[][] = []
  let current: CurvePoint[] | null = null
  let smoothed = 0 // 직전 프레임까지의 EMA 값 (semitone)
  let lastVoicedMs: number | null = null

  for (const frame of frames) {
    const hz = voicedHz(frame)
    const previousMs = lastVoicedMs
    const withinHold = previousMs !== null && frame.timestampMs - previousMs <= HOLD_MAX_GAP_MS

    if (hz === null) {
      // 무성. 짧은 구멍이면 직전 값 그대로 점을 하나 두어 선이 이어지게 하고,
      // 긴 구멍이면 아무것도 안 둔다 - 다음 유성 프레임에서 새 선분이 시작된다.
      if (!withinHold) continue
    } else {
      const st = 12 * Math.log2(hz / center)
      if (previousMs !== null && withinHold) {
        // 시간 가중 EMA. 직전 유성 프레임에서 벌어진 만큼을 프레임 수로 환산해 옛 값의
        // 몫을 그만큼 거듭 깎는다 - 연속 프레임이면 gapFrames=1이라 기존 식과 같다.
        const gapFrames = Math.max(
          1,
          Math.round((frame.timestampMs - previousMs) / FRAME_INTERVAL_MS),
        )
        const retain = (1 - USER_CURVE_EMA_ALPHA) ** gapFrames
        smoothed = st + (smoothed - st) * retain
      } else {
        // 선분이 새로 시작될 때(맨 처음, 또는 끊김 뒤)는 첫 값 그대로 놓는다.
        smoothed = st
      }
      if (!withinHold) current = null // 새 선분
      lastVoicedMs = frame.timestampMs
    }

    if (frame.timestampMs < windowStartMs) continue

    const x = (frame.timestampMs - windowStartMs) / windowMs
    // 중심에서 ±폭/2가 레인 위아래 끝이다. 벗어난 값(감탄·고성)은 레인 안으로 눌러 담는다.
    const normalized = Math.min(1, Math.max(0, 0.5 + smoothed / USER_CURVE_SPAN_SEMITONE))
    const point: CurvePoint = { x, y: 1 - normalized }

    if (current === null) {
      current = []
      segments.push(current)
    }
    current.push(point)
  }

  return segments
}

/**
 * 두 유성 프레임 사이의 짧은 무성 구멍을 **semitone 선형 보간**으로 메운 프레임 목록.
 * 원본은 건드리지 않고 같은 길이·같은 순서·같은 timestampMs의 새 목록을 돌려준다.
 *
 * **Review 화면 전용이다.** 실시간 곡선은 인과적이어야 해서(자기보다 뒤의 프레임을 보면 과거가
 * 다시 그려진다) 구멍을 앞 값으로 유지하는 수밖에 없지만, Review는 녹음이 끝나 데이터가 완성된
 * 뒤라 구멍의 양옆을 다 보고 메워도 거짓이 아니다. 가이드 곡선이 이미 같은 규칙으로 무성
 * 구간을 잇고 있어, 위아래 두 레인의 규칙이 이것으로 맞아떨어진다 (`pitch-curve.md` §4).
 *
 * 보간을 Hz가 아니라 log(semitone) 영역에서 하는 이유는 곡선의 y축이 semitone이기 때문이다 —
 * 200Hz와 800Hz 사이의 한가운데는 산술평균 500Hz가 아니라 기하평균 400Hz다.
 *
 * [maxGapMs]를 넘는 구멍은 진짜 쉼으로 보고 그대로 둔다. 앞뒤 가장자리의 구멍(녹음 시작 전·
 * 끝난 뒤)도 그대로다 — 이어 줄 반대쪽 이웃이 없어 메우는 것이 보간이 아니라 값을 지어내는
 * 일이 된다.
 */
export function fillShortGaps(
  frames: PitchFrame[],
  maxGapMs: number = REVIEW_FILL_MAX_GAP_MS,
): PitchFrame[] {
  const voiced: { index: number; hz: number }[] = []
  frames.forEach((frame, index) => {
    const hz = voicedHz(frame)
    if (hz !== null) voiced.push({ index, hz })
  })
  // 유성이 하나뿐이면 사이에 낀 구멍이라는 게 없다.
  if (voiced.length < 2) return frames

  const filled = frames.slice()
  for (let k = 0; k < voiced.length - 1; k++) {
    const { index: i0, hz: hz0 } = voiced[k]
    const { index: i1, hz: hz1 } = voiced[k + 1]
    if (i1 - i0 <= 1) continue // 붙어 있어 메울 자리가 없다

    const startMs = frames[i0].timestampMs
    const spanMs = frames[i1].timestampMs - startMs
    if (spanMs <= 0 || spanMs > maxGapMs) continue

    // semitone 영역이면 곧 log2(Hz)의 상수배라, 밑이 무엇이든 선형 보간 결과는 같다.
    const log0 = Math.log2(hz0)
    const log1 = Math.log2(hz1)
    for (let i = i0 + 1; i < i1; i++) {
      const t = (frames[i].timestampMs - startMs) / spanMs
      filled[i] = { ...frames[i], pitchHz: 2 ** (log0 + (log1 - log0) * t) }
    }
  }
  return filled
}

/**
 * 이 프레임의 유효한 유성 F0. 무성이거나 값이 성립하지 않으면 null이다.
 * "무엇이 유성인가"의 정의가 둘로 갈리면 곡선이 그려지는 조건과 다른 판정이 조용히 어긋난다.
 */
function voicedHz(frame: PitchFrame): number | null {
  const hz = frame.pitchHz
  if (hz === null) return null
  return Number.isFinite(hz) && hz > 0 ? hz : null
}
