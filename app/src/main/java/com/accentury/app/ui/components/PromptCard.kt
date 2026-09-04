package com.accentury.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.accenturyColors

/**
 * 대사 카드 (KAN-148, 형태는 KAN-161 2단계). 웹의 `.prompt-card`와 같은 규격이다 —
 * 어휘 문항(웹)과 이 화면이 번갈아 나오므로 카드 크기·모서리·그림자가 다르면 전환마다
 * 화면이 들썩인다.
 *
 * 오려 낸 종이 카드다: 크림 면에 잉크 테두리 1.5dp를 두르고 [paperShadow]가 오른쪽·아래로
 * 어긋난 그늘 한 겹을 깐다. 카드와 배경이 같은 크림이라 카드를 세우는 것은 색이 아니라
 * 테두리와 그늘이다 — 그라디언트로 깊이를 내던 자리를 이 둘이 대신한다.
 *
 * 높이를 [Dimens.promptCardMinHeight]로 잡는 이유는 그대로다: 문항마다 글자 수가 달라도
 * 카드가 같은 크기여야 아래 요소가 제자리에 있는 것처럼 읽힌다.
 *
 * [caption]은 알약 배지가 아니라 카드 맨 위 캡션 한 줄이다 — 배지는 면을 하나 더 만드는데,
 * 카드가 이미 배경과 같은 크림이라 그 면이 카드 안에 또 카드를 그린 것처럼 보였다.
 * `badge`라는 이름이 그 사라진 알약을 가리키고 있어 캡션으로 바꿔 달았다 (KAN-161 4단계).
 *
 * [supporting]은 아트보드에 없다. 대사 아래 한 줄을 더 두면 카드가 "읽을 문장"과 "읽는 법"
 * 둘을 함께 내미는데, 그 둘의 글자 크기 차이(26 대 14)만으로는 어느 쪽을 소리 내야 하는지가
 * 즉시 갈리지 않는다. 안내는 카드 밖 하단 캡션이 맡는다. 남겨 둔 이유는 아트보드가 없는
 * 화면(목소리 점검)이 아직 쓰기 때문이다.
 */
@Composable
fun PromptCard(
    caption: String,
    prompt: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val colors = MaterialTheme.accenturyColors
    val shape = RoundedCornerShape(Radius.xl)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .paperShadow(colors.primaryDim, Radius.xl)
            .defaultMinSize(minHeight = Dimens.promptCardMinHeight)
            .clip(shape)
            .background(colors.promptCardStart)
            .border(CARD_BORDER, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(Dimens.promptCardPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2, Alignment.CenterVertically),
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onPromptCardMuted,
        )
        Text(
            prompt,
            /*
             * 한국어를 낱말 단위로 끊는다 (시안의 `word-break: keep-all`). 기본 전략은 글자
             * 아무 데서나 줄을 바꿔서 "자연스럽게"가 "자연스"/"럽게"로 갈리는데, 소리 내어
             * 읽는 문장에서 그 끊김은 읽는 사람을 그대로 더듬게 만든다.
             *
             * `Paragraph`는 줄바꿈 후보를 문단 전체를 보고 고르는 전략이라(기본 `Simple`은
             * 한 줄씩 채운다) 마지막 줄에 한 낱말만 남는 모양도 함께 줄어든다.
             */
            style = MaterialTheme.typography.headlineMedium.copy(
                lineBreak = LineBreak.Paragraph,
            ),
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

/** 테두리 굵기. 주 CTA와 선택 상태만 2dp, 나머지는 1.5dp다 (시안 규칙) */
private val CARD_BORDER = 1.5.dp
