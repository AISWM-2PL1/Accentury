import AccenturyCore
import FirebaseCrashlytics
import Foundation

/// 비치명 오류 보고 (KAN-33). 크래시가 아니라 **조용히 실패하는 자리**를 남기는 통로다.
/// 안드로이드 `analytics/CrashReports.kt`의 이식본이고 같은 두 지점에서 불린다.
///
/// 우리 앱의 실패 경로는 대부분 사용자에게 화면으로 보이지 않는다 — 브리지가 못 읽는 메시지는
/// 그냥 버려지고, 마이크가 안 열리면 화면은 "다시 시도"만 보여준다. 그 둘은 사람이 봐야 고칠 수
/// 있는 사실인데, 크래시가 아니니 크래시 리포트에는 영영 안 나타난다.
///
/// ## 전역인 이유
///
/// Crashlytics 자체가 프로세스 단위 싱글턴이고, 보고 지점이 협력자를 주입받을 수 없는 자리에 있다 —
/// ``BridgeDispatcher``는 클로저 목록이 곧 계약이라 계측 하나 때문에 늘릴 자리가 아니고,
/// ``RecordingModel``은 화면이 기본 생성자로 만든다. 그 둘을 위해 주입 경로를 세우는 것보다,
/// 실패해도 되는 부수 기능을 SDK와 같은 모양(전역·조용한 실패)으로 두는 편이 얕다.
///
/// ## 무엇을 싣지 않는가 (KAN-38 로그 마스킹)
///
/// 세션 토큰·오디오 바이트·임시 파일 경로는 절대 들어가지 않는다. 여기 들어오는 값은 우리가 코드에
/// 박아 둔 고정 문자열(브리지 메서드명, 녹음 실패 사유)뿐이고, 사용자 입력이나 서버 응답을 그대로
/// 넘기는 경로는 만들지 않는다. `setUserID`도 부르지 않는다 — 익명 계측 규칙이 크래시 쪽에도
/// 그대로 적용된다.
enum CrashReports {

    /// 도메인을 종류별로 나누는 이유는 Crashlytics가 비치명 오류를 **도메인과 코드로 묶기** 때문이다.
    /// 하나로 두면 브리지 스큐와 마이크 실패가 한 이슈에 뭉쳐, 어느 쪽이 늘었는지 목록에서 보이지 않는다.
    private static let bridgeParseDomain = "accentury.bridge_parse_failed"
    private static let captureDomain = "accentury.audio_capture_failed"

    /// 브리지 계약 버전을 실어 두는 커스텀 키.
    private static let bridgeVersionKey = "bridge_contract_version"

    /// 앱 시작에서 커스텀 키 하나를 걸어 둔다 (``AccenturyApp``). ``FirebaseSetup/start()`` 다음이다.
    ///
    /// 브리지 계약 버전을 싣는 이유: 이 앱에서 나온 보고는 대부분 웹과 네이티브가 서로 다른 계약을
    /// 들고 있을 때 생기는데, 리포트만 봐서는 어느 쪽 버전인지 알 수 없다. 비식별 상수라 익명
    /// 규칙에도 걸리지 않는다.
    static func install() {
        guard FirebaseSetup.isReady else { return }
        Crashlytics.crashlytics().setCustomValue(bridgeContractVersion, forKey: bridgeVersionKey)
    }

    /// 웹이 보낸 브리지 메시지를 읽지 못했다 (``BridgeDispatcher``).
    ///
    /// allowlist를 통과한 페이지만 여기까지 오므로, 이 보고는 곧 **우리 웹과 우리 앱이 계약을
    /// 다르게 알고 있다**는 뜻이다 — 남이 보낸 잡음이 아니라 배포 스큐의 신호다.
    ///
    /// - Parameter method: 브리지 메서드 이름. payload는 싣지 않는다 — 세션 토큰이 지나는 계약은
    ///   아니지만, "무엇이 왔는지"를 남기기 시작하면 다음 필드가 늘 때 그 규칙이 함께 넘어간다
    static func recordBridgeParseFailure(_ method: String) {
        record(domain: bridgeParseDomain, detail: method)
    }

    /// 녹음 엔진이 마이크를 열지 못했거나 캡처가 도중에 끊겼다 (``RecordingModel``).
    ///
    /// 사용자에게는 "녹음에 실패했어요"까지만 보이고 그 뒤에 무엇이 있었는지는 남지 않는다. 기기·OS
    /// 조합에 따라 갈리는 실패라(마이크 점유, 세션 활성화 거부, 변환기 생성 실패) 분포를 봐야
    /// 대응이 갈린다.
    ///
    /// - Parameter reason: ``AccenturyCore/RecordingUiState`` `.failed`가 든 우리 문구.
    ///   `CaptureError`가 만드는 고정 어휘이고, 오디오 바이트나 파일 경로는 거기 애초에 실리지 않는다
    static func recordCaptureFailure(_ reason: String) {
        record(domain: captureDomain, detail: reason)
    }

    /// - Parameter detail: 이슈 목록의 부제로 보이는 한 줄. 도메인이 묶음을 정하고 이 값이 그 안을 가른다.
    private static func record(domain: String, detail: String) {
        guard FirebaseSetup.isReady else { return }
        Crashlytics.crashlytics().record(
            error: NSError(domain: domain, code: 0, userInfo: [NSLocalizedDescriptionKey: detail])
        )
    }
}
