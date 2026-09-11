import AppTrackingTransparency
import UIKit

/// ATT(App Tracking Transparency) 프롬프트 한 자리 (KAN-196). ``AdsController``만 부른다.
///
/// 프롬프트는 **앱이 active일 때만** 뜬다 — 그 밖의 상태(런치 직후 inactive, 백그라운드)에서 부르면
/// iOS가 시트를 띄우지 않고 `.notDetermined`를 돌려주거나 조용히 묵살한다. 앱 시작 시점의 요청
/// (`AdsController.start`, 이미 허용한 사용자가 설정에서 ATT를 초기화한 경우)이 정확히 그 구간이라
/// active가 될 때까지 한 번 기다린다.
///
/// 결과는 **저장하지도, `setAdConsent`로 접지도 않는다** (webview-bridge.md §8.5). 시트 상태는
/// 사용자가 시트에서 고른 값이어야 「맞춤형 광고 설정」이 보여 주는 상태와 맞는다. ATT를 거부하면
/// SDK가 IDFA를 못 읽어 맞춤형 요청이 사실상 비맞춤이 되는데, 그건 SDK 몫이다 — 우리 저장값은
/// 여전히 `granted`이고 요청도 맞춤형으로 나간다.
@MainActor
enum TrackingAuthorization {

    /// 아직 안 물어본 상태인가. 물어본 적 있으면(허용·거부·제한) 다시 뜨지 않는다 — iOS가 한 번만 묻는다.
    static var isUndetermined: Bool {
        ATTrackingManager.trackingAuthorizationStatus == .notDetermined
    }

    /// 프롬프트가 끝나기를 기다리는 완료 콜백들. 하나가 떠 있는 동안 두 번째 요청이 오면
    /// (SDK 초기화 완료와 시트 선택이 겹치는 경우) 시스템에 다시 묻지 않고 같은 결과를 기다린다.
    private static var pending: [@MainActor () -> Void] = []

    /// 아직 안 물어봤으면 묻고, 답이 나오면(또는 이미 답이 있으면) `completion`을 메인에서 부른다.
    static func requestIfUndetermined(completion: @escaping @MainActor () -> Void) {
        guard isUndetermined else {
            completion()
            return
        }
        pending.append(completion)
        if pending.count > 1 { return }
        whenActive { requestNow() }
    }

    private static func requestNow() {
        ATTrackingManager.requestTrackingAuthorization { _ in
            // 완료 콜백의 스레드가 문서에 보장돼 있지 않다 — 메인으로 옮긴다.
            Task { @MainActor in
                let callbacks = pending
                pending = []
                callbacks.forEach { $0() }
            }
        }
    }

    /// 지금 active면 바로, 아니면 다음 `didBecomeActive`에 한 번 부른다.
    private static func whenActive(_ body: @escaping @MainActor () -> Void) {
        if UIApplication.shared.applicationState == .active {
            body()
            return
        }
        Task { @MainActor in
            // 첫 통지 하나만 기다린다 — 알림 시퀀스라 옵저버 토큰을 들고 지울 일이 없다.
            for await _ in NotificationCenter.default.notifications(named: UIApplication.didBecomeActiveNotification) {
                break
            }
            body()
        }
    }
}
