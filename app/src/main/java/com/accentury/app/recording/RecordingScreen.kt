package com.accentury.app.recording

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.sp
import com.accentury.app.ui.components.AccenturyButton
import com.accentury.app.ui.components.ButtonVariant
import com.accentury.app.ui.components.ProgressIndicator
import com.accentury.app.ui.components.PromptCard
import com.accentury.app.ui.components.RecordButton
import com.accentury.app.ui.components.StatusBlock
import com.accentury.app.ui.components.StatusTone
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.accenturyColors
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
    // 상단 레인의 정적 가이드 곡선 (KAN-102). null은 안 실어 보낸 구버전 웹 - 레인만 비운다.
    guideF0: GuideF0? = null,
    /*
     * 제출한 시도의 결과가 웹에 닿기를 기다리는 중인가 (KAN-146).
     * 화면을 갈아끼우지 않고 이 화면 안에서 아래쪽만 바꾼다 - 문항 문구도 곡선도 제자리에 남아,
     * [다음]을 누른 뒤 다음 문항이 뜰 때까지가 한 화면의 상태 변화로 읽힌다.
     */
    submitting: Boolean = false,
    /*
     * 서버가 이 녹음을 거절해서 화면이 스스로 다시 열린 경우인가 (KAN-147).
     * 사용자가 [다음]을 누르고 웹으로 돌아간 뒤에 벌어지는 일이라, 이유를 한 줄 적어두지 않으면
     * 녹음 화면이 까닭 없이 되돌아온 것으로 보인다.
     */
    afterUploadFailure: Boolean = false,
    /*
     * 그 거절에서 서버가 준 문구 (KAN-147). 녹음이 왜 거절됐는지(너무 길다, 너무 작다)는 서버만
     * 아는 것이라 그대로 보여준다 - 앱이 지어낸 일반 문구로 덮으면 사용자가 같은 실패를 반복한다.
     * null이면 아래 기본 안내를 쓴다.
     */
    failureMessage: String? = null,
    viewModel: RecordingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 문항이 사는 동안 곡선 데이터는 정적이다 - 좌표 계산은 마운트당 한 번이면 된다.
    // unit 가드: "0은 무성이 아니다" 규칙(GuideCurve)은 semitone에서만 참이다. 모르는 단위는
    // 자기 스케일 덕에 그럴듯하게 그려지면서 무성 판정만 조용히 틀리므로, 안 그리는 쪽을 택한다.
    val guidePoints = remember(guideF0) {
        if (guideF0?.unit == "semitone") guideCurveDisplayPoints(guideF0.values) else emptyList()
    }
    // 창 길이에는 unit 가드를 걸지 않는다. 위 가드는 "값을 어떻게 읽을 것인가"의 문제라
    // 단위를 모르면 그릴 수 없지만, 길이는 간격 x 구간 수라서 단위와 무관하게 맞는다.
    // 그래서 가이드를 못 그리는 경우에도 두 레인의 시간축은 여전히 같게 잡을 수 있다.
    val windowMs = remember(guideF0) {
        userCurveWindowMs(guideF0?.frameIntervalMs, guideF0?.values?.size)
    }
    // 녹음 중에는 자라는 곡선, 완료 후에는 방금 녹음의 곡선을 남긴다 (2026-08-18 결정).
    // 재녹음을 시작하면 Recording의 빈 목록으로 바뀌므로 지난 곡선이 새 녹음에 섞이지 않는다.
    val pitchFrames = when (val s = state) {
        is RecordingUiState.Recording -> s.pitchFrames
        is RecordingUiState.Review -> s.pitchFrames
        else -> emptyList()
    }
    // 프레임이 청크마다 늘어나므로 remember로 묶지 않는다 - 어차피 매 방출마다 다시 계산해야 한다.
    val myPoints = userCurveDisplayPoints(pitchFrames, windowMs)

    Column(modifier = Modifier.fillMaxSize().padding(Spacing.x4)) {
        /*
         * 웹 진행바와 같은 컴포넌트, 같은 값, 같은 폭이다 - 웹은 음성 문항 화면 맨 위에서 진행바를
         * 폭 전체로 그린다(.progress-indicator { width: 100% }). 문항이 두 런타임을 오가므로
         * 막대 길이나 표기가 달라지면 사용자에게는 진행이 튄 것처럼 보인다.
         * ProgressIndicator가 이미 막대와 "3 / 10"을 한 줄에 눕히는 Row라 따로 감싸지 않는다.
         */
        ProgressIndicator(
            current = questionIndex,
            total = totalQuestions,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Spacing.x4))
        /*
         * 대사 카드. 웹 음성 문항 화면의 카드와 같은 규격이라 전환에서 카드가 튀지 않는다.
         * headlineMedium(26sp)이 ux-ui.md §5의 "대사 카드 24sp 이상"을 지킨다.
         */
        PromptCard(
            badge = "🎤 음성 문항",
            prompt = questionText,
            supporting = "평소 말하듯 자연스럽게 읽어주세요",
        )

        Spacer(modifier = Modifier.height(Spacing.x4))
        CurveCard(guidePoints = guidePoints, userPoints = myPoints)

        Spacer(modifier = Modifier.weight(1f))

        if (submitting) {
            /*
             * 결과를 기다리는 동안의 하단. 버튼 자리를 문구 하나로 바꿔 "눌린 건 알아들었고 지금
             * 처리 중"만 알린다 - 진행률이나 취소를 주지 않는 이유는 이 구간이 보통 1초 안쪽이고
             * (상한도 호출자가 건다) 여기서 되돌릴 수 있는 것이 없기 때문이다.
             */
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("제출 중…")
            }
        } else when (val s = state) {
            is RecordingUiState.Idle -> {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordButton(contentDescription = "녹음 시작", onClick = viewModel::startRecording)
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    Text(
                        if (afterUploadFailure) {
                            failureMessage ?: "업로드에 실패해서 다시 녹음이 필요해요"
                        } else {
                            "버튼을 눌러 녹음"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is RecordingUiState.Recording -> {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatElapsed(s.elapsedMs) + " / 최대 10초",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // 개발용 — 오디오 경로 진단
                    Text("입력 레벨(RMS): ${s.rms.toInt()}", style = MaterialTheme.typography.labelSmall)
                    if (s.countdownActive) {
                        Text(
                            "곧 자동 종료됩니다 (${(RecordingEngine.MAX_DURATION_MS - s.elapsedMs) / 1000 + 1}초)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    RecordButton(
                        contentDescription = "녹음 정지",
                        onClick = viewModel::stopRecording,
                        recording = true,
                    )
                }
            }

            is RecordingUiState.Review -> {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (s.autoStopped) {
                        Text(
                            "10초가 지나 자동으로 종료됐어요",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(qualityMessage(s.quality), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "녹음 길이 ${"%.1f".format(s.durationMs / 1000.0)}초",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                        AccenturyButton(
                            text = "재녹음",
                            variant = ButtonVariant.Secondary,
                            onClick = viewModel::retryRecording,
                        )
                        AccenturyButton(
                            text = "다음",
                            enabled = s.canProceed,
                            /*
                             * 되감기(reset)를 여기서 부르지 않는다 (KAN-146). [다음] 뒤에도 이 화면은
                             * 결과가 나갈 때까지 제출 중 상태로 남으므로, 이 자리에서 되감으면 방금 그린
                             * '내 억양' 곡선이 그 구간에서 사라진다. 되감기는 화면이 걷힌 뒤 호출자
                             * (MainActivity)가 한다. onNext 안의 consumeRecording이 PCM을 이미
                             * 가져가므로(FR-DP-02) 되감기가 늦어져도 음성 바이트가 남지는 않는다.
                             */
                            onClick = { onNext(s.attemptId, s.durationMs, s.quality) },
                        )
                    }
                }
            }

            is RecordingUiState.Failed -> {
                StatusBlock(
                    tone = StatusTone.Error,
                    message = "녹음에 실패했어요",
                    detail = s.reason,
                    action = {
                        AccenturyButton(text = "다시 시도", onClick = viewModel::retryRecording)
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.x8))
    }
}

/**
 * 곡선 캔버스의 레인 하나 (ux-ui.md §D — 위/아래 2단, 같은 가로폭·같은 시간축).
 * 위 레인은 정적 가이드 곡선(KAN-102), 아래 레인은 녹음 중 자라는 사용자 곡선(KAN-104)이다.
 *
 * 좌표는 [guideCurveDisplayPoints]와 [userCurveDisplayPoints]가 만든 0..1 비율이고
 * 여기서는 캔버스 크기만 곱한다 -
 * 곡선 처리 규칙은 전부 저쪽(JVM 테스트 가능)에, 여기는 픽셀 변환만 남긴다.
 * 점이 없으면 빈 레인이다: 전부 무성이거나 구버전 웹이 곡선을 안 실어 보낸 경우고,
 * 곡선은 없어도 녹음은 성립하므로 오류 표시 없이 조용히 비워 둔다.
 */
@Composable
private fun CurveLane(
    label: String,
    points: List<CurvePoint>,
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
            if (points.size >= 2) {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = p.x * size.width
                    val y = p.y * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
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
                // 유성 프레임이 하나뿐인 극단 - 선은 못 그리니 그 시각에 점 하나로 남긴다
                val p = points.single()
                drawCircle(lineColor, radius = stroke, center = Offset(p.x * size.width, p.y * size.height))
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

private val CURVE_STROKE = 2.dp
private val DASH_ON = 5.dp
private val DASH_OFF = 3.dp

/**
 * 곡선 두 레인을 감싸는 카드 (시안). 레인을 카드에 넣는 이유는 곡선이 "화면에 그려진 선"이
 * 아니라 "지금 보고 있는 자료"로 읽히게 하기 위해서다 - 대사 카드와 나란히 놓이면 두 덩어리가
 * 화면의 위아래를 나눈다.
 */
@Composable
private fun CurveCard(guidePoints: List<CurvePoint>, userPoints: List<CurvePoint>) {
    val colors = MaterialTheme.accenturyColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.xl))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.curveLaneBorder, RoundedCornerShape(Radius.xl))
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text("억양 곡선", style = MaterialTheme.typography.labelLarge)
        CurveLane(label = "가이드", points = guidePoints, lineColor = colors.guideCurve, dashed = true)
        CurveLane(label = "내 억양", points = userPoints, lineColor = colors.userCurve, dashed = false)
    }
}

/** 곡선 레인 하나의 높이. 레인 둘에 대사·버튼까지 한 화면에 서야 한다 (ux-ui.md §D) */
private val CURVE_LANE_HEIGHT = 120.dp

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
