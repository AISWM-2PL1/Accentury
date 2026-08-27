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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.accenturyColors

/**
 * 화면을 여는 원형 아이콘 (KAN-148, 형태는 KAN-161 2단계). 웹의 `.hero-icon`과 같은 규격이다 —
 * 오려 낸 크림 동그라미에 잉크 테두리를 두르고 오프셋 그림자로 띄운다.
 *
 * 잉크로 꽉 찬 원이었는데 뒤집었다: 잉크 면은 화면에서 주 버튼과 무게가 같아져 어느 쪽을
 * 눌러야 하는지가 흐려진다. 아이콘은 누르는 것이 아니다.
 *
 * 안에 들어가는 이모지는 아직 시안 이전 상태다 — 시안의 히어로는 손으로 오린 일러스트라
 * 잉크와 크림 두 색뿐인데, 이모지는 색을 갖고 있어 화면에서 유일한 색조로 남는다.
 * 일러스트 자산은 화면 이식(KAN-161 3·4단계) 몫이라 여기서는 형태만 맞춘다.
 */
@Composable
fun HeroIcon(emoji: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.accenturyColors
    Box(
        modifier = modifier
            .paperCircleShadow(colors.primaryDim)
            .size(Dimens.heroIconSize)
            .clip(CircleShape)
            .background(colors.heroStart)
            .border(HERO_BORDER, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = HERO_EMOJI_SIZE)
    }
}

/** 테두리 굵기. 주 CTA와 선택 상태만 2dp, 나머지는 1.5dp다 (시안 규칙) */
private val HERO_BORDER = 1.5.dp

private val HERO_EMOJI_SIZE = 56.sp
