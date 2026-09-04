package com.accentury.app.web

import android.webkit.JavascriptInterface
import com.accentury.app.analytics.CrashReports
import com.accentury.app.analytics.EventParam
import com.accentury.app.analytics.isAnalyticsName
import com.accentury.app.analytics.parseEventParams
import com.accentury.app.bridge.SharePayload
import com.accentury.app.bridge.VoiceItemStart
import com.accentury.app.bridge.parseSharePayload
import com.accentury.app.bridge.parseVoiceItemStart

/**
 * 웹 → 네이티브 브리지 (webview-layer.md §8). `window.AccenturyBridge`로 주입된다.
 *
 * 최소 표면 원칙 — 화면 전환(KAN-100)·답안 제출 인증(KAN-13)·재응시(KAN-34)·결과 공유(KAN-30)·
 * 계측(KAN-33)까지 필요한 일곱 메서드만 둔다. 늘리기 전에 웹에서 해결 가능한지 먼저 볼 것.
 *
 * 메서드 추가는 하위호환이라 [BRIDGE_CONTRACT_VERSION]을 올리지 않는다 (§5).
 *
 * @JavascriptInterface 메서드는 WebView에 로드된 임의 페이지의 JS가 **별도 스레드**에서
 * 호출한다. 그래서 상태를 바꾸는 호출은 (1) 메인 스레드로 넘긴 뒤 (2) 실행 시점의 현재 URL이
 * allowlist 안일 때만 동작한다 — allowlist(§7)와 호출 시점 origin 검증을 이중으로 거는 이유다.
 *
 * @param postToMain 메인 스레드 실행기 (프로덕션에선 View.post, 테스트에선 인라인 실행)
 * @param isCurrentUrlAllowed 메인 스레드에서 현재 로드된 URL의 allowlist 여부를 답한다
 * @param isOriginAllowedNow 임의 스레드에서 안전하게 읽는 origin 허용 여부 — 값을 **돌려주는**
 *   메서드는 postToMain으로 미룰 수 없어서(동기 반환), 메인 스레드가 페이지 전환마다 갱신해 둔
 *   플래그를 여기로 받는다 (WebViewHost의 AtomicBoolean)
 * @param sessionToken 세션 토큰 공급자 (KAN-13)
 * @param onStartRetest 결과 화면의 [다시 테스트하기] (KAN-34). 중복 호출 무시는 이 콜백 너머의
 *   상태 머신이 맡는다 — 진행 중이라는 사실의 주인이 둘이면 어긋난다 (SessionGateController.retestInFlight)
 * @param onShareResult 결과 화면의 [친구에게 공유하기] (KAN-30). 카드 자산은 웹이 실어 보내고
 *   (서버가 정한 값이다) 어느 통로로 나갈지는 네이티브가 정한다 (ResultSharer)
 * @param onLogEvent 웹이 센 계측 이벤트 (KAN-33). 이름·파라미터는 검증을 통과한 값이고, 어디로
 *   보낼지는 창구 너머의 sink가 정한다 (analytics/AppEvents.kt)
 */
class AccenturyBridge(
    private val postToMain: (() -> Unit) -> Unit,
    private val isCurrentUrlAllowed: () -> Boolean,
    private val isOriginAllowedNow: () -> Boolean,
    private val sessionToken: () -> String,
    private val onRequestMicPermission: () -> Unit,
    private val onStartVoiceItem: (VoiceItemStart) -> Unit,
    private val onStartRetest: () -> Unit,
    private val onShareResult: (SharePayload) -> Unit,
    private val onLogEvent: (String, Map<String, EventParam>) -> Unit,
) {
    /** §5 스큐 협상 — 웹이 앱의 계약 버전을 런타임에 재확인할 때 쓴다. 상태 변경이 없어 스레드 무관. */
    @JavascriptInterface
    fun getContractVersion(): Int = BRIDGE_CONTRACT_VERSION

    /**
     * 세션 토큰 — 웹의 어휘 답안 제출(KAN-13)이 Authorization 헤더에 싣는다.
     *
     * 토큰을 URL 쿼리로 실어 보내지 않는 이유가 이 메서드의 존재 이유다: 쿼리는 히스토리·로그에
     * 남는다. 메서드 추가는 하위호환이라 계약 버전 1을 유지한다 (§5).
     *
     * 비밀값을 돌려주므로 origin 검증이 필수인데, 반환이 동기라 postToMain 패턴을 못 쓴다 —
     * 대신 메인 스레드가 유지하는 [isOriginAllowedNow] 플래그를 본다. 허용이 아니면 빈 문자열이다
     * (웹 래퍼가 빈 값을 null로 정규화한다).
     */
    @JavascriptInterface
    fun getSessionToken(): String = if (isOriginAllowedNow()) sessionToken() else ""

    /** [시작하기] → 네이티브 마이크 권한 게이트 호출. 권한 로직 자체는 KAN-98 범위다. */
    @JavascriptInterface
    fun requestMicPermission() {
        postToMain {
            if (isCurrentUrlAllowed()) onRequestMicPermission()
        }
    }

    /**
     * 결과 화면의 [다시 테스트하기] → 네이티브 재응시 (KAN-34, KAN-107).
     *
     * 인자가 없는 이유: 무엇을 버릴지는 웹이 아니라 네이티브가 안다. 폐기할 이전 세션의 토큰은
     * 네이티브가 들고 있는 값이고, 웹이 실어 보내게 하면 토큰이 JS 경계를 한 번 더 건넌다 —
     * 쿼리에 토큰을 싣지 않는 것과 같은 이유다 (getSessionToken KDoc).
     *
     * 메서드 추가는 하위호환이라 [BRIDGE_CONTRACT_VERSION] 1을 유지한다 (§5).
     */
    @JavascriptInterface
    fun startRetest() {
        postToMain {
            if (isCurrentUrlAllowed()) onStartRetest()
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
            val start = parseVoiceItemStart(payloadJson) ?: run {
                // 조용히 버리되 흔적은 남긴다 (KAN-33). allowlist를 통과한 페이지만 여기 오므로
                // 이 실패는 우리 웹과 앱이 계약을 다르게 알고 있다는 뜻이다 (CrashReports).
                CrashReports.recordBridgeParseFailure("startVoiceItem")
                return@postToMain
            }
            onStartVoiceItem(start)
        }
    }

    /**
     * 결과 화면의 [친구에게 공유하기] → 네이티브 카카오톡 공유 (KAN-30).
     *
     * [startVoiceItem]과 같은 순서다 — origin 검증을 통과한 payload만 파싱한다. 여기서는 그 순서가
     * 한 겹 더 중요한데, 이 payload의 값들은 화면에 그려지고 마는 게 아니라 카카오 템플릿과 공유
     * 인텐트를 타고 **앱 밖으로 나간다** (parseSharePayload의 https 검증).
     *
     * **공유 결말을 웹에 회신하지 않는다.** 결과 화면은 공유가 되든 취소되든 달라질 게 없고
     * (ItemResultDelivery처럼 다음 단계를 여는 신호가 아니다), 카톡으로 넘어간 뒤 사용자가 실제로
     * 보냈는지는 우리가 알 수 있는 사실도 아니다. 알 수 없는 값을 회신 계약으로 만들면 웹이 그 값에
     * 기대어 화면을 바꾸게 된다.
     *
     * 메서드 추가는 하위호환이라 [BRIDGE_CONTRACT_VERSION] 1을 유지한다 (§5).
     */
    @JavascriptInterface
    fun shareResult(payloadJson: String) {
        postToMain {
            if (!isCurrentUrlAllowed()) return@postToMain
            val payload = parseSharePayload(payloadJson) ?: run {
                CrashReports.recordBridgeParseFailure("shareResult")
                return@postToMain
            }
            onShareResult(payload)
        }
    }

    /**
     * 웹이 센 계측 이벤트를 네이티브 Firebase로 넘긴다 (KAN-33).
     *
     * 앱 안 이벤트를 웹의 gtag가 아니라 여기로 받는 이유는 두 가지다. SDK가 붙여 주는 축
     * (기기·OS·앱 버전·앱 인스턴스)은 WebView 안에서 만들 수 없고, 웹 스트림으로 보내면 앱
     * 사용자가 웹 트래픽으로 세어진다 (`analytics/track.ts`의 분기표).
     *
     * @JavascriptInterface는 문자열만 주고받으므로 파라미터는 JSON으로 온다. 검증 순서는
     * [startVoiceItem]과 같다 — origin을 통과한 값만 파싱한다. 다만 여기서 거르는 것은 안전이
     * 아니라 **집계 축의 위생**이다: 규격 밖 이름이 한 번 흘러가면 GA4에 지울 수 없는 축이 생긴다
     * (`analytics/EventParams.kt`).
     *
     * 이벤트 하나를 잃는 것은 감수한다. 웹은 오류를 돌려줄 상대가 아니고(§8), 계측 때문에 응시를
     * 멈출 이유는 더더욱 없다 — 대신 버렸다는 사실만 Crashlytics 비치명 이벤트로 남긴다.
     *
     * 메서드 추가는 하위호환이라 [BRIDGE_CONTRACT_VERSION] 1을 유지한다 (§5). 그래서 계측을 모르는
     * 구버전 앱도 스큐 게이트를 그대로 통과하고, 웹 래퍼가 false로 걸러 이벤트만 조용히 사라진다.
     */
    @JavascriptInterface
    fun logEvent(name: String, paramsJson: String) {
        postToMain {
            if (!isCurrentUrlAllowed()) return@postToMain
            val params = if (isAnalyticsName(name)) parseEventParams(paramsJson) else null
            if (params == null) {
                CrashReports.recordBridgeParseFailure("logEvent")
                return@postToMain
            }
            onLogEvent(name, params)
        }
    }
}
