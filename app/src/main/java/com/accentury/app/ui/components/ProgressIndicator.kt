package com.accentury.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Motion
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.motionDuration

/**
 * 진척도 (KAN-148). 웹의 `ProgressIndicator`와 같은 구성이다 — 막대와 "3/10" 표기를
 * 한 덩어리로 묶는다. 둘이 떨어져 있으면 한쪽만 고쳐 숫자와 막대가 어긋나는 날이 온다.
 *
 * [current]가 1부터 시작하는 건 호출자 몫이자 의도다 — 첫 문항을 0/10으로 보이면 아직
 * 시작도 안 한 느낌이라 이탈이 는다 (ux-ui.md §3 Goal-Gradient, endowed progress).
 */
@Composable
fun ProgressIndicator(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier,
    label: String = "문항 진행률",
) {
    val fraction = progressFraction(current, total)
    // 문항이 넘어갈 때 막대가 순간이동하지 않고 자란다. 모션 축소면 0ms라 즉시 값이 바뀐다
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = motionDuration(Motion.BASE), easing = Motion.easeOut),
        label = "progress",
    )

    // 시안은 막대와 숫자를 한 줄에 눕힌다 - 숫자를 아래가 아니라 옆에 두면 세로 공간이
    // 문항 카드로 간다
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .weight(1f)
                .height(Dimens.progressBarHeight)
                /*
                 * 막대와 숫자가 각각 읽히면 스크린 리더가 같은 정보를 두 번 말한다.
                 * 막대에 "3/10문항"을 통째로 실어 한 번만 읽히게 하고, 아래 숫자는
                 * 의미론에서 뺀다(시각적으로는 남는다).
                 */
                .clearAndSetSemantics { contentDescription = "$label $current / $total" },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            // 웹의 ProgressIndicator와 같은 표기다 - 문항이 두 런타임을 오가므로 공백 하나도
            // 다르면 전환에서 숫자가 미세하게 움직인다
            "$current / $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * 막대가 채워질 비율. 계산만 떼어 둔 이유는 두 가장자리 때문이다 —
 * 정의가 비어 [total]이 0이면 0으로 나누기가 되고(NaN이 들어가면 막대가 사라진다),
 * 상태가 어긋나 [current]가 [total]을 넘으면 막대가 칸을 넘어 그려진다.
 * 둘 다 화면에서 알아채기 어려운 종류라 단위 테스트로 못 박는다.
 */
internal fun progressFraction(current: Int, total: Int): Float =
    if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
