package com.accentury.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.accentury.app.ui.theme.Dimens

/**
 * 오프셋 종이 그림자 (정본 §5, KAN-161 2단계). 오른쪽·아래로 [Dimens.paperShadowX]·
 * [Dimens.paperShadowY]만큼 어긋난 자리에 단색 면 한 겹을 깐다 — 번지지도, 흐려지지도,
 * 비쳐 보이지도 않는 그림자다. 오려 낸 종이가 바닥에 드리우는 그늘 한 장이다.
 *
 * Material의 `shadow`/`elevation`을 쓰지 않는 이유: 저쪽은 사방으로 번지는 흐린 그림자라
 * 이 모양이 나오지 않는다. 알파를 섞은 흐림은 Papercut에 없는 재질이다.
 *
 * ## 왜 padding으로 자리를 먼저 비우는가
 *
 * `drawBehind`만으로 그리면 그림자가 노드 바깥에 그려져 레이아웃이 그 자리를 모른다 —
 * 아래 요소가 그림자 위로 올라온다. 그래서 [padding]으로 어긋난 만큼을 먼저 비우고,
 * 비운 자리에 그림자를 그린다. 결과적으로 이 modifier를 붙인 요소가 차지하는 크기는
 * 본체 + (3dp, 4dp)이고, 그 안에서 본체는 왼쪽 위에 붙는다.
 *
 * ## 눌림
 *
 * 누르는 쪽은 이 modifier가 아니라 본체가 한다: 본체에 `offset(paperShadowX, paperShadowY)`를
 * 주면 정확히 그림자 자리로 내려가 그림자를 덮는다. 그래서 눌림 전후로 전체 크기가 같고,
 * 옆 요소가 밀려 화면이 들썩이지 않는다. 두 곳이 다른 값을 쓰면 종이가 바닥에 닿지 않고
 * 어긋난 채로 멈추므로, 양쪽 모두 [Dimens]의 같은 토큰을 읽는다.
 *
 * @param visible 그림자를 그릴지. `false`면 자리만 비운다 — 보조 버튼처럼 그림자가 없는
 *   컨트롤도 주 버튼과 같은 크기·같은 눌림 거리를 갖게 하려는 것이다.
 */
fun Modifier.paperShadow(
    color: Color,
    cornerRadius: Dp,
    visible: Boolean = true,
): Modifier = this
    .padding(end = Dimens.paperShadowX, bottom = Dimens.paperShadowY)
    .drawBehind {
        if (!visible) return@drawBehind
        drawRoundRect(
            color = color,
            topLeft = Offset(Dimens.paperShadowX.toPx(), Dimens.paperShadowY.toPx()),
            size = size,
            cornerRadius = CornerRadius(cornerRadius.toPx()),
        )
    }

/**
 * 원형용 [paperShadow]. 반경을 값으로 받지 않고 그릴 때 크기의 절반으로 잡는다 —
 * 원형 요소는 지름이 화면마다 다를 수 있고, 반경이 지름의 절반에서 조금이라도 어긋나면
 * 동그라미 뒤에서 각진 모서리가 삐져나온다.
 */
fun Modifier.paperCircleShadow(
    color: Color,
    visible: Boolean = true,
): Modifier = this
    .padding(end = Dimens.paperShadowX, bottom = Dimens.paperShadowY)
    .drawBehind {
        if (!visible) return@drawBehind
        drawCircle(
            color = color,
            radius = size.minDimension / 2f,
            center = Offset(
                x = size.width / 2f + Dimens.paperShadowX.toPx(),
                y = size.height / 2f + Dimens.paperShadowY.toPx(),
            ),
        )
    }
