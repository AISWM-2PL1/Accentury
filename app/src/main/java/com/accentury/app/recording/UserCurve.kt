package com.accentury.app.recording

import com.accentury.app.audio.READ_CHUNK_SIZE
import com.accentury.app.audio.RecordingEngine
import com.accentury.app.audio.SAMPLE_RATE
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 녹음 중 쌓인 사용자 F0 프레임을 캔버스 좌표로 바꾼다 (KAN-104 → KAN-105에서 다듬음).
 *
 * [GuideCurve]와 같은 자리의 순수 계산이다. 다른 점은 입력이 "완성된 배열"이 아니라
 * "지금까지 들어온 만큼"이라는 것 - 매 청크마다 다시 불리므로, 프레임이 늘어도 이미 그린
 * 부분이 흔들리지 않는 규칙이 필요하다. 아래 결정들이 그 요구에서 나왔다.
 *
 * - **사용자 창은 가이드 길이의 [USER_CURVE_WINDOW_SCALE]배다**([userCurveWindowMs], Review는
 *   녹음 전체 길이 [reviewWindowMs]). 시드 가이드보다 실제 발화가 길어서, 창을 가이드에
 *   맞춰 놓으면 발화 앞부분이 창 밖으로 밀린다. 녹음이 창 길이를 넘어가면 창이 미끄러져
 *   최신 프레임이 항상 오른쪽 끝에 있게 하고, 밀린 프레임은 버린다.
 * - **가이드 레인은 별도 시간축이다** (2026-08-25 결정). 가이드는 사용자 창이 얼마든 자기
 *   길이로 레인 폭 전체를 쓴다 - KAN-104의 원래 모양이다. 한때 가이드를 사용자 창에 맞춰
 *   축소해 두 레인의 같은 x가 같은 시각이 되게 했지만(KAN-104/AC4), 발화가 길수록 가이드가
 *   왼쪽 구석에 작게 눌려 정작 비교하라고 놓은 곡선이 더 안 보였다. 두 레인은 이제 같은 시각을
 *   맞춰 보는 도구가 아니라 **모양을 견주는 도구**다 - 세로축이 둘 다 semitone이라 오르내림의
 *   폭과 방향은 그대로 비교된다.
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
 * - **EMA는 프레임 개수가 아니라 시간으로 감쇠한다** (KAN-105). 구멍을 건너뛴 뒤의 첫 유성
 *   값이 옛 값에 끌려가지 않게, 벌어진 간격만큼 옛 값의 몫을 줄인다 - 자세한 이유는
 *   [USER_CURVE_EMA_ALPHA]에 적었다.
 *
 * y를 뒤집는 것(`1 - 정규화값`)은 가이드와 같다 - Canvas는 아래로 갈수록 y가 커진다.
 *
 * 계산은 전부 **인과적(causal)**이다 - 각 점은 자기보다 앞선 프레임만 보고 정해지므로,
 * 프레임이 더 쌓여도 이미 계산된 점의 y는 변하지 않는다. 실시간 곡선에서 과거가 다시 그려지면
 * 사용자에게는 곡선이 저 혼자 꿈틀대는 것으로 보인다.
 */

/**
 * 가이드가 없거나 쓸 수 없을 때의 창 길이. 가이드 길이를 모르니 1초를 기준 길이로 삼고,
 * 아래 [USER_CURVE_WINDOW_SCALE]을 똑같이 곱한다 - 창을 넓히는 이유(실제 발화가 기준 길이보다
 * 길다)는 가이드가 있든 없든 같으므로, 폴백만 1초로 두면 이 경우에만 앞부분이 밀려난다.
 */
private const val FALLBACK_GUIDE_MS = 1000L

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
 *
 * **적용은 프레임 개수가 아니라 시간 기준이다.** 무성 구멍을 사이에 두고 32ms짜리 α를 한 번만
 * 먹이면, 구멍이 얼마나 길든 새 값은 옛 값에 (1-α)=70%만큼 끌려간다 - 구멍이 길수록 그 옛 값은
 * 못 믿을 값인데도 그렇다. 그래서 직전 유성 프레임과 벌어진 만큼을 프레임 수로 환산해
 * `retain = (1-α)^gapFrames`를 쓴다. 연속 프레임(gapFrames=1)이면 `직전*0.7 + 현재*0.3`과
 * 정확히 같고, 구멍이 길어지면 옛 값의 몫이 저절로 사그라든다(100ms→34%, 250ms→6%, 500ms→0.3%).
 */
const val USER_CURVE_EMA_ALPHA = 0.3f

/**
 * 이 길이 이하의 무성 구간은 선을 잇고(직전 값 유지), 넘으면 끊는다.
 *
 * 자음·무성음(파열음 폐쇄 구간 등)과 문장 사이 쉼을 가르는 선이다. 무조건 이으면 쉼 구간에
 * 긴 가짜 평선이 생기고, 무조건 끊으면 한 어절 안에서도 자음마다 선이 조각난다.
 *
 * **100 → 250 (KAN-105).** 기식음이 많은 화자는 어절 안 자음 구간이 100ms를 예사로 넘긴다
 * (30대 샘플은 유성 판정률이 49%였다) - 그 화자에게는 100ms 기준이 한 어절을 대여섯 조각으로
 * 잘랐다. 늘리지 못하던 이유는 "구멍 뒤 첫 유성 값이 옛 값에 70% 끌려간다"였는데, EMA가
 * 시간 가중으로 바뀌면서([USER_CURVE_EMA_ALPHA]) 그 부작용이 사라졌다 - 250ms 구멍이면 옛 값의
 * 몫이 6%밖에 안 남는다. 남는 대가는 쉼이 시작된 뒤 최대 250ms짜리 평선인데, 인과적 곡선에서는
 * "쉼이 시작됐다"를 그 자리에서 알 방법이 없으므로 감수한다.
 *
 * 판정은 프레임 개수가 아니라 timestampMs 차이로 한다 - 청크 경계에서 프레임 수가 흔들려도
 * 시각은 흔들리지 않는다.
 */
const val HOLD_MAX_GAP_MS = 250L

/**
 * Review 화면에서 메워 주는 무성 구멍의 최대 길이. 이보다 긴 구멍은 진짜 쉼으로 보고 둔다.
 *
 * 실시간 곡선의 [HOLD_MAX_GAP_MS]보다 넉넉한 이유는 [fillShortGaps]에 적었다.
 */
const val REVIEW_FILL_MAX_GAP_MS = 500L

/**
 * 프레임 하나가 나오는 간격 (ms).
 *
 * 마이크를 [READ_CHUNK_SIZE]샘플씩 읽고 `OverlappedFramer`의 hop도 같은 크기라, 분석 창이
 * 그 간격마다 하나씩 완성된다. 16kHz에서 512/16000 = 32ms다. 숫자를 박아 두지 않고 두 상수에서
 * 뽑는 건, 청크 크기나 표본율이 바뀌면 EMA의 시간 환산도 같이 따라와야 하기 때문이다.
 */
private const val FRAME_INTERVAL_MS = READ_CHUNK_SIZE * 1000.0 / SAMPLE_RATE

/**
 * 창 길이를 가이드 길이의 몇 배로 잡을지.
 *
 * 시드 가이드는 한 문항이 0.9~1.2초인데 같은 문장을 실제로 읽으면 1.5~2.5초가 나온다.
 * 1배 창이면 말이 끝나기도 전에 발화 앞부분이 창 밖으로 밀려, 사용자는 자기 곡선의 시작을
 * 볼 수 없다. 2배면 그 발화가 통째로 들어오면서도 곡선이 지나치게 눌리지 않는다.
 *
 * 정확한 값은 실기기에서 눈으로 정한다(KAN-105 4단계) - 그때 이 상수 하나만 바꾸면 되게 두었다.
 */
const val USER_CURVE_WINDOW_SCALE = 2.0

/**
 * 가이드 곡선이 담는 시간. 프레임 간격 x 구간 수다. 길이를 알 수 없으면 0이다 -
 * 값이 1개뿐이면 구간이 0이고, 간격이 0 이하면 시간축 자체가 무의미하다.
 *
 * 인자를 GuideF0가 아니라 원시값으로 받는 건 [guideCurveDisplayPoints]와 같은 이유다 -
 * 그리기 계산이 bridge 레이어의 payload 타입을 알 필요가 없다.
 */
fun guideDurationMs(frameIntervalMs: Int?, valueCount: Int?): Long {
    if (frameIntervalMs == null || frameIntervalMs <= 0) return 0L
    if (valueCount == null || valueCount < 2) return 0L
    return frameIntervalMs.toLong() * (valueCount - 1)
}

/**
 * 사용자 레인 한 폭이 담을 시간. 가이드 길이의 [USER_CURVE_WINDOW_SCALE]배다.
 *
 * 가이드가 없거나(정의에 guideF0가 없음) 길이를 계산할 수 없으면 [FALLBACK_GUIDE_MS]를
 * 가이드 길이로 놓고 같은 배율을 곱한다.
 */
fun userCurveWindowMs(frameIntervalMs: Int?, valueCount: Int?): Long {
    val guideMs = guideDurationMs(frameIntervalMs, valueCount).takeIf { it > 0L } ?: FALLBACK_GUIDE_MS
    return (guideMs * USER_CURVE_WINDOW_SCALE).roundToLong()
}

/**
 * 녹음이 끝난 Review 화면이 쓸 창 길이. 라이브 창([userCurveWindowMs])과 "녹음 전체 길이" 중
 * 긴 쪽이다. 프레임이 없으면 라이브 창을 그대로 쓴다.
 *
 * 녹음 중에는 창이 미끄러져야 한다 - 지금 내 목소리가 오른쪽 끝에 붙어 있어야 방금 낸 소리와
 * 화면이 같이 움직인다. 그런데 녹음이 끝나면 볼 대상이 "방금 한 발화 전체"로 바뀐다. 라이브 창을
 * 그대로 두면 창 길이를 넘긴 발화는 마지막 구간만 남고 앞부분이 잘려 나가, 정작 다시 볼 수 있게
 * 된 시점에 앞부분을 못 본다.
 *
 * 창을 마지막 프레임 시각까지 늘리면 [userCurveDisplayPoints]의
 * `windowStartMs = max(0, newest - window)`가 0이 되어 처음부터 끝까지 그려진다. 한 프레임 간격
 * ([FRAME_INTERVAL_MS])을 더 얹는 건 마지막 점이 x=1인 오른쪽 모서리에 딱 붙지 않게 하기
 * 위해서다 - 그 프레임도 자기 몫의 폭을 차지한다.
 */
fun reviewWindowMs(frames: List<RecordingEngine.PitchFrame>, liveWindowMs: Long): Long {
    val lastMs = frames.maxOfOrNull { it.timestampMs } ?: return liveWindowMs
    return maxOf(liveWindowMs, lastMs + FRAME_INTERVAL_MS.roundToLong())
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
 *
 * **"선을 잇는가"와 "옛 값을 얼마나 끌고 오는가"는 이제 다른 일이다.** [HOLD_MAX_GAP_MS] 판정은
 * 선분을 가를지만 정하고, 옛 값의 몫은 시간 가중 EMA가 간격을 보고 연속적으로 정한다. 예전에는
 * 둘이 한 판정에 묶여 있어서, 유지 한계를 늘리면 "구멍이 길어도 옛 값을 70% 끌고 온다"가 딸려
 * 왔다 - 그 결합을 풀었기에 [HOLD_MAX_GAP_MS]를 250까지 늘릴 수 있었다. 끊김 뒤에는 어차피
 * 간격이 250ms를 넘어 옛 값의 몫이 6% 아래로 떨어지므로, 선분 첫 값을 그대로 놓는 것은
 * 시간 가중 식이 이미 내놓는 답을 정확히 0으로 못박는 일에 가깝다.
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
            val st = 12.0 * log2(hz / center.toDouble())
            smoothed = if (previousMs != null && withinHold) {
                // 시간 가중 EMA. 직전 유성 프레임에서 벌어진 만큼을 프레임 수로 환산해 옛 값의
                // 몫을 그만큼 거듭 깎는다 - 연속 프레임이면 gapFrames=1이라 기존 식과 같다.
                val gapFrames = ((frame.timestampMs - previousMs).toDouble() / FRAME_INTERVAL_MS)
                    .roundToInt()
                    .coerceAtLeast(1)
                val retain = (1.0 - USER_CURVE_EMA_ALPHA).pow(gapFrames)
                st + (smoothed - st) * retain
            } else {
                // 선분이 새로 시작될 때(맨 처음, 또는 끊김 뒤)는 첫 값 그대로 놓는다.
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
 * 두 유성 프레임 사이의 짧은 무성 구멍을 **semitone 선형 보간**으로 메운 프레임 목록.
 * 원본은 건드리지 않고 같은 길이·같은 순서·같은 timestampMs의 새 목록을 돌려준다.
 *
 * **Review 화면 전용이다.** 실시간 곡선은 인과적이어야 해서(자기보다 뒤의 프레임을 보면 과거가
 * 다시 그려진다) 구멍을 앞 값으로 유지하는 수밖에 없지만, Review는 녹음이 끝나 데이터가 완성된
 * 뒤라 구멍의 양옆을 다 보고 메워도 거짓이 아니다. 가이드 곡선([guideCurveDisplayPoints])이
 * 이미 같은 규칙으로 무성 구간을 잇고 있어, 위아래 두 레인의 규칙이 이것으로 맞아떨어진다.
 *
 * 보간을 Hz가 아니라 log(semitone) 영역에서 하는 이유는 곡선의 y축이 semitone이기 때문이다 -
 * Hz 선형 보간은 화면 위에서 아래로 휜 선이 된다. 200Hz와 800Hz 사이의 한가운데는 500Hz가
 * 아니라 기하평균 400Hz다. EMA를 semitone에 거는 것과 같은 근거다.
 *
 * 구멍 길이는 양옆 유성 프레임의 timestampMs 차이로 잰다([HOLD_MAX_GAP_MS]와 같은 기준).
 * [maxGapMs]를 넘는 구멍은 진짜 쉼으로 보고 그대로 둔다 - 문장 사이가 이어지면 안 된다.
 * 앞뒤 가장자리의 구멍(녹음 시작 전·끝난 뒤)도 그대로다. 이어 줄 반대쪽 이웃이 없어 메우는
 * 것이 보간이 아니라 값을 지어내는 일이 된다.
 *
 * 메운 프레임을 [userCurveDisplayPoints]의 EMA가 다시 스무딩해도 상관없다 - 보간값은 직선
 * 위에 놓여 있어 스무딩이 거의 움직이지 않는다.
 */
fun fillShortGaps(
    frames: List<RecordingEngine.PitchFrame>,
    maxGapMs: Long = REVIEW_FILL_MAX_GAP_MS,
): List<RecordingEngine.PitchFrame> {
    val voiced = frames.withIndex().mapNotNull { (i, f) ->
        f.voicedHz()?.let { IndexedValue(i, it) }
    }
    // 유성이 하나뿐이면 사이에 낀 구멍이라는 게 없다.
    if (voiced.size < 2) return frames

    val filled = frames.toMutableList()
    for (k in 0 until voiced.size - 1) {
        val (i0, hz0) = voiced[k]
        val (i1, hz1) = voiced[k + 1]
        if (i1 - i0 <= 1) continue // 붙어 있어 메울 자리가 없다

        val startMs = frames[i0].timestampMs
        val spanMs = frames[i1].timestampMs - startMs
        if (spanMs <= 0L || spanMs > maxGapMs) continue

        // semitone 영역이면 곧 log2(Hz)의 상수배라, 밑이 무엇이든 선형 보간 결과는 같다.
        val log0 = log2(hz0.toDouble())
        val log1 = log2(hz1.toDouble())
        for (i in i0 + 1 until i1) {
            val t = (frames[i].timestampMs - startMs).toDouble() / spanMs
            filled[i] = frames[i].copy(pitchHz = 2.0.pow(log0 + (log1 - log0) * t).toFloat())
        }
    }
    return filled
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
