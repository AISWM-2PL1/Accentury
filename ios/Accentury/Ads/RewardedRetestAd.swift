import AccenturyCore
import GoogleMobileAds
import os
import UIKit

/// 재응시 보상형 광고의 SDK 결선 (KAN-196, webview-bridge.md §8.2). 결정은 Core의
/// ``AccenturyCore/RewardedRetestGate``가 하고 여기는 `RewardedAd`의 로드·표시·콜백을 그 상태
/// 머신의 메서드로 옮길 뿐이다. 안드로이드 `RewardedRetestAd.kt`의 이식본이다.
///
/// 로드·소진·재로드 규칙은 ``InterstitialGate``와 같다 — 미리 받아 두고, 띄우면 참조를 비우고,
/// 닫히면 다음 것을 받는다. 로드 실패에 재시도 루프가 없는 것도 같다.
///
/// 전부 메인 스레드다 (브리지 메시지, SDK 콜백).
///
/// - Parameter gate: 상태 머신. 기본값은 새 인스턴스이고 테스트는 자기 것을 넣는다
@MainActor
final class RewardedRetestAd: NSObject, FullScreenContentDelegate {

    private static let logger = Logger(subsystem: "com.accentury.app", category: "ads")

    private let adUnitId: String
    private let consent: () -> AdConsent
    private let gate: RewardedRetestGate
    private var loaded: RewardedAd?
    private var loading = false

    /// 지금 떠 있는 광고의 결과를 받을 상대. 표시 한 건에 한 쌍이고 닫히면 비운다.
    private var onProceed: (() -> Void)?
    private var onDismissed: (() -> Void)?

    init(adUnitId: String, consent: @escaping () -> AdConsent, gate: RewardedRetestGate = RewardedRetestGate()) {
        self.adUnitId = adUnitId
        self.consent = consent
        self.gate = gate
    }

    /// 다음 재응시를 위해 미리 받아 둔다. 이미 있거나 받는 중이면 아무 일도 없다.
    func preload() {
        if loaded != nil || loading { return }
        loading = true
        RewardedAd.load(with: adUnitId, request: AdRequests.make(consent: consent())) { [weak self] ad, error in
            // 완료 핸들러는 메인으로 오지만 `@Sendable`로 선언돼 있어 격리를 타입으로 다시 못박는다 —
            // 로드 상태는 메인에서만 바뀐다는 계약을 컴파일러가 확인하게 둔다.
            Task { @MainActor in
                self?.onLoaded(ad, error: error)
            }
        }
    }

    private func onLoaded(_ ad: RewardedAd?, error: Error?) {
        loading = false
        guard let ad else {
            loaded = nil
            Self.logger.warning("보상형 광고 로드 실패: \(error?.localizedDescription ?? "?", privacy: .public)")
            return
        }
        loaded = ad
    }

    /// 받아 둔 광고를 버린다 — 동의가 바뀌었을 때 (``AdsController/setConsent(_:)``).
    func discard() {
        loaded = nil
    }

    /// 재응시 요청 한 건을 게이트에 태운다.
    ///
    /// - Parameters:
    ///   - onProceed: 재응시를 진행하라 — 기존 `beginRetest()` → 세션 생성 흐름이 여기서 시작된다.
    ///     광고를 끝까지 봤을 때·광고가 없을 때·표시 실패 때 **정확히 한 번** 불린다
    ///   - onDismissed: 광고를 중도에 닫았다 — 웹에 `AD_DISMISSED`를 회신할 자리
    func run(onProceed: @escaping () -> Void, onDismissed: @escaping () -> Void) {
        switch gate.request(loaded: loaded != nil) {
        case .ignored:
            return

        case .proceed:
            // 광고 없이 통과한다 (§8.2 "광고 로드 실패는 막지 않는다"). 다음 재응시를 위해
            // 지금 한 번 더 받아 둔다 — 로드 실패 뒤의 유일한 재시도 기회다.
            preload()
            onProceed()

        case .showAd:
            // request가 showAd를 돌려줬다는 것은 loaded가 있었다는 뜻이다.
            guard let ad = loaded else { return }
            loaded = nil
            self.onProceed = onProceed
            self.onDismissed = onDismissed
            ad.fullScreenContentDelegate = self
            ad.present(from: TopViewController.current()) { [weak self] in
                // 보상 종류·양은 보지 않는다 — 우리 보상은 "재응시 한 번"이고 그 사실은 콜백이
                // 왔다는 것 자체다. 핸들러는 메인 스레드에서 온다 (SDK 문서: present는 메인 전용).
                guard let self else { return }
                if self.gate.onEarnedReward() == .proceed { self.onProceed?() }
            }

        // 결정을 돌려주는 자리에서 나올 수 없는 값이다 — 콜백에서만 나온다.
        case .dismissed:
            return
        }
    }

    // MARK: FullScreenContentDelegate

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        if gate.onDismissed() == .dismissed { onDismissed?() }
        clearHandlers()
        preload()
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        Self.logger.warning("보상형 광고 표시 실패: \(error.localizedDescription, privacy: .public)")
        if gate.onShowFailed() == .proceed { onProceed?() }
        clearHandlers()
        preload()
    }

    private func clearHandlers() {
        onProceed = nil
        onDismissed = nil
    }
}
