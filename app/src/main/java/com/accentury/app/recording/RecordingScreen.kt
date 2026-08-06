package com.accentury.app.recording

import android.annotation.SuppressLint
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accentury.app.audio.QualityStatus
import com.accentury.app.audio.RecordingEngine

@SuppressLint("MissingPermission") // 이 화면은 권한 보유 상태에서만 열린다 (KAN-11 게이트)
@Composable
fun RecordingScreen(
    questionText: String,
    questionIndex: Int,
    totalQuestions: Int,
    onNext: (attemptId: String, durationMs: Long) -> Unit,
    onExit: () -> Unit,
    viewModel: RecordingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
        CurveLane(label = "가이드")
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
                                onNext(s.attemptId, s.durationMs)
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

@Composable
private fun CurveLane(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .border(1.dp, Color.Gray),
        contentAlignment = Alignment.Center,
    ) {
        Text("$label 곡선 (KAN-54)", fontSize = 12.sp)
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
