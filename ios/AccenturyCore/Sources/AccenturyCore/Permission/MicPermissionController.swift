/// 마이크 권한 게이트의 상태 (KAN-98, ux-ui.md §4-C).
///
/// 거부는 두 갈래로 갈린다 — OS 팝업을 다시 띄울 수 있는 ``denied``와,
/// OS가 재요청을 막아 설정 앱만이 유일한 경로인 ``permanentlyDenied``.
/// 이 구분이 화면의 버튼(다시 요청 vs 설정 이동)을 결정한다.
///
/// **iOS에서 ``denied``는 사실상 나오지 않는다.** 안드로이드는 1회 거부까지는 팝업을 다시
/// 띄워 주지만, iOS는 한 번 거부하면 `AVAudioApplication`이 팝업 자체를 다시 만들지 않는다
/// (`canAskAgain`이 언제나 false). 그런데도 네 갈래를 그대로 둔 이유는 브리지가 웹에 넘길
/// 상태 계약이 두 플랫폼에서 같아야 하기 때문이다 (티켓 §4) — 웹이 플랫폼별 분기를 갖지
/// 않도록 표현 가능한 상태 집합을 맞춰 둔다.
///
/// 저장 키(``saveKey``)는 안드로이드 `rememberSaveable` 저장값과 같은 문자열이다.
public enum MicPermissionState: String, Sendable, CaseIterable {

    /// OS 팝업 전 자체 안내 화면 1장 — 맥락 없는 권한 요청은 거부율이 높다 (ux-ui.md §4-C).
    case rationale

    case granted

    /// 거부됐지만 OS 재요청이 아직 가능하다 — 가치 재설명 후 다시 요청한다.
    /// iOS에서는 도달하지 않는다(위 타입 주석 참고).
    case denied

    /// OS 재요청 불가 — 설정 딥링크로만 진행 가능 (2026-07-27 확정: 건너뛰기 없음).
    case permanentlyDenied = "permanently_denied"

    /// 프로세스 재시작을 건너 상태를 나르는 문자열. 안드로이드 `toSaveKey()`와 값이 같다.
    public var saveKey: String { rawValue }

    /// 모르는 키는 안내 화면으로 떨어뜨린다 — 저장값이 깨졌을 때 게이트가 열리지 않는 것보다
    /// 한 번 더 묻는 쪽이 낫다. 안드로이드 `stateFromSaveKey()`와 같은 판정이다.
    public static func fromSaveKey(_ key: String?) -> MicPermissionState {
        guard let key, let state = MicPermissionState(rawValue: key) else { return .rationale }
        return state
    }
}

/// 마이크 권한 게이트 상태 머신. 권한 결과 콜백·설정 앱 복귀가 여기로 모인다.
///
/// 화면에서 분리한 이유: 거부/영구거부 판별과 설정 복귀 재확인이 게이트 UX의 정확성을
/// 좌우하는데, SwiftUI·AVFoundation에 붙어 있으면 시뮬레이터 없이 테스트할 수 없다
/// (안드로이드가 Compose·ActivityResult에서 떼어낸 것과 같은 이유).
///
/// `ObservableObject`가 아닌 것도 같은 이유다 — Core는 Combine·SwiftUI를 모른다.
/// 상태를 바꾸는 메서드는 전부 동기라, 앱 계층의 `PermissionGateModel`은 호출 직후
/// ``state``를 읽어 `@Published`로 옮긴다 (콜백을 두지 않은 이유: 이 컨트롤러는 액터에
/// 매이지 않았고 모델은 `@MainActor`라, 콜백을 끼우면 그 경계를 넘는 결선이 하나 더 는다).
public final class MicPermissionController {

    public private(set) var state: MicPermissionState

    private init(state: MicPermissionState) {
        self.state = state
    }

    /// 이미 허용된 상태로 게이트에 들어오면 안내 화면 없이 바로 통과한다
    /// ("허용 후에는 재진입 없이 바로 첫 문항으로 이동한다").
    public convenience init(initiallyGranted: Bool) {
        self.init(state: initiallyGranted ? .granted : .rationale)
    }

    /// OS 권한 팝업 결과.
    ///
    /// - Parameter canAskAgain: 거부 직후 팝업을 한 번 더 띄울 수 있는가. 안드로이드는
    ///   `shouldShowRequestPermissionRationale`이 이 값을 준다. **iOS는 언제나 false다** —
    ///   호출부(`PermissionGateModel`)가 상수로 넘긴다.
    public func onPermissionResult(granted: Bool, canAskAgain: Bool) {
        if granted {
            state = .granted
        } else if canAskAgain {
            state = .denied
        } else {
            state = .permanentlyDenied
        }
    }

    /// 설정 앱에서 돌아왔을 때(iOS `scenePhase == .active`, 안드로이드 `ON_RESUME`)의 재확인 —
    /// 허용으로 바뀌었으면 재시작 없이 통과한다.
    ///
    /// 허용→회수 방향은 다루지 않는다: 설정에서 권한을 회수하면 OS가 프로세스를 재시작하므로
    /// 이 분기는 도달 불가고, 녹음 중 회수는 KAN-86 범위다.
    public func onReturnedToApp(granted: Bool) {
        if granted { state = .granted }
    }

    /// 프로세스 재시작 후 복원. 영구 거부가 재생성에 증발해 "설정 딥링크만" 제약을 잃으면
    /// 안 되지만, 저장 시점과 복원 시점의 실제 권한이 어긋날 수도 있어 대조한다: 실제로
    /// 허용돼 있으면 저장값과 무관하게 통과하고, 저장값이 ``MicPermissionState/granted``인데
    /// 실제로는 회수됐으면(프로세스 사망 중 설정 변경) 처음부터 다시 묻는다 — 이때 재요청
    /// 가능 여부는 알 수 없으므로 다음 요청 결과가 상태를 다시 판정한다.
    public static func restored(
        saved: MicPermissionState,
        currentlyGranted: Bool
    ) -> MicPermissionController {
        if currentlyGranted {
            return MicPermissionController(state: .granted)
        }
        if saved == .granted {
            return MicPermissionController(state: .rationale)
        }
        return MicPermissionController(state: saved)
    }
}
