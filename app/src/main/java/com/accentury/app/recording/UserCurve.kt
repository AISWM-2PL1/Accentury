package com.accentury.app.recording

import com.accentury.app.audio.RecordingEngine
import com.accentury.app.audio.YinPitchEstimator
import kotlin.math.ln

/**
 * 녹음 중 쌓인 사용자 F0 프레임을 캔버스 좌표로 바꾼다 (KAN-104).
 *
 * [GuideCurve]와 같은 자리의 순수 계산이다. 다른 점은 입력이 "완성된 배열"이 아니라
 * "지금까지 들어온 만큼"이라는 것 - 매 청크마다 다시 불리므로, 프레임이 늘어도 이미 그린
 * 부분이 흔들리지 않는 규칙이 필요하다. 아래 세 결정이 그 요구에서 나왔다.
 *
 * - **시간축은 가이드 길이에 맞춘다.** 사용자 레인의 폭 하나가 가이드 레인의 폭 하나와 같은
 *   시간을 뜻한다([userCurveWindowMs]). 위아래 두 곡선의 x가 같은 시각을 가리켜야 "여기서
 *   올렸어야 했다"를 눈으로 맞춰 볼 수 있다. 녹음이 그 길이를 넘어가면 창이 미끄러져
 *   최신 프레임이 항상 오른쪽 끝에 있게 하고, 창 밖으로 밀린 프레임은 버린다.
 * - **y축은 80..400Hz 고정 로그 스케일이다.** 가이드처럼 자기 min/max로 잡으면 새 최고점이
 *   찍힐 때마다 이미 그린 곡선 전체가 위아래로 튄다 - 실시간 곡선에서는 자기 스케일이
 *   거짓 움직임이 된다. 범위는 [YinPitchEstimator]가 낼 수 있는 밴드 그대로고, 로그를 쓰는
 *   이유는 같은 음정 간격이 같은 화면 거리가 되게 하기 위해서다(가이드의 semitone과 같은 근거).
 * - **무성 프레임은 그냥 버린다.** 폴리라인이 구멍을 가로질러 이어진다. 보간이나 직전 값
 *   유지는 곡선을 매끄럽게 만드는 후속 티켓(스무딩, 베지어)의 몫이고, 이 티켓은 동작 우선이다.
 *
 * y를 뒤집는 것(`1 - 정규화값`)은 가이드와 같다 - Canvas는 아래로 갈수록 y가 커진다.
 */

/** 가이드가 없거나 쓸 수 없을 때의 창 길이. 1초면 실시간 곡선이 움직이는 게 보인다 */
private const val FALLBACK_WINDOW_MS = 1000L

private val LOG_MIN_HZ = ln(YinPitchEstimator.MIN_F0_HZ.toDouble())
private val LOG_HZ_SPAN = ln(YinPitchEstimator.MAX_F0_HZ.toDouble()) - LOG_MIN_HZ

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
 * 지금까지 쌓인 프레임을 표시 좌표 목록으로 바꾼다. 그릴 유성 프레임이 없으면 빈 목록이다.
 *
 * [frames]는 시각 순이고, [windowMs]는 [userCurveWindowMs]가 준 값이다.
 */
fun userCurveDisplayPoints(
    frames: List<RecordingEngine.PitchFrame>,
    windowMs: Long,
): List<CurvePoint> {
    if (frames.isEmpty() || windowMs <= 0L) return emptyList()

    // 창의 오른쪽 끝은 항상 최신 프레임이다. 아직 창이 안 찼으면 0에서 시작해 곡선이
    // 왼쪽부터 자라고, 넘어서면 창이 통째로 미끄러진다.
    val newestMs = frames.maxOf { it.timestampMs }
    val windowStartMs = maxOf(0L, newestMs - windowMs)

    return frames.mapNotNull { frame ->
        val hz = frame.pitchHz ?: return@mapNotNull null
        if (!hz.isFinite()) return@mapNotNull null
        if (frame.timestampMs < windowStartMs) return@mapNotNull null

        val x = (frame.timestampMs - windowStartMs).toDouble() / windowMs
        // 추정기가 이미 밴드 안으로 눌러 주지만, 좌표가 레인 밖으로 나가는 일만은 없게 한다.
        val clamped = hz.toDouble().coerceIn(
            YinPitchEstimator.MIN_F0_HZ.toDouble(),
            YinPitchEstimator.MAX_F0_HZ.toDouble(),
        )
        val normalized = (ln(clamped) - LOG_MIN_HZ) / LOG_HZ_SPAN
        CurvePoint(x.toFloat(), (1.0 - normalized).toFloat())
    }
}
