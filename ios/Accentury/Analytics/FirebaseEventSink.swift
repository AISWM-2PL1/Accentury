import AccenturyCore
import FirebaseAnalytics
import FirebaseCore
import Foundation
import os

/// Firebase를 쓸 수 있는 빌드인가 — **설정 유무를 판정하는 유일한 자리다** (KAN-33).
///
/// 안드로이드에서는 이 판정이 저절로 생긴다. google-services 플러그인이 만든 리소스가 있으면 SDK의
/// ContentProvider가 앱 시작 시점에 초기화를 끝내 놓고, 없으면 `FirebaseApp.getApps`가 빈 목록이다.
/// iOS에는 그런 자동 초기화가 없어서 우리가 `FirebaseApp.configure()`를 불러야 하는데, **설정
/// 파일이 없으면 그 호출이 앱을 죽인다** — 그게 SDK의 기본 동작이다. 그래서 부르기 전에 번들에
/// 파일이 있는지 먼저 본다.
///
/// **파일이 없는 것이 정상 상태다.** `AppConfig.kakaoNativeAppKey`가 nil인 것과 같은 판단이다 —
/// 갓 클론한 기계와 CI에는 이 파일이 없고, 그 상태로도 빌드·테스트·실행이 전부 되어야 한다.
/// 다만 카카오 키와 갈리는 지점이 있다: 카카오는 키가 없으면 공유 통로 하나가 시트로 내려갈 뿐이고,
/// 여기는 계측과 크래시 보고가 통째로 꺼진다.
///
/// 판정을 여기 하나로 모으는 이유도 카카오 분기와 같다: "설정이 없다"를 화면마다 다시 물으면 어떤
/// 화면은 묻는 것을 잊고, 그 화면만 초기화되지 않은 SDK를 부르게 된다. 그 접근은 그 자체로 사고다.
///
/// ## 광고 식별자를 링크하지 않는다
///
/// 안드로이드는 매니페스트의 `google_analytics_adid_collection_enabled=false`로 끄지만, iOS의 IDFA
/// 수집은 설정이 아니라 **무엇을 링크했는가**로 갈린다. 그래서 SwiftPM product를
/// `FirebaseAnalyticsCore`로 고른다 (`ios/project.yml`) — 기본 `FirebaseAnalytics` product가 끌고
/// 오는 `GoogleAppMeasurement`(IDFA 수집·전환 측정 포함) 대신 `GoogleAppMeasurementCore`를 물어
/// AdSupport·AppTrackingTransparency가 바이너리에 아예 들어오지 않는다. 켤 수 있는 코드가 없는
/// 것이 플래그로 끄는 것보다 강한 보장이고, App Store 개인정보 라벨(KAN-175)에 적을 항목도 그만큼 준다.
///
/// 광고 개인화 동의 기본값(`GOOGLE_ANALYTICS_DEFAULT_ALLOW_AD_*`)은 Info plist가 끈다. 링크를
/// 안 했으니 실질적으로는 이미 꺼진 상태지만, 웹에서 `allow_google_signals`를 끈 것과 같은 요구를
/// 설정에도 한 번 더 적어 둔다.
enum FirebaseSetup {

    private static let logger = Logger(subsystem: "com.accentury.app", category: "analytics")

    /// 초기화가 실제로 끝났는가. ``start()``를 부르기 전에는 늘 false다.
    private(set) static var isReady = false

    /// 앱 시작에서 한 번 부른다 (``AccenturyApp``). 안드로이드 `AccenturyApplication.onCreate` 자리.
    ///
    /// 여러 번 불려도 안전하다 — 두 번째 호출은 ``isReady``에서 되돌아간다. `FirebaseApp.configure()`를
    /// 두 번 부르면 SDK가 경고를 찍고 무시하는데, 그 경고가 로그에 남을 이유가 없다.
    ///
    /// 파일이 있는데 내용이 우리 번들 id와 어긋나는 경우는 막지 않는다. 그건 잘못 받아 온 설정
    /// 파일이고, 조용히 계측만 빠진 채로 도는 것보다 SDK가 시끄럽게 실패하는 편이 낫다 — 그 상태를
    /// 사람이 알아차릴 수 있는 유일한 통로가 그 실패다.
    static func start() {
        guard !isReady else { return }
        guard Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil else {
            logger.debug("GoogleService-Info.plist 없음 — 계측·크래시 보고를 끈 채로 실행한다")
            return
        }
        FirebaseApp.configure()
        isReady = FirebaseApp.app() != nil
    }
}

/// 이벤트를 Firebase Analytics로 흘려보내는 ``EventSink`` (KAN-33).
///
/// SDK가 붙여 주는 축(기기·OS·앱 버전·앱 인스턴스)이 이 sink가 존재하는 이유다. 앱 안 이벤트를
/// WebView의 gtag로 보내면 그 축이 없는 데다 앱 사용자가 웹 트래픽으로 세어진다 (`track.ts`).
///
/// 상태가 없다 — `Analytics`가 전부 타입 메서드라 들고 있을 인스턴스가 없기 때문이고, 그래서
/// 화면이 이 값을 오래 붙들 이유도 없다 (안드로이드는 `FirebaseAnalytics` 인스턴스를 들고 다닌다).
///
/// 안드로이드가 여기에 두른 `try/catch(Throwable)`이 없다. 그쪽은 Play 서비스 부재나 초기화 경합에서
/// `NoClassDefFoundError`가 올라오는 경로가 있어서인데, Swift에는 그에 해당하는 통로가 없다 —
/// `Analytics.logEvent`는 `throws`가 아니고, SDK 안에서 나는 ObjC 예외는 어차피 Swift로 잡히지
/// 않는다. 그래서 "계측이 사용자 흐름을 끊지 않는다"는 규칙을 여기서 지키는 방법은 초기화되지
/// 않은 SDK를 아예 부르지 않는 것뿐이고, 그 판정이 ``makeEventSink()``다.
struct FirebaseEventSink: EventSink {

    func log(_ name: String, _ params: [String: EventParam]) {
        /*
         * 타입이 여기서 갈린다. 숫자를 문자열로 넣으면 GA4에서 측정항목이 아니라 차원이 되어
         * 평균·P95를 낼 수 없다 (``EventParam`` 주석). `NSNumber`로 감싸는 이유는 파라미터 값이
         * `Any`라서다 — Swift `Int64`를 그대로 넣어도 브리징되지만, 정수와 실수를 각각 어떤
         * 숫자로 넘겼는지가 이 자리에 드러나 있어야 나중에 읽는 사람이 위 규칙을 확인할 수 있다.
         */
        var parameters: [String: Any] = [:]
        for (key, param) in params {
            switch param {
            case let .text(value): parameters[key] = value
            case let .count(value): parameters[key] = NSNumber(value: value)
            case let .amount(value): parameters[key] = NSNumber(value: value)
            }
        }
        Analytics.logEvent(name, parameters: parameters)
    }
}

/// 이 빌드가 쓸 sink를 고른다. 설정이 없으면 ``ConsoleEventSink``다.
///
/// 판정 자체는 ``FirebaseSetup``이 이미 끝냈고 여기는 그 결과를 읽기만 한다 — 안드로이드
/// `EventSink.create(context)`가 서는 자리이고, 화면은 어느 쪽을 받았는지 모른다.
func makeEventSink() -> EventSink {
    FirebaseSetup.isReady ? FirebaseEventSink() : ConsoleEventSink()
}
