package com.accentury.app.recording

import com.accentury.app.audio.RecordingEngine
import kotlin.math.log2

/**
 * 녹음 중 쌓인 사용자 F0 프레임을 캔버스 좌표로 바꾼다 (KAN-104 → KAN-105에서 다듬음).
 *
 * [GuideCurve]와 같은 자리의 순수 계산이다. 다른 점은 입력이 "완성된 배열"이 아니라
 * "지금까지 들어온 만큼"이라는 것 - 매 청크마다 다시 불리므로, 프레임이 늘어도 이미 그린
 * 부분이 흔들리지 않는 규칙이 필요하다. 아래 결정들이 그 요구에서 나왔다.
 *
 * - **시간축은 가이드 길이에 맞춘다.** 사용자 레인의 폭 하나가 가이드 레인의 폭 하나와 같은
 *   시간을 뜻한다([userCurveWindowMs]). 위아래 두 곡선의 x가 같은 시각을 가리켜야 "여기서
 *   올렸어야 했다"를 눈으로 맞춰 볼 수 있다. 녹음이 그 길이를 넘어가면 창이 미끄러져
 *   최신 프레임이 항상 오른쪽 끝에 있게 하고, 창 밖으로 밀린 프레임은 버린다.
 * - **y축은 화자 중심 ±[USER_CURVE_SPAN_SEMITONE]/2 고정 폭 창이다** (KAN-105).
 *   KAN-104는 80..400Hz 밴드를 통째로 썼는데, 그 밴드는 27.9 semitone이라 실제 발화가
 *   레인 높이의 1/4밖에 안 썼다. 실제 샘플(여성 20대, 경남 대화)에서 F0 중앙값 219Hz,
 *   등락 폭 p5~p95가 6.6 semitone, 최대 9.1 semitone이었다. 가이드 레인은 자기 스케일이라
 *   레인을 꽉 채우므로, 사용자 곡선만 눌린 채 나란히 놓여 "내 억양은 밋밋하다"는 거짓 인상을
 *   준다. 그렇다고 가이드처럼 자기 min/max를 쓸 수는 없다 - 새 최고점이 찍힐 때마다 이미 그린
 *   곡선 전체가 위아래로 튄다. 그래서 **폭은 고정하고 중심만 화자에 맞춘다**([userCurveCenterHz]).
 *   로그(semitone)를 쓰는 이유는 KAN-104와 같다 - 같은 음정 간격이 같은 화면 거리가 되게.
 * - **무성 구간은 길이로 갈라 다룬다** (KAN-105). 자음·무성음 같은 짧은 구멍은 직전 값을
 *   유지해 선을 잇고, 문장 사이 쉼처럼 긴 구멍은 선을 끊는다([HOLD_MAX_GAP_MS]).
 *   KAN-104는 무성 프레임을 전부 버려서 폴리라인이 쉼 구간을 가로지르는 가짜 사선을 그렸다.
 *
 * y를 뒤집는 것(`1 - 정규화값`)은 가이드와 같다 - Canvas는 아래로 갈수록 y가 커진다.
 *
 * 계산은 전부 **인과적(causal)**이다 - 각 점은 자기보다 앞선 프레임만 보고 정해지므로,
 * 프레임이 더 쌓여도 이미 계산된 점의 y는 변하지 않는다. 실시간 곡선에서 과거가 다시 그려지면
 * 사용자에게는 곡선이 저 혼자 꿈틀대는 것으로 보인다.
 */

/** 가이드가 없거나 쓸 수 없을 때의 창 길이. 1초면 실시간 곡선이 움직이는 게 보인다 */
private const val FALLBACK_WINDOW_MS = 1000L

/**
 * 표시 창의 세로 폭 (semitone). 중심에서 위아래로 절반씩이므로 ±7 semitone이다.
 *
 * 측정 근거: 실제 샘플의 일상 발화 등락은 p5~p95 기준 6.6 semitone, 최대 9.1 semitone이었다.
 * 14로 잡으면 일반 발화(6~12)는 잘리지 않고, 감탄·고성 같은 예외만 레인 끝에 눌러 담긴다.
 * 더 좁히면 평범한 문장도 clamp되어 곡선이 천장을 기는 평선이 되고, 더 넓히면 KAN-104의
 * "곡선이 눌려 보인다" 문제로 되돌아간다.
 */
const val USER_CURVE_SPAN_SEMITONE = 14.0

/**
 * 중심(화자 기준 음높이)을 정하는 데 필요한 유성 프레임 수. 32ms 간격 기준 약 250ms.
 *
 * 이보다 적으면 첫 한두 음절의 음높이가 곧 화자의 중심이 되어, 우연히 높게 시작한 발화가
 * 통째로 레인 아래쪽에 눌린다. 반대로 너무 크게 잡으면 축이 정해지기까지 곡선이 안 나온다.
 */
const val CENTER_MIN_VOICED_FRAMES = 8

/**
 * EMA 스무딩 계수. 티켓 표의 `직전값*0.7 + 현재값*0.3`이 이 값이다.
 *
 * α=0.3이면 시정수가 약 3.3프레임 ≈ 107ms - 부드러움과 반응성의 트레이드오프다. 낮출수록
 * 곡선은 매끄럽지만 억양 변화가 늦게 따라오고, 높일수록 YIN의 프레임 단위 떨림이 그대로 보인다.
 * 최종값은 실기기에서 눈으로 정한다(KAN-105 4단계) - 그때 이 상수 하나만 바꾸면 되게 두었다.
 */
const val USER_CURVE_EMA_ALPHA = 0.3f

/**
 * 이 길이 이하의 무성 구간은 선을 잇고(직전 값 유지), 넘으면 끊는다.
 *
 * 100ms는 자음·무성음(파열음 폐쇄 구간 등)과 문장 사이 쉼을 가르는 선이다. 무조건 이으면
 * 쉼 구간에 긴 가짜 평선이 생기고 다음 발화의 첫 값이 옛 값에 끌려간다. 무조건 끊으면
 * 한 어절 안에서도 자음마다 선이 조각난다.
 *
 * 판정은 프레임 개수가 아니라 timestampMs 차이로 한다 - 청크 경계에서 프레임 수가 흔들려도
 * 시각은 흔들리지 않는다.
 */
const val HOLD_MAX_GAP_MS = 100L

/**
 * 사용자 레인 한 폭이 담을 시간. 가이드 전체 길이와 같게 잡는다 - 프레임 간격 x 구간 수다.
 *
 * 가이드가 없거나(정의에 guideF0가 없음) 길이를 계산할 수 없으면([FALLBACK_WINDOW_MS]).
 * 값이 1개뿐이면 구간이 0이라 길이가 0이 되고, 간격이 0 이하면 시간축 자체가 무의미하다.
 *
 * 인자를 GuideF0가 아니라 원시값으로 받는 건 [guideCurveDisplayPoints]와 같은 이유다 -
 * 그리기 계산이 bridge 레이어의 payload 타입을 알 필요가 없다.
 */
fun userCurveWindowMs(frameIntervalMs: Int?, valueCount: Int?): Long {
    if (frameIntervalMs == null || frameIntervalMs <= 0) return FALLBACK_WINDOW_MS
    if (valueCount == null || valueCount < 2) return FALLBACK_WINDOW_MS
    return frameIntervalMs.toLong() * (valueCount - 1)
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
fun userCurveCenterHz(frames: List<RecordingEngine.PitchFrame>): Float? {
    val first = ArrayList<Float>(CENTER_MIN_VOICED_FRAMES)
    for (frame in frames) {
        val hz = frame.voicedHz() ?: continue
        first.add(hz)
        if (first.size == CENTER_MIN_VOICED_FRAMES) break
    }
    if (first.size < CENTER_MIN_VOICED_FRAMES) return null
    first.sort()
    val mid = first.size / 2
    // 짝수개면 가운데 둘의 평균이 통상의 중앙값이다. 옥타브 오류는 정렬하면 끝으로 밀려나므로
    // 가운데 둘은 여전히 정상 값이다.
    return if (first.size % 2 == 0) (first[mid - 1] + first[mid]) / 2f else first[mid]
}

/**
 * 지금까지 쌓인 프레임을 표시 좌표로 바꾼다. 반환은 **선분 목록**이다 - 긴 무성 구간에서
 * 곡선이 끊기므로 폴리라인 하나로는 표현할 수 없다. 빈 선분은 만들지 않고, 선분 하나가
 * 점 1개일 수도 있다(그 시각에 점만 찍는다).
 *
 * [frames]는 시각 순이고, [windowMs]는 [userCurveWindowMs]가 준 값이다.
 *
 * [centerHz]를 주면 그걸 y축 중심으로 쓰고(목소리 점검 화면이 미리 잰 값을 넘긴다),
 * 없으면 [userCurveCenterHz]로 이 녹음에서 직접 잡는다. **둘 다 없으면 빈 결과다** -
 * 중심이 정해지기 전에 임시 축으로 그려 두면, 축이 잠기는 순간 곡선 전체가 한 번 점프한다.
 *
 * 스무딩과 중심 계산은 **창을 자르기 전 전체 프레임**으로 한다. 창은 보여줄 구간을 고르는
 * 일일 뿐이라, 창이 미끄러졌다고 남아 있는 점의 y가 달라지면 안 된다.
 */
fun userCurveDisplayPoints(
    frames: List<RecordingEngine.PitchFrame>,
    windowMs: Long,
    centerHz: Float? = null,
): List<List<CurvePoint>> {
    if (frames.isEmpty() || windowMs <= 0L) return emptyList()

    val center = centerHz?.takeIf { it.isFinite() && it > 0f } ?: userCurveCenterHz(frames)
    if (center == null || !center.isFinite() || center <= 0f) return emptyList()

    // 창의 오른쪽 끝은 항상 최신 프레임이다. 아직 창이 안 찼으면 0에서 시작해 곡선이
    // 왼쪽부터 자라고, 넘어서면 창이 통째로 미끄러진다.
    val newestMs = frames.maxOf { it.timestampMs }
    val windowStartMs = maxOf(0L, newestMs - windowMs)

    val segments = ArrayList<MutableList<CurvePoint>>()
    var current: MutableList<CurvePoint>? = null
    var smoothed = 0.0 // 직전 프레임까지의 EMA 값 (semitone)
    var lastVoicedMs: Long? = null

    for (frame in frames) {
        val hz = frame.voicedHz()
        val previousMs = lastVoicedMs
        val withinHold = previousMs != null && frame.timestampMs - previousMs <= HOLD_MAX_GAP_MS

        if (hz == null) {
            // 무성. 짧은 구멍이면 직전 값 그대로 점을 하나 두어 선이 이어지게 하고,
            // 긴 구멍이면 아무것도 안 둔다 - 다음 유성 프레임에서 새 선분이 시작된다.
            if (!withinHold) continue
        } else {
            // 유성 구간이 새로 시작될 때(맨 처음, 또는 긴 구멍 뒤)는 EMA를 첫 값으로 초기화한다.
            // 직전 발화의 값에서 끌려오면 새 발화의 첫 몇 프레임이 통째로 거짓 위치에 놓인다.
            val st = 12.0 * log2(hz / center.toDouble())
            smoothed = if (withinHold) {
                smoothed * (1.0 - USER_CURVE_EMA_ALPHA) + st * USER_CURVE_EMA_ALPHA
            } else {
                st
            }
            if (!withinHold) current = null // 새 선분
            lastVoicedMs = frame.timestampMs
        }

        if (frame.timestampMs < windowStartMs) continue

        val x = (frame.timestampMs - windowStartMs).toDouble() / windowMs
        // 중심에서 ±폭/2가 레인 위아래 끝이다. 벗어난 값(감탄·고성)은 레인 안으로 눌러 담는다.
        val normalized = (0.5 + smoothed / USER_CURVE_SPAN_SEMITONE).coerceIn(0.0, 1.0)
        val point = CurvePoint(x.toFloat(), (1.0 - normalized).toFloat())

        val target = current ?: ArrayList<CurvePoint>().also {
            segments.add(it)
            current = it
        }
        target.add(point)
    }

    return segments
}

/**
 * 이 프레임의 유효한 유성 F0. 무성이거나 값이 성립하지 않으면 null이다.
 *
 * internal인 이유: 목소리 점검([VoiceCheckController])이 유성 프레임을 세는데, "무엇이 유성인가"의
 * 정의가 둘로 갈리면 곡선이 그려지는 조건과 점검이 통과하는 조건이 조용히 어긋난다.
 */
internal fun RecordingEngine.PitchFrame.voicedHz(): Float? {
    val hz = pitchHz ?: return null
    return if (hz.isFinite() && hz > 0f) hz else null
}
