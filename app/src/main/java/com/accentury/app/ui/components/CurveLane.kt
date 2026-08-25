package com.accentury.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.accentury.app.recording.CurvePoint
import com.accentury.app.recording.PathCommand
import com.accentury.app.recording.smoothPathCommands
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.accenturyColors

/**
 * 곡선 캔버스의 레인 하나 (ux-ui.md §D — 위/아래 2단, 같은 가로폭·같은 시간축).
 * 위 레인은 정적 가이드 곡선(KAN-102), 아래 레인은 녹음 중 자라는 사용자 곡선(KAN-104)이다.
 *
 * 좌표는 [com.accentury.app.recording.guideCurveDisplayPoints]와
 * [com.accentury.app.recording.userCurveDisplayPoints]가 만든 0..1 비율의 선분 목록이고
 * 여기서는 캔버스 크기만 곱한다 -
 * 곡선 처리 규칙은 전부 저쪽(JVM 테스트 가능)에, 여기는 픽셀 변환만 남긴다.
 * 점이 없으면 빈 레인이다: 전부 무성이거나 구버전 웹이 곡선을 안 실어 보낸 경우고,
 * 곡선은 없어도 녹음은 성립하므로 오류 표시 없이 조용히 비워 둔다.
 *
 * 녹음 화면에서 공용 컴포넌트로 옮겨 왔다 (KAN-105 2단계) — 목소리 점검 화면도 같은 레인에
 * 자기 곡선을 그린다. 점검에서 본 레인과 문항에서 볼 레인이 다르게 생기면, 곡선이 무엇을
 * 뜻하는지 사용자가 두 번 배워야 한다.
 */
@Composable
internal fun CurveLane(
    label: String,
    segments: List<List<CurvePoint>>,
    lineColor: Color,
    dashed: Boolean,
) {
    val colors = MaterialTheme.accenturyColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.curveLaneHeight)
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.curveLaneSurface)
            .border(1.dp, colors.curveLaneBorder, RoundedCornerShape(Radius.md)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(top = Spacing.x4, bottom = Spacing.x1)) {
            val stroke = CURVE_STROKE.toPx()
            // 점선은 가이드에만 쓴다 - 색이 아니라 선 모양으로 두 곡선을 가르므로
            // 색각 이상에서도 어느 쪽이 내 곡선인지 알 수 있다 (WCAG 1.4.1)
            val effect = if (dashed) {
                PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))
            } else {
                null
            }
            // 선분마다 따로 그린다 - 긴 무성 구간에서 곡선이 끊기므로(KAN-105) 하나로 이으면
            // 쉼 구간을 가로지르는 가짜 사선이 생긴다. 가이드는 선분 하나짜리 목록이다.
            segments.forEach { points ->
                if (points.size >= 2) {
                    val path = smoothPathCommands(points, size.width, size.height).toPath()
                    drawPath(
                        path,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.x3, top = Spacing.x1),
        )
    }
}

/**
 * 곡선 명령을 Compose [Path]로 재생한다.
 *
 * 기하 계산은 [smoothPathCommands]가 하고 여기는 옮겨 담기만 한다 - `Path`는 되읽을 수 없어
 * JVM 테스트로 검사할 수 없으므로, 검사할 것은 전부 명령 목록 쪽에 둔다
 * (인과성 근거는 [smoothPathCommands] KDoc).
 */
private fun List<PathCommand>.toPath(): Path {
    val path = Path()
    forEach { command ->
        when (command) {
            is PathCommand.MoveTo -> path.moveTo(command.x, command.y)
            is PathCommand.LineTo -> path.lineTo(command.x, command.y)
            is PathCommand.QuadTo -> path.quadraticTo(command.cx, command.cy, command.x, command.y)
        }
    }
    return path
}

private val CURVE_STROKE = 2.dp
private val DASH_ON = 5.dp
private val DASH_OFF = 3.dp
