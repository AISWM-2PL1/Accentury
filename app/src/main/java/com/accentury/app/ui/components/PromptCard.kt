package com.accentury.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.accenturyColors

/**
 * 대사 카드 (KAN-148). 웹의 `.prompt-card`와 같은 규격이다 — 어휘 문항(웹)과 이 화면이
 * 번갈아 나오므로 카드 크기·모서리·그림자가 다르면 전환마다 화면이 들썩인다.
 *
 * 높이를 [Dimens.promptCardMinHeight]로 잡는 이유도 같다: 문항마다 글자 수가 달라도
 * 카드가 같은 크기여야 아래 요소가 제자리에 있는 것처럼 읽힌다.
 *
 * 그라디언트는 대각선이다. 단색으로 채우면 카드가 평평해 보여서, 시안이 이 카드에만
 * 준 깊이감을 잃는다.
 */
@Composable
fun PromptCard(
    badge: String,
    prompt: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val colors = MaterialTheme.accenturyColors
    val shape = RoundedCornerShape(Radius.xl)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimens.promptCardMinHeight)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(colors.promptCardStart, colors.promptCardEnd),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .padding(Dimens.promptCardPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text(
            badge,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onPromptCardMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.full))
                .background(colors.promptCardBadge)
                .padding(horizontal = Spacing.x3, vertical = Spacing.x1),
        )
        Text(
            prompt,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onPromptCard,
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onPromptCardMuted,
            )
        }
    }
}
