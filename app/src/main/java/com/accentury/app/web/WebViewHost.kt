package com.accentury.app.web

import android.annotation.SuppressLint
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.accentury.app.BuildConfig
import kotlinx.coroutines.delay

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
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewHost(
    url: String,
    allowedOrigins: Set<String>,
    onRequestMicPermission: () -> Unit,
    modifier: Modifier = Modifier,
    timeoutMs: Long = LOAD_TIMEOUT_MS,
) {
    var loadState by remember { mutableStateOf<WebLoadState>(WebLoadState.Loading) }
    // 재시도마다 1씩 올려 key()로 WebView를 처음부터 새로 만든다 — 실패한 WebView의
    // 내부 상태(오류 페이지 등)를 이어받지 않기 위해서다.
    var attempt by remember { mutableIntStateOf(0) }

    if (loadState == WebLoadState.Failed) {
        LoadFailureScreen(
            onRetry = {
                attempt += 1
                loadState = WebLoadState.Loading
            },
            modifier = modifier,
        )
        return
    }

    key(attempt) {
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

                            override fun onPageFinished(view: WebView, url: String?) {
                                // 오류 콜백이 먼저 Failed를 찍었으면 덮어쓰지 않는다
                                // (크롬 오류 페이지도 onPageFinished를 쏜다).
                                if (loadState == WebLoadState.Loading) loadState = WebLoadState.Ready
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                // 서브리소스 하나 실패로 화면 전체를 접지 않는다 — 메인 프레임만.
                                if (request.isForMainFrame) loadState = WebLoadState.Failed
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse,
                            ) {
                                if (request.isForMainFrame) loadState = WebLoadState.Failed
                            }
                        }

                        addJavascriptInterface(
                            AccenturyBridge(
                                // View.post는 이 뷰가 붙은 UI(메인) 스레드 큐로 넘긴다.
                                postToMain = { block -> post(block) },
                                // 실행 시점의 현재 URL로 재검증 — 로드 중 리다이렉트돼 있어도 안전하다.
                                isCurrentUrlAllowed = { isAllowedWebUrl(this.url, allowedOrigins) },
                                onRequestMicPermission = onRequestMicPermission,
                            ),
                            "AccenturyBridge",
                        )
                        loadUrl(url)
                    }
                },
                onRelease = { it.destroy() },
            )
            if (loadState == WebLoadState.Loading) {
                LoadingScreen()
            }
        }

        // 자체 타임아웃 (§6) — onPageFinished가 오지 않는 실패(끊긴 연결에서의 무한 대기 등)를
        // 오류 콜백 대신 시간으로 잡는다. attempt가 바뀌면 타이머도 새로 시작된다.
        LaunchedEffect(attempt) {
            delay(timeoutMs)
            if (loadState == WebLoadState.Loading) loadState = WebLoadState.Failed
        }
    }
}

/**
 * 로드 완료까지 붙드는 네이티브 로딩 화면 — 빈 화면·흰 플래시를 노출하지 않는다 (§10 Q5).
 * Surface로 아래 WebView를 완전히 가린다.
 */
@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("사투리 억양 테스트", fontSize = 20.sp)
            CircularProgressIndicator()
        }
    }
}

/** 네이티브 오류 화면 (§6) — 비난 없는 카피 + [다시 시도]. */
@Composable
private fun LoadFailureScreen(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("연결이 불안정해요", fontSize = 20.sp)
        Text("네트워크를 확인하고 다시 시도해 주세요", fontSize = 14.sp)
        Button(onClick = onRetry) { Text("다시 시도") }
    }
}
