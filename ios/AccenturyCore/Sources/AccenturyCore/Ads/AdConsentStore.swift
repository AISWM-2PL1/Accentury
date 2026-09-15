import Foundation

/// 광고 동의의 정본 저장소 (KAN-196, webview-bridge.md §8.1). 안드로이드 `AdConsentStore.kt`.
///
/// 프로토콜로 둔 이유는 안드로이드가 인터페이스로 둔 이유와 같다 — 브리지와 광고 허브는 단위
/// 테스트 대상인데 저장소를 실제 `UserDefaults.standard`에 물리면 테스트가 기계의 상태를 읽고 쓴다.
/// 프로덕션은 ``UserDefaultsAdConsentStore``, 테스트는 값 하나짜리 가짜다.
///
/// 읽기·쓰기 모두 메인 스레드다. 안드로이드는 `getAdConsent`가 JS 스레드에서 동기로 읽지만,
/// iOS는 그 값을 문서에 미리 밀어 넣는 구조라(`BridgeUserScript`의 토큰과 같은 자리) 읽는 쪽도
/// 네이티브 메인 스레드다.
public protocol AdConsentStore {
    /// 저장된 적 없으면 ``AdConsent/unknown``.
    func read() -> AdConsent

    /// 사용자가 시트에서 고른 값을 적는다. ``AdConsent/unknown``을 쓰는 호출자는 없어야 한다.
    func write(_ consent: AdConsent)
}

/// `UserDefaults` 구현. 키 `ad_consent.state`, 값은 ``AdConsent/bridgeValue`` 문자열 그대로다 —
/// 안드로이드 SharedPreferences(파일 `ad_consent`, 키 `state`)와 **같은 이름·같은 값**이어서
/// 두 플랫폼의 `getAdConsent`가 같은 저장 형식을 말한다 (ads-admob.md §7). iOS에는 파일 단위가
/// 없어 파일명과 키를 점으로 이어 한 키로 만들었다.
///
/// `TestFlowModel`의 저장 키들(`test_flow_*`)과 같은 표준 도메인에 두되 이름으로 갈라 둔다.
/// 안드로이드가 전용 prefs 파일을 쓴 이유(AdMob SDK가 기본 prefs를 훑는다)는 iOS에도 있다 —
/// SDK가 `UserDefaults.standard`에 `GAD*`·`gad_*` 키를 쓴다. 우리 키는 그 접두어와 겹치지 않는다.
///
/// Core(Foundation만 쓰는 순수 계층)에 두는 이유: `UserDefaults`는 Foundation이라 `swift test`가
/// 돈다 — 그래서 "깨진 값은 unknown"까지 시뮬레이터 없이 검증된다. 테스트는 `UserDefaults(suiteName:)`으로
/// 격리된 도메인을 쓴다.
public struct UserDefaultsAdConsentStore: AdConsentStore {

    public static let key = "ad_consent.state"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func read() -> AdConsent {
        guard let raw = defaults.string(forKey: Self.key) else { return .unknown }
        // 깨진 값(구버전 형식 등)은 저장이 없었던 것으로 본다 — 시트를 한 번 더 묻는 편이
        // 모르는 값을 "허용"으로 읽는 것보다 안전하다.
        return AdConsent(bridgeValue: raw) ?? .unknown
    }

    public func write(_ consent: AdConsent) {
        // 되읽기는 메모리 사본에서 즉시 반영된다 (UserDefaults 계약) — 안드로이드 `apply()`와 같다.
        defaults.set(consent.bridgeValue, forKey: Self.key)
    }
}
