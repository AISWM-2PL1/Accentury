package com.accentury.app.web

import android.webkit.JavascriptInterface

/**
 * 웹 → 네이티브 브리지 (webview-layer.md §8). `window.AccenturyBridge`로 주입된다.
 *
 * 최소 표면 원칙 — 인트로(KAN-97)에 필요한 두 메서드만 둔다. 늘리기 전에
 * 웹에서 해결 가능한지 먼저 볼 것. 전체 셸(KAN-11)에서 startVoiceItem 등이 추가된다.
 *
 * @JavascriptInterface 메서드는 WebView에 로드된 임의 페이지의 JS가 **별도 스레드**에서
 * 호출한다. 그래서 상태를 바꾸는 호출은 (1) 메인 스레드로 넘긴 뒤 (2) 실행 시점의 현재 URL이
 * allowlist 안일 때만 동작한다 — allowlist(§7)와 호출 시점 origin 검증을 이중으로 거는 이유다.
 *
 * @param postToMain 메인 스레드 실행기 (프로덕션에선 View.post, 테스트에선 인라인 실행)
 * @param isCurrentUrlAllowed 메인 스레드에서 현재 로드된 URL의 allowlist 여부를 답한다
 */
class AccenturyBridge(
    private val postToMain: (() -> Unit) -> Unit,
    private val isCurrentUrlAllowed: () -> Boolean,
    private val onRequestMicPermission: () -> Unit,
) {
    /** §5 스큐 협상 — 웹이 앱의 계약 버전을 런타임에 재확인할 때 쓴다. 상태 변경이 없어 스레드 무관. */
    @JavascriptInterface
    fun getContractVersion(): Int = BRIDGE_CONTRACT_VERSION

    /** [시작하기] → 네이티브 마이크 권한 게이트 호출. 권한 로직 자체는 KAN-98 범위다. */
    @JavascriptInterface
    fun requestMicPermission() {
        postToMain {
            if (isCurrentUrlAllowed()) onRequestMicPermission()
        }
    }
}
