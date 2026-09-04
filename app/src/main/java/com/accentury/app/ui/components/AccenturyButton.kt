package com.accentury.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Motion
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.accenturyColors
import com.accentury.app.ui.theme.motionDuration

/**
 * 버튼 무게 (KAN-148). 화면들이 실제로 쓰는 셋만 둔다.
 *
 * - [Primary] 주동작 (녹음 시작·다음·허용). 화면에서 유일하게 잉크로 꽉 찬 면이다
 * - [Secondary] 보조 (재녹음·재시도). 테두리만 두르고 그림자를 두지 않는다
 * - [Text] 이탈·종료. 눌리면 안 되는 쪽이라 무게를 가장 뺀다
 */
enum class ButtonVariant { Primary, Secondary, Text }

/**
 * 공통 버튼 (KAN-148, 형태는 KAN-161 2단계). 웹의 `.btn`과 같은 모양·같은 값이다 —
 * 두 런타임이 한 테스트 안에서 번갈아 나오므로 버튼이 서로 다르게 생기면 바로 보인다.
 *
 * 오려 낸 종이다: 오른쪽·아래로 어긋난 자리에 [paperShadow]가 단색 면 한 겹을 깔아 종이가
 * 떠 있는 것처럼 보이고, 누르면 본체가 정확히 그만큼 내려가 그림자를 덮는다 — 종이가
 * 바닥에 닿는 순간이다. 총 차지 높이는 눌림 전후로 같아서 옆 요소가 밀리지 않는다.
 *
 * 그림자는 주 버튼에만 있다. 그림자는 "떠 있다"는 뜻이라 화면에 떠 있는 종이가 둘이면
 * 어느 쪽을 눌러야 하는지가 흐려진다. 보조 버튼은 자리만 같게 비우고(눌림 거리가 같다)
 * 그림자를 그리지 않는다.
 *
 * 최소 높이는 주 버튼이 [Dimens.controlHeightLg] 56dp, 보조가 [Dimens.touchTargetMin] 48dp다
 * (ux-ui.md §5의 48dp 최소선을 둘 다 넘는다).
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
    val shape = RoundedCornerShape(Radius.md)

    // 눌림은 0..1 한 값이다 - 본체가 내려가는 거리를 x·y 따로 애니메이션하면 두 축이
    // 미세하게 어긋나 종이가 비스듬히 미끄러진다.
    val sink by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(
            durationMillis = motionDuration(Motion.PRESS),
            easing = Motion.easeOut,
        ),
        label = "buttonSink",
    )

    Box(
        modifier = modifier
            // 비활성은 불투명도만 낮춘다 - 회색으로 칠하면 색이 하나 더 늘고 배경에 따라
            // 대비를 잃는다. 원래 색을 흐리게 하면 대비가 함께 줄어 예측 가능하다.
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .paperShadow(colors.primaryDim, Radius.md, visible = isPrimary)
            .offset(x = sink * Dimens.paperShadowX, y = sink * Dimens.paperShadowY)
            .defaultMinSize(
                minWidth = MIN_BUTTON_WIDTH,
                minHeight = if (isPrimary) Dimens.controlHeightLg else Dimens.touchTargetMin,
            )
            .clip(shape)
            .background(if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            // 주 버튼도 테두리를 두른다. 면과 같은 색이라 낭비 같지만, 크림 배경 위에서
            // 잉크 면의 가장자리가 종이를 오린 자리처럼 또렷해진다.
            .border(
                width = if (isPrimary) PRIMARY_BORDER else SECONDARY_BORDER,
                color = colors.controlBorder,
                shape = shape,
            )
            .clickableButton(enabled, interaction, onClick)
            .padding(horizontal = Spacing.x6),
        contentAlignment = Alignment.Center,
    ) {
        if (isPrimary) {
            // 주 CTA 라벨은 Jua 20sp(`titleSmall`) - 이 화면에서 눌러야 할 것이 제목만큼 크다
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                letterSpacing = PRIMARY_LETTER_SPACING,
            )
        } else {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 라벨이 짧아도 손가락이 닿을 만큼은 넓게 잡는다 */
private val MIN_BUTTON_WIDTH = 120.dp

/** 테두리 굵기. 주 CTA와 선택 상태만 2dp, 나머지는 1.5dp다 (시안 규칙) */
private val PRIMARY_BORDER = 2.dp
private val SECONDARY_BORDER = 1.5.dp

/** Jua 라벨의 자간. 굵기를 못 올리는 폰트라 자간이 라벨의 무게를 대신한다 */
private val PRIMARY_LETTER_SPACING = 0.4.sp

private const val DISABLED_ALPHA = 0.6f

/** ripple 없이 누름 상태만 받는다 - 눌림 표현은 종이가 내려가는 것으로 이미 하고 있다 */
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
