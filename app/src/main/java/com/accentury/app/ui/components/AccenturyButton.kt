package com.accentury.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Motion
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.accenturyColors
import com.accentury.app.ui.theme.motionDuration

/**
 * 버튼 무게 (KAN-148). 화면들이 실제로 쓰는 셋만 둔다.
 *
 * - [Primary] 주동작 (녹음 시작·다음·허용)
 * - [Secondary] 보조 (재녹음·재시도)
 * - [Text] 이탈·종료. 눌리면 안 되는 쪽이라 무게를 가장 뺀다
 */
enum class ButtonVariant { Primary, Secondary, Text }

/**
 * 공통 버튼 (KAN-148). 웹의 `.btn`과 같은 모양·같은 값이다 —
 * 두 런타임이 한 테스트 안에서 번갈아 나오므로 버튼이 서로 다르게 생기면 바로 보인다.
 *
 * 시안(prototype ChunkyBtn)의 두께감을 그대로 옮겼다: 밑변에 [AccenturyColors.primaryDim]
 * 그림자를 깔아 두께를 만들고, 누르면 그림자가 줄면서 본체가 그만큼 내려간다.
 * Material의 elevation을 쓰지 않는 이유 - elevation은 사방으로 번지는 그림자라
 * 밑변만 있는 이 두께감이 안 나온다.
 *
 * 최소 높이는 [Dimens.touchTargetMin] 48dp다 (ux-ui.md §5).
 */
@Composable
fun AccenturyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
) {
    if (variant == ButtonVariant.Text) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.defaultMinSize(minHeight = Dimens.touchTargetMin),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
        return
    }

    val colors = MaterialTheme.accenturyColors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val isPrimary = variant == ButtonVariant.Primary
    val depthColor = if (isPrimary) colors.primaryDim else colors.controlBorder
    val fill = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val label = if (isPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    // 눌림은 깊이 하나로 표현된다 - 본체가 내려간 만큼 그림자가 줄어 총 높이가 유지된다.
    // 높이가 같이 변하면 옆 요소가 밀려 화면이 들썩인다.
    val depth by animateDpAsState(
        targetValue = if (pressed && enabled) Dimens.buttonPressedDepth else Dimens.buttonRestDepth,
        animationSpec = tween(
            durationMillis = motionDuration(Motion.PRESS),
            easing = Motion.easeOut,
        ),
        label = "buttonDepth",
    )
    val sink = Dimens.buttonRestDepth - depth

    val shape = RoundedCornerShape(Radius.md)

    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = MIN_BUTTON_WIDTH,
                minHeight = Dimens.touchTargetMin + Dimens.buttonRestDepth,
            )
            // 비활성은 불투명도만 낮춘다 - 회색으로 칠하면 색이 하나 더 늘고 배경에 따라
            // 대비를 잃는다. 원래 색을 흐리게 하면 대비가 함께 줄어 예측 가능하다.
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        /*
         * 그림자 판과 본체를 같은 크기로 잡고 서로 반대쪽에 padding을 준다 -
         * 전체 높이는 항상 (터치 타겟 + 두께)이고, 그 안에서 본체가 [sink]만큼 내려가면
         * 그림자가 그만큼 가려진다. 두 판의 크기를 따로 정하면 본체가 그림자보다 작아져
         * 그림자가 사방으로 삐져나온다.
         */
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = Dimens.buttonRestDepth)
                .clip(shape)
                .background(depthColor),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = Dimens.buttonRestDepth)
                .offset(y = sink)
                .clip(shape)
                .background(fill)
                .then(
                    if (isPrimary) Modifier
                    else Modifier.border(1.dp, colors.controlBorder, shape),
                )
                .clickableButton(enabled, interaction, onClick)
                .padding(horizontal = Spacing.x6),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = label)
        }
    }
}

/** 라벨이 짧아도 손가락이 닿을 만큼은 넓게 잡는다 */
private val MIN_BUTTON_WIDTH = 120.dp

private const val DISABLED_ALPHA = 0.6f

/** ripple 없이 누름 상태만 받는다 - 눌림 표현은 깊이 애니메이션이 이미 하고 있다 */
private fun Modifier.clickableButton(
    enabled: Boolean,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)
