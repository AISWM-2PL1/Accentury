package com.accentury.app.analytics

import com.accentury.app.web.BRIDGE_CONTRACT_VERSION
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * 비치명 오류 보고 (KAN-33). 크래시가 아니라 **조용히 실패하는 자리**를 남기는 통로다.
 *
 * 우리 앱의 실패 경로는 대부분 사용자에게 화면으로 보이지 않는다 — 브리지가 못 읽는 메시지는
 * 그냥 버려지고, 마이크가 안 열리면 화면은 "다시 시도"만 보여준다. 그 둘은 사람이 봐야 고칠 수
 * 있는 사실인데, 크래시가 아니니 크래시 리포트에는 영영 안 나타난다.
 *
 * ## 전역인 이유
 *
 * Crashlytics 자체가 프로세스 단위 싱글턴이고, 보고 지점이 의존성을 끌고 다닐 수 없는 자리에
 * 있다 — [com.accentury.app.audio.RecordingEngine]은 ViewModel 기본 생성자를 타고 만들어져
 * Context도 팩토리도 지나지 않는다. 그 하나를 위해 ViewModel 팩토리를 세우는 것보다, 실패해도
 * 되는 부수 기능을 SDK와 같은 모양(전역·조용한 실패)으로 두는 편이 얕다.
 *
 * ## 무엇을 싣지 않는가 (KAN-38 로그 마스킹)
 *
 * 세션 토큰·오디오 바이트·임시 파일 경로는 절대 들어가지 않는다. 여기 들어오는 값은 우리가 코드에
 * 박아 둔 고정 문자열(브리지 메서드명, 녹음 실패 사유)뿐이고, 사용자 입력이나 서버 응답을 그대로
 * 넘기는 경로는 만들지 않는다. `setUserId`도 부르지 않는다 — 익명 계측 규칙이 크래시 쪽에도
 * 그대로 적용된다.
 */
object CrashReports {

    /**
     * 설정이 있는 빌드에서만 값이 있다. 판정 근거는 [EventSink.create]와 같다 —
     * google-services.json이 없으면 FirebaseApp이 초기화되지 않는다.
     *
     * Context를 받지 않으려고 `FirebaseApp.getInstance()`를 쓴다. 초기화되지 않았으면 예외라
     * runCatching이 null로 바꾸고, 그 뒤로는 이 객체 전체가 아무 일도 하지 않는다.
     */
    private val crashlytics: FirebaseCrashlytics? by lazy {
        runCatching {
            FirebaseApp.getInstance()
            FirebaseCrashlytics.getInstance()
        }.getOrNull()
    }

    /**
     * 앱 시작 시 커스텀 키 하나를 걸어 둔다 (`AccenturyApplication`).
     *
     * 브리지 계약 버전을 싣는 이유: 이 앱에서 나온 보고는 대부분 웹과 네이티브가 서로 다른 계약을
     * 들고 있을 때 생기는데, 리포트만 봐서는 어느 쪽 버전인지 알 수 없다. 비식별 상수라 익명
     * 규칙에도 걸리지 않는다.
     */
    fun install() {
        val client = crashlytics ?: return
        runCatching { client.setCustomKey(KEY_BRIDGE_VERSION, BRIDGE_CONTRACT_VERSION) }
    }

    /**
     * 웹이 보낸 브리지 메시지를 읽지 못했다 (`AccenturyBridge`).
     *
     * allowlist를 통과한 페이지만 여기까지 오므로, 이 보고는 곧 **우리 웹과 우리 앱이 계약을
     * 다르게 알고 있다**는 뜻이다 — 남이 보낸 잡음이 아니라 배포 스큐의 신호다.
     *
     * @param method 브리지 메서드 이름. payload는 싣지 않는다 — 세션 토큰이 지나는 계약은 아니지만,
     *   "무엇이 왔는지"를 남기기 시작하면 다음 필드가 늘 때 그 규칙이 함께 넘어간다
     */
    fun recordBridgeParseFailure(method: String) {
        record("bridge_parse_failed: $method")
    }

    /**
     * 녹음 엔진이 마이크를 열지 못했다 ([com.accentury.app.audio.RecordingEngine]).
     *
     * 사용자에게는 "녹음에 실패했어요"까지만 보이고 그 뒤에 무엇이 있었는지는 남지 않는다. 기기·OS
     * 조합에 따라 갈리는 실패라(마이크 점유, 초기화 거부) 분포를 봐야 대응이 갈린다.
     *
     * @param reason `AudioRecorder.CaptureException`이 든 우리 문구. 오디오 바이트나 파일 경로는
     *   이 예외에 애초에 실리지 않는다
     */
    fun recordCaptureFailure(reason: String) {
        record("audio_capture_failed: $reason")
    }

    private fun record(message: String) {
        val client = crashlytics ?: return
        // 계측·보고가 사용자 흐름을 끊으면 안 된다 (EventSink.log와 같은 규칙).
        runCatching { client.recordException(NonFatal(message)) }
    }

    /**
     * 보고용 예외. 스택은 호출 지점에서 잡히므로 Crashlytics가 자리별로 묶어 준다 — 메시지를
     * 고정 어휘로 두는 것이 그 묶음을 읽을 수 있게 만든다.
     */
    private class NonFatal(message: String) : RuntimeException(message)

    private const val KEY_BRIDGE_VERSION = "bridge_contract_version"
}
