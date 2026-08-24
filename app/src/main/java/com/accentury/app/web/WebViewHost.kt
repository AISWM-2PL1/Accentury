package com.accentury.app.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.accentury.app.BuildConfig
import com.accentury.app.ui.components.AccenturyButton
import com.accentury.app.ui.components.StatusBlock
import com.accentury.app.ui.components.StatusTone
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.bridge.VoiceItemStart
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** 원격 웹 로드의 세 상태. Failed로 가는 길은 명시 오류 콜백과 자체 타임아웃 두 갈래다. */
sealed interface WebLoadState {
    data object Loading : WebLoadState
    data object Ready : WebLoadState
    data object Failed : WebLoadState
}

/**
 * 원격 전용 WebView 호스트 (webview-layer.md §3·§6·§7).
 *
 * 크롬 기본 오류 페이지를 사용자에게 절대 노출하지 않는다 — 실패가 감지되면 WebView를
 * 통째로 걷어내고 네이티브 오류 화면으로 바꾼다. 오프라인 동작이 목표가 아니라
 * (테스트 자체가 서버 필수) "실패의 질"이 목표다.
 *
 * [url]이 바뀌면 같은 WebView에서 이어 로드한다 (인트로 → 테스트 진입, KAN-100).
 *
 * @param sessionToken 브리지 getSessionToken이 웹에 건넬 세션 토큰 공급자 (KAN-13)
 * @param onStartRetest 결과 화면의 [다시 테스트하기] (KAN-34). 메인 스레드로 온다
 * @param onWebViewCreated 결과를 웹으로 주입하려면(evaluateJavascript) 상위가 인스턴스를 알아야 한다
 * @param onWebViewReleased 해제된 인스턴스. 상위가 들고 있는 참조를 놓을 자리다
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewHost(
    url: String,
    allowedOrigins: Set<String>,
    sessionToken: () -> String,
    onRequestMicPermission: () -> Unit,
    onStartVoiceItem: (VoiceItemStart) -> Unit,
    onStartRetest: () -> Unit,
    modifier: Modifier = Modifier,
    timeoutMs: Long = LOAD_TIMEOUT_MS,
    onWebViewCreated: (WebView) -> Unit = {},
    onWebViewReleased: (WebView) -> Unit = {},
) {
    // 상태 전이 규칙은 WebLoadController에 모여 있다(JVM 테스트 대상). 여기는 결선만 한다.
    val controller = remember { WebLoadController() }

    if (controller.state == WebLoadState.Failed) {
        LoadFailureScreen(onRetry = controller::retry, modifier = modifier)
        return
    }

    // attempt가 바뀌면 key()가 WebView를 처음부터 새로 만든다 — 실패한 WebView의
    // 내부 상태(오류 페이지 등)를 이어받지 않기 위해서다.
    key(controller.attempt) {
        // 실제로 로드를 건 URL. update 블록은 리컴포지션마다 도는데 매번 loadUrl하면 로드가
        // 끝나지 않으므로, 값이 달라졌을 때만 다시 건다. 컴포지션에서 읽지 않아 이 쓰기가
        // 리컴포지션을 부르지도 않는다. attempt가 바뀌면 함께 초기화돼 새 WebView가 다시 로드한다.
        val loadedUrl = remember { mutableStateOf<String?>(null) }

        /*
         * 동기 반환 브리지(getSessionToken, KAN-13)용 origin 허용 플래그.
         *
         * 값을 돌려주는 메서드는 postToMain 검증을 못 쓴다(반환이 동기) — 대신 메인 스레드가
         * 메인 프레임 전환(onPageStarted)마다 이 플래그를 갱신해 두고, JS 스레드는 읽기만 한다.
         * 시작값 false: 첫 페이지가 뜨기 전(about:blank 포함)에는 아무에게도 토큰을 주지 않는다.
         * attempt가 바뀌면 remember째 초기화돼 새 WebView도 false에서 시작한다.
         */
        val originAllowed = remember { AtomicBoolean(false) }

        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // §7 보안 설정표 그대로. 원격 전용이라 로컬 파일·콘텐츠 접근은 전부 끈다.
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        // 릴리스 빌드에서 원격 디버깅 차단
                        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                // allowlist 밖 URL은 로드 차단 (§7). 외부 링크가 생기면
                                // 여기서 Custom Tabs로 여는 처리를 더한다 — 인트로엔 아직 없다.
                                return !isAllowedWebUrl(request.url.toString(), allowedOrigins)
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                // 메인 프레임 전환마다 동기 브리지용 origin 플래그 갱신 (메인 스레드).
                                // 리다이렉트도 각 전환이 onPageStarted를 타므로 여기 한 곳이면 된다.
                                originAllowed.set(isAllowedWebUrl(url, allowedOrigins))
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                controller.onPageFinished()
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                // 서브리소스 하나 실패로 화면 전체를 접지 않는다 — 메인 프레임만.
                                if (request.isForMainFrame) controller.onMainFrameError()
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse,
                            ) {
                                if (request.isForMainFrame) controller.onMainFrameError()
                            }
                        }

                        addJavascriptInterface(
                            AccenturyBridge(
                                // View.post는 이 뷰가 붙은 UI(메인) 스레드 큐로 넘긴다.
                                postToMain = { block -> post(block) },
                                // 실행 시점의 현재 URL로 재검증 — 로드 중 리다이렉트돼 있어도 안전하다.
                                isCurrentUrlAllowed = { isAllowedWebUrl(this.url, allowedOrigins) },
                                // 동기 반환 메서드용 — 메인 스레드가 갱신해 둔 플래그를 읽기만 한다.
                                isOriginAllowedNow = { originAllowed.get() },
                                sessionToken = sessionToken,
                                onRequestMicPermission = onRequestMicPermission,
                                onStartVoiceItem = onStartVoiceItem,
                                onStartRetest = onStartRetest,
                            ),
                            "AccenturyBridge",
                        )
                        // 첫 로드도 update가 건다 — 로드 지점이 하나여야 "url이 곧 화면"이 유지된다.
                        onWebViewCreated(this)
                    }
                },
                update = { view ->
                    if (loadedUrl.value != url) {
                        loadedUrl.value = url
                        controller.onNavigationStarted()
                        view.loadUrl(url)
                    }
                },
                // 참조를 먼저 놓게 한 뒤 파괴한다 — 상위가 파괴된 WebView를 붙들 틈을 주지 않는다.
                onRelease = {
                    onWebViewReleased(it)
                    it.destroy()
                },
            )
            if (controller.state == WebLoadState.Loading) {
                LoadingScreen()
            }
        }

        // 자체 타임아웃 (§6) — onPageFinished가 오지 않는 실패(끊긴 연결에서의 무한 대기 등)를
        // 오류 콜백 대신 시간으로 잡는다. attempt나 url이 바뀌면 타이머도 새로 시작된다.
        LaunchedEffect(controller.attempt, url) {
            delay(timeoutMs)
            controller.onTimeout()
        }
    }
}

/**
 * 로드 완료까지 붙드는 네이티브 로딩 화면 — 빈 화면·흰 플래시를 노출하지 않는다 (§10 Q5).
 * Surface로 아래 WebView를 완전히 가린다.
 */
@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("사투리 억양 테스트", style = MaterialTheme.typography.titleMedium)
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 네이티브 오류 화면 (§6) — 비난 없는 카피 + [다시 시도]. */
@Composable
private fun LoadFailureScreen(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusBlock(
            tone = StatusTone.Error,
            message = "연결이 불안정해요",
            detail = "네트워크를 확인하고 다시 시도해 주세요",
            action = { AccenturyButton(text = "다시 시도", onClick = onRetry) },
        )
    }
}
