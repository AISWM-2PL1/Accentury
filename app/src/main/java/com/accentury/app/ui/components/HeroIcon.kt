package com.accentury.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.accenturyColors

/**
 * 화면을 여는 원형 아이콘 (KAN-148). 웹의 `.hero-icon`과 같은 규격이다 —
 * 이모지 하나를 파랑 원에 얹고 테두리와 그림자로 띄운다.
 *
 * 아이콘 자산이 아니라 이모지를 쓰는 이유: 시안이 그렇게 잡았고, 화면마다 다른 그림이
 * 필요한데 벡터 자산을 화면 수만큼 들이면 관리 비용이 그림값보다 커진다.
 */
@Composable
fun HeroIcon(emoji: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.accenturyColors
    Box(
        modifier = modifier
            .size(Dimens.heroIconSize)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(colors.heroStart, colors.heroEnd),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .border(4.dp, MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = HERO_EMOJI_SIZE)
    }
}

private val HERO_EMOJI_SIZE = 56.sp
