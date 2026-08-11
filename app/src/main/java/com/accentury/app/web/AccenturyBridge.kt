package com.accentury.app.web

import android.webkit.JavascriptInterface
import com.accentury.app.bridge.VoiceItemStart
import com.accentury.app.bridge.parseVoiceItemStart

/**
 * 웹 → 네이티브 브리지 (webview-layer.md §8). `window.AccenturyBridge`로 주입된다.
 *
 * 최소 표면 원칙 — 화면 전환(KAN-100)까지 필요한 세 메서드만 둔다. 늘리기 전에
 * 웹에서 해결 가능한지 먼저 볼 것.
 *
 * 메서드 추가는 하위호환이라 [BRIDGE_CONTRACT_VERSION]을 올리지 않는다 (§5).
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
    private val onStartVoiceItem: (VoiceItemStart) -> Unit,
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

    /**
     * VOICE 문항 진입 → 네이티브 녹음 화면 전환 (KAN-100).
     *
     * 파싱을 origin 검증 뒤로 미룬 이유: allowlist 밖 페이지가 보낸 payload는 내용과 무관하게
     * 처리할 값이 아니다. 검증을 통과한 payload만 파싱해야 순서가 곧 신뢰 경계와 같아진다.
     * 불량 payload는 조용히 무시한다 — 웹은 신뢰 경계 밖이라 여기서 오류를 되돌려 줄 상대가 아니고,
     * 잘못된 컨텍스트로 녹음 화면을 띄우는 것보다 아무 일도 안 하는 편이 안전하다.
     */
    @JavascriptInterface
    fun startVoiceItem(payloadJson: String) {
        postToMain {
            if (!isCurrentUrlAllowed()) return@postToMain
            val start = parseVoiceItemStart(payloadJson) ?: return@postToMain
            onStartVoiceItem(start)
        }
    }
}
