import AccenturyCore
import Combine
import Foundation
import GoogleMobileAds

/// 광고 경로의 프로세스 단위 허브 (KAN-196). SDK 초기화·동의 저장·두 광고 게이트의 프리로드·
/// ATT 프롬프트를 한 곳에서 잇는다. 안드로이드 `AdsController.kt`의 이식본이고, 그쪽이
/// `AccenturyApplication`에 하나 들고 있는 자리를 여기서는 ``shared``가 맡는다 — SwiftUI에는
/// Application 클래스가 없다 (`AccenturyApp.init`이 ``start()``를 부른다).
///
/// ## 프로세스 단위인 이유
///
/// 미리 받아 둔 광고는 화면 재생성을 넘겨야 한다 — 뷰의 `@StateObject`에 두면 `TestFlowView`가
/// 다시 만들어질 때 버려지고 다시 요청하는데, 그 요청은 노출 없는 광고 요청이라 채우기율만 깎는다.
/// 동의 저장소도 정본이 프로세스 밖(UserDefaults)이라 소유자는 프로세스가 맞다.
///
/// ## 프리로드 규칙 (webview-bridge.md §8.5 "동의 → SDK")
///
/// **동의가 ``AccenturyCore/AdConsent/unknown``이면 광고 요청을 아예 내지 않는다.** 시트가 뜨기 전이라
/// 사용자가 아직 아무것도 고르지 않았고, 그 상태로 요청이 나가면 npa를 붙이더라도 "묻기 전에 광고
/// 서버와 통신했다"가 된다. 첫 `setAdConsent`(시트 선택)가 프리로드의 시작점이고, 그 뒤로는
/// 앱 시작마다 저장된 값으로 바로 받아 둔다. 동의가 바뀌면(``setConsent(_:)``) 받아 둔 광고를 버리고
/// 새 조건으로 다시 받는다 — 허용으로 받아 둔 맞춤형 광고가 거부 뒤에 한 번 더 나가면 안 된다.
///
/// ## ATT — 시트 동의와 프롬프트의 순서 (4단계 결정)
///
/// **`setAdConsent("granted")`가 들어온 직후에 `ATTrackingManager.requestTrackingAuthorization`을
/// 부른다.** 순서가 시트 → ATT인 이유:
///
/// - 시트가 먼저여야 ATT 프롬프트가 "왜 지금 묻는지"를 가진다. 사용자가 방금 「맞춤형 광고 허용」을
///   골랐고, iOS는 그 허용을 실행하려면 기기 식별자 접근을 한 번 더 확인한다 — 우리 문구
///   (`NSUserTrackingUsageDescription`)도 그 맥락으로 적혀 있다
/// - **`denied`면 ATT를 부르지 않는다.** 추적 자체가 없는데 추적 허용을 묻는 것은 사용자에게도
///   심사에도 설명이 안 된다. 비맞춤 요청(npa)은 IDFA가 필요 없다
/// - ATT 결과는 저장하지 않고 `setAdConsent`로 접지도 않는다 (§8.5 규칙). 시트 값은 사용자가
///   고른 것이어야 「맞춤형 광고 설정」이 보여 주는 상태와 맞는다. ATT 거부로 SDK가 IDFA를 못
///   읽으면 맞춤형이 사실상 비맞춤이 되는데, 그건 SDK 몫이다
/// - 앱 시작에 이미 `granted`면 ATT가 `notDetermined`일 때만 한 번 더 묻는다 — 설정에서
///   추적을 초기화한 경우다. 이미 답이 있으면(허용·거부) iOS가 다시 띄우지 않으므로 부르지 않는다
/// - 맞춤형 프리로드는 ATT 답이 난 **뒤**에 건다. 프롬프트가 떠 있는 동안 나간 요청은 IDFA
///   없이 나가 첫 광고가 비맞춤이 된다 — 시트에서 허용을 고른 직후의 첫 광고가 그것이다
///
/// ## 스레드
///
/// 전부 메인 스레드다 (`@MainActor`). SDK 초기화(`MobileAds.shared.start`)는 SDK가 내부에서
/// 비동기로 돌고 완료 콜백을 메인으로 준다 — 안드로이드가 별도 스레드를 만든 자리가 여기서는
/// 없다. 초기화 전에 온 프리로드 요청은 버려지고 완료 콜백이 저장된 동의를 보고 다시 건다 —
/// 그래서 "초기화가 끝나기 전에 시트를 골랐다"도 잃지 않는다.
///
/// - Parameter consentStore: 동의 정본. 프로덕션은 UserDefaults, 테스트는 가짜
@MainActor
final class AdsController: ObservableObject {

    /// 프로덕션 결선 — 저장소는 UserDefaults, 광고 단위는 빌드가 주입한 값(없으면 테스트 단위).
    static let shared = AdsController(
        consentStore: UserDefaultsAdConsentStore(),
        interstitialAdUnitId: AppConfig.admobInterstitialId,
        rewardedAdUnitId: AppConfig.admobRewardedId
    )

    let consentStore: AdConsentStore

    /// 저장소의 사본. 브리지 `getAdConsent`가 웹에 건넬 값이 여기서 나온다 — 값이 문서에 매인
    /// JS 변수라(`BridgeUserScript`) 미는 쪽이 변화를 봐야 하고, 그래서 `@Published`다
    /// (`TestFlowModel.bridgeToken`과 같은 구조).
    @Published private(set) var consent: AdConsent

    let interstitial: InterstitialGate
    let rewarded: RewardedRetestAd

    private var sdkReady = false

    /// 광고 호출을 통과시키는 스모크 스위치 (KAN-108 §8 구동기, webview-bridge.md §8.5 하단).
    ///
    /// 구동기는 화면을 JS `.click()`으로 누르므로 광고 위에서는 아무것도 못 누른다 — 전면 광고가
    /// 뜨면 대기 화면 진행이, 보상형이 뜨면 재응시가 광고 위에서 멈춘다. 켜져 있으면 전면은
    /// 아무 일 없이, 보상형은 광고 없이 통과(proceed)한다. 디버그 빌드에서 실행 인자로만 켜지고
    /// 릴리스에는 켜는 코드가 없다.
    private(set) var adsSuppressed = false

    init(consentStore: AdConsentStore, interstitialAdUnitId: String, rewardedAdUnitId: String) {
        self.consentStore = consentStore
        self.consent = consentStore.read()
        self.interstitial = InterstitialGate(adUnitId: interstitialAdUnitId, consent: consentStore.read)
        self.rewarded = RewardedRetestAd(adUnitId: rewardedAdUnitId, consent: consentStore.read)
    }

    /// SDK를 세운다 (`AccenturyApp.init`, Firebase 뒤). 두 번 부르지 않는다는 것은 호출자 책임이다.
    ///
    /// 요청 설정은 초기화 **앞**에 건다 — 초기화 시점에 나가는 첫 요청부터 적용돼야 해서다.
    /// 아동 대상이 아니고(개인정보처리방침 7항) 동의 연령 미만 대상도 아님을 명시로 적는다.
    /// 두 태그는 13.3.0(2026-04-27)에서 `ageRestrictedTreatment`로 대체 예고됐지만 그쪽 enum에는
    /// "제한 없음"을 **명시하는** 값이 없다(`.unspecified`·`.child`·`.teen`뿐, 곧 기본값과 같다).
    /// 명시 선언을 남길 수 있는 것은 옛 태그뿐이라 안드로이드 3단계와 같은 판단으로 그것을 쓰고
    /// (deprecated 경고 두 줄은 의도한 것이다 — Swift에는 `@Suppress`가 없다), SDK가 태그를 지우는
    /// 판이 오면 이 두 줄을 지우는 것으로 끝난다 — 기본값이 이미 "제한 없음"이다.
    /// 근거: developers.google.com/admob/ios/targeting, rel-notes 13.3.0
    ///
    /// 테스트 기기 등록은 하지 않는다 — 디버그·미주입 빌드는 애초에 Google 테스트 광고 단위를
    /// 쓰므로(Base.xcconfig) 어느 기기·시뮬레이터에서든 테스트 광고만 나온다.
    func start() {
        #if DEBUG
        /*
         * 스모크 구동기의 동의 사전 세팅 (webview-bridge.md §8.5 하단). `-AutoFlowDrive`·`-AutoStartSmoke`는
         * 인트로의 [시작하기]를 JS로 누르는데, 동의가 unknown이면 그 앞에 시트가 서고 시트는
         * 구동기가 모른다. `denied`를 미리 적어 시트를 건너뛰고(npa 요청이라 IDFA·ATT도 없다),
         * 광고 호출은 `adsSuppressed`로 통과시킨다 — 테스트 단위라 광고가 떠도 정책 위반은 아니지만
         * 광고 위에서는 구동기가 아무것도 못 누른다.
         *
         * `WebAutoDriver.isEnabled`와 같은 UserDefaults 읽기다. 릴리스 바이너리에는 이 블록이 없다.
         */
        if WebAutoDriver.isEnabled || UserDefaults.standard.bool(forKey: "AutoStartSmoke") {
            consentStore.write(.denied)
            consent = .denied
            adsSuppressed = true
            smokeLog("ADS: smoke consent=denied suppressed=true")
        }
        #endif

        let configuration = MobileAds.shared.requestConfiguration
        configuration.tagForChildDirectedTreatment = false
        configuration.tagForUnderAgeOfConsent = false
        MobileAds.shared.start { [weak self] _ in
            // 완료 콜백은 메인 스레드다 (developers.google.com/admob/ios/quick-start). 그래도
            // 격리를 타입으로 못박는다 — SDK가 스레드를 바꾸는 판이 와도 우리 상태는 메인에서만 바뀐다.
            Task { @MainActor in
                guard let self else { return }
                self.sdkReady = true
                self.preloadAfterTrackingSettled()
            }
        }
    }

    /// 시트에서 고른 값을 적고 프리로드를 (다시) 건다. 브리지 `setAdConsent`가 메인 스레드에서 부른다.
    ///
    /// 같은 값이면 받아 둔 광고를 버리지 않는다 — 인트로 링크로 설정 시트를 열었다가 같은 것을 다시
    /// 고르는 경로가 있고, 그때마다 버리고 다시 받으면 노출 없는 요청만 는다.
    func setConsent(_ next: AdConsent) {
        let previous = consentStore.read()
        consentStore.write(next)
        consent = next
        if previous != next {
            interstitial.discard()
            rewarded.discard()
        }
        preloadAfterTrackingSettled()
    }

    /// 브리지 `showInterstitialAd` (§8.3). 스모크 스위치가 켜져 있으면 아무 일도 없다.
    func showInterstitial() {
        if adsSuppressed { return }
        interstitial.show()
    }

    /// 브리지 `startRetest`의 앞단 (§8.2). 스모크 스위치가 켜져 있으면 광고 없이 곧바로 진행한다.
    func runRewardedRetest(onProceed: @escaping () -> Void, onDismissed: @escaping () -> Void) {
        if adsSuppressed {
            onProceed()
            return
        }
        rewarded.run(onProceed: onProceed, onDismissed: onDismissed)
    }

    /// `granted`이고 ATT가 아직 안 물어본 상태면 먼저 묻고, 답이 난 뒤 프리로드한다. 그 밖에는 바로.
    private func preloadAfterTrackingSettled() {
        guard consentStore.read() == .granted, TrackingAuthorization.isUndetermined else {
            preloadIfConsented()
            return
        }
        TrackingAuthorization.requestIfUndetermined { [weak self] in self?.preloadIfConsented() }
    }

    private func preloadIfConsented() {
        guard sdkReady else { return }
        // 게이트의 preload()도 같은 판정을 한다 — 여기서만 거르면 show/run의 재로드 경로가 샌다 (P1-1).
        if !shouldRequestAds(consentStore.read()) { return }
        interstitial.preload()
        rewarded.preload()
    }
}
