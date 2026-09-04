package com.accentury.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing

/**
 * 진척도 (KAN-148, 형태는 KAN-161 2단계). 웹의 `ProgressIndicator`와 같은 구성이다 —
 * 도트 줄과 "3 / 10" 표기를 한 덩어리로 묶는다. 둘이 떨어져 있으면 한쪽만 고쳐 숫자와
 * 도트가 어긋나는 날이 온다.
 *
 * 막대 하나였는데 [total]개의 캡슐로 바꿨다. 남은 문항을 세어 볼 수 있고, 칸 하나가
 * 채워지는 것이 막대가 조금 자라는 것보다 "한 문항 넘어갔다"로 읽힌다.
 *
 * 세 상태를 색이 아니라 **형태**로 가른다 (정본 §7·§8): 완료는 잉크로 찬 캡슐, 현재는
 * 테두리가 2dp로 두꺼워지고 왼쪽 절반만 찬 캡슐, 미완료는 빈 캡슐이다.
 * `primaryDim`(#cfc5aa)으로 남은 칸을 칠하지 않는다 — 크림 위 1.46:1이라 안 보인다.
 *
 * [current]가 1부터 시작하는 건 호출자 몫이자 의도다 — 첫 문항을 0/10으로 보이면 아직
 * 시작도 안 한 느낌이라 이탈이 는다 (ux-ui.md §3 Goal-Gradient, endowed progress).
 */
@Composable
fun ProgressIndicator(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier,
    label: String = "문항 진행률",
    note: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                /*
                 * 값을 읽는 것은 이 줄 하나다. 도트 열 개가 각각 읽히면 스크린 리더가 같은
                 * 정보를 열 번 말하므로 줄 전체에 "3 / 10문항"을 통째로 실어 한 번만 읽히게
                 * 하고, 아래 숫자는 의미론에서 뺀다(시각적으로는 남는다).
                 */
                .clearAndSetSemantics { contentDescription = "$label $current / $total" },
            horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(maxOf(total, 0)) { index ->
                ProgressDot(state = dotState(index + 1, current))
            }
        }
        Text(
            // 웹의 ProgressIndicator와 같은 표기다 - 문항이 두 런타임을 오가므로 공백 하나도
            // 다르면 전환에서 숫자가 미세하게 움직인다
            if (note == null) "$current / $total" else "$current / $total · $note",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 숫자는 시각 전용 - 위 Row가 이미 "문항 진행률 3 / 10"을 읽는다 (PR #58 리뷰 P2).
            // align만 남기고 clearAndSetSemantics를 떨어뜨리면 TalkBack이 문항마다 두 번 읽는다.
            modifier = Modifier.align(Alignment.End).clearAndSetSemantics { },
        )
    }
}

/** 도트 하나의 상태 */
internal enum class ProgressDotState { Done, Current, Todo }

@Composable
private fun RowScope.ProgressDot(state: ProgressDotState) {
    val ink = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surface
    val shape = RoundedCornerShape(Radius.full)

    Box(
        modifier = Modifier
            .weight(1f)
            .height(Dimens.progressDotHeight)
            .clip(shape)
            .background(if (state == ProgressDotState.Done) ink else paper)
            /*
             * 현재 칸만 왼쪽 절반이 차 있다. 배경 위에 사각형 하나를 얹을 뿐이라 번지는
             * 면이 아니고, 바깥의 clip이 캡슐 모양으로 잘라 준다 - 웹이 50%에서 딱 끊기는
             * linear-gradient로 그리는 것과 같은 결과다.
             */
            .drawBehind {
                if (state != ProgressDotState.Current) return@drawBehind
                drawRect(color = ink, size = Size(size.width / 2f, size.height))
            }
            .border(
                width = if (state == ProgressDotState.Current) CURRENT_BORDER else DOT_BORDER,
                color = ink,
                shape = shape,
            ),
    )
}

private val DOT_BORDER = 1.dp
private val CURRENT_BORDER = 2.dp

/**
 * 몇 번째 칸이 어떤 상태인가. 계산을 떼어 둔 이유는 경계다 — `position == current`가
 * 현재 칸이고 그보다 앞이 완료인데, 부등호를 한 칸 잘못 쓰면 진행이 통째로 밀린다.
 */
internal fun dotState(position: Int, current: Int): ProgressDotState = when {
    position < current -> ProgressDotState.Done
    position == current -> ProgressDotState.Current
    else -> ProgressDotState.Todo
}
