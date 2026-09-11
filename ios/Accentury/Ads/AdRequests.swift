import AccenturyCore
import GoogleMobileAds

/// 동의 상태에 맞는 광고 요청을 만든다 (KAN-196). 안드로이드 `AdRequests.kt`의 `buildAdRequest`
/// 자리이고, 판정(`personalizationAllowed`)은 Core에 있어 `swift test`가 못박는다.
///
/// 비맞춤은 AdMob 어댑터 extras `npa=1`이다. Google Mobile Ads SDK 문서 「Forward consent to the
/// Google Mobile Ads SDK」의 방식 그대로이고("any version of the Google Mobile Ads SDK"), 현재
/// 문서는 UMP SDK의 TCF 문자열로 같은 것을 표현하지만 우리는 UMP를 쓰지 않는다 — 동의를 묻는
/// 자리가 웹 시트(KAN-196 2단계)라 SDK의 동의 폼과 이중으로 물을 수 없다.
/// 근거: web.archive.org/web/2021/https://developers.google.com/admob/ios/eu-consent,
/// `Extras.additionalParameters` + `Request.register(_:)`는 developers.google.com/admob/ios/targeting
/// (v12에서 `GADExtras`·`registerAdNetworkExtras`가 이 이름으로 바뀌었다).
///
/// `publisherPrivacyPersonalizationState = .disabled`(요청 설정 전역)를 쓰지 않는 이유: 그건
/// 세션 전체를 비맞춤으로 고정하는 스위치인데, 동의는 인트로 링크로 언제든 바뀐다
/// (§8 「맞춤형 광고 설정」). 요청마다 그 시점의 값으로 나가야 하므로 요청 단위 extras가 맞다.
enum AdRequests {

    static func make(consent: AdConsent) -> Request {
        let request = Request()
        if !personalizationAllowed(consent) {
            let extras = Extras()
            extras.additionalParameters = ["npa": "1"]
            request.register(extras)
        }
        return request
    }
}
