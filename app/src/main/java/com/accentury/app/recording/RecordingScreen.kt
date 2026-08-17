package com.accentury.app.recording

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accentury.app.audio.QualityStatus
import com.accentury.app.audio.RecordingEngine
import com.accentury.app.bridge.GuideF0

@SuppressLint("MissingPermission") // 이 화면은 권한 보유 상태에서만 열린다 (KAN-11 게이트)
@Composable
fun RecordingScreen(
    questionText: String,
    questionIndex: Int,
    totalQuestions: Int,
    // quality는 Review 상태에만 있고 뷰모델은 넘어가는 즉시 reset되므로, 호출자가 나중에 되물을 수 없다.
    // 브리지 계약(KAN-89)이 qualityStatus를 요구해서 여기서 함께 넘긴다.
    onNext: (attemptId: String, durationMs: Long, quality: QualityStatus) -> Unit,
    onExit: () -> Unit,
    // 상단 레인의 정적 가이드 곡선 (KAN-102). null은 안 실어 보낸 구버전 웹 - 레인만 비운다.
    guideF0: GuideF0? = null,
    viewModel: RecordingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 문항이 사는 동안 곡선 데이터는 정적이다 - 좌표 계산은 마운트당 한 번이면 된다.
    val guidePoints = remember(guideF0) { guideCurveDisplayPoints(guideF0?.values ?: emptyList()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    viewModel.reset() // 이탈 즉시 녹음 중단·마이크 해제·PCM 폐기 (FR-DP-02)
                    onExit()
                },
            ) { Text("나가기") }
            Spacer(modifier = Modifier.weight(1f))
            Text("$questionIndex / $totalQuestions")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(questionText, fontSize = 24.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text("평소 말하듯 자연스럽게 읽어주세요", fontSize = 14.sp)

        Spacer(modifier = Modifier.height(24.dp))
        CurveLane(label = "가이드", points = guidePoints, lineColor = GuideCurveColor)
        Spacer(modifier = Modifier.height(8.dp))
        CurveLane(label = "내 억양")

        Spacer(modifier = Modifier.weight(1f))

        when (val s = state) {
            is RecordingUiState.Idle -> {
                RecordButton(text = "● 녹음", onClick = viewModel::startRecording)
            }

            is RecordingUiState.Recording -> {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatElapsed(s.elapsedMs) + " / 최대 10초")
                    Text("입력 레벨(RMS): ${s.rms.toInt()}", fontSize = 12.sp) // 개발용 — 오디오 경로 진단
                    if (s.countdownActive) {
                        Text("곧 자동 종료됩니다 (${(RecordingEngine.MAX_DURATION_MS - s.elapsedMs) / 1000 + 1}초)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    RecordButton(text = "■ 정지", onClick = viewModel::stopRecording)
                }
            }

            is RecordingUiState.Review -> {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (s.autoStopped) Text("10초가 지나 자동으로 종료됐어요")
                    Text(qualityMessage(s.quality))
                    Text("녹음 길이 ${"%.1f".format(s.durationMs / 1000.0)}초")
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = viewModel::retryRecording) { Text("재녹음") }
                        Button(
                            enabled = s.canProceed,
                            onClick = {
                                onNext(s.attemptId, s.durationMs, s.quality)
                                viewModel.reset()
                            },
                        ) { Text("다음") }
                    }
                }
            }

            is RecordingUiState.Failed -> {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("녹음에 실패했어요 — ${s.reason}")
                    Spacer(modifier = Modifier.height(8.dp))
                    RecordButton(text = "다시 시도", onClick = viewModel::retryRecording)
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

/**
 * 가이드 곡선의 연한 색 (ux-ui.md §D). 시그니처 색은 사용자 곡선 몫이라, 가이드는 힌트일 뿐
 * 주인공이 아니라는 위계를 색 무게로 표현한다. 확정 팔레트 전이라 무디자인 톤의 임시값이다.
 */
private val GuideCurveColor = Color(0xFFB0C4DE)

/**
 * 곡선 캔버스의 레인 하나 (ux-ui.md §D — 위/아래 2단, 같은 가로폭·같은 시간축).
 * 위 레인은 정적 가이드 곡선(KAN-102), 아래 레인은 사용자 곡선 자리다(후속 티켓).
 *
 * 좌표는 [guideCurveDisplayPoints]가 만든 0..1 비율이고 여기서는 캔버스 크기만 곱한다 —
 * 곡선 처리 규칙은 전부 저쪽(JVM 테스트 가능)에, 여기는 픽셀 변환만 남긴다.
 * 점이 없으면 빈 레인이다: 전부 무성이거나 구버전 웹이 곡선을 안 실어 보낸 경우고,
 * 곡선은 없어도 녹음은 성립하므로 오류 표시 없이 조용히 비워 둔다.
 */
@Composable
private fun CurveLane(label: String, points: List<CurvePoint> = emptyList(), lineColor: Color = Color.Gray) {
    Box(
        // 240dp 자리표시자에서 축소 - 레인 둘에 대사·버튼까지 한 화면에 서야 한다 (ux-ui.md §D)
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .border(1.dp, Color.Gray),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            if (points.size >= 2) {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = p.x * size.width
                    val y = p.y * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else if (points.size == 1) {
                // 유성 프레임이 하나뿐인 극단 - 선은 못 그리니 그 시각에 점 하나로 남긴다
                val p = points.single()
                drawCircle(lineColor, radius = stroke, center = Offset(p.x * size.width, p.y * size.height))
            }
        }
        Text(
            label,
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
    }
}

@Composable
private fun RecordButton(text: String, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(onClick = onClick, modifier = Modifier.size(width = 160.dp, height = 72.dp)) {
            Text(text, fontSize = 18.sp)
        }
    }
}

private fun formatElapsed(elapsedMs: Long): String {
    val seconds = elapsedMs / 1000
    return "00:%02d".format(seconds)
}

private fun qualityMessage(quality: QualityStatus): String = when (quality) {
    QualityStatus.NORMAL -> "녹음 상태가 좋아요"
    QualityStatus.TOO_SHORT -> "발화가 너무 짧아요 — 조금 더 길게 말해주세요"
    QualityStatus.TOO_QUIET -> "소리가 너무 작아요 — 조금 더 크게 말해주세요"
    QualityStatus.CLIPPED -> "소리가 튀었어요 — 마이크에서 조금 떨어져 주세요"
}
