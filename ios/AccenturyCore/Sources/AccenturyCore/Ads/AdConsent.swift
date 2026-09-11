import Foundation

/// 맞춤형 광고 동의 상태 (KAN-196). 브리지 `getAdConsent`/`setAdConsent`가 주고받는 세 값이다
/// (webview-bridge.md §8.5). 안드로이드 `ads/AdConsent.kt`의 이식본이다.
///
/// 정본은 네이티브 저장소다 (``AdConsentStore``). 웹이 들고 있지 않는 이유는 §8.1에 있다 — 소비자
/// (AdMob SDK의 npa 판정·iOS ATT 흐름)가 네이티브에 살고, WebView 저장소는 사용자가 앱 설정에서 지운다.
public enum AdConsent: String, Equatable, Sendable, CaseIterable {

    /// 시트에서 허용을 골랐다. 맞춤형 광고를 요청한다.
    case granted

    /// 시트에서 거부를 골랐다. 비맞춤(npa) 광고만 요청한다.
    case denied

    /// 아직 고른 적이 없다 — 저장소에 값이 없는 상태이자 "시트를 띄워야 한다"는 뜻이다.
    /// 사용자가 고를 수 있는 값이 아니라 ``init(bridgeValue:)``는 받아들이되 ``AdConsentStore/write(_:)``의
    /// 호출자(브리지 `setAdConsent`)가 거른다.
    case unknown

    /// 브리지 계약의 문자열. 웹 래퍼(`bridge.ts`의 `readAdConsent`)가 이 세 값만 계약 안으로 보고
    /// 그 밖은 null(광고 동의 개념 없음)로 접는다. raw value가 곧 계약이라 따로 매핑하지 않는다 —
    /// 안드로이드의 `bridgeValue`와 같은 문자열이어야 두 플랫폼의 `getAdConsent`가 같은 말을 한다.
    public var bridgeValue: String { rawValue }

    /// 브리지 문자열을 상태로 읽는다. 계약 밖 문자열은 nil — 브리지가 조용히 버리고 Crashlytics
    /// 흔적만 남기는 규칙(§5)의 판정 근거다. 대소문자·공백 보정은 하지 않는다: 계약은 정확히
    /// 이 세 문자열이고, 보정을 시작하면 웹과 앱이 다른 계약을 들고도 "동작하는" 상태가 생긴다.
    public init?(bridgeValue: String) {
        self.init(rawValue: bridgeValue)
    }
}

/// 동의 → 맞춤형 허용 판정 (KAN-196, webview-bridge.md §8.5 "동의 → SDK"). 안드로이드 `AdRequests.kt`.
///
/// **``AdConsent/granted``만 true다.** ``AdConsent/denied``는 당연하고, ``AdConsent/unknown``도 false —
/// 시트가 뜨기 전이라 광고 요청 자체가 없어야 하지만(`AdsController`가 그렇게 막는다), 어떤
/// 경로로든 요청이 나간다면 비맞춤이 안전한 쪽이다. "모르면 맞춤형"은 동의 없이 개인화하는 것이라
/// 방침(6항)이 약속한 것과 어긋난다.
///
/// 순수 함수로 뺀 이유는 이 한 줄이 곧 동의의 법적 의미이기 때문이다 — `swift test`가 못박는다.
public func personalizationAllowed(_ consent: AdConsent) -> Bool {
    consent == .granted
}
