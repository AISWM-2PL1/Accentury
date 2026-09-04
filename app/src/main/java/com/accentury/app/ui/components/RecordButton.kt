package com.accentury.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.accentury.app.R
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Motion
import com.accentury.app.ui.theme.accenturyColors
import com.accentury.app.ui.theme.isReducedMotionEnabled
import com.accentury.app.ui.theme.motionDuration

/**
 * 원형 녹음 버튼 (KAN-148, 형태는 KAN-161 2단계). 시안이 이 화면에만 주는 모양이다 —
 * 다른 버튼은 모두 알약꼴인데 녹음만 원형이라, 화면에서 눌러야 할 것이 무엇인지 모양만으로
 * 읽힌다.
 *
 * 오려 낸 크림 동그라미에 잉크 테두리 2dp를 두르고 [paperCircleShadow]가 그늘을 깐다.
 * 누르면 다른 버튼과 같은 거리만큼 내려가 그늘을 덮는다.
 *
 * 색으로는 상태를 말하지 않는다 (정본 §7). 녹음 중이라는 것은 **안쪽 도형**이 말한다 —
 * 대기는 마이크 선화, 녹음 중은 잉크 정사각형(정지의 관용 기호)이다. 여기에 둘레의
 * 파문과 접근성 라벨이 겹쳐 세 신호가 같은 것을 알린다.
 *
 * 파문은 모션 축소에서 멈춘다 — 끝없이 반복하는 애니메이션은 전정 장애가 있는 사용자에게
 * 실제로 불편을 준다 (WCAG 2.3.3). 대신 도형과 라벨은 그대로라 상태 정보는 잃지 않는다.
 */
@Composable
fun RecordButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = isReducedMotionEnabled()
    val ink = MaterialTheme.colorScheme.primary

    val sink by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(
            durationMillis = motionDuration(Motion.PRESS),
            easing = Motion.easeOut,
        ),
        label = "recordSink",
    )

    Box(
        modifier = modifier
            .size(
                width = Dimens.recordButtonSize + Dimens.paperShadowX,
                height = Dimens.recordButtonSize + Dimens.paperShadowY,
            )
            // 아이콘에는 설명을 달지 않고 버튼이 통째로 하나로 읽히게 한다 - 그러지 않으면
            // 스크린 리더가 아이콘과 버튼을 따로 짚는다
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
        // 그림자 자리를 오른쪽·아래에 두므로 본체는 왼쪽 위에 붙는다
        contentAlignment = Alignment.TopStart,
    ) {
        if (recording && !reduceMotion) {
            Box(
                modifier = Modifier.size(Dimens.recordButtonSize),
                contentAlignment = Alignment.Center,
            ) {
                RecordingRipple(color = ink)
            }
        }
        Box(
            modifier = Modifier
                .paperCircleShadow(MaterialTheme.accenturyColors.primaryDim)
                .size(Dimens.recordButtonSize)
                .offset(x = sink * Dimens.paperShadowX, y = sink * Dimens.paperShadowY)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(BUTTON_BORDER, ink, CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                /*
                 * 정지는 아이콘 자산 없이 사각형 하나로 그린다 - 재생/정지의 관용 기호라
                 * 따로 설명하지 않아도 읽히고, 마이크에 사선을 그은 그림보다 "지금 누르면
                 * 멈춘다"가 분명하다.
                 */
                Box(
                    modifier = Modifier
                        .size(STOP_ICON_SIZE)
                        .clip(RoundedCornerShape(STOP_ICON_RADIUS))
                        .background(ink),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.outline_mic_24),
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(RECORD_ICON_SIZE),
                )
            }
        }
    }
}

/**
 * 녹음 중 둘레로 퍼지는 파문. 잉크 테두리 하나가 커지면서 옅어지고 처음부터 다시 시작한다 —
 * 면이 아니라 선인 이유는 반투명한 면이 크림 위에서 회색 얼룩으로 보이기 때문이다.
 * 종이 오리기 그림에는 비쳐 보이는 면이 없다 (정본 §7).
 */
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
            .alpha(1f - progress)
            .border(RIPPLE_BORDER, color, CircleShape),
    )
}

private val BUTTON_BORDER = 2.dp
private val RECORD_ICON_SIZE = 34.dp

/** 정지 사각형. 시안 값(28dp·모서리 6dp) 그대로다 */
private val STOP_ICON_SIZE = 28.dp
private val STOP_ICON_RADIUS = 6.dp

private val RIPPLE_BORDER = 2.dp
private const val RIPPLE_MS = 1_400
private const val RIPPLE_GROWTH = 0.6f
