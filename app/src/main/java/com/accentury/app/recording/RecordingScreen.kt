package com.accentury.app.recording

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.accentury.app.ui.components.AccenturyButton
import com.accentury.app.ui.components.ButtonVariant
import com.accentury.app.ui.components.CurveLane
import com.accentury.app.ui.components.CurveLaneGroup
import com.accentury.app.ui.components.CurveLaneVariant
import com.accentury.app.ui.components.ProgressIndicator
import com.accentury.app.ui.components.PromptCard
import com.accentury.app.ui.components.RecordButton
import com.accentury.app.ui.components.StatusBlock
import com.accentury.app.ui.components.StatusTone
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Motion
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.motionDuration
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
     * 사용자 곡선 y축의 중심 음높이 (KAN-105). 목소리 점검 화면이 미리 잰 값을 넘긴다.
     * null이면 이 녹음의 첫 유성 프레임들로 직접 잡는다([userCurveCenterHz]) - 그 전까지는
     * 곡선을 그리지 않는다. 결선은 KAN-105 2단계고, 지금은 기본값이라 호출부가 안 바뀐다.
     */
    centerHz: Float? = null,
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
    // 창 길이에는 unit 가드를 걸지 않는다. 아래 가드는 "값을 어떻게 읽을 것인가"의 문제라
    // 단위를 모르면 그릴 수 없지만, 길이는 간격 x 구간 수라서 단위와 무관하게 맞는다.
    // 그래서 가이드를 못 그리는 경우에도 두 레인의 시간축은 여전히 같게 잡을 수 있다.
    val liveWindowMs = remember(guideF0) {
        userCurveWindowMs(guideF0?.frameIntervalMs, guideF0?.values?.size)
    }
    // 녹음 중에는 자라는 곡선, 완료 후에는 방금 녹음의 곡선을 남긴다 (2026-08-18 결정).
    // 재녹음을 시작하면 Recording의 빈 목록으로 바뀌므로 지난 곡선이 새 녹음에 섞이지 않는다.
    val pitchFrames = when (val s = state) {
        is RecordingUiState.Recording -> s.pitchFrames
        // Review에서만 짧은 무성 구멍을 메운다. 녹음 중에는 곡선이 인과적이어야 해서(뒤 프레임을
        // 보면 이미 그린 과거가 다시 그려진다) 구멍을 앞 값으로 유지하는 수밖에 없지만, 완료 후에는
        // 데이터가 다 모여 있어 구멍의 양옆을 보고 이어도 거짓이 아니다 - fillShortGaps KDoc 참고.
        is RecordingUiState.Review -> fillShortGaps(s.pitchFrames)
        else -> emptyList()
    }
    // 이 창은 사용자 레인만 쓴다. 녹음 중에는 최신 구간이 오른쪽 끝에 붙어야 하니 미끄러지는
    // 라이브 창을 그대로 쓰고, 녹음이 끝난 Review에서는 발화 전체가 들어오게 창을 늘린다
    // - reviewWindowMs KDoc 참고.
    val windowMs = when (state) {
        is RecordingUiState.Review -> reviewWindowMs(pitchFrames, liveWindowMs)
        else -> liveWindowMs
    }
    // 가이드는 사용자 창과 무관하게 항상 자기 길이로 레인 폭 전체를 쓴다 (2026-08-25 결정,
    // KAN-104의 원래 모양으로 되돌림). 가이드 레인은 "정답 억양이 어떤 모양인가"를 보여 주는
    // 그림이라 레인을 꽉 채워야 오르내림이 읽힌다. 사용자 창(가이드의 2배, Review는 녹음 전체
    // 길이)에 맞춰 축소하면 발화가 길수록 가이드가 왼쪽 구석에 작게 눌려, 정작 비교하라고 놓은
    // 곡선이 더 안 보였다. 대가로 두 레인의 시간축이 달라지는 것은 감수한다 - 두 레인은 이제
    // 같은 시각을 맞춰 보는 도구가 아니라 모양을 견주는 도구다.
    val guidePoints = remember(guideF0) {
        if (guideF0?.unit != "semitone") emptyList() else guideCurveDisplayPoints(guideF0.values)
    }
    // 프레임이 청크마다 늘어나므로 remember로 묶지 않는다 - 어차피 매 방출마다 다시 계산해야 한다.
    val mySegments = userCurveDisplayPoints(pitchFrames, windowMs, centerHz)

    /*
     * 화면 틀 (아트보드 ②). 위 64 · 좌우 24 · 아래 32다 - 위만 8의 배수 밖에 서는 이유는
     * 배치이기 때문이고(정본 §4), 웹 4화면의 `--screen-padding-top`과 같은 값이라 문항이 두
     * 런타임을 오가도 첫 요소가 같은 높이에서 시작한다.
     */
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = Dimens.screenPaddingTop,
                start = Spacing.x6,
                end = Spacing.x6,
                bottom = Spacing.x8,
            ),
    ) {
        /*
         * 본문은 스크롤하고 하단(타이머·녹음 버튼)은 고정이다 (아트보드의 `screen()` 틀).
         * 글꼴을 200%로 키운 기기에서 대사 카드와 곡선이 자라면 녹음 버튼이 화면 밖으로 밀리는데,
         * 이 화면에서 버튼이 안 보이는 것은 곧 녹음을 못 하는 것이다 - 밀려야 할 쪽은 본문이다.
         */
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            /*
             * 웹 진행바와 같은 컴포넌트, 같은 값, 같은 폭이다 - 웹은 음성 문항 화면 맨 위에서 진행바를
             * 폭 전체로 그린다(.progress-indicator { width: 100% }). 문항이 두 런타임을 오가므로
             * 막대 길이나 표기가 달라지면 사용자에게는 진행이 튄 것처럼 보인다.
             *
             * [note]가 "음성"인 것도 같은 이유다 - 웹 캡션이 "3 / 10 · 음성"이라, 여기서만 종류를
             * 빼면 같은 자리의 같은 줄이 화면을 넘어갈 때마다 길어졌다 짧아진다.
             */
            ProgressIndicator(
                current = questionIndex,
                total = totalQuestions,
                note = "음성",
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(Spacing.x6))
            /*
             * 대사 카드. 배지·이모지·부연을 걷고 캡션 한 줄 + 대사만 남겼다 (아트보드 ②).
             * "평소 말하듯 자연스럽게 읽어주세요"가 사라진 것은 문구를 줄이려는 게 아니라 자리를
             * 옮긴 것이다 - 카드는 읽을 문장을 내밀고, 어떻게 하라는 말은 버튼 밑 캡션이 한다.
             */
            PromptCard(
                caption = promptCaption(questionIndex, totalQuestions),
                prompt = questionText,
            )

            Spacer(modifier = Modifier.height(Spacing.x6))
            CurveCard(guidePoints = guidePoints, userSegments = mySegments)
        }

        // 하단 고정. 아트보드의 footer는 본문과 16만큼 떨어진다
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.x4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (submitting) {
                /*
                 * 결과를 기다리는 동안의 하단. 버튼 자리를 문구 하나로 바꿔 "눌린 건 알아들었고 지금
                 * 처리 중"만 알린다 - 진행률이나 취소를 주지 않는 이유는 이 구간이 보통 1초 안쪽이고
                 * (상한도 호출자가 건다) 여기서 되돌릴 수 있는 것이 없기 때문이다.
                 */
                Text("제출 중…", style = MaterialTheme.typography.bodyLarge)
            } else when (val s = state) {
                is RecordingUiState.Idle -> IdleControls(
                    hint = if (afterUploadFailure) {
                        failureMessage ?: "업로드에 실패해서 다시 녹음이 필요해요"
                    } else {
                        "버튼을 눌러 녹음"
                    },
                    onStart = viewModel::startRecording,
                )

                is RecordingUiState.Recording -> RecordingControls(
                    elapsedMs = s.elapsedMs,
                    warning = s.countdownActive,
                    onStop = viewModel::stopRecording,
                )

                is RecordingUiState.Review -> ReviewControls(
                    state = s,
                    onRetry = viewModel::retryRecording,
                    onNext = { onNext(s.attemptId, s.durationMs, s.quality) },
                )

                is RecordingUiState.Failed -> StatusBlock(
                    tone = StatusTone.Error,
                    message = "녹음에 실패했어요",
                    detail = s.reason,
                    action = {
                        AccenturyButton(text = "다시 시도", onClick = viewModel::retryRecording)
                    },
                )
            }
        }
    }
}

/**
 * 대사 카드 위 캡션. 아트보드는 "3 / 10 · 이 문장을 읽어주세요"다 — 진행 도트 아래 캡션이
 * 이미 같은 숫자를 말하지만 두 줄이 화면 위아래로 떨어져 있어, 대사 바로 위에 한 번 더
 * 있는 편이 "지금 읽을 것은 이것"으로 읽힌다.
 *
 * 번호를 모르는 경우(구버전 웹이 문항 수를 안 실어 보냈다)에는 안내만 남긴다. `0 / 0 ·`으로
 * 시작하는 줄은 숫자가 있는 것보다 나쁘다 — 사용자가 자기가 몇 번째인지 잘못 읽는다.
 */
internal fun promptCaption(questionIndex: Int, totalQuestions: Int): String =
    if (questionIndex > 0 && totalQuestions > 0) {
        "$questionIndex / $totalQuestions · 이 문장을 읽어주세요"
    } else {
        "이 문장을 읽어주세요"
    }

/** 대기. 누를 것 하나와 그 아래 캡션 한 줄이다 (아트보드 ②) */
@Composable
private fun IdleControls(hint: String, onStart: () -> Unit) {
    RecordButton(contentDescription = "녹음 시작", onClick = onStart)
    Spacer(modifier = Modifier.height(Spacing.x2))
    Caption(hint)
}

/**
 * 녹음 중 (아트보드 ②·②b). 위에서부터 타이머 → 버튼 → 캡션이고, 마지막 2초에는 타이머 자리가
 * 잉크 캡슐로, 캡션이 자동 종료 안내로 바뀐다.
 *
 * 같은 숫자를 다르게 그리는 것이 아니라 **다른 것을 말한다** — 위 표기는 "얼마나 읽었나",
 * 캡슐은 "곧 끊긴다"다. 그래서 문장을 맺어야 하는 순간에만 나타나고, 나타났다는 사실 자체가
 * 신호가 된다. 팔레트에 빨강이 없어 위급함을 색으로 말할 수 없는데(정본 §7), 애초에 색보다
 * 문구와 등장이 강하다.
 */
@Composable
private fun RecordingControls(elapsedMs: Long, warning: Boolean, onStop: () -> Unit) {
    val enter = tween<Float>(
        durationMillis = motionDuration(Motion.BASE),
        easing = Motion.easeOut,
    )

    /*
     * 타이머와 캡슐이 같은 자리를 나눠 쓴다. 높이를 캡슐 크기로 고정해 두는 이유는 둘의 높이가
     * 달라서다(글자 한 줄 vs 32dp 알약) - 자리를 안 잡아 두면 경고가 뜨는 순간 아래 녹음 버튼이
     * 14dp 내려앉는다. 사용자는 그때 버튼을 누르려던 참이다.
     */
    Box(
        modifier = Modifier.height(COUNTDOWN_CAPSULE_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = !warning, enter = fadeIn(enter), exit = fadeOut(enter)) {
            ElapsedTimer(elapsedMs)
        }
        AnimatedVisibility(
            visible = warning,
            // 살짝 커지며 나타난다 - 자리에 원래 있던 것이 바뀐 게 아니라 새로 놓였다는 뜻이다
            enter = fadeIn(enter) + scaleIn(enter, initialScale = 0.92f),
            exit = fadeOut(enter),
        ) {
            CountdownCapsule(remainingSeconds(elapsedMs, RecordingEngine.MAX_DURATION_MS))
        }
    }

    Spacer(modifier = Modifier.height(Spacing.x2))
    RecordButton(contentDescription = "녹음 정지", onClick = onStop, recording = true)
    Spacer(modifier = Modifier.height(Spacing.x2))
    // 자동 종료를 미리 알린다 — 갑자기 멈추면 사용자는 자기가 뭘 잘못 눌렀다고 생각한다
    Caption(if (warning) "10초가 되면 자동으로 멈춰요" else "녹음 중 · 탭해서 멈추기")
}

/**
 * `00:04 / 10초`. 뒤쪽 상한만 흐린 잉크다 — 앞의 두 자리가 초마다 바뀌는 값이고 뒤는 고정이라,
 * 같은 무게로 적으면 어느 쪽이 지금인지 한 번에 안 갈린다.
 */
@Composable
private fun ElapsedTimer(elapsedMs: Long) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            append(formatElapsed(elapsedMs))
            withStyle(SpanStyle(color = muted)) {
                append(" / ${RecordingEngine.MAX_DURATION_MS / 1000}초")
            }
        },
        style = MaterialTheme.typography.titleSmall,
    )
}

/**
 * 8초 경고 캡슐 (아트보드 ②b). 잉크로 채운 알약이라 화면에서 주 버튼 다음으로 눈에 띈다.
 *
 * `liveRegion = Polite`로 화면을 안 보는 사용자에게도 남은 시간이 닿는다. Assertive가 아닌
 * 이유는 매초 바뀌는 값이라, 끼어드는 쪽으로 두면 사용자가 지금 소리 내어 읽고 있는 대사를
 * 스크린 리더가 가로챈다.
 */
@Composable
private fun CountdownCapsule(seconds: Int) {
    Box(
        modifier = Modifier
            .height(COUNTDOWN_CAPSULE_HEIGHT)
            .clip(RoundedCornerShape(Radius.full))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = COUNTDOWN_CAPSULE_PADDING)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${seconds}초 남음",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** 정지 뒤의 확인. 판정 한 줄, 길이 한 줄, 그리고 갈림길 둘 */
@Composable
private fun ReviewControls(
    state: RecordingUiState.Review,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    if (state.autoStopped) {
        Caption("10초가 지나 자동으로 종료됐어요")
    }
    Text(qualityMessage(state.quality), style = MaterialTheme.typography.bodyLarge)
    Caption("녹음 길이 ${"%.1f".format(state.durationMs / 1000.0)}초")

    Spacer(modifier = Modifier.height(Spacing.x4))
    /*
     * 둘이 같은 폭을 갖는다 (웹 `.record-actions`와 같은 규칙). 무게는 이미 변형이 가르므로
     * (보조는 그림자 없는 크림, 주는 잉크 면) 폭까지 다르면 보조 동작이 눌리지 않을 만큼 작아진다.
     */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        AccenturyButton(
            text = "재녹음",
            variant = ButtonVariant.Secondary,
            onClick = onRetry,
            modifier = Modifier.weight(1f),
        )
        AccenturyButton(
            text = "다음",
            enabled = state.canProceed,
            /*
             * 되감기(reset)를 여기서 부르지 않는다 (KAN-146). [다음] 뒤에도 이 화면은
             * 결과가 나갈 때까지 제출 중 상태로 남으므로, 이 자리에서 되감으면 방금 그린
             * '내 억양' 곡선이 그 구간에서 사라진다. 되감기는 화면이 걷힌 뒤 호출자
             * (MainActivity)가 한다. onNext 안의 consumeRecording이 PCM을 이미
             * 가져가므로(FR-DP-02) 되감기가 늦어져도 음성 바이트가 남지는 않는다.
             */
            onClick = onNext,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 하단 캡션 한 줄. 13sp 흐린 잉크다 (정본 §3 `caption`) */
@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 경고 캡슐 크기 (아트보드 ②b: 높이 32 · 가로 패딩 14) */
private val COUNTDOWN_CAPSULE_HEIGHT = 32.dp
private val COUNTDOWN_CAPSULE_PADDING = 14.dp

/**
 * 곡선 두 레인을 감싸는 상자 (시안). 레인을 상자에 넣는 이유는 곡선이 "화면에 그려진 선"이
 * 아니라 "지금 보고 있는 자료"로 읽히게 하기 위해서다 - 대사 카드와 나란히 놓이면 두 덩어리가
 * 화면의 위아래를 나눈다.
 *
 * 상자 위에 "억양 곡선" 제목을 달지 않는다 (KAN-161 2단계) - 레인 라벨이 이미 "가이드"와
 * "내 억양"이라, 제목은 같은 말을 한 번 더 하면서 세로 공간만 먹는다.
 */
@Composable
private fun CurveCard(guidePoints: List<CurvePoint>, userSegments: List<List<CurvePoint>>) {
    CurveLaneGroup {
        // 가이드는 무성 구간을 보간으로 이어 둔 하나짜리 폴리라인이라 선분 하나로 감싼다.
        CurveLane(
            label = "가이드",
            segments = listOf(guidePoints),
            variant = CurveLaneVariant.Guide,
        )
        CurveLane(
            label = "내 억양",
            segments = userSegments,
            variant = CurveLaneVariant.User,
            topDivider = true,
        )
    }
}

private fun qualityMessage(quality: QualityStatus): String = when (quality) {
    QualityStatus.NORMAL -> "녹음 상태가 좋아요"
    QualityStatus.TOO_SHORT -> "발화가 너무 짧아요 — 조금 더 길게 말해주세요"
    QualityStatus.TOO_QUIET -> "소리가 너무 작아요 — 조금 더 크게 말해주세요"
    QualityStatus.CLIPPED -> "소리가 튀었어요 — 마이크에서 조금 떨어져 주세요"
}
