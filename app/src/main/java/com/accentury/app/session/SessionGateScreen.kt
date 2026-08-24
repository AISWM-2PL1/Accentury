package com.accentury.app.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.accentury.app.ui.components.AccenturyButton
import com.accentury.app.ui.components.StatusBlock
import com.accentury.app.ui.components.StatusTone
import com.accentury.app.ui.theme.Spacing
import kotlinx.coroutines.ensureActive

/**
 * 세션 확보 화면 (KAN-34). 마이크 권한 게이트(KAN-98)를 지난 뒤 테스트 진입 URL을 열기 전에 선다.
 *
 * [com.accentury.app.web.WebViewHost]의 로딩·실패 화면과 같은 자리를 차지한다 — WebView는 아래에서
 * 인트로를 든 채 살아 있고, 이 화면은 [Surface]로 그 위를 덮을 뿐이다. 세션을 받으면 화면이 걷히고
 * 같은 WebView가 테스트 진입 URL로 이어 로드한다.
 *
 * 요청을 이 화면이 거는 이유: 세션 생성은 사용자가 기다리는 화면과 수명이 같다. 화면 밖에서 걸면
 * 화면이 사라진 뒤에도 도는 요청과 그 결과를 받을 자리를 따로 관리해야 하는데, 여기서는 이탈이 곧
 * 취소이고(코루틴 취소가 소켓까지 내려간다) 다시 들어오면 다시 건다.
 *
 * @param gate 상태 머신. 결과 판정과 재시도가 전부 여기로 모인다
 * @param onBackToIntro 다시 시도해도 소용없는 실패에서 인트로로 돌려보낸다
 */
@Composable
fun SessionGateScreen(
    gate: SessionGateController,
    client: SessionClient,
    appVersion: String,
    onBackToIntro: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(gate.attempt) {
        // 실패 화면에 머무는 동안에는 다시 걸지 않는다 — 재시도는 attempt를 올려 이 이펙트를
        // 통째로 다시 돌리는 경로 하나뿐이다.
        if (gate.state !is SessionGateState.Creating) return@LaunchedEffect

        /*
         * 버려 둔 세션이 있으면 그 토큰을 함께 보내 서버에서도 지운다 (KAN-107).
         *
         * 테스트를 종료하고 인트로로 돌아온 경우가 이 자리다 — 앱은 이미 세션을 버렸지만 서버는
         * 다음 생성 요청이 이전 토큰을 실어야 지운다. 없으면(최초 응시, 또는 폐기가 이미 끝난 뒤)
         * null이고 그때는 평범한 세션 생성이다.
         *
         * 결과 화면의 재응시는 이 경로를 타지 않는다 — 게이트 화면이 뜨지 않는 자리에서 벌어지므로
         * MainActivity가 직접 건다 (SessionGateController.beginRetest).
         */
        val result = client.create(appVersion = appVersion, previousToken = gate.pendingPreviousToken)

        // 취소된 뒤 도착한 앞 시도의 결과는 버린다. 재시도가 이 이펙트를 다시 걸었는데 앞 시도의
        // 실패가 뒤늦게 반영되면, 방금 시작한 '준비 중'이 실패 화면으로 되돌아간다.
        ensureActive()
        gate.onResult(result)
    }

    // 색을 명시한다 — Surface 기본값은 surface(카드 흰색)라, 그대로 두면 이 화면만 흰 배경이 되어
    // 바로 앞뒤 WebView 화면(background #eff6ff)과 어긋난다 (녹음 오버레이와 같은 이유).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = gate.state) {
            is SessionGateState.Failed -> FailureScreen(state, onRetry = gate::restart, onBackToIntro = onBackToIntro)
            // 확보 직후 한 프레임은 여기로 올 수 있다 — 상위가 세션을 보고 이 화면을 걷어내기
            // 직전이라, 준비 중 표시를 그대로 두는 것이 화면이 덜컥거리지 않는 쪽이다.
            else -> PreparingScreen()
        }
    }
}

/**
 * 준비 중 화면. WebView 로딩 화면(webview-layer.md §10 Q5)과 같은 구성이다 — 빈 화면·흰 플래시를
 * 노출하지 않는 것이 목적이라 문구와 스피너 한 쌍이면 충분하다.
 */
@Composable
private fun PreparingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("테스트를 준비하고 있어요", style = MaterialTheme.typography.titleMedium)
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 실패 화면. 웹 로드 실패 화면과 같은 [StatusBlock] 구성이다 — 비난 없는 문구 + 지금 할 수 있는 동작 하나.
 *
 * [SessionFailureReason.Unsupported]에만 [다시 시도] 대신 [처음으로]를 준다. 서버가 재시도 불가로
 * 못박은 거절은 같은 요청을 다시 보내도 같은 답이 오므로 버튼이 거짓말이 된다(업로드 상태 바가
 * 재시도 불가 실패에 버튼을 주지 않는 것과 같은 판단). 그렇다고 아무 동작도 주지 않으면 이 게이트
 * 뒤에는 인트로로 돌아갈 길이 없어 사용자가 갇힌다.
 */
@Composable
private fun FailureScreen(
    state: SessionGateState.Failed,
    onRetry: () -> Unit,
    onBackToIntro: () -> Unit,
) {
    val message = when (state.reason) {
        SessionFailureReason.RateLimited -> "잠시 뒤에 시작할 수 있어요"
        SessionFailureReason.Network -> "연결이 불안정해요"
        SessionFailureReason.Server -> "테스트를 시작하지 못했어요"
        SessionFailureReason.Unsupported -> "지금은 테스트를 시작할 수 없어요"
    }
    val detail = when (state.reason) {
        SessionFailureReason.RateLimited -> {
            val wait = state.retryAfterSeconds
            if (wait != null) {
                "접속이 몰리고 있어요 · ${wait}초 뒤에 다시 눌러 주세요"
            } else {
                "접속이 몰리고 있어요 · 잠시 뒤에 다시 눌러 주세요"
            }
        }
        SessionFailureReason.Network -> "네트워크를 확인하고 다시 시도해 주세요"
        SessionFailureReason.Server -> "잠시 뒤에 다시 시도해 주세요"
        SessionFailureReason.Unsupported -> "앱을 최신 버전으로 업데이트한 뒤 다시 열어 주세요"
    }
    val unsupported = state.reason == SessionFailureReason.Unsupported

    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusBlock(
            tone = StatusTone.Error,
            message = message,
            detail = detail,
            action = {
                AccenturyButton(
                    text = if (unsupported) "처음으로" else "다시 시도",
                    onClick = if (unsupported) onBackToIntro else onRetry,
                )
            },
        )
    }
}
