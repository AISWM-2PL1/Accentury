package com.accentury.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Motion
import com.accentury.app.ui.theme.isReducedMotionEnabled

/**
 * 원형 녹음 버튼 (KAN-148). 시안이 이 화면에만 주는 모양이다 — 다른 버튼은 모두 알약꼴인데
 * 녹음만 원형이라, 화면에서 눌러야 할 것이 무엇인지 모양만으로 읽힌다.
 *
 * [recording]이면 둘레에 파문이 퍼진다. 녹음 중이라는 것을 색(빨강)만으로 알리면 색각 이상에서
 * 정지 상태와 구분이 안 되므로, 움직임과 라벨(■ 정지)이 함께 신호를 준다.
 *
 * 파문은 모션 축소에서 멈춘다 — 끝없이 반복하는 애니메이션은 전정 장애가 있는 사용자에게
 * 실제로 불편을 준다 (WCAG 2.3.3). 대신 색과 라벨은 그대로라 상태 정보는 잃지 않는다.
 */
@Composable
fun RecordButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = isReducedMotionEnabled()

    val fill = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val sink = if (pressed) Dimens.buttonRestDepth - Dimens.buttonPressedDepth else 0.dp

    Box(
        modifier = modifier.size(Dimens.recordButtonSize + Dimens.buttonRestDepth * 2),
        contentAlignment = Alignment.Center,
    ) {
        if (recording && !reduceMotion) {
            RecordingRipple(color = fill)
        }
        Box(
            modifier = Modifier
                .size(Dimens.recordButtonSize)
                .offset(y = sink)
                .clip(CircleShape)
                .background(fill)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 이모지는 글꼴 크기로만 커진다 - 타이포 스케일이 아니라 아이콘 치수를 쓴다
            Text(label, fontSize = RECORD_ICON_SIZE)
        }
    }
}

/** 녹음 중 둘레로 퍼지는 파문. 커지면서 옅어지고 처음부터 다시 시작한다 */
@Composable
private fun RecordingRipple(color: Color) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = RIPPLE_MS, easing = Motion.easeOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rippleProgress",
    )

    Box(
        modifier = Modifier
            .size(Dimens.recordButtonSize)
            .scale(1f + progress * RIPPLE_GROWTH)
            .alpha((1f - progress) * RIPPLE_MAX_ALPHA)
            .clip(CircleShape)
            .background(color),
    )
}

private val RECORD_ICON_SIZE = 30.sp

private const val RIPPLE_MS = 1_400
private const val RIPPLE_GROWTH = 0.6f
private const val RIPPLE_MAX_ALPHA = 0.35f
