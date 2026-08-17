package com.accentury.app.recording

/**
 * 가이드 곡선(guideF0)을 캔버스에 그릴 수 있는 좌표로 바꾼다 (KAN-102).
 *
 * 순수 계산만 있다 — Compose도 Canvas도 모른다. 출력은 "시간축 어디(x)·음높이 어디(y)"라는
 * 0..1 비율뿐이고, 실제 픽셀로 늘리는 건 그리는 쪽 일이다. 이렇게 갈라 둔 이유는 곡선 처리
 * 규칙(무성 보간·표시 스케일·좌표 뒤집기)을 기기 없이 JVM 단위 테스트로 전부 덮기 위해서다.
 *
 * 여기가 소화하는 규칙들:
 *
 * - **무성 구간은 `null`이다** (2026-08-17 결정). 스키마 단위가 정규화 semitone이라 0은
 *   "평균 음높이"라는 유효한 값이다 — Hz였다면 0을 무성으로 썼겠지만 이 스키마에선 값과
 *   무성을 숫자로 겹쳐 표현할 방법이 없어 `null`만 남는다. `NaN`은 계약에 없지만 산출
 *   파이프라인(KAN-17) 사고를 대비해 무성으로 취급한다.
 * - **곡선 중간의 무성 구간은 선형 보간으로 잇는다** (AC: 무성 구간이 곡선 끊김 없이 처리).
 *   가이드의 역할은 채점이 아니라 억양 모양 힌트라, 구멍 난 곡선보다 이어진 곡선이 목적에 맞다.
 * - **앞뒤 가장자리의 무성 구간은 그리지 않는다.** 이어줄 반대쪽 이웃이 없어 보간이 아니라
 *   날조가 된다 — 평평한 가짜 꼬리를 붙이는 대신 곡선이 첫·끝 유성 프레임에서 시작·끝나게
 *   두되, x 위치는 원래 시각을 유지한다(시간축은 배열 전체 길이 기준).
 * - **표시 스케일은 곡선 자신의 min/max다** (ux-ui.md "레인별 자기 스케일"). 티켓의 "표시
 *   범위 100~250Hz로 좁게"는 Hz 스키마 시절 문구고, 의도(움직임이 커 보이게)는 자기 스케일이
 *   그대로 담당한다. 여백 [SCALE_PADDING]은 최고·최저점이 레인 가장자리에 붙지 않게 한다.
 * - **y는 뒤집는다** (`1 - 정규화값`). Canvas는 아래로 갈수록 y가 커지므로 뒤집지 않으면
 *   높은 음이 아래로 그려진다.
 */

/** 표시 좌표 한 점. x·y 모두 0..1 — 그리기 직전에 캔버스 크기를 곱한다 */
data class CurvePoint(val x: Float, val y: Float)

/** 표시 스케일 여백 비율. min/max 바깥으로 range의 10%씩 넓혀 잡는다 */
private const val SCALE_PADDING = 0.1

/**
 * guideF0 `values`를 표시 좌표 목록으로 바꾼다. 그릴 수 있는 유성 프레임이 하나도 없으면
 * 빈 목록이다 — 이때 어떻게 보일지(빈 레인)는 그리는 쪽이 정한다.
 */
fun guideCurveDisplayPoints(values: List<Double?>): List<CurvePoint> {
    val voiced = values.withIndex().filter { (_, v) -> v != null && v.isFinite() }
    if (voiced.isEmpty()) return emptyList()

    val first = voiced.first().index
    val last = voiced.last().index

    // 첫~끝 유성 프레임 사이를 촘촘한 값 배열로 만든다. 유성 프레임은 제 값 그대로,
    // 사이에 낀 무성 프레임은 양옆 유성 값의 선형 보간이다.
    val filled = DoubleArray(last - first + 1)
    filled[0] = voiced.first().value!!
    for (k in 0 until voiced.size - 1) {
        val (i0, v0) = voiced[k]
        val (i1, v1) = voiced[k + 1]
        for (i in i0..i1) {
            val t = if (i1 == i0) 0.0 else (i - i0).toDouble() / (i1 - i0)
            filled[i - first] = v0!! + (v1!! - v0) * t
        }
    }

    // 보간값은 항상 양옆 유성 값 사이에 있으므로, filled의 min/max는 곧 유성 값의 min/max다.
    val min = filled.min()
    val range = filled.max() - min

    val lastIndex = values.size - 1
    return filled.mapIndexed { k, v ->
        val i = first + k
        val x = if (lastIndex == 0) 0.5f else i.toFloat() / lastIndex
        // 평평한 곡선(range 0)은 나눗셈이 안 되고, 어디 그리든 정보가 없으니 레인 중앙에 둔다.
        val y = if (range == 0.0) 0.5f
        else (1.0 - (v - min + range * SCALE_PADDING) / (range * (1 + 2 * SCALE_PADDING))).toFloat()
        CurvePoint(x, y)
    }
}
