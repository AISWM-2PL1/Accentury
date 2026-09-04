package com.accentury.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.accenturyColors

/**
 * 화면을 여는 원형 아이콘 (KAN-148, 형태는 KAN-161 2단계). 웹의 `.hero-icon`과 같은 규격이다 —
 * 오려 낸 크림 동그라미에 잉크 테두리를 두르고 오프셋 그림자로 띄운다.
 *
 * 잉크로 꽉 찬 원이었는데 뒤집었다: 잉크 면은 화면에서 주 버튼과 무게가 같아져 어느 쪽을
 * 눌러야 하는지가 흐려진다. 아이콘은 누르는 것이 아니다.
 *
 * 안에 들어가는 것은 **잉크 선화**다 (KAN-161 4단계). 이모지를 담고 있었는데, 잉크 한 색
 * 화면에서 이모지는 색을 가진 유일한 물건이라 종이에 붙인 스티커처럼 그림 밖으로 튄다 —
 * 오려 낸 크림 동그라미 안에 컬러 그림이 앉아 있는 모양이었다. 아이콘도 같은 잉크로 그린
 * 선 하나가 되어야 화면 전체가 종이 한 장으로 읽힌다 (정본 §7).
 *
 * 잉크로 꽉 찬 원이었던 **면**도 뒤집혀 있다 (2단계): 잉크 면은 화면에서 주 버튼과 무게가
 * 같아져 어느 쪽을 눌러야 하는지가 흐려진다. 아이콘은 누르는 것이 아니다.
 *
 * @param contentDescription 아이콘이 정보를 나를 때만 준다. 옆 제목이 같은 말을 하면
 *   `null`로 두어 스크린 리더가 같은 문장을 두 번 읽지 않게 한다.
 */
@Composable
fun HeroIcon(
    painter: Painter,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
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
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(HERO_GLYPH_SIZE),
        )
    }
}

/** 테두리 굵기. 주 CTA와 선택 상태만 2dp, 나머지는 1.5dp다 (시안 규칙) */
private val HERO_BORDER = 1.5.dp

/** 원 지름(112)의 절반쯤. 선화가 테두리에 닿지 않고 종이 가운데에 앉는 크기다 */
private val HERO_GLYPH_SIZE = 56.dp
