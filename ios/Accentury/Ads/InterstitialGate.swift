import AccenturyCore
import GoogleMobileAds
import os
import UIKit

/// 분석 대기 화면의 전면 광고 (KAN-196, webview-bridge.md §8.3). 브리지 `showInterstitialAd`가 여기로 온다.
/// 안드로이드 `InterstitialGate.kt`의 이식본이다.
///
/// **fire-and-forget이다.** 로드된 광고가 없으면 ``show()``는 아무 일도 하지 않고 웹에 회신도 없다 —
/// 대기 화면은 광고가 떴는지에 따라 달라질 것이 없다. 횟수(세션당 한 번)는 웹이 센다
/// (`ads/interstitial.ts`). 여기서는 받은 만큼 띄운다.
///
/// ## 전면 광고 중 폴링
///
/// 안드로이드에서 "광고 아래에서 분석 폴링이 그대로 돈다"고 확인한 사정(§5.1)은 iOS에서도 같다 —
/// 전면 광고는 SDK의 뷰 컨트롤러가 우리 창 위에 present되는 것이고, WKWebView는 그 아래에서
/// 살아 있으며 JS 타이머는 앱이 백그라운드로 내려갈 때만 늦춰진다. 광고를 닫으면 결과 화면이 와 있다.
///
/// ## 스레드
///
/// 전부 메인 스레드다 — `@MainActor`가 그 계약이다. 브리지 메시지(WebKit)와 SDK의 로드 완료·
/// 표시 콜백이 모두 메인으로 온다(SDK v12부터 `FullScreenContentDelegate`가 `@MainActor`다).
///
/// - Parameters:
///   - adUnitId: 전면 광고 단위. 주입되지 않은 빌드는 Google 테스트 단위다 (Base.xcconfig)
///   - consent: 로드 시점의 동의 상태. 요청마다 다시 읽는다 (``AdRequests``)
@MainActor
final class InterstitialGate: NSObject, FullScreenContentDelegate {

    private static let logger = Logger(subsystem: "com.accentury.app", category: "ads")

    private let adUnitId: String
    private let consent: () -> AdConsent
    private var loaded: InterstitialAd?
    private var loading = false
    private let generation = AdLoadGeneration()

    init(adUnitId: String, consent: @escaping () -> AdConsent) {
        self.adUnitId = adUnitId
        self.consent = consent
    }

    /// 다음 표시를 위해 미리 받아 둔다. 이미 있거나 받는 중이면 아무 일도 없다.
    ///
    /// **동의가 `unknown`이면 요청하지 않는다** (``AccenturyCore/shouldRequestAds(_:)``). `AdsController`의
    /// 프리로드만 거르면 ``show()``가 만드는 재로드 경로가 시트 전에 요청을 낸다 — 구동기의 JS click이나
    /// 심 경합으로 `getAdConsent()`가 `""`인 찰나가 그 경로다. 판정은 허브와 게이트가 같은 함수를 쓴다 (P1-1).
    ///
    /// 로드 실패에 재시도 루프를 두지 않는다 — 실패한 자리에서 곧바로 다시 요청하면 무효 트래픽으로
    /// 잡힐 수 있어 SDK 문서가 말리는 패턴이다. 다음 기회는 ``show()``가 만든다: 보여줄 것이 없을 때
    /// 한 번 더 받아 두므로 세션 하나가 광고 없이 지나가더라도 그다음 세션에는 있다.
    func preload() {
        if !shouldRequestAds(consent()) { return }
        if loaded != nil || loading { return }
        loading = true
        // 요청 시점의 세대를 들고 간다 — 로드 중에 동의가 바뀌어 discard()가 세대를 올리면 이 결과는 버린다.
        let token = generation.begin()
        InterstitialAd.load(with: adUnitId, request: AdRequests.make(consent: consent())) { [weak self] ad, error in
            // 완료 핸들러는 메인으로 오지만 `@Sendable`로 선언돼 있어 격리를 타입으로 다시 못박는다 —
            // 로드 상태는 메인에서만 바뀐다는 계약을 컴파일러가 확인하게 둔다.
            Task { @MainActor in
                self?.onLoaded(ad, error: error, token: token)
            }
        }
    }

    private func onLoaded(_ ad: InterstitialAd?, error: Error?, token: Int) {
        // 옛 세대의 결과는 성공·실패 둘 다 버린다 — 이미 다른 조건의 로드가 나가 있거나 곧 나간다 (P1-2).
        guard generation.isCurrent(token) else { return }
        loading = false
        guard let ad else {
            loaded = nil
            // 로드 실패는 정상 경로(무재고)라 Crashlytics로는 보내지 않는다. 오류 설명은 SDK가
            // 만드는 진단 문자열이라 사용자 값이 없다 (KAN-38).
            Self.logger.warning("전면 광고 로드 실패: \(error?.localizedDescription ?? "?", privacy: .public)")
            return
        }
        loaded = ad
    }

    /// 받아 둔 광고를 버린다 — 동의가 바뀌어 요청 조건이 달라졌을 때 (``AdsController/setConsent(_:)``).
    ///
    /// 진행 중인 로드도 버린다 (``AccenturyCore/AdLoadGeneration``): 세대를 올려 그 콜백이 결과를 들이지
    /// 못하게 하고, `loading`을 내려 뒤따르는 ``preload()``가 새 조건으로 곧바로 나가게 한다. 참조만 비우면
    /// 옛 조건으로 나간 로드가 완료돼 `loaded`로 들어온다 (P1-2).
    func discard() {
        generation.invalidate()
        loading = false
        loaded = nil
    }

    /// 로드된 광고가 있으면 띄운다. 없으면 아무 일도 없다 (회신 없음, §8.3).
    func show() {
        guard let ad = loaded else {
            preload()
            return
        }
        // 전면 광고는 일회용이다 — 참조를 먼저 비워 두 번 띄우는 경로를 막는다 (SDK 샘플 그대로).
        loaded = nil
        ad.fullScreenContentDelegate = self
        ad.present(from: TopViewController.current())
    }

    // MARK: FullScreenContentDelegate

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        preload()
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        Self.logger.warning("전면 광고 표시 실패: \(error.localizedDescription, privacy: .public)")
        preload()
    }
}
