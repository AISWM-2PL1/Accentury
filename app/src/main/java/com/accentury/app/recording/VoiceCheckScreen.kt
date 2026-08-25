package com.accentury.app.recording

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accentury.app.audio.AudioQuality
import com.accentury.app.ui.components.AccenturyButton
import com.accentury.app.ui.components.CurveLane
import com.accentury.app.ui.components.PromptCard
import com.accentury.app.ui.components.StatusBlock
import com.accentury.app.ui.components.StatusTone
import com.accentury.app.ui.theme.Dimens
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.ui.theme.accenturyColors
import kotlin.math.log10

/**
 * 목소리 점검 화면 (KAN-105 2단계). 시작 게이트의 세 번째 칸이다 —
 * 마이크 권한(KAN-98) 뒤, 세션 생성(KAN-34) 앞.
 *
 * "안녕하세요" 한 마디로 두 가지를 끝낸다. 하나는 이 화자의 중심 음높이다 — 이후 모든 문항의
 * '내 억양' 곡선이 이 값을 y축 중심으로 쓰므로(KAN-105 1단계), 미리 재 두면 첫 문항의 첫 음절부터
 * 곡선이 제자리에서 그려진다. 문항마다 그 녹음의 앞부분으로 중심을 잡으면 문항끼리 축이 달라져
 * "내 억양"이 문항마다 다른 높이에 놓인다. 다른 하나는 볼륨 확인이다 — 마이크가 멀거나 막혀
 * 소리가 작은 상태를, 결과에 반영되는 첫 문항이 아니라 여기서 알아채게 한다.
 *
 * 화면이 이 자리에 서는 이유: 마이크가 방금 열려 확인할 것이 바로 앞에 있고, 아직 네트워크를
 * 쓰기 전이라 실패할 구석이 없다(전부 기기 안에서 끝난다). 세션 뒤로 밀면 이미 발급된 세션을
 * 든 채 점검에 붙들리는 구간이 생긴다.
 *
 * 판정은 전부 [VoiceCheckController]가 하고 여기는 그 상태를 그리기만 한다.
 *
 * @param onDone 잰 중심 음높이를 호출자에게 넘긴다 — 이 값이 문항 화면의 centerHz가 된다
 */
@SuppressLint("MissingPermission") // 이 화면은 권한 게이트를 지난 뒤에만 열린다 (KAN-98)
@Composable
fun VoiceCheckScreen(
    viewModel: VoiceCheckViewModel,
    onDone: (centerHz: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 권한은 앞 칸에서 받았으니 버튼 없이 진입 즉시 듣는다 - 여기서 한 번 더 누르게 하면
    // "말하세요"라는 안내와 "시작하세요"라는 버튼이 서로를 가린다.
    LaunchedEffect(Unit) { viewModel.start() }
    // 화면이 걷히면 마이크를 놓는다. 뷰모델은 이 화면보다 오래 살아서(회전) 스스로는 안 끝난다.
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    val frames = when (val s = state) {
        is VoiceCheckState.Listening -> s.frames
        is VoiceCheckState.Ready -> s.frames
        is VoiceCheckState.TimedOut -> s.frames
        is VoiceCheckState.Failed -> emptyList()
    }
    /*
     * 잠긴 중심을 그대로 넘긴다. 값 자체는 [userCurveDisplayPoints]가 프레임에서 스스로 잡는
     * 것과 같지만(같은 [userCurveCenterHz]), 화면이 "무엇을 축으로 그리는가"를 판정기와 한
     * 값으로 묶어 두면 나중에 판정 규칙이 바뀌어도 곡선이 따라간다.
     */
    val centerHz = when (val s = state) {
        is VoiceCheckState.Listening -> s.centerHz
        is VoiceCheckState.Ready -> s.centerHz
        else -> null
    }
    val segments = userCurveDisplayPoints(frames, VOICE_CHECK_WINDOW_MS, centerHz)

    // 색을 명시한다 - Surface 기본값은 surface(카드 흰색)라, 그대로 두면 이 화면만 흰 배경이 되어
    // 바로 앞뒤 화면(background #eff6ff)과 어긋난다 (세션 게이트·녹음 오버레이와 같은 이유).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.x4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "목소리를 확인할게요",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Spacing.x2))
            Text(
                "아래 말을 편하게 해 주세요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(Spacing.x4))
            // 문항 화면과 같은 카드다 - 여기서 말한 방식 그대로 문항에서도 말하면 된다는 뜻이 된다.
            PromptCard(badge = "🎤 목소리 점검", prompt = "안녕하세요")

            Spacer(modifier = Modifier.height(Spacing.x4))
            /*
             * 레인은 하나다. 점검에는 따라 할 가이드가 없으므로(자기 목소리만 재는 자리)
             * 빈 가이드 레인을 함께 세우면 사용자는 없는 곡선을 찾게 된다.
             * 중심이 잠기기 전에는 빈 레인이 정상이다 - 임시 축으로 그려 두면 축이 잠기는 순간
             * 곡선 전체가 한 번 점프한다 ([userCurveDisplayPoints] 참고).
             */
            CurveLane(
                label = "내 억양",
                segments = segments,
                lineColor = MaterialTheme.accenturyColors.userCurve,
                dashed = false,
            )

            Spacer(modifier = Modifier.height(Spacing.x4))
            InputLevelBar(level = (state as? VoiceCheckState.Listening)?.level ?: 0.0)

            Spacer(modifier = Modifier.height(Spacing.x4))
            val failed = state is VoiceCheckState.TimedOut || state is VoiceCheckState.Failed
            StatusBlock(
                // 실패에만 Error를 준다 - 그래야 스크린 리더가 스스로 읽는다(StatusBlock 주석).
                // 듣는 중 문구는 청크마다 바뀌므로 읽어 주면 소음이 된다.
                tone = if (failed) StatusTone.Error else StatusTone.Waiting,
                message = statusMessage(state),
                // 시간이 다 됐을 때만 무엇이 모자랐는지 덧붙인다 - "잡히지 않았어요"만으로는
                // 다음에 무엇을 다르게 해야 하는지 알 수 없다.
                detail = (state as? VoiceCheckState.TimedOut)?.let { hintMessage(it.hint) },
            )

            Spacer(modifier = Modifier.weight(1f))

            when (val s = state) {
                is VoiceCheckState.Ready -> AccenturyButton(
                    text = "다음",
                    onClick = { onDone(s.centerHz) },
                    modifier = Modifier.fillMaxWidth(),
                )

                is VoiceCheckState.TimedOut, is VoiceCheckState.Failed -> AccenturyButton(
                    text = "다시 시도",
                    onClick = viewModel::restart,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 듣는 중에는 버튼이 없다 - 지금 사용자가 할 일은 말하는 것 하나뿐이라,
                // 누를 것을 주면 말하기를 멈추고 그걸 누른다.
                is VoiceCheckState.Listening -> Unit
            }

            Spacer(modifier = Modifier.height(Spacing.x4))
            Text(
                "이 소리는 저장하거나 보내지 않아요",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Spacing.x8))
        }
    }
}

/**
 * 곡선이 담는 시간. 가이드가 없는 화면이라 [userCurveWindowMs]의 기본값과 같은 1초다 —
 * 창이 짧을수록 곡선이 흐르는 게 눈에 보여서 "지금 내 목소리가 들어오고 있다"가 전해진다.
 */
private const val VOICE_CHECK_WINDOW_MS = 1_000L

/**
 * 입력 레벨 바. 곡선이 "무엇을 말했는가"라면 이건 "얼마나 크게 말했는가"다 —
 * 볼륨 부족은 곡선만 봐서는 알 수 없다(작게 말해도 F0는 잡힌다).
 *
 * 눈금은 통과선([AudioQuality.QUIET_RMS_THRESHOLD])이다. "조금 더 크게"라는 말만으로는
 * 얼마나 더인지 알 수 없어서, 넘어야 할 자리를 눈에 보이게 둔다.
 */
@Composable
private fun InputLevelBar(level: Double) {
    val fraction = levelBarFraction(level)
    val threshold = levelBarFraction(AudioQuality.QUIET_RMS_THRESHOLD)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.progressBarHeight)
            .clip(RoundedCornerShape(Radius.full))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // 값이 아니라 뜻만 읽힌다 - 시시각각 바뀌는 숫자를 읽어 주면 화면을 못 쓴다.
            .clearAndSetSemantics { contentDescription = "입력 레벨" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Radius.full))
                .background(MaterialTheme.colorScheme.primary),
        )
        // 눈금은 채움 위에 그린다 - 채움이 눈금을 넘어선 순간에도 선이 보여야 통과가 읽힌다.
        Box(modifier = Modifier.fillMaxWidth(threshold), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .width(TICK_WIDTH)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

private val TICK_WIDTH = 2.dp

/**
 * 원 스케일 rms를 바의 0..1 비율로. **로그 스케일**이다 —
 * 사람이 느끼는 크기가 로그라서, 선형으로 그리면 일상적인 발화(rms 수백)가 전체 스케일
 * (32768) 대비 바 왼쪽 끝에 붙어 버려 커졌는지 작아졌는지가 안 보인다.
 */
private fun levelBarFraction(rms: Double): Float {
    if (rms <= 1.0) return 0f
    return (log10(rms) / log10(AudioQuality.FULL_SCALE)).coerceIn(0.0, 1.0).toFloat()
}

/** 상태 한 줄. 비난 없이, 지금 할 일 하나만 말한다. */
private fun statusMessage(state: VoiceCheckState): String = when (state) {
    is VoiceCheckState.Listening -> hintMessage(state.hint)
    is VoiceCheckState.Ready -> "좋아요, 목소리가 잘 들려요"
    is VoiceCheckState.TimedOut -> "목소리가 잡히지 않았어요"
    // 엔진이 준 문구를 그대로 쓴다 - 마이크가 왜 안 열렸는지는 앱이 지어낼 수 없다.
    is VoiceCheckState.Failed -> state.reason
}

private fun hintMessage(hint: VoiceCheckHint): String = when (hint) {
    VoiceCheckHint.SAY_IT -> "'안녕하세요'라고 말해 주세요"
    VoiceCheckHint.KEEP_GOING -> "조금만 더요"
    VoiceCheckHint.TOO_QUIET -> "조금 더 크게 말해 주세요"
}
