import AccenturyCore
import Combine
import Foundation

/// 게이트 화면의 상태 보유자. `AccenturyCore`의 순수 상태 머신(`MicPermissionController`)을
/// SwiftUI가 볼 수 있는 `@Published`로 감싸고, iOS API 결선(권한 조회·요청)과 저장을 맡는다.
///
/// 안드로이드에서 `rememberSaveable(saver = MicPermissionController.saver(::isGranted))` 한 줄이
/// 하던 일이 여기서 두 조각으로 나뉜다 — 저장은 `UserDefaults`, 복원 대조는 `restored(saved:currentlyGranted:)`.
/// Compose의 저장 봉투(Bundle)는 프로세스가 죽어도 OS가 들고 있어 주지만 iOS에는 그 자리가
/// 없어서, 상태가 프로세스보다 오래 살아야 하는 이유(영구 거부의 "설정 딥링크만" 제약)를
/// 직접 디스크에 적는다.
@MainActor
final class PermissionGateModel: ObservableObject {

    /// 저장 키. 값은 `MicPermissionState.saveKey`(안드로이드와 같은 문자열)다.
    static let storageKey = "mic_permission_state"

    @Published private(set) var state: MicPermissionState

    /// OS 팝업 결과를 기다리는 중. 버튼을 잠그고 진행 표시를 띄우는 데 쓴다.
    @Published private(set) var isRequesting = false

    /// 게이트 통과를 이미 통보했는가 (``consumeGrantedDelivery()``).
    private var grantedDelivered = false

    private let controller: MicPermissionController
    private let defaults: UserDefaults
    private let isGranted: () -> Bool
    private let requestFromOS: () async -> Bool

    /// - Parameters:
    ///   - isGranted: 지금의 실제 권한. 기본값은 OS 조회고, 테스트가 `AVAudioSession`을
    ///     건드리지 않도록 클로저로 뚫어 둔다.
    ///   - requestFromOS: OS 팝업. 같은 이유로 주입 가능하다.
    init(
        defaults: UserDefaults = .standard,
        isGranted: @escaping () -> Bool = { MicPermission.currentStatus().granted },
        requestFromOS: @escaping () async -> Bool = { await MicPermission.request() }
    ) {
        self.defaults = defaults
        self.isGranted = isGranted
        self.requestFromOS = requestFromOS

        // 저장값이 없는 첫 실행은 `fromSaveKey(nil)`이 안내 화면으로 떨어뜨린다. 실제 권한이
        // 이미 허용이면(설치 후 재실행 등) 저장값과 무관하게 통과한다 — 진짜 권한이 이긴다.
        let saved = MicPermissionState.fromSaveKey(defaults.string(forKey: Self.storageKey))
        let controller = MicPermissionController.restored(saved: saved, currentlyGranted: isGranted())
        self.controller = controller
        self.state = controller.state
        persist(controller.state)
    }

    /// 안내 화면·재요청 화면의 버튼. OS 팝업을 띄우고 결과를 상태 머신에 넘긴다.
    ///
    /// `canAskAgain: false`가 상수인 것은 iOS의 규칙이다 — 팝업은 설치당 한 번뿐이라
    /// 거부는 곧 영구 거부다 (`MicPermission` 주석). 안드로이드처럼 `denied`에 머무는
    /// 중간 단계가 없어서 이 앱의 iOS 화면은 사실상 안내 → 영구거부 두 장으로 돈다.
    ///
    /// ## 늦게 도착한 결과가 실제 권한을 덮지 않는다
    /// 팝업이 떠 있는 동안에도 ``onReturnedToApp()``은 돈다 — 사용자가 설정 앱에 다녀오거나
    /// 앱이 잠깐 뒤로 갔다 오면 그렇다. 그 재대조가 `.granted`를 관측한 뒤에 이 요청의 낡은
    /// `false`가 도착하면, 그대로 적용할 경우 실제로는 허용된 마이크를 영구 거부로 적어 두고
    /// 디스크에까지 남긴다. 그래서 결과를 적용하기 **직전에** 실제 권한을 한 번 더 읽고,
    /// 거부 결과는 실제 권한도 거부일 때만 적용한다 — 게이트 전체를 관통하는 "실제 권한이
    /// 이긴다" 규칙을 결과 경로에도 그대로 건다.
    func requestPermission() async {
        // 요청 하나가 도는 동안 또 걸지 않는다. 팝업은 어차피 한 장이고, 겹쳐 걸면 늦게
        // 끝난 쪽 결과가 앞의 결과를 덮는다.
        guard !isRequesting else { return }
        isRequesting = true
        defer { isRequesting = false }

        let result = await requestFromOS()
        controller.onPermissionResult(granted: result || isGranted(), canAskAgain: false)
        sync()
    }

    /// 앱이 다시 앞으로 나왔을 때(`scenePhase == .active`)의 재확인. 설정에서 허용하고
    /// 돌아온 경우를 잡는다 — 실제 권한이 저장된 상태를 이긴다 (KAN-98에서 배운 것).
    func onReturnedToApp() {
        controller.onReturnedToApp(granted: isGranted())
        sync()
    }

    /// 게이트 통과 통보를 내보낼 차례인지 묻고, 내보냈다고 표시한다. `true`는 딱 한 번만
    /// 돌아온다.
    ///
    /// 뷰의 `.task`에 맡기지 않는 이유: 그 이펙트는 화면이 다시 나타날 때마다 다시 돌고,
    /// SwiftUI가 뷰 값을 다시 만드는 것도 우리가 통제할 수 없다. 통과 통보는 상위 화면을
    /// 바꾸는 사건이라 두 번 나가면 안 되므로, 뷰보다 오래 사는 모델이 그 사실을 기억한다.
    func consumeGrantedDelivery() -> Bool {
        guard state == .granted, !grantedDelivered else { return false }
        grantedDelivered = true
        return true
    }

    /// 상태 머신이 움직인 결과를 화면·디스크로 옮긴다. 값이 그대로면 아무것도 하지 않는다 —
    /// 복귀 재확인은 앱이 앞으로 나올 때마다 도는 자리라, 같은 값을 다시 발행하면 SwiftUI가
    /// 의미 없이 다시 그린다.
    private func sync() {
        guard state != controller.state else { return }
        state = controller.state
        persist(state)
    }

    /// 상태 문자열만 적는다. 권한 자체는 OS가 들고 있고 여기 값은 화면 복원용 힌트라,
    /// 개인정보나 음성에 닿는 것이 하나도 없다.
    private func persist(_ newState: MicPermissionState) {
        defaults.set(newState.saveKey, forKey: Self.storageKey)
    }
}
