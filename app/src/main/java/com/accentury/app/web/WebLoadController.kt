package com.accentury.app.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 원격 웹 로드 상태 머신 (webview-layer.md §6). WebView 콜백·타이머·재시도가 여기로 모인다.
 *
 * WebViewHost에서 분리한 이유: 콜백 도착 순서가 뒤엉키는 경계 조건(오류 후 onPageFinished,
 * Ready 후 늦은 타임아웃 등)이 실패 UX의 정확성을 좌우하는데, WebView에 붙어 있으면
 * JVM 단위 테스트가 불가능하다. Compose snapshot state라 화면은 그대로 따라온다.
 */
class WebLoadController {
    var state: WebLoadState by mutableStateOf(WebLoadState.Loading)
        private set

    /** 재시도 횟수이자 WebView 재생성 키 — 값이 바뀌면 호스트가 WebView를 처음부터 새로 만든다. */
    var attempt: Int by mutableIntStateOf(0)
        private set

    /**
     * 크롬 오류 페이지도 onPageFinished를 쏘기 때문에, 오류 콜백이 먼저 찍은 Failed를
     * 여기서 덮어쓰면 안 된다 — Loading일 때만 Ready로 간다.
     */
    fun onPageFinished() {
        if (state == WebLoadState.Loading) state = WebLoadState.Ready
    }

    /** 메인 프레임 오류(네트워크·HTTP)는 로드 단계든 로드 후 내비게이션이든 실패 화면으로 보낸다. */
    fun onMainFrameError() {
        state = WebLoadState.Failed
    }

    /** 자체 타임아웃 (§6). 로드가 이미 끝났으면(성공이든 실패든) 늦게 도착한 타이머는 무시한다. */
    fun onTimeout() {
        if (state == WebLoadState.Loading) state = WebLoadState.Failed
    }

    /**
     * 같은 WebView에서 다른 URL 로드를 시작했다 (인트로 → 테스트 진입, KAN-100).
     * 앞 페이지가 Ready였다고 다음 페이지를 로드 완료로 볼 수는 없다 — 다시 Loading으로 내려야
     * 로딩 화면이 전환 중의 앞 페이지를 덮고, 타임아웃도 새 로드를 대상으로 다시 걸린다.
     */
    fun onNavigationStarted() {
        state = WebLoadState.Loading
    }

    /** [다시 시도] — attempt를 올려 WebView를 새로 만들고 처음부터 로드한다. */
    fun retry() {
        attempt += 1
        state = WebLoadState.Loading
    }
}
