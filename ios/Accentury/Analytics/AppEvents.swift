import AccenturyCore
import Foundation
import os

/// 앱 안 익명 이벤트 창구 (FR-SH-06, KAN-33). 안드로이드 `analytics/AppEvents.kt`의 `EventSink`와
/// 같은 자리다.
///
/// 웹의 `analytics/track.ts`가 하는 일과 같다 — 화면이 "이 일이 일어났다"고 말할 창구 하나이고,
/// 실제 전송(Firebase)은 이 프로토콜 뒤에 있다 (``FirebaseEventSink``). 그렇게 나눈 이유도 웹과
/// 같다: 보내는 방법이 바뀌어도 화면 코드는 그대로다. 설정이 없는 빌드에서 ``ConsoleEventSink``로
/// 내려가는 것도 화면이 모르는 사정이다.
///
/// 앱 안 이벤트를 웹이 아니라 네이티브가 보내는 것이 KAN-33의 결정이다 — 같은 사건이 두 경로로
/// 두 번 세어지면 안 되므로, WebView 안의 `track`은 웹 단독 실행에서만 gtag로 간다. 앱 안에서
/// 웹이 세는 사건은 브리지를 건너 이 창구로 들어온다 (``BridgeDispatcher`` `logEvent`).
///
/// ## 익명 규칙
///
/// 세션 id·세션 토큰·문항 내용·점수 원값은 파라미터에 싣지 않는다. 하나라도 섞이면 "익명 계측"이라는
/// 전제가 깨지고, 그 값들이 계측 서버에 남을 이유도 없다. `setUserID`를 부르는 곳도 앱 전체에
/// 없다 — 광고 식별자를 아예 링크하지 않는 이유는 ``FirebaseSetup`` 주석에 있다.
///
/// **등급 코드는 예외다.** `tier_assigned` 하나에만 `tier_code`와 종합 점수의 10점 단위 버킷이
/// 실린다 (FR-AN-09, 정본은 `web/src/analytics/events.ts`). 등급은 5개뿐인 집계 축이고 점수는
/// 10점 눈금으로 뭉개져 개인을 특정할 수 없다 — 이 둘이 없으면 KAN-21의 "등급 분포 편향" 트리거를
/// 판단할 계기판이 아예 없다. 그 이벤트를 보내는 것은 결과 화면(웹)이라 앱에서는 브리지를 건너 온다.
protocol EventSink {

    /// 이벤트 하나를 흘려보낸다. **절대 던지지 않는다** — 호출자는 전부 사용자 흐름의 한복판이라
    /// (공유 버튼 탭, 카톡 전환, 재녹음) 계측값 하나 때문에 그 흐름이 끊기면 안 된다. 계측은
    /// 실패해도 되는 일이고 나머지는 아니다 (`track.ts`와 같은 판단이다).
    ///
    /// - Parameters:
    ///   - name: GA4 스타일 snake_case 이벤트명 (``ShareEvents``·``RecordingEvents`` 참고)
    ///   - params: 이벤트에 실을 값. 익명 규칙을 지키는 값만 넣는다
    func log(_ name: String, _ params: [String: EventParam])
}

extension EventSink {

    /// 파라미터 없는 이벤트. 프로토콜 요구사항에 기본값을 달 수 없어(Swift 제약) 확장으로 뺐다 —
    /// 안드로이드가 `fun interface`를 SAM으로 남기려고 확장 함수를 쓴 것과 같은 자리다.
    func log(_ name: String) { log(name, [:]) }
}

/// 설정(GoogleService-Info.plist)이 없는 빌드의 기본값 — 로그에만 남긴다.
///
/// 웹에서 태그가 없을 때 `track`이 아무 데도 보내지 않는 것과 같은 자리이고, 안드로이드
/// `LogcatEventSink`의 짝이다. 개발에서 이벤트가 실제로 도는지 눈으로 확인할 유일한 통로이기도 하다.
///
/// 파라미터 **값**은 남기지 않고 이름만 남긴다. 이 sink가 도는 자리는 설정이 없는 개발·CI 빌드라
/// 값이 위험할 일은 없지만, 로그에 무엇을 싣는지의 규칙은 sink마다 달라지지 않는 편이 낫다
/// (KAN-38 마스킹 원칙).
struct ConsoleEventSink: EventSink {

    private static let logger = Logger(subsystem: "com.accentury.app", category: "analytics")

    func log(_ name: String, _ params: [String: EventParam]) {
        let keys = params.keys.sorted().joined(separator: ",")
        Self.logger.debug("event \(name, privacy: .public) params=[\(keys, privacy: .public)]")
    }
}
