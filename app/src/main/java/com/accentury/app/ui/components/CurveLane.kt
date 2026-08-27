package com.accentury.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.accentury.app.recording.CurvePoint
import com.accentury.app.recording.PathCommand
import com.accentury.app.recording.smoothPathCommands
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.accenturyColors

/**
 * 레인의 성격 (KAN-161 2단계). 선 굵기·점선·망점·색이 함께 움직이므로 하나로 묶는다 —
 * 넷을 따로 받으면 호출자마다 조합이 달라져 "가이드처럼 생긴 내 곡선"이 만들어진다.
 */
internal enum class CurveLaneVariant { Guide, User }

/**
 * 곡선 레인을 담는 상자 (KAN-161 2단계). 레인 하나든 둘이든 테두리와 모서리는 이 상자가
 * 갖고 레인 자신은 갖지 않는다 — 레인마다 테두리를 두르면 상자 안에 상자가 겹쳐 선이
 * 두 겹으로 보인다. 웹 `.curve-card`와 같은 규격이다.
 */
@Composable
internal fun CurveLaneGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.accenturyColors
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.curveLaneSurface)
            .border(GROUP_BORDER, colors.curveLaneBorder, shape),
        content = content,
    )
}

/**
 * 곡선 캔버스의 레인 하나 (ux-ui.md §D — 위/아래 2단, 같은 가로폭).
 * 위 레인은 정적 가이드 곡선(KAN-102), 아래 레인은 녹음 중 자라는 사용자 곡선(KAN-104)이다.
 *
 * 좌표는 [com.accentury.app.recording.guideCurveDisplayPoints]와
 * [com.accentury.app.recording.userCurveDisplayPoints]가 만든 0..1 비율의 선분 목록이고
 * 여기서는 캔버스 크기만 곱한다 -
 * 곡선 처리 규칙은 전부 저쪽(JVM 테스트 가능)에, 여기는 픽셀 변환만 남긴다.
 * 점이 없으면 빈 레인이다: 전부 무성이거나 구버전 웹이 곡선을 안 실어 보낸 경우고,
 * 곡선은 없어도 녹음은 성립하므로 오류 표시 없이 조용히 비워 둔다.
 *
 * ## 두 곡선을 무엇이 가르는가
 *
 * 팔레트가 잉크 한 색이라 색으로는 아무것도 못 가른다 (정본 §7). 대신 셋이 함께 가른다 —
 * 가이드는 얇은 점선, 내 억양은 굵은 실선에 곡선 아래가 망점으로 차 있다. 망점(halftone)은
 * 종이 오리기 인쇄물의 회색 표현이고, 이 앱에서 **화면당 한 곳**만 쓰기로 한 무늬다:
 * 곡선 레인이 그 한 곳이라 다른 컴포넌트에는 망점이 없다.
 *
 * 녹음 화면에서 공용 컴포넌트로 옮겨 왔다 (KAN-105 2단계) — 목소리 점검 화면도 같은 레인에
 * 자기 곡선을 그린다. 점검에서 본 레인과 문항에서 볼 레인이 다르게 생기면, 곡선이 무엇을
 * 뜻하는지 사용자가 두 번 배워야 한다.
 *
 * @param topDivider 위 레인과 나를 가르는 줄을 그릴지. 웹이 `.curve-lane + .curve-lane`로
 *   자동으로 하는 일을 여기서는 첫 레인이 아닌 쪽이 스스로 말한다.
 */
@Composable
internal fun CurveLane(
    label: String,
    segments: List<List<CurvePoint>>,
    variant: CurveLaneVariant,
    topDivider: Boolean = false,
) {
    val colors = MaterialTheme.accenturyColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isUser = variant == CurveLaneVariant.User
    val lineColor = if (isUser) colors.userCurve else colors.guideCurve

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.curveLaneHeight)
            // 레인 사이 구분선. 상자 테두리보다 얇고 흐리다 - 나누는 선이 두르는 선만큼
            // 진하면 레인 둘이 따로 놓인 상자로 보인다
            .drawBehind {
                if (!topDivider) return@drawBehind
                val width = DIVIDER.toPx()
                drawLine(
                    color = muted,
                    start = Offset(0f, width / 2f),
                    end = Offset(size.width, width / 2f),
                    strokeWidth = width,
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(top = Spacing.x4, bottom = Spacing.x1)) {
            val stroke = (if (isUser) USER_STROKE else GUIDE_STROKE).toPx()
            // 점선은 가이드에만 쓴다 - 색이 아니라 선 모양으로 두 곡선을 가르므로
            // 색각 이상에서도 어느 쪽이 내 곡선인지 알 수 있다 (WCAG 1.4.1)
            val effect = if (isUser) {
                null
            } else {
                PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))
            }
            // 선분마다 따로 그린다 - 긴 무성 구간에서 곡선이 끊기므로(KAN-105) 하나로 이으면
            // 쉼 구간을 가로지르는 가짜 사선이 생긴다. 가이드는 선분 하나짜리 목록이다.
            segments.forEach { points ->
                if (points.size >= 2) {
                    val commands = smoothPathCommands(points, size.width, size.height)
                    // 채움을 먼저 그리고 선을 나중에 그린다 - 순서가 바뀌면 망점이 곡선 위를
                    // 덮어 선이 점무늬에 잠긴다.
                    if (isUser) drawHalftone(commands.toPath(closeAtY = size.height), lineColor)
                    drawPath(
                        commands.toPath(),
                        lineColor,
                        style = Stroke(
                            width = stroke,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = effect,
                        ),
                    )
                } else if (points.size == 1) {
                    // 점이 하나뿐인 선분 - 선은 못 그리니 그 시각에 점 하나로 남긴다
                    val p = points.single()
                    drawCircle(lineColor, radius = stroke, center = Offset(p.x * size.width, p.y * size.height))
                }
            }
        }
        // 라벨은 레인 좌상단에 얹는다(시안). 곡선 상단 여백 10% 안쪽이라 겹치지 않는다
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            modifier = Modifier.padding(start = Spacing.x3, top = Spacing.x1),
        )
    }
}

/**
 * 곡선 아래를 망점으로 채운다. 웹은 SVG `<pattern>` 한 줄이면 되지만 Compose에는 무늬
 * 개념이 없어서, 닫은 도형으로 [clipPath]를 걸고 그 안에 격자로 점을 찍는다 — 결과는 같고
 * 점의 간격·크기도 웹과 같은 값이다.
 *
 * 점을 레인 좌표 격자에 찍는 것이 요점이다(웹의 `userSpaceOnUse`와 같다). 곡선을 기준으로
 * 찍으면 곡선이 자랄 때 이미 찍힌 점이 함께 움직여 무늬가 살아 있는 것처럼 보인다.
 */
private fun DrawScope.drawHalftone(area: Path, color: Color) {
    clipPath(area) {
        val step = HALFTONE_STEP.toPx()
        val radius = HALFTONE_DOT.toPx()
        var y = step / 2f
        while (y < size.height) {
            var x = step / 2f
            while (x < size.width) {
                drawCircle(color, radius = radius, center = Offset(x, y), alpha = HALFTONE_ALPHA)
                x += step
            }
            y += step
        }
    }
}

/**
 * 곡선 명령을 Compose [Path]로 재생한다.
 *
 * 기하 계산은 [smoothPathCommands]가 하고 여기는 옮겨 담기만 한다 - `Path`는 되읽을 수 없어
 * JVM 테스트로 검사할 수 없으므로, 검사할 것은 전부 명령 목록 쪽에 둔다
 * (인과성 근거는 [smoothPathCommands] KDoc).
 *
 * @param closeAtY 주면 곡선 끝에서 이 높이로 내려가고 시작점 아래까지 간 뒤 닫는다 —
 *   망점을 채울 면이다. 선분이 레인 폭 전체를 쓰지 않아도(녹음이 짧으면 왼쪽만 차 있다)
 *   채운 면이 곡선 밑에만 남는다.
 */
private fun List<PathCommand>.toPath(closeAtY: Float? = null): Path {
    val path = Path()
    var firstX = 0f
    var lastX = 0f
    forEachIndexed { index, command ->
        // 끝점의 x는 명령마다 자리가 달라(QuadTo는 제어점이 앞에 온다) 분기 안에서 꺼낸다
        val x = when (command) {
            is PathCommand.MoveTo -> { path.moveTo(command.x, command.y); command.x }
            is PathCommand.LineTo -> { path.lineTo(command.x, command.y); command.x }
            is PathCommand.QuadTo -> {
                path.quadraticTo(command.cx, command.cy, command.x, command.y)
                command.x
            }
        }
        if (index == 0) firstX = x
        lastX = x
    }
    if (closeAtY != null) {
        path.lineTo(lastX, closeAtY)
        path.lineTo(firstX, closeAtY)
        path.close()
    }
    return path
}

/** 곡선 굵기. 가이드는 얇고 내 억양은 굵다 - 웹의 2px/3px와 같다 */
private val GUIDE_STROKE = 2.dp
private val USER_STROKE = 3.dp

/** 점선 패턴. 가이드에만 쓴다. 웹의 `stroke-dasharray="6 5"`와 같다 */
private val DASH_ON = 6.dp
private val DASH_OFF = 5.dp

/** 망점 한 칸의 크기와 점 반지름. 웹 `<pattern>`의 5×5·r=1과 같다 */
private val HALFTONE_STEP = 5.dp
private val HALFTONE_DOT = 1.dp

/** 망점의 진하기. 1이면 곡선 아래가 잉크 면이 되어 곡선 자체가 안 보인다 */
private const val HALFTONE_ALPHA = 0.5f

private val GROUP_BORDER = 1.5.dp
private val DIVIDER = 1.dp
