import Foundation

/// 빌드 구성이 정한 주소를 앱 코드에 넘겨주는 한 곳.
/// 안드로이드의 `BuildConfig.WEB_URL` / `BuildConfig.API_BASE_URL`에 해당한다 — 다만 iOS에는
/// 생성된 상수 클래스가 없어서 xcconfig → Info.plist → `Bundle.main.infoDictionary` 경로로 흐른다.
///
/// 값이 비어 있으면 기본값으로 때우지 않고 즉시 죽는다. 주소가 빠진 채로 뜬 앱은 "빈 화면"이나
/// "네트워크 오류"처럼 원인이 한참 먼 증상으로 나타나는데, 그건 빌드 설정 실수라 실행 첫 순간에
/// 알아야 고칠 수 있다.
enum AppConfig {

    /// WebView가 열 화면의 출처. 예) Debug `http://localhost:5173`, Release `https://accentury.app`.
    static let webURL: String = required("WEB_URL")

    /// 네이티브(세션·업로드)가 때릴 API 출처. 배포에서는 [webURL]과 같은 CloudFront 단일 출처다(KAN-126).
    static let apiBaseURL: String = required("API_BASE_URL")

    /// 카카오 네이티브 앱 키 (KAN-180). **nil이 정상 상태다.**
    ///
    /// [webURL]·[apiBaseURL]과 달리 `required`가 아닌 이유: 주소가 빠진 앱은 아무것도 못 하지만,
    /// 카카오 키가 빠진 앱은 공유가 OS 공유 시트로 갈 뿐 나머지가 멀쩡하다. 키 없이도 빌드와
    /// 실행이 되어야 한다는 건 티켓 요구이기도 하다 — 레포에 키를 두지 않으므로 갓 클론한
    /// 기계의 기본 상태가 곧 이 nil이다.
    ///
    /// 값의 사슬은 Config/Base.xcconfig의 `KAKAO_NATIVE_APP_KEY` → Info-*.plist → 여기다.
    /// 안드로이드의 `BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()` 분기와 같은 자리이고,
    /// 이 값이 nil이면 ``AccenturyApp``이 `KakaoSDK.initSDK`를 건너뛴다.
    static let kakaoNativeAppKey: String? = value(for: "KAKAO_NATIVE_APP_KEY", in: Bundle.main.infoDictionary)

    /// 웹에 스큐 협상용으로 알리는 앱 버전 (`CFBundleShortVersionString` = MARKETING_VERSION).
    static let appVersionName: String = value(for: "CFBundleShortVersionString", in: Bundle.main.infoDictionary) ?? "0"

    /// Info.plist 한 칸을 읽어 정규화한다. 없거나 공백뿐이면 nil.
    /// 끝의 `/`를 떼는 이유: 뒤에 쿼리·경로를 잇는 쪽(WebConfig.buildWebUrl 등)이 구분자를 직접 붙이므로
    /// 여기서 정리해 두지 않으면 `.../?bridge=1` 같은 이중 구분자가 나온다.
    static func value(for key: String, in info: [String: Any]?) -> String? {
        guard let raw = info?[key] as? String else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var normalized = trimmed
        while normalized.hasSuffix("/") {
            normalized.removeLast()
        }
        return normalized.isEmpty ? nil : normalized
    }

    private static func required(_ key: String) -> String {
        guard let value = value(for: key, in: Bundle.main.infoDictionary) else {
            preconditionFailure(
                "Info.plist에 \(key)가 없다. ios/Accentury/Config의 xcconfig와 Info-*.plist를 확인하라."
            )
        }
        return value
    }
}
